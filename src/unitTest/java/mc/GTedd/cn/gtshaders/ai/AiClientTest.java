package mc.GTedd.cn.gtshaders.ai;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 覆盖 AI 接入的传输层：URL 规范化、SSE 分帧、响应解析、错误归类、两份配置的存放。
 *
 * <p>这里钉的东西有个共同点：<b>错了之后的表现和真正的原因毫无关系</b>。
 * URL 少一段路径报的是 404（长得像「模型不存在」）；SSE 分帧错了表现成「生成到一半停住」；
 * 把 reasoning 当成正文拼进去，最后是驱动报一句指着中文推理的语法错误。
 * 全都是要靠猜的故障，所以必须在这里锁住。
 *
 * <p>最后那两个用例是<b>安全回归</b>：一旦有人往 {@link AiConfig} 里加了个 key 字段，
 * 或者把凭据文件挪回 config 目录，它们会立刻变红。那种改动本身不会报错，
 * 而后果是玩家的 key 随整合包发出去。
 */
class AiClientTest {

    // ------------------------------------------------------------ URL

    /**
     * 官方文档给的是 SDK 用法（base_url 只到域名），玩家会照抄。三种写法都得认，
     * 否则唯一的症状是 404——而 404 会让人去换模型、换 key，永远想不到是路径少一段。
     */
    @Test
    void resolvesEveryBaseUrlSpelling() {
        String want = "https://api.deepseek.com/v1/responses";
        assertEquals(want, AiEndpoint.resolveUrl("https://api.deepseek.com"));
        assertEquals(want, AiEndpoint.resolveUrl("https://api.deepseek.com/"));
        assertEquals(want, AiEndpoint.resolveUrl("https://api.deepseek.com/v1"));
        assertEquals(want, AiEndpoint.resolveUrl("https://api.deepseek.com/v1/"));
        assertEquals(want, AiEndpoint.resolveUrl("https://api.deepseek.com/v1/responses"));
        assertEquals(want, AiEndpoint.resolveUrl("  https://api.deepseek.com  "));
        // 少了协议头：URI.create 会把它解析成没有 host 的相对路径，报错更加不知所云
        assertEquals(want, AiEndpoint.resolveUrl("api.deepseek.com"));
    }

    /** 本地模型走 http 且带端口，不能被强行升成 https——那样一个字节都发不出去。 */
    @Test
    void keepsPlainHttpForLocalServers() {
        assertEquals("http://localhost:11434/v1/responses",
                AiEndpoint.resolveUrl("http://localhost:11434/v1"));
        assertEquals("http://127.0.0.1:8000/v1/responses",
                AiEndpoint.resolveUrl("http://127.0.0.1:8000"));
    }

    @Test
    void emptyBaseUrlStaysEmpty() {
        assertEquals("", AiEndpoint.resolveUrl(""));
        assertEquals("", AiEndpoint.resolveUrl(null));
    }

    @Test
    void hostIsExtractedForKeyLookup() {
        assertEquals("api.deepseek.com", AiCredentials.hostOf("https://api.deepseek.com/v1"));
        assertEquals("api.deepseek.com", AiCredentials.hostOf("HTTPS://API.DeepSeek.COM"));
        assertEquals("localhost", AiCredentials.hostOf("http://localhost:11434/v1"));
        assertEquals("", AiCredentials.hostOf(""));
    }

    // ------------------------------------------------------------ SSE

    private static SseParser.Frame feed(SseParser p, String... lines) {
        SseParser.Frame last = null;
        for (String l : lines) {
            SseParser.Frame f = p.feedLine(l);
            if (f != null) {
                last = f;
            }
        }
        return last;
    }

    @Test
    void parsesOneFrame() {
        SseParser p = new SseParser();
        SseParser.Frame f = feed(p,
                "event: response.output_text.delta",
                "data: {\"type\":\"response.output_text.delta\",\"delta\":\"vec3\"}",
                "");
        assertNotNull(f);
        assertEquals("vec3", SseParser.textDelta(f));
    }

    /**
     * 一帧里多行 data 要按 \n 拼起来。绝大多数时候 JSON 都在一行里，所以「一行一事件」
     * 的写法能跑很久——直到某个中转服务按自己的缓冲边界断行，那时拿到的是半截 JSON。
     */
    @Test
    void joinsMultilineData() {
        SseParser p = new SseParser();
        SseParser.Frame f = feed(p,
                "data: {\"type\":\"response.output_text.delta\",",
                "data:  \"delta\":\"a\\nb\"}",
                "");
        assertNotNull(f);
        assertEquals("a\nb", SseParser.textDelta(f));
    }

    /** HTTP 是 CRLF；按 \n 切行的实现会把 \r 留在行尾，不剥掉就整帧解析失败。 */
    @Test
    void toleratesTrailingCarriageReturn() {
        SseParser p = new SseParser();
        SseParser.Frame f = feed(p,
                "data: {\"type\":\"response.output_text.delta\",\"delta\":\"x\"}\r",
                "\r");
        assertNotNull(f);
        assertEquals("x", SseParser.textDelta(f));
    }

    /** 冒号开头是服务端的保活心跳，当成字段处理会凭空多出一帧。 */
    @Test
    void ignoresCommentHeartbeat() {
        SseParser p = new SseParser();
        assertNull(p.feedLine(": ping"));
        assertNull(p.feedLine(""));
    }

    @Test
    void recognisesDoneSentinel() {
        SseParser p = new SseParser();
        assertNull(p.feedLine("data: [DONE]"));
        assertTrue(p.isDone());
    }

    /** 流没有以空行收尾时，最后一帧要在 flush 里补交，否则最后几个字丢掉。 */
    @Test
    void flushEmitsUnterminatedFrame() {
        SseParser p = new SseParser();
        assertNull(p.feedLine("data: {\"type\":\"response.output_text.delta\",\"delta\":\"tail\"}"));
        SseParser.Frame f = p.flush();
        assertNotNull(f);
        assertEquals("tail", SseParser.textDelta(f));
    }

    /** 中转服务常常丢掉 event: 行，只转发 data:。以 data 里的 type 为准才收得到事件。 */
    @Test
    void typeComesFromDataThenEventLine() {
        assertEquals("response.completed", SseParser.typeOf(
                new SseParser.Frame("", "{\"type\":\"response.completed\"}")));
        assertEquals("response.completed", SseParser.typeOf(
                new SseParser.Frame("response.completed", "{}")));
    }

    /**
     * 思维链绝不能当正文。混进去的话，交给驱动的是一段中文推理加一段 GLSL，
     * 报错指着第一行说语法错误。
     */
    @Test
    void reasoningDeltaIsNotText() {
        SseParser.Frame f = new SseParser.Frame("",
                "{\"type\":\"response.reasoning_summary_text.delta\",\"delta\":\"先想想\"}");
        assertNull(SseParser.textDelta(f));
    }

    @Test
    void detectsFailureAndTruncation() {
        assertEquals("boom", SseParser.failure(new SseParser.Frame("",
                "{\"type\":\"response.failed\",\"error\":{\"message\":\"boom\"}}")));
        assertEquals("bad key", SseParser.failure(new SseParser.Frame("",
                "{\"type\":\"error\",\"message\":\"bad key\"}")));
        // 截断要能识别出来：半截着色器编译失败之后的报错完全指不到真正的原因
        String inc = SseParser.failure(new SseParser.Frame("",
                "{\"type\":\"response.incomplete\",\"response\":{\"incomplete_details\":"
                        + "{\"reason\":\"max_output_tokens\"}}}"));
        assertNotNull(inc);
        assertTrue(inc.startsWith("incomplete:"), inc);
        assertTrue(inc.contains("max_output_tokens"), inc);
        assertNull(SseParser.failure(new SseParser.Frame("", "{\"type\":\"response.created\"}")));
    }

    // ------------------------------------------------------------ 非流式响应

    @Test
    void prefersOutputTextShortcut() {
        assertEquals("hello", ResponsesClient.extractText("{\"output_text\":\"hello\"}"));
    }

    /**
     * OpenAI 官方的原始 HTTP 响应里<b>没有</b> output_text（那是各语言 SDK 拼的），
     * 所以必须能自己聚合，否则换个自建服务就一个字都取不到。
     */
    @Test
    void aggregatesOutputArrayAndSkipsReasoning() {
        String json = """
                {"output":[
                  {"type":"reasoning","content":[{"type":"reasoning_text","text":"思考"}]},
                  {"type":"message","content":[
                     {"type":"output_text","text":"void "},
                     {"type":"output_text","text":"main(){}"}]}
                ]}
                """;
        assertEquals("void main(){}", ResponsesClient.extractText(json));
    }

    @Test
    void extractTextSurvivesGarbage() {
        assertEquals("", ResponsesClient.extractText("not json at all"));
        assertEquals("", ResponsesClient.extractText("{}"));
        assertEquals("", ResponsesClient.extractText("[]"));
    }

    // ------------------------------------------------------------ Chat 协议响应

    @Test
    void chatTakesContentFromFirstChoice() {
        assertEquals("void main(){}", ChatClient.extractText(
                "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"void main(){}\"}}]}"));
    }

    /**
     * 思维链绝不能当正文。
     *
     * <p>DeepSeek-R1 那一类推理模型把推理过程放在 {@code reasoning_content}，
     * 和正文 {@code content} 并列。混进去的话交给编译器的是一段中文推理加一段 GLSL，
     * 而报错指着第一行说语法错误。
     */
    @Test
    void chatIgnoresReasoningContent() {
        String json = "{\"choices\":[{\"message\":{\"reasoning_content\":\"先想想\","
                + "\"content\":\"void main(){}\"}}]}";
        assertEquals("void main(){}", ChatClient.extractText(json));

        // 只有思维链、没有正文时不能把思维链顶上来
        assertEquals("", ChatClient.extractText(
                "{\"choices\":[{\"message\":{\"reasoning_content\":\"只有推理\"}}]}"));
    }

    @Test
    void chatStreamDeltaIsPickedUp() {
        assertEquals("vec3", ChatClient.deltaOf(
                "{\"choices\":[{\"delta\":{\"content\":\"vec3\"}}]}"));
        assertNull(ChatClient.deltaOf(
                "{\"choices\":[{\"delta\":{\"reasoning_content\":\"思考\"}}]}"));
        assertNull(ChatClient.deltaOf("{\"choices\":[{\"delta\":{}}]}"));
        assertNull(ChatClient.deltaOf("not json"));
    }

    @Test
    void chatStreamErrorIsDetected() {
        assertEquals("bad key", ChatClient.errorOf(
                "{\"error\":{\"message\":\"bad key\",\"type\":\"auth\"}}"));
        assertNull(ChatClient.errorOf("{\"choices\":[{\"delta\":{\"content\":\"x\"}}]}"));
    }

    @Test
    void chatSurvivesGarbage() {
        assertEquals("", ChatClient.extractText("not json"));
        assertEquals("", ChatClient.extractText("{}"));
        assertEquals("", ChatClient.extractText("{\"choices\":[]}"));
    }

    // ------------------------------------------------------------ 错误归类

    /**
     * 402 单列是因为 DeepSeek 用它表示余额不足。429 有两义，措辞里带 balance 的要归到
     * 余额而不是限流——对已经欠费的人说「等一会儿再试」是纯粹浪费他的时间。
     */
    @Test
    void classifiesStatusCodes() {
        assertEquals(AiException.Kind.AUTH, AiHttp.classify(401, "").kind());
        assertEquals(AiException.Kind.AUTH, AiHttp.classify(403, "").kind());
        assertEquals(AiException.Kind.QUOTA, AiHttp.classify(402, "").kind());
        assertEquals(AiException.Kind.NOT_FOUND, AiHttp.classify(404, "").kind());
        assertEquals(AiException.Kind.RATE_LIMIT, AiHttp.classify(429, "slow down").kind());
        assertEquals(AiException.Kind.QUOTA,
                AiHttp.classify(429, "Insufficient Balance").kind());
        assertEquals(AiException.Kind.SERVER, AiHttp.classify(503, "").kind());
    }

    /** 网关会回一整页 HTML，界面上放不下。 */
    @Test
    void truncatesHugeErrorBodies() {
        String detail = AiHttp.classify(500, "x".repeat(5000)).detail();
        assertTrue(detail.length() < 700, "detail length=" + detail.length());
    }

    @Test
    void errorKindMapsToLangKey() {
        assertEquals("gtshaders.ai.error.auth",
                new AiException(AiException.Kind.AUTH, "").langKey());
        assertEquals("gtshaders.ai.error.rate_limit",
                new AiException(AiException.Kind.RATE_LIMIT, "").langKey());
    }

    // ------------------------------------------------------------ 凭据

    @Test
    void credentialsRoundTrip(@TempDir Path dir) {
        Path file = dir.resolve("credentials.json");
        AiCredentials.write(file, Map.of("api.deepseek.com", "sk-secret-value-1234"));
        Map<String, String> back = AiCredentials.read(file);
        assertEquals("sk-secret-value-1234", back.get("api.deepseek.com"));
    }

    @Test
    void missingOrBrokenCredentialFileReadsAsEmpty(@TempDir Path dir) throws Exception {
        assertTrue(AiCredentials.read(dir.resolve("nope.json")).isEmpty());
        Path broken = dir.resolve("broken.json");
        Files.writeString(broken, "{ this is not json", StandardCharsets.UTF_8);
        assertTrue(AiCredentials.read(broken).isEmpty());
    }

    /** 掩码要留得住「这是哪家的 key」和「是不是我填的那把」，中间一概不给。 */
    @Test
    void masksKeyForDisplay() {
        String masked = AiCredentials.mask("sk-abcdefghijklmnop");
        assertTrue(masked.startsWith("sk-"), masked);
        assertTrue(masked.endsWith("mnop"), masked);
        assertFalse(masked.contains("defghij"), masked);
        // 短到掩不住的整串打星：万一它真是把 key，露出来的就是全部
        assertEquals("****", AiCredentials.mask("abcd"));
        assertEquals("", AiCredentials.mask(""));
    }

    // ------------------------------------------------------------ 安全回归

    /**
     * <b>安全回归。</b>{@code config/} 会被整合包整个打包带走，所以那份配置里
     * 一个字节的 key 都不能有。
     *
     * <p>这一条钉的是<b>类型</b>而不是序列化行为：只要 record 里没有能装 key 的组件，
     * 就没有人能不小心把它写出去。钉序列化的话，防线是「记得跳过那个字段」，
     * 而那种约定第一次重构就会被忘掉——忘掉之后一切照常工作，没有任何报错。
     */
    @Test
    void configTypeHasNowhereToPutAKey() {
        for (var component : AiConfig.class.getRecordComponents()) {
            String name = component.getName().toLowerCase(java.util.Locale.ROOT);
            for (String forbidden : List.of("key", "secret", "credential", "auth", "password")) {
                assertFalse(name.contains(forbidden),
                        "AiConfig 多了一个能装凭据的组件 " + component.getName()
                                + "：key 必须走 AiCredentials，落在 ~/.gtshaders/ 而不是 config/");
            }
        }
    }

    /**
     * 落盘的那份文件里也不能有。
     *
     * <p>逐个键值地查而不是在整份文本里搜关键词：文件头上那句 {@code _comment} 本身就要
     * 提到 credentials.json 在哪，整体搜关键词会被自己的说明命中。
     * 那种假警报最后一定是靠加例外绕过去的，而例外会顺手放过真正的问题。
     */
    @Test
    void savedConfigFileCarriesNoCredentials(@TempDir Path dir) throws Exception {
        AiConfig.defaults().save(dir);
        String json = Files.readString(dir.resolve("ai.json"), StandardCharsets.UTF_8);
        var root = com.google.gson.JsonParser.parseString(json).getAsJsonObject();
        for (String key : root.keySet()) {
            if (key.equals("_comment")) {
                continue;
            }
            String lowerKey = key.toLowerCase(java.util.Locale.ROOT);
            // 刻意不把 "token" 列进来：maxOutputTokens 是个正当字段，
            // 拿它当禁词只会让这条断言变成噪音
            for (String forbidden : List.of("key", "secret", "credential", "bearer", "auth")) {
                assertFalse(lowerKey.contains(forbidden),
                        "ai.json 里出现了不该有的字段 " + key + "：\n" + json);
            }
            var value = root.get(key);
            if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) {
                assertFalse(value.getAsString().startsWith("sk-"),
                        "ai.json 的 " + key + " 看起来是一把 key：\n" + json);
            }
        }
    }

    /** 凭据必须落在用户主目录，不能在 .minecraft 里——那是整合包打包器的领地。 */
    @Test
    void credentialsLiveOutsideTheGameDirectory() {
        Path file = AiCredentials.defaultFile();
        Path home = Path.of(System.getProperty("user.home", "."));
        assertTrue(file.startsWith(home), "凭据应当在用户主目录下，实际是 " + file);
        assertEquals("credentials.json", file.getFileName().toString());
        assertEquals(".gtshaders", file.getParent().getFileName().toString());
    }
}
