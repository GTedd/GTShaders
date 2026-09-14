package mc.GTedd.cn.gtshaders.runtime;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import mc.GTedd.cn.gtshaders.core.AnchorBinding;
import mc.GTedd.cn.gtshaders.core.AnchorSlot;
import mc.GTedd.cn.gtshaders.core.EmitterSlot;
import mc.GTedd.cn.gtshaders.core.ShaderProject;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 锚点运行时：把世界里的实体/物品/坐标，解算成后处理着色器每帧能读到的屏幕锚点。
 *
 * <p>这是「让效果长在世界上而不是铺满屏幕」的那一半实现，另一半是 {@code GlslCodegen}
 * 注入的 {@code gtAnchor*} 系列 helper。
 *
 * <h2>两条节拍</h2>
 *
 * <p>事件采集和坐标解算<b>刻意分在不同的节拍上</b>：
 * <ul>
 *   <li>{@link #tick()} 跟着客户端 tick 走（20Hz）。死亡、受伤这些是<b>离散事件</b>，
 *       只在某一 tick 发生一次，多看几遍没有意义，漏看一遍才要命。</li>
 *   <li>{@link #solve()} 跟着渲染帧走（不限帧率）。屏幕坐标是<b>连续量</b>，
 *       按 20Hz 更新的话，转视角时锚点会明显地一跳一跳。</li>
 * </ul>
 *
 * <h2>时间轴用编辑器的，不用墙钟</h2>
 *
 * <p>生命周期进度是拿 {@link PreviewRuntime#editorTime()} 算的，那是编辑器自己驱动、
 * <b>可暂停可回拨</b>的时间。于是「按一下模拟触发、暂停、逐帧看死亡黑洞第 0.4 秒长什么样」
 * 是天然成立的——而这正是调这类一次性效果时唯一有效的办法。用墙钟的话效果一闪就过去了，
 * 只能靠反复触发去碰运气。
 *
 * <h2>为什么解算放 Java 侧而不是着色器侧</h2>
 *
 * <p>见 {@link AnchorSlot} 的类注释：本工程是 mod，每帧本来就在往 uniform 缓冲写字节，
 * 不需要 VanillaDI 那套「把数据画进像素再解码」的绕行。
 */
public final class AnchorRuntime {

    /** 相机背后的锚点推到屏幕外多远（UV 单位）。够远就保证任何有限半径的效果都画不到屏幕上。 */
    private static final float OFFSCREEN_PUSH = 4f;
    /** 齐次坐标 w 小于它就认为在相机平面上或背后，透视除法不可信。 */
    private static final float W_EPSILON = 1e-4f;
    /** 事件存活期间的强度下限，保证 {@code gtAnchorValid} 不会在缓动曲线的两端翻假。 */
    private static final float LIVE_FLOOR = 1e-3f;

    /**
     * 视线落点的默认射程（方块）。
     *
     * <p>刻意远远超过玩家的交互距离：{@link AnchorBinding.Source#LOOK_HIT} 的语义是
     * 「准星指着的那个地方」，而人在用它的时候看的多半是几十格外的地面或墙面——
     * 「看着远处打一发天降激光」正是这个来源存在的理由。
     *
     * <p>上限不放到 {@code maxDistance} 允许的 512，是因为常驻触发的绑定每帧都要射一条线，
     * 而 128 格之外的落点在屏幕上早就退化成一个点了，再远只是白烧 DDA 遍历。
     */
    private static final double LOOK_HIT_RANGE = 128.0;

    /**
     * 一个正在播放的事件锚点。
     *
     * @param follow    跟随的实体；{@code null} 表示钉死在 {@link #pos}
     * @param startTime 触发时刻，取自 {@link PreviewRuntime#editorTime()}
     */
    private static final class Event {
        final @Nullable UUID follow;
        Vec3 pos;
        final float startTime;
        final float radius;

        Event(@Nullable UUID follow, Vec3 pos, float startTime, float radius) {
            this.follow = follow;
            this.pos = pos;
            this.startTime = startTime;
            this.radius = radius;
        }
    }

    private static @Nullable ShaderProject project;
    /**
     * 每条绑定各自的事件队列。
     *
     * <p>用 {@link IdentityHashMap} 而不是普通 HashMap：{@link AnchorBinding} 没有也不该有
     * 值语义的 equals——两条配置完全相同的绑定是两条不同的绑定，各自的事件不能混在一起。
     */
    private static final Map<AnchorBinding, Deque<Event>> EVENTS = new IdentityHashMap<>();
    /** 上一 tick 见过的实体，用来认出 {@code ON_SPAWN}。 */
    private static Set<UUID> seenEntities = new HashSet<>();
    private static AnchorSlot[] slots = emptySlots();
    /**
     * 与 {@link #slots} <b>逐槽对齐</b>的载体扩展。
     *
     * <p>分成两个数组而不是把字段并进 {@link AnchorSlot}，是因为绝大多数效果只要位置：
     * 并进去的话每个用了 {@code gtAnchor} 的着色器都要白付 16 个 vec4，
     * 而 uniform 块的布局是编译期定死的，没法按用途裁剪。
     */
    private static EmitterSlot[] emitters = emptyEmitters();

    private AnchorRuntime() {
    }

    private static EmitterSlot[] emptyEmitters() {
        EmitterSlot[] out = new EmitterSlot[AnchorSlot.SLOTS];
        java.util.Arrays.fill(out, EmitterSlot.EMPTY);
        return out;
    }

    private static AnchorSlot[] emptySlots() {
        AnchorSlot[] out = new AnchorSlot[AnchorSlot.SLOTS];
        java.util.Arrays.fill(out, AnchorSlot.EMPTY);
        return out;
    }

    /**
     * 换一个当前工程。持有的是<b>活对象</b>而不是快照：界面上改绑定要立刻反映到画面，
     * 而改绑定不该触发重编译（锚点是纯 uniform 路径）。
     */
    public static void setProject(@Nullable ShaderProject p) {
        if (project != p) {
            project = p;
            EVENTS.clear();
            slots = emptySlots();
            emitters = emptyEmitters();
        }
    }

    public static @Nullable ShaderProject project() {
        return project;
    }

    /** 最近一次解算的结果。界面画锚点准星用它，不必自己再算一遍。 */
    /** 与 {@link #slots()} 逐槽对齐的载体扩展。 */
    public static EmitterSlot[] emitters() {
        return emitters;
    }

    public static AnchorSlot[] slots() {
        return slots;
    }

    /** 工程里是否有启用的绑定。没有的话整条锚点路径都可以跳过。 */
    public static boolean hasBindings() {
        if (project == null) {
            return false;
        }
        for (AnchorBinding b : project.anchors()) {
            if (b.isEnabled()) {
                return true;
            }
        }
        return false;
    }

    // ---------------------------------------------------------------- tick：事件采集

    /**
     * 每客户端 tick 调一次：采集新触发的事件、淘汰过期的事件。
     *
     * <p>不做任何投影计算——那是 {@link #solve()} 的事。
     */
    public static void tick() {
        ShaderProject p = project;
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (p == null || level == null) {
            return;
        }

        // 工程被替换（载入、快照回退）之后，老绑定的事件队列就是垃圾了，清掉
        EVENTS.keySet().retainAll(p.anchors());

        Set<UUID> nowSeen = new HashSet<>();
        List<Entity> entities = new ArrayList<>();
        for (Entity e : level.entitiesForRendering()) {
            entities.add(e);
            nowSeen.add(e.getUUID());
        }

        float now = PreviewRuntime.editorTime();
        for (AnchorBinding b : p.anchors()) {
            if (!b.isEnabled()) {
                continue;
            }
            expire(b, now);
            if (b.trigger().isEvent() && b.trigger() != AnchorBinding.Trigger.MANUAL) {
                collect(b, entities, mc, now);
            }
        }
        seenEntities = nowSeen;
    }

    /** 淘汰跑完生命周期的事件。常驻绑定的事件永不过期。 */
    private static void expire(AnchorBinding b, float now) {
        Deque<Event> q = EVENTS.get(b);
        if (q == null || b.isPersistent()) {
            return;
        }
        q.removeIf(ev -> now - ev.startTime > b.duration());
    }

    /** 扫描这一 tick 有没有目标触发了这条绑定。 */
    private static void collect(AnchorBinding b, List<Entity> entities, Minecraft mc, float now) {
        for (Entity e : entities) {
            if (!matches(b, e, mc)) {
                continue;
            }
            if (fired(b.trigger(), e)) {
                push(b, e, now);
            }
        }
    }

    /**
     * 这一 tick 该触发器是否在这个实体上点亮了。
     *
     * <p>三个判据都是客户端本来就同步到的字段，不需要任何服务端配合：
     * <ul>
     *   <li>{@code deathTime} 在实体死后每 tick 自增，所以 {@code == 1} 恰好是死亡后的第一 tick</li>
     *   <li>{@code hurtTime} 受伤时被重置成 {@code hurtDuration} 然后逐 tick 递减，
     *       所以两者相等就是挨打的那一 tick；连续挨打会再次相等，于是能连续触发</li>
     *   <li>出生靠和上一 tick 的实体集合求差</li>
     * </ul>
     */
    private static boolean fired(AnchorBinding.Trigger trigger, Entity e) {
        return switch (trigger) {
            case ON_DEATH -> e instanceof LivingEntity le && le.deathTime == 1;
            case ON_HURT -> e instanceof LivingEntity le && le.hurtTime > 0 && le.hurtTime == le.hurtDuration;
            case ON_SPAWN -> !seenEntities.isEmpty() && !seenEntities.contains(e.getUUID());
            case ALWAYS, MANUAL -> false;
        };
    }

    private static void push(AnchorBinding b, @Nullable Entity e, float now) {
        Deque<Event> q = EVENTS.computeIfAbsent(b, k -> new ArrayDeque<>());
        Vec3 pos = e == null ? Vec3.ZERO : anchorPos(e, b, 1f);
        // stick 的事件钉死坐标；跟随的记下 uuid，每帧再去问它现在在哪
        q.addLast(new Event(b.isStick() || e == null ? null : e.getUUID(), pos, now, b.worldRadius()));
        // 槽位就那么多，超了淘汰最老的——新发生的事件更值得看见
        while (q.size() > b.maxSlots()) {
            q.removeFirst();
        }
    }

    /**
     * 手动触发一条绑定，供编辑器的「模拟触发」按钮调用。
     *
     * <p>位置按绑定自己的来源解算：绑的是视线落点就打在准星指着的地方，绑的是某个实体就打在它身上。
     * 解不出目标时退回玩家脚下——总得让人看见点什么，否则按下去毫无反馈，
     * 分不清是「没配对」还是「按钮坏了」。
     *
     * @return 是否真的点亮了
     */
    public static boolean simulate(AnchorBinding b) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || b == null) {
            return false;
        }
        float now = PreviewRuntime.editorTime();
        List<Target> targets = resolve(b, mc);
        if (targets.isEmpty()) {
            Vec3 fallback = mc.player != null ? mc.player.position() : Vec3.ZERO;
            Deque<Event> q = EVENTS.computeIfAbsent(b, k -> new ArrayDeque<>());
            q.addLast(new Event(null, fallback.add(0, b.yOffset(), 0), now, b.worldRadius()));
            while (q.size() > b.maxSlots()) {
                q.removeFirst();
            }
            return true;
        }
        for (Target t : targets) {
            Deque<Event> q = EVENTS.computeIfAbsent(b, k -> new ArrayDeque<>());
            q.addLast(new Event(b.isStick() ? null : t.uuid(), t.pos(), now, b.worldRadius()));
            while (q.size() > b.maxSlots()) {
                q.removeFirst();
            }
        }
        return true;
    }

    /** 清掉一条绑定所有正在播放的事件。 */
    public static void stop(AnchorBinding b) {
        EVENTS.remove(b);
    }

    public static void clearEvents() {
        EVENTS.clear();
    }

    // ---------------------------------------------------------------- 目标解算

    /**
     * 一个候选目标。
     *
     * @param uuid 目标实体；固定坐标类的来源为 {@code null}
     */
    private record Target(Vec3 pos, @Nullable UUID uuid, double distSq, @Nullable Vec3 look) {
    }

    /**
     * 按绑定的来源去世界里找目标，已按到相机的距离排序。
     *
     * <p>排序是必须的：{@code ENTITY_TYPE} 可能匹配到十几个僵尸而槽位只有几个，
     * 按距离取前几个才是符合直觉的选择——远处那只被截掉没人会注意，
     * 而如果按遍历顺序取，视野正中那只随时可能因为区块加载顺序变了就消失。
     */
    private static List<Target> resolve(AnchorBinding b, Minecraft mc) {
        List<Target> out = new ArrayList<>();
        ClientLevel level = mc.level;
        if (level == null) {
            return out;
        }
        Vec3 camPos = mc.gameRenderer.mainCamera().position();
        float partial = partialTick(mc);

        switch (b.source()) {
            case BLOCK_POS -> {
                double[] xyz = AnchorBinding.parseBlockPos(b.selector());
                if (xyz != null) {
                    // 方块坐标指的是方块本身，落到它的中心才是人想要的位置
                    Vec3 p = new Vec3(xyz[0] + 0.5, xyz[1] + b.yOffset(), xyz[2] + 0.5);
                    out.add(new Target(p, null, p.distanceToSqr(camPos), null));
                }
            }
            case LOOK_HIT -> {
                Vec3 p = lookHit(mc, b, partial);
                if (p != null) {
                    // distSq 记 0：射程已经由射线长度表达过一次，再按到相机的距离筛一遍是重复的，
                    // 而第三人称下相机比眼睛还退后几格，一百格外的落点会被 maxDistance 自己误伤掉
                    out.add(new Target(p, null, 0, null));
                }
            }
            case SELF -> {
                if (mc.player != null) {
                    add(out, mc.player, b, partial, camPos);
                }
            }
            case LOOK_AT -> {
                // 先用原版的准星拾取——它和玩家看到的高亮框是同一个判据，近处最符合直觉。
                // 但它的射程是玩家的<b>交互距离</b>（三四格），准星明明指着五格外那只怪，
                // 它却返回 null。于是「看着它按一下」在稍远一点的地方就静默失效，
                // 而画面上没有任何东西说明为什么。够不着就换我们自己那条 128 格的射线
                Entity e = mc.crosshairPickEntity;
                if (e == null) {
                    Pick pick = pick(mc, LOOK_HIT_RANGE, partial);
                    e = pick == null ? null : pick.entity();
                }
                if (e != null) {
                    add(out, e, b, partial, camPos);
                }
            }
            default -> {
                for (Entity e : level.entitiesForRendering()) {
                    if (matches(b, e, mc)) {
                        add(out, e, b, partial, camPos);
                    }
                }
            }
        }

        out.sort(Comparator.comparingDouble(Target::distSq));
        double max = b.maxDistance();
        if (max > 0) {
            double maxSq = max * max;
            out.removeIf(t -> t.distSq() > maxSq);
        }
        while (out.size() > b.maxSlots()) {
            out.remove(out.size() - 1);
        }
        return out;
    }

    /**
     * 这条绑定<b>此刻</b>的候选目标世界坐标，给绑定模式的 3D 标记用。
     *
     * <p>走的是解算用的同一个 {@link #resolve}，所以框画在哪儿、效果就会出现在哪儿。
     * 另写一份「大概在这附近」的近似，会让这个工具在最需要它的时候（配错了、找不到目标）
     * 反而给出误导。
     *
     * <p>对事件型绑定，返回的是「一旦触发会打在哪」的候选位置，而不是「现在正在播的那些」。
     * 那正是配绑定时要确认的东西——效果播的时候人往往顾不上看框。
     */
    public static List<Vec3> previewPositions(AnchorBinding b, Minecraft mc) {
        List<Vec3> out = new ArrayList<>();
        for (Target t : resolve(b, mc)) {
            out.add(t.pos());
        }
        return out;
    }

    private static void add(List<Target> out, Entity e, AnchorBinding b, float partial, Vec3 camPos) {
        Vec3 p = anchorPos(e, b, partial);
        // 视线向量只在真要用朝向时才取：getViewVector 每次都要算两组三角函数，
        // 而绝大多数绑定是圆对称效果，根本不看朝向
        Vec3 look = b.usesFacing() ? e.getViewVector(partial) : null;
        out.add(new Target(p, e.getUUID(), p.distanceToSqr(camPos), look));
    }

    /**
     * 从玩家眼睛沿视线射一条线，返回打中的那个点。
     *
     * <p><b>不能用 {@code mc.hitResult}</b>：那是原版为「能不能挖到 / 能不能右键」算的，
     * 射程就是玩家的方块交互距离（几格），准星一指远处地面就是 {@code MISS}。
     * 于是这条绑定解不出目标，{@link #simulate} 退回玩家脚下——症状就是
     * 「按下触发，效果炸在自己脚底下，而不是准星指着的地方」。
     *
     * <p>打空（对着天空）时返回射程末端而不是 null：视角落点在这种情况下仍然是有方向的，
     * 让效果出现在天上远处，比让它跳回脚下更接近作者按下那一键时想看到的东西。
     *
     * @return 已经加过 {@code yOffset} 的世界坐标；没有玩家或世界时返回 null
     */
    private static @Nullable Vec3 lookHit(Minecraft mc, AnchorBinding b, float partial) {
        double range = b.maxDistance() > 0
                ? Math.min(b.maxDistance(), LOOK_HIT_RANGE)
                : LOOK_HIT_RANGE;
        Pick pick = pick(mc, range, partial);
        return pick == null ? null : pick.point().add(0, b.yOffset(), 0);
    }

    /**
     * 一次准星解析的结果。
     *
     * @param entity 打中的实体；没打中实体是 null
     * @param block  打中的方块坐标；没打中方块是 null
     * @param point  最终落点世界坐标。<b>永远非 null</b>——打空时是射程末端，
     *               因为「对着天空按一下」仍然是一个有方向的意图
     */
    public record Pick(@Nullable Entity entity, @Nullable BlockPos block, Vec3 point) {

        /** 准星什么都没指着——落点是射程末端那个凭空的点。 */
        public boolean isMiss() {
            return entity == null && block == null;
        }
    }

    /**
     * 按 {@link AnchorBinding.Source#LOOK_HIT} 的判据解析准星指着什么。
     *
     * <p>绑定模式和事件解算走的是<b>同一个方法</b>，这一点很重要：绑定模式在世界里画出来的
     * 那个落点标记，必须和按下去之后效果真正出现的位置是同一个。两边各写一遍射线，
     * 迟早会在「实体优先还是方块优先」「打空怎么办」这些细节上分叉，
     * 而那种分叉表现出来就是「预览时明明对着那儿，一按却打偏了」。
     *
     * @param range   射程（方块）
     * @param partial 渲染插值，tick 里调传 0
     */
    public static @Nullable Pick pick(Minecraft mc, double range, float partial) {
        ClientLevel level = mc.level;
        Entity viewer = mc.player;
        if (level == null || viewer == null) {
            return null;
        }
        Vec3 eye = viewer.getEyePosition(partial);
        Vec3 dir = viewer.getViewVector(partial);
        Vec3 end = eye.add(dir.scale(range));

        Vec3 hit = end;
        BlockPos blockPos = null;
        try {
            // OUTLINE 而不是遮挡测试用的 VISUAL：这里问的是「准星停在哪个面上」，
            // 判据该和原版高亮方块的那个线框一致
            BlockHitResult block = level.clip(new ClipContext(eye, end,
                    ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, viewer));
            if (block.getType() != HitResult.Type.MISS) {
                hit = block.getLocation();
                blockPos = block.getBlockPos();
                // 实体检测不该越过挡在前面的墙——把终点收到方块面上
                end = hit;
            }
        } catch (RuntimeException e) {
            // 同 occlusion()：区块边界上偶发的异常不该让一次触发整个失败，当作没打中方块
        }

        AABB sweep = viewer.getBoundingBox().expandTowards(dir.scale(range)).inflate(1.0);
        EntityHitResult ent = ProjectileUtil.getEntityHitResult(level, viewer, eye, end, sweep,
                e -> !e.isSpectator() && e.isPickable(), 0f);
        if (ent != null) {
            // 打在实体身上时落点取交点，和原版准星「指着谁」的判据一致
            hit = ent.getLocation();
            // 实体挡在方块前面，方块就不算被指着了——否则「看着怪按一下」会绑到它背后的墙
            return new Pick(ent.getEntity(), null, hit);
        }
        return new Pick(null, blockPos, hit);
    }

    /** 默认射程（{@value #LOOK_HIT_RANGE} 格）的准星解析。 */
    public static @Nullable Pick pick(Minecraft mc) {
        return pick(mc, LOOK_HIT_RANGE, 0f);
    }

    /**
     * 实体身上的锚点位置：插值过的脚部坐标，再抬 {@code yOffset}。
     *
     * <p>手动插值而不是直接用 {@code position()}：后者是 tick 位置，20Hz 更新。
     * 锚点是要投影到屏幕上的，用 tick 位置的话，跟着一个走动的实体时锚点会明显地一跳一跳，
     * 而画面本身是插值过的——效果和它要附着的东西对不上，比效果不动还难看。
     */
    private static Vec3 anchorPos(Entity e, AnchorBinding b, float partial) {
        double x = e.xOld + (e.getX() - e.xOld) * partial;
        double y = e.yOld + (e.getY() - e.yOld) * partial;
        double z = e.zOld + (e.getZ() - e.zOld) * partial;
        return new Vec3(x, y + b.yOffset(), z);
    }

    private static float partialTick(Minecraft mc) {
        try {
            return mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);
        } catch (RuntimeException e) {
            return 1f;
        }
    }

    /** 这个实体符不符合绑定的筛选条件。 */
    private static boolean matches(AnchorBinding b, Entity e, Minecraft mc) {
        return switch (b.source()) {
            case SELF -> e == mc.player;
            case LOOK_AT -> e == mc.crosshairPickEntity;
            case ENTITY_UUID -> {
                String sel = b.selector();
                yield !sel.isEmpty() && e.getUUID().toString().equalsIgnoreCase(sel);
            }
            case ENTITY_TYPE -> {
                String want = AnchorBinding.normalizeId(b.selector());
                // 选择器留空 = 匹配全部生物。和 ITEM_ENTITY 的「留空匹配全部」一致，
                // 而且这让「随便打死点什么就能看见效果」成为默认体验——
                // 要求先准确打出 minecraft:zombie 才有反应，等于把第一次尝试全挡在门外
                yield want.isEmpty() ? e instanceof LivingEntity : want.equals(typeId(e));
            }
            case ITEM_ENTITY -> {
                if (!(e instanceof ItemEntity item)) {
                    yield false;
                }
                String want = AnchorBinding.normalizeId(b.selector());
                // 选择器留空 = 匹配全部掉落物，这是「掉落物都发光」这类需求最短的写法
                yield want.isEmpty() || want.equals(itemId(item.getItem()));
            }
            case HELD_ITEM -> {
                if (!(e instanceof LivingEntity le)) {
                    yield false;
                }
                String want = AnchorBinding.normalizeId(b.selector());
                if (want.isEmpty()) {
                    yield false;
                }
                yield want.equals(itemId(le.getMainHandItem()))
                        || want.equals(itemId(le.getOffhandItem()));
            }
            // 这两个来源没有实体，靠 resolve 直接给坐标，不参与实体扫描
            case BLOCK_POS, LOOK_HIT -> false;
        };
    }

    private static String typeId(Entity e) {
        var key = BuiltInRegistries.ENTITY_TYPE.getKey(e.getType());
        return key == null ? "" : key.toString();
    }

    private static String itemId(ItemStack stack) {
        if (stack.isEmpty()) {
            return "";
        }
        var key = BuiltInRegistries.ITEM.getKey(stack.getItem());
        return key == null ? "" : key.toString();
    }

    // ---------------------------------------------------------------- solve：每帧投影

    /**
     * 把所有启用的绑定解算成槽位数组。每帧调用，结果直接写进 uniform。
     *
     * <p>槽位按绑定在列表里的顺序依次分配，与 {@link ShaderProject#anchorSlotBase} 保持一致——
     * 两边算错一个就会让作者写的 {@code gtAnchor(2)} 指到别的东西上，而画面上不会有任何报错。
     */
    public static AnchorSlot[] solve() {
        AnchorSlot[] out = emptySlots();
        EmitterSlot[] fx = emptyEmitters();
        ShaderProject p = project;
        Minecraft mc = Minecraft.getInstance();
        if (p == null || mc.level == null || mc.gameRenderer == null) {
            slots = out;
            emitters = fx;
            return out;
        }

        Camera cam = mc.gameRenderer.mainCamera();
        Vec3 camPos = cam.position();
        Matrix4f viewProj = cam.getViewRotationProjectionMatrix(new Matrix4f());
        Matrix4f view = cam.getViewRotationMatrix(new Matrix4f());
        // 竖直方向的半视角正切，用来把世界半径换算成屏幕半径
        float tanHalfFov = (float) Math.tan(Math.toRadians(cam.getFov()) * 0.5);
        float now = PreviewRuntime.editorTime();
        float partial = partialTick(mc);

        int slot = 0;
        for (AnchorBinding b : p.anchors()) {
            if (!b.isEnabled() || slot >= AnchorSlot.SLOTS) {
                continue;
            }
            int limit = Math.min(b.maxSlots(), AnchorSlot.SLOTS - slot);
            if (b.trigger() == AnchorBinding.Trigger.ALWAYS) {
                solveAlways(b, mc, out, fx, slot, limit, camPos, viewProj, view, tanHalfFov, now);
            } else {
                solveEvents(b, mc, out, fx, slot, limit, camPos, viewProj, view, tanHalfFov, now, partial);
            }
            // 不管这一次实际写了几个，都按 maxSlots 推进：槽位分配必须是静态的，
            // 否则「视野里暂时没有僵尸」就会让后面所有绑定的槽位号整体前移，
            // 作者源码里写死的 gtAnchor(2) 会静悄悄指到别的东西上
            slot += b.maxSlots();
        }
        slots = out;
        emitters = fx;
        return out;
    }

    private static void solveAlways(AnchorBinding b, Minecraft mc, AnchorSlot[] out, EmitterSlot[] fx,
                                    int base, int limit,
                                    Vec3 camPos, Matrix4f viewProj, Matrix4f view, float tanHalfFov,
                                    float now) {
        List<Target> targets = resolve(b, mc);
        int n = Math.min(targets.size(), limit);
        for (int i = 0; i < n; i++) {
            Target t = targets.get(i);
            out[base + i] = project(t.pos(), b, b.strength(), 1f, b.worldRadius(),
                    mc, camPos, viewProj, view, tanHalfFov);
            fx[base + i] = projectEmitter(t.pos(), t.look(), b, camPos, viewProj, now);
        }
    }

    private static void solveEvents(AnchorBinding b, Minecraft mc, AnchorSlot[] out, EmitterSlot[] fx,
                                    int base, int limit,
                                    Vec3 camPos, Matrix4f viewProj, Matrix4f view, float tanHalfFov,
                                    float now, float partial) {
        Deque<Event> q = EVENTS.get(b);
        if (q == null || q.isEmpty()) {
            return;
        }
        int i = 0;
        for (Event ev : q) {
            if (i >= limit) {
                break;
            }
            float life = b.isPersistent() ? 1f
                    : Math.max(0f, Math.min(1f, (now - ev.startTime) / Math.max(b.duration(), 1e-4f)));
            // 缓动曲线在两端会正好取到 0（EASE_IN 的 life=0、PULSE 的 life=0 与 1），
            // 而 strength == 0 是「这个槽位是空的」的<b>唯一</b>判据——于是事件的第一帧和
            // 最后一帧会被判成没有锚点，效果突然掉回退化形态（跳到备用屏幕坐标上闪一下）。
            // 事件还活着就给一个下限，让 gtAnchorValid 保持为真；想淡出的作者自己乘 strength。
            // 作者把基准强度调成 0 是明确的「关掉」，那种情况不兜底。
            float raw = b.strengthAt(life);
            float strength = b.strength() > 0f ? Math.max(raw, LIVE_FLOOR) : raw;
            Vec3 pos = ev.pos;
            Vec3 look = null;
            if (ev.follow != null) {
                Entity e = findEntity(mc, ev.follow);
                if (e != null) {
                    if (b.usesFacing()) {
                        look = e.getViewVector(partial);
                    }
                    // 跟随目标还活着就更新坐标；已经不在了就停在最后见到的地方，
                    // 而不是让效果凭空消失——尸体消失和效果结束是两件事
                    pos = anchorPos(e, b, partial);
                    ev.pos = pos;
                }
            }
            out[base + i] = project(pos, b, strength, life, ev.radius,
                    mc, camPos, viewProj, view, tanHalfFov);
            fx[base + i] = projectEmitter(pos, look, b, camPos, viewProj, now);
            i++;
        }
    }

    /**
     * 解算一个槽位的载体扩展：朝向投影 + 类型 + 自定义值 + 自转相位。
     *
     * <p>朝向的屏幕方向用<b>投影两个点求差</b>，而不是把方向向量丢进视图矩阵取 xy。
     * 后者算的是「方向在视图空间的投影」，忽略了透视——载体离屏幕中心越远、
     * 偏差越大，一根指向正前方的聚光在画面边缘会歪出十几度。
     *
     * <p>{@code AnchorBinding.Facing.NONE} 时整段跳过，只填类型和自定义值。
     * 圆对称的效果占了库里的绝大多数，不该为它们每帧算两组三角函数加四次矩阵乘法。
     */
    private static EmitterSlot projectEmitter(Vec3 world, @Nullable Vec3 look, AnchorBinding b,
                                              Vec3 camPos, Matrix4f viewProj, float now) {
        float type = b.emitterType();
        float spin = 0f;
        if (b.spin() != 0f) {
            // 先对 360 度取模再转弧度：编辑器跑上几小时后 spin*now 会大到 float 尾数
            // 吃不下一度的变化，表现是转速越来越"卡"直到完全停住
            spin = (float) Math.toRadians((b.spin() * now) % 360f);
        }
        if (!b.usesFacing()) {
            return new EmitterSlot(0f, 0f, 0f, 0f, type, b.custom1(), b.custom2(), spin);
        }

        Vec3 toCam = camPos.subtract(world);
        double toCamLen = toCam.length();
        Vec3 toCamDir = toCamLen > 1e-6 ? toCam.scale(1.0 / toCamLen) : new Vec3(0, 0, 1);
        Vec3 fwd = forwardOf(b, look, toCamDir);

        float facing = (float) fwd.dot(toCamDir);

        // 上向量：先用世界 up 求出右向量，再叉回来。前向量正好竖直时那次叉乘会退化，
        // 换一个参考轴——否则俯视/仰视的聚光 roll 会是 NaN，整块 uniform 跟着废掉
        Vec3 right = fwd.cross(new Vec3(0, 1, 0));
        if (right.lengthSqr() < 1e-8) {
            right = fwd.cross(new Vec3(0, 0, 1));
        }
        Vec3 up = right.normalize().cross(fwd).normalize();

        float aspect = aspectOf();
        float[] origin = toUv(world.subtract(camPos), viewProj);
        if (origin == null) {
            // 载体在相机背后：屏幕方向没有意义。锚点那边已经把它推到屏幕外了，
            // 这里给零方向，效果自然什么都画不出来
            return new EmitterSlot(0f, 0f, facing, 0f, type, b.custom1(), b.custom2(), spin);
        }
        // 半格远：够短，短到透视畸变可以忽略；又够长，长到不会被投影的浮点误差淹掉
        float[] dir = screenDir(world, fwd.scale(0.5), camPos, viewProj, origin, aspect);
        float[] upDir = screenDir(world, up.scale(0.5), camPos, viewProj, origin, aspect);
        float roll = upDir == null ? 0f : (float) Math.atan2(upDir[1], upDir[0]);

        return new EmitterSlot(dir == null ? 0f : dir[0], dir == null ? 0f : dir[1],
                facing, roll, type, b.custom1(), b.custom2(), spin);
    }

    /** 按朝向模式给出世界空间的前向量，必定是单位向量。 */
    private static Vec3 forwardOf(AnchorBinding b, @Nullable Vec3 look, Vec3 toCamDir) {
        return switch (b.facing()) {
            // 目标身上没有视线（固定坐标、方块）时退回面板上填的角度，
            // 而不是给一个零向量——那会让 roll 和 dir 一起变成 NaN
            case TARGET -> look != null ? look.normalize() : fromYawPitch(b.yaw(), b.pitch());
            case FIXED -> fromYawPitch(b.yaw(), b.pitch());
            case CAMERA -> toCamDir;
            default -> fromYawPitch(b.yaw(), b.pitch());
        };
    }

    /** Minecraft 的角度约定：yaw 0 朝南(+Z)、顺时针增；pitch 正值朝下。 */
    private static Vec3 fromYawPitch(float yawDeg, float pitchDeg) {
        double yaw = Math.toRadians(yawDeg);
        double pitch = Math.toRadians(pitchDeg);
        double cosPitch = Math.cos(pitch);
        return new Vec3(-Math.sin(yaw) * cosPitch, -Math.sin(pitch), Math.cos(yaw) * cosPitch);
    }

    /**
     * 把世界点投影成 UV。
     *
     * @param rel 相对相机的位置
     * @return {@code {u, v}}；在相机平面上或背后时返回 {@code null}
     */
    private static float @Nullable [] toUv(Vec3 rel, Matrix4f viewProj) {
        Vector4f clip = viewProj.transform(
                new Vector4f((float) rel.x, (float) rel.y, (float) rel.z, 1f));
        if (clip.w <= W_EPSILON) {
            return null;
        }
        return new float[]{(clip.x / clip.w) * 0.5f + 0.5f, (clip.y / clip.w) * 0.5f + 0.5f};
    }

    /**
     * 从载体位置沿 {@code offset} 走一小段，算这一段在屏幕上指向哪。
     *
     * @return 归一化的等比屏幕方向；那一小段落在相机背后或短到量不出来时返回 {@code null}
     */
    private static float @Nullable [] screenDir(Vec3 world, Vec3 offset, Vec3 camPos,
                                                Matrix4f viewProj, float[] origin, float aspect) {
        float[] tip = toUv(world.add(offset).subtract(camPos), viewProj);
        if (tip == null) {
            return null;
        }
        float du = (tip[0] - origin[0]) * aspect;
        float dv = tip[1] - origin[1];
        float len = (float) Math.sqrt(du * du + dv * dv);
        if (len < 1e-6f) {
            // 方向几乎正对/背对相机，在屏幕上缩成一个点。此时方向确实无从谈起，
            // 效果该靠 facing 去分辨，而不是拿一个被浮点噪声决定的随机方向
            return null;
        }
        return new float[]{du / len, dv / len};
    }

    private static float aspectOf() {
        Minecraft mc = Minecraft.getInstance();
        int w = mc.getWindow().getWidth();
        int h = mc.getWindow().getHeight();
        return h > 0 ? (float) w / h : 1f;
    }

    private static @Nullable Entity findEntity(Minecraft mc, UUID id) {
        if (mc.level == null) {
            return null;
        }
        for (Entity e : mc.level.entitiesForRendering()) {
            if (e.getUUID().equals(id)) {
                return e;
            }
        }
        return null;
    }

    /**
     * 把一个世界坐标投影成槽位。
     *
     * <p>屏幕半径用解析式而不是「再投影一个偏移点然后量距离」：一个世界半径 {@code R} 的球
     * 在距离 {@code d} 处占据的纵向屏幕比例是 {@code R / (d · tan(fov/2))}，
     * 而 NDC 的纵向半程是 1、UV 是 NDC 的一半，所以 UV 半径就是 {@code R / (2d·tan(fov/2))}。
     * 再投影一个点在近处会因为透视畸变给出不对称的结果，解析式则处处稳定。
     *
     * <p>这个半径的单位是<b>纵向 UV</b>，刻意对齐库效果里那句
     * {@code vec2 asp = vec2(OutSize.x / OutSize.y, 1.0)}——在 {@code (texCoord - center) * asp}
     * 这个等比空间里，半径可以直接拿去和 {@code length(d)} 比大小，作者不用再换算一次。
     */
    private static AnchorSlot project(Vec3 world, AnchorBinding b, float strength, float life, float radius,
                                      Minecraft mc, Vec3 camPos, Matrix4f viewProj, Matrix4f view,
                                      float tanHalfFov) {
        if (strength <= 0f) {
            return AnchorSlot.EMPTY;
        }
        Vec3 rel = world.subtract(camPos);
        float dist = (float) rel.length();
        // 距离在分母上，所以走近目标时这个值无界增长——1.5 格的世界半径在 1 格外
        // 投影出来比整个屏幕还高。数学上没错，但没有哪个效果为「贴脸」那一档设计过观感，
        // 表现出来就是走近一只绑了黑洞的怪，整个屏幕变纯黑。上限的由来见
        // AnchorBinding.DEFAULT_MAX_SCREEN_RADIUS
        float screenRadius = radius / Math.max(dist * 2f * tanHalfFov, 1e-4f);
        float cap = b.maxScreenRadius();
        if (cap > 0f) {
            screenRadius = Math.min(screenRadius, cap);
        }

        // 视图空间方向：不受视锥限制，目标转到身后照样成立。z 取反，让正前方 = +1
        Vector4f viewPos = view.transform(
                new Vector4f((float) rel.x, (float) rel.y, (float) rel.z, 1f));
        float vlen = (float) Math.sqrt(viewPos.x * viewPos.x + viewPos.y * viewPos.y
                + viewPos.z * viewPos.z);
        float dirX = vlen > 1e-5f ? viewPos.x / vlen : 0f;
        float dirY = vlen > 1e-5f ? viewPos.y / vlen : 0f;
        float dirZ = vlen > 1e-5f ? -viewPos.z / vlen : 1f;

        Vector4f clip = viewProj.transform(
                new Vector4f((float) rel.x, (float) rel.y, (float) rel.z, 1f));

        if (clip.w <= W_EPSILON) {
            // 相机背后：透视除法在这里没有意义（会把背后的东西镜像到屏幕上）。
            // 用视图空间的横向方向把锚点推到屏幕外——方向是对的，所以「屏幕外指示箭头」
            // 这类效果仍然知道该指哪边；而画在锚点上的东西自然落在屏幕外，什么都不画。
            //
            // u/v 在这一支只是个占位，别拿它算距离；要方向就读 dir，它在任何角度都成立
            float len = (float) Math.sqrt(viewPos.x * viewPos.x + viewPos.y * viewPos.y);
            float dx = len > 1e-5f ? viewPos.x / len : 0f;
            float dy = len > 1e-5f ? viewPos.y / len : -1f;
            return new AnchorSlot(0.5f + dx * OFFSCREEN_PUSH, 0.5f + dy * OFFSCREEN_PUSH,
                    1f, strength, screenRadius, dist, life, 0f,
                    dirX, dirY, dirZ, AnchorSlot.STATE_BEHIND);
        }

        float u = (clip.x / clip.w) * 0.5f + 0.5f;
        float v = (clip.y / clip.w) * 0.5f + 0.5f;
        float depth = Math.max(0f, Math.min(1f, (clip.z / clip.w) * 0.5f + 0.5f));

        float visibility = 1f;
        float state = AnchorSlot.STATE_ON_SCREEN;
        if (u < 0f || u > 1f || v < 0f || v > 1f) {
            // 在屏幕外但在相机前方：坐标本身是准的（效果的边缘可能还探进画面），
            // 只是中心看不见。可见度归零，让作者能自己决定要不要照样画
            visibility = 0f;
            state = AnchorSlot.STATE_OFFSCREEN;
        } else if (b.isOcclusion()) {
            visibility = occlusion(mc, camPos, world);
            if (visibility <= 0f) {
                // 被墙挡住和「在屏幕外」是两回事，前者位置完全可用、只是看不见。
                // 从前这两种都只表现为 visibility==0，作者没法分别处理
                state = AnchorSlot.STATE_OCCLUDED;
            }
        }

        return new AnchorSlot(u, v, depth, strength, screenRadius, dist, life, visibility,
                dirX, dirY, dirZ, state);
    }


    /**
     * 从相机到锚点连一条线，被方块挡住就是 0。
     *
     * <p>用 {@code VISUAL} 形状而不是 {@code COLLIDER}：玻璃、树叶这类能看穿的东西不该算遮挡，
     * 而它们是有碰撞箱的。判据应当和「眼睛能不能看到」一致，而不是和「能不能走过去」一致。
     *
     * <p>只做一条中心射线，不做软遮挡。半遮挡的过渡当然更好看，但那要几十条射线，
     * 而这是每帧、每个锚点都要跑的路径——先要它不掉帧。
     */
    private static float occlusion(Minecraft mc, Vec3 from, Vec3 to) {
        ClientLevel level = mc.level;
        if (level == null) {
            return 1f;
        }
        try {
            HitResult hit = level.clip(new ClipContext(from, to,
                    ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, mc.player));
            return hit.getType() == HitResult.Type.MISS ? 1f : 0f;
        } catch (RuntimeException e) {
            // 射线在区块边界上偶发的异常不该让整帧崩掉，当作没遮挡
            return 1f;
        }
    }
}
