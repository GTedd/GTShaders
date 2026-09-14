package mc.GTedd.cn.gtshaders.library;

import com.google.gson.JsonArray;
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
 * 内置效果库：几十个开箱即用的后处理效果，按分类组织。
 *
 * <h2>为什么索引是一份 json 而不是 Java 常量表</h2>
 *
 * <p>加一个效果只需要"丢一个 .fsh + 在 index.json 里加一行"，不用重新编译 Java。
 * 而且这份索引同时被三个地方读：编辑器菜单、{@code generateShaderLib} 生成器、单元测试。
 * 单一真相来源，三者不可能漂移——生成器还会主动核对索引与磁盘文件是否一一对应。
 *
 * <h2>为什么名字直接写在索引里而不走 lang key</h2>
 *
 * <p>效果名有几十条，全塞进 {@code lang/*.json} 会把语言文件淹掉，而它们和界面文案的
 * 生命周期完全不同——效果是内容，界面是外壳。索引里直接放 {@code zh_cn} / {@code en_us}
 * 两个字段，取名时按当前语言挑，缺了就退回英文。
 */
public final class EffectLibrary {

    private static final String INDEX_PATH = "/assets/gtshaders/library/index.json";
    private static final String SOURCE_DIR = "/assets/gtshaders/library/";

    /**
     * 一个效果。
     *
     * @param id       资源 id，同时也是文件名与导出的 post_effect id
     * @param category 分类 id
     * @param names    语言代码 → 显示名
     * @param outline   是不是实体轮廓效果。这类效果覆盖的是 {@code minecraft:entity_outline} 那条链，
     *                  加进工程时必须建成轮廓层——建成普通后处理层的话，它调用的
     *                  {@code gtMask()} / {@code SceneSampler} 全都不存在，一编译就报一堆未定义
     */
    public record Entry(String id, String category, Map<String, String> names, boolean outline) {
        /** 按语言取名；缺失时退回英文，再缺就用 id——永远不会显示成空白。 */
        public String name(String lang) {
            String v = names.get(lang);
            if (v == null) {
                v = names.get(GtLang.FALLBACK_LANG);
            }
            return v == null ? id : v;
        }

        /** 按当前界面语言取名。 */
        public String displayName() {
            return name(GtLang.contentLang());
        }
    }

    private static List<Entry> entries;
    private static Map<String, Map<String, String>> categoryNames;
    private static List<String> categoryOrder;

    private EffectLibrary() {
    }

    public static synchronized List<Entry> all() {
        load();
        return entries;
    }

    /** 分类 id，按索引里的声明顺序——菜单的分组顺序是设计过的，不能按字典序打乱。 */
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

    /**
     * 读一个效果的作者源码。
     *
     * @return 源码；id 不存在或读取失败时返回 null
     */
    public static @Nullable String loadSource(String id) {
        Entry entry = find(id);
        if (entry == null) {
            return null;
        }
        String path = SOURCE_DIR + entry.category() + "/" + entry.id() + ".fsh";
        try (InputStream in = EffectLibrary.class.getResourceAsStream(path)) {
            if (in == null) {
                return null;
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    private static void load() {
        if (entries != null) {
            return;
        }
        List<Entry> loaded = new ArrayList<>();
        Map<String, Map<String, String>> cats = new LinkedHashMap<>();
        List<String> order = new ArrayList<>();
        java.util.Set<String> outlineCategories = new java.util.HashSet<>();

        try (InputStream in = EffectLibrary.class.getResourceAsStream(INDEX_PATH)) {
            if (in != null) {
                try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                    JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
                    for (JsonElement el : root.getAsJsonArray("categories")) {
                        JsonObject o = el.getAsJsonObject();
                        String id = o.get("id").getAsString();
                        order.add(id);
                        cats.put(id, names(o));
                        if (o.has("outline") && o.get("outline").getAsBoolean()) {
                            outlineCategories.add(id);
                        }
                    }
                    JsonArray effects = root.getAsJsonArray("effects");
                    for (JsonElement el : effects) {
                        JsonObject o = el.getAsJsonObject();
                        String category = o.get("category").getAsString();
                        // 标在分类上而不是逐条标：轮廓与后处理是两条完全不同的落地路径，
                        // 一个分类不可能一半是这个一半是那个。标在条目上只会给出错留空间
                        boolean outline = outlineCategories.contains(category);
                        loaded.add(new Entry(o.get("id").getAsString(), category, names(o), outline));
                    }
                }
            }
        } catch (Exception e) {
            // 索引坏掉不该让编辑器打不开：库是锦上添花，空库只是菜单里少一栏
            loaded.clear();
        }
        // 按分类声明顺序排，分类内保持索引里的顺序
        loaded.sort((a, b) -> Integer.compare(order.indexOf(a.category()), order.indexOf(b.category())));

        entries = List.copyOf(loaded);
        categoryNames = Map.copyOf(cats);
        categoryOrder = List.copyOf(order);
    }

    private static Map<String, String> names(JsonObject o) {
        Map<String, String> map = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> e : o.entrySet()) {
            if (!e.getKey().equals("id") && !e.getKey().equals("category")
                    && e.getValue().isJsonPrimitive()) {
                map.put(e.getKey(), e.getValue().getAsString());
            }
        }
        return map;
    }
}
