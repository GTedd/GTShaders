package mc.GTedd.cn.gtshaders.ai;

import mc.GTedd.cn.gtshaders.GTShaders;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * AI 调用的日志与诊断记录。
 *
 * <h2>为什么值得单独做</h2>
 *
 * <p>在有这个类之前，一次失败的生成在 {@code latest.log} 里<b>一个字都不留</b>。
 * 玩家能看到的只有界面上一行归好类的错误，而那行字是刻意写给玩家看的、
 * 不含任何可用于排查的细节：哪个地址、哪个模型、哪种协议、token 花在了哪儿、
 * 第几轮失败的——全都没有。远程帮人排查时这等于什么线索都没有。
 *
 * <p>所以这里做两件事：往日志里写一份带 {@code [GTShaders/AI]} 前缀的时间线（方便 grep），
 * 同时在内存里留最近若干条，界面上一个按钮就能复制走。
 *
 * <h2>绝不记录的东西</h2>
 *
 * <p><b>API Key 一个字符都不进来。</b>日志文件会被玩家整份发到群里、贴进 issue，
 * 而它一旦落进日志就再也收不回来了。这个类的每个入口都只接收调用方明确挑好的字段，
 * 没有「把 endpoint 整个打出来」这种便捷方法——那种方法迟早会被人用在含 key 的对象上。
 */
public final class AiLog {

    /** 内存里留最近这么多条，够覆盖一次完整生成（含三轮修错）。 */
    private static final int KEEP = 60;

    private static final List<String> RECENT = new ArrayList<>();

    private AiLog() {
    }

    /** 一次请求发出去之前。<b>url 不含 key</b>——key 在 Authorization 头里。 */
    public static void request(String providerLabel, AiProtocol protocol, String model,
                               String url, boolean stream, int maxOutputTokens) {
        record("request provider=%s protocol=%s model=%s stream=%s max_output_tokens=%s url=%s",
                providerLabel, protocol.name().toLowerCase(Locale.ROOT), model, stream,
                // 0 是「不发这个字段」，照原样打成 0 会让看日志的人以为上限真的是零
                maxOutputTokens <= 0 ? "auto" : String.valueOf(maxOutputTokens), url);
    }

    /**
     * 服务端报的 token 用量。
     *
     * <p>{@code reasoning} 是这里最关键的一个数：推理模型的思维链<b>也算进
     * max_output_tokens</b>。它接近上限而正文寥寥，就说明 token 全花在推理上了——
     * 而表面症状只是「输出被截断」，完全看不出是这个原因。
     */
    public static void usage(int input, int output, int reasoning) {
        if (reasoning > 0) {
            record("usage input=%d output=%d reasoning=%d", input, output, reasoning);
        } else {
            record("usage input=%d output=%d", input, output);
        }
    }

    public static void truncated(int wasLimit, int nextLimit) {
        record("truncated at max_output_tokens=%d, retrying with %d", wasLimit, nextLimit);
    }

    public static void extracted(int replyChars, int sourceChars, List<String> notes) {
        record("extracted reply=%d chars, source=%d chars, notes=%s",
                replyChars, sourceChars, notes);
    }

    public static void compile(int round, List<String> errors) {
        if (errors.isEmpty()) {
            record("compile round=%d ok", round);
        } else {
            record("compile round=%d errors=%d first=%s", round, errors.size(), errors.getFirst());
        }
    }

    public static void pitfalls(List<String> codes) {
        record("pitfalls %s", codes);
    }

    public static void stage(String stage, int round, int budget) {
        record("stage %s round=%d/%d", stage, round, budget);
    }

    public static void models(String url, int count) {
        record("models %d from %s", count, url);
    }

    public static void done(int round, int sourceChars) {
        record("done round=%d source=%d chars", round, sourceChars);
    }

    public static void failed(AiException e) {
        record("failed kind=%s detail=%s", e.kind(), e.detail());
    }

    /** 归不进上面任何一类的说明。 */
    public static void note(String message) {
        record("%s", message);
    }

    private static void record(String format, Object... args) {
        String line = String.format(Locale.ROOT, format, args);
        GTShaders.LOGGER.info("[GTShaders/AI] {}", line);
        synchronized (RECENT) {
            RECENT.add(line);
            while (RECENT.size() > KEEP) {
                RECENT.removeFirst();
            }
        }
    }

    /**
     * 最近的时间线，一行一条，供界面上的「复制诊断」用。
     *
     * <p>刻意不加时间戳：日志文件里本来就有，而这份是要贴进聊天窗口的，越短越可能被真的贴出来。
     */
    public static String recent() {
        synchronized (RECENT) {
            if (RECENT.isEmpty()) {
                return "(no AI activity yet)";
            }
            return String.join("\n", RECENT);
        }
    }

    public static void clear() {
        synchronized (RECENT) {
            RECENT.clear();
        }
    }
}
