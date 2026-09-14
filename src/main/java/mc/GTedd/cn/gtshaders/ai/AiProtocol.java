package mc.GTedd.cn.gtshaders.ai;

import java.util.Locale;

/**
 * 服务商说哪种协议。
 *
 * <h2>为什么最终还是做了两种</h2>
 *
 * <p>本来只做 Responses API，理由是「两套请求体、两套流式事件、两套错误形状，
 * 差异会渗进上层」。但查下来的事实是：除了 OpenAI 和 DeepSeek，
 * 智谱、Kimi、阿里百炼、硅基流动、OpenRouter 这些<b>普遍只提供 Chat Completions 兼容</b>。
 * 只做 Responses API 等于把可用的服务商砍到两家，而这个功能要玩家自带 key——
 * 他手上有哪家的 key 不是我们能决定的。
 *
 * <p>渗透的问题靠 {@link AiClient} 挡住：协议差异全部收在两个实现里，
 * 上层拿到的只是「一段文本」。选哪个实现由这个枚举决定，只在一处分叉。
 *
 * <h2>两种协议的实际差异</h2>
 *
 * <table border="1">
 *   <caption>同一件事的两种说法</caption>
 *   <tr><th></th><th>RESPONSES</th><th>CHAT</th></tr>
 *   <tr><td>路径</td><td>{@code /v1/responses}</td><td>{@code /v1/chat/completions}</td></tr>
 *   <tr><td>系统提示</td><td>{@code instructions} 字段</td><td>{@code messages[0].role=system}</td></tr>
 *   <tr><td>对话</td><td>{@code input}</td><td>{@code messages}</td></tr>
 *   <tr><td>输出上限</td><td>{@code max_output_tokens}</td><td>{@code max_tokens}</td></tr>
 *   <tr><td>整段取文本</td><td>{@code output_text}</td><td>{@code choices[0].message.content}</td></tr>
 *   <tr><td>流式增量</td><td>{@code response.output_text.delta}</td><td>{@code choices[0].delta.content}</td></tr>
 *   <tr><td>流式收尾</td><td>{@code response.completed}</td><td>{@code data: [DONE]}</td></tr>
 * </table>
 */
public enum AiProtocol {

    /** OpenAI Responses API。OpenAI 与 DeepSeek 支持。 */
    RESPONSES("responses"),

    /** OpenAI Chat Completions。几乎所有兼容服务都支持，也是国内各家的通用形态。 */
    CHAT("chat/completions");

    private final String path;

    AiProtocol(String path) {
        this.path = path;
    }

    /** 拼在 base 后面的端点路径。 */
    public String path() {
        return path;
    }

    public String translationKey() {
        return "gtshaders.ai.protocol." + name().toLowerCase(Locale.ROOT);
    }

    public AiProtocol next() {
        return this == RESPONSES ? CHAT : RESPONSES;
    }

    /** 读配置用。认不出来一律退回 CHAT——它的兼容面最广，猜错的代价最小。 */
    public static AiProtocol parse(String raw) {
        if (raw == null) {
            return CHAT;
        }
        return "responses".equalsIgnoreCase(raw.trim()) ? RESPONSES : CHAT;
    }
}
