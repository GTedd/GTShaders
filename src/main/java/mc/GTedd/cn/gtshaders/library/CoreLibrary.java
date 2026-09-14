package mc.GTedd.cn.gtshaders.library;

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
 * 核心着色器的示例库。
 *
 * <p>和后处理的 {@link EffectLibrary} 是<b>两套</b>东西，不能合并：
 * 后处理效果是一段完整的 {@code main()}，核心着色器示例只是两个钩子函数，
 * 而且必须绑定到某一个 {@link mc.GTedd.cn.gtshaders.core.ShaderKind} 上才有意义——
 * 一段写给 lightmap 的代码放进 entity 里根本编译不过。
 */
public final class CoreLibrary {

    private static final String INDEX_PATH = "/assets/gtshaders/corelib/index.json";
    private static final String SOURCE_DIR = "/assets/gtshaders/corelib/";

    /**
     * @param id     示例 id，同时也是文件名
     * @param kindId 它写给哪个核心着色器种类
     */
    public record Entry(String id, String kindId, Map<String, String> names) {
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
    }

    private static List<Entry> entries;

    private CoreLibrary() {
    }

    public static synchronized List<Entry> all() {
        load();
        return entries;
    }

    /** 某个种类下的全部示例。 */
    public static List<Entry> forKind(String kindId) {
        List<Entry> out = new ArrayList<>();
        for (Entry e : all()) {
            if (e.kindId().equals(kindId)) {
                out.add(e);
            }
        }
        return out;
    }

    public static @Nullable Entry find(String id) {
        for (Entry e : all()) {
            if (e.id().equals(id)) {
                return e;
            }
        }
        return null;
    }

    /** 读一个示例的钩子源码。 */
    public static @Nullable String loadSource(String id) {
        try (InputStream in = CoreLibrary.class.getResourceAsStream(SOURCE_DIR + id + ".fsh")) {
            return in == null ? null : new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    private static void load() {
        if (entries != null) {
            return;
        }
        List<Entry> loaded = new ArrayList<>();
        try (InputStream in = CoreLibrary.class.getResourceAsStream(INDEX_PATH)) {
            if (in != null) {
                try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                    JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
                    for (JsonElement el : root.getAsJsonArray("effects")) {
                        JsonObject o = el.getAsJsonObject();
                        Map<String, String> names = new LinkedHashMap<>();
                        if (o.has("zh_cn")) {
                            names.put("zh_cn", o.get("zh_cn").getAsString());
                        }
                        if (o.has("en_us")) {
                            names.put("en_us", o.get("en_us").getAsString());
                        }
                        loaded.add(new Entry(o.get("id").getAsString(),
                                o.get("kind").getAsString(), names));
                    }
                }
            }
        } catch (Exception e) {
            // 索引坏掉不该让编辑器打不开
            loaded.clear();
        }
        entries = List.copyOf(loaded);
    }
}
