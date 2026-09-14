package mc.GTedd.cn.gtshaders.ui;

import com.mojang.blaze3d.platform.InputConstants;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 覆盖输入框的字符准入与长度上限。
 *
 * <h2>为什么这几条值得钉住</h2>
 *
 * <p>超出上限的字符是<b>静默丢弃</b>的：界面上没有任何提示，光标也照常闪，
 * 只是敲下去的字不出现。这类故障玩家的描述永远是「打不进去字」，
 * 而真正的原因（长度上限定得太小）离那个现象非常远。
 *
 * <p>{@link TextField} 原本给所有 {@code TEXT} 字段写死 64 个字符——那是按层名、
 * 搜索词定的。AI 面板拿它装 API Key 之后就出了事：OpenAI 兼容服务的
 * {@code sk-proj-…} 上百字符，粘进去只进前 64 个，最后只收到一句 401。
 *
 * <p>这里只测不碰 GL 与 Minecraft 运行时的部分：字符准入、长度、以及全选替换。
 * 渲染和剪贴板要真实客户端，交给实机。
 */
class TextFieldTest {

    private static TextField focused(TextField.Kind kind, int maxLength) {
        TextField f = new TextField(kind, maxLength);
        f.focus();
        return f;
    }

    private static void type(TextField f, String s) {
        for (int i = 0; i < s.length(); i++) {
            f.charTyped(s.charAt(i));
        }
    }

    @Test
    void textFieldDefaultsToTheShortLimit() {
        TextField f = new TextField(TextField.Kind.TEXT);
        f.focus();
        type(f, "x".repeat(TextField.DEFAULT_MAX_LENGTH + 20));
        assertEquals(TextField.DEFAULT_MAX_LENGTH, f.text().length());
    }

    /** 这条就是那个 bug 的回归：长 key 必须能整串进去。 */
    @Test
    void longApiKeyFitsWhenTheFieldAsksForRoom() {
        TextField f = focused(TextField.Kind.TEXT, 512);
        String key = "sk-proj-" + "aB3".repeat(60);
        assertTrue(key.length() > TextField.DEFAULT_MAX_LENGTH,
                "这条测试要有意义，样本得比默认上限长");
        type(f, key);
        assertEquals(key, f.text());
    }

    @Test
    void asciiLettersAndDigitsGoIn() {
        TextField f = focused(TextField.Kind.TEXT, 256);
        String s = "https://api.deepseek.com/v1 deepseek-v4-pro 0123456789";
        type(f, s);
        assertEquals(s, f.text());
    }

    @Test
    void overflowIsDroppedSilentlyAtTheLimit() {
        TextField f = focused(TextField.Kind.TEXT, 8);
        type(f, "abcdefghijkl");
        assertEquals("abcdefgh", f.text());
    }

    /** 长度上限只管 TEXT；另外两种有自己的天然长度，不该被这个参数影响。 */
    @Test
    void numberAndHexKeepTheirOwnRules() {
        TextField num = focused(TextField.Kind.NUMBER, 512);
        type(num, "-12.34abc");
        assertEquals("-12.34", num.text());

        TextField hex = focused(TextField.Kind.HEX, 512);
        type(hex, "7FD4FFZZ99");
        assertEquals("7FD4FF", hex.text());
    }

    /**
     * Ctrl+A 之后再输入要顶掉整串。
     *
     * <p>原来 Ctrl+A 只是把光标挪到末尾，于是「全选再粘一把新 key」得到的是
     * 旧 key 拼上新 key——超长、也不对，而报出来的只是一句 401。
     */
    @Test
    void selectAllThenTypeReplacesEverything() {
        TextField f = focused(TextField.Kind.TEXT, 256);
        type(f, "sk-old-key");
        f.keyPressed(InputConstants.KEY_A, InputConstants.MOD_CONTROL);
        type(f, "sk-new");
        assertEquals("sk-new", f.text());
    }

    @Test
    void selectAllThenBackspaceClearsEverything() {
        TextField f = focused(TextField.Kind.TEXT, 256);
        type(f, "sk-old-key");
        f.keyPressed(InputConstants.KEY_A, InputConstants.MOD_CONTROL);
        f.keyPressed(InputConstants.KEY_BACKSPACE, 0);
        assertEquals("", f.text());
    }

    /** 移动光标要取消全选，否则「看一眼开头再接着敲」会把整串弄没。 */
    @Test
    void movingTheCaretCancelsSelectAll() {
        TextField f = focused(TextField.Kind.TEXT, 256);
        type(f, "abcd");
        f.keyPressed(InputConstants.KEY_A, InputConstants.MOD_CONTROL);
        f.keyPressed(InputConstants.KEY_HOME, 0);
        type(f, "X");
        assertEquals("Xabcd", f.text());
    }

    @Test
    void controlCharactersNeverEnterTheField() {
        TextField f = focused(TextField.Kind.TEXT, 256);
        type(f, "a");
        f.charTyped('\n');
        f.charTyped('\t');
        f.charTyped('\r');
        assertEquals("a", f.text());
    }

    /** 外部同步值时不受上限之外的状态影响，且光标要落到末尾。 */
    @Test
    void externalValueReplacesWhenNotFocused() {
        TextField f = new TextField(TextField.Kind.TEXT, 256);
        f.setTextIfUnfocused("https://api.deepseek.com");
        assertEquals("https://api.deepseek.com", f.text());

        f.focus();
        f.setTextIfUnfocused("ignored-while-editing");
        assertEquals("https://api.deepseek.com", f.text(),
                "正在编辑时被外部值冲掉，等于玩家敲的东西凭空消失");
    }
}
