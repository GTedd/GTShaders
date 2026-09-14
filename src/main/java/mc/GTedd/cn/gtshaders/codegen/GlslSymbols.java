package mc.GTedd.cn.gtshaders.codegen;

import mc.GTedd.cn.gtshaders.core.ShaderParam;
import mc.GTedd.cn.gtshaders.i18n.GtLang;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 编辑器能认得的名字：补全列表、悬停说明、签名提示共用这一张表。
 *
 * <h2>四个来源</h2>
 *
 * <ol>
 *   <li><b>作者自己的源码</b>：函数、变量、{@code @param} 参数、{@code #define}。每次按需重扫，
 *       只做词法——写到一半编译不过是常态，依赖「上次编译成功」的反射结果（SHADERed 的做法）
 *       会让补全在最需要它的时候失效。</li>
 *   <li><b>GTShaders 的 helper</b>：签名与说明<b>直接从 {@link GlslCodegen#helperSources()} 解析</b>，
 *       说明取函数上方的注释，不另写一份。</li>
 *   <li><b>生成头部里的宏与内置量</b>：{@code GTTime}、{@code InSampler} 这些，说明走语言文件。</li>
 *   <li><b>GLSL 内置函数与关键字</b>：只给签名。</li>
 * </ol>
 */
public final class GlslSymbols {

    public enum Kind {
        /** 作者源码里的函数。 */
        FUNCTION,
        /** 作者源码里的变量或宏。 */
        VARIABLE,
        /** 面板参数。 */
        PARAM,
        /** GTShaders 注入的 helper 函数。 */
        HELPER,
        /** 生成头部里的宏与内置量。 */
        BUILTIN_VALUE,
        /** GLSL 内置函数。 */
        GLSL_FUNCTION,
        /** 关键字与类型名。 */
        KEYWORD
    }

    /**
     * @param signatures 函数的全部重载签名；变量与关键字为空
     * @param doc        说明，可能为空串
     * @param line       在作者源码里的行号（1 起）；不是作者写的为 0
     */
    public record Symbol(String name, Kind kind, List<String> signatures, String doc, int line) {
        public String detail() {
            return signatures.isEmpty() ? "" : signatures.get(0);
        }
    }

    private GlslSymbols() {
    }

    // ------------------------------------------------------------------ 静态部分

    private static final List<String> KEYWORDS = List.of(
            "float", "int", "uint", "bool", "vec2", "vec3", "vec4", "ivec2", "ivec3", "ivec4",
            "uvec2", "uvec3", "uvec4", "bvec2", "bvec3", "bvec4", "mat2", "mat3", "mat4", "sampler2D",
            "void", "const", "in", "out", "inout", "struct", "if", "else", "for", "while", "do",
            "return", "break", "continue", "discard", "true", "false", "uniform", "layout");

    /** 语句开头能出现的类型名，用来认局部变量声明。 */
    private static final Set<String> TYPES = Set.of(
            "float", "int", "uint", "bool", "vec2", "vec3", "vec4", "ivec2", "ivec3", "ivec4",
            "uvec2", "uvec3", "uvec4", "bvec2", "bvec3", "bvec4", "mat2", "mat3", "mat4", "sampler2D", "void");

    private static final String[] GLSL_FUNCTIONS = {
            "radians(genType degrees)", "degrees(genType radians)", "sin(genType angle)", "cos(genType angle)",
            "tan(genType angle)", "asin(genType x)", "acos(genType x)", "atan(genType y, genType x)",
            "atan(genType y_over_x)", "sinh(genType x)", "cosh(genType x)", "tanh(genType x)",
            "asinh(genType x)", "acosh(genType x)", "atanh(genType x)",
            "pow(genType x, genType y)", "exp(genType x)", "log(genType x)", "exp2(genType x)",
            "log2(genType x)", "sqrt(genType x)", "inversesqrt(genType x)",
            "abs(genType x)", "sign(genType x)", "floor(genType x)", "trunc(genType x)", "round(genType x)",
            "roundEven(genType x)", "ceil(genType x)", "fract(genType x)",
            "mod(genType x, float y)", "mod(genType x, genType y)", "modf(genType x, out genType i)",
            "min(genType x, genType y)", "max(genType x, genType y)",
            "clamp(genType x, genType minVal, genType maxVal)", "mix(genType x, genType y, genType a)",
            "step(genType edge, genType x)", "smoothstep(genType edge0, genType edge1, genType x)",
            "isnan(genType x)", "isinf(genType x)", "floatBitsToInt(genType value)",
            "floatBitsToUint(genType value)", "intBitsToFloat(genIType value)", "uintBitsToFloat(genUType value)",
            "length(genType x)", "distance(genType p0, genType p1)", "dot(genType x, genType y)",
            "cross(vec3 x, vec3 y)", "normalize(genType x)", "faceforward(genType N, genType I, genType Nref)",
            "reflect(genType I, genType N)", "refract(genType I, genType N, float eta)",
            "matrixCompMult(mat x, mat y)", "outerProduct(vec c, vec r)", "transpose(mat m)",
            "determinant(mat m)", "inverse(mat m)",
            "lessThan(vec x, vec y)", "lessThanEqual(vec x, vec y)", "greaterThan(vec x, vec y)",
            "greaterThanEqual(vec x, vec y)", "equal(vec x, vec y)", "notEqual(vec x, vec y)",
            "any(bvec x)", "all(bvec x)", "not(bvec x)",
            "textureSize(sampler2D sampler, int lod)", "texture(sampler2D sampler, vec2 P)",
            "texture(sampler2D sampler, vec2 P, float bias)", "textureLod(sampler2D sampler, vec2 P, float lod)",
            "textureOffset(sampler2D sampler, vec2 P, ivec2 offset)", "texelFetch(sampler2D sampler, ivec2 P, int lod)",
            "texelFetchOffset(sampler2D sampler, ivec2 P, int lod, ivec2 offset)",
            "textureGrad(sampler2D sampler, vec2 P, vec2 dPdx, vec2 dPdy)",
            "dFdx(genType p)", "dFdy(genType p)", "fwidth(genType p)"};

    /** 生成头部里的宏与内置量；说明在语言文件 {@code gtshaders.doc.<名字>}。 */
    private static final String[][] BUILTIN_VALUES = {
            {"InSampler", "sampler2D"}, {"texCoord", "vec2"}, {"fragColor", "vec4"}, {"gl_FragCoord", "vec4"},
            {"OutSize", "vec2"}, {"InSize", "vec2"}, {"GTTime", "float"}, {"GTDeltaTime", "float"},
            {"GTFrame", "float"}, {"GTPlaying", "float"}, {"GTStrength", "float"}, {"GTLayerIndex", "float"},
            {"GTLayerCount", "float"}, {"GTViewportUV", "vec2"}, {"iTime", "float"}, {"iTimeDelta", "float"},
            {"iResolution", "vec3"}, {"iChannel0", "sampler2D"}, {"GameTime", "float"},
            {"ScreenSize", "vec2"}, {"CameraBlockPos", "ivec3"}, {"CameraOffset", "vec3"}};

    private static volatile @Nullable List<Symbol> staticCache;
    private static volatile String staticCacheLang = "";

    /** 不依赖作者源码的那部分。说明跟着界面语言走，所以按语言缓存。 */
    public static List<Symbol> staticSymbols() {
        List<Symbol> cached = staticCache;
        String lang = GtLang.currentLang();
        if (cached != null && lang.equals(staticCacheLang)) {
            return cached;
        }
        List<Symbol> out = new ArrayList<>();
        for (String[] v : BUILTIN_VALUES) {
            String doc = GtLang.getOrNull("gtshaders.doc." + v[0]);
            out.add(new Symbol(v[0], Kind.BUILTIN_VALUE, List.of(v[1] + " " + v[0]), doc == null ? "" : doc, 0));
        }
        Map<String, List<String>> helperSigs = new LinkedHashMap<>();
        Map<String, String> helperDocs = new LinkedHashMap<>();
        for (String src : GlslCodegen.helperSources()) {
            for (Function f : functions(src)) {
                if (!f.name().startsWith("gt")) {
                    continue;
                }
                helperSigs.computeIfAbsent(f.name(), k -> new ArrayList<>()).add(f.signature());
                if (!f.doc().isEmpty()) {
                    helperDocs.putIfAbsent(f.name(), f.doc());
                }
            }
        }
        helperSigs.forEach((name, sigs) -> out.add(new Symbol(name, Kind.HELPER, List.copyOf(sigs),
                helperDocs.getOrDefault(name, ""), 0)));
        Map<String, List<String>> glsl = new LinkedHashMap<>();
        for (String sig : GLSL_FUNCTIONS) {
            glsl.computeIfAbsent(sig.substring(0, sig.indexOf('(')), k -> new ArrayList<>()).add(sig);
        }
        glsl.forEach((name, sigs) -> out.add(new Symbol(name, Kind.GLSL_FUNCTION, List.copyOf(sigs), "", 0)));
        for (String k : KEYWORDS) {
            out.add(new Symbol(k, Kind.KEYWORD, List.of(), "", 0));
        }
        List<Symbol> frozen = Collections.unmodifiableList(out);
        staticCache = frozen;
        staticCacheLang = lang;
        return frozen;
    }

    // ------------------------------------------------------------------ 作者源码

    /** 一个函数定义：返回类型 + 名字 + 参数表，以及紧贴在它上方的注释。 */
    public record Function(String name, String signature, String doc, int line) {
    }

    /**
     * 找出源码里的顶层函数定义：{@code 类型 名字(...) {}}，花括号深度为 0 的位置。
     *
     * <p>说明取紧贴在返回类型上方、连续的 {@code //} 注释；{@code // ----} 这种分节标题不算。
     */
    public static List<Function> functions(String src) {
        List<Function> out = new ArrayList<>();
        List<GlslLexer.Token> all = GlslLexer.tokenize(src);
        int depth = 0;
        for (int i = 0; i < all.size(); i++) {
            GlslLexer.Token t = all.get(i);
            if (t.is("{")) {
                depth++;
            } else if (t.is("}")) {
                depth = Math.max(0, depth - 1);
            }
            if (depth != 0 || t.kind() != GlslLexer.Kind.IDENT) {
                continue;
            }
            int nameIdx = nextCode(all, i);
            if (nameIdx < 0 || all.get(nameIdx).kind() != GlslLexer.Kind.IDENT) {
                continue;
            }
            int open = nextCode(all, nameIdx);
            if (open < 0 || !all.get(open).is("(")) {
                continue;
            }
            int close = matchingParen(all, open);
            if (close < 0) {
                continue;
            }
            int brace = nextCode(all, close);
            if (brace < 0 || !all.get(brace).is("{")) {
                continue;
            }
            String name = all.get(nameIdx).text();
            String signature = collapse(src.substring(t.start(), all.get(close).end()));
            out.add(new Function(name, signature, docAbove(all, i), all.get(nameIdx).line()));
            i = close;
        }
        return out;
    }

    private static int nextCode(List<GlslLexer.Token> all, int from) {
        for (int j = from + 1; j < all.size(); j++) {
            if (all.get(j).isCode()) {
                return j;
            }
        }
        return -1;
    }

    private static int matchingParen(List<GlslLexer.Token> all, int open) {
        int depth = 0;
        for (int j = open; j < all.size(); j++) {
            GlslLexer.Token t = all.get(j);
            if (!t.isCode()) {
                continue;
            }
            if (t.is("(")) {
                depth++;
            } else if (t.is(")")) {
                depth--;
                if (depth == 0) {
                    return j;
                }
            } else if (t.is("{") || t.is(";")) {
                return -1;
            }
        }
        return -1;
    }

    private static String docAbove(List<GlslLexer.Token> all, int typeIdx) {
        List<String> lines = new ArrayList<>();
        int expectLine = all.get(typeIdx).line() - 1;
        for (int j = typeIdx - 1; j >= 0; j--) {
            GlslLexer.Token t = all.get(j);
            if (t.kind() != GlslLexer.Kind.LINE_COMMENT || t.line() != expectLine) {
                break;
            }
            String text = t.text().substring(2).strip();
            // 分节标题与 @param / @group / @texture 注解都不是这个函数的说明
            if (text.startsWith("----") || text.startsWith("@")) {
                break;
            }
            lines.add(0, text);
            expectLine--;
        }
        return String.join(" ", lines).replace("<b>", "").replace("</b>", "").strip();
    }

    private static String collapse(String s) {
        return s.replaceAll("\\s+", " ").strip();
    }

    /**
     * 作者源码里的名字：函数、{@code #define}、变量声明，再加面板参数。
     *
     * <p>变量只认「类型名 + 标识符」紧挨着的写法，结构体类型的变量认不出——为此去做完整的类型推导不值得。
     */
    public static List<Symbol> userSymbols(String src, List<ShaderParam> params) {
        Map<String, Symbol> out = new LinkedHashMap<>();
        for (Function f : functions(src)) {
            Symbol prev = out.get(f.name());
            List<String> sigs = new ArrayList<>(prev == null ? List.of() : prev.signatures());
            sigs.add(f.signature());
            out.put(f.name(), new Symbol(f.name(), Kind.FUNCTION, sigs, f.doc(), prev == null ? f.line() : prev.line()));
        }
        for (ShaderParam p : params) {
            String doc = p.resolveLabel(GtLang.contentLang(), GtLang::getOrNull);
            String desc = p.resolveDesc(GtLang.contentLang());
            if (!desc.isEmpty()) {
                doc = doc + " · " + desc;
            }
            out.putIfAbsent(p.name(), new Symbol(p.name(), Kind.PARAM,
                    List.of(p.type().glslType() + " " + p.name()), doc, Math.max(0, p.sourceLine())));
        }
        List<GlslLexer.Token> all = GlslLexer.tokenize(src);
        for (int i = 0; i < all.size(); i++) {
            GlslLexer.Token t = all.get(i);
            if (t.kind() == GlslLexer.Kind.PREPROCESSOR) {
                String[] parts = t.text().strip().split("\\s+");
                if (parts.length >= 2 && parts[0].equals("#define")) {
                    String name = parts[1].replaceAll("\\(.*", "");
                    out.putIfAbsent(name, new Symbol(name, Kind.VARIABLE, List.of(collapse(t.text())), "", t.line()));
                }
                continue;
            }
            if (t.kind() != GlslLexer.Kind.IDENT || !TYPES.contains(t.text()) || t.is("void")) {
                continue;
            }
            int n = nextCode(all, i);
            if (n < 0 || all.get(n).kind() != GlslLexer.Kind.IDENT || KEYWORDS.contains(all.get(n).text())) {
                continue;
            }
            int after = nextCode(all, n);
            if (after >= 0 && all.get(after).is("(")) {
                continue;
            }
            String name = all.get(n).text();
            out.putIfAbsent(name, new Symbol(name, Kind.VARIABLE, List.of(t.text() + " " + name), "", all.get(n).line()));
        }
        return new ArrayList<>(out.values());
    }

    // ------------------------------------------------------------------ 查询

    /** 按名字查，作者源码里的优先（作者可以遮蔽 helper 名字，那时说的就是他自己那个）。 */
    public static @Nullable Symbol lookup(String name, List<Symbol> user) {
        for (Symbol s : user) {
            if (s.name().equals(name)) {
                return s;
            }
        }
        for (Symbol s : staticSymbols()) {
            if (s.name().equals(name)) {
                return s;
            }
        }
        return null;
    }

    /**
     * 补全候选。前缀命中（不分大小写）排在子串命中之前；同一档里作者的名字优先，
     * 然后是 helper、内置量、GLSL 函数、关键字；再按名字长短。
     *
     * <p>和输入完全相同的那一个不列：已经敲完的词再弹出一个它自己，只会让回车先被吃掉一次。
     */
    public static List<Symbol> complete(String prefix, List<Symbol> user, int limit) {
        if (prefix == null || prefix.isEmpty()) {
            return List.of();
        }
        String p = prefix.toLowerCase(Locale.ROOT);
        // 作者的排在前面，下面按名字去重时同名的 helper 自然被遮蔽
        List<Symbol> pool = new ArrayList<>(user);
        pool.addAll(staticSymbols());
        List<Symbol> hits = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (Symbol s : pool) {
            String n = s.name().toLowerCase(Locale.ROOT);
            if (s.name().equals(prefix) || !n.contains(p) || !seen.add(s.name())) {
                continue;
            }
            hits.add(s);
        }
        hits.sort((a, b) -> {
            boolean ap = a.name().toLowerCase(Locale.ROOT).startsWith(p);
            boolean bp = b.name().toLowerCase(Locale.ROOT).startsWith(p);
            if (ap != bp) {
                return ap ? -1 : 1;
            }
            int k = Integer.compare(a.kind().ordinal(), b.kind().ordinal());
            if (k != 0) {
                return k;
            }
            int len = Integer.compare(a.name().length(), b.name().length());
            return len != 0 ? len : a.name().compareTo(b.name());
        });
        return hits.size() > limit ? hits.subList(0, limit) : hits;
    }
}
