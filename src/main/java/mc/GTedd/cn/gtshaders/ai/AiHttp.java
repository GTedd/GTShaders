package mc.GTedd.cn.gtshaders.ai;

import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.net.ConnectException;
import java.net.ProxySelector;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 两种协议共用的 HTTP 底座：连接、超时、错误归类、SSE 读取循环。
 *
 * <p>协议之间不同的只有请求体长什么样、响应里去哪儿捞文本。
 * 连接怎么建、代理怎么走、404 该说成什么话、流怎么一行行读——这些完全一样，
 * 抄两遍的话，下次改超时或者加一种错误码就要记得改两处，而漏掉的那处不会报错。
 */
final class AiHttp {

    /** 连接阶段的超时。整体超时另由 {@link AiEndpoint#timeoutSeconds()} 控制。 */
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(15);

    /**
     * 共享实例。
     *
     * <p>{@code ProxySelector.getDefault()} 是刻意给的：不给的话 HttpClient 走
     * {@code NO_PROXY}，玩家在启动参数里配的 {@code -Dhttps.proxyHost} 会被无视，
     * 于是「浏览器能开、游戏里连不上」——这种不一致极难自查。
     */
    static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            .proxy(ProxySelector.getDefault())
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private AiHttp() {
    }

    static HttpRequest post(String url, String apiKey, int timeoutSeconds, String body, boolean stream) {
        return HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/json")
                .header("Accept", stream ? "text/event-stream" : "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
    }

    static HttpRequest get(String url, String apiKey, int timeoutSeconds) {
        return HttpRequest.newBuilder(URI.create(url))
                .header("Accept", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .GET()
                .build();
    }

    /** 整段读回。非 200 直接归类成 {@link AiException}。 */
    static String readWhole(HttpRequest req) throws IOException, InterruptedException {
        HttpResponse<String> resp = CLIENT.send(req,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (resp.statusCode() != 200) {
            throw classify(resp.statusCode(), resp.body());
        }
        return resp.body();
    }

    /**
     * 按 SSE 逐帧读，每一帧交给 {@code sink}。
     *
     * <p>只负责分帧和取消检查；帧里是什么意思由各协议自己解释——
     * 这正是两种协议唯一真正不同的地方。
     */
    static void readStream(HttpRequest req, @Nullable BooleanSupplier cancelled,
                           Consumer<SseParser.Frame> sink)
            throws IOException, InterruptedException {
        HttpResponse<Stream<String>> resp = CLIENT.send(req, HttpResponse.BodyHandlers.ofLines());
        if (resp.statusCode() != 200) {
            // 错误响应即使在流式请求下也是一份完整 JSON，收全了再归类
            try (Stream<String> body = resp.body()) {
                throw classify(resp.statusCode(), body.collect(Collectors.joining("\n")));
            }
        }
        SseParser parser = new SseParser();
        try (Stream<String> lines = resp.body()) {
            for (String line : (Iterable<String>) lines::iterator) {
                if (cancelled != null && cancelled.getAsBoolean()) {
                    throw new AiException(AiException.Kind.CANCELLED, "cancelled by user");
                }
                SseParser.Frame f = parser.feedLine(line);
                if (f != null) {
                    sink.accept(f);
                }
                if (parser.isDone()) {
                    return;
                }
            }
            SseParser.Frame tail = parser.flush();
            if (tail != null) {
                sink.accept(tail);
            }
        }
    }

    /** 把网络层的各种异常翻成 {@link AiException}。两个客户端的 catch 块本来一模一样。 */
    static AiException wrap(Exception e) {
        if (e instanceof AiException ai) {
            return ai;
        }
        if (e instanceof HttpTimeoutException) {
            return new AiException(AiException.Kind.TIMEOUT, String.valueOf(e.getMessage()), e);
        }
        if (e instanceof UnknownHostException) {
            return new AiException(AiException.Kind.NETWORK, "unknown host: " + e.getMessage(), e);
        }
        if (e instanceof ConnectException) {
            return new AiException(AiException.Kind.NETWORK, String.valueOf(e.getMessage()), e);
        }
        if (e instanceof InterruptedException) {
            Thread.currentThread().interrupt();
            return new AiException(AiException.Kind.CANCELLED, "interrupted", e);
        }
        return new AiException(AiException.Kind.NETWORK,
                e.getClass().getSimpleName() + ": " + e.getMessage(), e);
    }

    /**
     * HTTP 状态码 + 响应体 → 玩家看得懂的类别。
     *
     * <p>402 单列是因为 DeepSeek 用它表示余额不足，而 402 在别处几乎见不到。
     * 401/403 都归 AUTH：玩家的动作是同一个——去检查那把 key。
     */
    static AiException classify(int status, String body) {
        String detail = truncate(body);
        String lower = detail.toLowerCase(Locale.ROOT);
        return switch (status) {
            case 401, 403 -> new AiException(AiException.Kind.AUTH, detail);
            case 402 -> new AiException(AiException.Kind.QUOTA, detail);
            case 404 -> new AiException(AiException.Kind.NOT_FOUND, detail);
            case 429 -> new AiException(
                    // 429 两义：限流，或者配额彻底用完。措辞里带 quota/balance 的归后者，
                    // 因为「等一会儿再试」对已经欠费的人是纯粹的浪费时间
                    lower.contains("quota") || lower.contains("balance") || lower.contains("insufficient")
                            ? AiException.Kind.QUOTA : AiException.Kind.RATE_LIMIT, detail);
            case 400, 422 -> new AiException(AiException.Kind.BAD_RESPONSE, detail);
            default -> new AiException(
                    status >= 500 ? AiException.Kind.SERVER : AiException.Kind.BAD_RESPONSE, detail);
        };
    }

    /** 服务端可能回一整页 HTML（网关错误页），界面上放不下也没必要放。 */
    static String truncate(String s) {
        if (s == null) {
            return "";
        }
        String t = s.strip();
        return t.length() <= 600 ? t : t.substring(0, 600) + "…";
    }

    /** 请求前的通用前置检查，两个客户端都要做。 */
    static void requireUsable(AiEndpoint endpoint) {
        if (endpoint.requestUrl().isBlank()) {
            throw new AiException(AiException.Kind.NOT_FOUND, "empty base url");
        }
        if (!endpoint.hasKey()) {
            throw new AiException(AiException.Kind.AUTH, "no api key");
        }
    }
}
