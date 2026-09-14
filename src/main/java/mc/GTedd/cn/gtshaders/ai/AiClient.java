package mc.GTedd.cn.gtshaders.ai;

import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * 一次「把话说给模型、把回答拿回来」。
 *
 * <h2>这个接口存在的全部意义</h2>
 *
 * <p>把两种协议的差异<b>关在实现里</b>。Responses 和 Chat Completions 在请求体、
 * 流式事件、错误形状上处处不同，但对上层来说要做的事只有一件：给一段文本，收一段文本。
 * 一旦让 {@code if (protocol == …)} 漏到 {@link ShaderSmith} 或界面层，
 * 那个分支就再也去不掉了——每加一个功能都要在两处各写一遍。
 *
 * <p>所以选实现只发生在 {@link #of(AiProtocol)} 这一处。
 */
public interface AiClient {

    /** 对话中的一轮。role 只用 {@code user} 和 {@code assistant}，系统提示单独传。 */
    record Turn(String role, String content) {
        public static Turn user(String content) {
            return new Turn("user", content);
        }

        public static Turn assistant(String content) {
            return new Turn("assistant", content);
        }
    }

    /**
     * 发一次请求，把模型输出的纯文本拿回来。<b>会阻塞，必须在后台线程里调。</b>
     *
     * @param instructions 系统提示。Responses 走 {@code instructions} 字段，
     *                     Chat 走 {@code messages[0]}——差别由实现自己消化
     * @param onDelta      流式增量回调，非流式时不会被调用；回调发生在调用线程上
     * @param cancelled    随时可能变 true 的取消信号，流式时每帧检查一次
     */
    String send(AiEndpoint endpoint, String instructions, List<Turn> input, boolean stream,
                @Nullable Consumer<String> onDelta, @Nullable BooleanSupplier cancelled);

    /** 按协议挑实现。<b>整个功能里唯一一处按协议分叉的地方。</b> */
    static AiClient of(AiProtocol protocol) {
        return protocol == AiProtocol.RESPONSES ? new ResponsesClient() : new ChatClient();
    }
}
