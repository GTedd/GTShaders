package mc.GTedd.cn.gtshaders.i18n;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 多语言。
 *
 * <p><b>单一真相来源</b>：内置翻译只存在于 jar 里的
 * {@code assets/gtshaders/lang/<lang>.json}，本类直接用类加载器读取，不再另外维护一份
 * Java 里的映射表。之前那种「json 一份、Java 常量一份」的写法必然会漂移——
 * 加一条文案要改两处，漏掉一处就在界面上冒出原始 key。
 *
 * <p>直接读 classpath 而不是走 Minecraft 的资源系统，还顺带解决了时机问题：
 * mod 初始化早于第一次资源重载，走资源系统的话这段时间取到的全是 key。
 *
 * <p>查找顺序：
 * <ol>
 *   <li>外部语言包 {@code config/gtshaders/lang/<lang>.json} —— 用户自己加的，优先级最高</li>
 *   <li>jar 内置 {@code <lang>.json}</li>
 *   <li>繁体、港台、文言这些中文变体再查 {@code zh_cn}——读简体比读英文顺得多</li>
 *   <li>jar 内置 {@code en_us.json}</li>
 *   <li>都没有则原样返回 key，这样界面上一眼能看出缺了哪条</li>
 * </ol>
 */
public final class GtLang {

    public static final String FALLBACK_LANG = "en_us";
    public static final String CHINESE_LANG = "zh_cn";
    private static final String BUILTIN_PATH = "/assets/gtshaders/lang/";

    /** jar 内置翻译，按语言缓存。 */
    private static final Map<String, Map<String, String>> BUILTIN = new HashMap<>();
    /** 外部语言包，优先级最高。 */
    private static final Map<String, Map<String, String>> EXTERNAL = new HashMap<>();
    /** 已确认 jar 里不存在的语言，避免每次都去碰一次 IO。 */
    private static final Set<String> MISSING_BUILTIN = new LinkedHashSet<>();

    private static String currentLang = FALLBACK_LANG;
    private static java.util.function.Consumer<String> warnSink = System.err::println;

    private GtLang() {
    }

    public static void setWarnSink(java.util.function.Consumer<String> sink) {
        if (sink != null) {
            warnSink = sink;
        }
    }

    /**
     * 切换语言。会顺带把该语言的内置翻译加载进来；jar 里没有这门语言也不算错误，
     * 它可能完全由外部语言包提供——用户新增一门语言正是这个场景。
     */
    public static void setCurrentLang(String lang) {
        String normalized = normalize(lang);
        currentLang = normalized;
        loadBuiltin(normalized);
        loadBuiltin(FALLBACK_LANG);
    }

    public static String currentLang() {
        return currentLang;
    }

    /**
     * 查效果名、参数名、源码说明这类「内容」时用的语言。
     *
     * <p>内容只写了中英两份，界面文案却可以由外部语言包补任意语言。两者分开：
     * 玩家用 {@code zh_tw} 时界面先找 {@code zh_tw.json}，内容则直接取简体那份。
     */
    public static String contentLang() {
        return isChinese(currentLang) ? CHINESE_LANG : currentLang;
    }

    /** {@code zh_cn} / {@code zh_tw} / {@code zh_hk} / {@code lzh}（文言）都算中文。 */
    public static boolean isChinese(String lang) {
        String n = normalize(lang);
        return n.equals("zh") || n.startsWith("zh_") || n.equals("lzh");
    }

    private static String normalize(String lang) {
        if (lang == null || lang.isBlank()) {
            return FALLBACK_LANG;
        }
        return lang.trim().toLowerCase(Locale.ROOT).replace('-', '_');
    }

    private static void loadBuiltin(String lang) {
        if (BUILTIN.containsKey(lang) || MISSING_BUILTIN.contains(lang)) {
            return;
        }
        try (InputStream in = GtLang.class.getResourceAsStream(BUILTIN_PATH + lang + ".json")) {
            if (in == null) {
                MISSING_BUILTIN.add(lang);
                return;
            }
            try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                Map<String, String> map = parseFlatJson(JsonParser.parseReader(reader));
                BUILTIN.put(lang, map);
            }
        } catch (IOException | RuntimeException e) {
            MISSING_BUILTIN.add(lang);
            warn("内置语言文件读取失败：" + lang + " (" + e.getMessage() + ")");
        }
    }

    private static Map<String, String> parseFlatJson(JsonElement parsed) {
        Map<String, String> map = new HashMap<>();
        if (parsed == null || !parsed.isJsonObject()) {
            return map;
        }
        for (Map.Entry<String, JsonElement> e : parsed.getAsJsonObject().entrySet()) {
            if (e.getValue().isJsonPrimitive()) {
                map.put(e.getKey(), e.getValue().getAsString());
            }
        }
        return map;
    }

    /**
     * 扫描并加载外部语言目录。整体重载，方便用户改完 json 直接在编辑器里点一下刷新。
     *
     * @return 载入的语言代码列表，供 UI 展示
     */
    public static List<String> reloadExternal(Path langDir) {
        EXTERNAL.clear();
        List<String> loaded = new ArrayList<>();
        if (langDir == null || !Files.isDirectory(langDir)) {
            return loaded;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(langDir, "*.json")) {
            for (Path file : stream) {
                String name = file.getFileName().toString();
                String lang = normalize(name.substring(0, name.length() - ".json".length()));
                try {
                    String text = Files.readString(file, StandardCharsets.UTF_8);
                    Map<String, String> map = parseFlatJson(JsonParser.parseString(text));
                    if (!map.isEmpty()) {
                        EXTERNAL.put(lang, map);
                        loaded.add(lang);
                    }
                } catch (IOException | RuntimeException ex) {
                    // 单个语言文件坏掉不该拖垮整个加载流程
                    warn("外部语言文件加载失败：" + file.getFileName() + " (" + ex.getMessage() + ")");
                }
            }
        } catch (IOException ex) {
            warn("无法读取语言目录：" + langDir + " (" + ex.getMessage() + ")");
        }
        loaded.sort(String::compareTo);
        return loaded;
    }

    /** 当前可用的全部语言：jar 内置已加载的 + 外部提供的。 */
    public static Set<String> availableLanguages() {
        Set<String> all = new LinkedHashSet<>(BUILTIN.keySet());
        all.addAll(EXTERNAL.keySet());
        return all;
    }

    /** 翻译；找不到就返回 key 本身，让缺失一眼可见。 */
    public static String get(String key) {
        String v = getOrNull(key);
        return v != null ? v : key;
    }

    /** 带参数的翻译，占位符用 {@code %s} / {@code %d}，与原版语言文件一致。 */
    public static String get(String key, Object... args) {
        String pattern = get(key);
        if (args == null || args.length == 0) {
            return pattern;
        }
        try {
            return String.format(pattern, args);
        } catch (RuntimeException e) {
            // 语言文件里占位符写错不该让界面崩掉，退回未格式化的原串
            return pattern;
        }
    }

    /** 只查不回退到 key 本身，供参数标签判断某个 key 是否真的存在。 */
    public static String getOrNull(String key) {
        String v = lookup(key, currentLang);
        if (v == null && isChinese(currentLang) && !currentLang.equals(CHINESE_LANG)) {
            v = lookup(key, CHINESE_LANG);
        }
        return v != null ? v : lookup(key, FALLBACK_LANG);
    }

    public static boolean has(String key) {
        return getOrNull(key) != null;
    }

    private static String lookup(String key, String lang) {
        Map<String, String> ext = EXTERNAL.get(lang);
        if (ext != null) {
            String v = ext.get(key);
            if (v != null) {
                return v;
            }
        }
        loadBuiltin(lang);
        Map<String, String> builtin = BUILTIN.get(lang);
        return builtin == null ? null : builtin.get(key);
    }

    private static void warn(String msg) {
        warnSink.accept("[GTShaders/i18n] " + msg);
    }
}
