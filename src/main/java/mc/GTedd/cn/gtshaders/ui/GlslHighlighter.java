package mc.GTedd.cn.gtshaders.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 极简 GLSL 词法着色。
 *
 * <p>刻意做成<b>单行无状态</b>的：每一行独立着色，不跨行维护状态。代价是跨行块注释
 * {@code /* ... *&#47;} 只有起始行会被标灰；换来的是任意一行都能独立重绘，
 * 编辑器滚动和局部重绘不需要从头扫描整个文件，光标停在第 3000 行也不会掉帧。
 * 对一个后处理着色器（通常几十到几百行）而言这个取舍完全划算。
 */
public final class GlslHighlighter {

    /** 一段同色文本。 */
    public record Span(int start, int end, int color) {
    }

    private static final Set<String> KEYWORDS = Set.of(
            "if", "else", "for", "while", "do", "break", "continue", "return", "discard",
            "switch", "case", "default", "struct", "const", "uniform", "in", "out", "inout",
            "layout", "flat", "smooth", "noperspective", "precision", "highp", "mediump", "lowp",
            "true", "false");

    private static final Set<String> TYPES = Set.of(
            "void", "bool", "int", "uint", "float", "double",
            "vec2", "vec3", "vec4", "ivec2", "ivec3", "ivec4", "bvec2", "bvec3", "bvec4",
            "uvec2", "uvec3", "uvec4",
            "mat2", "mat3", "mat4", "mat2x2", "mat2x3", "mat2x4",
            "mat3x2", "mat3x3", "mat3x4", "mat4x2", "mat4x3", "mat4x4",
            "sampler2D", "sampler2DShadow", "sampler2DArray", "sampler3D", "samplerCube",
            "isampler2D", "usampler2D");

    /**
     * 着色语言内建函数 + 本工具注入的量。
     *
     * <p>后一半是「作者写得出、但不是他自己声明的」名字——生成头部里的 sampler、
     * 别名宏，以及原版 {@code Globals} 块的成员。它们和作者自己的变量长得一样，
     * 高亮里区分开才看得出哪些是免费拿到的、哪些得自己算。
     */
    private static final Set<String> BUILTINS = Set.of(
            // --- GLSL 内建函数 ---
            "texture", "textureLod", "textureSize", "texelFetch", "textureProj", "textureGrad",
            "textureOffset", "mix", "clamp", "smoothstep", "step", "abs", "sign", "floor",
            "ceil", "round", "trunc", "fract", "mod", "modf", "min", "max", "pow", "exp",
            "log", "exp2", "log2", "sqrt", "inversesqrt", "sin", "cos", "tan", "asin", "acos",
            "atan", "sinh", "cosh", "tanh", "radians", "degrees",
            "length", "distance", "dot", "cross", "normalize", "reflect", "refract",
            "faceforward", "transpose", "inverse", "determinant", "matrixCompMult", "outerProduct",
            "lessThan", "lessThanEqual", "greaterThan", "greaterThanEqual", "equal", "notEqual",
            "all", "any", "not", "isnan", "isinf",
            "dFdx", "dFdy", "fwidth",
            "gl_FragCoord", "gl_FragDepth", "gl_FrontFacing", "gl_PointCoord",
            "gl_Position", "gl_VertexID", "gl_FragColor",
            // --- 由生成头部提供的输入输出 ---
            "fragColor", "texCoord", "InSampler", "OutSize", "InSize",
            // --- GTShaders 自己驱动的量 ---
            "GTTime", "GTDeltaTime", "GTFrame", "GTPlaying",
            "GTStrength", "GTLayerIndex", "GTLayerCount",
            "GTViewport", "GTViewportUV",
            // --- Shadertoy 风格别名 ---
            "iTime", "iTimeDelta", "iResolution", "iChannel0",
            // --- 原版 Globals 块成员 ---
            "CameraBlockPos", "CameraOffset", "ScreenSize", "GlintAlpha", "GameTime",
            "MenuBlurRadius", "UseRgss");

    private GlslHighlighter() {
    }

    /** 对一行源码分段着色。返回的 span 覆盖整行且互不重叠。 */
    public static List<Span> highlight(String line) {
        List<Span> spans = new ArrayList<>();
        int n = line.length();
        int i = 0;

        // 行注释和我们的参数注解优先判断：@param 用不同的颜色，让作者一眼看出哪些注释是"活的"。
        int commentAt = line.indexOf("//");
        if (commentAt == 0 || (commentAt > 0 && !insideString(line, commentAt))) {
            if (commentAt > 0) {
                spans.addAll(highlightCode(line.substring(0, commentAt), 0));
            }
            String comment = line.substring(commentAt);
            boolean isAnnotation = comment.replaceFirst("^//\\s*", "").toLowerCase(java.util.Locale.ROOT).startsWith("@param");
            spans.add(new Span(commentAt, n, isAnnotation ? Theme.SYN_ANNOTATION : Theme.SYN_COMMENT));
            return spans;
        }

        String trimmed = line.stripLeading();
        if (trimmed.startsWith("#")) {
            spans.add(new Span(0, n, Theme.SYN_PREPROC));
            return spans;
        }
        if (trimmed.startsWith("/*") || trimmed.startsWith("*")) {
            spans.add(new Span(0, n, Theme.SYN_COMMENT));
            return spans;
        }

        spans.addAll(highlightCode(line, 0));
        return spans;
    }

    private static List<Span> highlightCode(String text, int offset) {
        List<Span> spans = new ArrayList<>();
        int n = text.length();
        int i = 0;
        while (i < n) {
            char c = text.charAt(i);
            if (Character.isJavaIdentifierStart(c)) {
                int start = i;
                while (i < n && (Character.isJavaIdentifierPart(text.charAt(i)))) {
                    i++;
                }
                String word = text.substring(start, i);
                int color = Theme.SYN_PLAIN;
                if (KEYWORDS.contains(word)) {
                    color = Theme.SYN_KEYWORD;
                } else if (TYPES.contains(word)) {
                    color = Theme.SYN_TYPE;
                } else if (BUILTINS.contains(word)) {
                    color = Theme.SYN_BUILTIN;
                }
                spans.add(new Span(offset + start, offset + i, color));
            } else if (Character.isDigit(c)) {
                int start = i;
                while (i < n && (Character.isLetterOrDigit(text.charAt(i)) || text.charAt(i) == '.')) {
                    i++;
                }
                spans.add(new Span(offset + start, offset + i, Theme.SYN_NUMBER));
            } else {
                int start = i;
                i++;
                spans.add(new Span(offset + start, offset + i, Theme.SYN_PLAIN));
            }
        }
        return spans;
    }

    /** 粗略判断 {@code //} 是否落在字符串字面量里。GLSL 没有字符串，所以这里恒为 false，留作扩展点。 */
    private static boolean insideString(String line, int index) {
        return false;
    }
}
