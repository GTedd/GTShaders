package mc.GTedd.cn.gtshaders.ai;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import mc.GTedd.cn.gtshaders.GTShaders;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * API Key 的本地保管。<b>这个类的全部存在理由是「别把玩家的钱包泄露出去」。</b>
 *
 * <h2>为什么不放在 config/gtshaders/ 里</h2>
 *
 * <p>那是本 mod 其它所有配置的家，唯独 key 不能进去。整合包的打包范围就是实例目录：
 * CurseForge、Modrinth、MultiMC 导出时 {@code config/} <b>默认整个打进压缩包</b>——
 * 这正是「作者做完整合包上传，几天后 key 被人跑光」的经典路径。玩家不会意识到
 * 自己刚导出的整合包里夹着一把 sk- 开头的钥匙，等发现时账单已经产生了。
 *
 * <p>所以 key 落在 <b>{@code ~/.gtshaders/credentials.json}</b>——用户主目录，
 * 在 {@code .minecraft} 之外。整合包打包器再怎么递归也够不着它。
 * 附带的好处是同一台机器上多个实例共用一把 key，换实例不用重填。
 *
 * <h2>为什么按 host 分开存</h2>
 *
 * <p>玩家会在 DeepSeek 和自建/中转之间来回切。存成单值的话，切一次就得重填一次，
 * 于是玩家会把 key 记在别的地方——那才是真正的泄露源头。按 host 存，切回去就还在。
 *
 * <h2>关于「加密」</h2>
 *
 * <p>这里<b>刻意不做加密</b>。本地加密需要一把本地的密钥去解，而那把密钥同样只能放在
 * 本地——结果只是把问题挪了个地方，还让人误以为文件是安全的。真正起作用的是三件事：
 * 放在打包够不着的位置、把文件权限收到只有本人可读、以及任何日志和界面上都不出现明文。
 * 这三件这个类都做了，加密不做。
 */
public final class AiCredentials {

    /** 内存缓存。避免每次发请求都读盘。 */
    private static final Map<String, String> CACHE = new TreeMap<>();
    private static boolean loaded;

    private AiCredentials() {
    }

    /** 默认位置：{@code ~/.gtshaders/credentials.json}，刻意在 .minecraft 之外。 */
    public static Path defaultFile() {
        return Paths.get(System.getProperty("user.home", "."), ".gtshaders", "credentials.json");
    }

    /**
     * 从 baseUrl 取出用作索引的 host。
     *
     * <p>解析失败时回退成整串小写——宁可索引得笨一点，也不能因为一个畸形 URL 就把
     * 玩家已经填好的 key 弄丢。
     */
    public static String hostOf(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            return "";
        }
        try {
            String host = URI.create(baseUrl.trim()).getHost();
            if (host != null && !host.isBlank()) {
                return host.toLowerCase(Locale.ROOT);
            }
        } catch (IllegalArgumentException ignored) {
            // 落到下面的回退
        }
        return baseUrl.trim().toLowerCase(Locale.ROOT);
    }

    /** 取某个 host 的 key，没有则返回空串。<b>返回值绝不可以进日志。</b> */
    public static synchronized String get(String baseUrl) {
        ensureLoaded();
        return CACHE.getOrDefault(hostOf(baseUrl), "");
    }

    public static synchronized boolean has(String baseUrl) {
        return !get(baseUrl).isEmpty();
    }

    /** 存入并立即落盘。传空串等同于删除这一条。 */
    public static synchronized void put(String baseUrl, String key) {
        ensureLoaded();
        String host = hostOf(baseUrl);
        String trimmed = key == null ? "" : key.trim();
        if (trimmed.isEmpty()) {
            CACHE.remove(host);
        } else {
            CACHE.put(host, trimmed);
        }
        write(defaultFile(), CACHE);
    }

    /** 清掉全部 key 并删除文件。给「我要把这台机器交出去」用。 */
    public static synchronized void forgetAll() {
        CACHE.clear();
        loaded = true;
        try {
            Files.deleteIfExists(defaultFile());
        } catch (IOException e) {
            GTShaders.LOGGER.warn("无法删除 GTShaders 凭据文件: {}", e.getClass().getSimpleName());
        }
    }

    private static void ensureLoaded() {
        if (!loaded) {
            CACHE.putAll(read(defaultFile()));
            loaded = true;
        }
    }

    /** 测试用：把缓存状态清干净，下次访问重新读盘。 */
    static synchronized void resetCacheForTest() {
        CACHE.clear();
        loaded = false;
    }

    // ------------------------------------------------------------ 读写

    /** 读。任何异常都只警告不抛：读不到 key 的后果是「要求玩家重填」，不该是崩溃。 */
    static Map<String, String> read(Path file) {
        Map<String, String> out = new TreeMap<>();
        if (!Files.isRegularFile(file)) {
            return out;
        }
        try {
            JsonObject root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8))
                    .getAsJsonObject();
            JsonObject keys = root.has("keys") && root.get("keys").isJsonObject()
                    ? root.getAsJsonObject("keys")
                    : root;
            for (String host : keys.keySet()) {
                var v = keys.get(host);
                if (v != null && v.isJsonPrimitive()) {
                    String s = v.getAsString().trim();
                    if (!s.isEmpty()) {
                        out.put(host, s);
                    }
                }
            }
        } catch (Exception e) {
            // 刻意只打异常类型名，不打 message：解析失败的 message 里常常带着原文片段
            GTShaders.LOGGER.warn("GTShaders 凭据文件无法解析（{}），当作空处理",
                    e.getClass().getSimpleName());
        }
        return out;
    }

    /**
     * 写。<b>先建空文件、收紧权限、再写内容</b>——顺序不能反。
     *
     * <p>反过来的话，明文会有一小段时间躺在一个默认权限（同机其他用户可读）的文件里。
     * 这个窗口只有几毫秒，但它是无谓的：调换两步的顺序不花任何代价。
     */
    static void write(Path file, Map<String, String> keys) {
        try {
            Path dir = file.getParent();
            if (dir != null) {
                Files.createDirectories(dir);
            }
            if (!Files.exists(file)) {
                Files.createFile(file);
            }
            restrictToOwner(file);

            JsonObject keyObj = new JsonObject();
            for (Map.Entry<String, String> e : keys.entrySet()) {
                keyObj.addProperty(e.getKey(), e.getValue());
            }
            JsonObject root = new JsonObject();
            root.addProperty("_comment",
                    "GTShaders API keys. Keep this file private; never ship it inside a modpack.");
            root.add("keys", keyObj);
            Files.writeString(file, root + "\n", StandardCharsets.UTF_8);
        } catch (IOException e) {
            GTShaders.LOGGER.warn("无法保存 GTShaders 凭据: {}", e.getClass().getSimpleName());
        }
    }

    /**
     * 把文件权限收到「只有本人可读写」。
     *
     * <p>两套文件系统各走各的路：POSIX 直接 {@code rw-------}；Windows 走 ACL，
     * 把继承来的条目<b>整个换掉</b>只留属主一条——只加不减的话，"Users" 那条继承权限
     * 还在，同机其他账户照样读得到。
     *
     * <p>失败只警告：某些网络盘、外置存储不支持任何一种权限模型，那时唯一的选择是
     * 让功能继续可用并告诉玩家一声，而不是拒绝保存。
     */
    private static void restrictToOwner(Path file) {
        PosixFileAttributeView posix = Files.getFileAttributeView(file, PosixFileAttributeView.class);
        if (posix != null) {
            try {
                posix.setPermissions(PosixFilePermissions.fromString("rw-------"));
            } catch (IOException | UnsupportedOperationException e) {
                GTShaders.LOGGER.warn("无法收紧凭据文件权限（POSIX）: {}", e.getClass().getSimpleName());
            }
            return;
        }
        AclFileAttributeView acl = Files.getFileAttributeView(file, AclFileAttributeView.class);
        if (acl == null) {
            return;
        }
        try {
            UserPrincipal owner = Files.getOwner(file);
            AclEntry only = AclEntry.newBuilder()
                    .setType(AclEntryType.ALLOW)
                    .setPrincipal(owner)
                    .setPermissions(EnumSet.allOf(AclEntryPermission.class))
                    .build();
            acl.setAcl(List.of(only));
        } catch (IOException | UnsupportedOperationException | SecurityException e) {
            GTShaders.LOGGER.warn("无法收紧凭据文件权限（ACL）: {}", e.getClass().getSimpleName());
        }
    }

    // ------------------------------------------------------------ 展示

    /**
     * 给界面看的掩码形式。
     *
     * <p>只留前 3 后 4：前缀让玩家认出这是哪家的 key，后 4 位让他确认自己填的是哪一把，
     * 中间一律星号。短到掩不住的（少于 12 位）整串打星——那种长度的串本来也不像真 key，
     * 但万一是，露出来的就是全部。
     */
    public static String mask(String key) {
        if (key == null || key.isBlank()) {
            return "";
        }
        String k = key.trim();
        if (k.length() < 12) {
            return "*".repeat(k.length());
        }
        return k.substring(0, 3) + "*".repeat(Math.min(8, k.length() - 7)) + k.substring(k.length() - 4);
    }
}
