package mc.GTedd.cn.gtshaders.ai;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 一次调用需要的全部东西：URL、模型、参数、以及那把 key。
 *
 * <p><b>这个对象从不落盘。</b>{@link AiConfig} 负责持久化除 key 以外的部分，
 * key 由 {@link AiCredentials} 单独保管，两者只在发请求的那一刻在这里合流。
 * 类型上就没有「把它整个序列化出去」这条路，也就不会有人手滑写出那行代码。
 *
 * @param baseUrl         规范化之前的原始地址，拉模型列表时还要再拼一次别的路径
 * @param model           模型名
 * @param apiKey          明文 key，只在内存里活着
 * @param temperature     采样温度
 * @param maxOutputTokens 输出上限
 * @param timeoutSeconds  单次请求超时
 */
public record AiEndpoint(String baseUrl, String model, String apiKey, AiProtocol protocol,
                         float temperature, int maxOutputTokens, int timeoutSeconds) {

    /** 末尾已经是 {@code /v1}、{@code /v2} 这类版本段。 */
    private static final Pattern VERSION_TAIL = Pattern.compile(".*/v\\d+$");

    /** 玩家可能连端点路径一起粘进来，识别出来剥掉，回到干净的 base。 */
    private static final String[] KNOWN_TAILS = {"/responses", "/chat/completions", "/models"};

    public static AiEndpoint of(AiConfig config, String apiKey) {
        return of(config.active(), config, apiKey);
    }

    /**
     * 用指定的 provider 组一个 endpoint。
     *
     * <p>「测试连接」和「拉模型列表」要能作用在玩家<b>正在编辑、还没保存</b>的那一条上，
     * 否则改完地址得先保存再测，而保存一个还没验证过的地址正是我们想避免的顺序。
     */
    public static AiEndpoint of(AiProvider provider, AiConfig config, String apiKey) {
        return new AiEndpoint(provider.baseUrl(), provider.model(), apiKey, provider.protocol(),
                config.temperature(), config.maxOutputTokens(), config.timeoutSeconds());
    }

    public boolean hasKey() {
        return apiKey != null && !apiKey.isBlank();
    }

    /** 生成着色器用的端点，路径由协议决定。 */
    public String requestUrl() {
        return resolve(baseUrl, protocol.path());
    }

    /** 列模型用的端点。 */
    public String modelsUrl() {
        return resolveModelsUrl(baseUrl);
    }

    /** 输出被截断时把上限调大重试用。 */
    public AiEndpoint withMaxOutputTokens(int v) {
        return new AiEndpoint(baseUrl, model, apiKey, protocol, temperature,
                AiConfig.clampOutput(v), timeoutSeconds);
    }

    /** 是否交给服务端决定输出上限。为真时请求体里<b>不带</b>那个字段。 */
    public boolean autoMaxOutput() {
        return maxOutputTokens <= AiConfig.MAX_OUTPUT_AUTO;
    }

    public AiEndpoint withModel(String v) {
        return new AiEndpoint(baseUrl, v, apiKey, protocol, temperature, maxOutputTokens, timeoutSeconds);
    }

    // ------------------------------------------------------------ URL 归一化

    /** 仅供测试与文档引用：Responses 协议下的完整端点。 */
    public static String resolveUrl(String baseUrl) {
        return resolve(baseUrl, AiProtocol.RESPONSES.path());
    }

    /** 仅供测试与文档引用：Chat Completions 协议下的完整端点。 */
    public static String resolveChatUrl(String baseUrl) {
        return resolve(baseUrl, AiProtocol.CHAT.path());
    }

    public static String resolveModelsUrl(String baseUrl) {
        return resolve(baseUrl, "models");
    }

    /**
     * 把玩家填的各种写法都归一成完整端点。
     *
     * <h2>为什么值得单独写一个函数</h2>
     *
     * <p>官方文档给的是 SDK 用法（{@code base_url="https://api.deepseek.com"}），SDK 会自己
     * 拼上 {@code /v1/responses}。玩家照着文档把那串填进来，我们要是直接拿去发请求，
     * 得到的是 404——而 404 长得就像「模型不存在」或者「服务挂了」，玩家会去换模型、
     * 换 key、重启游戏，唯独想不到是少了一段路径。
     *
     * <p>反过来，看过一点资料的玩家会填 {@code .../v1} 甚至完整的 {@code .../v1/responses}。
     * 全都得认，否则就变成「必须精确填对某一种」，那是把解析成本转嫁给玩家。
     *
     * <p>已经带了端点路径的还要能<b>换成另一个端点</b>：玩家填了 {@code .../v1/responses}，
     * 拉模型列表要的是同一个 base 下的 {@code .../v1/models}，
     * 不剥掉尾巴就会拼出 {@code .../v1/responses/models}。
     */
    private static String resolve(String baseUrl, String endpoint) {
        String s = baseUrl == null ? "" : baseUrl.trim();
        if (s.isEmpty()) {
            return "";
        }
        String lower = s.toLowerCase(Locale.ROOT);
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
            s = "https://" + s;
        }
        s = stripTrailingSlashes(s);

        // 先剥掉已知的端点路径，回到干净的 base
        boolean stripped = true;
        while (stripped) {
            stripped = false;
            String tail = s.toLowerCase(Locale.ROOT);
            for (String known : KNOWN_TAILS) {
                if (tail.endsWith(known)) {
                    s = stripTrailingSlashes(s.substring(0, s.length() - known.length()));
                    stripped = true;
                    break;
                }
            }
        }
        if (s.isEmpty()) {
            return "";
        }
        if (!VERSION_TAIL.matcher(s.toLowerCase(Locale.ROOT)).matches()) {
            s = s + "/v1";
        }
        return s + "/" + endpoint;
    }

    private static String stripTrailingSlashes(String s) {
        String out = s;
        while (out.endsWith("/")) {
            out = out.substring(0, out.length() - 1);
        }
        return out;
    }
}
