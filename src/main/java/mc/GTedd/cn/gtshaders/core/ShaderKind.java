package mc.GTedd.cn.gtshaders.core;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.jspecify.annotations.Nullable;
import mc.GTedd.cn.gtshaders.i18n.GtLang;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 可编辑的原版着色器种类。
 *
 * <h2>为什么是一张表而不是一堆 Java 常量</h2>
 *
 * <p>原版有三十多个核心着色器，每个都有自己的文件路径、阶段、可用属性，
 * 而且路径还会随版本改名（26.3 就把 {@code rendertype_clouds} 改成了 {@code clouds}）。
 * 写成 JSON 之后，加一种或改一个路径不用重新编译 Java，
 * 而且这张表同时是编辑器里种类说明的唯一来源。
 *
 * <h2>这里<b>没有</b>任何 Mojang 的源码</h2>
 *
 * <p>只有我们自己的元数据：分类、显示名、钩子策略。
 * 生成时要用的原版模板是<b>运行时从游戏里取</b>的
 * （从 {@code ResourceManager} 读），不随 jar 分发——
 * Minecraft 的资产是专有的，打进 jar 会有版权问题。
 *
 * <h2>核心着色器和后处理的一个根本差别</h2>
 *
 * <p>后处理的可调参数是 uniform，改一下不用重编译；
 * 核心着色器<b>不能新增 uniform 块</b>——块必须由 Java 侧的渲染管线声明，
 * 资源包加不了（见 <a href="https://mojira.dev/MC-307387">MC-307387</a>）。
 * 所以这里的参数只能编译成 {@code const}，改参数 = 重新生成 + 重新编译。
 */
public final class ShaderKind {

    private static final String INDEX_PATH = "/assets/gtshaders/vanilla/kinds.json";

    /**
     * @param id           种类 id
     * @param category     分类 id
     * @param names        语言代码 → 显示名
     * @param notes        语言代码 → 一句话说明
     * @param path         资源路径，如 {@code core/entity}
     * @param stages       有哪些阶段：vsh / fsh
     * @param vertexHook   是否支持自动注入顶点钩子
     * @param fragmentHook 是否支持自动注入片段钩子
     */
    public record Entry(String id, String category, Map<String, String> names,
                        Map<String, String> notes, String path,
                        List<String> stages, boolean vertexHook, boolean fragmentHook) {

        public boolean hasVertexStage() {
            return stages.contains("vsh");
        }

        public String name(String lang) {
            String v = names.get(lang);
            if (v == null) {
                v = names.get(GtLang.FALLBACK_LANG);
            }
            return v == null ? id : v;
        }

        public String displayName() {
            return name(GtLang.contentLang());
        }

        public String note(String lang) {
            String v = notes.get(lang);
            if (v == null) {
                v = notes.get(GtLang.FALLBACK_LANG);
            }
            return v == null ? "" : v;
        }

        public String displayNote() {
            return note(GtLang.contentLang());
        }
    }

    private static List<Entry> entries;
    private static List<String> categoryOrder;
    private static Map<String, Map<String, String>> categoryNames;

    private ShaderKind() {
    }

    public static synchronized List<Entry> all() {
        load();
        return entries;
    }

    public static synchronized List<String> categories() {
        load();
        return categoryOrder;
    }

    public static synchronized List<Entry> byCategory(String category) {
        load();
        List<Entry> out = new ArrayList<>();
        for (Entry e : entries) {
            if (e.category().equals(category)) {
                out.add(e);
            }
        }
        return out;
    }

    public static synchronized String categoryName(String category, String lang) {
        load();
        Map<String, String> names = categoryNames.get(category);
        if (names == null) {
            return category;
        }
        String v = names.get(lang);
        if (v == null) {
            v = names.get(GtLang.FALLBACK_LANG);
        }
        return v == null ? category : v;
    }

    public static String categoryDisplayName(String category) {
        return categoryName(category, GtLang.contentLang());
    }

    public static @Nullable Entry find(String id) {
        for (Entry e : all()) {
            if (e.id().equals(id)) {
                return e;
            }
        }
        return null;
    }

    private static void load() {
        if (entries != null) {
            return;
        }
        List<Entry> loaded = new ArrayList<>();
        List<String> order = new ArrayList<>();
        Map<String, Map<String, String>> cats = new LinkedHashMap<>();

        try (InputStream in = ShaderKind.class.getResourceAsStream(INDEX_PATH)) {
            if (in != null) {
                try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                    JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
                    for (JsonElement el : root.getAsJsonArray("categories")) {
                        JsonObject o = el.getAsJsonObject();
                        String id = o.get("id").getAsString();
                        order.add(id);
                        cats.put(id, langMap(o, "zh_cn", "en_us"));
                    }
                    for (JsonElement el : root.getAsJsonArray("kinds")) {
                        loaded.add(parse(el.getAsJsonObject()));
                    }
                }
            }
        } catch (Exception e) {
            // 表坏掉不该让编辑器打不开：核心着色器那一栏空着就是了
            loaded.clear();
        }
        loaded.sort((a, b) -> Integer.compare(order.indexOf(a.category()), order.indexOf(b.category())));

        entries = List.copyOf(loaded);
        categoryOrder = List.copyOf(order);
        categoryNames = Map.copyOf(cats);
    }

    private static Entry parse(JsonObject o) {
        String id = o.get("id").getAsString();
        if (!o.has("path")) {
            throw new IllegalStateException(id + " 缺少 path");
        }
        String path = o.get("path").getAsString();

        List<String> stages = new ArrayList<>();
        for (JsonElement s : o.getAsJsonArray("stages")) {
            stages.add(s.getAsString());
        }

        return new Entry(id, o.get("category").getAsString(),
                langMap(o, "zh_cn", "en_us"),
                langMap(o, "note_zh", "note_en"),
                path, List.copyOf(stages),
                o.has("vertexHook") && o.get("vertexHook").getAsBoolean(),
                o.has("fragmentHook") && o.get("fragmentHook").getAsBoolean());
    }

    /** 把 {@code zh_cn} / {@code note_zh} 这类字段收成「语言代码 → 文本」。 */
    private static Map<String, String> langMap(JsonObject o, String zhKey, String enKey) {
        Map<String, String> map = new LinkedHashMap<>();
        if (o.has(zhKey)) {
            map.put("zh_cn", o.get(zhKey).getAsString());
        }
        if (o.has(enKey)) {
            map.put("en_us", o.get(enKey).getAsString());
        }
        return map;
    }
}
