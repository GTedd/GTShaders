package mc.GTedd.cn.gtshaders.core;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Properties;

/**
 * 编译目标的 Minecraft 版本号，以及该版本的资源包 / 数据包格式号。
 *
 * <p>真源是 {@code gradle.properties}：{@code processResources} 把那三个值展开进
 * {@code gtshaders-target.properties}，这里运行时读回来。游戏里和单元测试里读的是同一份资源，
 * 所以不依赖 Minecraft 运行时——{@code SharedConstants} 在单元测试里拿不到，
 * 而小地图导出恰恰要在单元测试里跑。
 *
 * <p>为什么要有这个类：小地图导出曾把版本号和数据包格式号抄在十几处。跟到 26.3-rc-2 时
 * 数据包格式号已经从 120 涨到 121（pre-3 涨的），抄下来的旧值会让导出的数据包在游戏里显示「不兼容」。
 * 这和 {@code VanillaAssets} 当初要解决的是同一类问题：漏改一处，没有任何报错。
 *
 * <p>{@link GtProfile#minPackFormat()} 那个 97 <b>不</b>从这里取：它是「新写法从哪一版起成立」的
 * 下界，是个判断，不是当前版本的事实。
 */
public final class TargetVersion {

    private static final String RESOURCE = "/gtshaders-target.properties";
    private static final Properties PROPS = load();

    private TargetVersion() {
    }

    /** 官方版本 id，例如 {@code 26.3}（预发布带连字符，如 {@code 26.3-rc-2}），和 Mojang 的清单一致。 */
    public static String minecraft() {
        return get("minecraft_version");
    }

    /** 资源包格式号 {@code [major, minor]}，直接可以写进 {@code pack.mcmeta} 的 min/max_format。 */
    public static List<Integer> resourcePackFormat() {
        return format("resource_pack_format");
    }

    /** 数据包格式号 {@code [major, minor]}。 */
    public static List<Integer> dataPackFormat() {
        return format("data_pack_format");
    }

    private static List<Integer> format(String key) {
        String v = get(key);
        String[] parts = v.split("\\.");
        if (parts.length != 2) {
            throw new IllegalStateException(RESOURCE + " 里的 " + key + " 应当是 major.minor，实际是 " + v);
        }
        return List.of(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]));
    }

    private static String get(String key) {
        String v = PROPS.getProperty(key);
        // 没展开的占位符原样留着 ${...}：说明资源没经过 processResources，比拿到空值更该早点炸
        if (v == null || v.isBlank() || v.contains("${")) {
            throw new IllegalStateException(RESOURCE + " 里的 " + key + " 无效（" + v + "）——资源没有经过 processResources 展开？");
        }
        return v.trim();
    }

    private static Properties load() {
        Properties p = new Properties();
        try (InputStream in = TargetVersion.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("缺少 " + RESOURCE + "，请重新构建 GTShaders");
            }
            p.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException(RESOURCE + " 读失败", e);
        }
        return p;
    }
}
