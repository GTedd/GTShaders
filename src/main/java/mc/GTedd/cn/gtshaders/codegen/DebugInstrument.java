package mc.GTedd.cn.gtshaders.codegen;

import mc.GTedd.cn.gtshaders.core.ParamType;
import mc.GTedd.cn.gtshaders.core.ShaderParam;
import org.jspecify.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 调试插桩：把作者源码改写成「顺便把某个值带出来」的版本，交给 GPU 真算。
 *
 * <h2>为什么不在 CPU 上模拟</h2>
 *
 * <p>SHADERed 的调试器在 CPU 上用 SPIR-V 虚拟机重跑片元着色器，而屏幕上的颜色是驱动算的，
 * 两边可能对不上——它的 VM 采样只有最近邻，Minecraft 的后处理链却大量用线性过滤。
 * 这里反过来：值由游戏里真正跑着色器的那块 GPU 算出来，再回读一个像素。
 * 代价是只能看值、不能单步。
 *
 * <p>思路借鉴 SHADERed（dfranx，MIT）的调试器与 SPIRV-VM 的异常类型清单，未复制代码。
 * 没有照搬它的 CPU 虚拟机，是因为移植量大（约 5000 行 C），且 VM 的采样与 GPU 必然对不上。
 *
 * <h2>两种插桩</h2>
 *
 * <ul>
 *   <li>{@link #probe 变量探针}：在某一行之后插一句 {@code gtDbgCapture(表达式);}，
 *       记下「这个像素第一次执行到这一行时」表达式的值。插在循环、辅助函数里都成立。</li>
 *   <li>{@link #ub 异常检查}：把 {@code pow} {@code sqrt} {@code smoothstep} 这些在参数越界时
 *       结果未定义的调用换成带检查的包装函数，记下第一次出事的类型与行号；
 *       输出为 NaN/Inf 的像素另记一种。清单取自 SHADERed 的 SPIRV-VM analyzer。</li>
 * </ul>
 *
 * <p><b>两种改写都不增删行</b>：插入的文字写在原有行内，编译器报的行号仍然对得上作者源码。
 *
 * <h2>输出怎么带出来</h2>
 *
 * <p>主缓冲是 8 位的，直接写一个 float 会被夹到 0..1 再量化。所以探针用
 * {@code floatBitsToUint} 把一个分量按位拆成 RGBA 四个字节写出，回读后逐位还原——无损。
 * 一帧只能带一个分量，于是由隐藏参数 {@link #SEL_PARAM} 选这一帧输出什么，
 * 运行时逐帧切换（见 {@code runtime/DebugSession}）。不回读的时候它停在「伪彩色」，
 * 作者直接在画面上看到这个值的分布。
 */
public final class DebugInstrument {

    /** 选择这一帧输出什么的隐藏参数。 */
    public static final String SEL_PARAM = "gtDbgSel";
    /** 伪彩色的取值范围（lo, hi）。 */
    public static final String RANGE_PARAM = "gtDbgRange";

    /** 探针：伪彩色显示。默认值，也就是没在回读时画面上看到的东西。 */
    public static final int SEL_VIEW = 0;
    /** 探针：元信息字节（是否执行到、分量数、类型）。 */
    public static final int SEL_META = 1;
    /** 探针：第 0 个分量的按位字节。第 k 个分量是 {@code SEL_COMPONENT + k}。 */
    public static final int SEL_COMPONENT = 2;
    /** 异常检查：精确编码（类型、行号、次数）。{@link #SEL_VIEW} 时显示异常图。 */
    public static final int SEL_UB_EXACT = 1;

    public enum Mode {
        VALUE, UB
    }

    /**
     * @param body   改写后的作者源码，行数与原来相同
     * @param hook   交给 {@link GlslCodegen} 的钩子
     * @param line   探针插在第几行（1 起）；异常检查为 0
     * @param rewrites 异常检查改写了多少处调用；探针为 0
     */
    public record Instrumented(Mode mode, String body, GlslCodegen.Hook hook, int line, int rewrites) {
        public ShaderParam sel() {
            return hook.params().get(0);
        }

        public @Nullable ShaderParam range() {
            return hook.params().size() > 1 ? hook.params().get(1) : null;
        }
    }

    /**
     * 插桩结果。失败时 {@code value} 为 null，{@code errorKey} 是语言键，{@code args} 是它的参数。
     */
    public record Outcome(@Nullable Instrumented value, @Nullable String errorKey, Object[] args) {
        static Outcome ok(Instrumented v) {
            return new Outcome(v, null, new Object[0]);
        }

        static Outcome fail(String key, Object... args) {
            return new Outcome(null, key, args);
        }

        public boolean ok() {
            return value != null;
        }
    }

    private DebugInstrument() {
    }

    // ================================================================ 变量探针

    /** 写在语句前面而不是后面的关键字：插在 {@code return} 之后的代码永远执行不到。 */
    private static final Set<String> JUMPS = Set.of("return", "break", "continue", "discard");

    /**
     * 在第 {@code line} 行插一个探针，记录 {@code expression} 的值。
     *
     * <p>插在哪：该行最后一个代码 token 之后（行尾注释之前）；该行以 {@code return} 这类跳转开头时
     * 插在它前面；空行插在行首。无论哪种，插入点都必须是语句边界、在函数体里、不在括号里，
     * 并且后面紧跟的不是 {@code else} / {@code while}——否则插进去的语句会把 if/else 拆散。
     */
    public static Outcome probe(String body, int line, String expression) {
        String expr = expression == null ? "" : expression.strip();
        if (expr.isEmpty()) {
            return Outcome.fail("gtshaders.debug.err.empty_expr");
        }
        if (expr.contains(";") || expr.contains("{") || expr.contains("}") || expr.contains("\n")
                || expr.contains("//") || expr.contains("/*") || expr.contains("#")) {
            return Outcome.fail("gtshaders.debug.err.bad_expr");
        }
        String src = body == null ? "" : body;
        int lineCount = 1;
        for (int i = 0; i < src.length(); i++) {
            if (src.charAt(i) == '\n') {
                lineCount++;
            }
        }
        if (line < 1 || line > lineCount) {
            return Outcome.fail("gtshaders.debug.err.line_range", line, lineCount);
        }

        List<GlslLexer.Token> all = GlslLexer.tokenize(src);
        List<GlslLexer.Token> onLine = new ArrayList<>();
        for (GlslLexer.Token t : all) {
            if (t.isCode() && t.line() == line) {
                onLine.add(t);
            }
        }

        int offset;
        boolean before;
        if (onLine.isEmpty()) {
            // 整行被块注释或预处理指令占着：插进去要么被注释吞掉，要么把宏改坏
            for (GlslLexer.Token t : all) {
                if (!t.isCode() && t.kind() != GlslLexer.Kind.LINE_COMMENT
                        && t.line() <= line && t.endLine() >= line) {
                    return Outcome.fail("gtshaders.debug.err.comment_line", line);
                }
            }
            offset = lineStart(src, line);
            before = true;
        } else if (JUMPS.contains(onLine.get(0).text())) {
            offset = onLine.get(0).start();
            before = true;
        } else {
            offset = onLine.get(onLine.size() - 1).end();
            before = false;
        }

        String problem = checkInsertion(all, offset);
        if (problem != null) {
            return Outcome.fail(problem, line);
        }

        String capture = "gtDbgCapture(" + expr + ");";
        String inserted = before ? capture + " " : " " + capture;
        String newBody = src.substring(0, offset) + inserted + src.substring(offset);

        ShaderParam sel = hiddenParam(SEL_PARAM, ParamType.INT, 0f, 16f, SEL_VIEW);
        ShaderParam range = new ShaderParam(RANGE_PARAM, ParamType.VEC2, -1e9f, 1e9f);
        range.setDefaults(new float[]{0f, 1f});
        range.resetToDefault();
        GlslCodegen.Hook hook = new GlslCodegen.Hook(VALUE_HEADER, VALUE_TAIL, List.of(sel, range));
        return Outcome.ok(new Instrumented(Mode.VALUE, newBody, hook, line, 0));
    }

    private static int lineStart(String src, int line) {
        int current = 1;
        for (int i = 0; i < src.length(); i++) {
            if (current == line) {
                return i;
            }
            if (src.charAt(i) == '\n') {
                current++;
            }
        }
        return src.length();
    }

    /**
     * 判断 {@code offset} 处能不能插一条语句。
     *
     * @return null 表示可以；否则是错误的语言键
     */
    private static @Nullable String checkInsertion(List<GlslLexer.Token> all, int offset) {
        int paren = 0;
        int bracket = 0;
        // true = 代码块（函数体、if/for 的块、嵌套块）；false = struct / uniform 块这类声明
        Deque<Boolean> blocks = new ArrayDeque<>();
        GlslLexer.Token prev = null;
        GlslLexer.Token next = null;
        for (GlslLexer.Token t : all) {
            if (!t.isCode()) {
                continue;
            }
            if (t.start() >= offset) {
                next = t;
                break;
            }
            switch (t.text()) {
                case "(" -> paren++;
                case ")" -> paren = Math.max(0, paren - 1);
                case "[" -> bracket++;
                case "]" -> bracket = Math.max(0, bracket - 1);
                case "{" -> blocks.push(opensCodeBlock(prev, blocks));
                case "}" -> {
                    if (!blocks.isEmpty()) {
                        blocks.pop();
                    }
                }
                default -> {
                }
            }
            prev = t;
        }
        if (blocks.isEmpty() || !blocks.peek()) {
            return "gtshaders.debug.err.outside_function";
        }
        if (paren > 0 || bracket > 0 || prev == null
                || !(prev.is(";") || prev.is("{") || prev.is("}"))) {
            return "gtshaders.debug.err.not_statement";
        }
        if (next != null && (next.is("else") || next.is("while"))) {
            return "gtshaders.debug.err.not_statement";
        }
        return null;
    }

    private static boolean opensCodeBlock(GlslLexer.@Nullable Token prev, Deque<Boolean> blocks) {
        if (prev == null) {
            return false;
        }
        String p = prev.text();
        if (p.equals(")") || p.equals("else") || p.equals("do")) {
            return true;
        }
        if (p.equals("{") || p.equals("}") || p.equals(";")) {
            // 裸块：只有已经在代码块里时才算代码块
            return !blocks.isEmpty() && blocks.peek();
        }
        // struct Foo {、uniform Block {、= { ... } 之类
        return false;
    }

    private static ShaderParam hiddenParam(String name, ParamType type, float min, float max, float def) {
        ShaderParam p = new ShaderParam(name, type, min, max);
        p.setDefaults(new float[]{def});
        p.resetToDefault();
        return p;
    }

    private static final String VALUE_HEADER = """

            // ---- GTShaders 调试探针（只在编辑器的调试视图里注入，导出物里没有）----
            vec4 gtDbgValue = vec4(0.0);
            int gtDbgKind = 0;
            int gtDbgType = 0;
            bool gtDbgHit = false;
            void gtDbgStore(vec4 v, int kind, int type) {
                if (!gtDbgHit) {
                    gtDbgValue = v;
                    gtDbgKind = kind;
                    gtDbgType = type;
                    gtDbgHit = true;
                }
            }
            void gtDbgCapture(float v) { gtDbgStore(vec4(v, 0.0, 0.0, 0.0), 1, 0); }
            void gtDbgCapture(vec2 v) { gtDbgStore(vec4(v, 0.0, 0.0), 2, 0); }
            void gtDbgCapture(vec3 v) { gtDbgStore(vec4(v, 0.0), 3, 0); }
            void gtDbgCapture(vec4 v) { gtDbgStore(v, 4, 0); }
            void gtDbgCapture(int v) { gtDbgStore(vec4(float(v), 0.0, 0.0, 0.0), 1, 1); }
            void gtDbgCapture(ivec2 v) { gtDbgStore(vec4(vec2(v), 0.0, 0.0), 2, 1); }
            void gtDbgCapture(ivec3 v) { gtDbgStore(vec4(vec3(v), 0.0), 3, 1); }
            void gtDbgCapture(ivec4 v) { gtDbgStore(vec4(v), 4, 1); }
            void gtDbgCapture(uint v) { gtDbgStore(vec4(float(v), 0.0, 0.0, 0.0), 1, 2); }
            void gtDbgCapture(bool v) { gtDbgStore(vec4(v ? 1.0 : 0.0, 0.0, 0.0, 0.0), 1, 3); }
            // 一个 float 按位拆成四个字节。8 位缓冲存 k/255 再读回来恰好是 k，所以无损
            vec4 gtDbgBytes(float v) {
                uint b = floatBitsToUint(v);
                return vec4(float((b >> 24u) & 255u), float((b >> 16u) & 255u),
                            float((b >> 8u) & 255u), float(b & 255u)) / 255.0;
            }
            vec3 gtDbgHeat(float t) {
                t = clamp(t, 0.0, 1.0) * 4.0;
                if (t < 1.0) return mix(vec3(0.0, 0.0, 1.0), vec3(0.0, 1.0, 1.0), t);
                if (t < 2.0) return mix(vec3(0.0, 1.0, 1.0), vec3(0.0, 1.0, 0.0), t - 1.0);
                if (t < 3.0) return mix(vec3(0.0, 1.0, 0.0), vec3(1.0, 1.0, 0.0), t - 2.0);
                return mix(vec3(1.0, 1.0, 0.0), vec3(1.0, 0.0, 0.0), t - 3.0);
            }
            vec3 gtDbgFalseColor(vec2 px) {
                if (!gtDbgHit) {
                    // 没执行到这一行的像素画成灰色棋盘，和「值恰好是 0」一眼分得开
                    float c = mod(floor(px.x / 8.0) + floor(px.y / 8.0), 2.0);
                    return vec3(0.16 + 0.08 * c);
                }
                for (int i = 0; i < gtDbgKind; i++) {
                    if (isnan(gtDbgValue[i])) return vec3(1.0, 0.0, 1.0);
                    if (isinf(gtDbgValue[i])) return vec3(1.0);
                }
                float lo = gtDbgRange.x;
                float span = gtDbgRange.y - gtDbgRange.x;
                if (abs(span) < 1e-8) span = 1.0;
                if (gtDbgKind == 1) return gtDbgHeat((gtDbgValue.x - lo) / span);
                vec3 rgb = clamp((gtDbgValue.rgb - lo) / span, 0.0, 1.0);
                if (gtDbgKind == 2) rgb.b = 0.0;
                return rgb;
            }
            """;

    private static final String VALUE_TAIL = """
                if (gtDbgSel == 1) {
                    fragColor = vec4(gtDbgHit ? 1.0 : 0.0, float(gtDbgKind) / 255.0,
                                     float(gtDbgType) / 255.0, 1.0);
                } else if (gtDbgSel >= 2 && gtDbgSel <= 5) {
                    fragColor = gtDbgBytes(gtDbgValue[gtDbgSel - 2]);
                } else {
                    fragColor = vec4(gtDbgFalseColor(gl_FragCoord.xy), 1.0);
                }
            """;

    // ================================================================ 异常检查

    /**
     * 异常类型。编号写进输出字节，语言键是 {@code gtshaders.debug.ub.<id>}。
     *
     * <p>{@code smoothstep} 只查两个边界<b>相等</b>（除零），不查反写：库里有几十处
     * {@code smoothstep(0.4, 0.0, x)} 这种写法，按规范是未定义，但主流实现都按公式算出了
     * 作者想要的结果。全标出来的话异常图会被它们铺满，真正的除零反而看不见。
     */
    public enum UbKind {
        NONE(0), POW(1), SQRT(2), INVERSESQRT(3), LOG(4), LOG2(5), ASIN(6), ACOS(7),
        ACOSH(8), ATANH(9), ATAN2(10), SMOOTHSTEP(11), CLAMP(12), NORMALIZE(13), MOD(14), NAN_OUT(15);

        public final int code;

        UbKind(int code) {
            this.code = code;
        }

        public String translationKey() {
            return "gtshaders.debug.ub." + name().toLowerCase(java.util.Locale.ROOT);
        }

        public static UbKind of(int code) {
            for (UbKind k : values()) {
                if (k.code == code) {
                    return k;
                }
            }
            return NONE;
        }
    }

    /** 要改写的内置函数 → 包装函数名。 */
    private static final Map<String, String> WRAPPED = Map.ofEntries(
            Map.entry("pow", "gtUbPow"), Map.entry("sqrt", "gtUbSqrt"),
            Map.entry("inversesqrt", "gtUbInversesqrt"), Map.entry("log", "gtUbLog"),
            Map.entry("log2", "gtUbLog2"), Map.entry("asin", "gtUbAsin"), Map.entry("acos", "gtUbAcos"),
            Map.entry("acosh", "gtUbAcosh"), Map.entry("atanh", "gtUbAtanh"), Map.entry("atan", "gtUbAtan"),
            Map.entry("smoothstep", "gtUbSmoothstep"), Map.entry("clamp", "gtUbClamp"),
            Map.entry("normalize", "gtUbNormalize"), Map.entry("mod", "gtUbMod"));

    /** 函数名之前出现这些词时仍然是一次调用，而不是声明。 */
    private static final Set<String> CALL_KEYWORDS = Set.of("return");

    /**
     * 异常检查插桩：改写危险调用，输出第一次出事的类型与行号。
     */
    public static Outcome ub(String body) {
        String src = body == null ? "" : body;
        List<GlslLexer.Token> code = GlslLexer.code(src);
        record Edit(int start, int end, String text) {
        }
        List<Edit> edits = new ArrayList<>();
        for (int i = 0; i < code.size(); i++) {
            GlslLexer.Token t = code.get(i);
            String wrapper = WRAPPED.get(t.text());
            if (wrapper == null || t.kind() != GlslLexer.Kind.IDENT) {
                continue;
            }
            if (i + 1 >= code.size() || !code.get(i + 1).is("(")) {
                continue;
            }
            if (i > 0) {
                GlslLexer.Token p = code.get(i - 1);
                // x.pow( 不是内置函数；float pow( 是在声明同名函数
                if (p.is(".") || (p.kind() == GlslLexer.Kind.IDENT && !CALL_KEYWORDS.contains(p.text()))) {
                    continue;
                }
            }
            GlslLexer.Token paren = code.get(i + 1);
            edits.add(new Edit(t.start(), paren.end(), wrapper + "(" + t.line() + ", "));
        }
        StringBuilder sb = new StringBuilder(src);
        for (int i = edits.size() - 1; i >= 0; i--) {
            Edit e = edits.get(i);
            sb.replace(e.start(), e.end(), e.text());
        }
        ShaderParam sel = hiddenParam(SEL_PARAM, ParamType.INT, 0f, 16f, SEL_VIEW);
        GlslCodegen.Hook hook = new GlslCodegen.Hook(UB_HEADER, UB_TAIL, List.of(sel));
        return Outcome.ok(new Instrumented(Mode.UB, sb.toString(), hook, 0, edits.size()));
    }

    private static final String UB_TAIL = """
                if (gtUbCode == 0 && (any(isnan(fragColor)) || any(isinf(fragColor)))) {
                    gtUbCode = 15;
                }
                if (gtDbgSel == 1) {
                    fragColor = vec4(float(gtUbCode), float((gtUbLine >> 8) & 255),
                                     float(gtUbLine & 255), float(min(gtUbCount, 255))) / 255.0;
                } else if (gtUbCode != 0) {
                    // 出事的像素：紫红是参数越界，黄色是输出成了 NaN/Inf
                    fragColor = vec4(gtUbCode == 15 ? vec3(1.0, 0.85, 0.0) : vec3(1.0, 0.0, 0.85), 1.0);
                } else {
                    vec3 shown = gtInside ? gtBlend(gtBase.rgb, fragColor.rgb) : gtBase.rgb;
                    fragColor = vec4(shown * 0.35, 1.0);
                }
            """;

    private static final String UB_HEADER = buildUbHeader();

    private static String buildUbHeader() {
        StringBuilder sb = new StringBuilder();
        sb.append("""

                // ---- GTShaders 异常检查（只在编辑器的调试视图里注入，导出物里没有）----
                int gtUbCode = 0;
                int gtUbLine = 0;
                int gtUbCount = 0;
                void gtUbHit(int code, int line) {
                    if (gtUbCode == 0) {
                        gtUbCode = code;
                        gtUbLine = line;
                    }
                    gtUbCount++;
                }
                """);
        unary(sb, "sqrt", "gtUbSqrt", UbKind.SQRT, "v < 0.0");
        unary(sb, "inversesqrt", "gtUbInversesqrt", UbKind.INVERSESQRT, "v <= 0.0");
        unary(sb, "log", "gtUbLog", UbKind.LOG, "v <= 0.0");
        unary(sb, "log2", "gtUbLog2", UbKind.LOG2, "v <= 0.0");
        unary(sb, "asin", "gtUbAsin", UbKind.ASIN, "abs(v) > 1.0");
        unary(sb, "acos", "gtUbAcos", UbKind.ACOS, "abs(v) > 1.0");
        unary(sb, "acosh", "gtUbAcosh", UbKind.ACOSH, "v < 1.0");
        unary(sb, "atanh", "gtUbAtanh", UbKind.ATANH, "abs(v) >= 1.0");

        // pow(x, y)：x < 0，或 x == 0 且 y <= 0
        sb.append("bool gtUbBadPow(float x, float y) { return x < 0.0 || (x == 0.0 && y <= 0.0); }\n");
        for (int n = 1; n <= 4; n++) {
            String t = ftype(n);
            sb.append(t).append(" gtUbPow(int l, ").append(t).append(" x, ").append(t).append(" y) {\n")
                    .append("    if (").append(anyOf(n, (m, i) -> "gtUbBadPow(" + c("x", m, i) + ", " + c("y", m, i) + ")"))
                    .append(") gtUbHit(").append(UbKind.POW.code).append(", l);\n")
                    .append("    return pow(x, y);\n}\n");
        }

        // atan：单参数原样转发，双参数查 y == x == 0
        for (int n = 1; n <= 4; n++) {
            String t = ftype(n);
            sb.append(t).append(" gtUbAtan(int l, ").append(t).append(" y) { return atan(y); }\n");
            sb.append(t).append(" gtUbAtan(int l, ").append(t).append(" y, ").append(t).append(" x) {\n")
                    .append("    if (").append(anyOf(n, (m, i) -> "(" + c("y", m, i) + " == 0.0 && " + c("x", m, i) + " == 0.0)"))
                    .append(") gtUbHit(").append(UbKind.ATAN2.code).append(", l);\n")
                    .append("    return atan(y, x);\n}\n");
        }

        // smoothstep：只查边界相等（除零）
        for (int n = 1; n <= 4; n++) {
            String t = ftype(n);
            sb.append(t).append(" gtUbSmoothstep(int l, float e0, float e1, ").append(t).append(" x) {\n")
                    .append("    if (e0 == e1) gtUbHit(").append(UbKind.SMOOTHSTEP.code).append(", l);\n")
                    .append("    return smoothstep(e0, e1, x);\n}\n");
            if (n > 1) {
                sb.append(t).append(" gtUbSmoothstep(int l, ").append(t).append(" e0, ").append(t)
                        .append(" e1, ").append(t).append(" x) {\n")
                        .append("    if (").append(anyOf(n, (m, i) -> c("e0", m, i) + " == " + c("e1", m, i)))
                        .append(") gtUbHit(").append(UbKind.SMOOTHSTEP.code).append(", l);\n")
                        .append("    return smoothstep(e0, e1, x);\n}\n");
            }
        }

        // clamp：min > max。float / int / uint 三族，各有「分量边界」与「标量边界」两种重载
        String[][] families = {{"float", "vec"}, {"int", "ivec"}, {"uint", "uvec"}};
        for (String[] fam : families) {
            for (int n = 1; n <= 4; n++) {
                String t = n == 1 ? fam[0] : fam[1] + n;
                sb.append(t).append(" gtUbClamp(int l, ").append(t).append(" x, ").append(t)
                        .append(" lo, ").append(t).append(" hi) {\n")
                        .append("    if (").append(anyOf(n, (m, i) -> c("lo", m, i) + " > " + c("hi", m, i)))
                        .append(") gtUbHit(").append(UbKind.CLAMP.code).append(", l);\n")
                        .append("    return clamp(x, lo, hi);\n}\n");
                if (n > 1) {
                    sb.append(t).append(" gtUbClamp(int l, ").append(t).append(" x, ").append(fam[0])
                            .append(" lo, ").append(fam[0]).append(" hi) {\n")
                            .append("    if (lo > hi) gtUbHit(").append(UbKind.CLAMP.code).append(", l);\n")
                            .append("    return clamp(x, lo, hi);\n}\n");
                }
            }
        }

        // normalize：零向量
        for (int n = 1; n <= 4; n++) {
            String t = ftype(n);
            String zero = n == 1 ? "v == 0.0" : "dot(v, v) == 0.0";
            sb.append(t).append(" gtUbNormalize(int l, ").append(t).append(" v) {\n")
                    .append("    if (").append(zero).append(") gtUbHit(").append(UbKind.NORMALIZE.code).append(", l);\n")
                    .append("    return normalize(v);\n}\n");
        }

        // mod(x, y)：y == 0
        for (int n = 1; n <= 4; n++) {
            String t = ftype(n);
            sb.append(t).append(" gtUbMod(int l, ").append(t).append(" x, float y) {\n")
                    .append("    if (y == 0.0) gtUbHit(").append(UbKind.MOD.code).append(", l);\n")
                    .append("    return mod(x, y);\n}\n");
            if (n > 1) {
                sb.append(t).append(" gtUbMod(int l, ").append(t).append(" x, ").append(t).append(" y) {\n")
                        .append("    if (").append(anyOf(n, (m, i) -> c("y", m, i) + " == 0.0"))
                        .append(") gtUbHit(").append(UbKind.MOD.code).append(", l);\n")
                        .append("    return mod(x, y);\n}\n");
            }
        }
        return sb.toString();
    }

    private static void unary(StringBuilder sb, String fn, String wrapper, UbKind kind, String badScalar) {
        sb.append("bool gtUbBad_").append(fn).append("(float v) { return ").append(badScalar).append("; }\n");
        for (int n = 1; n <= 4; n++) {
            String t = ftype(n);
            sb.append(t).append(' ').append(wrapper).append("(int l, ").append(t).append(" x) {\n")
                    .append("    if (").append(anyOf(n, (m, i) -> "gtUbBad_" + fn + "(" + c("x", m, i) + ")"))
                    .append(") gtUbHit(").append(kind.code).append(", l);\n")
                    .append("    return ").append(fn).append("(x);\n}\n");
        }
    }

    private static String ftype(int n) {
        return n == 1 ? "float" : "vec" + n;
    }

    private static String c(String v, int n, int i) {
        return n == 1 ? v : v + "[" + i + "]";
    }

    private interface Term {
        String at(int n, int i);
    }

    private static String anyOf(int n, Term term) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) {
            if (i > 0) {
                sb.append(" || ");
            }
            sb.append(term.at(n, i));
        }
        return sb.toString();
    }

    // ================================================================ 回读解码

    /** 探针元信息：这个像素执行到没有、值有几个分量、是什么类型（0 float · 1 int · 2 uint · 3 bool）。 */
    public record ProbeMeta(boolean hit, int components, int type) {
        public static ProbeMeta decode(int r, int g, int b) {
            return new ProbeMeta(r >= 128, Math.max(0, Math.min(4, g)), b);
        }
    }

    /** 四个字节按大端还原成一个 float——与 {@code gtDbgBytes} 的拆法对应。 */
    public static float decodeFloat(int r, int g, int b, int a) {
        int bits = ((r & 255) << 24) | ((g & 255) << 16) | ((b & 255) << 8) | (a & 255);
        return Float.intBitsToFloat(bits);
    }

    /** 异常检查的一个像素：类型、第一次出事的作者行号（0 表示不在作者代码里）、次数（封顶 255）。 */
    public record UbSample(UbKind kind, int line, int count) {
        public static UbSample decode(int r, int g, int b, int a) {
            return new UbSample(UbKind.of(r & 255), ((g & 255) << 8) | (b & 255), a & 255);
        }
    }
}
