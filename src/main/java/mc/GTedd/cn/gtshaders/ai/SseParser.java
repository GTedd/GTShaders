package mc.GTedd.cn.gtshaders.ai;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.jspecify.annotations.Nullable;

/**
 * Responses API 流式返回的 SSE 解析。
 *
 * <h2>为什么不能简单地「一行 data 一个事件」</h2>
 *
 * <p>SSE 是<b>按空行分帧</b>的协议，一帧里可以有多行 {@code data:}，语义是用 {@code \n}
 * 拼起来。绝大多数时候模型返回的 JSON 都在一行里，于是「一行一事件」的写法能跑很久——
 * 直到某个 delta 恰好含有换行、或者某个中转服务按自己的缓冲边界断行，
 * 那时拿到的就是半截 JSON，解析失败，流看起来像是「生成到一半突然停了」。
 *
 * <h2>为什么优先看 data 里的 type 而不是 event: 行</h2>
 *
 * <p>官方实现两处都给，内容一样。但中转服务（one-api 这类）经常只转发 {@code data:} 而
 * 丢掉 {@code event:} 行——那时只认 {@code event:} 就等于收不到任何事件。
 * 反过来 {@code data:} 里的 {@code type} 字段是 JSON 载荷的一部分，谁转发都不会弄丢。
 * 所以以 {@code type} 为准，{@code event:} 只作回退。
 */
public final class SseParser {

    /**
     * @param event {@code event:} 行的内容，可能为空
     * @param data  该帧全部 {@code data:} 行按 {@code \n} 拼接的结果
     */
    public record Frame(String event, String data) {
    }

    private final StringBuilder data = new StringBuilder();
    private String event = "";
    private boolean done;

    /** 见过 {@code data: [DONE]}。某些兼容实现用它收尾，官方实现则用 response.completed。 */
    public boolean isDone() {
        return done;
    }

    /**
     * 喂一行。返回该行凑齐的一帧，未凑齐则返回 null。
     *
     * @param raw 不含行尾换行符的一行
     */
    public @Nullable Frame feedLine(String raw) {
        String line = raw == null ? "" : stripBom(raw);
        // 行尾可能残留 \r：HTTP 是 CRLF，而按 \n 切行的实现会把 \r 留下
        if (line.endsWith("\r")) {
            line = line.substring(0, line.length() - 1);
        }
        if (line.isEmpty()) {
            return emit();
        }
        // 冒号开头是注释行，服务端拿它当心跳保活，必须忽略而不是当成字段
        if (line.startsWith(":")) {
            return null;
        }
        int colon = line.indexOf(':');
        String field = colon < 0 ? line : line.substring(0, colon);
        String value = colon < 0 ? "" : line.substring(colon + 1);
        if (value.startsWith(" ")) {
            value = value.substring(1);
        }
        switch (field) {
            case "event" -> event = value;
            case "data" -> {
                if ("[DONE]".equals(value.trim())) {
                    done = true;
                    return null;
                }
                if (!data.isEmpty()) {
                    data.append('\n');
                }
                data.append(value);
            }
            default -> {
                // id / retry 之类字段用不上，忽略
            }
        }
        return null;
    }

    /** 流结束时调用：没有以空行收尾的最后一帧在这里补交。 */
    public @Nullable Frame flush() {
        return emit();
    }

    private @Nullable Frame emit() {
        if (data.isEmpty() && event.isEmpty()) {
            return null;
        }
        Frame f = new Frame(event, data.toString());
        data.setLength(0);
        event = "";
        return f;
    }

    /** BOM 写成转义形式：直接敲进源文件的话，下一个人看到的是一个空白的比较。 */
    private static String stripBom(String s) {
        return !s.isEmpty() && s.charAt(0) == '\uFEFF' ? s.substring(1) : s;
    }

    // ------------------------------------------------------------ 语义

    /** 一帧的类型：以 data 里的 {@code type} 为准，缺失时回退到 {@code event:} 行。 */
    public static String typeOf(Frame f) {
        JsonObject o = asObject(f.data());
        if (o != null && o.has("type") && o.get("type").isJsonPrimitive()) {
            return o.get("type").getAsString();
        }
        return f.event();
    }

    /**
     * 取出增量文本。
     *
     * <p>只认 {@code response.output_text.delta}。推理模型还会发
     * {@code response.reasoning_summary_text.delta}——那是思维链，<b>不能拼进源码</b>，
     * 混进去的话最后交给编译器的就是一段中文推理加一段 GLSL。
     */
    public static @Nullable String textDelta(Frame f) {
        if (!"response.output_text.delta".equals(typeOf(f))) {
            return null;
        }
        JsonObject o = asObject(f.data());
        if (o == null) {
            return null;
        }
        JsonElement d = o.get("delta");
        return d != null && d.isJsonPrimitive() ? d.getAsString() : null;
    }

    /**
     * 取出终止性错误的说明；这一帧不是错误则返回 null。
     *
     * <p>{@code response.incomplete} 也算失败：它意味着输出被 max_output_tokens 截断，
     * 拿到的是半截着色器。半截源码编译失败之后的报错完全指不到真正的原因，
     * 不如在这里就说清楚「输出被截断了，把上限调高」。
     */
    public static @Nullable String failure(Frame f) {
        String type = typeOf(f);
        JsonObject o = asObject(f.data());
        return switch (type) {
            case "error", "response.failed" -> messageOf(o, "unknown error");
            case "response.incomplete" -> "incomplete: " + messageOf(o, "output truncated");
            default -> null;
        };
    }

    public static boolean isCompleted(Frame f) {
        return "response.completed".equals(typeOf(f));
    }

    /** 错误信息可能在 {@code error.message}、{@code response.incomplete_details.reason} 或顶层 {@code message}。 */
    private static String messageOf(@Nullable JsonObject o, String fallback) {
        if (o == null) {
            return fallback;
        }
        if (o.has("error") && o.get("error").isJsonObject()) {
            JsonObject err = o.getAsJsonObject("error");
            if (err.has("message") && err.get("message").isJsonPrimitive()) {
                return err.get("message").getAsString();
            }
        }
        if (o.has("response") && o.get("response").isJsonObject()) {
            JsonObject r = o.getAsJsonObject("response");
            if (r.has("incomplete_details") && r.get("incomplete_details").isJsonObject()) {
                JsonObject d = r.getAsJsonObject("incomplete_details");
                if (d.has("reason") && d.get("reason").isJsonPrimitive()) {
                    return d.get("reason").getAsString();
                }
            }
        }
        if (o.has("message") && o.get("message").isJsonPrimitive()) {
            return o.get("message").getAsString();
        }
        return fallback;
    }

    private static @Nullable JsonObject asObject(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            JsonElement e = JsonParser.parseString(json);
            return e.isJsonObject() ? e.getAsJsonObject() : null;
        } catch (Exception e) {
            return null;
        }
    }
}
