package mc.GTedd.cn.gtshaders.ui;

import mc.GTedd.cn.gtshaders.codegen.GlslSymbols;
import mc.GTedd.cn.gtshaders.codegen.ParamScanner;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 代码区辅助能力里只看文本的那部分：补全来源、签名上下文、括号配对、查找替换。 */
class CodeAssistTest {

    private static final String SRC = """
            // @param name=Glow type=float min=0 max=2 zh_cn=辉光
            // 亮度
            float luma(vec3 c) {
                return dot(c, vec3(0.2126, 0.7152, 0.0722));
            }
            #define SCALE 2.0
            void main() {
                vec3 base = texture(InSampler, texCoord).rgb;
                float l = luma(base) * Glow; // (注释里的括号
                fragColor = vec4(vec3(l), 1.0);
            }
            """;

    @Test
    void helper的签名与说明从生成器源码里解析出来() {
        GlslSymbols.Symbol s = GlslSymbols.lookup("gtDepthEdge", List.of());
        assertNotNull(s);
        assertEquals(GlslSymbols.Kind.HELPER, s.kind());
        assertEquals("float gtDepthEdge(vec2 uv, float radius)", s.detail());
        assertFalse(s.doc().isEmpty(), "说明应当取自函数上方的注释");
        assertFalse(s.doc().contains("----"), "分节标题不算说明");
        assertNotNull(GlslSymbols.lookup("gtDrive", List.of()), "驱动器 helper 也要进表");
    }

    @Test
    void 作者源码里的函数变量宏与参数都认得() {
        List<GlslSymbols.Symbol> user = GlslSymbols.userSymbols(SRC, ParamScanner.scan(SRC).params());
        GlslSymbols.Symbol luma = GlslSymbols.lookup("luma", user);
        assertEquals(GlslSymbols.Kind.FUNCTION, luma.kind());
        assertEquals("float luma(vec3 c)", luma.detail());
        assertEquals("亮度", luma.doc());
        assertEquals(3, luma.line());
        assertEquals(GlslSymbols.Kind.VARIABLE, GlslSymbols.lookup("base", user).kind());
        assertEquals(GlslSymbols.Kind.VARIABLE, GlslSymbols.lookup("SCALE", user).kind());
        assertEquals(GlslSymbols.Kind.PARAM, GlslSymbols.lookup("Glow", user).kind());
    }

    @Test
    void 补全前缀优先作者名字靠前且不列刚敲完的词() {
        List<GlslSymbols.Symbol> user = GlslSymbols.userSymbols(SRC, ParamScanner.scan(SRC).params());
        List<GlslSymbols.Symbol> hits = GlslSymbols.complete("lu", user, 8);
        assertEquals("luma", hits.get(0).name());
        assertTrue(GlslSymbols.complete("gtDepth", List.of(), 8).stream().allMatch(s -> !s.name().equals("gtDepth")));
        assertTrue(GlslSymbols.complete("smooth", List.of(), 8).stream().anyMatch(s -> s.name().equals("smoothstep")));
        // 子串命中排在前缀命中之后
        List<GlslSymbols.Symbol> depth = GlslSymbols.complete("depth", List.of(), 20);
        assertFalse(depth.isEmpty());
    }

    @Test
    void 签名上下文数得清第几个实参且不被注释里的括号骗() {
        String text = "float x = smoothstep(0.0, clamp(a, 0.0, 1.0), ";
        CodeAssist.CallContext call = CodeAssist.callAt(text, text.length());
        assertEquals("smoothstep", call.name());
        assertEquals(2, call.argIndex(), "内层 clamp 里的逗号不算外层的实参");
        String inner = "float x = clamp(a, ";
        assertEquals(1, CodeAssist.callAt(inner, inner.length()).argIndex());
        int inComment = SRC.indexOf("(注释") + 1;
        assertNull(CodeAssist.callAt(SRC, inComment));
        assertNull(CodeAssist.callAt("a = 1; b", 8));
    }

    @Test
    void 括号配对跳过注释() {
        String text = "f(a, /* ) */ g(b))";
        int[] pair = CodeAssist.matchBracket(text, 1);
        assertNotNull(pair);
        assertEquals(1, pair[0]);
        assertEquals(text.length() - 1, pair[1], "注释里的 ) 不能拿来配对");
        int[] back = CodeAssist.matchBracket(text, text.length());
        assertEquals(1, back[1], "光标在右括号后面也能配");
    }

    @Test
    void 查找与替换() {
        String text = "Glow glow GLOW";
        assertEquals(List.of(0, 5, 10), CodeAssist.findAll(text, "glow", false));
        assertEquals(List.of(5), CodeAssist.findAll(text, "glow", true));
        assertEquals("Glow x GLOW", CodeAssist.replace(text, "glow", "x", true, 0));
        assertEquals("y y y", CodeAssist.replace(text, "glow", "y", false, -1));
        assertEquals("luma", CodeAssist.prefixAt("x = luma", 8));
        assertEquals("", CodeAssist.prefixAt("x = 1.0", 7));
    }
}
