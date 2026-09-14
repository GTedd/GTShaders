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
 * OpenAI Responses API 的客户端。
 *
 * <h2>为什么每一轮都重发完整对话</h2>
 *
 * <p>Responses API 本来有 {@code store} + {@code previous_response_id} 这套服务端记忆，
 * 但 DeepSeek 的文档写明这两个参数<b>被静默忽略</b>。「静默」是这里的关键：
 * 代码不会报错，只会在第二轮时让模型面对一段它看不见的上文，
 * 产出一份牛头不对马嘴的修正。所以修错轮必须自带完整源码，见 {@link AiPrompt#repairRequest}。
 */
public final class ResponsesClient implements AiClient {

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
        JsonArray items = new JsonArray();
        for (Turn t : input) {
            JsonObject item = new JsonObject();
            item.addProperty("role", t.role());
            item.addProperty("content", t.content());
            items.add(item);
        }
        JsonObject body = new JsonObject();
        body.addProperty("model", ep.model());
        body.addProperty("instructions", instructions);
        body.add("input", items);
        body.addProperty("stream", stream);
        body.addProperty("temperature", ep.temperature());
        // 自动档不发这个字段，服务端会用模型自己的最大值
        if (!ep.autoMaxOutput()) {
            body.addProperty("max_output_tokens", ep.maxOutputTokens());
        }
        return body.toString();
    }

    // ------------------------------------------------------------ 非流式

    private static String readWhole(AiEndpoint ep, String body) throws Exception {
        String raw = AiHttp.readWhole(
                AiHttp.post(ep.requestUrl(), ep.apiKey(), ep.timeoutSeconds(), body, false));
        logUsage(raw);
        String truncation = incompleteReason(raw);
        if (truncation != null) {
            throw new AiException(AiException.Kind.TRUNCATED, "incomplete: " + truncation);
        }
        String text = extractText(raw);
        if (text.isBlank()) {
            throw new AiException(AiException.Kind.BAD_RESPONSE, AiHttp.truncate(raw));
        }
        return text;
    }

    /**
     * 从整份响应里取出模型说的话。
     *
     * <p>先看 {@code output_text}：DeepSeek 在 HTTP 层就给了这个便捷字段。但 OpenAI 官方的
     * <b>原始 HTTP 响应里没有它</b>（那是各语言 SDK 拼出来的），所以必须能从
     * {@code output[].content[].text} 自己聚合，否则换个自建服务就取不到东西。
     *
     * <p>聚合时只收 {@code output_text} 类型的 content：推理模型还会在 output 里放
     * {@code reasoning} 项，混进去的话交给编译器的就是一段思维链加一段 GLSL。
     */
    static String extractText(String json) {
        JsonObject root;
        try {
            JsonElement e = JsonParser.parseString(json);
            if (!e.isJsonObject()) {
                return "";
            }
            root = e.getAsJsonObject();
        } catch (Exception e) {
            return "";
        }
        if (root.has("output_text") && root.get("output_text").isJsonPrimitive()) {
            String s = root.get("output_text").getAsString();
            if (!s.isBlank()) {
                return s;
            }
        }
        if (!root.has("output") || !root.get("output").isJsonArray()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (JsonElement item : root.getAsJsonArray("output")) {
            if (!item.isJsonObject()) {
                continue;
            }
            JsonObject o = item.getAsJsonObject();
            if (!o.has("content") || !o.get("content").isJsonArray()) {
                continue;
            }
            for (JsonElement c : o.getAsJsonArray("content")) {
                if (!c.isJsonObject()) {
                    continue;
                }
                JsonObject co = c.getAsJsonObject();
                String type = co.has("type") && co.get("type").isJsonPrimitive()
                        ? co.get("type").getAsString() : "";
                if ("output_text".equals(type) && co.has("text") && co.get("text").isJsonPrimitive()) {
                    sb.append(co.get("text").getAsString());
                }
            }
        }
        return sb.toString();
    }

    /**
     * 非流式响应里的截断标记。
     *
     * <p>不看这个字段的话，被截断的半截代码会被当成正常产出拿去编译——
     * 报出来的是一堆莫名的语法错误（因为花括号没闭合），而真正的原因是没写完。
     */
    static String incompleteReason(String json) {
        try {
            JsonElement e = JsonParser.parseString(json);
            if (!e.isJsonObject()) {
                return null;
            }
            JsonObject root = e.getAsJsonObject();
            String status = root.has("status") && root.get("status").isJsonPrimitive()
                    ? root.get("status").getAsString() : "";
            if (!"incomplete".equals(status)) {
                return null;
            }
            if (root.has("incomplete_details") && root.get("incomplete_details").isJsonObject()) {
                JsonObject d = root.getAsJsonObject("incomplete_details");
                if (d.has("reason") && d.get("reason").isJsonPrimitive()) {
                    return d.get("reason").getAsString();
                }
            }
            return "unknown";
        } catch (Exception ignored) {
            return null;
        }
    }

    /**
     * 把 token 用量记进日志。
     *
     * <p>{@code reasoning_tokens} 是排查截断时最关键的一个数：它接近上限而正文寥寥，
     * 就说明预算全花在思维链上了，而表面症状只是「输出被截断」。
     */
    private static void logUsage(String json) {
        try {
            JsonElement e = JsonParser.parseString(json);
            if (!e.isJsonObject()) {
                return;
            }
            JsonObject root = e.getAsJsonObject();
            // 流式的 response.completed 把整个 response 套了一层
            if (root.has("response") && root.get("response").isJsonObject()) {
                root = root.getAsJsonObject("response");
            }
            if (!root.has("usage") || !root.get("usage").isJsonObject()) {
                return;
            }
            JsonObject u = root.getAsJsonObject("usage");
            int reasoning = 0;
            if (u.has("output_tokens_details") && u.get("output_tokens_details").isJsonObject()) {
                JsonObject d = u.getAsJsonObject("output_tokens_details");
                if (d.has("reasoning_tokens") && d.get("reasoning_tokens").isJsonPrimitive()) {
                    reasoning = d.get("reasoning_tokens").getAsInt();
                }
            }
            AiLog.usage(intOf(u, "input_tokens"), intOf(u, "output_tokens"), reasoning);
        } catch (Exception ignored) {
            // 用量只是诊断信息，解析不出来不该影响正事
        }
    }

    private static int intOf(JsonObject o, String key) {
        return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsInt() : 0;
    }

    // ------------------------------------------------------------ 流式

    private static String readStream(AiEndpoint ep, String body,
                                     @Nullable Consumer<String> onDelta,
                                     @Nullable BooleanSupplier cancelled) throws Exception {
        StringBuilder text = new StringBuilder();
        String[] failure = {null};
        boolean[] completed = {false};

        AiHttp.readStream(
                AiHttp.post(ep.requestUrl(), ep.apiKey(), ep.timeoutSeconds(), body, true),
                cancelled,
                frame -> {
                    if (failure[0] != null) {
                        return;
                    }
                    String fail = SseParser.failure(frame);
                    if (fail != null) {
                        failure[0] = fail;
                        return;
                    }
                    if (SseParser.isCompleted(frame)) {
                        completed[0] = true;
                        logUsage(frame.data());
                        return;
                    }
                    String delta = SseParser.textDelta(frame);
                    if (delta != null && !delta.isEmpty()) {
                        text.append(delta);
                        if (onDelta != null) {
                            onDelta.accept(delta);
                        }
                    }
                });

        if (failure[0] != null) {
            // 截断可恢复（把上限调大重试），别的不行——归错类的话上层就没机会自救
            AiException.Kind kind = failure[0].startsWith("incomplete:")
                    ? AiException.Kind.TRUNCATED
                    : AiException.Kind.SERVER;
            throw new AiException(kind, failure[0]);
        }
        if (text.isEmpty()) {
            throw new AiException(AiException.Kind.BAD_RESPONSE,
                    completed[0] ? "empty output" : "stream ended without any output");
        }
        return text.toString();
    }
}
