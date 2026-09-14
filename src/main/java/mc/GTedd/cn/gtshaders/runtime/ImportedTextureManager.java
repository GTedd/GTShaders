package mc.GTedd.cn.gtshaders.runtime;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;
import mc.GTedd.cn.gtshaders.GTShaders;
import mc.GTedd.cn.gtshaders.workspace.SafePaths;
import mc.GTedd.cn.gtshaders.workspace.Workspace;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 自定义贴图导入：把用户拖入的 PNG 复制到 {@code config/gtshaders/imported_textures/}，
 * 并在运行时注册成 Minecraft TextureManager 可直接按 id 取到的动态贴图。
 *
 * <p>导入后的贴图 id 形如 {@code gtshaders:textures/imported/xxx.png}，
 * 可以直接作为 {@code @texture path=...} 使用，也能被导出器一起打进资源包。
 */
public final class ImportedTextureManager {

    private static final Map<Identifier, Path> REGISTERED = new HashMap<>();
    private static final Set<String> BUILTIN_REGISTERED = new HashSet<>();

    private ImportedTextureManager() {
    }

    public static Path dir() {
        return Workspace.importedTexturesDir();
    }

    /** 已注册的导入贴图（{@code gtshaders:imported/...}），按名字排序，供参数面板逐张切换。 */
    public static List<String> importedLocations() {
        return REGISTERED.keySet().stream().map(Identifier::toString).sorted().toList();
    }

    /** 把外部 PNG 导入为可预览、可导出的贴图，返回新的资源 id。 */
    public static Identifier importPng(Path file) throws IOException {
        if (file == null || !Files.isRegularFile(file)) {
            throw new IOException("not a file");
        }
        String fileName = sanitize(file.getFileName().toString());
        Path dest = SafePaths.resolveOrNull(dir(), fileName);
        if (dest == null) {
            throw new IOException("bad file name: " + fileName);
        }
        Files.createDirectories(dir());
        Files.copy(file, dest, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        String idPath = fileName.endsWith(".png") ? fileName.substring(0, fileName.length() - 4) : fileName;
        Identifier id = Identifier.fromNamespaceAndPath("gtshaders", "imported/" + idPath);
        register(id, dest);
        return id;
    }

    /** 把内置贴图也从 classpath 注册成动态贴图，避免 ResourceManager 找不到 mod 资源时显示紫黑块。 */
    public static void registerBuiltin(TextureManager textureManager, String location) {
        if (textureManager == null || location == null
                || !location.startsWith("gtshaders:textures/")
                || location.startsWith("gtshaders:textures/imported/")) {
            return;
        }
        if (!BUILTIN_REGISTERED.add(location)) {
            return;
        }
        String rel = location.substring("gtshaders:".length()); // killicon/foo
        String path = "textures/effect/" + rel + ".png";
        try (InputStream in = ImportedTextureManager.class.getResourceAsStream("/assets/gtshaders/" + path)) {
            if (in == null) {
                GTShaders.LOGGER.warn("内置贴图不在 classpath：{}", location);
                return;
            }
            NativeImage image = NativeImage.read(in);
            Identifier id = Identifier.parse(location);
            DynamicTexture texture = new DynamicTexture(() -> id.toString(), image);
            textureManager.register(id, texture);
            GTShaders.LOGGER.info("已注册内置贴图 {}", location);
        } catch (IOException | RuntimeException e) {
            GTShaders.LOGGER.warn("无法注册内置贴图 {}", location, e);
        }
    }

    /** 扫描导入目录，把尚未注册的 PNG 全部注册进 TextureManager。 */
    public static void registerAll(TextureManager textureManager) {
        if (textureManager == null) {
            return;
        }
        Path dir = dir();
        if (!Files.isDirectory(dir)) {
            return;
        }
        try (var stream = Files.list(dir)) {
            for (Path p : stream.toList()) {
                if (!Files.isRegularFile(p) || !p.getFileName().toString().toLowerCase(java.util.Locale.ROOT).endsWith(".png")) {
                    continue;
                }
                String fileName = p.getFileName().toString();
                String idPath = fileName.endsWith(".png") ? fileName.substring(0, fileName.length() - 4) : fileName;
                Identifier id = Identifier.fromNamespaceAndPath("gtshaders", "imported/" + idPath);
                if (!REGISTERED.containsKey(id) || !Files.exists(REGISTERED.get(id))) {
                    register(id, p);
                }
            }
        } catch (IOException e) {
            GTShaders.LOGGER.warn("无法扫描导入贴图目录", e);
        }
    }

    /** 根据完整资源 id 返回本地文件；不是导入贴图则返回 null。 */
    public static @Nullable Path pathFor(Identifier id) {
        if (id != null && "gtshaders".equals(id.getNamespace())
                && id.getPath().startsWith("imported/")) {
            return pathFor("gtshaders:" + id.getPath());
        }
        return REGISTERED.get(id);
    }

    public static @Nullable Path pathFor(String location) {
        if (location == null || !location.startsWith("gtshaders:imported/")) {
            return null;
        }
        String idPath = location.substring("gtshaders:imported/".length());
        if (idPath.isEmpty()) {
            return null;
        }
        String fileName = idPath.endsWith(".png") ? idPath : idPath + ".png";
        // 必须过 SafePaths：location 来自 .fsh 的 @texture 注解，而 Identifier 的路径允许 '.'，
        // 所以 gtshaders:imported/../../x 是个合法 id，直接 resolve 就能读到导入目录外面的文件，
        // 再被导出器原样打进资源包。
        Path candidate = SafePaths.resolveOrNull(dir(), fileName);
        if (candidate == null) {
            GTShaders.LOGGER.warn("贴图路径越界，已拒绝：{}", location);
            return null;
        }
        if (Files.isRegularFile(candidate)) {
            return candidate;
        }
        try {
            return REGISTERED.get(Identifier.parse(location));
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static void register(Identifier id, Path file) {
        try (InputStream in = Files.newInputStream(file)) {
            NativeImage image = NativeImage.read(in);
            DynamicTexture texture = new DynamicTexture(() -> id.toString(), image);
            Minecraft.getInstance().getTextureManager().register(id, texture);
            REGISTERED.put(id, file.toAbsolutePath().normalize());
            GTShaders.LOGGER.info("已注册导入贴图 {} <- {}", id, file);
        } catch (IOException | RuntimeException e) {
            GTShaders.LOGGER.warn("无法注册导入贴图 {} <- {}", id, file, e);
        }
    }

    private static String sanitize(String name) {
        String n = name == null ? "custom.png" : name.trim();
        n = n.replaceAll("[^a-zA-Z0-9._-]", "_");
        if (n.isEmpty() || !n.toLowerCase(java.util.Locale.ROOT).endsWith(".png")) {
            n = n + ".png";
        }
        return n;
    }
}
