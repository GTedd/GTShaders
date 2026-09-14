package mc.GTedd.cn.gtshaders.ui;

import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;
import mc.GTedd.cn.gtshaders.GTShaders;
import mc.GTedd.cn.gtshaders.core.AnchorBinding;
import mc.GTedd.cn.gtshaders.core.AnchorSlot;
import mc.GTedd.cn.gtshaders.core.ShaderProject;
import mc.GTedd.cn.gtshaders.i18n.GtLang;
import mc.GTedd.cn.gtshaders.runtime.AnchorRuntime;
import mc.GTedd.cn.gtshaders.core.TrailSlot;
import mc.GTedd.cn.gtshaders.runtime.PreviewRuntime;
import mc.GTedd.cn.gtshaders.runtime.TrailRuntime;

import java.util.ArrayList;
import java.util.List;

/**
 * 局内锚点状态浮层。
 *
 * <h2>为什么必须有这个东西</h2>
 *
 * <p>锚点这条链出问题时<b>没有任何报错</b>：着色器照常编译、画面照常渲染、面板上每一项都填着值，
 * 只是效果不出现。玩家能做的只有反复试，而每一次试都要「关编辑器 → 去打死一只怪 → 发现没反应 →
 * 回编辑器猜哪里错了」。
 *
 * <p>这个浮层把整条链上每个环节的<b>实时真值</b>摊开：绑定占了哪个槽位、来源和触发器是什么、
 * 此刻有几个事件在播、播到了生命周期的哪一段。于是「没反应」不再是一个黑盒，而是能一眼看出
 * 断在哪一步——是没触发（活跃数恒为 0）、还是触发了但效果没接上（活跃数在跳但画面没动）。
 *
 * <h2>什么时候显示</h2>
 *
 * <p>只在「预览开着 <b>且</b> 工程里有启用的锚点绑定」时出现。这两个条件同时成立基本上就等于
 * 「正在调锚点」，普通玩耍时不会撞上。想让它消失：按 Delete 关预览，或者把绑定停用。
 *
 * <p>刻意不复用 {@link Theme}：那套配色是给编辑器的浅色面板用的，压在游戏画面上会糊成一片。
 * 也刻意不走 {@link UiUtil} / {@code UiText}——那些依赖编辑器每帧设置的缩放上下文，
 * 在 HUD 这条路径上根本没有人去设。
 */
public final class AnchorHud implements HudElement {

    private static final int PANEL = 0xB0101418;
    private static final int BORDER = 0x60FFFFFF;
    private static final int TEXT = 0xFFE6E9EF;
    private static final int TEXT_DIM = 0xFF9AA3AF;
    private static final int SLOT = 0xFF6FA8FF;
    private static final int LIVE = 0xFF5BD98A;
    private static final int WARN = 0xFFFFB020;

    private static final int PAD = 5;
    private static final int MARGIN = 4;

    private AnchorHud() {
    }

    public static void register() {
        HudElementRegistry.addLast(
                Identifier.fromNamespaceAndPath(GTShaders.MOD_ID, "anchor_hud"), new AnchorHud());
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, DeltaTracker deltaTracker) {
        Minecraft mc = Minecraft.getInstance();
        // addLast 注册的层<b>不继承</b>原版的显示条件，F1 隐藏界面得自己判
        if (mc.level == null || mc.gui == null || mc.gui.hud == null || mc.gui.hud.isHidden()) {
            return;
        }
        ShaderProject project = AnchorRuntime.project();
        // 轨迹和锚点是同一类「没反应时完全是黑盒」的东西，所以共用这个浮层：
        // 刀光不出现可能是没开第一人称、手里没拿东西、挥得不够快，三者表现完全一样
        if (project == null || !PreviewRuntime.isActive()
                || (!AnchorRuntime.hasBindings() && !PreviewRuntime.usesTrails())) {
            return;
        }

        List<Line> lines = build(project);
        if (lines.isEmpty()) {
            return;
        }

        Font font = mc.font;
        int width = 0;
        for (Line l : lines) {
            width = Math.max(width, font.width(l.text()) + l.indent());
        }
        int w = width + PAD * 2;
        int h = lines.size() * (font.lineHeight + 1) + PAD * 2 - 1;

        g.fill(MARGIN, MARGIN, MARGIN + w, MARGIN + h, PANEL);
        // 只描上下两条边：四条边框在游戏画面上会显得像一个弹窗，压着它抢注意力
        g.fill(MARGIN, MARGIN, MARGIN + w, MARGIN + 1, BORDER);
        g.fill(MARGIN, MARGIN + h - 1, MARGIN + w, MARGIN + h, BORDER);

        int y = MARGIN + PAD;
        for (Line l : lines) {
            g.text(font, l.text(), MARGIN + PAD + l.indent(), y, l.color(), true);
            y += font.lineHeight + 1;
        }
    }

    /** 一行文字。{@code indent} 用来把绑定的细节缩进去，一眼能分出层级。 */
    private record Line(String text, int color, int indent) {
        static Line of(String text, int color) {
            return new Line(text, color, 0);
        }
    }

    private static List<Line> build(ShaderProject project) {
        List<Line> out = new ArrayList<>();
        if (AnchorRuntime.hasBindings()) {
            buildAnchors(project, out);
        }
        if (PreviewRuntime.usesTrails()) {
            buildTrail(out);
        }
        out.add(Line.of(GtLang.get("gtshaders.hud.keys"), TEXT_DIM));
        return out;
    }

    /**
     * 武器轨迹这一节。
     *
     * <p>只报三个数，但那正好是「刀光怎么不出来」的三种答案：<b>采到几帧</b>为 0 说明
     * 压根没拿到刀（第三人称、手里空着、界面挡着）；<b>挥动速度</b>低于效果里的起光门限
     * 说明数据是通的、只是没挥够狠；两个都正常还不显示，那就轮到查效果自己的参数了。
     */
    private static void buildTrail(List<Line> out) {
        TrailSlot[] trail = TrailRuntime.slots();
        int live = 0;
        float speed = 0f;
        for (TrailSlot s : trail) {
            if (!s.isEmpty()) {
                live++;
                speed = Math.max(speed, s.speed());
            }
        }
        out.add(Line.of(GtLang.get("gtshaders.hud.trail_title", live, TrailSlot.SLOTS), TEXT));
        if (live == 0) {
            out.add(new Line(GtLang.get("gtshaders.hud.trail_idle"), WARN, 8));
        } else {
            out.add(new Line(GtLang.get("gtshaders.hud.trail_live",
                    String.format(java.util.Locale.ROOT, "%.3f", speed),
                    String.format(java.util.Locale.ROOT, "%.2f", TrailRuntime.swing())), LIVE, 8));
        }
    }

    private static void buildAnchors(ShaderProject project, List<Line> out) {
        AnchorSlot[] slots = AnchorRuntime.slots();

        out.add(Line.of(GtLang.get("gtshaders.hud.title",
                project.anchorSlotsUsed(), AnchorSlot.SLOTS), TEXT));

        // 「有没有效果在读锚点」是整条链上最容易被忽略的一环：绑定配得再对，
        // 工程里没有一层用到 gtAnchor 的话画面上永远不会有任何变化
        if (!PreviewRuntime.usesAnchors()) {
            out.add(new Line(GtLang.get("gtshaders.hud.no_anchor_layer"), WARN, 0));
        }

        List<AnchorBinding> anchors = project.anchors();
        for (int i = 0; i < anchors.size(); i++) {
            AnchorBinding b = anchors.get(i);
            if (!b.isEnabled()) {
                continue;
            }
            int base = project.anchorSlotBase(i);
            String head = (base < 0 ? "#-" : "#" + base) + "  " + b.name();
            out.add(Line.of(head, SLOT));

            String selector = b.selector().isBlank()
                    ? GtLang.get("gtshaders.hud.selector_any") : b.selector();
            out.add(new Line(b.trigger().displayName() + " · " + b.source().displayName()
                    + (b.source().needsSelector() ? " · " + selector : ""), TEXT_DIM, 8));

            // 活跃事件数与生命周期进度：这两个数字回答「到底触发了没有」，
            // 而那正是「配了没反应」时唯一需要知道的事
            int active = 0;
            float life = 0f;
            for (int s = base; base >= 0 && s < base + b.maxSlots() && s < slots.length; s++) {
                if (!slots[s].isEmpty()) {
                    active++;
                    life = Math.max(life, slots[s].life());
                }
            }
            if (active > 0) {
                out.add(new Line(GtLang.get("gtshaders.hud.live", active,
                        String.format(java.util.Locale.ROOT, "%.2f", life)), LIVE, 8));
            } else {
                out.add(new Line(GtLang.get("gtshaders.hud.idle"), TEXT_DIM, 8));
            }

            // 载体那一行只在真配了朝向或子类型时出现。都是默认值的绑定跟载体无关，
            // 多这一行只会把真正在用载体的那几条淹掉
            if (PreviewRuntime.usesEmitters() && (b.usesFacing() || b.emitterType() != 0)) {
                out.add(new Line(GtLang.get("gtshaders.hud.emitter",
                        b.facing().displayName(), b.emitterType()), TEXT_DIM, 8));
            }

            String warn = b.warning();
            if (!warn.isEmpty()) {
                out.add(new Line(GtLang.get(warn), WARN, 8));
            }
        }
    }
}
