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
import mc.GTedd.cn.gtshaders.runtime.BindMode;
import mc.GTedd.cn.gtshaders.runtime.PreviewRuntime;

import java.util.ArrayList;
import java.util.List;

/**
 * 绑定模式的状态栏。
 *
 * <h2>为什么不并进 {@link AnchorHud}</h2>
 *
 * <p>那个浮层在左上角，是<b>诊断</b>用的——效果没反应时去读它，找断在哪一环。
 * 这个状态栏是<b>操作</b>用的：进模式的人眼睛一直盯着准星，而准星在屏幕正中。
 * 让人为了确认「我现在按左键会绑成什么」把视线甩到左上角，
 * 一次两次没问题，配八条绑定就会开始漏看。
 *
 * <p>所以它贴着屏幕底部居中，抬眼就在余光里。两个浮层同时出现也不打架——
 * 一个在左上，一个在正下。
 *
 * <h2>为什么第三行永远显示按键表</h2>
 *
 * <p>绑定模式改写了左键、右键、中键、滚轮、Esc 五个键的含义，其中三个是<b>玩家肌肉记忆
 * 最深</b>的那几个。不写出来的话，进模式之后第一个念头会是「我现在还能不能打怪」，
 * 而这个疑问本身就足以让人退出去。写出来它就变成了一个说明书摊开的工具。
 */
public final class BindModeHud implements HudElement {

    private static final int PANEL = 0xC0101418;
    private static final int BORDER = 0x70FFFFFF;
    private static final int TEXT = 0xFFE6E9EF;
    private static final int TEXT_DIM = 0xFF9AA3AF;
    private static final int ACCENT = 0xFF66E0FF;
    private static final int LIVE = 0xFF5BD98A;
    private static final int WARN = 0xFFFFB020;
    private static final int DANGER = 0xFFFF5C5C;

    private static final int PAD = 5;
    /** 距屏幕底边的距离。避开物品栏（约 22px）和它上面的经验条、action bar。 */
    private static final int BOTTOM_MARGIN = 68;

    private BindModeHud() {
    }

    public static void register() {
        HudElementRegistry.addLast(
                Identifier.fromNamespaceAndPath(GTShaders.MOD_ID, "bind_mode_hud"), new BindModeHud());
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, DeltaTracker deltaTracker) {
        Minecraft mc = Minecraft.getInstance();
        // addLast 注册的层<b>不继承</b>原版的显示条件，F1 隐藏界面得自己判
        if (!BindMode.isActive() || mc.level == null
                || mc.gui == null || mc.gui.hud == null || mc.gui.hud.isHidden()) {
            return;
        }
        ShaderProject project = AnchorRuntime.project();
        if (project == null) {
            return;
        }

        List<Line> lines = build(mc, project);
        Font font = mc.font;
        int width = 0;
        for (Line l : lines) {
            width = Math.max(width, font.width(l.text()));
        }
        int w = width + PAD * 2;
        int h = lines.size() * (font.lineHeight + 1) + PAD * 2 - 1;
        int x = (g.guiWidth() - w) / 2;
        int y = g.guiHeight() - BOTTOM_MARGIN - h;

        g.fill(x, y, x + w, y + h, PANEL);
        // 删除举起时整框描红。这是唯一一个「再按一下就不可逆」的状态，
        // 只靠一行小字说不够——那行字正好在人准备按第二下的时候不会被读
        int border = BindMode.isDeleteArmed() ? DANGER : BORDER;
        g.fill(x, y, x + w, y + 1, border);
        g.fill(x, y + h - 1, x + w, y + h, border);

        int ty = y + PAD;
        for (Line l : lines) {
            g.text(font, l.text(), x + (w - font.width(l.text())) / 2, ty, l.color(), true);
            ty += font.lineHeight + 1;
        }
    }

    private record Line(String text, int color) {
    }

    private static List<Line> build(Minecraft mc, ShaderProject project) {
        List<Line> out = new ArrayList<>();

        // 第一行：在编辑哪一条、它占哪个槽、槽位还剩多少
        AnchorBinding b = project.selectedAnchor();
        if (b == null) {
            out.add(new Line(GtLang.get("gtshaders.bind.title_empty"), WARN));
        } else {
            int slot = project.anchorSlotBase(project.selectedAnchorIndex());
            out.add(new Line(GtLang.get("gtshaders.bind.title",
                    project.selectedAnchorIndex() + 1, project.anchors().size(),
                    b.name(), slot < 0 ? "-" : String.valueOf(slot),
                    project.anchorSlotsUsed(), AnchorSlot.SLOTS), ACCENT));
        }

        // 第二行：准星指着什么 + 按下去会配成什么。这两件事必须在同一行——
        // 「目标」和「绑法」分开读的话，人得自己在脑子里把它们组合起来
        out.add(new Line(GtLang.get("gtshaders.bind.target_line",
                BindMode.describeTarget(mc), BindMode.semantic().displayName()), TEXT));
        out.add(new Line(BindMode.semantic().hint(), TEXT_DIM));

        // 第三行：按键表。改写了五个肌肉记忆最深的键，不写出来没人敢按
        out.add(new Line(GtLang.get("gtshaders.bind.keys"), TEXT_DIM));

        // 配得再对，下游断了一环画面上照样什么都不会发生。两种断法的原因完全不同，
        // 给同一条提示会把人引到错的方向——「没有层读锚点」的人会去翻效果库，
        // 而他实际上只需要按一下 Delete
        if (!PreviewRuntime.isActive()) {
            out.add(new Line(GtLang.get("gtshaders.bind.no_preview"), WARN));
        } else if (!PreviewRuntime.usesAnchors()) {
            out.add(new Line(GtLang.get("gtshaders.bind.no_layer"), WARN));
        }

        String flash = BindMode.flash();
        if (!flash.isEmpty()) {
            out.add(new Line(flash, BindMode.isDeleteArmed() ? DANGER : LIVE));
        }
        return out;
    }
}
