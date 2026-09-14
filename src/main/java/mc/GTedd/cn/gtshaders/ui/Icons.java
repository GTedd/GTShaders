package mc.GTedd.cn.gtshaders.ui;

import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * 手绘矢量图标。
 *
 * <p>不用字符字形（▶ ⏸ ↺ 之类）是有原因的：Minecraft 默认字体对几何符号与杂项技术符号的覆盖
 * 并不完整，缺字时会显示成豆腐块，而且不同 GUI 缩放下字形大小不受控。用 {@code fill} 拼出来的
 * 图标始终按像素网格对齐，在任何缩放下都是清晰的，也能精确对上 MasterGo 那套细线图标的观感。
 *
 * <p>所有图标都画在 {@code size × size} 的方框里，坐标以左上角为原点。
 */
public final class Icons {

    private Icons() {
    }

    /** 水平线段。 */
    private static void h(GuiGraphicsExtractor g, int x, int y, int len, int thick, int c) {
        g.fill(x, y, x + len, y + thick, c);
    }

    /** 垂直线段。 */
    private static void v(GuiGraphicsExtractor g, int x, int y, int len, int thick, int c) {
        g.fill(x, y, x + thick, y + len, c);
    }

    /** 空心方框。 */
    private static void box(GuiGraphicsExtractor g, int x, int y, int w, int hgt, int c) {
        UiUtil.box(g, x, y, w, hgt, c);
    }

    /** 指向右侧的实心三角（播放）。 */
    public static void play(GuiGraphicsExtractor g, int x, int y, int size, int c) {
        int h = size - 2;
        for (int i = 0; i < h; i++) {
            int half = Math.min(i, h - 1 - i);
            int w = 1 + half;
            g.fill(x + 3, y + 1 + i, x + 3 + w, y + 2 + i, c);
        }
    }

    /** 两条竖杠（暂停）。 */
    public static void pause(GuiGraphicsExtractor g, int x, int y, int size, int c) {
        v(g, x + 3, y + 2, size - 4, 2, c);
        v(g, x + size - 5, y + 2, size - 4, 2, c);
    }

    /** 逆时针回转箭头（重置时间）。 */
    public static void reset(GuiGraphicsExtractor g, int x, int y, int size, int c) {
        int cx = x + size / 2;
        int cy = y + size / 2;
        int r = size / 2 - 2;
        // 用四段折线近似一个开口在右上的圆环
        h(g, cx - r, cy - r, 2 * r - 1, 1, c);
        h(g, cx - r, cy + r - 1, 2 * r, 1, c);
        v(g, cx - r, cy - r, 2 * r, 1, c);
        v(g, cx + r - 1, cy, r, 1, c);
        // 箭头（指向左的小三角）
        g.fill(cx - r - 1, cy - r - 1, cx - r + 2, cy - r + 2, c);
    }

    /** 顺时针刷新箭头（编译并应用）。 */
    public static void refresh(GuiGraphicsExtractor g, int x, int y, int size, int c) {
        int cx = x + size / 2;
        int cy = y + size / 2;
        int r = size / 2 - 2;
        h(g, cx - r + 1, cy - r, 2 * r - 1, 1, c);
        h(g, cx - r, cy + r - 1, 2 * r - 1, 1, c);
        v(g, cx - r, cy - r + 1, r, 1, c);
        v(g, cx + r - 1, cy - r, 2 * r, 1, c);
        g.fill(cx + r - 2, cy - r - 1, cx + r + 1, cy - r + 2, c);
    }

    /** 加号。 */
    public static void plus(GuiGraphicsExtractor g, int x, int y, int size, int c) {
        int cx = x + size / 2;
        int cy = y + size / 2;
        h(g, cx - size / 2 + 2, cy - 1, size - 4, 2, c);
        v(g, cx - 1, cy - size / 2 + 2, size - 4, 2, c);
    }

    /** 减号。 */
    public static void minus(GuiGraphicsExtractor g, int x, int y, int size, int c) {
        int cx = x + size / 2;
        int cy = y + size / 2;
        h(g, cx - size / 2 + 2, cy - 1, size - 4, 2, c);
    }

    /** 两个错开的方框（复制）。 */
    public static void duplicate(GuiGraphicsExtractor g, int x, int y, int size, int c) {
        int s = size - 5;
        box(g, x + 1, y + 1, s, s, c);
        box(g, x + 4, y + 4, s, s, c);
    }

    /** 垃圾桶（删除）。 */
    public static void trash(GuiGraphicsExtractor g, int x, int y, int size, int c) {
        int w = size - 6;
        h(g, x + 2, y + 3, size - 4, 1, c);
        h(g, x + size / 2 - 2, y + 1, 4, 1, c);
        box(g, x + 3, y + 4, w + 2, size - 6, c);
        v(g, x + size / 2 - 1, y + 6, size - 10, 1, c);
    }

    /** 上箭头。 */
    public static void arrowUp(GuiGraphicsExtractor g, int x, int y, int size, int c) {
        int cx = x + size / 2;
        v(g, cx - 1, y + 3, size - 6, 2, c);
        for (int i = 0; i < 4; i++) {
            g.fill(cx - 1 - i, y + 3 + i, cx + 1 + i, y + 4 + i, c);
        }
    }

    /** 下箭头。 */
    public static void arrowDown(GuiGraphicsExtractor g, int x, int y, int size, int c) {
        int cx = x + size / 2;
        v(g, cx - 1, y + 3, size - 6, 2, c);
        for (int i = 0; i < 4; i++) {
            g.fill(cx - 1 - i, y + size - 4 - i, cx + 1 + i, y + size - 3 - i, c);
        }
    }

    /** 眼睛（可见）。 */
    public static void eye(GuiGraphicsExtractor g, int x, int y, int size, int c) {
        int cy = y + size / 2;
        int cx = x + size / 2;
        h(g, x + 2, cy - 3, size - 4, 1, c);
        h(g, x + 2, cy + 2, size - 4, 1, c);
        v(g, x + 1, cy - 2, 4, 1, c);
        v(g, x + size - 2, cy - 2, 4, 1, c);
        g.fill(cx - 2, cy - 2, cx + 2, cy + 2, c);
    }

    /** 闭眼（隐藏）：眼睛加一道斜杠。 */
    public static void eyeOff(GuiGraphicsExtractor g, int x, int y, int size, int c) {
        int cy = y + size / 2;
        h(g, x + 2, cy, size - 4, 1, c);
        for (int i = 0; i < size - 2; i++) {
            g.fill(x + 1 + i, y + size - 2 - i, x + 2 + i, y + size - 1 - i, c);
        }
    }

    /** 放大镜（搜索）。 */
    public static void search(GuiGraphicsExtractor g, int x, int y, int size, int c) {
        int r = size / 2 - 2;
        int cx = x + r + 1;
        int cy = y + r + 1;
        box(g, cx - r, cy - r, 2 * r, 2 * r, c);
        for (int i = 0; i < 3; i++) {
            g.fill(cx + r + i - 1, cy + r + i - 1, cx + r + i, cy + r + i, c);
        }
    }

    /** 叉（关闭）。 */
    public static void close(GuiGraphicsExtractor g, int x, int y, int size, int c) {
        for (int i = 2; i < size - 2; i++) {
            g.fill(x + i, y + i, x + i + 1, y + i + 1, c);
            g.fill(x + size - 1 - i, y + i, x + size - i, y + i + 1, c);
        }
    }

    /**
     * 四角星加一颗小星（AI 生成）。
     *
     * <p>四条臂从中心向外收细，画出来才是「星」而不是「加号」——等宽的十字在小尺寸下
     * 和 {@link #plus} 分不清，而这两个按钮就挨在顶栏上。
     */
    public static void sparkle(GuiGraphicsExtractor g, int x, int y, int size, int c) {
        int cx = x + size / 2;
        int cy = y + size / 2;
        int arm = size / 2 - 1;
        for (int i = 0; i < arm; i++) {
            // 越靠外越细：i 到臂端时厚度收到 1
            int t = Math.max(1, (arm - i + 1) / 2);
            int off = t / 2;
            g.fill(cx - off, cy - i - 1, cx - off + t, cy - i, c);
            g.fill(cx - off, cy + i, cx - off + t, cy + i + 1, c);
            g.fill(cx - i - 1, cy - off, cx - i, cy - off + t, c);
            g.fill(cx + i, cy - off, cx + i + 1, cy - off + t, c);
        }
        // 右上角那颗小的：让它一眼区别于任何十字形图标
        g.fill(x + size - 3, y + 1, x + size - 1, y + 2, c);
        g.fill(x + size - 2, y, x + size - 1, y + 3, c);
    }

    /** 向下的小三角（下拉）。 */
    public static void chevronDown(GuiGraphicsExtractor g, int x, int y, int size, int c) {
        int cx = x + size / 2;
        int cy = y + size / 2 - 1;
        for (int i = 0; i < 4; i++) {
            g.fill(cx - 3 + i, cy + i, cx - 2 + i, cy + i + 1, c);
            g.fill(cx + 3 - i, cy + i, cx + 4 - i, cy + i + 1, c);
        }
    }

    /** 软盘（保存）。 */
    public static void save(GuiGraphicsExtractor g, int x, int y, int size, int c) {
        box(g, x + 2, y + 2, size - 4, size - 4, c);
        g.fill(x + 5, y + 2, x + size - 5, y + size / 2 - 1, c);
        box(g, x + 5, y + size / 2 + 1, size - 10, size / 2 - 3, c);
    }

    /** 盒子加向上箭头（导出）。 */
    public static void export(GuiGraphicsExtractor g, int x, int y, int size, int c) {
        h(g, x + 2, y + size - 3, size - 4, 1, c);
        v(g, x + 2, y + size - 6, 4, 1, c);
        v(g, x + size - 3, y + size - 6, 4, 1, c);
        int cx = x + size / 2;
        v(g, cx - 1, y + 3, size - 8, 2, c);
        for (int i = 0; i < 4; i++) {
            g.fill(cx - 1 - i, y + 3 + i, cx + 1 + i, y + 4 + i, c);
        }
    }

    /**
     * 文件夹（浏览目录）。
     *
     * <p>左上角那截凸起的「标签」是认出它的唯一特征——只画个方框的话，
     * 和旁边的输入框、按钮框糊成一片，看着像装饰而不是可点的东西。
     */
    public static void folder(GuiGraphicsExtractor g, int x, int y, int size, int c) {
        int top = y + 4;
        int bottom = y + size - 3;
        box(g, x + 2, top, size - 4, bottom - top, c);
        int tabW = size / 2 - 2;
        h(g, x + 2, top - 2, tabW, 1, c);
        v(g, x + 2, top - 2, 2, 1, c);
        v(g, x + 2 + tabW - 1, top - 2, 2, 1, c);
    }

    /** 地球（语言）。 */
    public static void globe(GuiGraphicsExtractor g, int x, int y, int size, int c) {
        int r = size / 2 - 2;
        int cx = x + size / 2;
        int cy = y + size / 2;
        box(g, cx - r, cy - r, 2 * r, 2 * r, c);
        h(g, cx - r, cy, 2 * r, 1, c);
        v(g, cx, cy - r, 2 * r, 1, c);
    }

    /** 三条横线（图层/菜单）。 */
    public static void layers(GuiGraphicsExtractor g, int x, int y, int size, int c) {
        h(g, x + 2, y + 3, size - 4, 1, c);
        h(g, x + 2, y + size / 2, size - 4, 1, c);
        h(g, x + 2, y + size - 4, size - 4, 1, c);
    }

    /** 鼠标指针（选择工具）。 */
    public static void cursor(GuiGraphicsExtractor g, int x, int y, int size, int c) {
        for (int i = 0; i < size - 4; i++) {
            g.fill(x + 3, y + 2 + i, x + 4 + i / 2, y + 3 + i, c);
        }
        g.fill(x + 3 + (size - 6) / 2, y + size - 5, x + 5 + (size - 6) / 2, y + size - 1, c);
    }

    /** 方框（画布/取景框）。 */
    public static void frame(GuiGraphicsExtractor g, int x, int y, int size, int c) {
        box(g, x + 2, y + 2, size - 4, size - 4, c);
        h(g, x + 2, y + 5, size - 4, 1, c);
    }

    /**
     * git 风格的提交节点：一条竖线穿过一个菱形。
     *
     * <p>用节点而不是软盘（保存）图标，是因为这两件事真的不一样：保存覆盖同一个工程文件，
     * 提交则是在时间线上多钉一个点。图标先把这层语义说清楚。
     */
    public static void commit(GuiGraphicsExtractor g, int x, int y, int size, int c) {
        int cx = x + size / 2;
        int cy = y + size / 2;
        v(g, cx - 1, y + 2, Math.max(1, cy - y - 5), 2, c);
        v(g, cx - 1, cy + 3, Math.max(1, y + size - 2 - (cy + 3)), 2, c);
        for (int i = -3; i <= 3; i++) {
            int half = 3 - Math.abs(i);
            g.fill(cx - half - 1, cy + i, cx + half + 1, cy + i + 1, c);
        }
    }

    /**
     * 折叠三角。展开时朝下、收起时朝右。
     *
     * <p>手绘而不是写 {@code "▾"}：几何符号在系统中文字体里经常是缺的（雅黑、苹方都不保证），
     * 而界面一旦换成玩家自己的 TTF，缺字就会变成一个个豆腐块。这也是这个文件里
     * 所有图标都手绘的原因。
     */
    public static void caret(GuiGraphicsExtractor g, int x, int y, int size, int c, boolean expanded) {
        int cx = x + size / 2;
        int cy = y + size / 2;
        int r = Math.max(2, size / 4);
        for (int i = 0; i < r; i++) {
            if (expanded) {
                // 朝下：每往下一行收窄一格
                g.fill(cx - r + i, cy - r / 2 + i, cx + r - i, cy - r / 2 + i + 1, c);
            } else {
                // 朝右：每往右一列收窄一格
                g.fill(cx - r / 2 + i, cy - r + i, cx - r / 2 + i + 1, cy + r - i, c);
            }
        }
    }

    /** 九宫格（效果库）。四个小方块——「一堆现成的东西」这个意思，比放大镜准确。 */
    public static void grid(GuiGraphicsExtractor g, int x, int y, int size, int c) {
        int s = Math.max(3, (size - 5) / 2);
        int gap = 2;
        g.fill(x + 2, y + 2, x + 2 + s, y + 2 + s, c);
        g.fill(x + 3 + s + gap - 1, y + 2, x + 3 + 2 * s + gap - 1, y + 2 + s, c);
        g.fill(x + 2, y + 3 + s + gap - 1, x + 2 + s, y + 3 + 2 * s + gap - 1, c);
        g.fill(x + 3 + s + gap - 1, y + 3 + s + gap - 1, x + 3 + 2 * s + gap - 1,
                y + 3 + 2 * s + gap - 1, c);
    }

    /** 问号（帮助）。 */
    public static void help(GuiGraphicsExtractor g, int x, int y, int size, int c) {
        int cx = x + size / 2;
        int top = y + 3;
        h(g, cx - 3, top, 6, 2, c);
        v(g, cx + 2, top, 4, 2, c);
        h(g, cx - 1, top + 4, 4, 2, c);
        v(g, cx - 1, top + 5, 3, 2, c);
        g.fill(cx - 1, y + size - 4, cx + 1, y + size - 2, c);
    }

    /** 铅笔（重命名）。斜向笔杆加一个笔尖。 */
    public static void pencil(GuiGraphicsExtractor g, int x, int y, int size, int c) {
        int n = size - 7;
        for (int i = 0; i < n; i++) {
            g.fill(x + size - 4 - i, y + 3 + i, x + size - 2 - i, y + 5 + i, c);
        }
        g.fill(x + 3, y + size - 6, x + 6, y + size - 3, c);
    }
}
