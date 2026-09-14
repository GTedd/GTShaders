package mc.GTedd.cn.gtshaders.ai;

import org.junit.jupiter.api.Test;
import mc.GTedd.cn.gtshaders.GTShaders;
import mc.GTedd.cn.gtshaders.codegen.GlslCodegen;
import mc.GTedd.cn.gtshaders.codegen.ParamScanner;
import mc.GTedd.cn.gtshaders.core.BlendMode;
import mc.GTedd.cn.gtshaders.core.GtProfile;
import mc.GTedd.cn.gtshaders.core.ParamType;
import mc.GTedd.cn.gtshaders.core.ShaderParam;
import mc.GTedd.cn.gtshaders.library.SourceDoc;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 盯着 prompt 和它带的那几份 few-shot 示例。
 *
 * <h2>为什么示例值得一整个测试类</h2>
 *
 * <p>示例是整套提示词里<b>唯一一处会悄悄过时</b>的东西。规范改了、内置量加了、
 * 某条禁令收紧了——这些都是文字，而示例是代码，它不会因为规范变了就自己跟着变。
 * 而模型对示例的模仿程度远高于对规则的遵守程度：一份过时的示例能稳定地
 * 带出一批「看起来对但扫不出参数」的产出，比规范里少写一句话严重得多。
 *
 * <p>所以示例被当成产品代码来对待：它得能被 {@link SourceDoc} 解析出标题、
 * 被 {@link ParamScanner} 扫出参数、被 {@link PitfallCheck} 判定为干净、
 * 还得能过真机编译（探针由 {@link #writesExampleProbesForGlslCheck()} 产出）。
 * 示例自己踩坑，等于在教模型踩坑。
 */
class AiExampleTest {

    /** prompt 里拼进去的那几份示例。改这个列表时 {@code AiPrompt.EXAMPLE_RES} 要一起改。 */
    private static final List<String> EXAMPLES = List.of(
            "/assets/gtshaders/ai/example_post.fsh",
            "/assets/gtshaders/ai/example_warp.fsh");

    private static String resource(String path) {
        try (InputStream in = GTShaders.class.getResourceAsStream(path)) {
            assertNotNull(in, "资源缺失: " + path);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new AssertionError("读取资源失败: " + path, e);
        }
    }

    private static String tagOf(String path) {
        return path.substring(path.lastIndexOf('/') + 1);
    }

    // ------------------------------------------------------------ 示例本身

    @Test
    void everyExampleObeysEveryConventionItTeaches() {
        for (String path : EXAMPLES) {
            String example = resource(path);
            String tag = tagOf(path);

            SourceDoc doc = SourceDoc.parse(example);
            assertFalse(doc.isEmpty(), tag + " 缺少头部说明注释");
            assertTrue(doc.title().contains(" / "),
                    tag + " 的标题应当是「中文 / English」形式: " + doc.title());
            assertFalse(doc.summary().isBlank(), tag + " 缺少一句话说明");

            ParamScanner.Result scan = ParamScanner.scan(example);
            int n = scan.params().size();
            assertTrue(n >= 3, tag + " 只有 " + n + " 个参数，而 prompt 要求至少 3 个");
            assertTrue(n <= 10, tag + " 有 " + n + " 个参数，超过 prompt 给的上限 10");
            assertTrue(scan.warnings().isEmpty(), tag + " 的参数注解有问题: " + scan.warnings());

            // 这一条把「每个参数都得真的用上」也一起管了——示例要是留了个用不上的滑块，
            // 模型会照着学，而那种错编译一声不响
            List<PitfallCheck.Warning> pitfalls = PitfallCheck.scan(example);
            assertTrue(pitfalls.isEmpty(), tag + " 踩了它自己教人避开的坑: " + pitfalls);

            // 「不要重复声明」那一条，示例必须自己守住，否则模型照抄就编译失败
            assertFalse(example.contains("#version"), tag + " 不该写 #version");
            assertFalse(example.contains("out vec4 fragColor;"), tag + " 不该声明 fragColor");
            assertFalse(example.contains("uniform sampler2D"), tag + " 不该声明采样器");
            assertTrue(example.contains("void main()"), tag + " 的入口必须是 main");
            assertTrue(example.contains("fragColor ="), tag + " 必须给 fragColor 赋值");

            // 四条禁令与几条硬要求，示例一条都不许犯
            assertFalse(example.contains("texture2D("), tag + " 用了 GLSL 120 的 texture2D");
            assertFalse(example.contains("GameTime"), tag + " 用了 GameTime，动画该用 GTTime");
            assertFalse(example.contains("ScreenSize"), tag + " 用了 ScreenSize，像素步长该用 OutSize");
            assertFalse(example.contains("discard"), tag + " 用了 discard");
            assertFalse(example.contains("gl_FragColor"), tag + " 用了 gl_FragColor");
        }
    }

    /**
     * 两份示例合起来要覆盖全部实用类型。
     *
     * <p>只演示 float 的话，模型遇到「开关」「颜色」「循环层数」时就得自己猜类型关键字，
     * 而猜错的后果是那一条参数悄悄退化成一个 0..1 的 float 滑块——面板上看着正常，
     * 语义全错，玩家拖半天不明白为什么颜色调不动。类型表在 prompt 里写着，但示例比表管用。
     */
    @Test
    void examplesCoverEveryPracticalParamType() {
        Set<ParamType> seen = new HashSet<>();
        for (String path : EXAMPLES) {
            for (ShaderParam p : ParamScanner.scan(resource(path)).params()) {
                seen.add(p.type());
            }
        }
        for (ParamType want : List.of(ParamType.FLOAT, ParamType.INT, ParamType.BOOL,
                ParamType.VEC2, ParamType.COLOR3)) {
            assertTrue(seen.contains(want),
                    "没有任何示例演示了 " + want + " 类型的参数，实际覆盖: " + seen);
        }
    }

    /** 至少有一份示例要演示 @group——分组是库里 45% 的效果都在用的东西。 */
    @Test
    void atLeastOneExampleDemonstratesGrouping() {
        boolean any = EXAMPLES.stream().anyMatch(p -> resource(p).contains("// @group"));
        assertTrue(any, "没有任何示例演示 @group 分组");
    }

    /**
     * 把每份示例生成成完整着色器，落到临时目录 {@code %TEMP%/gtshaders-ai-example}，供外部真编译。
     *
     * <p>上面那些断言只能证明示例<b>格式</b>合规，证明不了它是合法的 GLSL。
     * 单元测试碰不到 GL 也不加载 ShaderC，所以这里只负责产出素材：
     * 目录布局是一个资源包（{@code assets/gtshaders/shaders/post/*.fsh}），
     * 拿任意一个离线 GLSL 编译器、或者游戏自带的 ShaderC 去编译它即可。
     */
    @Test
    void writesExampleProbesForGlslCheck() throws Exception {
        Path dir = Path.of(System.getProperty("java.io.tmpdir"),
                "gtshaders-ai-example", "assets", "gtshaders", "shaders", "post");
        Files.createDirectories(dir);
        int written = 0;
        for (String path : EXAMPLES) {
            ParamScanner.Result scan = ParamScanner.scan(resource(path));
            String stem = tagOf(path).replace(".fsh", "");
            for (GtProfile profile : GtProfile.values()) {
                GlslCodegen.Output out = GlslCodegen.generate(profile, scan.strippedBody(),
                        scan.params(), BlendMode.NORMAL);
                assertTrue(out.source().contains("void main"), stem + " 生成的着色器没有 main");
                Files.writeString(
                        dir.resolve(stem + "_" + profile.name().toLowerCase(Locale.ROOT) + ".fsh"),
                        out.source(), StandardCharsets.UTF_8);
                written++;
            }
        }
        assertEquals(EXAMPLES.size() * GtProfile.values().length, written);
        System.out.println("AI 示例探针已写入 " + written + " 份: " + dir);
    }

    /** 削头是幂等的：示例本来就合规，过一遍不该有任何改动。 */
    @Test
    void extractingTheExamplesChangesNothing() {
        for (String path : EXAMPLES) {
            String example = resource(path);
            ShaderExtractor.Result r = ShaderExtractor.extract(fence(example));
            assertEquals(example.strip(), r.source().strip(), tagOf(path) + " 被削头改动了");
            assertTrue(r.notes().isEmpty(), tagOf(path) + " 合规却触发了改动: " + r.notes());
        }
    }

    /** 三个反引号不方便写在测试字面量里，拼出来。 */
    private static String fence(String body) {
        String f = "`".repeat(3);
        return f + "glsl\n" + body + f;
    }

    // ------------------------------------------------------------ prompt

    /** system prompt 必须真的把那几条硬性禁令和输出格式说出来，缺一条产出质量就塌一半。 */
    @Test
    void systemPromptCarriesTheRulesThatMatter() {
        String sys = AiPrompt.system();
        for (String must : List.of(
                "@param", "@group",
                "GTTime", "InSampler", "gl_FragCoord", "OutSize",
                "color3", "> 0.5",
                // 能力边界这一条以前写的是「拿不到深度」。26.3 之后深度与世界相机都有了，
                // 但都要显式开口，而且相机在纯资源包里会退化——模型最容易凭训练语料
                // 想当然的就是这两处，所以改成钉住新的三个关键词
                "DepthSampler", "gtDepth(uv)", "gtWorldPos",
                "1.0 / OutSize",
                // 位打包唯一的致命用法：读它必须走 texelFetch。写错不报错，只是解出垃圾
                "gtUnpackFloatAt",
                "break",
                "fract")) {
            assertTrue(sys.contains(must), "system prompt 里没有提到关键的一条: " + must);
        }
        assertTrue(sys.contains(fenceMarker()), "没有说清输出格式必须是 glsl 代码块");
        assertTrue(sys.contains("#version"), "没有说清哪些头部不要写");

        // 每份示例都要真的拼进去，否则 few-shot 等于没有
        for (String path : EXAMPLES) {
            String title = SourceDoc.parse(resource(path)).localTitle();
            assertFalse(title.isBlank(), path + " 没有标题，无法验证它是否被拼进 prompt");
            assertTrue(sys.contains(title), tagOf(path) + "（" + title + "）没有拼进 system prompt");
        }
    }

    private static String fenceMarker() {
        return "`".repeat(3) + "glsl";
    }

    /**
     * 保留名清单必须<b>逐个</b>出现在 prompt 里。
     *
     * <p>这一条钉的是「清单只有一份」：{@link PitfallCheck#RESERVED_NAMES} 是唯一来源，
     * prompt 由它渲染。以后谁往那个集合里加了个名字，prompt 自动就跟上了；
     * 而要是有人图省事把清单抄进 markdown，加名字时就只会改代码——
     * 模型继续拿它当参数名，撞出来的报错还指着模板行，两边都查不出问题。
     */
    @Test
    void promptListsEveryReservedNameFromTheSingleSource() {
        String sys = AiPrompt.system();
        assertFalse(sys.contains("{{RESERVED_NAMES}}"), "保留名占位符没有被替换");
        assertFalse(PitfallCheck.RESERVED_NAMES.isEmpty(), "保留名清单是空的");
        for (String name : PitfallCheck.RESERVED_NAMES) {
            assertTrue(sys.contains("`" + name + "`"),
                    "保留名 " + name + " 没有出现在 prompt 里，模型会拿它当参数名");
        }
    }

    /**
     * 保留名清单得真的覆盖 codegen 会生成的那些标识符。
     *
     * <p>拿两份示例过一遍完整的 codegen，把生成出来的头部里所有 uniform 名、in/out 名、
     * define 名抓出来，逐个确认在清单里。加了新内置量却忘了加进清单时，这里会红——
     * 那种漏掉只会表现为「模型偶尔生成一个撞名的参数，报错指着模板行」，
     * 极难联想到是清单少了一条。
     */
    @Test
    void reservedNamesCoverWhatCodegenActuallyEmits() {
        ParamScanner.Result scan = ParamScanner.scan(resource(EXAMPLES.getFirst()));
        GlslCodegen.Output out = GlslCodegen.generate(GtProfile.MC_26_3, scan.strippedBody(),
                scan.params(), BlendMode.NORMAL);
        String header = out.source().substring(0, out.source().indexOf("void "));

        Set<String> emitted = new HashSet<>();
        // uniform sampler2D Foo;  /  #define Foo ...  /  in vec2 foo;  /  out vec4 foo;
        for (var m : java.util.regex.Pattern
                .compile("(?m)^\\s*(?:uniform\\s+sampler2D|#define|(?:layout\\s*\\([^)]*\\)\\s*)?(?:in|out)\\s+\\w+)\\s+(\\w+)")
                .matcher(header).results().toList()) {
            emitted.add(m.group(1));
        }
        assertFalse(emitted.isEmpty(), "没能从生成的头部里抓到任何标识符，正则该修了");

        Set<String> params = new HashSet<>();
        for (ShaderParam p : scan.params()) {
            params.add(p.name());
        }
        for (String name : emitted) {
            if (params.contains(name) || name.startsWith("GT_")) {
                continue;
            }
            assertTrue(PitfallCheck.RESERVED_NAMES.contains(name),
                    "codegen 生成了标识符 " + name + "，但它不在 RESERVED_NAMES 里——"
                            + "模型可能拿它当参数名，撞出来的报错会指着模板行");
        }
    }
}
