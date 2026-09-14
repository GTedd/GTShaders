package mc.GTedd.cn.gtshaders.ui;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import org.jspecify.annotations.Nullable;
import mc.GTedd.cn.gtshaders.core.ColorMath;
import mc.GTedd.cn.gtshaders.core.ShaderParam;
import mc.GTedd.cn.gtshaders.i18n.GtLang;

import java.util.Locale;

/**
 * 取色浮层：SV 方块 + 色相条 + 透明度条 + 十六进制色盘。
 *
 * <p>三种取色方式各有各的场景，所以都留着：
 * <ul>
 *   <li><b>色盘</b>——最快。想要「一个偏冷的蓝」时没人愿意去调 HSV，点一格就完事。</li>
 *   <li><b>SV 方块 + 色相条</b>——最准。要连续微调明度/饱和度时只有它能干。</li>
 *   <li><b>十六进制键入</b>——最硬。设计稿上给的就是一个 {@code #99CCFF}，
 *       任何拖拽都不如直接把它敲进去。</li>
 * </ul>
 *
 * <p><b>长按拖动（scrub）</b>是这里的关键交互：在色块上按住不放，浮层立刻弹出，
 * 手指不抬直接划过色盘就能连续换色，松手即定。它和「点一下弹出、再点一下收起」
 * 的常驻模式共用同一套绘制与命中，只在松手时的行为上分叉（见 {@link #isTransient()}）。
 *
 * <p>自己做命中测试而不是复用 {@code EditorScreen} 的 region 表：浮层必须压在所有东西之上，
 * 而 region 表是按绘制顺序反查的，把取色器塞进去反而要处处小心顺序。
 */
public final class ColorPicker {

    public static final int WIDTH = 172;

    private static final int PAD = 8;
    private static final int SV_H = 92;
    private static final int BAR_W = 14;
    private static final int GAP = 6;
    /** 色盘：12 列 × 4 行，格子 13×13。 */
    private static final int CELL = 13;
    private static final int COLS = 12;
    private static final int ROWS = 4;
    private static final int PALETTE_H = CELL * ROWS;

    private @Nullable ShaderParam target;
    private String key = "";
    private int x;
    private int y;
    private boolean transientMode;
    /** 本次打开以来是否真的取到过颜色。长按弹出却什么都没划中时，用它决定别把浮层收掉。 */
    private boolean touched;
    /**
     * 色相单独存。RGB→HSV 在饱和度或明度为 0 时会把色相丢掉（黑色没有色相），
     * 只靠反算的话，把明度拖到底再拖回来，颜色就莫名其妙变了。
     */
    private float hue;

    public boolean isOpen() {
        return target != null;
    }

    public boolean isFor(String key) {
        return isOpen() && this.key.equals(key);
    }

    public String key() {
        return key;
    }

    /** 长按弹出的临时浮层：松手就收。 */
    public boolean isTransient() {
        return transientMode;
    }

    public boolean wasTouched() {
        return touched;
    }

    /** 把临时浮层转成常驻。长按弹出后一次颜色都没取到时用——直接收掉只会像是闪了一下。 */
    public void makeSticky() {
        transientMode = false;
    }

    public @Nullable ShaderParam target() {
        return target;
    }

    /**
     * 打开浮层。
     *
     * @param anchorX,anchorY 触发它的控件左下角，浮层从这里往下展开
     * @param boundsW,boundsH 逻辑画布尺寸，用来保证浮层不会开到屏幕外
     */
    public void open(String key, ShaderParam param, int anchorX, int anchorY,
                     int boundsW, int boundsH, boolean transientMode) {
        this.target = param;
        this.key = key;
        this.transientMode = transientMode;
        this.touched = false;
        this.hue = rgbToHue(param.get(0), param.get(1), param.get(2), this.hue);
        int h = height();
        this.x = Math.max(2, Math.min(anchorX, boundsW - WIDTH - 2));
        // 下面放不下就翻到控件上方，而不是硬贴着屏幕底部把内容截掉
        this.y = anchorY + h + 2 > boundsH ? Math.max(2, anchorY - h - 24) : anchorY;
    }

    public void close() {
        target = null;
        key = "";
        transientMode = false;
    }

    public int height() {
        return PAD + 14 + SV_H + GAP + PALETTE_H + GAP + 12 + PAD;
    }

    private boolean hasAlpha() {
        return target != null && target.type().components() >= 4;
    }

    private int svWidth() {
        return WIDTH - PAD * 2 - GAP - BAR_W - (hasAlpha() ? GAP + BAR_W : 0);
    }

    private int svTop() {
        return y + PAD + 14;
    }

    private int paletteTop() {
        return svTop() + SV_H + GAP;
    }

    public boolean isOver(double mx, double my) {
        return isOpen() && mx >= x && mx < x + WIDTH && my >= y && my < y + height();
    }

    // ------------------------------------------------------------------ 绘制

    public void render(GuiGraphicsExtractor g, Font font, int mx, int my) {
        ShaderParam p = target;
        if (p == null) {
            return;
        }
        int h = height();
        UiUtil.dropShadow(g, x, y, WIDTH, h, 4);
        UiUtil.roundRect(g, x, y, WIDTH, h, 6, Theme.PANEL);
        UiUtil.box(g, x, y, WIDTH, h, Theme.BORDER_STRONG);

        UiUtil.textLeft(g, font, GtLang.get("gtshaders.color.title"), x + PAD, y + 5, Theme.TEXT_SUB);
        UiUtil.swatch(g, x + WIDTH - PAD - 12, y + 4, 12,
                Theme.rgb(p.get(0), p.get(1), p.get(2)));

        int svW = svWidth();
        int svY = svTop();
        drawSaturationValue(g, x + PAD, svY, svW, SV_H, p);
        drawHueBar(g, x + PAD + svW + GAP, svY, BAR_W, SV_H);
        if (hasAlpha()) {
            drawAlphaBar(g, x + PAD + svW + GAP + BAR_W + GAP, svY, BAR_W, SV_H, p);
        }

        String hoverHex = drawPalette(g, x + PAD, paletteTop(), mx, my);

        // 底部：当前色值；鼠标悬在色盘上时先显示悬停格的色值，点下去之前就知道会得到什么
        int hexY = paletteTop() + PALETTE_H + GAP;
        boolean preview = hoverHex != null;
        UiUtil.textLeft(g, font, "#" + (preview ? hoverHex : p.hex()), x + PAD, hexY,
                preview ? Theme.TEXT_DIM : Theme.TEXT);
        UiUtil.textRight(g, font,
                UiUtil.ellipsize(font, GtLang.get("gtshaders.color.hex_hint"), WIDTH - PAD * 2 - 50),
                x + WIDTH - PAD, hexY, Theme.TEXT_DIM);
    }

    /** 饱和度（横）× 明度（纵）。逐 4px 方格铺，原版没有渐变填充，逐像素又太贵。 */
    private void drawSaturationValue(GuiGraphicsExtractor g, int bx, int by, int w, int h,
                                     ShaderParam p) {
        int step = 4;
        for (int i = 0; i < w; i += step) {
            float s = (float) i / Math.max(1, w - step);
            for (int j = 0; j < h; j += step) {
                float v = 1f - (float) j / Math.max(1, h - step);
                float[] rgb = hsvToRgb(hue, Math.min(1f, s), Math.min(1f, Math.max(0f, v)));
                g.fill(bx + i, by + j, Math.min(bx + i + step, bx + w),
                        Math.min(by + j + step, by + h), Theme.rgb(rgb[0], rgb[1], rgb[2]));
            }
        }
        UiUtil.box(g, bx, by, w, h, Theme.BORDER_STRONG);

        float[] hsv = rgbToHsv(p.get(0), p.get(1), p.get(2));
        int cx = bx + Math.round(hsv[1] * (w - 1));
        int cy = by + Math.round((1f - hsv[2]) * (h - 1));
        // 指示环用黑白双层，浅色区和深色区都看得见
        UiUtil.box(g, cx - 4, cy - 4, 9, 9, 0xFF000000);
        UiUtil.box(g, cx - 3, cy - 3, 7, 7, 0xFFFFFFFF);
    }

    private void drawHueBar(GuiGraphicsExtractor g, int bx, int by, int w, int h) {
        int step = 2;
        for (int j = 0; j < h; j += step) {
            float hh = (float) j / Math.max(1, h - step);
            float[] rgb = hsvToRgb(Math.min(1f, hh), 1f, 1f);
            g.fill(bx, by + j, bx + w, Math.min(by + j + step, by + h),
                    Theme.rgb(rgb[0], rgb[1], rgb[2]));
        }
        UiUtil.box(g, bx, by, w, h, Theme.BORDER_STRONG);
        int cy = by + Math.round(hue * (h - 1));
        g.fill(bx - 1, cy - 1, bx + w + 1, cy + 2, 0xFFFFFFFF);
        UiUtil.box(g, bx - 1, cy - 1, w + 2, 3, 0xFF000000);
    }

    private void drawAlphaBar(GuiGraphicsExtractor g, int bx, int by, int w, int h, ShaderParam p) {
        int step = 4;
        for (int j = 0; j < h; j += step) {
            // 棋盘格打底，透明的那一端才看得出是「透明」而不是「白」
            for (int i = 0; i < w; i += 7) {
                boolean dark = ((i / 7) + (j / step)) % 2 == 0;
                g.fill(bx + i, by + j, Math.min(bx + i + 7, bx + w), Math.min(by + j + step, by + h),
                        dark ? 0xFFCCCCCC : 0xFFFFFFFF);
            }
            float a = 1f - (float) j / Math.max(1, h - step);
            int argb = (Math.round(Math.min(1f, Math.max(0f, a)) * 255f) << 24)
                    | (Theme.rgb(p.get(0), p.get(1), p.get(2)) & 0x00FFFFFF);
            g.fill(bx, by + j, bx + w, Math.min(by + j + step, by + h), argb);
        }
        UiUtil.box(g, bx, by, w, h, Theme.BORDER_STRONG);
        int cy = by + Math.round((1f - p.get(3)) * (h - 1));
        g.fill(bx - 1, cy - 1, bx + w + 1, cy + 2, 0xFFFFFFFF);
        UiUtil.box(g, bx - 1, cy - 1, w + 2, 3, 0xFF000000);
    }

    /** @return 鼠标悬停格的十六进制色值；没悬在色盘上时为 null */
    private @Nullable String drawPalette(GuiGraphicsExtractor g, int bx, int by, int mx, int my) {
        String hover = null;
        for (int r = 0; r < ROWS; r++) {
            for (int c = 0; c < COLS; c++) {
                int cx = bx + c * CELL;
                int cy = by + r * CELL;
                int argb = paletteColor(r, c);
                g.fill(cx, cy, cx + CELL - 1, cy + CELL - 1, argb);
                if (mx >= cx && mx < cx + CELL - 1 && my >= cy && my < cy + CELL - 1) {
                    UiUtil.box(g, cx - 1, cy - 1, CELL + 1, CELL + 1, Theme.TEXT);
                    hover = String.format(Locale.ROOT, "%06X", argb & 0xFFFFFF);
                }
            }
        }
        return hover;
    }

    /**
     * 色盘配色：首行灰阶，其余三行是同一组色相的浅 / 正 / 深。
     * 按行分明度、按列分色相，扫过去的时候「往右换色、往下变深」是可预期的。
     */
    private static int paletteColor(int row, int col) {
        if (row == 0) {
            float v = (float) col / (COLS - 1);
            return Theme.rgb(v, v, v);
        }
        float h = (float) col / COLS;
        float[] sv = switch (row) {
            case 1 -> new float[]{0.32f, 1.00f};
            case 2 -> new float[]{0.85f, 0.95f};
            default -> new float[]{0.90f, 0.55f};
        };
        float[] rgb = hsvToRgb(h, sv[0], sv[1]);
        return Theme.rgb(rgb[0], rgb[1], rgb[2]);
    }

    // ------------------------------------------------------------------ 交互

    /** @return 是否吃掉了这次点击 */
    public boolean mouseClicked(double mx, double my) {
        return applyAt(mx, my);
    }

    public boolean mouseDragged(double mx, double my) {
        return applyAt(mx, my);
    }

    /**
     * 把光标位置映射成一次取色。三个区域共用这一个入口，
     * 于是「按住不放划过整个浮层」在哪个区域上都能连续取色。
     */
    private boolean applyAt(double mx, double my) {
        ShaderParam p = target;
        if (p == null) {
            return false;
        }
        int svW = svWidth();
        int svY = svTop();
        int hueX = x + PAD + svW + GAP;
        int alphaX = hueX + BAR_W + GAP;

        if (my >= svY && my < svY + SV_H) {
            if (mx >= x + PAD && mx < x + PAD + svW) {
                float s = clamp01((float) (mx - (x + PAD)) / Math.max(1, svW - 1));
                float v = 1f - clamp01((float) (my - svY) / Math.max(1, SV_H - 1));
                setRgb(p, hsvToRgb(hue, s, v));
                touched = true;
                return true;
            }
            if (mx >= hueX && mx < hueX + BAR_W) {
                hue = clamp01((float) (my - svY) / Math.max(1, SV_H - 1));
                float[] hsv = rgbToHsv(p.get(0), p.get(1), p.get(2));
                // 只换色相，保留已经调好的饱和度与明度
                setRgb(p, hsvToRgb(hue, hsv[1], hsv[2]));
                touched = true;
                return true;
            }
            if (hasAlpha() && mx >= alphaX && mx < alphaX + BAR_W) {
                p.set(3, 1f - clamp01((float) (my - svY) / Math.max(1, SV_H - 1)));
                touched = true;
                return true;
            }
            return true;
        }

        int py = paletteTop();
        if (my >= py && my < py + PALETTE_H && mx >= x + PAD && mx < x + PAD + CELL * COLS) {
            int c = (int) ((mx - (x + PAD)) / CELL);
            int r = (int) ((my - py) / CELL);
            if (c >= 0 && c < COLS && r >= 0 && r < ROWS) {
                int argb = paletteColor(r, c);
                p.set(0, ((argb >> 16) & 0xFF) / 255f);
                p.set(1, ((argb >> 8) & 0xFF) / 255f);
                p.set(2, (argb & 0xFF) / 255f);
                hue = rgbToHue(p.get(0), p.get(1), p.get(2), hue);
                touched = true;
            }
            return true;
        }
        // 浮层内的其它位置（标题、提示行）：吃掉点击，免得穿透到底下的控件
        return isOver(mx, my);
    }

    private static void setRgb(ShaderParam p, float[] rgb) {
        p.set(0, rgb[0]);
        p.set(1, rgb[1]);
        p.set(2, rgb[2]);
    }

    // ------------------------------------------------------------------ 色彩换算

    private static float[] hsvToRgb(float h, float s, float v) {
        return ColorMath.hsvToRgb(h, s, v);
    }

    private static float[] rgbToHsv(float r, float g, float b) {
        return ColorMath.rgbToHsv(r, g, b);
    }

    private static float rgbToHue(float r, float g, float b, float previous) {
        return ColorMath.hueOf(r, g, b, previous);
    }

    private static float clamp01(float v) {
        return Math.max(0f, Math.min(1f, v));
    }
}
