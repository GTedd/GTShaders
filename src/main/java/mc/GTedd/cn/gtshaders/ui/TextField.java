package mc.GTedd.cn.gtshaders.ui;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * 单行文本输入框，自绘。
 *
 * <p>存在的理由是「可操控性」：只有滑块的话，参数只能拖个大概值，而着色器里
 * 「把速度精确设成 2.6」是很常见的需求。截图里 MasterGo 的每个参数右侧都配了一个可键入的
 * 数值框，这里对齐同样的行为——滑块用来找感觉，输入框用来定死数值。
 *
 * <p>刻意不复用原版 {@code EditBox}：它的外观和 MasterGo 的浅色圆角输入框差得太远，
 * 而且原版的事件签名已经换成 record，套一层适配反而更啰嗦。
 */
public final class TextField {

    /** 允许输入的字符类型。 */
    public enum Kind {
        /** 数字，允许负号与小数点。 */
        NUMBER,
        /** 十六进制颜色，只允许 0-9a-fA-F，最长 6 位。 */
        HEX,
        /** 任意文本。 */
        TEXT
    }

    /**
     * {@link Kind#TEXT} 的默认长度上限。
     *
     * <p>够用于层名、工程名、搜索词这些「一眼能看完」的短文本。
     * 需要装 URL、API Key、一整句需求的字段必须自己调大——见 {@link #TextField(Kind, int)}。
     */
    public static final int DEFAULT_MAX_LENGTH = 64;

    private final Kind kind;
    private final int maxLength;
    private String text = "";
    private boolean focused;
    private int cursor;
    /**
     * 可见窗口的起始字符下标。
     *
     * <p>没有它的话，长文本只能从头显示、右边被裁掉，光标一走出框就再也看不见自己在敲什么——
     * 粘贴一把上百字符的 API Key 进来，画面上永远停在前二十几个字符，
     * 完全无法确认粘对了没有。
     */
    private int scroll;
    /**
     * 整串处于「已全选」状态，下一次输入会顶掉它。
     *
     * <p>没有做真正的区间选择——单行框里 Ctrl+A 之后紧跟着的动作几乎总是「重打一遍」，
     * 支持这一种就够，而区间选择要牵进拖选、Shift+方向键、双击选词一整套。
     */
    private boolean selectAll;
    /** 获得焦点时的原值：按 Esc 撤销时回到它。 */
    private String textOnFocus = "";
    private Runnable onCommit = () -> {
    };

    public TextField(Kind kind) {
        this(kind, DEFAULT_MAX_LENGTH);
    }

    /**
     * @param maxLength {@link Kind#TEXT} 的字符上限；另外两种 Kind 有自己的天然长度，忽略这个值
     */
    public TextField(Kind kind, int maxLength) {
        this.kind = kind;
        this.maxLength = Math.max(1, maxLength);
    }

    public void setOnCommit(Runnable onCommit) {
        this.onCommit = onCommit == null ? () -> {
        } : onCommit;
    }

    public String text() {
        return text;
    }

    /** 外部同步值。输入框正在编辑时不覆盖，否则会把用户正在敲的内容冲掉。 */
    public void setTextIfUnfocused(String value) {
        if (!focused) {
            this.text = value == null ? "" : value;
            this.cursor = this.text.length();
            this.scroll = 0;
            this.selectAll = false;
        }
    }

    public boolean isFocused() {
        return focused;
    }

    public void focus() {
        focused = true;
        textOnFocus = text;
        cursor = text.length();
        selectAll = false;
        // 26.3 起 SDL 的文本输入要显式打开，不然只收得到按键、收不到字符
        TextInputGate.begin(this);
    }

    public void blur() {
        if (focused) {
            focused = false;
            selectAll = false;
            scroll = 0;
            TextInputGate.end(this);
            onCommit.run();
        }
    }

    public Float asFloat() {
        try {
            return Float.parseFloat(text.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public Integer asHex() {
        try {
            return Integer.parseInt(text.trim(), 16);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public void render(GuiGraphicsExtractor g, Font font, int x, int y, int w, int h,
                       boolean hovered, boolean rightAlign) {
        UiUtil.roundRect(g, x, y, w, h, 4, focused ? Theme.INPUT_BG_FOCUS : Theme.INPUT_BG);
        if (focused) {
            UiUtil.box(g, x, y, w, h, Theme.ACCENT);
        } else if (hovered) {
            UiUtil.box(g, x, y, w, h, Theme.BORDER_STRONG);
        }

        int ty = y + (h - font.lineHeight) / 2 + 1;
        int innerW = Math.max(1, w - 10);
        if (focused) {
            TextInputGate.setArea(x, y, w, h);
        }

        // 右对齐的都是数值框，短到不会溢出，保持原来的整串省略行为
        if (rightAlign) {
            String shown = UiUtil.ellipsize(font, text, innerW);
            int tx = x + w - 5 - UiText.width(shown);
            UiText.draw(g, shown, tx, ty, Theme.TEXT);
            if (focused && caretOn()) {
                int cx = tx + UiText.width(shown.substring(0, Math.min(cursor, shown.length())));
                g.fill(cx, ty - 1, cx + 1, ty + font.lineHeight, Theme.TEXT);
            }
            return;
        }

        String shown = windowText(innerW);
        int tx = x + 5;
        if (selectAll && !shown.isEmpty()) {
            // 不画出来的话，玩家不知道下一个键会把整串顶掉
            g.fill(tx - 1, ty - 1, tx + UiText.width(shown) + 1, ty + font.lineHeight, Theme.CODE_SELECTION);
        }
        UiText.draw(g, shown, tx, ty, Theme.TEXT);

        if (focused && caretOn()) {
            int cx = tx + UiText.width(text.substring(scroll, cursor));
            g.fill(cx, ty - 1, cx + 1, ty + font.lineHeight, Theme.TEXT);
        }
    }

    private static boolean caretOn() {
        return (System.currentTimeMillis() / 500) % 2 == 0;
    }

    /**
     * 算出当前该显示哪一段，并把 {@link #scroll} 调到让光标可见。
     *
     * <p>三步各管一种情况：光标往左退出窗口、光标往右超出窗口、文本变短后右边空出一片。
     * 每帧都跑，但 scroll 通常只差一两个字符，循环跑不了几次。
     */
    private String windowText(int innerW) {
        cursor = Math.clamp(cursor, 0, text.length());
        scroll = Math.clamp(scroll, 0, text.length());

        // 没在编辑时一律显示开头：那一眼是用来认「这个框里装的是什么」的，
        // 停在上次编辑的位置只会让人看到半截 URL
        if (!focused) {
            scroll = 0;
            String head = text;
            while (!head.isEmpty() && UiText.width(head) > innerW) {
                head = head.substring(0, head.length() - 1);
            }
            return head;
        }

        if (scroll > cursor) {
            scroll = cursor;
        }
        while (scroll < cursor && UiText.width(text.substring(scroll, cursor)) > innerW) {
            scroll++;
        }
        // 删掉一段之后右边会空出来，把窗口往回拉，否则前面的内容再也看不到了
        while (scroll > 0 && UiText.width(text.substring(scroll - 1)) <= innerW) {
            scroll--;
        }

        String shown = text.substring(scroll);
        while (!shown.isEmpty() && UiText.width(shown) > innerW) {
            shown = shown.substring(0, shown.length() - 1);
        }
        return shown;
    }

    public boolean mouseClicked(double mx, double my, int x, int y, int w, int h) {
        boolean inside = mx >= x && mx < x + w && my >= y && my < y + h;
        if (inside) {
            if (!focused) {
                focus();
            }
            return true;
        }
        blur();
        return false;
    }

    public boolean keyPressed(int key, int modifiers) {
        if (!focused) {
            return false;
        }
        boolean ctrl = (modifiers & InputConstants.MOD_CONTROL) != 0;
        if (ctrl && key == InputConstants.KEY_V) {
            for (char c : Minecraft.getInstance().keyboardHandler.getClipboard().toCharArray()) {
                // 从网页复制 API Key 很容易带上换行。单行框里它本来就没有意义，
                // 而混进 Authorization 头会让 HttpClient 直接抛异常——
                // 报出来的和「key 不对」长得一模一样，查不出来
                if (Character.isISOControl(c)) {
                    continue;
                }
                insert(c);
            }
            return true;
        }
        if (ctrl && key == InputConstants.KEY_A) {
            // 真正的「全选」：下一次输入或粘贴会顶掉整串。
            // 原来这里只是把光标挪到末尾，于是「Ctrl+A 再 Ctrl+V 换一把 key」
            // 得到的是旧 key 拼上新 key——超长、也不对，而报出来的只是一句 401
            selectAll = !text.isEmpty();
            cursor = text.length();
            return true;
        }
        switch (key) {
            case InputConstants.KEY_BACKSPACE -> {
                if (consumeSelection()) {
                    return true;
                }
                if (cursor > 0) {
                    text = text.substring(0, cursor - 1) + text.substring(cursor);
                    cursor--;
                }
                return true;
            }
            case InputConstants.KEY_DELETE -> {
                if (consumeSelection()) {
                    return true;
                }
                if (cursor < text.length()) {
                    text = text.substring(0, cursor) + text.substring(cursor + 1);
                }
                return true;
            }
            case InputConstants.KEY_LEFT -> {
                selectAll = false;
                cursor = Math.max(0, cursor - 1);
                return true;
            }
            case InputConstants.KEY_RIGHT -> {
                selectAll = false;
                cursor = Math.min(text.length(), cursor + 1);
                return true;
            }
            case InputConstants.KEY_HOME -> {
                selectAll = false;
                cursor = 0;
                return true;
            }
            case InputConstants.KEY_END -> {
                selectAll = false;
                cursor = text.length();
                return true;
            }
            case InputConstants.KEY_RETURN, InputConstants.KEY_NUMPADENTER, InputConstants.KEY_TAB -> {
                blur();
                return true;
            }
            case InputConstants.KEY_ESCAPE -> {
                // 撤销本次编辑：输入到一半反悔时不该把原值改掉
                text = textOnFocus;
                cursor = text.length();
                focused = false;
                selectAll = false;
                scroll = 0;
                TextInputGate.end(this);
                return true;
            }
            default -> {
                return false;
            }
        }
    }

    public boolean charTyped(int codepoint) {
        if (!focused || Character.isISOControl(codepoint)) {
            return false;
        }
        insert((char) codepoint);
        return true;
    }

    private void insert(char c) {
        consumeSelection();
        if (!accepts(c)) {
            return;
        }
        text = text.substring(0, cursor) + c + text.substring(cursor);
        cursor++;
    }

    /** 有全选就把整串清掉，返回是否清了。顺序很重要：先清空再判长度上限，否则换不了长值。 */
    private boolean consumeSelection() {
        if (!selectAll) {
            return false;
        }
        selectAll = false;
        text = "";
        cursor = 0;
        scroll = 0;
        return true;
    }

    private boolean accepts(char c) {
        return switch (kind) {
            case NUMBER -> (c >= '0' && c <= '9')
                    // 小数点和负号各自最多一个，否则会拼出解析不了的串
                    || (c == '.' && text.indexOf('.') < 0)
                    || (c == '-' && cursor == 0 && text.indexOf('-') < 0);
            case HEX -> text.length() < 6
                    && ((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F'));
            case TEXT -> text.length() < maxLength;
        };
    }
}
