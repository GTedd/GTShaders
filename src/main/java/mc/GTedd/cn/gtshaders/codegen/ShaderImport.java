package mc.GTedd.cn.gtshaders.codegen;

import org.jspecify.annotations.Nullable;
import mc.GTedd.cn.gtshaders.core.ShaderKind;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 认一份丢进来的着色器文本，把它变成可以直接编辑的作者源码。
 *
 * <p>三种来源，按可靠性从高到低依次尝试：
 * <ol>
 *   <li><b>本工程生成的产物</b>——头部有我们自己的分界线，直接按线切回来，
 *       逐字节还原，参数面板完整恢复。{@code shaderlib/} 里的效果走的就是这条路。</li>
 *   <li><b>原版格式的完整着色器</b>——有 {@code #version} 但没有我们的分界线，
 *       交给 {@link VanillaImporter} 剥掉头部，顺便把 uniform 块转成参数注解。</li>
 *   <li><b>作者源码</b>——没有 {@code #version}，说明它本来就是一段函数体，原样收下。</li>
 * </ol>
 *
 * <p>之所以要分辨这三种：把作者源码误当成完整着色器去剥，会把有用的行删掉；
 * 把完整着色器直接当作者源码收下，则会和生成的头部撞成重复定义。两种错法都不好排查。
 */
public final class ShaderImport {

    public enum Kind {
        /** 本工程生成的产物，原样切回 */
        ROUND_TRIP,
        /** 原版/第三方的完整着色器，剥掉头部 */
        STRIPPED,
        /** 本来就是作者源码 */
        RAW,
        /** 认出是某个原版<b>核心</b>着色器，整份接管 */
        CORE_OVERRIDE
    }

    /**
     * @param authorSource 可以直接塞进效果层的作者源码
     * @param name         建议的层名（来自文件名）
     * @param kind         走了哪条路径，用来给用户一句准确的反馈
     * @param coreKindId   认出的核心着色器种类 id；不是核心着色器时为 null
     */
    public record Result(String authorSource, String name, Kind kind,
                         @Nullable String coreKindId) {
        public Result(String authorSource, String name, Kind kind) {
            this(authorSource, name, kind, null);
        }
    }

    /**
     * 从文件名认出这是哪个原版核心着色器。
     *
     * <p>只看<b>文件名</b>而不看内容：内容可能已经被改得面目全非（那正是用户要拖进来的原因），
     * 但文件名必须和原版一致——否则 Minecraft 根本不会加载它。
     * 所以文件名反而是这里最可靠的线索。
     *
     * <p>旧名字也认。本工程只生成 26.3 的产物，但<b>拖进来的文件往往正是要迁移的旧文件</b>——
     * 认出 {@code rendertype_clouds.fsh} 并把它当成 clouds 种类，正是这个功能的意义所在；
     * 不认的话用户只会看到「这不是着色器文件」，而他手里拿着的恰恰就是。
     */
    public static @Nullable String detectCoreKind(String fileName) {
        String base = baseName(fileName);
        String canonical = LEGACY_NAMES.getOrDefault(base, base);
        for (ShaderKind.Entry k : ShaderKind.all()) {
            String path = k.path();
            if (canonical.equals(path.substring(path.lastIndexOf('/') + 1))) {
                return k.id();
            }
        }
        return null;
    }

    /**
     * 26.3 改过名的核心着色器：旧文件名 → 新文件名。
     *
     * <p>只有这两个。26.3-snapshot-2 把 {@code rendertype_} 前缀从这两个身上去掉了，
     * 其余种类的文件名一个都没动。
     */
    private static final Map<String, String> LEGACY_NAMES = Map.of(
            "rendertype_clouds", "clouds",
            "rendertype_world_border", "world_border");

    /** 认得出的着色器文件后缀。 */
    private static final List<String> SHADER_SUFFIXES = List.of(".fsh", ".glsl", ".frag", ".txt");

    private ShaderImport() {
    }

    public static boolean looksLikeShaderFile(String fileName) {
        String lower = fileName.toLowerCase(Locale.ROOT);
        for (String suffix : SHADER_SUFFIXES) {
            if (lower.endsWith(suffix)) {
                return true;
            }
        }
        return false;
    }

    /**
     * @param text     文件全文
     * @param fileName 文件名，只用来起层名
     * @return 导入结果；文本里根本没有 {@code main()} 时返回 null
     */
    public static @Nullable Result read(String text, String fileName) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String name = baseName(fileName);

        String roundTrip = GlslCodegen.extractAuthorBody(text);
        if (roundTrip != null) {
            return new Result(roundTrip, name, Kind.ROUND_TRIP);
        }

        // 核心着色器优先判断：它们的文件名是原版固定的，认出来之后整份接管。
        // 放在 #version 分支<b>之前</b>——否则会被当成后处理着色器去剥头部，
        // 而核心着色器的头部（uniform 块、varying、include）恰恰是必须保留的。
        String coreKind = detectCoreKind(fileName);
        if (coreKind != null && text.contains("#version")) {
            return new Result(text, name, Kind.CORE_OVERRIDE, coreKind);
        }

        if (text.contains("#version")) {
            // 这里刻意不看 rejectReason：那道检查是给「挂到原版效果槽上」用的，
            // 而拖进编辑器只是想看源码、改源码，能不能跑由作者自己判断。
            return new Result(VanillaImporter.toAuthorSource(text, fileName, List.of()),
                    name, Kind.STRIPPED);
        }

        if (!text.contains("main")) {
            return null;
        }
        return new Result(text, name, Kind.RAW);
    }

    /** 去掉目录和后缀，{@code screencrack.fsh} → {@code screencrack}。 */
    private static String baseName(String fileName) {
        String n = fileName;
        int slash = Math.max(n.lastIndexOf('/'), n.lastIndexOf('\\'));
        if (slash >= 0) {
            n = n.substring(slash + 1);
        }
        int dot = n.lastIndexOf('.');
        if (dot > 0) {
            n = n.substring(0, dot);
        }
        return n.isBlank() ? "shader" : n;
    }
}
