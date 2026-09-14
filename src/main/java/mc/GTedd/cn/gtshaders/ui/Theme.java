package mc.GTedd.cn.gtshaders.ui;

/**
 * 配色，对齐 MasterGo 的浅色工作台。
 *
 * <p>面板<b>不透明</b>而中央画布区完全不画——这不是随手定的：预览效果作用于整个游戏画面，
 * 中间越空，作者越能看清自己正在调的东西；而面板要压在游戏画面上，半透明会让文字糊掉。
 */
public final class Theme {

    // ---- 面板 ----
    public static final int PANEL = 0xFFFFFFFF;
    public static final int PANEL_ALT = 0xFFF7F8FA;
    public static final int PANEL_SUNKEN = 0xFFF2F3F5;
    public static final int BORDER = 0xFFE5E6EB;
    public static final int BORDER_STRONG = 0xFFD0D3DA;
    public static final int SHADOW = 0x1A000000;

    // ---- 文字 ----
    public static final int TEXT = 0xFF1F2329;
    public static final int TEXT_SUB = 0xFF646A73;
    public static final int TEXT_DIM = 0xFF8F959E;
    public static final int TEXT_ON_ACCENT = 0xFFFFFFFF;

    // ---- 强调色 ----
    public static final int ACCENT = 0xFF2E6BE6;
    public static final int ACCENT_HOVER = 0xFF1F5BD6;
    /** 选中行的浅蓝底，取自截图里图层树的选中态。 */
    public static final int ACCENT_SOFT = 0xFFDCE4FA;
    public static final int SUCCESS = 0xFF17A673;
    public static final int WARNING = 0xFFE08A00;
    public static final int ERROR = 0xFFE5484D;

    // ---- 控件 ----
    public static final int INPUT_BG = 0xFFF2F3F5;
    public static final int INPUT_BG_FOCUS = 0xFFFFFFFF;
    public static final int BUTTON_BG = 0xFFFFFFFF;
    public static final int BUTTON_HOVER = 0xFFF0F1F3;
    public static final int BUTTON_ACTIVE_BG = 0xFFE8EEFC;
    /** 拖面板时目标区的高亮底色。半透明——底下那块内容要仍然看得见，才知道自己盖住了什么。 */
    public static final int DOCK_DROP_FILL = 0x332E6BE6;

    public static final int SLIDER_TRACK = 0xFFE5E6EB;
    public static final int SLIDER_FILL = ACCENT;
    /** 截图里的拖柄是深色实心圆点，不是白色。 */
    public static final int SLIDER_KNOB = 0xFF2B2F36;
    public static final int TOGGLE_OFF = 0xFFD0D3DA;
    /** 滚动条：轨道几乎看不见，滑块也只是比背景深一点——它不该抢代码的注意力。 */
    public static final int SCROLL_TRACK = 0x14000000;
    public static final int SCROLL_THUMB = 0xFFC4C9D1;

    // ---- 画布 ----
    public static final int CANVAS_FRAME = ACCENT;
    /** 未选中的层：一圈淡淡的框，说明「这里还有一层」但不抢注意力。 */
    public static final int CANVAS_FRAME_IDLE = 0x552E6BE6;
    public static final int CANVAS_HANDLE_FILL = 0xFFFFFFFF;
    public static final int CANVAS_BADGE = ACCENT;

    // ---- 代码编辑器（浅色，与整体一致） ----
    public static final int CODE_BG = 0xFFFFFFFF;
    public static final int CODE_GUTTER_BG = 0xFFF7F8FA;
    public static final int CODE_LINE_NO = 0xFFB0B5BD;
    public static final int CODE_CURSOR_LINE = 0x14000000;
    public static final int CODE_SELECTION = 0x442E6BE6;
    public static final int CODE_ERROR_LINE = 0x22E5484D;

    public static final int SYN_PLAIN = 0xFF1F2329;
    public static final int SYN_KEYWORD = 0xFF9A3FBF;
    public static final int SYN_TYPE = 0xFF1F6FEB;
    public static final int SYN_BUILTIN = 0xFF0F7B6C;
    public static final int SYN_NUMBER = 0xFFC2410C;
    public static final int SYN_COMMENT = 0xFF8A9199;
    public static final int SYN_ANNOTATION = 0xFFB45309;
    public static final int SYN_PREPROC = 0xFFBE185D;

    private Theme() {
    }

    /** 把 0..1 的 RGB 打成不透明 ARGB，取色器色块用。 */
    public static int rgb(float r, float g, float b) {
        return 0xFF000000 | (clamp255(r) << 16) | (clamp255(g) << 8) | clamp255(b);
    }

    private static int clamp255(float v) {
        return Math.max(0, Math.min(255, Math.round(v * 255f)));
    }
}
