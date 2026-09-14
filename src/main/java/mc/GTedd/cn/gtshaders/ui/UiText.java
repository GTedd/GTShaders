package mc.GTedd.cn.gtshaders.ui;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import org.jspecify.annotations.Nullable;

/**
 * 界面文字的绘制与测量。矢量字体可用时走它，不可用时原样退回原版位图字体。
 *
 * <h2>为什么文字要单独处理，不能跟着界面一起缩放</h2>
 *
 * <p>编辑器整体套了一层 {@code pose().scale(uiScale)}。字形——无论位图还是从 TTF 栅格化出来的
 * ——最终都是纹理，被这个矩阵一缩就要重新采样，非整数倍下必然发虚。
 *
 * <p>所以这里反着做：<b>把那层缩放抵消掉，让文字落在原生像素上</b>，
 * 再用一份<b>按当前缩放重新栅格化</b>的字体去画（见 {@link UiFont}）。
 * 于是缩放不再是"把字放大缩小"，而是"换一个字号重新排版"——和 imgui 按 fontScale
 * 重建字体图集是同一个思路。
 *
 * <h2>为什么用一份每帧设置的静态上下文</h2>
 *
 * <p>绘制点有近百处，全部分布在 {@link UiUtil} 与各个面板里。要让它们知道当前缩放，
 * 要么给每个函数加参数（改上百处调用，且每处都可能传错），要么在一帧开始时设一次。
 * Minecraft 的 GUI 渲染是单线程的，后者安全而且改动面小得多。
 *
 * <h2>坐标与单位</h2>
 *
 * <p>对外的一切都是<b>编辑器逻辑单位</b>，和没有这个类时完全一样。内部才换算到
 * Minecraft 逻辑坐标去绘制。{@link #lineHeight()} 恒等于原版的 9，正是为了让既有的
 * 上百处排版常量一个都不用改。
 */
final class UiText {

    private static Font vanilla = null;
    private static @Nullable Font vector;
    private static float uiScale = 1.0f;

    private UiText() {
    }

    /**
     * 一帧开始时设定上下文。
     *
     * @param vanillaFont 原版字体，矢量字体不可用时用它
     * @param scale       编辑器自己的缩放（{@code pose} 上那一层）
     * @param guiScale    游戏的 GUI Scale
     */
    static void begin(Font vanillaFont, float scale, int guiScale) {
        vanilla = vanillaFont;
        uiScale = scale <= 0f ? 1.0f : scale;
        vector = UiFont.get().fontFor(uiScale, guiScale);
    }

    /** 当前这一帧是不是在用矢量字体。界面上要据此说明清晰度从哪来。 */
    static boolean isVector() {
        return vector != null;
    }

    static Font vanillaFont() {
        return vanilla;
    }

    /**
     * 一行有多高（编辑器逻辑单位）。
     *
     * <p>恒为 9——矢量字体的字号是按 {@code BASE_SIZE × uiScale} 取的，除回逻辑单位之后
     * 正好落在原版的行高上。这不是巧合而是刻意选的基准：它让所有既有的行距、面板高度、
     * 垂直居中计算原封不动地继续成立。
     */
    static int lineHeight() {
        return vanilla != null ? vanilla.lineHeight : 9;
    }

    /** 字符串宽度（编辑器逻辑单位）。 */
    static int width(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        if (vector != null) {
            // 矢量字体的度量是 Minecraft 逻辑单位（字号里已经含了 uiScale），除回去
            return Math.round(vector.width(text) / uiScale);
        }
        return vanilla != null ? vanilla.width(text) : text.length() * 6;
    }

    static int width(char c) {
        return width(String.valueOf(c));
    }

    /**
     * 画一行文字。{@code x}/{@code y} 是编辑器逻辑坐标。
     *
     * <p>矢量字体路径上会把外层的 {@code uiScale} 抵消掉，坐标乘回去并<b>取整</b>——
     * 取整这一步不能省：字形落在半个像素上时，抗锯齿会把每一根竖线都糊成两根浅的。
     */
    static void draw(GuiGraphicsExtractor g, String text, int x, int y, int color) {
        if (vector == null) {
            if (vanilla != null) {
                g.text(vanilla, text, x, y, color, false);
            }
            return;
        }
        g.pose().pushMatrix();
        g.pose().scale(1f / uiScale, 1f / uiScale);
        g.text(vector, text, Math.round(x * uiScale), Math.round(y * uiScale), color, false);
        g.pose().popMatrix();
    }
}
