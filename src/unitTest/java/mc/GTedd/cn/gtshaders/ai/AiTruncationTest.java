package mc.GTedd.cn.gtshaders.ai;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 覆盖「输出被截断」的识别，以及模型预选。
 *
 * <h2>为什么这两件事放在一起</h2>
 *
 * <p>它们是同一次线上故障的两半。玩家从清单里选中了
 * {@code deepseek-v4-flash-vision-exp}——一个视觉实验模型——去写 GLSL，
 * 结果推理把输出额度吃光，界面上只留下一句「返回的内容用不了」。
 *
 * <p>两半都得修：截断要能<b>被识别并自救</b>（调大上限重试），
 * 而预选要<b>别把玩家往那个模型上推</b>（字母序第一个恰好就是它）。
 * 少修任何一半，同样的故障还会以另一种面貌回来。
 */
class AiTruncationTest {

    // ------------------------------------------------------------ Responses 协议

    /**
     * Responses 把整个响应标成 {@code status: "incomplete"}。
     *
     * <p>不看这个字段的话，被截断的半截代码会被当成正常产出拿去编译——
     * 报出来的是一堆花括号没闭合的语法错误，而真正的原因是没写完。
     */
    @Test
    void responsesIncompleteIsDetected() {
        String json = """
                {"status":"incomplete","incomplete_details":{"reason":"max_output_tokens"},
                 "output_text":"void main() { vec3 col ="}
                """;
        assertEquals("max_output_tokens", ResponsesClient.incompleteReason(json));
    }

    @Test
    void responsesCompletedIsNotTruncated() {
        assertNull(ResponsesClient.incompleteReason(
                "{\"status\":\"completed\",\"output_text\":\"void main(){}\"}"));
        assertNull(ResponsesClient.incompleteReason("{}"));
        assertNull(ResponsesClient.incompleteReason("not json"));
    }

    /** 流式那条路径的截断标记在 SSE 帧里，归类必须是可恢复的 TRUNCATED。 */
    @Test
    void responsesStreamIncompleteFrameIsRecognised() {
        String fail = SseParser.failure(new SseParser.Frame("",
                "{\"type\":\"response.incomplete\",\"response\":{\"incomplete_details\":"
                        + "{\"reason\":\"max_output_tokens\"}}}"));
        assertNotNull(fail);
        assertTrue(fail.startsWith("incomplete:"), fail);
    }

    // ------------------------------------------------------------ Chat 协议

    /**
     * Chat 更阴险：照常返回 200 和一段<b>看起来正常</b>的内容，
     * 只在 {@code finish_reason} 上留个 {@code "length"} 的记号。
     */
    @Test
    void chatFinishReasonLengthIsTruncation() {
        assertTrue(ChatClient.isTruncated(
                "{\"choices\":[{\"finish_reason\":\"length\",\"message\":{\"content\":\"void main(\"}}]}"));
        assertFalse(ChatClient.isTruncated(
                "{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":\"void main(){}\"}}]}"));
        assertFalse(ChatClient.isTruncated("{\"choices\":[{\"message\":{\"content\":\"x\"}}]}"));
        assertFalse(ChatClient.isTruncated("not json"));
    }

    // ------------------------------------------------------------ 错误归类

    /** 截断必须归成自己那一类：归进 BAD_RESPONSE 的话上层就没机会自救。 */
    @Test
    void truncationHasItsOwnRecoverableKind() {
        AiException e = new AiException(AiException.Kind.TRUNCATED, "incomplete: max_output_tokens");
        assertEquals(AiException.Kind.TRUNCATED, e.kind());
        assertEquals("gtshaders.ai.error.truncated", e.langKey());
        assertNotEquals(AiException.Kind.BAD_RESPONSE, e.kind());
    }

    private static void assertNotEquals(Object unexpected, Object actual) {
        org.junit.jupiter.api.Assertions.assertNotEquals(unexpected, actual);
    }

    /**
     * 截断自救需要的两样能力：能认出「当前是自动档」，以及能换成一个显式上限。
     *
     * <p>自动档下服务端用的是模型自己的上限，被它截断时翻倍无从下手——
     * 我们不知道那个数是多少，只能换成一个明确的大值再试。
     *
     * <p>上限的取值细节（封顶、下限、字段名）在 {@code AiOutputLimitTest} 里，
     * 这里只钉自救这条路径要用到的部分。
     */
    @Test
    void truncationRecoveryCanSwitchFromAutoToExplicit() {
        AiProvider p = new AiProvider("t", "t", "https://h", "m", AiProtocol.CHAT);
        AiEndpoint ep = AiEndpoint.of(p, AiConfig.defaults(), "sk-x");
        assertTrue(ep.autoMaxOutput(), "默认是自动档：替模型猜上限正是这次故障的起因");

        AiEndpoint bumped = ep.withMaxOutputTokens(32768);
        assertFalse(bumped.autoMaxOutput());
        assertEquals(32768, bumped.maxOutputTokens());
        assertEquals(65536, bumped.withMaxOutputTokens(65536).maxOutputTokens());
    }

    // ------------------------------------------------------------ 模型预选

    /**
     * <b>这条就是那次故障的直接回归。</b>
     *
     * <p>DeepSeek 的真实清单按字母序排下来，{@code deepseek-v4-flash-vision-exp}
     * 正好排在 {@code deepseek-v4-pro} 前面。取第一个就等于把玩家推到视觉实验模型上。
     */
    @Test
    void visionModelIsNotPreselected() {
        List<String> real = List.of(
                "deepseek-v4-flash",
                "deepseek-v4-flash-vision-exp",
                "deepseek-v4-pro");
        assertEquals("deepseek-v4-flash-vision-exp", real.get(1),
                "样本要保持和真实清单一致，否则这条测试就没意义了");

        String picked = ModelCatalog.preferredFor(real);
        assertFalse(picked.contains("vision"), "预选到了视觉模型: " + picked);
        assertEquals("deepseek-v4-pro", picked, "有 pro 就该优先挑它");
    }

    @Test
    void offTopicModelsAreSkippedWhenPreselecting() {
        for (String bad : List.of("text-embedding-3-large", "whisper-1", "dall-e-3",
                "bge-reranker-v2", "qwen-vl-max", "tts-1", "omni-moderation-latest")) {
            String picked = ModelCatalog.preferredFor(List.of(bad, "some-chat-model"));
            assertEquals("some-chat-model", picked, "没跳过 " + bad);
        }
    }

    @Test
    void coderModelWinsWhenPresent() {
        assertEquals("qwen3-coder-plus",
                ModelCatalog.preferredFor(List.of("qwen3-32b", "qwen3-coder-plus", "qwen3-max")));
    }

    /** 全都是「不像干这个的」时也得给一个——预选只是预选，玩家随时能改。 */
    @Test
    void preselectNeverReturnsEmptyForNonEmptyList() {
        assertEquals("whisper-1", ModelCatalog.preferredFor(List.of("whisper-1")));
        assertEquals("", ModelCatalog.preferredFor(List.of()));
    }

    // ------------------------------------------------------------ 诊断日志

    /**
     * <b>安全回归：日志里绝不能出现 key。</b>
     *
     * <p>日志文件会被玩家整份发到群里、贴进 issue，落进去就再也收不回来。
     */
    @Test
    void diagnosticsNeverContainTheKey() {
        AiLog.clear();
        String secret = "sk-super-secret-key-value-1234";
        AiLog.request("api.deepseek.com", AiProtocol.RESPONSES, "deepseek-v4-pro",
                "https://api.deepseek.com/v1/responses", true, 8192);
        AiLog.usage(1523, 8192, 7900);
        AiLog.truncated(8192, 16384);
        AiLog.failed(new AiException(AiException.Kind.TRUNCATED, "incomplete: max_output_tokens"));

        String report = AiLog.recent();
        assertFalse(report.contains(secret), "日志里出现了 key");
        assertFalse(report.contains("sk-"), "日志里出现了 key 前缀:\n" + report);
        // 排查真正需要的那几个数都得在
        assertTrue(report.contains("deepseek-v4-pro"), report);
        assertTrue(report.contains("reasoning=7900"), "没记推理 token，就看不出额度是被谁吃掉的");
        assertTrue(report.contains("max_output_tokens=8192"), report);
        assertTrue(report.contains("truncated"), report);
    }

    @Test
    void diagnosticsSurviveWithNoActivity() {
        AiLog.clear();
        assertFalse(AiLog.recent().isBlank(), "没有活动时也要给一句话，不能是空白");
    }
}
