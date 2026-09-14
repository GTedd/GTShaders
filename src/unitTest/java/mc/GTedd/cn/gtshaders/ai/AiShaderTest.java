package mc.GTedd.cn.gtshaders.ai;

import org.junit.jupiter.api.Test;
import mc.GTedd.cn.gtshaders.GTShaders;
import mc.GTedd.cn.gtshaders.codegen.ParamScanner;
import mc.GTedd.cn.gtshaders.library.SourceDoc;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 覆盖「模型说的话 → 能编译的着色器」这一段：代码提取、削头、以及踩坑扫描。
 *
 * <p>这一段的故障全都<b>不表现为它真正的原因</b>：模型多写一行 {@code out vec4 fragColor;}，
 * 驱动报的是模板里某一行重复声明——玩家看到的是一个指着自己没写过的代码的报错，
 * 而且自动修错循环也修不动（模型看不见模板）。所以这些只能在源头挡住，
 * 挡不住就一路烂到玩家脸上。
 */
class AiShaderTest {

    private static String resource(String path) {
        try (InputStream in = GTShaders.class.getResourceAsStream(path)) {
            assertNotNull(in, "资源缺失: " + path);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new AssertionError("读取资源失败: " + path, e);
        }
    }

    // ------------------------------------------------------------ 取代码

    /** 模型爱先聊两句再给代码，甚至先给个片段再给完整版。取第一个块就会取到半成品。 */
    @Test
    void picksTheLongestBlockThatHasAnEntryPoint() {
        String reply = """
                这个效果的核心是这样一句：

                ```glsl
                float k = sin(GTTime);
                ```

                完整实现如下：

                ```glsl
                // 测试 / Test
                // @param name=Amount type=float min=0 max=1 default=0.5
                void main() {
                    fragColor = vec4(texture(InSampler, texCoord).rgb * Amount, 1.0);
                }
                ```

                希望有帮助。
                """;
        String src = ShaderExtractor.extract(reply).source();
        assertTrue(src.contains("void main"), src);
        assertFalse(src.contains("float k = sin"), "取到了前面那个片段:\n" + src);
        assertFalse(src.contains("希望有帮助"), "把解释也带进来了:\n" + src);
    }

    /** 被要求「只输出代码」之后，有些模型会老实到连 fence 都不写。 */
    @Test
    void acceptsBareCodeWithoutFence() {
        String reply = """
                // 裸奔 / Bare
                // @param name=A type=float min=0 max=1 default=0.5
                void main() {
                    fragColor = vec4(vec3(A), 1.0);
                }
                """;
        assertTrue(ShaderExtractor.extract(reply).source().contains("void main"));
    }

    @Test
    void emptyReplyYieldsEmptyResult() {
        assertTrue(ShaderExtractor.extract("").isEmpty());
        assertTrue(ShaderExtractor.extract(null).isEmpty());
        assertTrue(ShaderExtractor.extract("我不太会写这个").isEmpty()
                || !ShaderExtractor.extract("我不太会写这个").source().contains("void main"));
    }

    // ------------------------------------------------------------ 削头

    /**
     * 模型见过的语料几乎全是完整 GLSL 文件，它默认会把 codegen 已经生成的那一套再写一遍。
     * 留着就是重复声明，而报错指向模板行，玩家和模型都看不懂。
     */
    @Test
    void stripsEverythingCodegenAlreadyGenerates() {
        String reply = """
                ```glsl
                #version 150 core
                #extension GL_ARB_separate_shader_objects : require
                precision highp float;

                layout(std140) uniform Globals {
                    mat4 ModelViewMat;
                    vec4 ColorModulator;
                };

                uniform sampler2D InSampler;
                in vec2 texCoord;
                out vec4 fragColor;

                #define GTTime (GameTime * 1200.0 + GTSystem.x)
                #define iResolution vec3(OutSize, 1.0)

                // 我的效果 / Mine
                // @param name=Amount type=float min=0 max=2 default=1
                uniform float Extra;

                void main() {
                    fragColor = vec4(texture(InSampler, texCoord).rgb * Amount * Extra, 1.0);
                }
                ```
                """;
        String src = ShaderExtractor.extract(reply).source();

        assertFalse(src.contains("#version"), src);
        assertFalse(src.contains("#extension"), src);
        assertFalse(src.contains("precision highp"), src);
        assertFalse(src.contains("uniform sampler2D InSampler"), src);
        assertFalse(src.contains("in vec2 texCoord"), src);
        assertFalse(src.contains("out vec4 fragColor"), src);
        assertFalse(src.contains("#define GTTime"), src);
        assertFalse(src.contains("#define iResolution"), src);
        assertFalse(src.contains("ModelViewMat"), "Globals 块要连花括号一起削掉:\n" + src);
        assertFalse(src.contains("ColorModulator"), "Globals 块要连花括号一起削掉:\n" + src);

        // 削过头比漏削更糟：作者的裸 uniform 是 ParamScanner 生成滑块的依据，
        // 削掉它玩家拿到的就是一个什么都不能调的效果
        assertTrue(src.contains("uniform float Extra;"), "作者的 uniform 被误删:\n" + src);
        assertTrue(src.contains("// @param name=Amount"), "参数注解被误删:\n" + src);
        assertTrue(src.contains("// 我的效果 / Mine"), "头部说明被误删:\n" + src);
        assertTrue(src.contains("void main"), src);
        assertTrue(src.startsWith("//"), "削完应当以头部注释开头:\n" + src);
    }

    /** 单行写完的 uniform 块（{...}; 在同一行）不能把后面的代码一起吃掉。 */
    @Test
    void handlesSingleLineUniformBlock() {
        String reply = """
                ```glsl
                layout(std140) uniform Globals { mat4 M; };
                // 效果 / Effect
                // @param name=A type=float min=0 max=1 default=0.5
                void main() { fragColor = vec4(vec3(A), 1.0); }
                ```
                """;
        String src = ShaderExtractor.extract(reply).source();
        assertFalse(src.contains("mat4 M"), src);
        assertTrue(src.contains("void main"), "块之后的代码被误吞:\n" + src);
    }

    /**
     * 模型语料里的后处理 GLSL 绝大多数来自 Shadertoy，入口叫 mainImage。
     * codegen 找不到 main 就不改写入口，最终报的是「缺少 main」——
     * 那个错误里完全看不出「你写的是另一个函数名」。
     */
    @Test
    void bridgesShadertoyEntryPoint() {
        String reply = """
                ```glsl
                // 移植 / Ported
                // @param name=A type=float min=0 max=1 default=0.5
                void mainImage(out vec4 O, in vec2 fragCoord) {
                    O = vec4(vec3(A), 1.0);
                }
                ```
                """;
        ShaderExtractor.Result r = ShaderExtractor.extract(reply);
        assertTrue(r.source().contains("void main()"), "没有补上桥接:\n" + r.source());
        assertTrue(r.source().contains("mainImage(fragColor"), r.source());
        assertTrue(r.notes().contains("bridged"), r.notes().toString());
    }

    /** 已经有 main 的时候不能再补一个——两个 main 直接链接失败。 */
    @Test
    void doesNotBridgeWhenMainExists() {
        String reply = """
                ```glsl
                void main() { fragColor = vec4(1.0); }
                ```
                """;
        ShaderExtractor.Result r = ShaderExtractor.extract(reply);
        assertFalse(r.notes().contains("bridged"), r.notes().toString());
        assertEquals(1, countOccurrences(r.source(), "void main"), r.source());
    }

    /** 模型偶尔吐出全角分号之类的字符，它们和正常字符长得几乎一样，只报 unexpected token。 */
    @Test
    void sanitizesLookalikeCharacters() {
        String reply = "```glsl\nvoid main() {\n    fragColor = vec4(1.0)；\n}\n```";
        String src = ShaderExtractor.extract(reply).source();
        assertFalse(src.contains("；"), "全角分号没有被清洗:\n" + src);
        assertTrue(src.contains(";"), src);
    }

    private static int countOccurrences(String s, String needle) {
        int n = 0;
        int i = s.indexOf(needle);
        while (i >= 0) {
            n++;
            i = s.indexOf(needle, i + needle.length());
        }
        return n;
    }

    // ------------------------------------------------------------ 陷阱扫描

    private static boolean has(List<PitfallCheck.Warning> ws, String code) {
        return ws.stream().anyMatch(w -> w.code().equals(code));
    }

    /**
     * 一次性播放：加进工程时 GTTime 已经几百秒，clamp 恒等于 1，效果停在结束态。
     * 这一条编译完全通过，驱动一个字都不会说。
     */
    @Test
    void catchesOneShotTimeRamp() {
        String src = """
                // @param name=Duration type=float min=0.1 max=5 default=1
                // @param name=A type=float min=0 max=1 default=0.5
                // @param name=B type=float min=0 max=1 default=0.5
                void main() {
                    float t = clamp(GTTime / Duration, 0.0, 1.0);
                    fragColor = vec4(vec3(t * A * B), 1.0);
                }
                """;
        assertTrue(has(PitfallCheck.scan(src), "one_shot"));
    }

    @Test
    void acceptsLoopingTime() {
        String src = """
                // @param name=Period type=float min=0.1 max=5 default=1
                // @param name=A type=float min=0 max=1 default=0.5
                // @param name=B type=float min=0 max=1 default=0.5
                void main() {
                    float t = fract(GTTime / max(Period, 0.01));
                    fragColor = vec4(vec3(t * A * B), 1.0);
                }
                """;
        assertFalse(has(PitfallCheck.scan(src), "one_shot"));
    }

    /**
     * 用 {@code texture()} 读位打包的数据。这是这套编码唯一的致命用法：
     * 位模式被双线性一混，解出来是个毫无关系的数，而编译、链接、运行全都不吭声。
     */
    @Test
    void catchesBilinearReadOfPackedData() {
        String src = """
                // @texture name=Tbl path=gtshaders:effect/tbl width=256 height=1
                // @param name=A type=float min=0 max=1 default=0.5
                // @param name=B type=float min=0 max=1 default=0.5
                // @param name=C type=float min=0 max=1 default=0.5
                void main() {
                    float k = gtUnpackFloat(texture(Tbl, vec2(texCoord.x, 0.5)));
                    fragColor = vec4(vec3(k * A * B * C), 1.0);
                }
                """;
        assertTrue(has(PitfallCheck.scan(src), "packed_bilinear"));
    }

    /** 走 texelFetch 的那条路是对的，不该报。 */
    @Test
    void acceptsTexelFetchReadOfPackedData() {
        String src = """
                // @texture name=Tbl path=gtshaders:effect/tbl width=256 height=1
                // @param name=A type=float min=0 max=1 default=0.5
                // @param name=B type=float min=0 max=1 default=0.5
                // @param name=C type=float min=0 max=1 default=0.5
                void main() {
                    float k = gtUnpackFloatAt(Tbl, ivec2(int(texCoord.x * 255.0), 0));
                    fragColor = vec4(vec3(k * A * B * C), 1.0);
                }
                """;
        assertFalse(has(PitfallCheck.scan(src), "packed_bilinear"));
    }

    /** 写死的亮度门限在夜里和洞里恒为 0，玩家转个头就以为效果坏了。 */
    @Test
    void catchesHardCodedLumaThreshold() {
        String src = """
                // @param name=A type=float min=0 max=1 default=0.5
                // @param name=B type=float min=0 max=1 default=0.5
                // @param name=C type=float min=0 max=1 default=0.5
                void main() {
                    vec3 col = texture(InSampler, texCoord).rgb;
                    float luma = dot(col, vec3(0.299, 0.587, 0.114));
                    float m = smoothstep(0.78, 0.95, luma);
                    fragColor = vec4(col * (1.0 + m * A * B * C), 1.0);
                }
                """;
        assertTrue(has(PitfallCheck.scan(src), "absolute_threshold"));
    }

    /**
     * 误报的代价是白跑一轮生成、还可能把本来没问题的着色器改坏，所以判据收得很紧：
     * 卡的不是亮度就不该出声。
     */
    @Test
    void doesNotFlagNonLumaSmoothstep() {
        String src = """
                // @param name=A type=float min=0 max=1 default=0.5
                // @param name=B type=float min=0 max=1 default=0.5
                // @param name=C type=float min=0 max=1 default=0.5
                void main() {
                    float band = fract(texCoord.y - GTTime);
                    float m = smoothstep(0.4, 0.6, band);
                    fragColor = vec4(vec3(m * A * B * C), 1.0);
                }
                """;
        assertFalse(has(PitfallCheck.scan(src), "absolute_threshold"));
    }

    /**
     * 模型很爱把 prompt 里的反例抄进注释当提醒。不去注释就会把自己的提醒扫成一条警告，
     * 然后白白多跑一轮去「修」一个根本不存在的问题。
     */
    @Test
    void ignoresPitfallsQuotedInComments() {
        String src = """
                // 说明：这里刻意不用 clamp(GTTime / Period, 0.0, 1.0)，那样会播完就停
                /* 也不用 smoothstep(0.6, 0.85, luma) 这种写死的门限 */
                // @param name=A type=float min=0 max=1 default=0.5
                // @param name=B type=float min=0 max=1 default=0.5
                // @param name=C type=float min=0 max=1 default=0.5
                void main() {
                    fragColor = vec4(vec3(A * B * C), 1.0);
                }
                """;
        List<PitfallCheck.Warning> ws = PitfallCheck.scan(src);
        assertFalse(has(ws, "one_shot"), ws.toString());
        assertFalse(has(ws, "absolute_threshold"), ws.toString());
    }

    /** 一个不能调的效果等于一张静态贴图，而这是个可视化编辑器。 */
    @Test
    void catchesShaderWithoutParams() {
        String src = "void main() { fragColor = vec4(1.0, 0.0, 0.0, 1.0); }";
        assertTrue(has(PitfallCheck.scan(src), "too_few_params"));
    }

    @Test
    void pitfallCodeMapsToLangKey() {
        assertEquals("gtshaders.ai.pitfall.one_shot",
                PitfallCheck.langKey(new PitfallCheck.Warning("one_shot", "")));
    }

    @Test
    void repairRequestCarriesFullSourceBecauseServerKeepsNoState() {
        String src = "void main() { fragColor = vec4(1.0); }";
        String msg = AiPrompt.repairRequest(src, List.of("第 3 行: undefined variable 'Foo'"));
        assertTrue(msg.contains(src), "修错请求必须自带完整源码——服务端不替我们记上文");
        assertTrue(msg.contains("undefined variable 'Foo'"), msg);
        assertTrue(msg.contains("```glsl"), msg);
    }
}
