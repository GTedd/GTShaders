package mc.GTedd.cn.gtshaders.codegen;

import org.jspecify.annotations.Nullable;
import mc.GTedd.cn.gtshaders.i18n.GtLang;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 把原版的后处理片段着色器改写成 GTShaders 可编辑的「作者源码」。
 *
 * <p>原版 {@code .fsh} 的结构非常规整，这让自动改写可行：
 * <pre>
 *   #version 330
 *   #extension GL_ARB_separate_shader_objects  ← 丢掉（26.3 才有），头部会重新给
 *   #moj_import / #include &lt;minecraft:globals.glsl&gt;  ← 丢掉，我们的头部已内联 Globals
 *   uniform sampler2D InSampler;                ← 丢掉，头部会重新声明
 *   layout(std140) uniform SamplerInfo { ... }; ← 丢掉，同上
 *   layout(std140) uniform BlurConfig { ... };  ← 转成 // @param，于是它们变成面板上的滑块
 *   in vec2 texCoord;  out vec4 fragColor;      ← 丢掉，同上
 *   void main() { ... }                         ← 原样保留
 * </pre>
 *
 * <p><b>老写法也认</b>：{@code #moj_import} 与裸 {@code in/out}（26.3 之前的格式）
 * 和现在的 {@code #include} + {@code layout(location = N)} 一样会被正确剥掉。
 * 这不是版本兼容——本工程只<b>产出</b> 26.3 格式，剥完之后走的还是同一条生成路径。
 * 认老写法纯粹是因为<b>拖进来的文件往往正是要迁移的那份老文件</b>：
 * 不认的话它会被当成普通代码留在作者源码里，和我们头部的声明撞车，一编译就报重复定义。
 *
 * <p>关键收益在第五行：原版把可调量放在自己的 uniform 块里，值写在 post effect JSON 里。
 * 把「块成员声明 + JSON 里的默认值」合成一行 {@code // @param}，
 * 参数就自动出现在面板上并且可以拖——这正是「对官方效果自动识别和改动」。
 *
 * <p>min/max 是猜的（JSON 里根本没有这个信息），所以生成的注释里会写明可以随手改。
 * 猜错的代价只是滑块范围不顺手，而不猜就没有滑块可用。
 */
public final class VanillaImporter {

    /**
     * post effect JSON 里的一条 uniform 值。
     *
     * @param type 原版写法：float / int / vec2 / vec3 / vec4
     */
    public record Uniform(String name, String type, float[] value) {
    }

    private static final Pattern VERSION = Pattern.compile("^\\s*#version\\b.*");
    /** 26.3 起每个原版 shader 都带 {@code #extension GL_ARB_separate_shader_objects}，头部会重新给。 */
    private static final Pattern EXTENSION = Pattern.compile("^\\s*#extension\\b.*");
    private static final Pattern IMPORT = Pattern.compile("^\\s*#moj_import\\b.*");
    private static final Pattern INCLUDE = Pattern.compile("^\\s*#include\\b.*");
    private static final Pattern SAMPLER = Pattern.compile("^\\s*uniform\\s+sampler2D\\s+(\\w+)\\s*;.*");
    /**
     * in / out 声明。
     *
     * <p>{@code layout(...)} 前缀是可选的：26.3 写成 {@code layout(location = 0) in vec2 texCoord;}，
     * 更早的版本写成 {@code in vec2 texCoord;}。两种都要认——
     * 漏掉任一种，这一行都会被当成普通代码留在作者源码里，和我们头部里的声明撞车，
     * 于是导入的效果一编译就报重复定义。
     */
    private static final String OPTIONAL_LAYOUT = "^\\s*(?:layout\\s*\\([^)]*\\)\\s*)?";
    private static final Pattern VARYING_IN = Pattern.compile(OPTIONAL_LAYOUT + "in\\s+(\\w+)\\s+(\\w+)\\s*;.*");
    private static final Pattern FRAG_OUT = Pattern.compile(OPTIONAL_LAYOUT + "out\\s+vec4\\s+(\\w+)\\s*;.*");
    private static final Pattern BLOCK_START =
            Pattern.compile("^\\s*layout\\s*\\(\\s*std140\\s*\\)\\s*uniform\\s+(\\w+)\\s*\\{.*");
    private static final Pattern MEMBER = Pattern.compile("^\\s*(\\w+)\\s+(\\w+)\\s*;.*");
    private static final Pattern MAIN = Pattern.compile("\\bvoid\\s+main\\s*\\(");

    /** 这些块由我们的头部统一提供，导入时整块丢掉。 */
    private static final List<String> BUILTIN_BLOCKS = List.of("Globals", "SamplerInfo");

    private VanillaImporter() {
    }

    /**
     * 检查一个原版片段着色器能不能被导入成单输入的效果层。
     *
     * @return 不能导入的原因（已翻译）；null 表示可以
     */
    public static @Nullable String rejectReason(String fsh) {
        boolean hasMain = false;
        for (String line : fsh.split("\n", -1)) {
            Matcher sampler = SAMPLER.matcher(line);
            if (sampler.matches() && !sampler.group(1).equals("InSampler")) {
                // 例如 spiderclip 需要 BlurSampler：那是另一个渲染目标，单层模型表达不了
                return GtLang.get("gtshaders.vanilla.reject_sampler", sampler.group(1));
            }
            Matcher in = VARYING_IN.matcher(line);
            if (in.matches() && !in.group(2).equals("texCoord")) {
                // 例如 scaledCoord 来自原版的 rotscale.vsh，我们固定用 screenquad
                return GtLang.get("gtshaders.vanilla.reject_varying", in.group(2));
            }
            if (MAIN.matcher(line).find()) {
                hasMain = true;
            }
        }
        return hasMain ? null : GtLang.get("gtshaders.vanilla.reject_no_main");
    }

    /**
     * 改写成作者源码。
     *
     * @param fsh         原版片段着色器全文
     * @param originLabel 来源标注，会写进生成的注释里
     * @param uniforms    该通道在 post effect JSON 里给出的 uniform 值
     */
    public static String toAuthorSource(String fsh, String originLabel, List<Uniform> uniforms) {
        Map<String, Uniform> byName = new LinkedHashMap<>();
        for (Uniform u : uniforms) {
            byName.put(u.name(), u);
        }

        List<String> body = new ArrayList<>();
        // 块成员的声明顺序就是作者读代码时的顺序，用它排 @param 比用 JSON 顺序更自然
        List<String[]> params = new ArrayList<>();

        String[] lines = fsh.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];

            if (VERSION.matcher(line).matches() || EXTENSION.matcher(line).matches()
                    || IMPORT.matcher(line).matches() || INCLUDE.matcher(line).matches()) {
                continue;
            }
            Matcher sampler = SAMPLER.matcher(line);
            if (sampler.matches() && sampler.group(1).equals("InSampler")) {
                continue;
            }
            Matcher in = VARYING_IN.matcher(line);
            if (in.matches() && in.group(2).equals("texCoord")) {
                continue;
            }
            if (FRAG_OUT.matcher(line).matches()) {
                continue;
            }

            Matcher block = BLOCK_START.matcher(line);
            if (block.matches()) {
                boolean builtin = BUILTIN_BLOCKS.contains(block.group(1));
                // 一直吃到闭合的 };，块体不进入 body；非内置块的成员留下来变成 @param
                int j = i;
                while (j < lines.length && !lines[j].contains("};")) {
                    if (j > i && !builtin) {
                        Matcher member = MEMBER.matcher(lines[j]);
                        if (member.matches()) {
                            params.add(new String[]{member.group(1), member.group(2)});
                        }
                    }
                    j++;
                }
                i = j;
                continue;
            }
            body.add(line);
        }

        StringBuilder sb = new StringBuilder(fsh.length() + 512);
        sb.append("// ").append(GtLang.get("gtshaders.vanilla.header", originLabel)).append('\n');
        if (!params.isEmpty()) {
            sb.append("// ").append(GtLang.get("gtshaders.vanilla.header_range")).append('\n');
        }
        for (String[] p : params) {
            sb.append(paramLine(p[0], p[1], byName.get(p[1]))).append('\n');
        }
        sb.append('\n');
        sb.append(String.join("\n", trimLeadingBlanks(body)));
        return sb.toString();
    }

    private static List<String> trimLeadingBlanks(List<String> body) {
        int from = 0;
        while (from < body.size() && body.get(from).isBlank()) {
            from++;
        }
        return body.subList(from, body.size());
    }

    private static String paramLine(String glslType, String name, @Nullable Uniform value) {
        String type = switch (glslType) {
            case "int" -> "int";
            case "vec2" -> "vec2";
            case "vec3" -> "vec3";
            case "vec4" -> "vec4";
            default -> "float";
        };
        int components = switch (type) {
            case "vec2" -> 2;
            case "vec3" -> 3;
            case "vec4" -> 4;
            default -> 1;
        };
        float[] v = new float[4];
        if (value != null) {
            System.arraycopy(value.value(), 0, v, 0, Math.min(4, value.value().length));
        }

        float absMax = 0f;
        boolean negative = false;
        for (int i = 0; i < components; i++) {
            absMax = Math.max(absMax, Math.abs(v[i]));
            negative |= v[i] < 0f;
        }
        // 上限取「默认值的两倍、至少 1」并向上取整：Resolution 16 → 32、MosaicSize 4 → 8，
        // 都落在能直观拖动的量级上。默认值为 0 时无从推断，给 0..1 并在注释里说明可改。
        float max = Math.max(1f, (float) Math.ceil(absMax * 2f));
        float min = negative ? -max : 0f;

        StringBuilder sb = new StringBuilder("// @param name=").append(name)
                .append(" type=").append(type)
                .append(" min=").append(num(min))
                .append(" max=").append(num(max))
                .append(" default=");
        for (int i = 0; i < components; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(num(v[i]));
        }
        return sb.toString();
    }

    private static String num(float v) {
        if (v == Math.rint(v) && Math.abs(v) < 1e7f) {
            return Integer.toString((int) v);
        }
        return String.format(Locale.ROOT, "%.4f", v).replaceAll("0+$", "").replaceAll("\\.$", "");
    }
}
