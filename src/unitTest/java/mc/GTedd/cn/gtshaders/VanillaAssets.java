package mc.GTedd.cn.gtshaders;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * 原版资产在本机的落点，以及它们属于哪个版本。
 *
 * <h2>为什么要有这个类</h2>
 *
 * <p>版本号原本硬编码在五个地方（两个测试工具、这里的两条路径、两个 python 脚本），
 * 跟一次版本（26.2 → 26.3-pre-1 → pre-2 → rc-2）就要挨个改一遍。漏掉一个的表现是
 * <b>测试静默跳过</b>——目录名对不上，{@code assetsPresent()} 返回 false，一切"通过"。
 * 正好是最不该静默的那类失败：核对原版格式的那一半没跑，而报告上看不出来。
 *
 * <p>所以版本号只留一处：{@code gradle.properties} 的 {@code minecraft_version}，
 * 也就是真正决定编译目标的那一处。
 *
 * <p>原版资产是 Mojang 专有的，只在本机解包，<b>不入库</b>（{@code docs/vanilla/} 已在
 * {@code .gitignore} 里）。
 */
public final class VanillaAssets {

    private VanillaAssets() {
    }

    /** 官方版本 id，例如 {@code 26.3-rc-2}。带连字符，和 Mojang 的清单一致。 */
    public static String version() {
        Path props = Path.of("gradle.properties");
        if (!Files.isRegularFile(props)) {
            throw new IllegalStateException(
                    "读不到 gradle.properties（当前目录 " + Path.of("").toAbsolutePath()
                            + "）——这些工具要在工程根目录下跑");
        }
        Properties p = new Properties();
        try (var in = Files.newBufferedReader(props, StandardCharsets.UTF_8)) {
            p.load(in);
        } catch (IOException e) {
            throw new IllegalStateException("gradle.properties 读失败", e);
        }
        String v = p.getProperty("minecraft_version");
        if (v == null || v.isBlank()) {
            throw new IllegalStateException("gradle.properties 里没有 minecraft_version");
        }
        return v.trim();
    }

    /**
     * 解包好的原版着色器：{@code docs/vanilla/<版本>/shaders}。
     *
     * <p>把 client.jar 里的 {@code assets/minecraft/shaders/} 整个放进去即可。
     */
    public static Path shadersRoot() {
        return Path.of("docs", "vanilla", version(), "shaders");
    }

    /**
     * 原版 client.jar 根目录的 {@code version.json}：{@code docs/vanilla/<版本>/version.json}。
     *
     * <p>可选。放了的话 {@code TargetVersionTest} 会拿它的 {@code pack_version}
     * 核对 {@code gradle.properties} 里的两个包格式号。
     */
    public static Path versionJson() {
        return Path.of("docs", "vanilla", version(), "version.json");
    }

    /**
     * 本地解包脚本的输出目录：{@code %TEMP%/mcref/<版本>}（脚本不随仓库发布）。
     *
     * <p>和 {@link #shadersRoot()} 内容相同，只是那个脚本按临时目录组织。
     */
    public static Path mcrefRoot() {
        return Path.of(System.getProperty("java.io.tmpdir")).resolve("mcref").resolve(version());
    }
}
