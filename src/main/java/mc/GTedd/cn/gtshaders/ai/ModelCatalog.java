package mc.GTedd.cn.gtshaders.ai;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 向服务商要一份模型清单。
 *
 * <h2>为什么值得专门做这件事</h2>
 *
 * <p>模型名必须和服务商文档<b>一字不差</b>，而它们长得都差不多：
 * {@code deepseek-v4-pro}、{@code deepseek-v4-flash}、{@code deepseek-v4-flash-vision-exp}。
 * 手敲错一个字符，服务端回的是 404——而 404 在玩家眼里和「地址填错了」「服务挂了」
 * 完全一样，他会去反复核对一个本来就没错的地址。
 *
 * <p>让他从清单里点一个，这一类错误就整类消失了。顺带还能确认 key 是通的：
 * 拉得到清单，说明地址、key、网络三样都对。
 *
 * <h2>为什么不做过滤</h2>
 *
 * <p>OpenAI 那类服务的清单里混着语音、图像、嵌入模型，看起来该按名字滤掉。
 * 但过滤规则是猜的——今天靠 {@code -embed} 后缀滤掉的，明天可能就误伤一个新的对话模型，
 * 而玩家看到的是「我要用的那个模型不在列表里」，完全无从判断是服务商没给还是我们滤掉了。
 * 全列出来，让他自己认。
 */
public final class ModelCatalog {

    /**
     * 拉过的清单按 host 记着。
     *
     * <p>玩家在服务商之间来回切时不该每切一次就发一次请求——那既慢又白花配额，
     * 而模型清单在一次游戏会话里几乎不会变。想强制刷新的话界面上有个按钮。
     */
    private static final Map<String, List<String>> CACHE = new LinkedHashMap<>();

    private ModelCatalog() {
    }

    /** 缓存里有就直接给，没有返回空列表。界面每帧都要读，不能在这里发请求。 */
    public static synchronized List<String> cached(AiEndpoint endpoint) {
        return CACHE.getOrDefault(cacheKey(endpoint), List.of());
    }

    public static synchronized boolean hasCached(AiEndpoint endpoint) {
        return CACHE.containsKey(cacheKey(endpoint));
    }

    public static synchronized void forget(AiEndpoint endpoint) {
        CACHE.remove(cacheKey(endpoint));
    }

    private static String cacheKey(AiEndpoint endpoint) {
        return endpoint.modelsUrl();
    }

    /**
     * 真去拉一次。<b>阻塞，必须在后台线程里调。</b>
     *
     * @return 模型 id，按字母序；服务商返回空清单时也返回空列表
     */
    public static List<String> fetch(AiEndpoint endpoint) {
        String url = endpoint.modelsUrl();
        if (url.isBlank()) {
            throw new AiException(AiException.Kind.NOT_FOUND, "empty base url");
        }
        if (!endpoint.hasKey()) {
            throw new AiException(AiException.Kind.AUTH, "no api key");
        }
        HttpRequest req = AiHttp.get(url, endpoint.apiKey(),
                Math.min(30, endpoint.timeoutSeconds()));
        try {
            HttpResponse<String> resp = AiHttp.CLIENT.send(
                    req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (resp.statusCode() != 200) {
                throw AiHttp.classify(resp.statusCode(), resp.body());
            }
            List<String> ids = parse(resp.body());
            if (ids.isEmpty()) {
                throw new AiException(AiException.Kind.BAD_RESPONSE,
                        AiHttp.truncate(resp.body()));
            }
            synchronized (ModelCatalog.class) {
                CACHE.put(cacheKey(endpoint), ids);
            }
            AiLog.models(url, ids.size());
            return ids;
        } catch (HttpTimeoutException e) {
            throw new AiException(AiException.Kind.TIMEOUT, String.valueOf(e.getMessage()), e);
        } catch (UnknownHostException e) {
            throw new AiException(AiException.Kind.NETWORK, "unknown host: " + e.getMessage(), e);
        } catch (ConnectException e) {
            throw new AiException(AiException.Kind.NETWORK, String.valueOf(e.getMessage()), e);
        } catch (IOException e) {
            throw new AiException(AiException.Kind.NETWORK,
                    e.getClass().getSimpleName() + ": " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AiException(AiException.Kind.CANCELLED, "interrupted", e);
        }
    }

    /**
     * 从清单里挑一个适合写着色器的。
     *
     * <p>清单是<b>不过滤</b>的（理由见类注释），但「替玩家预选一个」和「给他看全部」
     * 是两件事。字母序第一个往往正好是最不该用的那个——DeepSeek 的清单里
     * {@code deepseek-v4-flash-vision-exp} 就排在 {@code deepseek-v4-pro} 前面，
     * 而拿一个视觉实验模型去写 GLSL，得到的是一段被思维链挤掉的半截代码。
     *
     * <p>所以先剔掉明摆着不是干这个的（视觉、语音、嵌入、重排），
     * 再优先挑名字里带 pro / coder / plus 的。都挑不出来就退回第一个——
     * 这只是个预选，玩家随时能在下拉里改。
     */
    public static String preferredFor(List<String> models) {
        if (models.isEmpty()) {
            return "";
        }
        List<String> usable = new ArrayList<>();
        for (String m : models) {
            if (!looksOffTopic(m)) {
                usable.add(m);
            }
        }
        if (usable.isEmpty()) {
            usable = models;
        }
        for (String want : List.of("coder", "-pro", "pro", "plus", "chat")) {
            for (String m : usable) {
                if (m.toLowerCase(Locale.ROOT).contains(want)) {
                    return m;
                }
            }
        }
        return usable.getFirst();
    }

    /** 名字里带这些的不是用来写代码的。只用于「预选哪个」，不从清单里隐藏。 */
    private static boolean looksOffTopic(String model) {
        String m = model.toLowerCase(Locale.ROOT);
        for (String bad : List.of("vision", "embed", "rerank", "audio", "tts", "whisper",
                "ocr", "image", "moderation", "-vl")) {
            if (m.contains(bad)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 解析 {@code {"object":"list","data":[{"id":"…"}]}}。
     *
     * <p>也认顶层直接是数组的写法——中转服务这一层的实现相当随意，
     * 而这里多认一种形状的成本只有三行。
     */
    static List<String> parse(String json) {
        List<String> out = new ArrayList<>();
        JsonElement root;
        try {
            root = JsonParser.parseString(json);
        } catch (Exception e) {
            return out;
        }
        JsonElement data = root;
        if (root.isJsonObject()) {
            JsonObject o = root.getAsJsonObject();
            if (!o.has("data")) {
                return out;
            }
            data = o.get("data");
        }
        if (!data.isJsonArray()) {
            return out;
        }
        for (JsonElement e : data.getAsJsonArray()) {
            String id = idOf(e);
            if (id != null && !id.isBlank() && !out.contains(id)) {
                out.add(id);
            }
        }
        out.sort(String.CASE_INSENSITIVE_ORDER);
        return out;
    }

    /** 条目通常是 {@code {"id":"…"}}，但也见过直接给字符串的。 */
    private static String idOf(JsonElement e) {
        if (e.isJsonPrimitive()) {
            return e.getAsString();
        }
        if (!e.isJsonObject()) {
            return null;
        }
        JsonObject o = e.getAsJsonObject();
        for (String key : List.of("id", "model", "name")) {
            if (o.has(key) && o.get(key).isJsonPrimitive()) {
                return o.get(key).getAsString();
            }
        }
        return null;
    }
}
