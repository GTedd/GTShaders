package mc.GTedd.cn.gtshaders;

import org.junit.jupiter.api.Test;
import mc.GTedd.cn.gtshaders.codegen.GlslCodegen;
import mc.GTedd.cn.gtshaders.codegen.ParamScanner;
import mc.GTedd.cn.gtshaders.codegen.PostEffectJsonBuilder;
import mc.GTedd.cn.gtshaders.codegen.ShaderImport;
import mc.GTedd.cn.gtshaders.core.BlendMode;
import mc.GTedd.cn.gtshaders.core.GtProfile;
import mc.GTedd.cn.gtshaders.core.ShaderLayer;
import mc.GTedd.cn.gtshaders.core.ShaderParam;
import mc.GTedd.cn.gtshaders.library.EffectLibrary;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 覆盖内置效果库、以及「导出的 .fsh 能原样拖回编辑器」这条往返链路。
 *
 * <p>往返这件事没有测试是守不住的：只要生成头部的格式动一下（多一行、少一行、
 * 换个措辞），旧产物就再也切不回来了，而这个故障要等到有人真的去拖一个老文件才会暴露。
 */
class LibraryTest {

    @Test
    void 效果库不是空的且每个效果都读得到源码() {
        List<EffectLibrary.Entry> all = EffectLibrary.all();
        // 下界跟着库一起涨，否则删掉一半效果也照样过——那就等于没这条检查
        assertTrue(all.size() >= 130, "效果库至少要有 130 个效果，实际 " + all.size());
        for (EffectLibrary.Entry e : all) {
            assertNotNull(EffectLibrary.loadSource(e.id()), e.id() + " 在索引里但读不到源码");
        }
    }

    @Test
    void 效果id唯一且分类都在已声明的分类里() {
        List<String> seen = new ArrayList<>();
        for (EffectLibrary.Entry e : EffectLibrary.all()) {
            assertFalse(seen.contains(e.id()), "重复的效果 id：" + e.id());
            seen.add(e.id());
            assertTrue(EffectLibrary.categories().contains(e.category()),
                    e.id() + " 的分类 " + e.category() + " 没有在 categories 里声明");
        }
    }

    @Test
    void 每个效果都有中英双语名字() {
        for (EffectLibrary.Entry e : EffectLibrary.all()) {
            assertNotEqualsId(e, "zh_cn");
            assertNotEqualsId(e, "en_us");
        }
    }

    /**
     * 按索引里标的类型建层。
     *
     * <p>逐实体效果必须建成轮廓层——它们调用的 {@code gtMask()} / {@code SceneSampler}
     * 只在轮廓层里注入。忘了这一步的话，这些用例照样会过（纯逻辑测试不编译 GLSL），
     * 但 {@code generateShaderLib} 出来的产物在真驱动上会报一串「未定义的 gtMask」。
     */
    private static ShaderLayer libLayer(EffectLibrary.Entry e, String source) {
        ShaderLayer layer = new ShaderLayer(e.id(), source);
        layer.setOutline(e.outline());
        return layer;
    }

    private static void assertNotEqualsId(EffectLibrary.Entry e, String lang) {
        // name() 找不到时会退回 id，所以「名字等于 id」就是缺翻译
        assertFalse(e.name(lang).equals(e.id()), e.id() + " 缺少 " + lang + " 名字");
    }

    @Test
    void 每个效果都能生成完整着色器且uniform块与json对得上() {
        for (EffectLibrary.Entry e : EffectLibrary.all()) {
            String source = EffectLibrary.loadSource(e.id());
            for (GtProfile profile : GtProfile.values()) {
                ShaderLayer layer = libLayer(e, source);
                GlslCodegen.Output out = layer.generate(profile);
                assertTrue(out.wrapped(), e.id() + " 在 " + profile.display() + " 上没找到 main()");
                assertTrue(out.source().startsWith("#version 330"),
                        e.id() + " 的头部不是 #version 330");
                // 块错位不会报编译错误，只会让画面显示成一堆莫名其妙的颜色，必须在这里挡住
                PostEffectJsonBuilder.verifyLayout(out.orderedParams(), out.source());
            }
        }
    }

    @Test
    void 每个效果至少暴露一个可调参数() {
        for (EffectLibrary.Entry e : EffectLibrary.all()) {
            ShaderLayer layer = libLayer(e, EffectLibrary.loadSource(e.id()));
            assertFalse(layer.params().isEmpty(), e.id() + " 一个参数都没有，等于不可调");
            assertTrue(layer.scanWarnings().isEmpty(),
                    e.id() + " 的参数注解有问题：" + layer.scanWarnings());
        }
    }

    // ------------------------------------------------------- 导出再导入的往返

    @Test
    void 生成的着色器能原样切回作者源码() {
        for (EffectLibrary.Entry e : EffectLibrary.all()) {
            String source = EffectLibrary.loadSource(e.id());
            ShaderLayer layer = libLayer(e, source);
            String generated = layer.generate(GtProfile.MC_26_3).source();

            String back = GlslCodegen.extractAuthorBody(generated);
            assertNotNull(back, e.id() + " 切不回来");
            // 逐字节比不现实（扫描器会剔掉裸 uniform 声明），但参数表必须完全一致
            assertParamsMatch(e.id(), layer.params(), libLayer(e, back).params());
            assertTrue(back.contains("void main()"), e.id() + " 切回来之后没有 main()");
        }
    }

    @Test
    void 往返之后再生成一次结果完全稳定() {
        // 不动点检查：拖回来、再导出，产物必须和第一次一模一样。
        // 不稳定的话，反复「导出→导入→导出」会让文件一次比一次长，很快就没法看了。
        EffectLibrary.Entry entry = EffectLibrary.all().get(0);
        ShaderLayer first = libLayer(entry, EffectLibrary.loadSource(entry.id()));
        String genA = first.generate(GtProfile.MC_26_3).source();

        ShaderLayer second = libLayer(entry, GlslCodegen.extractAuthorBody(genA));
        String genB = second.generate(GtProfile.MC_26_3).source();

        assertEquals(genA, genB, "往返一次之后产物变了");
    }

    @Test
    void 裸uniform声明在往返之后仍然是参数() {
        // 裸声明会被扫描器从正文里剔掉；如果只留个空行，参数在导出再导入之后就凭空消失了。
        // 现在换成写一行等价的 @param 注解，往返才不丢东西。
        String src = """
                uniform float Amount;
                void main() { fragColor = vec4(Amount); }
                """;
        ShaderLayer layer = new ShaderLayer("bare", src);
        assertEquals(1, layer.params().size());

        String generated = layer.generate(GtProfile.MC_26_3).source();
        ShaderLayer back = new ShaderLayer("bare", GlslCodegen.extractAuthorBody(generated));
        assertEquals(1, back.params().size(), "往返之后裸声明的参数丢了");
        assertEquals("Amount", back.params().get(0).name());
    }

    /**
     * CRLF 换行不能让参数凭空消失。
     *
     * <p>这条是踩出来的：Windows 上的编辑器和脚本会把 .fsh 写成 CRLF，按 {@code \n} 切开后
     * 每行尾巴上挂着一个 {@code \r}，而 Java 正则的 {@code .} 不匹配 {@code \r}
     * （它算行终止符），于是 {@code @param} 那条 {@code matches()} 全部落空——
     * <b>整份文件一个参数都扫不出来，而且不报任何错</b>。
     *
     * <p>最后暴露成驱动那句「未定义的 Amount」，指着作者自己写的代码。没有这条用例的话，
     * 下一个在 Windows 上编辑效果的人还得再踩一遍。
     */
    @Test
    void CRLF换行不会让参数消失() {
        String lf = """
                // @param name=Amount type=float min=0 max=2 default=0.5 zh_cn=强度
                // @group 外观 / Look
                // @param name=Tint type=color3 default=#FF8800
                void main() { fragColor = vec4(Tint * Amount, 1.0); }
                """;
        ParamScanner.Result crlf = ParamScanner.scan(lf.replace("\n", "\r\n"));
        assertEquals(2, crlf.params().size(), "CRLF 文件把参数全丢了");
        assertEquals("Amount", crlf.params().get(0).name());
        assertEquals("Look", crlf.params().get(1).groupKey(), "CRLF 下 @group 也要照常生效");

        // 行数必须和 LF 版一致，否则报错行号会整体错位
        assertEquals(ParamScanner.scan(lf).strippedBody().split("\n", -1).length,
                crlf.strippedBody().split("\n", -1).length);

        // 完整生成一遍：参数真的进了 uniform 块，而不是只在扫描结果里
        ShaderLayer layer = new ShaderLayer("crlf", lf.replace("\n", "\r\n"));
        String generated = layer.generate(GtProfile.MC_26_3).source();
        assertTrue(generated.contains("float Amount;"), "参数没进 uniform 块");
        assertTrue(generated.contains("vec3 Tint;"));
    }

    @Test
    void 剔除裸声明不改变行数() {
        // 报错行号靠这个成立：正文的行数必须和作者看到的一模一样
        String src = """
                uniform float A;
                uniform vec2 B;
                void main() { fragColor = vec4(A, B, 1.0); }
                """;
        ParamScanner.Result r = ParamScanner.scan(src);
        assertEquals(src.split("\n", -1).length, r.strippedBody().split("\n", -1).length);
    }

    // ------------------------------------------------------------ 拖入的识别

    @Test
    void 认得出本工程生成的产物() {
        ShaderLayer layer = new ShaderLayer("x", "// @param name=K type=float default=0.5\n"
                + "void main() { fragColor = vec4(K); }\n");
        String generated = layer.generate(GtProfile.MC_26_3).source();

        ShaderImport.Result result = ShaderImport.read(generated, "blackhole.fsh");
        assertNotNull(result);
        assertEquals(ShaderImport.Kind.ROUND_TRIP, result.kind());
        assertEquals("blackhole", result.name());
        assertFalse(result.authorSource().contains("#version"), "切回来的源码里不该还有头部");
    }

    @Test
    void 认得出原版格式的完整着色器() {
        String vanilla = """
                #version 330
                #moj_import <minecraft:globals.glsl>
                uniform sampler2D InSampler;
                layout(std140) uniform SamplerInfo { vec2 OutSize; vec2 InSize; };
                in vec2 texCoord;
                out vec4 fragColor;
                void main() { fragColor = texture(InSampler, texCoord); }
                """;
        ShaderImport.Result result = ShaderImport.read(vanilla, "invert.fsh");
        assertNotNull(result);
        assertEquals(ShaderImport.Kind.STRIPPED, result.kind());
        assertFalse(result.authorSource().contains("#version"));
        assertFalse(result.authorSource().contains("uniform sampler2D InSampler"));
    }

    @Test
    void 没有version的当成作者源码原样收下() {
        String raw = "void main() { fragColor = texture(InSampler, texCoord); }\n";
        ShaderImport.Result result = ShaderImport.read(raw, "my effect.glsl");
        assertNotNull(result);
        assertEquals(ShaderImport.Kind.RAW, result.kind());
        assertEquals(raw, result.authorSource());
        assertEquals("my effect", result.name());
    }

    @Test
    void 空文件与非着色器不被接受() {
        assertNull(ShaderImport.read("", "a.fsh"));
        assertNull(ShaderImport.read("   \n\n", "a.fsh"));
        assertNull(ShaderImport.read("这只是一段说明文字，没有入口函数。", "readme.txt"));
        assertFalse(ShaderImport.looksLikeShaderFile("pack.zip"));
        assertTrue(ShaderImport.looksLikeShaderFile("POST/CRT.FSH"));
    }

    // ------------------------------------------------------------ 数据探针

    @Test
    void 用到探针才注入解码函数() {
        // 十几行 helper 对每个着色器都加是白加，所以按需注入。
        // 反过来漏注入就是一句「未定义的 gtProbe」，那才是真的挡路——两边都要钉住。
        String without = GlslCodegen.generate(GtProfile.MC_26_3,
                "void main() { fragColor = vec4(1.0); }", List.of(), BlendMode.NORMAL).source();
        assertFalse(without.contains("gtProbe"), "没用到探针却注入了 helper");

        String with = GlslCodegen.generate(GtProfile.MC_26_3,
                "void main() { fragColor = vec4(gtProbe(0).x); }", List.of(), BlendMode.NORMAL).source();
        assertTrue(with.contains("vec4 gtProbe(int slot)"), "用到探针却没注入 helper");
        assertTrue(with.contains("int gtDecodeInt(vec3 c)"));
        assertTrue(with.contains("float gtDecodeFloat1024(vec3 c)"));
        assertTrue(with.contains("bool gtProbeValid(vec4 probe)"));
    }

    @Test
    void 探针编解码与VanillaDI逐位兼容() {
        // 这三个常量是从 VanillaDI 的 rendertype_entity_translucent_cull.fsh /
        // voxelize.vsh 里逐字抄来的。抄错任何一个，别人的编码端就喂不进来——
        // 而喂不进来时读到的不是空值，是<b>看起来合理的错值</b>，极难发现。
        String with = GlslCodegen.generate(GtProfile.MC_26_3,
                "void main() { fragColor = vec4(gtProbe(0)); }", List.of(), BlendMode.NORMAL).source();
        assertTrue(with.contains("/ 40000.0"), "gtDecodeFloat 的缩放不是 40000");
        assertTrue(with.contains("/ 1024.0"), "gtDecodeFloat1024 的缩放不是 1024");
        assertTrue(with.contains("* 65536"), "24 位编码的高位权重不是 65536");
        // 符号位借用 b 的最高位，解码时要把偏置去掉
        assertTrue(with.contains("c.b >= 128.0 ? -1 : 1"), "符号位的判定和 VanillaDI 不一致");
        assertTrue(with.contains("- 64 + s * 64"), "符号偏置的还原和 VanillaDI 不一致");
        // 数据条表头固定 36 个像素（矩阵 32 + 相机位置 3 + 数量 1），锚点接在后面
        assertTrue(with.contains("#define GT_PROBE_BASE 36"), "锚点起始像素和 VanillaDI 的数据条对不上");
        assertTrue(with.contains("gtProbeTexel(35)"), "数量应当读第 35 个像素");
    }

    /**
     * 用 Java 重跑一遍 GLSL 里的解码，验证它对得上 VanillaDI 的编码端。
     *
     * <p>编码端（{@code encodeInt}）是从对方源码抄来的；解码端是我们注入到着色器里的。
     * 两边都用纯整数运算，所以可以在 JVM 上等价复现——这比"看着像"可靠得多。
     */
    @Test
    void 编码端与解码端在整个量程上都能对上() {
        int[] samples = {0, 1, -1, 255, 256, 65535, 65536, 8388607, -8388607, -12345, 4242424};
        for (int value : samples) {
            int[] rgb = vanillaDiEncodeInt(value);
            assertEquals(value, gtDecodeInt(rgb), "编解码对不上：" + value);
        }
    }

    /** VanillaDI 的 encodeInt，逐行照抄。 */
    private static int[] vanillaDiEncodeInt(int i) {
        int s = (i < 0 ? 1 : 0) * 128;
        i = Math.abs(i);
        int r = i % 256;
        i = i / 256;
        int g = i % 256;
        i = i / 256;
        int b = i % 256;
        return new int[]{r, g, b + s};
    }

    /** 注入到着色器里的 gtDecodeInt，逐行照抄。 */
    private static int gtDecodeInt(int[] c) {
        int s = c[2] >= 128 ? -1 : 1;
        return s * (c[0] + c[1] * 256 + (c[2] - 64 + s * 64) * 65536);
    }

    @Test
    void 探针helper排在自动生成段之内() {
        // 必须落在头部里，否则 headerLineCount 算不进它，作者看到的报错行号会整体偏移
        GlslCodegen.Output out = GlslCodegen.generate(GtProfile.MC_26_3,
                "void main() { fragColor = vec4(gtProbe(0)); }", List.of(), BlendMode.NORMAL);
        int probeAt = out.source().indexOf("vec4 gtProbe(int slot)");
        int endAt = out.source().indexOf(GlslCodegen.HEADER_END);
        assertTrue(probeAt > 0 && probeAt < endAt, "探针 helper 跑到自动生成段外面去了");

        String header = out.source().substring(0, endAt);
        assertEquals(out.headerLineCount(), header.split("\n", -1).length,
                "headerLineCount 没把探针 helper 算进去");
    }

    @Test
    void 库里确实有用到探针做世界锚点的效果() {
        // 这是 VanillaDI 那套做法在本工程里的落点，丢了就等于研究白做了
        List<String> users = new ArrayList<>();
        for (EffectLibrary.Entry e : EffectLibrary.all()) {
            if (EffectLibrary.loadSource(e.id()).contains("gtProbe")) {
                users.add(e.id());
            }
        }
        assertTrue(users.size() >= 3, "用到数据探针的效果太少：" + users);
    }

    // ------------------------------------------------------------ 产物路径

    @Test
    void 单通道产物不带序号后缀而多通道带() {
        // JSON 里的 fragment_shader 和落盘的 .fsh 必须是同一个路径。
        // 对不上时 Minecraft 只会安静地不加载，什么都不报——所以只能靠测试挡。
        mc.GTedd.cn.gtshaders.core.ShaderProject one = new mc.GTedd.cn.gtshaders.core.ShaderProject("x");
        one.clearLayers();
        one.addLayer(enabledLayer("a"));
        assertEquals("post/x", one.generate(GtProfile.MC_26_3, "post/x").passes().get(0).shaderPath());

        one.addLayer(enabledLayer("b"));
        List<mc.GTedd.cn.gtshaders.core.ShaderProject.PassBuild> two =
                one.generate(GtProfile.MC_26_3, "post/x").passes();
        assertEquals("post/x_0", two.get(0).shaderPath());
        assertEquals("post/x_1", two.get(1).shaderPath());
    }

    @Test
    void 生成的json引用的正是落盘的着色器路径() {
        mc.GTedd.cn.gtshaders.core.ShaderProject p = new mc.GTedd.cn.gtshaders.core.ShaderProject("sonar");
        p.clearLayers();
        p.addLayer(enabledLayer("sonar"));
        mc.GTedd.cn.gtshaders.core.ShaderProject.Build build = p.generate(GtProfile.MC_26_3, "post/sonar");
        String json = PostEffectJsonBuilder.pretty(
                PostEffectJsonBuilder.buildChain("gtshaders", build, new float[]{0f, 0f, 0f, 1f}));
        assertTrue(json.contains("\"gtshaders:" + build.passes().get(0).shaderPath() + "\""),
                "JSON 引用的路径和 pass 给出的对不上：\n" + json);
    }

    private static ShaderLayer enabledLayer(String name) {
        ShaderLayer l = new ShaderLayer(name, "void main() { fragColor = vec4(1.0); }");
        l.setEnabled(true);
        return l;
    }

    private static void assertParamsMatch(String id, List<ShaderParam> a, List<ShaderParam> b) {
        assertEquals(a.size(), b.size(), id + " 往返后参数数量变了");
        for (int i = 0; i < a.size(); i++) {
            assertEquals(a.get(i).name(), b.get(i).name(), id + " 第 " + i + " 个参数名变了");
            assertEquals(a.get(i).type(), b.get(i).type(), id + " 参数 " + a.get(i).name() + " 类型变了");
        }
    }
}
