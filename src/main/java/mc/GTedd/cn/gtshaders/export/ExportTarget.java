package mc.GTedd.cn.gtshaders.export;

import org.jspecify.annotations.Nullable;

import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;

/**
 * 导出落点：写到哪个目录、叫什么文件名。
 *
 * <p>两条规则都为同一件事服务——<b>用户给的中文必须原样留住</b>。资源包<i>内部</i>的路径是
 * 另一回事：那里只能是 {@code [a-z0-9_.-]}，由 {@link ResourcePackExporter#sanitize} 负责。
 * 两者不要互相借用：拿 {@code sanitize} 去处理文件名，所有中文名都会塌成同一个
 * {@code untitled}，于是两个中文工程导出后互相覆盖，而现场看起来只是「导出好像没生效」。
 *
 * <p>{@link #fileName} 同时也是工程文件名的规则（{@code ProjectStore} 直接用它）：
 * 「哪些字符文件系统收不下」这件事和内容是什么无关，各写一份迟早会漂移，
 * 而漂移的表现是某一边在某台机器上写不出文件。
 */
public final class ExportTarget {

    /**
     * 文件名主干（不含扩展名）的长度上限。
     *
     * <p>取 196 而不是 255：常见文件系统限的是<b>字节</b>数，中文一个字在 UTF-8 下占 3 字节，
     * 再算上 {@code .gtshader.json} 这种较长的扩展名，196 个字符是各种组合下都还安全的余量。
     */
    private static final int MAX_STEM = 196;

    private ExportTarget() {
    }

    /**
     * 文件名安全化：保留可读字符（中文、空格都留着），只收敛文件系统不接受的部分。
     *
     * <p>做的四件事，每一件都对应一种「文件根本创建不出来」的失败：
     * <ol>
     *   <li>{@code / \ : * ? " < > |} 与控制字符换成下划线；</li>
     *   <li>去掉首尾的点和空格——Windows 会静默地把它们吃掉，于是「我的包 」和「我的包」
     *       指向同一个文件；</li>
     *   <li>避开 Windows 设备名（{@code CON}、{@code COM1}……），<b>带扩展名的也算</b>；</li>
     *   <li>超长截断，且不从 UTF-16 代理对中间切开——半个 emoji 会让文件操作直接失败。</li>
     * </ol>
     *
     * @param raw      用户输入；空白表示「没自定义」
     * @param fallback {@code raw} 为空白时用的名字，调用方保证它本身已经是安全的
     */
    public static String fileName(String raw, String fallback) {
        String trimmed = raw == null ? "" : raw.trim();
        if (trimmed.isEmpty()) {
            return fallback;
        }
        // 「我的包.zip」这样连扩展名一起敲进来是很自然的写法，不去掉就会拼成 .zip.zip
        if (trimmed.regionMatches(true, trimmed.length() - 4, ".zip", 0, 4)) {
            trimmed = trimmed.substring(0, trimmed.length() - 4).trim();
        }
        StringBuilder sb = new StringBuilder();
        for (char c : trimmed.toCharArray()) {
            if (c < 0x20) {
                continue;
            }
            switch (c) {
                case '/', '\\', ':', '*', '?', '"', '<', '>', '|' -> sb.append('_');
                default -> sb.append(c);
            }
        }
        String s = sb.toString().trim();
        s = s.replaceAll("^[\\. ]+|[\\. ]+$", "");
        if (s.isEmpty() || s.matches("(?i)^(con|prn|aux|nul|com\\d|lpt\\d)(\\..*)?$")) {
            s = "_" + s;
        }
        if (s.length() > MAX_STEM) {
            int end = Character.isHighSurrogate(s.charAt(MAX_STEM - 1)) ? MAX_STEM - 1 : MAX_STEM;
            s = s.substring(0, end);
        }
        return s;
    }

    /**
     * 把用户输入的目录解析成绝对路径。
     *
     * <p>绝对路径原样用；相对路径落在 {@code fallback} 下面——「我的包」这种输入应当理解成
     * 默认导出目录里的一个子目录。<b>不</b>拿进程的当前目录去解析：那是启动器决定的，
     * 玩家既看不见也改不动，同一串输入在不同启动器下会落到不同地方。
     *
     * <p>目录不存在不算错，写出去的时候会连着建好（见 {@link StagedWrite#toFile}）。
     *
     * @throws InvalidPathException 输入压根不是一个合法路径（Windows 上的 {@code a:b} 之类）
     */
    public static Path directory(String raw, Path fallback) {
        String s = raw == null ? "" : raw.trim();
        // Windows 资源管理器的「复制文件地址」带一对引号，粘进来直接解析会失败
        if (s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"")) {
            s = s.substring(1, s.length() - 1).trim();
        }
        if (s.isEmpty()) {
            return fallback.toAbsolutePath().normalize();
        }
        Path p = Path.of(s);
        // getRoot 也要判：Windows 上 "\包" 的 isAbsolute() 是 false（盘符未定），
        // 但它带 root，resolve 时会把基准目录整个顶掉
        Path base = p.isAbsolute() || p.getRoot() != null ? p : fallback.resolve(p);
        return base.toAbsolutePath().normalize();
    }

    /**
     * 往上找到第一个确实存在的目录。
     *
     * <p>给系统文件夹选择框定位用：传一个还不存在的目录进去，各平台的表现完全不一样——
     * 有的落到「上次的位置」，有的落到用户主目录，有的直接报错。往上退到存在的那一级，
     * 至少保证打开时人在自己想去的地方附近。
     *
     * @return 都不存在（比如传的是一个已拔掉的盘符）时返回 {@code null}，交给系统自己决定
     */
    public static @Nullable Path existingAncestor(@Nullable Path start) {
        Path p = start == null ? null : start.toAbsolutePath().normalize();
        while (p != null && !Files.isDirectory(p)) {
            p = p.getParent();
        }
        return p;
    }
}
