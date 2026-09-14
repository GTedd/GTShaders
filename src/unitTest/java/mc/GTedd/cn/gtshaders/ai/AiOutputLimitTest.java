package mc.GTedd.cn.gtshaders.ai;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 覆盖输出上限的「自动档」。
 *
 * <h2>为什么默认必须是自动</h2>
 *
 * <p>自己填一个数意味着要替所有模型猜一个上限，而这一代模型的输出能力差了两个数量级：
 * DeepSeek V4 Pro 能出 384K token，填 8192 是把它按在百分之二的额度上。
 * 更糟的是 reasoning token 也算在这个上限里——猜小了，模型先想两千字，
 * 正文还没开始写就被截断，而报出来只是一句「输出被截断」。
 *
 * <p>所以自动档的实现是<b>请求体里根本不带那个字段</b>，让服务端用模型自己的最大值。
 * 「不带」和「带一个很大的数」不是一回事：后者仍然是我们在猜，
 * 而且某些服务商会因为超过它的允许范围直接报 400。
 */
class AiOutputLimitTest {

    private static AiEndpoint endpoint(int limit) {
        AiProvider p = new AiProvider("t", "t", "https://api.example.com", "m", AiProtocol.CHAT);
        return AiEndpoint.of(p, AiConfig.defaults().withMaxOutputTokens(limit), "sk-x");
    }

    private static JsonObject body(String json) {
        return JsonParser.parseString(json).getAsJsonObject();
    }

    // ------------------------------------------------------------ 语义

    @Test
    void autoIsTheDefault() {
        AiConfig c = AiConfig.defaults();
        assertEquals(AiConfig.MAX_OUTPUT_AUTO, c.maxOutputTokens(),
                "默认必须是自动——替模型猜上限是这次线上故障的直接原因");
        assertTrue(AiEndpoint.of(c.active(), c, "sk-x").autoMaxOutput());
    }

    @Test
    void explicitLimitsAreKeptAndCappedAtOneMillion() {
        assertEquals(16384, AiConfig.clampOutput(16384));
        assertEquals(1_000_000, AiConfig.clampOutput(5_000_000), "上限按 1M 封顶");
        assertEquals(256, AiConfig.clampOutput(1), "太小的值抬到 256");
        assertEquals(AiConfig.MAX_OUTPUT_AUTO, AiConfig.clampOutput(0));
        assertEquals(AiConfig.MAX_OUTPUT_AUTO, AiConfig.clampOutput(-5),
                "负数也当自动，不该变成一个诡异的小上限");
    }

    @Test
    void autoFlagFollowsTheValue() {
        assertTrue(endpoint(AiConfig.MAX_OUTPUT_AUTO).autoMaxOutput());
        assertFalse(endpoint(16384).autoMaxOutput());
    }

    // ------------------------------------------------------------ 请求体

    /** <b>核心断言：自动档下请求体里不能出现上限字段。</b> */
    @Test
    void autoOmitsTheLimitFieldEntirely() {
        List<AiClient.Turn> input = List.of(AiClient.Turn.user("画个夜视仪"));

        JsonObject chat = body(ChatClient.buildBody(endpoint(AiConfig.MAX_OUTPUT_AUTO),
                "sys", input, true));
        assertFalse(chat.has("max_tokens"),
                "Chat 的自动档还是带上了 max_tokens: " + chat);

        AiProvider rp = new AiProvider("t", "t", "https://api.example.com", "m",
                AiProtocol.RESPONSES);
        AiConfig auto = AiConfig.defaults().withMaxOutputTokens(AiConfig.MAX_OUTPUT_AUTO);
        JsonObject responses = body(ResponsesClient.buildBody(
                AiEndpoint.of(rp, auto, "sk-x"), "sys", input, true));
        assertFalse(responses.has("max_output_tokens"),
                "Responses 的自动档还是带上了 max_output_tokens: " + responses);
    }

    @Test
    void explicitLimitIsSentUnderTheRightFieldName() {
        List<AiClient.Turn> input = List.of(AiClient.Turn.user("x"));

        JsonObject chat = body(ChatClient.buildBody(endpoint(32768), "sys", input, false));
        assertEquals(32768, chat.get("max_tokens").getAsInt());
        assertFalse(chat.has("max_output_tokens"), "Chat 用的是 max_tokens，不是那个名字");

        AiProvider rp = new AiProvider("t", "t", "https://api.example.com", "m",
                AiProtocol.RESPONSES);
        JsonObject responses = body(ResponsesClient.buildBody(
                AiEndpoint.of(rp, AiConfig.defaults().withMaxOutputTokens(32768), "sk-x"),
                "sys", input, false));
        assertEquals(32768, responses.get("max_output_tokens").getAsInt());
        assertFalse(responses.has("max_tokens"), "Responses 用的是 max_output_tokens");
    }

    /** 两种协议的其余字段名也不能串——串了服务端只会说参数不对。 */
    @Test
    void systemPromptGoesToTheRightPlacePerProtocol() {
        List<AiClient.Turn> input = List.of(AiClient.Turn.user("hello"));

        JsonObject chat = body(ChatClient.buildBody(endpoint(0), "SYSTEM", input, false));
        assertFalse(chat.has("instructions"), "Chat 没有 instructions 字段");
        JsonObject first = chat.getAsJsonArray("messages").get(0).getAsJsonObject();
        assertEquals("system", first.get("role").getAsString());
        assertEquals("SYSTEM", first.get("content").getAsString());

        AiProvider rp = new AiProvider("t", "t", "https://api.example.com", "m",
                AiProtocol.RESPONSES);
        JsonObject responses = body(ResponsesClient.buildBody(
                AiEndpoint.of(rp, AiConfig.defaults(), "sk-x"), "SYSTEM", input, false));
        assertEquals("SYSTEM", responses.get("instructions").getAsString());
        assertFalse(responses.has("messages"), "Responses 用的是 input，不是 messages");
        assertEquals(1, responses.getAsJsonArray("input").size());
    }

    // ------------------------------------------------------------ 存取

    @Test
    void autoSurvivesRoundTrip(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) {
        AiConfig.defaults().save(dir);
        assertEquals(AiConfig.MAX_OUTPUT_AUTO, AiConfig.load(dir).maxOutputTokens());

        AiConfig explicit = AiConfig.defaults().withMaxOutputTokens(65536);
        explicit.save(dir);
        assertEquals(65536, AiConfig.load(dir).maxOutputTokens());
    }

    /** 日志里 0 要写成 auto——照原样打成 0 会让看日志的人以为上限真的是零。 */
    @Test
    void logShowsAutoRatherThanZero() {
        AiLog.clear();
        AiLog.request("api.example.com", AiProtocol.CHAT, "m",
                "https://api.example.com/v1/chat/completions", true, AiConfig.MAX_OUTPUT_AUTO);
        String report = AiLog.recent();
        assertTrue(report.contains("max_output_tokens=auto"), report);
        assertFalse(report.contains("max_output_tokens=0"), report);
    }
}
