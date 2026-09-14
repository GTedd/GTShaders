package mc.GTedd.cn.gtshaders.ai;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import mc.GTedd.cn.gtshaders.GTShaders;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * AI 生成的可配置项——<b>除了 key 以外的全部</b>。
 *
 * <h2>为什么 apiKey 不是这个 record 的一个字段</h2>
 *
 * <p>这份配置存在 {@code config/gtshaders/ai.json}，而 {@code config/} 会被整合包整个打包带走。
 * 把 key 放进来只需要加一个字段那么容易，所以这里的防线不能是「记得在序列化时跳过它」——
 * 那种约定第一次重构就会被忘掉，而且忘掉之后一切照常工作，没有任何报错提醒你刚泄露了什么。
 *
 * <p>防线只能建在类型上：<b>这个 record 和 {@link AiProvider} 里都没有能装 key 的地方。</b>
 * 想序列化都无从下手。key 走 {@link AiCredentials}，落在 {@code ~/.gtshaders/}，
 * 两者只在 {@link AiEndpoint} 里合流一次，而 AiEndpoint 没有任何写盘的路径。
 *
 * <h2>为什么存一组 provider 而不是一套设置</h2>
 *
 * <p>换服务商这件事的实际频率比想象的高：官方限流了临时切中转、想省钱切便宜模型、
 * 拿本地模型试一下。存一套的话每次切换都要重敲地址和模型名，而敲错的唯一症状是 404。
 * 存一组、点一下切过去，key 也还在（按 host 存），这才是能用的形态。
 *
 * @param providers       全部服务商配置，至少一条
 * @param activeId        当前选中的那条的 id
 * @param temperature     采样温度
 * @param maxOutputTokens 单次输出上限
 * @param stream          是否流式。关掉就变成「转圈等一整段」，只在流式出问题时才该关
 * @param repairRounds    编译失败后自动重修的轮数，0 为不重修
 * @param timeoutSeconds  单次请求超时
 */
public record AiConfig(List<AiProvider> providers, String activeId,
                       float temperature, int maxOutputTokens,
                       boolean stream, int repairRounds, int timeoutSeconds) {

    private static final String FILE = "ai.json";

    /**
     * {@code maxOutputTokens} 取这个值表示<b>不发这个字段</b>，由服务端按模型的能力决定。
     *
     * <p>这是默认行为，也是唯一说得通的默认行为。自己填一个数意味着要替所有模型猜一个上限，
     * 而这一代模型的输出能力差了两个数量级——DeepSeek V4 Pro 能出 384K token，
     * 填 8192 是把它按在百分之二的额度上。更糟的是 reasoning token 也算在这个上限里：
     * 猜小了，模型先想两千字，正文还没开始写就被截断，
     * 报出来只是一句「输出被截断」，完全看不出是谁吃掉的。
     *
     * <p>不发这个字段，服务端就用模型自己的最大值——那才是「兼容模型正常输出」。
     * 想封顶控成本的人可以在界面上挑一个具体的数。
     */
    public static final int MAX_OUTPUT_AUTO = 0;

    /**
     * 默认指向 DeepSeek。
     *
     * <p>选 {@code deepseek-v4-pro} 而不是 flash：这个功能的成败几乎完全取决于模型写
     * GLSL 的水平，而单次生成不过一两千 output token，省下的钱远不值得换来一次
     * 「生成了三轮都编译不过」的体验。想省的人在界面上一键换。
     */
    public static AiConfig defaults() {
        List<AiProvider> presets = AiProvider.presets();
        return new AiConfig(presets, presets.getFirst().id(), 0.7f, MAX_OUTPUT_AUTO, true, 3, 120);
    }

    // ------------------------------------------------------------ 当前选中

    /** 当前选中的那条。activeId 指向不存在的条目时退回第一条，永远不返回 null。 */
    public AiProvider active() {
        for (AiProvider p : providers) {
            if (p.id().equals(activeId)) {
                return p;
            }
        }
        return providers.isEmpty() ? AiProvider.presets().getFirst() : providers.getFirst();
    }

    public AiConfig withActive(String id) {
        return new AiConfig(providers, id, temperature, maxOutputTokens,
                stream, repairRounds, timeoutSeconds);
    }

    /** 就地替换同 id 的那条。改地址、改模型、改标签都走这里。 */
    public AiConfig withProvider(AiProvider updated) {
        List<AiProvider> next = new ArrayList<>(providers.size());
        boolean found = false;
        for (AiProvider p : providers) {
            if (p.id().equals(updated.id())) {
                next.add(updated);
                found = true;
            } else {
                next.add(p);
            }
        }
        if (!found) {
            next.add(updated);
        }
        return new AiConfig(next, activeId, temperature, maxOutputTokens,
                stream, repairRounds, timeoutSeconds);
    }

    /** 加一条并立刻选中它——加完还得自己去点一下选中，是没有道理的。 */
    public AiConfig plusProvider(AiProvider added) {
        List<AiProvider> next = new ArrayList<>(providers);
        next.add(added);
        return new AiConfig(next, added.id(), temperature, maxOutputTokens,
                stream, repairRounds, timeoutSeconds);
    }

    /**
     * 删掉一条。
     *
     * <p>删到一条不剩时补回默认预设：空列表意味着界面上没有任何可选项，
     * 玩家会卡在一个既不能生成也不能配置的状态里。
     */
    public AiConfig minusProvider(String id) {
        List<AiProvider> next = new ArrayList<>(providers.size());
        for (AiProvider p : providers) {
            if (!p.id().equals(id)) {
                next.add(p);
            }
        }
        if (next.isEmpty()) {
            next.addAll(AiProvider.presets());
        }
        String nextActive = next.stream().anyMatch(p -> p.id().equals(activeId))
                ? activeId : next.getFirst().id();
        return new AiConfig(next, nextActive, temperature, maxOutputTokens,
                stream, repairRounds, timeoutSeconds);
    }

    // ------------------------------------------------------------ 其余设置

    public AiConfig withStream(boolean v) {
        return new AiConfig(providers, activeId, temperature, maxOutputTokens,
                v, repairRounds, timeoutSeconds);
    }

    public AiConfig withRepairRounds(int v) {
        return new AiConfig(providers, activeId, temperature, maxOutputTokens,
                stream, Math.clamp(v, 0, 5), timeoutSeconds);
    }

    public AiConfig withTemperature(float v) {
        return new AiConfig(providers, activeId, Math.clamp(v, 0f, 2f), maxOutputTokens,
                stream, repairRounds, timeoutSeconds);
    }

    /** 传 {@link #MAX_OUTPUT_AUTO} 表示交给服务端决定；其余值按 1M 封顶。 */
    public AiConfig withMaxOutputTokens(int v) {
        return new AiConfig(providers, activeId, temperature, clampOutput(v),
                stream, repairRounds, timeoutSeconds);
    }

    static int clampOutput(int v) {
        return v <= 0 ? MAX_OUTPUT_AUTO : Math.clamp(v, 256, 1_000_000);
    }

    public AiConfig withTimeoutSeconds(int v) {
        return new AiConfig(providers, activeId, temperature, maxOutputTokens,
                stream, repairRounds, Math.clamp(v, 10, 600));
    }

    // ------------------------------------------------------------ 读写

    /**
     * 读。缺字段、坏文件都退回默认值：这份配置里没有任何丢不起的东西
     * （真正丢不起的 key 不在这里），能让功能继续可用比忠实报错重要。
     */
    public static AiConfig load(Path dir) {
        Path file = dir.resolve(FILE);
        AiConfig def = defaults();
        if (!Files.isRegularFile(file)) {
            return def;
        }
        try {
            JsonObject o = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8))
                    .getAsJsonObject();
            List<AiProvider> providers = readProviders(o);
            String active = str(o, "activeId", providers.getFirst().id());
            return new AiConfig(
                    providers,
                    active,
                    (float) Math.clamp(num(o, "temperature", def.temperature()), 0, 2),
                    clampOutput((int) num(o, "maxOutputTokens", def.maxOutputTokens())),
                    bool(o, "stream", def.stream()),
                    (int) Math.clamp(num(o, "repairRounds", def.repairRounds()), 0, 5),
                    (int) Math.clamp(num(o, "timeoutSeconds", def.timeoutSeconds()), 10, 600));
        } catch (Exception e) {
            GTShaders.LOGGER.warn("GTShaders AI 配置无法解析（{}），使用默认值",
                    e.getClass().getSimpleName());
            return def;
        }
    }

    /**
     * 读 provider 列表，并兼容只有一套设置的旧格式。
     *
     * <p>旧版把 {@code baseUrl} / {@code model} 直接放在顶层。直接忽略它们的话，
     * 已经配好并用了一段时间的玩家升级之后会发现地址和模型都变回默认——
     * 而 key 还在，于是他会以为是自己手滑改了什么。
     */
    private static List<AiProvider> readProviders(JsonObject o) {
        List<AiProvider> out = new ArrayList<>();
        if (o.has("providers") && o.get("providers").isJsonArray()) {
            for (JsonElement e : o.getAsJsonArray("providers")) {
                if (!e.isJsonObject()) {
                    continue;
                }
                JsonObject p = e.getAsJsonObject();
                String id = str(p, "id", "");
                String baseUrl = str(p, "baseUrl", "");
                if (baseUrl.isBlank()) {
                    continue;
                }
                out.add(new AiProvider(
                        id.isBlank() ? AiProvider.newId() : id,
                        str(p, "label", ""),
                        baseUrl,
                        str(p, "model", ""),
                        AiProtocol.parse(str(p, "protocol", ""))));
            }
        }
        if (out.isEmpty()) {
            String legacyUrl = str(o, "baseUrl", "");
            if (!legacyUrl.isBlank()) {
                // 旧配置只可能是 Responses——那时候还没有第二种协议
                out.add(new AiProvider(AiProvider.newId(), "", legacyUrl,
                        str(o, "model", ""), AiProtocol.RESPONSES));
            }
        }
        if (out.isEmpty()) {
            out.addAll(AiProvider.presets());
        }
        return out;
    }

    public void save(Path dir) {
        JsonObject o = new JsonObject();
        // 这一行是写给「不小心把 config 打进整合包」的人看的，也是写给未来改这个类的人看的
        o.addProperty("_comment", "No API key is stored here on purpose. "
                + "Keys live in ~/.gtshaders/credentials.json so that modpack exports cannot pick them up.");
        JsonArray arr = new JsonArray();
        for (AiProvider p : providers) {
            JsonObject po = new JsonObject();
            po.addProperty("id", p.id());
            po.addProperty("label", p.label());
            po.addProperty("baseUrl", p.baseUrl());
            po.addProperty("model", p.model());
            po.addProperty("protocol", p.protocol().name().toLowerCase(java.util.Locale.ROOT));
            arr.add(po);
        }
        o.add("providers", arr);
        o.addProperty("activeId", activeId);
        o.addProperty("temperature", temperature);
        o.addProperty("maxOutputTokens", maxOutputTokens);
        o.addProperty("stream", stream);
        o.addProperty("repairRounds", repairRounds);
        o.addProperty("timeoutSeconds", timeoutSeconds);
        try {
            Files.createDirectories(dir);
            Files.writeString(dir.resolve(FILE),
                    new GsonBuilder().setPrettyPrinting().create().toJson(o) + "\n",
                    StandardCharsets.UTF_8);
        } catch (IOException e) {
            GTShaders.LOGGER.warn("无法保存 GTShaders AI 配置: {}", e.toString());
        }
    }

    private static String str(JsonObject o, String k, String def) {
        return o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsString() : def;
    }

    private static double num(JsonObject o, String k, double def) {
        try {
            return o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsDouble() : def;
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static boolean bool(JsonObject o, String k, boolean def) {
        try {
            return o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsBoolean() : def;
        } catch (Exception e) {
            return def;
        }
    }
}
