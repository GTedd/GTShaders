package mc.GTedd.cn.gtshaders.runtime;

import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import org.jspecify.annotations.Nullable;
import mc.GTedd.cn.gtshaders.GTShaders;
import mc.GTedd.cn.gtshaders.core.GtProfile;
import mc.GTedd.cn.gtshaders.core.ShaderKind;
import mc.GTedd.cn.gtshaders.workspace.SafePaths;
import mc.GTedd.cn.gtshaders.workspace.Workspace;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 取原版核心着色器的源码，当作注入的模板。
 *
 * <h2>为什么不把模板打进 jar</h2>
 *
 * <p>Minecraft 的资产是<b>专有</b>的，随 mod 分发原版着色器源码有版权问题。
 * 好在完全不需要：正在跑的游戏里就有一份，直接从 {@code ResourceManager} 读即可。
 *
 * <h2>一个必须说清楚的限制</h2>
 *
 * <p>游戏里只有<b>它自己那个版本</b>的模板。所以：
 * <ul>
 *   <li>导出<b>当前运行版本</b>的核心着色器包 —— 开箱即用；</li>
 *   <li>导出<b>别的版本</b>的 —— 需要你把那个版本的原版资产放到
 *       {@code config/gtshaders/vanilla/&lt;版本&gt;/} 下。</li>
 * </ul>
 *
 * <p>这和后处理不一样：后处理的产物是我们从零生成的，不依赖原版源码，任何版本都能导。
 * 核心着色器是「在原版基础上改」，没有那个版本的原版就无从改起——这是事实，不是没做完。
 *
 * <p>目前只支持 26.3 一个 profile，所以第二条实际用不上；它留着是为了下一个大版本
 * ——那时用当前这个版本的游戏导出下一版的包，走的就是这条路。
 *
 * <p>目录布局与 client.jar 里的 {@code assets/minecraft/shaders/} 一致，
 * 解出来拷过去就能用：
 * <pre>
 *   config/gtshaders/vanilla/26.3/core/entity.vsh
 *   config/gtshaders/vanilla/26.3/core/entity.fsh
 * </pre>
 */
public final class VanillaSource {

    /** 缓存：一次资源重载之内，同一个模板不必反复问。 */
    private static final Map<String, String> CACHE = new HashMap<>();

    /**
     * 重入保护。
     *
     * <p>预览时我们会接管 {@code minecraft:core/entity} 的源码；如果这时再去问
     * {@code getShader} 要模板，拿回来的就是<b>我们自己生成的那份</b>，
     * 于是下一次生成会在上一次的产物上再注入一遍，越滚越大。
     * 取模板期间把这个标志打开，mixin 见到就放行给原版。
     */
    private static boolean bypassOverride;

    private VanillaSource() {
    }

    public static boolean isBypassing() {
        return bypassOverride;
    }

    /**
     * 正在跑的是哪个 profile。
     *
     * <p>本 mod 只支持 26.3，所以现在恒等于 {@link GtProfile#MC_26_3}。
     * 方法保留着不是冗余：{@link #template} 靠「目标 profile 是不是当前运行版本」
     * 来决定走游戏里的资产还是走磁盘上的外部资产，下一个大版本一到，
     * 这里就又会分叉，而调用方一个字都不用改。
     */
    public static GtProfile runtimeProfile() {
        return GtProfile.MC_26_3;
    }

    /**
     * 取某个种类某个阶段的原版源码。
     *
     * @param profile 目标版本。等于当前运行版本时走游戏，否则走外部资产目录
     * @return 源码；取不到时返回 null（调用方应当据此提示用户）
     */
    public static @Nullable String template(ShaderKind.Entry kind, GtProfile profile, String stage) {
        String path = kind.path();
        String key = profile.name() + "|" + path + "|" + stage;
        String cached = CACHE.get(key);
        if (cached != null) {
            return cached;
        }

        // 目标版本就是正在跑的版本时，游戏里那份是最权威的（还会带上玩家启用的其他资源包的改动）；
        // 否则只能靠用户放进来的那个版本的资产
        String source = profile == runtimeProfile() ? fromGame(path, stage) : fromDisk(profile, path, stage);
        if (source == null) {
            source = profile == runtimeProfile() ? fromDisk(profile, path, stage) : fromGame(path, stage);
        }
        if (source != null) {
            CACHE.put(key, source);
        }
        return source;
    }

    /**
     * 当前正在跑的这个版本，直接从资源系统读。
     *
     * <p>26.2 时这里问的是 {@code ShaderManager.getShader}，26.3 把那个方法删掉了
     * （源码改由 {@code ShaderManager$Configs} 这个 {@code ShaderSource} 提供，
     * 且只在编译管线时才被调到）。改读 {@code ResourceManager} 反而更好：
     * <ul>
     *   <li>是公开 API，不依赖任何 mixin；</li>
     *   <li>同样尊重玩家启用的其他资源包——资源系统本来就是按包顺序合并的；</li>
     *   <li>绕开了我们自己的注入点，所以理论上不再需要重入保护。
     *       {@link #bypassOverride} 仍然保留并照常设置：它很便宜，而万一将来又改回
     *       走注入点的路径，少了它就是「模板越滚越大」这种极难查的问题。</li>
     * </ul>
     */
    private static @Nullable String fromGame(String path, String stage) {
        Minecraft client = Minecraft.getInstance();
        if (client == null) {
            return null;
        }
        ResourceManager resources = client.getResourceManager();
        if (resources == null) {
            return null;
        }
        Identifier file = Identifier.fromNamespaceAndPath("minecraft", "shaders/" + path + "." + stage);
        bypassOverride = true;
        try {
            Optional<Resource> resource = resources.getResource(file);
            if (resource.isEmpty()) {
                return null;
            }
            try (InputStream in = resource.get().open()) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
        } catch (Exception e) {
            GTShaders.LOGGER.warn("取原版着色器失败：{}.{}", path, stage, e);
            return null;
        } finally {
            bypassOverride = false;
        }
    }

    /** 别的版本，从用户放进来的资产目录读。 */
    private static @Nullable String fromDisk(GtProfile profile, String path, String stage) {
        Path file = SafePaths.resolveOrNull(vanillaDir(profile), path + "." + stage);
        if (file == null) {
            return null;
        }
        try {
            return Files.exists(file) ? Files.readString(file, StandardCharsets.UTF_8) : null;
        } catch (Exception e) {
            GTShaders.LOGGER.warn("读取外部原版资产失败：{}", file, e);
            return null;
        }
    }

    /** 某个版本的原版资产该放哪。 */
    public static Path vanillaDir(GtProfile profile) {
        return Workspace.rootDir().resolve("vanilla").resolve(profile.display());
    }

    /** 该版本的原版资产是否已经就位（用于在界面上提示）。 */
    public static boolean hasAssets(GtProfile profile) {
        return profile == runtimeProfile() || Files.isDirectory(vanillaDir(profile).resolve("core"));
    }

    /** 资源重载后模板可能变了（比如玩家启用了别的资源包），整体丢弃缓存。 */
    public static void invalidate() {
        CACHE.clear();
    }
}
