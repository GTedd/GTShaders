package mc.GTedd.cn.gtshaders.workspace;

import mc.GTedd.cn.gtshaders.i18n.GtLang;
import org.jspecify.annotations.Nullable;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;

/**
 * 把「外部来的一段字符串」解析成「一定落在某个目录里的路径」。
 *
 * <p>为什么需要它：工作目录下好几处都在拿外部字符串直接 {@code resolve}——
 * {@code @texture path=} 注解里的资源 id、{@code history.json} 里的文件名——
 * 而这些字符串未必只包含文件名。Minecraft 的 {@code Identifier} 路径允许 {@code .}，
 * 于是 {@code gtshaders:imported/../../foo} 是一个<b>合法</b>的 Identifier，
 * 直接 resolve 就能穿出 {@code imported_textures/}，被导出器读进资源包。
 *
 * <p>判定是纯语法的：{@link Path#normalize()} 之后检查是不是还在根目录下面。
 * 它<b>不解析符号链接</b>——真要防住 symlink 得 {@code toRealPath()}，
 * 而那要求文件已经存在，在「先算路径再决定写不写」的场景里用不了。
 * 这里的来源是作者自己写的注解和自己的配置文件，语法层面的收敛就够了。
 *
 * <p>Windows 上有个额外的坑：{@code Path.of("\\foo").isAbsolute()} 是 {@code false}
 * （它表示「当前盘符的根」，盘符未定所以不算绝对路径），但它带 root，
 * resolve 时会把基准目录整个顶掉。所以只判 {@code isAbsolute()} 不够，
 * 还要判 {@link Path#getRoot()}。
 */
public final class SafePaths {

    private SafePaths() {
    }

    /**
     * 解析并要求结果落在 {@code root} 里面，越界直接抛。
     *
     * @throws IllegalArgumentException 相对路径非法，或规范化之后逃出了 {@code root}
     */
    public static Path resolve(Path root, String relative) {
        Path resolved = resolveOrNull(root, relative);
        if (resolved == null) {
            throw new IllegalArgumentException(GtLang.get("gtshaders.status.path_rejected", relative));
        }
        return resolved;
    }

    /**
     * 同 {@link #resolve}，但越界与非法都返回 {@code null}。
     *
     * <p>给「找不到就当没有」的读取路径用：那些地方本来就返回可空的 Path，
     * 抛异常会逼着每个调用点包一层 try，反而让人忍不住写成吞掉异常。
     */
    public static @Nullable Path resolveOrNull(Path root, @Nullable String relative) {
        if (root == null || relative == null || relative.isBlank()) {
            return null;
        }
        Path rel;
        try {
            rel = Path.of(relative);
        } catch (InvalidPathException e) {
            // Windows 上诸如 "a:b" 这类带非法字符的串会走到这里
            return null;
        }
        if (rel.isAbsolute() || rel.getRoot() != null) {
            return null;
        }
        Path base = root.toAbsolutePath().normalize();
        Path result = base.resolve(rel).normalize();
        // startsWith 按路径元素比较而不是字符串前缀，所以 /x/root-evil 不会被当成在 /x/root 下。
        // 另外要排掉 result 就等于 base 的情况（relative 是 "." 或 "a/.." 这种）：
        // 调用方要的是目录<b>里面</b>的一个文件，不是目录本身。
        return result.startsWith(base) && !result.equals(base) ? result : null;
    }
}
