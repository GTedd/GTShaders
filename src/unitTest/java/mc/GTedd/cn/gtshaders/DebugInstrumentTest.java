package mc.GTedd.cn.gtshaders;

import mc.GTedd.cn.gtshaders.codegen.DebugInstrument;
import mc.GTedd.cn.gtshaders.codegen.GlslCodegen;
import mc.GTedd.cn.gtshaders.codegen.GlslLexer;
import mc.GTedd.cn.gtshaders.codegen.PostEffectJsonBuilder;
import mc.GTedd.cn.gtshaders.core.GtProfile;
import mc.GTedd.cn.gtshaders.core.ShaderLayer;
import mc.GTedd.cn.gtshaders.core.ShaderProject;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 调试插桩的纯文本部分：插在哪、改写了哪些调用、生成物的结构。
 *
 * <p>「插进去之后 ShaderC 编不编得过」由 {@code checkShaderLib} 负责——它会把素材库里每个效果
 * 都插一遍桩再真编译。这里钉住的是那些编译器发现不了的错：插在注释里（编译通过、探针永远不命中）、
 * 把 if/else 拆散（编译报错指着作者的代码，作者会以为是自己写错了）、行数变了（报错行号全部偏移）。
 */
class DebugInstrumentTest {

    private static final String BODY = """
            // 头注释
            float lum(vec3 c) {
                return dot(c, vec3(0.2126, 0.7152, 0.0722));
            }

            void main() {
                vec4 src = texture(InSampler, texCoord);
                float l = lum(src.rgb); // 亮度
                if (l > 0.5) {
                    l = 1.0;
                }
                else {
                    l = 0.0;
                }
                /* 块注释
                   第二行 */
                fragColor = vec4(vec3(l), 1.0);
            }
            """;

    private static long lines(String s) {
        return s.chars().filter(c -> c == '\n').count();
    }

    private static String probeBody(int line, String expr) {
        DebugInstrument.Outcome o = DebugInstrument.probe(BODY, line, expr);
        assertTrue(o.ok(), () -> "第 " + line + " 行应当能插：" + o.errorKey());
        String out = o.value().body();
        assertEquals(lines(BODY), lines(out), "插桩不能增删行，否则报错行号全部偏移");
        return out;
    }

    private static String lineOf(String src, int line) {
        return src.split("\n", -1)[line - 1];
    }

    @Test
    void 插在语句之后且在行尾注释之前() {
        String out = probeBody(8, "l");
        assertEquals("    float l = lum(src.rgb); gtDbgCapture(l); // 亮度", lineOf(out, 8));
    }

    @Test
    void return开头的行插在它前面() {
        String out = probeBody(3, "c");
        assertTrue(lineOf(out, 3).strip().startsWith("gtDbgCapture(c); return"), lineOf(out, 3));
    }

    @Test
    void 函数开头那行插在左花括号之后() {
        String out = probeBody(6, "texCoord");
        assertEquals("void main() { gtDbgCapture(texCoord);", lineOf(out, 6));
    }

    @Test
    void 函数体里的空行也能插() {
        String out = DebugInstrument.probe(BODY.replace("    /* 块注释", "\n    /* 块注释"), 15, "l")
                .value().body();
        assertEquals("gtDbgCapture(l); ", lineOf(out, 15));
    }

    @Test
    void 右花括号后面紧跟else时拒绝() {
        DebugInstrument.Outcome o = DebugInstrument.probe(BODY, 11, "l");
        assertFalse(o.ok(), "插在 } 与 else 之间会把 if/else 拆散");
        assertEquals("gtshaders.debug.err.not_statement", o.errorKey());
    }

    @Test
    void 函数外面拒绝() {
        assertEquals("gtshaders.debug.err.outside_function",
                DebugInstrument.probe(BODY, 4, "1.0").errorKey(), "函数结束那一行之后已经是全局作用域");
        assertEquals("gtshaders.debug.err.outside_function",
                DebugInstrument.probe(BODY, 1, "1.0").errorKey());
    }

    @Test
    void 块注释里的行拒绝() {
        assertEquals("gtshaders.debug.err.comment_line",
                DebugInstrument.probe(BODY, 16, "l").errorKey());
    }

    @Test
    void 跨行表达式中间拒绝() {
        String body = """
                void main() {
                    float a = 1.0 +
                        2.0;
                    fragColor = vec4(a);
                }
                """;
        assertEquals("gtshaders.debug.err.not_statement",
                DebugInstrument.probe(body, 2, "a").errorKey());
        assertTrue(DebugInstrument.probe(body, 3, "a").ok());
    }

    @Test
    void struct里面不算函数体() {
        String body = """
                struct Ray {
                    vec3 o;
                };
                void main() {
                    fragColor = vec4(1.0);
                }
                """;
        assertEquals("gtshaders.debug.err.outside_function",
                DebugInstrument.probe(body, 2, "1.0").errorKey());
    }

    @Test
    void 表达式不能带语句或注释() {
        assertEquals("gtshaders.debug.err.bad_expr", DebugInstrument.probe(BODY, 8, "l; l = 0.0").errorKey());
        assertEquals("gtshaders.debug.err.empty_expr", DebugInstrument.probe(BODY, 8, "  ").errorKey());
        assertEquals("gtshaders.debug.err.line_range", DebugInstrument.probe(BODY, 999, "l").errorKey());
    }

    @Test
    void 异常检查改写调用但跳过注释声明与成员访问() {
        String body = """
                // pow(x, 2.0) 写在注释里不算
                float pow2(float x) { return pow(x, 2.0); }
                #define SQ(x) sqrt(x)
                void main() {
                    float a = sqrt(texCoord.x) + smoothstep(0.0, 1.0, texCoord.y);
                    fragColor = vec4(clamp(vec3(a), 0.0, 1.0), 1.0);
                }
                """;
        DebugInstrument.Outcome o = DebugInstrument.ub(body);
        assertTrue(o.ok());
        String out = o.value().body();
        assertEquals(lines(body), lines(out));
        assertEquals(4, o.value().rewrites());
        assertTrue(out.contains("// pow(x, 2.0) 写在注释里不算"));
        assertTrue(out.contains("float pow2(float x) { return gtUbPow(2, x, 2.0); }"), out);
        assertTrue(out.contains("#define SQ(x) sqrt(x)"), "宏定义行不能动");
        assertTrue(out.contains("gtUbSqrt(5, texCoord.x) + gtUbSmoothstep(5, 0.0, 1.0, texCoord.y)"), out);
        assertTrue(out.contains("gtUbClamp(6, vec3(a), 0.0, 1.0)"), out);
    }

    @Test
    void 生成物带钩子且隐藏参数排进块里() {
        ShaderLayer layer = new ShaderLayer("t", BODY);
        DebugInstrument.Instrumented inst = DebugInstrument.probe(layer.strippedBody(), 8, "l").value();
        GlslCodegen.Output out = layer.generateInstrumented(GtProfile.MC_26_3, inst);
        assertTrue(out.source().contains("void gtDbgCapture(float v)"));
        assertTrue(out.source().contains("fragColor = gtDbgBytes(gtDbgValue[gtDbgSel - 2]);"));
        assertTrue(out.orderedParams().stream().anyMatch(p -> p.name().equals(DebugInstrument.SEL_PARAM)));
        // JSON、GLSL、每帧写入三方对得上：隐藏参数必须和作者参数走同一条排布
        assertDoesNotThrow(() -> PostEffectJsonBuilder.verifyLayout(out.orderedParams(), out.source()));
        // 行号映射不变：作者第 8 行仍在头部之后第 8 行
        String[] all = out.source().split("\n", -1);
        assertTrue(all[out.headerLineCount() + 8 - 1].contains("gtDbgCapture(l)"));
        // 本层记下的生成物不受影响
        assertFalse(layer.generatedSource().contains("gtDbgCapture"));
    }

    @Test
    void 调试链截到目标层为止() {
        ShaderProject project = new ShaderProject("p");
        ShaderLayer a = project.addLayer();
        ShaderLayer b = project.addLayer();
        ShaderLayer c = project.addLayer();
        for (ShaderLayer l : List.of(a, b, c)) {
            l.setEnabled(true);
        }
        ShaderProject.Build build = project.generateDebug(GtProfile.MC_26_3, "post/dbg", b, null);
        assertEquals(2, build.passes().size(), "b 之后的层不能再改它的输出");
        assertFalse(build.needsFinalBlit(), "两个通道结果正好停在主缓冲");
        assertEquals(3, build.passes().get(1).enabledCount(), "GTLayerCount 不随截断改变");
        assertEquals(0, project.generateDebug(GtProfile.MC_26_3, "post/dbg", new ShaderLayer("x", ""), null)
                .passes().size());
    }

    @Test
    void 回读解码() {
        int bits = Float.floatToRawIntBits(-3.25f);
        assertEquals(-3.25f, DebugInstrument.decodeFloat(bits >>> 24, (bits >> 16) & 255, (bits >> 8) & 255, bits & 255));
        DebugInstrument.UbSample s = DebugInstrument.UbSample.decode(11, 1, 44, 3);
        assertEquals(DebugInstrument.UbKind.SMOOTHSTEP, s.kind());
        assertEquals(300, s.line());
        assertEquals(3, s.count());
    }

    @Test
    void 词法器分得清注释预处理与代码() {
        List<GlslLexer.Token> t = GlslLexer.tokenize("#define A 1\nfloat x = 1e-3; // c\n/* a\nb */ y");
        assertEquals(GlslLexer.Kind.PREPROCESSOR, t.get(0).kind());
        assertEquals("1e-3", t.stream().filter(k -> k.kind() == GlslLexer.Kind.NUMBER).findFirst().orElseThrow().text());
        GlslLexer.Token block = t.stream().filter(k -> k.kind() == GlslLexer.Kind.BLOCK_COMMENT).findFirst().orElseThrow();
        assertEquals(3, block.line());
        assertEquals(4, block.endLine());
        assertEquals(4, t.get(t.size() - 1).line());
        String src = "a = 1; // pow(";
        assertTrue(GlslLexer.isInCommentOrPreprocessor(GlslLexer.tokenize(src), src.length()));
        assertFalse(GlslLexer.isInCommentOrPreprocessor(GlslLexer.tokenize(src), 3));
    }
}
