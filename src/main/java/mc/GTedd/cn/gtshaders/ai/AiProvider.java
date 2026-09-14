package mc.GTedd.cn.gtshaders.ai;

import mc.GTedd.cn.gtshaders.i18n.GtLang;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * 一个服务商配置：叫什么、地址在哪、说哪种协议、用哪个模型。
 *
 * <h2>为什么要有 id，而不是拿 baseUrl 当键</h2>
 *
 * <p>玩家会为同一个地址配出好几条来——一条用便宜的快模型、一条用贵的强模型，
 * 或者两条指向同一个中转但走不同的账号。拿 baseUrl 当键的话这些会互相覆盖。
 * id 一旦生成就不再变，玩家怎么改标签、改地址、换模型都不会丢掉「这是哪一条」。
 *
 * <h2>为什么这里也没有 apiKey 字段</h2>
 *
 * <p>和 {@link AiConfig} 同一个理由，而且更要紧：这个 record 是要被序列化进
 * {@code config/gtshaders/ai.json} 的列表元素，而 {@code config/} 会被整合包整个打包带走。
 * key 走 {@link AiCredentials}，落在 {@code ~/.gtshaders/}。
 *
 * @param id       稳定标识，不展示给玩家
 * @param label    界面上显示的名字
 * @param baseUrl  服务地址，允许写成官方文档那种只到域名的形式
 * @param model    当前选中的模型名
 * @param protocol 这家说哪种协议
 */
public record AiProvider(String id, String label, String baseUrl, String model, AiProtocol protocol) {

    /**
     * 内置预设。
     *
     * <p><b>模型名一律留空</b>（DeepSeek 除外，它是默认那条，要开箱可用）。
     * 预填一个模型名的风险比留空大得多：各家的模型名换代很快，
     * 写死一个过期的名字，玩家点生成得到 404，而 404 长得就像地址填错了。
     * 留空的话界面会提示他去点「获取」——那一步同时还验证了 key 和网络。
     *
     * <p>协议按各家实际提供的填。填错的后果同样是 404：
     * 拿 Chat Completions 的服务去请求 {@code /v1/responses}，服务端只会说找不到。
     */
    public static List<AiProvider> presets() {
        return List.of(
                preset("deepseek", "DeepSeek", "https://api.deepseek.com",
                        "deepseek-v4-pro", AiProtocol.RESPONSES),
                preset("openai", "OpenAI", "https://api.openai.com",
                        "", AiProtocol.RESPONSES),
                preset("zhipu", GtLang.get("gtshaders.ai.provider.zhipu"), "https://open.bigmodel.cn/api/paas/v4",
                        "", AiProtocol.CHAT),
                preset("moonshot", GtLang.get("gtshaders.ai.provider.moonshot"), "https://api.moonshot.cn/v1",
                        "", AiProtocol.CHAT),
                preset("bailian", GtLang.get("gtshaders.ai.provider.bailian"), "https://dashscope.aliyuncs.com/compatible-mode/v1",
                        "", AiProtocol.CHAT),
                preset("siliconflow", GtLang.get("gtshaders.ai.provider.siliconflow"), "https://api.siliconflow.cn/v1",
                        "", AiProtocol.CHAT),
                preset("openrouter", "OpenRouter", "https://openrouter.ai/api/v1",
                        "", AiProtocol.CHAT),
                preset("ollama", GtLang.get("gtshaders.ai.provider.ollama"), "http://localhost:11434/v1",
                        "", AiProtocol.CHAT),
                preset("lmstudio", GtLang.get("gtshaders.ai.provider.lmstudio"), "http://localhost:1234/v1",
                        "", AiProtocol.CHAT));
    }

    private static AiProvider preset(String id, String label, String baseUrl,
                                     String model, AiProtocol protocol) {
        return new AiProvider(id, label, baseUrl, model, protocol);
    }

    /** 新建一条空白配置，交给玩家自己填。 */
    public static AiProvider blank() {
        return new AiProvider(newId(), "", "", "", AiProtocol.CHAT);
    }

    public static String newId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    /** 界面上显示的名字：没起标签就拿域名顶上，总比显示一片空白强。 */
    public String displayName() {
        if (!label.isBlank()) {
            return label;
        }
        String host = AiCredentials.hostOf(baseUrl);
        return host.isBlank() ? "?" : host;
    }

    public boolean isComplete() {
        return !baseUrl.isBlank() && !model.isBlank();
    }

    public AiProvider withLabel(String v) {
        return new AiProvider(id, v == null ? "" : v, baseUrl, model, protocol);
    }

    public AiProvider withBaseUrl(String v) {
        return new AiProvider(id, label, v == null ? "" : v.trim(), model, protocol);
    }

    public AiProvider withModel(String v) {
        return new AiProvider(id, label, baseUrl, v == null ? "" : v.trim(), protocol);
    }

    public AiProvider withProtocol(AiProtocol v) {
        return new AiProvider(id, label, baseUrl, model, v);
    }

    /** 换个新 id，其余照旧。从预设导入时用——预设的 id 是固定的，直接加会覆盖同名那条。 */
    public AiProvider withNewId() {
        return new AiProvider(newId(), label, baseUrl, model, protocol);
    }

    /** 复制一份并换个新 id——「以这条为模板再加一条」用。 */
    public AiProvider copyAsNew() {
        String suffix = label.isBlank() ? "" : " copy";
        return new AiProvider(newId(), (label + suffix).trim(), baseUrl, model, protocol);
    }

    /** 同一个 host 上的配置共用一把 key，见 {@link AiCredentials}。 */
    public String credentialHost() {
        return AiCredentials.hostOf(baseUrl).toLowerCase(Locale.ROOT);
    }
}
