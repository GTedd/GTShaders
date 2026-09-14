package mc.GTedd.cn.gtshaders.ui;

import net.minecraft.client.Minecraft;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.gizmos.TextGizmo;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import mc.GTedd.cn.gtshaders.core.AnchorBinding;
import mc.GTedd.cn.gtshaders.core.AnchorSlot;
import mc.GTedd.cn.gtshaders.core.ShaderProject;
import mc.GTedd.cn.gtshaders.i18n.GtLang;
import mc.GTedd.cn.gtshaders.runtime.AnchorRuntime;
import mc.GTedd.cn.gtshaders.runtime.BindMode;

import java.util.List;

/**
 * 绑定模式下把锚点画进世界里。
 *
 * <h2>为什么必须画出来</h2>
 *
 * <p>锚点面板上「世界半径 1.50」「Y 偏移 1.00」这两个滑条，在画面上<b>没有任何对应物</b>。
 * 它们最终只影响两件事：投影出来的屏幕半径有多大、锚点落在目标身上的哪个高度。
 * 两者都得靠「改一点 → 关面板 → 触发一次 → 看效果 → 再回去改」来试，
 * 而这个循环走一遍要十几秒，于是实际上没人真的调它们，都在用默认值。
 *
 * <p>把半径画成一个线框盒、把锚点位置画成盒子中心之后，这两个滑条就是看着调的了。
 *
 * <p>另一件看不见的事是「这条绑定现在到底找到目标了没有」。绑定配错时画面上什么都不会发生，
 * 而「没找到目标」和「找到了但效果没接上」需要完全不同的排查方向。世界里有没有那个框，
 * 一眼就分开了这两种情况。
 *
 * <h2>为什么用原版的 Gizmo 系统</h2>
 *
 * <p>26.3 自带 {@link Gizmos}——线、圆、立方体、箭头、面向相机的文字，还有
 * {@code setAlwaysOnTop()} 这种穿墙显示。手搓一套 {@code VertexConsumer} 能得到的
 * 也就是这些，却要自己管 {@code RenderType}、深度测试、以及每个版本都在变的 Blaze3D 接口。
 *
 * <p>收集走的是 {@code Minecraft.collectPerTickGizmos()}：它在 tick 里设好 ThreadLocal 收集器，
 * 因此这个类只要在客户端 tick 期间被调到就行，不必去碰渲染线程。代价是标记比画面晚一 tick，
 * 对静止的锚点无感，对跟着实体跑的锚点是 50 毫秒的滞后——而 gizmo 本来就是逐 tick 采样的。
 */
public final class AnchorGizmos {

    /** 正在编辑的那一条。 */
    private static final int COLOR_CURRENT = 0xFF66E0FF;
    /** 其它绑定。压暗压灰，免得八条绑定糊成一片看不出在编辑哪个。 */
    private static final int COLOR_OTHER = 0x9078909F;
    /** 此刻真的点亮了（槽位强度非 0）。 */
    private static final int COLOR_LIVE = 0xFF5BD98A;
    /** 准星落点：按下左键会绑到这儿。 */
    private static final int COLOR_CROSSHAIR = 0xFFFFB020;
    /** 删除已举起，等第二下确认。 */
    private static final int COLOR_DANGER = 0xFFFF5C5C;

    /** 朝向箭头的长度（方块）。够看出方向，又不至于插进墙里让人以为是别的东西。 */
    private static final float ARROW_LEN = 1.5f;

    private AnchorGizmos() {
    }

    /**
     * 在客户端 tick 里调一次。不在绑定模式就什么都不画——世界里常驻一堆线框会严重干扰
     * 「效果本身好不好看」这个判断，而那才是这个工具的主业。
     */
    public static void tick(Minecraft mc) {
        if (!BindMode.isActive() || mc.level == null || mc.player == null) {
            return;
        }
        ShaderProject project = AnchorRuntime.project();
        if (project == null) {
            return;
        }

        try (var ignored = mc.collectPerTickGizmos()) {
            drawCrosshair();

            List<AnchorBinding> anchors = project.anchors();
            int current = project.selectedAnchorIndex();
            AnchorSlot[] slots = AnchorRuntime.slots();

            for (int i = 0; i < anchors.size(); i++) {
                AnchorBinding b = anchors.get(i);
                if (!b.isEnabled()) {
                    continue;
                }
                drawBinding(mc, project, b, i, i == current, slots);
            }
        }
    }

    /**
     * 准星落点标记：按下左键会绑到<b>这个点</b>。
     *
     * <p>读的是 {@code BindMode} 本 tick 缓存的那一次 {@code AnchorRuntime.pick}——
     * 和绑定解算走的是同一个方法，所以它不是一个近似提示，而就是那个落点本身。
     * 射程 128 格远远超过玩家的交互距离，原版的方块高亮线框在几格外就没了，
     * 没有这个标记的话，对着远处地面按一下完全是盲操作。
     */
    private static void drawCrosshair() {
        AnchorRuntime.Pick pick = BindMode.currentPick();
        if (pick == null) {
            return;
        }
        int color = BindMode.isDeleteArmed() ? COLOR_DANGER : COLOR_CROSSHAIR;
        Vec3 p = pick.point();
        // 小十字而不是一个点：一个 point 在远处会被压成一个几乎看不见的像素，
        // 而三条固定长度的线在任何距离上都还认得出中心在哪
        double d = 0.35;
        Gizmos.line(p.add(-d, 0, 0), p.add(d, 0, 0), color).setAlwaysOnTop();
        Gizmos.line(p.add(0, -d, 0), p.add(0, d, 0), color).setAlwaysOnTop();
        Gizmos.line(p.add(0, 0, -d), p.add(0, 0, d), color).setAlwaysOnTop();

        // 当前绑法和它会怎么点亮，就写在落点上。看着目标读，不用把视线挪到状态栏
        Gizmos.billboardText(BindMode.semantic().displayName(), p.add(0, 0.55, 0),
                TextGizmo.Style.forColorAndCentered(color).withScale(0.7f)).setAlwaysOnTop();
    }

    /** 一条绑定的全部候选目标。 */
    private static void drawBinding(Minecraft mc, ShaderProject project, AnchorBinding b,
                                    int index, boolean current, AnchorSlot[] slots) {
        List<Vec3> positions = AnchorRuntime.previewPositions(b, mc);
        if (positions.isEmpty()) {
            return;
        }
        int base = project.anchorSlotBase(index);
        float r = b.worldRadius();

        for (int k = 0; k < positions.size(); k++) {
            Vec3 p = positions.get(k);
            int slot = base < 0 ? -1 : base + k;
            // 点亮判据和着色器里的 gtAnchorValid 是同一个：strength 严格非 0。
            // 界面上说「亮着」而着色器认为是空槽，是这条链上最难查的一类不一致
            boolean live = slot >= 0 && slot < slots.length && slots[slot].strength() > 0f;
            int color = live ? COLOR_LIVE : (current ? COLOR_CURRENT : COLOR_OTHER);

            // 立方体而不是球：worldRadius 在解算里就是按半径投影的，
            // 一个边长 2r 的盒子把这个半径直接量给人看
            Gizmos.cuboid(AABB.ofSize(p, r * 2, r * 2, r * 2), GizmoStyle.stroke(color))
                    .setAlwaysOnTop();
            // 水平圆补一个「地面上的覆盖范围」，正对着看盒子时它是唯一能读出半径的东西
            Gizmos.circle(p, r, GizmoStyle.stroke(color)).setAlwaysOnTop();

            if (b.usesFacing()) {
                drawFacing(b, p, color);
            }

            // 只有当前编辑项标文字。八条绑定各标一行会把画面糊满，
            // 而其它绑定要的信息只是「它在那儿、还活着」
            if (current) {
                String label = GtLang.get("gtshaders.bind.gizmo_label",
                        slot < 0 ? "-" : String.valueOf(slot), b.name());
                Gizmos.billboardText(label, p.add(0, r + 0.45, 0),
                        TextGizmo.Style.forColorAndCentered(color)).setAlwaysOnTop();
            }
        }
    }

    /**
     * 朝向箭头。
     *
     * <p>这是载体那套 {@code gtEmitterDir} 唯一能在画面上核对的地方——面板上的 yaw/pitch
     * 是两个数字，而「聚光灯到底射向哪」只有画出来才知道。角度约定跟着解算侧走：
     * yaw 0 朝南（+Z），pitch 正值朝下，和原版实体朝向一致。
     */
    private static void drawFacing(AnchorBinding b, Vec3 p, int color) {
        double yaw = Math.toRadians(b.yaw());
        double pitch = Math.toRadians(b.pitch());
        double cp = Math.cos(pitch);
        Vec3 dir = new Vec3(-Math.sin(yaw) * cp, -Math.sin(pitch), Math.cos(yaw) * cp);
        Gizmos.arrow(p, p.add(dir.scale(ARROW_LEN)), color).setAlwaysOnTop();
    }
}
