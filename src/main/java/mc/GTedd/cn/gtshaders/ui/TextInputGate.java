package mc.GTedd.cn.gtshaders.ui;

import net.minecraft.client.Minecraft;

/**
 * 自绘输入控件的「文本输入开关」。
 *
 * <h2>为什么必须有这个东西</h2>
 *
 * <p>26.3 把输入层从 GLFW 换成了 SDL3，而 SDL 的文本输入是<b>需要显式打开的</b>：
 * 不调用 {@code SDL_StartTextInput}，就一个 {@code SDL_EVENT_TEXT_INPUT} 都收不到。
 * 原版 {@code EditBox} 在获得焦点时会通过 {@code Minecraft.onTextInputFocusChange}
 * 把它打开，而我们所有自绘的输入控件都不在那条路径上。
 *
 * <p>症状极具迷惑性：<b>退格、方向键、Ctrl+V 全都正常，唯独打不进字</b>。
 * 因为按键走的是 SDL 的 KEY_DOWN 事件，那条通道一直开着；
 * 只有字符走 TEXT_INPUT，而它没被打开。于是看起来像「这个框只能删不能写」，
 * 完全想不到是少调了一个开关。
 *
 * <h2>为什么不走 Minecraft.onTextInputFocusChange</h2>
 *
 * <p>那个方法要求传一个 {@code GuiEventListener}，而我们的 {@link TextField}、
 * {@link CodeEditor} 都是纯自绘对象，不在原版的控件树里。
 * {@link com.mojang.blaze3d.platform.TextInputManager#startTextInput(Object)} 收的是
 * {@code Object}，正好绕开这个限制。
 *
 * <p>代价是拿不到 IME 的预编辑（preedit）事件转发——中文输入法的候选框不会跟着光标走。
 * 但候选框的位置可以用 {@link #setArea} 单独告诉系统，而输入本身是通的：
 * IME 提交之后照样是一个 TEXT_INPUT 事件。
 */
final class TextInputGate {

    private TextInputGate() {
    }

    /** 控件获得焦点时调。{@code owner} 用控件自身，停的时候要对得上。 */
    static void begin(Object owner) {
        Minecraft mc = client();
        if (mc != null) {
            mc.textInputManager().startTextInput(owner);
        }
    }

    /**
     * 控件失去焦点时调。
     *
     * <p>按 owner 停而不是无条件停：两个输入框交接焦点时，新的那个先 begin、
     * 旧的后 end 是完全可能的顺序，无条件停会把刚打开的又关掉。
     */
    static void end(Object owner) {
        Minecraft mc = client();
        if (mc != null) {
            mc.textInputManager().stopTextInput(owner);
        }
    }

    /**
     * 告诉系统输入区在屏幕上的位置，输入法的候选框会贴着它弹。
     *
     * <p>不给的话候选框会跑到窗口左上角——中文输入时那一小串拼音离光标十万八千里。
     */
    static void setArea(int x, int y, int width, int height) {
        Minecraft mc = client();
        if (mc != null) {
            mc.textInputManager().setTextInputArea(x, y, width, height);
        }
    }

    /**
     * 拿客户端实例；拿不到就返回 null。
     *
     * <p>捕 {@link Throwable} 是为了单元测试：那里没有 Minecraft 运行时，
     * 连类加载都可能失败。输入法开关缺席不影响任何纯逻辑测试，
     * 但让测试因为它整片挂掉就很没道理。
     */
    private static Minecraft client() {
        try {
            return Minecraft.getInstance();
        } catch (Throwable ignored) {
            return null;
        }
    }
}
