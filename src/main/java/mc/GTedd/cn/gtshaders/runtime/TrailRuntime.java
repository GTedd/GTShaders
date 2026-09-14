package mc.GTedd.cn.gtshaders.runtime;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import mc.GTedd.cn.gtshaders.core.TrailSlot;

/**
 * 武器轨迹运行时：把第一人称手里那把刀的刀刃，解算成后处理每帧能读到的一条屏幕条带。
 *
 * <p>这是「刀光」的数据来源，另一半是 {@code GlslCodegen} 注入的 {@code gtTrail*} 系列 helper。
 * 与 {@link AnchorRuntime} 是同一条路子的两个用途，分工见 {@link TrailSlot} 的类注释。
 *
 * <h2>两级缓冲：先密后疏</h2>
 *
 * <p>采集端 {@link #capture} 每个渲染帧记一个原始样本进 {@link #RING} 个位置的环形缓冲；
 * 解算端 {@link #solve} 再从里面<b>按时间等距重采样</b>出 {@value TrailSlot#SLOTS} 个槽位。
 *
 * <p>直接把渲染帧一对一塞进槽位是不行的：那样拖尾覆盖的时间等于「槽位数 ÷ 帧率」，
 * 30Hz 下有 0.8 秒、240Hz 下只剩 0.1 秒——同一个效果在两台机器上会是两种东西。
 * 重采样之后，槽位永远铺满同一个 {@value #WINDOW} 秒的窗口，帧率只影响这条带子有多平滑。
 *
 * <h2>存绝对世界坐标</h2>
 *
 * <p>{@link #capture} 拿到的是刀刃在<b>相对相机</b>的坐标里的位置（见下面对 PoseStack 空间的
 * 说明），落进缓冲前先加上当帧相机位置变成绝对世界坐标。于是人一边跑一边挥刀时，
 * 已经画出去的那段拖尾会老老实实留在原地，而不是黏在屏幕上跟着人飘。
 *
 * <p>BladeFlash 为了同一件事写了一整段 {@code TURE_WORLDSPACE} 补偿——每帧把历史顶点
 * 按相机位移反向平移。它必须那么做，因为纯资源包只能把坐标编码进像素，而像素里存的是
 * 相机相对量。我们直接存世界量，这段补偿连同它的累积误差一起不存在。
 *
 * <h2>时间轴用编辑器的</h2>
 *
 * <p>和 {@link AnchorRuntime} 一样取 {@link PreviewRuntime#editorTime()}。暂停时它不推进，
 * {@link #capture} 也就不再收新样本——整条拖尾定格在暂停那一刻，可以逐帧去看
 * 「刀光第 0.1 秒是什么形状」。用墙钟的话暂停之后刀光会自己衰减光。
 */
public final class TrailRuntime {

    /**
     * 原始样本环形缓冲的容量。
     *
     * <p>要装得下高帧率下一整个 {@value #WINDOW} 秒窗口：240Hz × 0.5s = 120 帧，取 128。
     * 装不下的后果不是崩，是拖尾尾端被截断——而那种「只在高刷屏上短一截」的毛病最难查。
     */
    private static final int RING = 128;

    /**
     * 重采样窗口（秒）。槽位铺满的就是「现在往前数这么久」。
     *
     * <p>比任何效果实际会用的拖尾时长都长一些：着色器拿到的 {@code age} 是秒数，
     * 自己按参数裁掉尾巴即可。反过来（窗口比效果想要的短）就没法补救了。
     */
    private static final float WINDOW = 0.5f;

    /** 齐次坐标 w 小于它就认为在相机平面上或背后，透视除法不可信。与 {@link AnchorRuntime} 同一约定。 */
    private static final float W_EPSILON = 1e-4f;

    /** 相机背后的样本推到屏幕外多远（UV 单位）。够远就保证条带不会从边缘漏进画面。 */
    private static final float OFFSCREEN_PUSH = 4f;

    /** 刀根世界坐标，环形。 */
    private static final Vec3[] ringRoot = new Vec3[RING];
    /** 刀尖世界坐标，环形。 */
    private static final Vec3[] ringTip = new Vec3[RING];
    /** 采样时刻，取 {@link PreviewRuntime#editorTime()}。 */
    private static final float[] ringTime = new float[RING];
    /** 累计写入次数。倒数第 k 个样本落在 {@code (written - 1 - k) mod RING}。 */
    private static int written;

    private static TrailSlot[] slots = emptySlots();
    /** 当前挥砍动画进度 0..1，由 {@link #capture} 顺手记下——那一刻正好在渲染手。 */
    private static float swing;
    /** 最近一次成功采样的时刻。同一帧被调用多次时用它去重。 */
    private static float lastCaptureTime = Float.NEGATIVE_INFINITY;
    /**
     * 正在渲染的第一人称主手 {@code ItemStackRenderState}；不在那段时间里就是 null。
     *
     * <p>存成 {@code Object} 是刻意的：这个类只需要拿它做引用比对，不需要碰它的任何方法。
     * 声明成具体类型会让 {@code TrailRuntime} 平白依赖一个渲染内部类，而它是纯逻辑层。
     */
    private static @Nullable Object firstPersonMainHand;

    private TrailRuntime() {
    }

    // ---------------------------------------------------------------- 供 Mixin 调用

    /** 第一人称手部渲染开始。{@code mainHandState} 是这一帧主手物品的渲染状态对象。 */
    public static void beginFirstPerson(Object mainHandState) {
        firstPersonMainHand = mainHandState;
    }

    public static void endFirstPerson() {
        firstPersonMainHand = null;
    }

    /**
     * 这个渲染状态是不是「此刻正在画的第一人称主手物品」。
     *
     * <p>没有任何效果用到轨迹时直接返回 false——采集整条链于是在最外层就短路了，
     * 不去算矩阵、不去碰包围盒。绝大多数会话里这条注入的全部成本就是这一次判断。
     */
    public static boolean isFirstPersonMainHand(Object renderState) {
        return firstPersonMainHand != null
                && firstPersonMainHand == renderState
                && PreviewRuntime.usesTrails();
    }

    public static TrailSlot[] slots() {
        return slots;
    }

    public static float swing() {
        return swing;
    }

    /**
     * 丢掉全部历史。
     *
     * <p>{@link #capture} 在<b>时间轴回拨</b>时会调它，理由见那边。
     *
     * <p>「手里的东西没了」不走这条路：那种情况下 {@link #capture} 只是不再被调用，
     * 而 {@link #solve} 的窗口判据会让轨迹在 {@value #WINDOW} 秒内自然衰减干净——
     * 那正是刀光本来就该有的收尾，一刀切反而会让拖尾在收刀的瞬间硬生生断掉。
     */
    public static void clear() {
        written = 0;
        lastCaptureTime = Float.NEGATIVE_INFINITY;
        swing = 0f;
        slots = emptySlots();
    }

    /**
     * 采一帧刀刃位置。由 {@code ItemStackRenderStateMixin} 在第一人称主手物品提交渲染时调用。
     *
     * <h2>pose 是什么空间</h2>
     *
     * <p>{@code GameRenderer.renderItemInHand} 建的那个 {@code PoseStack} 是从
     * {@code cameraRenderState.viewRotationMatrix.invert()} 起步的，同时
     * {@code RenderSystem.getModelViewStack()} 被乘上了 {@code viewRotationMatrix}。
     * 于是最终送进管线的是
     * <pre>
     *   clip = Proj · viewRotation · pose · x
     *        = Proj · viewRotation · viewRotation⁻¹ · R · x
     *        = Proj · R · x
     * </pre>
     * ——中间那对逆矩阵抵消掉了。换句话说 <b>{@code pose · x} 本身就是「相对相机的世界坐标」</b>：
     * 世界轴向、以相机为原点。这正是 Mojang 让它从 {@code viewRotation⁻¹} 起步的原因，
     * {@code bobHurt} / {@code bobView} 那些晃动才好在世界轴上写。
     *
     * <p>所以这里不需要任何额外的坐标变换，加一个相机位置就是世界坐标。
     *
     * <h2>刀刃两端取模型包围盒的对角</h2>
     *
     * <p>{@code getModelBoundingBox()} 给的是这个物品模型在自己局部空间里的实际范围
     * （内部有缓存，每帧调用只是一次字段读取）。原版手持物品的贴图约定是<b>左下手柄、
     * 右上刀尖</b>，所以包围盒的 {@code (minX, minY)} 到 {@code (maxX, maxY)} 这条对角线
     * 就是刀刃。z 取中间那一层——物品是拉伸过的平面模型，前后两面对着色没有区别。
     *
     * <p>用包围盒而不是写死 {@code (-0.5,-0.5)→(0.5,0.5)}，是为了让短剑、三叉戟、
     * 自定义模型都能自适应：那些模型的顶点范围本来就不是满格。
     *
     * @param pose     物品提交渲染那一刻的 {@code PoseStack.last().pose()}
     * @param modelBox 物品模型在局部空间的包围盒
     */
    public static void capture(Matrix4f pose, AABB modelBox) {
        if (!PreviewRuntime.isPlaying()) {
            // 暂停时不收新样本，整条拖尾定格——逐帧调效果时要的就是这个
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.gameRenderer == null) {
            return;
        }
        float now = PreviewRuntime.editorTime();
        if (now == lastCaptureTime) {
            // 同一帧里被调了第二次（双持、多层模型）。第一次记的那个就是主手，后面的丢掉
            return;
        }
        if (now < lastCaptureTime) {
            // 时间轴被回拨了（编辑器的时间是可以往回拖的）。缓冲里的时间戳必须单调递减，
            // 否则 sampleAt 那个「从新往老找第一个不晚于目标时刻的样本」的搜索会在
            // 接缝处提前命中，插值出一段横跨整个屏幕的假条带。整段丢掉重攒最干净
            clear();
        }

        Vec3 camPos = mc.gameRenderer.mainCamera().position();
        float midZ = (float) ((modelBox.minZ + modelBox.maxZ) * 0.5);
        Vector3f root = pose.transformPosition(
                new Vector3f((float) modelBox.minX, (float) modelBox.minY, midZ));
        Vector3f tip = pose.transformPosition(
                new Vector3f((float) modelBox.maxX, (float) modelBox.maxY, midZ));

        if (root.distanceSquared(tip) < 1e-8f) {
            // 空模型的包围盒退化成一个点（getModelBoundingBox 对空模型返回零尺寸盒）。
            // 记下去只会是一条零长度的带子，白占一个槽位还让 speed 算出 NaN
            return;
        }

        int i = Math.floorMod(written, RING);
        ringRoot[i] = camPos.add(root.x, root.y, root.z);
        ringTip[i] = camPos.add(tip.x, tip.y, tip.z);
        ringTime[i] = now;
        written++;
        lastCaptureTime = now;

        if (mc.player != null) {
            swing = swingOf(mc.player, mc.getDeltaTracker().getGameTimeDeltaPartialTick(false));
        }
    }

    /**
     * 把缓冲里的原始样本重采样并投影成槽位。每渲染帧调用一次，结果直接写进 uniform。
     *
     * <p>槽位 0 是当前帧，往后每个槽位老 {@code WINDOW / (SLOTS - 1)} 秒。缓冲里还没攒够
     * 那么久的历史时，够不到的槽位留空——而不是把最老那个样本重复填满。重复填的话，
     * 刚拔出刀的第一帧就会有一条完整长度的拖尾糊在屏幕上。
     */
    public static TrailSlot[] solve() {
        TrailSlot[] out = emptySlots();
        Minecraft mc = Minecraft.getInstance();
        if (written == 0 || mc.level == null || mc.gameRenderer == null) {
            slots = out;
            return out;
        }

        float now = PreviewRuntime.editorTime();
        int newest = Math.floorMod(written - 1, RING);
        if (now - ringTime[newest] > WINDOW) {
            // 整个窗口里一帧都没采到：手里的东西没了或切了视角。留一条几秒前的旧刀光更糟
            slots = out;
            return out;
        }

        Camera cam = mc.gameRenderer.mainCamera();
        Vec3 camPos = cam.position();
        Matrix4f viewProj = cam.getViewRotationProjectionMatrix(new Matrix4f());
        Matrix4f view = cam.getViewRotationMatrix(new Matrix4f());
        float aspect = aspect(mc);

        float step = WINDOW / (TrailSlot.SLOTS - 1);
        // 以最新样本的时刻为基准往回走，而不是以 now。两者在正常情况下只差不到一帧，
        // 但暂停的那一刻 now 会停住而样本还是老的，以 now 为基准会让整条带子平白老一截
        float base = ringTime[newest];
        float prevTipU = 0f;
        float prevTipV = 0f;
        for (int i = 0; i < TrailSlot.SLOTS; i++) {
            float want = base - step * i;
            TrailSlot s = sampleAt(want, base, camPos, viewProj, view, aspect);
            if (s.isEmpty()) {
                // 一旦够不到就不必再往老里找了，后面只会更老
                break;
            }
            if (i > 0) {
                float du = (s.tipU() - prevTipU) * aspect;
                float dv = s.tipV() - prevTipV;
                s = new TrailSlot(s.rootU(), s.rootV(), s.tipU(), s.tipV(),
                        s.age(), s.depth(), s.valid(), (float) Math.sqrt(du * du + dv * dv));
            }
            prevTipU = s.tipU();
            prevTipV = s.tipV();
            out[i] = s;
        }
        slots = out;
        return out;
    }

    /**
     * 在原始样本之间线性插值出 {@code want} 时刻的刀刃，并投影到屏幕。
     *
     * <p>插值在<b>世界空间</b>做，投影在之后。反过来（先投影再插屏幕坐标）在样本跨越
     * 相机平面时会插出完全错误的位置。
     */
    private static TrailSlot sampleAt(float want, float base, Vec3 camPos,
                                      Matrix4f viewProj, Matrix4f view, float aspect) {
        int have = Math.min(written, RING);
        int newer = -1;
        for (int k = 0; k < have; k++) {
            int idx = Math.floorMod(written - 1 - k, RING);
            if (ringTime[idx] <= want) {
                // idx 是不晚于目标时刻的第一个样本，newer 是它前一个（更新的那个）
                Vec3 root;
                Vec3 tip;
                if (newer < 0) {
                    root = ringRoot[idx];
                    tip = ringTip[idx];
                } else {
                    float span = ringTime[newer] - ringTime[idx];
                    float f = span > 1e-6f ? (want - ringTime[idx]) / span : 0f;
                    root = lerp(ringRoot[idx], ringRoot[newer], f);
                    tip = lerp(ringTip[idx], ringTip[newer], f);
                }
                return project(root, tip, base - want, camPos, viewProj, view, aspect);
            }
            newer = idx;
        }
        // 缓冲里最老的样本都比目标时刻新——历史还没攒够这么长
        return TrailSlot.EMPTY;
    }

    private static Vec3 lerp(Vec3 a, Vec3 b, float f) {
        return new Vec3(a.x + (b.x - a.x) * f, a.y + (b.y - a.y) * f, a.z + (b.z - a.z) * f);
    }

    /**
     * 把一对世界坐标端点投影成一个槽位。
     *
     * <p>用的是<b>世界</b>的 view-projection，而手部实际渲染时用的是另一个 fov
     * （{@code getFov(camera, partialTick, false)}，不含疾跑/拉弓那些 fov 特效）。
     * 两者在默认状态下相等，开了速度效果时刀光会和刀身错开几个像素。
     * 换取的是：轨迹和 {@code gtAnchor} 落在同一个坐标系里，两种数据可以在同一个效果里混用，
     * 而 26.3 的投影矩阵已经只以 GPU buffer 的形式存在，CPU 侧根本读不回手部那一份。
     */
    private static TrailSlot project(Vec3 root, Vec3 tip, float age, Vec3 camPos,
                                     Matrix4f viewProj, Matrix4f view, float aspect) {
        float[] a = projectPoint(root, camPos, viewProj, view);
        float[] b = projectPoint(tip, camPos, viewProj, view);
        float depth = (a[2] + b[2]) * 0.5f;
        return new TrailSlot(a[0], a[1], b[0], b[1], age, depth, 1f, 0f);
    }

    /** @return {@code {u, v, depth}} */
    private static float[] projectPoint(Vec3 world, Vec3 camPos, Matrix4f viewProj, Matrix4f view) {
        double rx = world.x - camPos.x;
        double ry = world.y - camPos.y;
        double rz = world.z - camPos.z;
        Vector4f clip = viewProj.transform(new Vector4f((float) rx, (float) ry, (float) rz, 1f));
        if (clip.w <= W_EPSILON) {
            // 相机背后：透视除法会把它镜像到屏幕上。按视图空间的方向推到屏幕外，
            // 条带于是自然被裁掉，而不是在画面里出现一条镜像的假刀光
            Vector4f v = view.transform(new Vector4f((float) rx, (float) ry, (float) rz, 1f));
            float len = (float) Math.sqrt(v.x * v.x + v.y * v.y);
            float dx = len > 1e-5f ? v.x / len : 0f;
            float dy = len > 1e-5f ? v.y / len : -1f;
            return new float[]{0.5f + dx * OFFSCREEN_PUSH, 0.5f + dy * OFFSCREEN_PUSH, 1f};
        }
        return new float[]{
                (clip.x / clip.w) * 0.5f + 0.5f,
                (clip.y / clip.w) * 0.5f + 0.5f,
                Math.max(0f, Math.min(1f, (clip.z / clip.w) * 0.5f + 0.5f))
        };
    }

    private static float aspect(Minecraft mc) {
        int w = mc.getWindow().getWidth();
        int h = mc.getWindow().getHeight();
        return h > 0 ? (float) w / h : 1f;
    }

    /**
     * 挥砍进度 0..1。
     *
     * <p>不直接用 {@code isSwinging()} 的布尔值：刀光要跟着动画的曲线起落，
     * 而挥砍的前 1/3 才是真正在加速的那一段。
     */
    private static float swingOf(LivingEntity entity, float partialTick) {
        try {
            return Math.max(0f, Math.min(1f, entity.getSwingAnimation(partialTick)));
        } catch (RuntimeException e) {
            // 动画状态在切换物品的那一 tick 偶发不一致，不该让整帧挂掉
            return 0f;
        }
    }

    private static TrailSlot[] emptySlots() {
        TrailSlot[] out = new TrailSlot[TrailSlot.SLOTS];
        java.util.Arrays.fill(out, TrailSlot.EMPTY);
        return out;
    }
}
