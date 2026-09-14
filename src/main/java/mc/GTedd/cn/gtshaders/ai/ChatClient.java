package mc.GTedd.cn.gtshaders.ai;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * OpenAI Chat Completions 的客户端。
 *
 * <p>兼容面最广的那一种：智谱、Kimi、阿里百炼、硅基流动、OpenRouter，以及本机跑的
 * Ollama / LM Studio 都说这个。只做 Responses API 的话，能用的服务商只剩两家。
 *
 * <h2>三处必须小心的地方</h2>
 *
 * <ul>
 *   <li><b>系统提示走 messages[0]</b>，不是单独的字段。位置错了模型会把它当成用户的话。</li>
 *   <li><b>思维链在 {@code reasoning_content} 里</b>。DeepSeek-R1 那一类推理模型会把
 *       推理过程放在这个字段，和正文 {@code content} 并列。当成正文拼进去的话，
 *       最后交给编译器的是一段中文推理加一段 GLSL，而报错指着第一行说语法错误。</li>
 *   <li><b>结束标志是 {@code data: [DONE]}</b>，不是某个事件类型。有些服务会在
 *       最后一帧带 {@code finish_reason} 之后才发它，也有的干脆不发就断流——两种都得收得住。</li>
 * </ul>
 */
public final class ChatClient implements AiClient {

    @Override
    public String send(AiEndpoint ep, String instructions, List<Turn> input, boolean stream,
                       @Nullable Consumer<String> onDelta, @Nullable BooleanSupplier cancelled) {
        AiHttp.requireUsable(ep);
        AiLog.request(AiCredentials.hostOf(ep.baseUrl()), ep.protocol(), ep.model(),
                ep.requestUrl(), stream, ep.maxOutputTokens());
        String body = buildBody(ep, instructions, input, stream);
        try {
            return stream
                    ? readStream(ep, body, onDelta, cancelled)
                    : readWhole(ep, body);
        } catch (Exception e) {
            throw AiHttp.wrap(e);
        }
    }

    /** 包级可见是为了让测试能直接验请求体——「自动档不发上限字段」只有在这一层看得见。 */
    static String buildBody(AiEndpoint ep, String instructions, List<Turn> input, boolean stream) {
        JsonArray messages = new JsonArray();
        if (instructions != null && !instructions.isBlank()) {
            JsonObject sys = new JsonObject();
            sys.addProperty("role", "system");
            sys.addProperty("content", instructions);
            messages.add(sys);
        }
        for (Turn t : input) {
            JsonObject m = new JsonObject();
            m.addProperty("role", t.role());
            m.addProperty("content", t.content());
            messages.add(m);
        }
        JsonObject body = new JsonObject();
        body.addProperty("model", ep.model());
        body.add("messages", messages);
        body.addProperty("stream", stream);
        body.addProperty("temperature", ep.temperature());
        // 自动档不发这个字段，服务端会用模型自己的最大值
        if (!ep.autoMaxOutput()) {
            body.addProperty("max_tokens", ep.maxOutputTokens());
        }
        return body.toString();
    }

    // ------------------------------------------------------------ 非流式

    private static String readWhole(AiEndpoint ep, String body) throws Exception {
        String raw = AiHttp.readWhole(
                AiHttp.post(ep.requestUrl(), ep.apiKey(), ep.timeoutSeconds(), body, false));
        logUsage(raw);
        if (isTruncated(raw)) {
            throw new AiException(AiException.Kind.TRUNCATED, "incomplete: max_tokens");
        }
        String text = extractText(raw);
        if (text.isBlank()) {
            throw new AiException(AiException.Kind.BAD_RESPONSE, AiHttp.truncate(raw));
        }
        return text;
    }

    /**
     * 从整份响应里取出正文：{@code choices[0].message.content}。
     *
     * <p>刻意<b>不碰</b> {@code reasoning_content}——那是思维链，不是要写进着色器的东西。
     */
    static String extractText(String json) {
        JsonObject choice = firstChoice(json);
        if (choice == null) {
            return "";
        }
        if (!choice.has("message") || !choice.get("message").isJsonObject()) {
            return "";
        }
        JsonObject message = choice.getAsJsonObject("message");
        if (message.has("content") && message.get("content").isJsonPrimitive()) {
            return message.get("content").getAsString();
        }
        // 少数服务把 content 给成分段数组，形状照抄 Responses 那一套
        if (message.has("content") && message.get("content").isJsonArray()) {
            StringBuilder sb = new StringBuilder();
            for (JsonElement part : message.getAsJsonArray("content")) {
                if (part.isJsonObject()) {
                    JsonObject po = part.getAsJsonObject();
                    if (po.has("text") && po.get("text").isJsonPrimitive()) {
                        sb.append(po.get("text").getAsString());
                    }
                } else if (part.isJsonPrimitive()) {
                    sb.append(part.getAsString());
                }
            }
            return sb.toString();
        }
        return "";
    }

    private static @Nullable JsonObject firstChoice(String json) {
        try {
            JsonElement e = JsonParser.parseString(json);
            if (!e.isJsonObject()) {
                return null;
            }
            JsonObject root = e.getAsJsonObject();
            if (!root.has("choices") || !root.get("choices").isJsonArray()) {
                return null;
            }
            JsonArray choices = root.getAsJsonArray("choices");
            if (choices.isEmpty() || !choices.get(0).isJsonObject()) {
                return null;
            }
            return choices.get(0).getAsJsonObject();
        } catch (Exception e) {
            return null;
        }
    }

    // ------------------------------------------------------------ 流式

    private static String readStream(AiEndpoint ep, String body,
                                     @Nullable Consumer<String> onDelta,
                                     @Nullable BooleanSupplier cancelled) throws Exception {
        StringBuilder text = new StringBuilder();
        String[] failure = {null};
        boolean[] truncated = {false};

        AiHttp.readStream(
                AiHttp.post(ep.requestUrl(), ep.apiKey(), ep.timeoutSeconds(), body, true),
                cancelled,
                frame -> {
                    if (failure[0] != null) {
                        return;
                    }
                    String fail = errorOf(frame.data());
                    if (fail != null) {
                        failure[0] = fail;
                        return;
                    }
                    if (isTruncated(frame.data())) {
                        truncated[0] = true;
                    }
                    logUsage(frame.data());
                    String delta = deltaOf(frame.data());
                    if (delta != null && !delta.isEmpty()) {
                        text.append(delta);
                        if (onDelta != null) {
                            onDelta.accept(delta);
                        }
                    }
                });

        if (failure[0] != null) {
            throw new AiException(AiException.Kind.SERVER, failure[0]);
        }
        if (truncated[0]) {
            // 半截代码拿去编译只会报一堆花括号没闭合，看不出真正的原因是没写完
            throw new AiException(AiException.Kind.TRUNCATED, "incomplete: max_tokens");
        }
        if (text.isEmpty()) {
            throw new AiException(AiException.Kind.BAD_RESPONSE, "stream ended without any output");
        }
        return text.toString();
    }

    /**
     * Chat 协议用 {@code finish_reason == "length"} 表示被 max_tokens 截断。
     *
     * <p>这一条比 Responses 那边更要紧：Responses 会把整个响应标成 {@code incomplete}，
     * 而 Chat 照常返回 200 和一段<b>看起来正常</b>的内容，只在 finish_reason 上留个记号。
     * 不看它就等于拿半截代码去编译。
     */
    static boolean isTruncated(String json) {
        JsonObject choice = firstChoice(json);
        if (choice == null || !choice.has("finish_reason")
                || !choice.get("finish_reason").isJsonPrimitive()) {
            return false;
        }
        return "length".equals(choice.get("finish_reason").getAsString());
    }

    /** Chat 的用量字段名和 Responses 不同：prompt/completion，而不是 input/output。 */
    private static void logUsage(String json) {
        try {
            JsonElement e = JsonParser.parseString(json);
            if (!e.isJsonObject()) {
                return;
            }
            JsonObject root = e.getAsJsonObject();
            if (!root.has("usage") || !root.get("usage").isJsonObject()) {
                return;
            }
            JsonObject u = root.getAsJsonObject("usage");
            int reasoning = 0;
            if (u.has("completion_tokens_details") && u.get("completion_tokens_details").isJsonObject()) {
                JsonObject d = u.getAsJsonObject("completion_tokens_details");
                if (d.has("reasoning_tokens") && d.get("reasoning_tokens").isJsonPrimitive()) {
                    reasoning = d.get("reasoning_tokens").getAsInt();
                }
            }
            AiLog.usage(intOf(u, "prompt_tokens"), intOf(u, "completion_tokens"), reasoning);
        } catch (Exception ignored) {
            // 用量只是诊断信息
        }
    }

    private static int intOf(JsonObject o, String key) {
        return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsInt() : 0;
    }

    /** 取 {@code choices[0].delta.content}；不是正文增量就返回 null。 */
    static @Nullable String deltaOf(String data) {
        JsonObject choice = firstChoice(data);
        if (choice == null || !choice.has("delta") || !choice.get("delta").isJsonObject()) {
            return null;
        }
        JsonObject delta = choice.getAsJsonObject("delta");
        // reasoning_content 是思维链，坚决不要
        if (delta.has("content") && delta.get("content").isJsonPrimitive()) {
            return delta.get("content").getAsString();
        }
        return null;
    }

    /** 流中途报错时，服务端会塞一个 {@code error} 对象进来。 */
    static @Nullable String errorOf(String data) {
        try {
            JsonElement e = JsonParser.parseString(data);
            if (!e.isJsonObject()) {
                return null;
            }
            JsonObject root = e.getAsJsonObject();
            if (!root.has("error")) {
                return null;
            }
            JsonElement err = root.get("error");
            if (err.isJsonObject()) {
                JsonObject eo = err.getAsJsonObject();
                if (eo.has("message") && eo.get("message").isJsonPrimitive()) {
                    return eo.get("message").getAsString();
                }
                return eo.toString();
            }
            return err.isJsonPrimitive() ? err.getAsString() : null;
        } catch (Exception ignored) {
            return null;
        }
    }
}
