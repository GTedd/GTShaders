package mc.GTedd.cn.gtshaders.ai;

import mc.GTedd.cn.gtshaders.codegen.GlslSanitizer;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 把模型回答里那段着色器抠出来，并削掉它多写的头部。
 *
 * <h2>为什么必须削头</h2>
 *
 * <p>{@link mc.GTedd.cn.gtshaders.codegen.GlslCodegen} 会替作者生成 {@code #version}、{@code Globals}
 * 块、{@code InSampler}、{@code texCoord}/{@code fragColor} 以及一整排 {@code #define}。
 * 而模型见过的训练语料几乎全是<b>完整</b>的 GLSL 文件，它默认会把这些也写一遍——
 * 于是生成的代码拼进模板之后变成重复声明，驱动报 {@code redefinition of 'fragColor'}。
 *
 * <p>这个错的要命之处在于它<b>指向的是模板行而不是模型写的行</b>，玩家看到的是一个
 * 指着自己没写过的代码的报错。就算把错误回喂给模型，模型看不到模板，也修不动。
 * 所以这一步不能指望自动修错循环兜底，必须在源头削掉。
 *
 * <h2>为什么只削精确匹配的那几种</h2>
 *
 * <p>{@code uniform float Bloom;} 这样的裸 uniform 是<b>要留下的</b>——
 * {@link mc.GTedd.cn.gtshaders.codegen.ParamScanner} 靠它生成参数滑块。削过头的话，
 * 玩家拿到的是一个没有任何可调项的效果，而那正是这个编辑器存在的意义。
 * 所以宁可漏削（漏了还有修错循环），也不模糊匹配。
 */
public final class ShaderExtractor {

    /**
     * @param source 可以直接塞进 {@link mc.GTedd.cn.gtshaders.core.ShaderLayer} 的源码
     * @param notes  做过的非平凡改动，值得在界面上告诉玩家一声
     */
    public record Result(String source, List<String> notes) {
        public boolean isEmpty() {
            return source.isBlank();
        }
    }

    /** ```glsl / ```fsh / ```c / ``` 都认；语言标注五花八门，不该成为取不到代码的理由。 */
    private static final Pattern FENCE = Pattern.compile(
            "```[ \\t]*([A-Za-z0-9_+-]*)[ \\t]*\\r?\\n(.*?)(?:```|\\z)", Pattern.DOTALL);

    private static final Pattern MAIN = Pattern.compile("\\bvoid\\s+main\\s*\\(");
    private static final Pattern MAIN_IMAGE = Pattern.compile(
            "\\bvoid\\s+mainImage\\s*\\(\\s*out\\s+vec4\\s+\\w+\\s*,\\s*in\\s+vec2\\s+(\\w+)\\s*\\)");

    /** 整行就被削掉的头部声明。故意逐条列举，不用通配。 */
    private static final List<Pattern> DROP_LINES = List.of(
            Pattern.compile("^\\s*#version\\b.*$"),
            Pattern.compile("^\\s*#extension\\b.*$"),
            Pattern.compile("^\\s*precision\\s+(lowp|mediump|highp)\\s+\\w+\\s*;\\s*$"),
            Pattern.compile("^\\s*uniform\\s+sampler2D\\s+(InSampler|SceneSampler)\\s*;.*$"),
            Pattern.compile("^\\s*(layout\\s*\\([^)]*\\)\\s*)?in\\s+vec2\\s+texCoord\\s*;.*$"),
            Pattern.compile("^\\s*(layout\\s*\\([^)]*\\)\\s*)?out\\s+vec4\\s+fragColor\\s*;.*$"),
            // 只削 codegen 自己会生成的那批别名，作者自定义的 #define 一律保留
            Pattern.compile("^\\s*#define\\s+(GTTime|GTDeltaTime|GTFrame|GTPlaying|GTStrength"
                    + "|GTLayerIndex|GTLayerCount|GTViewportUV|iTime|iTimeDelta|iResolution"
                    + "|iChannel0|iChannel1|GT_ANCHOR_SLOTS)\\b.*$"));

    /** 由 codegen 生成的 std140 块，跨多行，要连着花括号一起削。 */
    private static final Pattern BLOCK_HEAD = Pattern.compile(
            "^\\s*layout\\s*\\(\\s*std140\\s*\\)\\s*uniform\\s+(Globals|SamplerInfo|GTParams|GT\\w*)\\b.*$");

    private ShaderExtractor() {
    }

    public static Result extract(String reply) {
        List<String> notes = new ArrayList<>();
        if (reply == null || reply.isBlank()) {
            return new Result("", notes);
        }
        String code = pickCode(reply);
        if (code.isBlank()) {
            return new Result("", notes);
        }

        GlslSanitizer.Result clean = GlslSanitizer.sanitize(code);
        if (clean.replaced() > 0) {
            notes.add("sanitized:" + clean.replaced());
        }

        int dropped = 0;
        StringBuilder out = new StringBuilder(clean.text().length());
        String[] lines = clean.text().split("\n", -1);
        int depth = 0;
        for (String line : lines) {
            if (depth > 0) {
                depth += braceDelta(line);
                dropped++;
                continue;
            }
            if (BLOCK_HEAD.matcher(line).matches()) {
                // 单行写完的块（{...};）在这里 delta 为 0，直接丢掉这一行即可
                depth = braceDelta(line);
                dropped++;
                continue;
            }
            if (matchesAny(line)) {
                dropped++;
                continue;
            }
            out.append(line).append('\n');
        }
        if (dropped > 0) {
            notes.add("stripped:" + dropped);
        }

        String body = trimBlankEdges(out.toString());
        String bridged = bridgeShadertoy(body);
        if (!bridged.equals(body)) {
            notes.add("bridged");
            body = bridged;
        }
        return new Result(body, notes);
    }

    /**
     * 选出那段代码。
     *
     * <p>模型经常先写一段带片段的解释再给完整实现，所以<b>不能取第一个代码块</b>。
     * 判据是「含 main 的块里最长的那个」：含 main 才是完整实现，最长的那个才是最终版本。
     * 一个带 fence 的块都没有时退回整段回答——有些模型在被要求「只输出代码」之后
     * 会老老实实地连 fence 都不写。
     */
    private static String pickCode(String reply) {
        List<String> blocks = new ArrayList<>();
        Matcher m = FENCE.matcher(reply);
        while (m.find()) {
            blocks.add(m.group(2));
        }
        String best = "";
        for (String b : blocks) {
            if (hasEntryPoint(b) && b.length() > best.length()) {
                best = b;
            }
        }
        if (!best.isEmpty()) {
            return best;
        }
        for (String b : blocks) {
            if (b.length() > best.length()) {
                best = b;
            }
        }
        return best.isEmpty() ? reply : best;
    }

    private static boolean hasEntryPoint(String s) {
        return MAIN.matcher(s).find() || MAIN_IMAGE.matcher(s).find();
    }

    /**
     * 补一座通往 Shadertoy 写法的桥。
     *
     * <p>模型语料里的后处理 GLSL 绝大多数来自 Shadertoy，那边的入口是
     * {@code mainImage(out vec4, in vec2)} 而不是 {@code main()}。prompt 里写明了要求，
     * 但这是个模型很容易滑回去的惯性写法，而后果格外难懂：codegen 找不到
     * {@code void main} 就不改写入口，最终链接时报「缺少 main」，
     * 报错里完全看不出「你写的是另一个函数名」。
     *
     * <p>补一行桥接比多跑一轮修错便宜得多，也比让玩家自己看懂那个链接错误人道。
     */
    private static String bridgeShadertoy(String body) {
        if (MAIN.matcher(body).find()) {
            return body;
        }
        Matcher m = MAIN_IMAGE.matcher(body);
        if (!m.find()) {
            return body;
        }
        return body + "\n\nvoid main() {\n    mainImage(fragColor, texCoord * iResolution.xy);\n}\n";
    }

    private static boolean matchesAny(String line) {
        for (Pattern p : DROP_LINES) {
            if (p.matcher(line).matches()) {
                return true;
            }
        }
        return false;
    }

    /** 只数代码里的花括号——字符串和注释里的不算，但 GLSL 头部块里两者都不会出现。 */
    private static int braceDelta(String line) {
        int d = 0;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '/' && i + 1 < line.length() && line.charAt(i + 1) == '/') {
                break;
            }
            if (c == '{') {
                d++;
            } else if (c == '}') {
                d--;
            }
        }
        return d;
    }

    private static String trimBlankEdges(String s) {
        String[] lines = s.split("\n", -1);
        int from = 0;
        int to = lines.length;
        while (from < to && lines[from].isBlank()) {
            from++;
        }
        while (to > from && lines[to - 1].isBlank()) {
            to--;
        }
        return String.join("\n", List.of(lines).subList(from, to));
    }
}
