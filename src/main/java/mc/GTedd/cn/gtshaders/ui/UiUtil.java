package mc.GTedd.cn.gtshaders.ui;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * 一组绘制原语。
 *
 * <p>原版的 GUI 只提供 {@code fill} / {@code text} / {@code outline} 这类最基础的操作，
 * 圆角、阴影、投影都得自己拼。这里把 MasterGo 那套视觉语言需要的几种形状封装出来，
 * 免得每个面板各画各的、最后风格对不齐。
 */
public final class UiUtil {

    private UiUtil() {
    }

    /**
     * 近似圆角矩形：四角各切掉一个小三角形。原版没有抗锯齿的圆角绘制，
     * 用逐行收缩的方式画出的圆角在 GUI 缩放下观感已经足够接近。
     */
    public static void roundRect(GuiGraphicsExtractor g, int x, int y, int w, int h, int r, int color) {
        if (w <= 0 || h <= 0) {
            return;
        }
        int radius = Math.max(0, Math.min(r, Math.min(w, h) / 2));
        if (radius == 0) {
            g.fill(x, y, x + w, y + h, color);
            return;
        }
        g.fill(x + radius, y, x + w - radius, y + h, color);
        g.fill(x, y + radius, x + radius, y + h - radius, color);
        g.fill(x + w - radius, y + radius, x + w, y + h - radius, color);
        for (int i = 0; i < radius; i++) {
            // 每往角落走一行，就多切掉一点宽度，形成阶梯状的圆角
            int inset = radius - (int) Math.round(Math.sqrt(radius * radius - (radius - i - 1) * (radius - i - 1)));
            g.fill(x + inset, y + i, x + w - inset, y + i + 1, color);
            g.fill(x + inset, y + h - i - 1, x + w - inset, y + h - i, color);
        }
    }

    /** 1px 描边的圆角矩形。 */
    public static void roundOutline(GuiGraphicsExtractor g, int x, int y, int w, int h, int r, int color) {
        roundRect(g, x, y, w, h, r, color);
        roundRect(g, x + 1, y + 1, w - 2, h - 2, Math.max(0, r - 1), 0x00000000);
    }

    /** 浮动面板的投影：向下向外铺几层递减的半透明黑，成本低但足以把面板从画面里"托"起来。 */
    public static void dropShadow(GuiGraphicsExtractor g, int x, int y, int w, int h, int layers) {
        for (int i = layers; i >= 1; i--) {
            int alpha = (int) (0x18 * (1.0 - (double) i / (layers + 1)));
            int color = (alpha << 24);
            g.fill(x - i, y - i + 2, x + w + i, y + h + i + 2, color);
        }
    }

    public static void hLine(GuiGraphicsExtractor g, int x0, int x1, int y, int color) {
        g.fill(x0, y, x1, y + 1, color);
    }

    public static void vLine(GuiGraphicsExtractor g, int x, int y0, int y1, int color) {
        g.fill(x, y0, x + 1, y1, color);
    }

    /**
     * 1px 的椭圆描边，用一圈短横线拼出来。
     *
     * <p>原版 {@code GuiGraphics} 只有填矩形，没有画圆的接口。这里按角度取样再逐段填 1×1，
     * 采样数跟着周长走：太少会拼出多边形，固定取一个大值又在画小圈时白跑几百次。
     *
     * <p>只画描边不填充是刻意的——这是叠在<b>实时预览画面</b>上的辅助线，
     * 填色会把它要标注的那块画面本身挡住。
     */
    public static void ring(GuiGraphicsExtractor g, int cx, int cy, int rx, int ry, int color) {
        int steps = Math.max(24, Math.min(360, (int) ((rx + ry) * 1.6)));
        for (int i = 0; i < steps; i++) {
            double a = Math.PI * 2 * i / steps;
            int px = cx + (int) Math.round(Math.cos(a) * rx);
            int py = cy + (int) Math.round(Math.sin(a) * ry);
            g.fill(px, py, px + 1, py + 1, color);
        }
    }

    /** 1px 方框。 */
    public static void box(GuiGraphicsExtractor g, int x, int y, int w, int h, int color) {
        g.fill(x, y, x + w, y + 1, color);
        g.fill(x, y + h - 1, x + w, y + h, color);
        g.fill(x, y + 1, x + 1, y + h - 1, color);
        g.fill(x + w - 1, y + 1, x + w, y + h - 1, color);
    }

    /**
     * 文本超出宽度时截断并加省略号。多语言下同一处标签长度差异很大，必须兜住。
     *
     * <p>{@code font} 参数保留是为了不动上百处调用；实际度量走 {@link UiText}，
     * 它知道这一帧用的是矢量字体还是原版位图字体。
     */
    public static String ellipsize(Font font, String text, int maxWidth) {
        if (text == null) {
            return "";
        }
        if (UiText.width(text) <= maxWidth) {
            return text;
        }
        String ellipsis = "…";
        int ellipsisW = UiText.width(ellipsis);
        StringBuilder sb = new StringBuilder();
        int w = 0;
        for (int i = 0; i < text.length(); i++) {
            int cw = UiText.width(text.charAt(i));
            if (w + cw + ellipsisW > maxWidth) {
                break;
            }
            sb.append(text.charAt(i));
            w += cw;
        }
        return sb + ellipsis;
    }

    public static void textLeft(GuiGraphicsExtractor g, Font font, String text, int x, int y, int color) {
        UiText.draw(g, text, x, y, color);
    }

    public static void textRight(GuiGraphicsExtractor g, Font font, String text, int right, int y, int color) {
        UiText.draw(g, text, right - UiText.width(text), y, color);
    }

    public static void textCenter(GuiGraphicsExtractor g, Font font, String text, int cx, int y, int color) {
        UiText.draw(g, text, cx - UiText.width(text) / 2, y, color);
    }

    /** 分区标题：小号灰字 + 右侧可选的操作图标位。对应 MasterGo 右栏的「填充 / 描边 / 特效」那种标题行。 */
    public static void sectionHeader(GuiGraphicsExtractor g, Font font, String title,
                                     int x, int y, int w) {
        textLeft(g, font, title, x, y, Theme.TEXT);
        hLine(g, x, x + w, y + font.lineHeight + 5, Theme.BORDER);
    }

    /** 水平滑块。返回拖柄中心 x，方便调用方做命中测试。 */
    public static int slider(GuiGraphicsExtractor g, int x, int y, int w, int h, float t) {
        float clamped = Math.max(0f, Math.min(1f, t));
        roundRect(g, x, y, w, h, h / 2, Theme.SLIDER_TRACK);
        int fill = Math.round(w * clamped);
        if (fill > 0) {
            roundRect(g, x, y, Math.max(fill, h), h, h / 2, Theme.SLIDER_FILL);
        }
        int knobR = h + 2;
        int knobX = x + Math.round((w - knobR) * clamped);
        roundRect(g, knobX, y - 1, knobR, knobR, knobR / 2, Theme.SLIDER_KNOB);
        return knobX + knobR / 2;
    }

    /** 开关。 */
    public static void toggle(GuiGraphicsExtractor g, int x, int y, int w, int h, boolean on) {
        roundRect(g, x, y, w, h, h / 2, on ? Theme.ACCENT : Theme.TOGGLE_OFF);
        int knob = h - 4;
        int knobX = on ? x + w - knob - 2 : x + 2;
        roundRect(g, knobX, y + 2, knob, knob, knob / 2, 0xFFFFFFFF);
    }

    /** 色块（带细边框，浅色底上的白色才看得见边界）。 */
    public static void swatch(GuiGraphicsExtractor g, int x, int y, int size, int argb) {
        roundRect(g, x, y, size, size, 3, argb);
        box(g, x, y, size, size, Theme.BORDER_STRONG);
    }
}
