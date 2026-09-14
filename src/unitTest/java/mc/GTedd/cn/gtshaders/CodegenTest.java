package mc.GTedd.cn.gtshaders;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import mc.GTedd.cn.gtshaders.codegen.GlslCodegen;
import mc.GTedd.cn.gtshaders.codegen.GlslSanitizer;
import mc.GTedd.cn.gtshaders.codegen.ParamScanner;
import mc.GTedd.cn.gtshaders.codegen.PostEffectJsonBuilder;
import mc.GTedd.cn.gtshaders.codegen.Samples;
import mc.GTedd.cn.gtshaders.codegen.VanillaImporter;
import mc.GTedd.cn.gtshaders.core.BlendMode;
import mc.GTedd.cn.gtshaders.core.ColorMath;
import mc.GTedd.cn.gtshaders.core.GtProfile;
import mc.GTedd.cn.gtshaders.core.ParamType;
import mc.GTedd.cn.gtshaders.core.ShaderLayer;
import mc.GTedd.cn.gtshaders.core.ShaderParam;
import mc.GTedd.cn.gtshaders.core.ShaderProject;
import mc.GTedd.cn.gtshaders.core.ViewportRect;
import mc.GTedd.cn.gtshaders.i18n.GtLang;
import mc.GTedd.cn.gtshaders.workspace.ProjectStore;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 覆盖参数识别、代码生成与通道串联。
 *
 * <p>这些地方出错时游戏里不会报错，只会显示成一堆莫名其妙的颜色（std140 错位）
 * 或者干脆黑屏（乒乓方向搞反），在游戏里排查的成本极高，所以用纯逻辑测试钉死。
 */
class CodegenTest {

    // ------------------------------------------------------------ 参数识别

    @Test
    void 注解参数被完整解析() {
        String src = """
                // @param name=Strength type=float min=0 max=2 default=0.6 zh_cn=效果强度 en_us=Strength
                // @param name=Tint type=color3 default=#99CCFF zh_cn=染色
                void main() { fragColor = vec4(1.0); }
                """;
        ParamScanner.Result r = ParamScanner.scan(src);
        assertEquals(2, r.params().size());

        ShaderParam strength = r.params().get(0);
        assertEquals("Strength", strength.name());
        assertEquals(ParamType.FLOAT, strength.type());
        assertEquals(2f, strength.max());
        assertEquals(0.6f, strength.get(0), 1e-6);
        assertEquals("效果强度", strength.resolveLabel("zh_cn", k -> null));
        assertEquals("Strength", strength.resolveLabel("en_us", k -> null));

        ShaderParam tint = r.params().get(1);
        assertEquals(ParamType.COLOR3, tint.type());
        assertEquals("99CCFF", tint.hex());
    }

    @Test
    void 裸uniform声明被自动提升为参数并从主体中剔除() {
        String src = """
                uniform float Speed;
                uniform vec3 Glow;
                void main() { fragColor = vec4(Glow * Speed, 1.0); }
                """;
        ParamScanner.Result r = ParamScanner.scan(src);
        assertEquals(2, r.params().size());
        assertEquals(ParamType.COLOR3, r.params().get(1).type());
        assertFalse(r.strippedBody().contains("uniform float Speed;"));
        // 行数不变，否则编译错误的行号会整体偏移
        assertEquals(src.split("\n", -1).length, r.strippedBody().split("\n", -1).length);
    }

    @Test
    void 注解优先于裸声明的推断() {
        String src = """
                // @param name=Speed type=float min=0 max=10 default=3.5
                uniform float Speed;
                void main() { fragColor = vec4(Speed); }
                """;
        ParamScanner.Result r = ParamScanner.scan(src);
        assertEquals(1, r.params().size());
        assertEquals(10f, r.params().get(0).max());
    }

    // ------------------------------------------------------------ 代码生成

    @Test
    void 生成的着色器结构完整且行号偏移准确() {
        ShaderLayer layer = new ShaderLayer("l", GlslCodegen.defaultAuthorBody());
        GlslCodegen.Output out = layer.generate(GtProfile.MC_26_3);
        String s = out.source();

        assertTrue(s.startsWith("#version 330"));
        assertTrue(s.contains("uniform sampler2D InSampler;"));
        assertTrue(s.contains("layout(std140) uniform SamplerInfo"));
        assertTrue(s.contains("layout(std140) uniform Globals"));
        assertTrue(s.contains("layout(std140) uniform " + GlslCodegen.PARAM_BLOCK));
        assertTrue(s.contains("vec4 " + GlslCodegen.SYSTEM_UNIFORM + ";"));
        assertTrue(s.contains("vec4 " + GlslCodegen.LAYER_UNIFORM + ";"));
        assertTrue(s.contains("in vec2 texCoord;"));
        assertTrue(s.contains("out vec4 fragColor;"));
        assertFalse(s.contains("#moj_import"), "26.3 用 #include，而且我们本来就把 Globals 内联了");

        String[] lines = s.split("\n", -1);
        assertEquals("// ==== 自动生成部分结束 ====", lines[out.headerLineCount() - 1]);
    }

    @Test
    void 二六三profile切换到显式location() {
        ShaderLayer layer = new ShaderLayer("l", GlslCodegen.defaultAuthorBody());
        String s = layer.generate(GtProfile.MC_26_3).source();
        assertTrue(s.contains("layout(location = 0) in vec2 texCoord;"));
        assertTrue(s.contains("layout(location = 0) out vec4 fragColor;"));
    }

    @Test
    void 仍是version330并require扩展而不是升到450() {
        // 逐字核对过 26.3-pre-2 与 rc-2 的 assets/minecraft/shaders/：全部 core 与 post
        // 都是「#version 330 + #extension GL_ARB_separate_shader_objects : require」。
        // layout(location) 用在 in/out 上在 330 里本来非法，靠这个扩展才合法——
        // 想当然地升到 #version 450 会和原版走两条路，这条用例就是拦住那个想当然。
        String s = new ShaderLayer("l", GlslCodegen.defaultAuthorBody())
                .generate(GtProfile.MC_26_3).source();
        assertTrue(s.startsWith("#version 330"), "26.3 仍然是 330");
        assertFalse(s.contains("#version 450"));
        assertTrue(s.contains("#extension GL_ARB_separate_shader_objects : require"));
    }

    @Test
    void Globals块成员顺序逐字对齐原版() {
        // 26.3 把两个 float 塞进了 ivec3 / vec3 后面本来浪费掉的 4 字节里，
        // 顺序和 26.2 不同（这次重排发生在 snapshot-6，不是引入 #include 的 snapshot-5）。
        // 顺序错了不会有任何编译错误，只会让 GameTime 之类读出垃圾值——所以逐字钉死。
        String s = new ShaderLayer("l", GlslCodegen.defaultAuthorBody())
                .generate(GtProfile.MC_26_3).source();
        assertTrue(s.contains("""
                layout(std140) uniform Globals {
                    ivec3 CameraBlockPos;
                    float GlintAlpha;
                    vec3 CameraOffset;
                    float GameTime;
                    vec2 ScreenSize;
                    int MenuBlurRadius;
                    int UseRgss;
                };"""), "26.3 布局");
    }

    @Test
    void packFormat下界取新Globals布局生效那一版() {
        // 下界取 97（26.3 pre 系列）而不是 93。着色器的新写法（#include / layout(location)）
        // 确实从 93 起生效，但 Globals 的成员重排要到 94 才发生——声明成 93 会让包
        // 在 93 那一版上加载成功却把 GameTime 读成 GlintAlpha，不报错、只是画面不对。
        assertEquals(97, GtProfile.MC_26_3.minPackFormat());
        assertTrue(GtProfile.MC_26_3.maxPackFormat() >= GtProfile.MC_26_3.minPackFormat(),
                "上界不该低于下界，否则包在任何版本上都不显示");
    }

    @Test
    void 混合外壳重命名作者main且不改变行数() {
        String body = """
                // @param name=A type=float default=1
                void main() {
                    fragColor = vec4(A);
                }
                """;
        ShaderLayer layer = new ShaderLayer("l", body);
        layer.setBlendMode(BlendMode.SCREEN);
        GlslCodegen.Output out = layer.generate(GtProfile.MC_26_3);
        String s = out.source();

        assertTrue(out.wrapped(), "应当成功套上混合外壳");
        assertTrue(s.contains("void gtAuthorMain()"), "作者的 main 应被重命名");
        assertTrue(s.contains("gtAuthorMain();"), "生成的 main 应调用它");
        assertTrue(s.contains(BlendMode.SCREEN.expression()), "应写入对应的混合表达式");
        assertTrue(s.contains("mix(base, clamp(mixed, 0.0, 1.0), GTStrength)"));

        // 作者代码所在的行号必须原样保留，否则编译错误定位会失准
        String[] lines = s.split("\n", -1);
        assertEquals("void gtAuthorMain() {", lines[out.headerLineCount() + 1].trim());
    }

    @Test
    void 没有main时不套外壳而不是生成坏代码() {
        ShaderLayer layer = new ShaderLayer("l", "float helper() { return 1.0; }\n");
        GlslCodegen.Output out = layer.generate(GtProfile.MC_26_3);
        assertFalse(out.wrapped());
        assertFalse(out.source().contains("gtAuthorMain();"),
                "找不到作者 main 时不该生成一个调用不存在函数的 main");
    }

    @Test
    void 参数按std140对齐降序排布() {
        ShaderLayer layer = new ShaderLayer("l", """
                // @param name=A type=float default=1
                // @param name=B type=color3 default=#FFFFFF
                // @param name=C type=vec2 default=1,2
                // @param name=D type=int default=3
                void main() { fragColor = vec4(1.0); }
                """);
        List<ShaderParam> ordered = layer.generate(GtProfile.MC_26_3).orderedParams();
        assertEquals("B", ordered.get(0).name());
        assertEquals("C", ordered.get(1).name());
        assertTrue(List.of("A", "D").contains(ordered.get(2).name()));
        assertTrue(List.of("A", "D").contains(ordered.get(3).name()));
    }

    @Test
    void 改源码时保留已调好的参数值() {
        ShaderLayer layer = new ShaderLayer("l", """
                // @param name=Strength type=float min=0 max=1 default=0.5
                void main() { fragColor = vec4(Strength); }
                """);
        layer.params().get(0).set(0, 0.9f);
        layer.setAuthorSource("""
                // @param name=Strength type=float min=0 max=1 default=0.5
                // 新加的注释
                void main() { fragColor = vec4(Strength); }
                """);
        assertEquals(0.9f, layer.params().get(0).get(0), 1e-6);

        // 类型变了就不迁移：标量值套到颜色上只会得到一个误导性的深色
        layer.setAuthorSource("""
                // @param name=Strength type=color3 default=#FFFFFF
                void main() { fragColor = vec4(Strength, 1.0); }
                """);
        assertEquals(1.0f, layer.params().get(0).get(0), 1e-6);
    }

    @Test
    void generate返回的是层里的活参数对象() {
        // 这一条保证拖滑块能不经重编译就实时生效
        ShaderLayer layer = new ShaderLayer("l", Samples.all().get(0).source());
        GlslCodegen.Output out = layer.generate(GtProfile.MC_26_3);
        assertFalse(out.orderedParams().isEmpty());
        for (ShaderParam generated : out.orderedParams()) {
            ShaderParam live = layer.params().stream()
                    .filter(p -> p.name().equals(generated.name())).findFirst().orElse(null);
            assertNotNull(live);
            assertSame(live, generated, "必须是同一个对象，否则调参不会反映到预览");
        }
    }

    // ------------------------------------------------------------ 多效果层与通道链

    @Test
    void 单层链需要收尾blit() {
        ShaderProject p = enabledProject(1);
        ShaderProject.Build build = p.generate(GtProfile.MC_26_3, "post/x");
        assertEquals(1, build.passes().size());
        assertTrue(build.needsFinalBlit(), "奇数个通道时结果停在 swap，必须拷回 main");
    }

    @Test
    void 双层链乒乓且不需要收尾blit() {
        ShaderProject p = enabledProject(2);
        ShaderProject.Build build = p.generate(GtProfile.MC_26_3, "post/x");
        assertEquals(2, build.passes().size());
        assertFalse(build.needsFinalBlit(), "偶数个通道时结果已经在 main 里");

        JsonObject json = PostEffectJsonBuilder.buildChain("gtshaders", build, new float[]{0, 0, 0, 1});
        JsonArray passes = json.getAsJsonArray("passes");
        assertEquals(2, passes.size());

        JsonObject p0 = passes.get(0).getAsJsonObject();
        assertEquals("minecraft:main",
                p0.getAsJsonArray("inputs").get(0).getAsJsonObject().get("target").getAsString());
        assertEquals("swap", p0.get("output").getAsString());

        JsonObject p1 = passes.get(1).getAsJsonObject();
        assertEquals("swap",
                p1.getAsJsonArray("inputs").get(0).getAsJsonObject().get("target").getAsString());
        assertEquals("minecraft:main", p1.get("output").getAsString());
    }

    @Test
    void 停用的层不占通道() {
        ShaderProject p = enabledProject(2);
        p.layers().get(0).setEnabled(false);
        ShaderProject.Build build = p.generate(GtProfile.MC_26_3, "post/x");
        assertEquals(1, build.passes().size());
    }

    @Test
    void uniform块的json与glsl逐项对应() {
        ShaderProject p = enabledProject(1);
        ShaderProject.Build build = p.generate(GtProfile.MC_26_3, "post/x");
        ShaderProject.PassBuild pass = build.passes().get(0);

        // 这一步在运行时也会做；对不上就直接抛，不让错位的块悄悄跑进画面
        PostEffectJsonBuilder.verifyLayout(pass.output().orderedParams(), pass.output().source());

        JsonObject json = PostEffectJsonBuilder.buildChain("gtshaders", build, new float[]{0, 0, 0, 1});
        JsonArray block = json.getAsJsonArray("passes").get(0).getAsJsonObject()
                .getAsJsonObject("uniforms").getAsJsonArray(GlslCodegen.PARAM_BLOCK);
        assertEquals(GlslCodegen.SYSTEM_UNIFORM, block.get(0).getAsJsonObject().get("name").getAsString());
        assertEquals(GlslCodegen.LAYER_UNIFORM, block.get(1).getAsJsonObject().get("name").getAsString());
        assertEquals(GlslCodegen.VIEWPORT_UNIFORM, block.get(2).getAsJsonObject().get("name").getAsString());
        assertEquals(pass.output().orderedParams().size() + 3, block.size());
        for (int i = 0; i < pass.output().orderedParams().size(); i++) {
            assertEquals(pass.output().orderedParams().get(i).name(),
                    block.get(i + 3).getAsJsonObject().get("name").getAsString());
        }
    }

    // ------------------------------------------------------------ 效果作用区域

    @Test
    void 取景框转GL坐标时翻转Y轴() {
        // screenquad.vsh 里 texCoord.y == 0 对应屏幕底部，而 GUI 坐标 y=0 是顶部。
        // 不翻转会出现「框在上面、效果在下面」这种极难定位的问题。
        ViewportRect v = new ViewportRect();
        v.set(0.1f, 0.2f, 0.6f, 0.9f);
        float[] uv = v.toGlUv();
        assertEquals(0.1f, uv[0], 1e-6, "u0 不翻转");
        assertEquals(1f - 0.9f, uv[1], 1e-6, "v0 = 1 - y1");
        assertEquals(0.6f, uv[2], 1e-6, "u1 不翻转");
        assertEquals(1f - 0.2f, uv[3], 1e-6, "v1 = 1 - y0");
    }

    @Test
    void 取景框平移越界时贴边且尺寸不变() {
        ViewportRect v = new ViewportRect();
        v.set(0.4f, 0.4f, 0.6f, 0.6f);
        v.translate(-10f, -10f);
        assertEquals(0f, v.x0(), 1e-6);
        assertEquals(0f, v.y0(), 1e-6);
        // 拖出屏幕再拖回来时尺寸不该被裁小
        assertEquals(0.2f, v.width(), 1e-5);
        assertEquals(0.2f, v.height(), 1e-5);
    }

    @Test
    void 取景框缩放不会塌成一个点() {
        ViewportRect v = new ViewportRect();
        v.set(0.4f, 0.4f, 0.6f, 0.6f);
        for (int i = 0; i < 200; i++) {
            v.zoomAround(0.5f, 0.5f, 0.5f);
        }
        assertTrue(v.width() > 0.01f, "缩到最小也要留得住，否则再也抓不回来");
        assertTrue(v.height() > 0.01f);
    }

    @Test
    void 默认取景框是全屏() {
        ShaderProject p = ShaderProject.createDefault();
        assertTrue(p.viewport().isFullScreen());
        float[] uv = p.viewport().toGlUv();
        assertArrayEquals(new float[]{0f, 0f, 1f, 1f}, uv, 1e-6f);
    }

    @Test
    void 生成的着色器包含取景框裁剪() {
        ShaderLayer layer = new ShaderLayer("l", GlslCodegen.defaultAuthorBody());
        String s = layer.generate(GtProfile.MC_26_3).source();
        assertTrue(s.contains("vec4 " + GlslCodegen.VIEWPORT_UNIFORM + ";"));
        assertTrue(s.contains("texCoord.x < " + GlslCodegen.VIEWPORT_UNIFORM + ".x"),
                "框外应当直接返回原画面");
    }

    @Test
    void 图层增删移动() {
        ShaderProject p = enabledProject(1);
        p.addLayer();
        assertEquals(2, p.layerCount());
        assertEquals(1, p.selectedIndex());

        assertTrue(p.moveSelected(-1));
        assertEquals(0, p.selectedIndex());
        assertFalse(p.moveSelected(-1), "已经在最上面了");

        p.duplicateSelected();
        assertEquals(3, p.layerCount());
        assertTrue(p.removeSelected());
        assertEquals(2, p.layerCount());
    }

    // ------------------------------------------------------------ 默认无效果

    @Test
    void 新建工程默认不产生任何效果() {
        // 这是「打开编辑器时玩家的画面必须和没装 mod 一样」的底线，
        // 一旦被改回默认启用，回归会以「一进游戏画面就变了」的形式出现，很难归因
        ShaderProject p = ShaderProject.createDefault();
        assertEquals(1, p.layerCount(), "要留一层给作者落笔");
        assertFalse(p.layers().get(0).isEnabled(), "但它必须是停用的");
        assertFalse(p.hasEffect());
        assertTrue(p.generate(GtProfile.MC_26_3, "post/x").passes().isEmpty(),
                "没有启用的层就不该生成任何通道");
    }

    @Test
    void 新增的效果层也默认停用() {
        ShaderProject p = ShaderProject.createDefault();
        assertFalse(p.addLayer().isEnabled());
    }

    @Test
    void 默认源码能编译且不改变画面() {
        // 起手源码必须是原样输出：既能立刻编译通过，又保证勾选启用也看不出变化，
        // 「启用了却什么都没发生」在这里是正确行为而不是 bug
        ShaderLayer layer = new ShaderLayer("l", GlslCodegen.defaultAuthorBody());
        GlslCodegen.Output out = layer.generate(GtProfile.MC_26_3);
        assertTrue(out.wrapped(), "起手源码必须带一个 main，否则新建就是坏的");
        assertTrue(layer.params().isEmpty(), "起手源码不该预置参数");
        assertTrue(out.source().contains("fragColor = texture(InSampler, texCoord);"));
    }

    @Test
    void 效果可以被全部清除() {
        ShaderProject p = enabledProject(2);
        assertTrue(p.hasEffect());

        assertTrue(p.disableAllLayers(), "停用全部");
        assertFalse(p.hasEffect());
        assertEquals(2, p.layerCount(), "停用不该丢掉源码");
        assertFalse(p.disableAllLayers(), "已经全停用了，再来一次没有变化");

        // 一路删到零层也必须允许——否则「彻底清空」是做不到的
        assertTrue(p.removeSelected());
        assertTrue(p.removeSelected());
        assertFalse(p.removeSelected(), "已经没有层了");
        assertEquals(0, p.layerCount());
        assertTrue(p.generate(GtProfile.MC_26_3, "post/x").passes().isEmpty());
    }

    @Test
    void 示例模板都能编译出完整着色器() {
        for (Samples.Sample sample : Samples.all()) {
            ShaderLayer layer = new ShaderLayer("l", sample.source());
            GlslCodegen.Output out = layer.generate(GtProfile.MC_26_3);
            assertTrue(out.wrapped(), sample.nameKey() + " 缺少 void main()");
            assertFalse(layer.params().isEmpty(), sample.nameKey() + " 应当带可调参数");
            PostEffectJsonBuilder.verifyLayout(out.orderedParams(), out.source());
        }
    }

    // ------------------------------------------------------------ 粘贴清洗

    @Test
    void 粘贴时替换掉GLSL读不懂的字符() {
        // 全角分号和不换行空格在编辑器里跟正常字符几乎一模一样，
        // 让它们混进代码 = 一个只有驱动看得见、作者怎么盯都盯不出来的编译错误
        String src = "float a ＝ 1.0；\r\n﻿vec2 b = vec2（0.0）;\n";
        GlslSanitizer.Result r = GlslSanitizer.sanitize(src);
        assertTrue(r.text().contains("float a = 1.0;"));
        assertTrue(r.text().contains("vec2 b = vec2(0.0);"));
        assertFalse(r.text().contains("\r"));
        assertFalse(r.text().contains("﻿"));
        assertTrue(r.replaced() > 0, "换掉了东西就该报出来，静悄悄改代码比不改还吓人");
    }

    @Test
    void 注释里的中文标点原样保留() {
        // 中文注释里的「，。」是作者想要的排版，不该顺手改成半角；
        // 真正会让编译失败的全角符号几乎只出现在代码里
        String src = "// 强度，越大越明显。\nfloat a＝1.0;\n";
        GlslSanitizer.Result r = GlslSanitizer.sanitize(src);
        assertTrue(r.text().contains("// 强度，越大越明显。"), "注释不动");
        assertTrue(r.text().contains("float a=1.0;"), "代码里的全角等号要换掉");
    }

    @Test
    void 制表符展开成空格且干净文本不被改动() {
        assertEquals("    x", GlslSanitizer.sanitize("\tx").text());

        String clean = "void main() {\n    fragColor = vec4(1.0);\n}\n";
        GlslSanitizer.Result r = GlslSanitizer.sanitize(clean);
        assertEquals(clean, r.text());
        assertEquals(0, r.replaced());
    }

    @Test
    void 输入法直接打出的全角符号也换掉() {
        assertEquals(";", GlslSanitizer.sanitizeChar('；'));
        assertEquals("(", GlslSanitizer.sanitizeChar('（'));
        assertEquals("a", GlslSanitizer.sanitizeChar('a'));
    }

    // ------------------------------------------------------------ 原版效果导入

    /**
     * 26.3 之前的 box_blur.fsh 原文（{@code #moj_import} + 裸 in/out）。
     *
     * <p>留着它不是为了兼容旧版本，而是为了钉住<b>导入老文件</b>这条路：
     * 用户手里的老资源包就是这个样子，剥不干净就会和生成的头部撞成重复定义。
     */
    private static final String VANILLA_BOX_BLUR = """
            #version 330

            #moj_import <minecraft:globals.glsl>

            uniform sampler2D InSampler;

            layout(std140) uniform SamplerInfo {
                vec2 OutSize;
                vec2 InSize;
            };

            layout(std140) uniform BlurConfig {
                vec2 BlurDir;
                float Radius;
            };

            in vec2 texCoord;

            out vec4 fragColor;

            void main() {
                vec2 oneTexel = 1.0 / InSize;
                vec2 sampleStep = oneTexel * BlurDir;
                fragColor = texture(InSampler, texCoord + sampleStep * Radius);
            }
            """;

    /**
     * 26.3 的 box_blur.fsh 原文，一字未改。
     *
     * <p>和上面那份老写法成对存在：拖进来的可能是任一种，导入器都得认。
     * 差异在 {@code #extension}、{@code #include}、以及 in/out 前面的
     * {@code layout(location = N)}。
     */
    private static final String VANILLA_BOX_BLUR_26_3 = """
            #version 330
            #extension GL_ARB_separate_shader_objects : require

            #include <minecraft:globals.glsl>

            uniform sampler2D InSampler;

            layout(std140) uniform SamplerInfo {
                vec2 OutSize;
                vec2 InSize;
            };

            layout(std140) uniform BlurConfig {
                vec2 BlurDir;
                float Radius;
            };

            layout(location = 0) in vec2 texCoord;

            layout(location = 0) out vec4 fragColor;

            void main() {
                vec2 oneTexel = 1.0 / InSize;
                vec2 sampleStep = oneTexel * BlurDir;
                fragColor = texture(InSampler, texCoord + sampleStep * Radius);
            }
            """;

    @Test
    void 二六三格式的原版着色器同样能剥干净头部() {
        String src = VanillaImporter.toAuthorSource(VANILLA_BOX_BLUR_26_3, "minecraft:post/box_blur",
                List.of(new VanillaImporter.Uniform("BlurDir", "vec2", new float[]{1f, 0f, 0f, 0f}),
                        new VanillaImporter.Uniform("Radius", "float", new float[]{2f, 0f, 0f, 0f})));

        assertFalse(src.contains("#version"));
        assertFalse(src.contains("#extension"), "26.3 的扩展声明也得剥掉");
        assertFalse(src.contains("#include"), "26.3 用 #include 而不是 #moj_import");
        assertFalse(src.contains("uniform sampler2D InSampler"));
        assertFalse(src.contains("SamplerInfo"));
        assertFalse(src.contains("BlurConfig"));
        // 带 layout(location) 前缀的 in/out 最容易漏：漏了就会和我们头部里的声明撞车，
        // 导入的效果一编译就报重复定义
        assertFalse(src.contains("in vec2 texCoord"), "带 location 前缀的 in 也得剥掉");
        assertFalse(src.contains("out vec4 fragColor"), "带 location 前缀的 out 也得剥掉");
        // 作者真正关心的那段必须原样还在
        assertTrue(src.contains("vec2 sampleStep = oneTexel * BlurDir;"));
        // uniform 块照样变成可调参数
        assertTrue(src.contains("@param name=BlurDir"));
        assertTrue(src.contains("@param name=Radius"));
    }

    @Test
    void 二六三格式的原版着色器不会被误判为不可导入() {
        assertNull(VanillaImporter.rejectReason(VANILLA_BOX_BLUR_26_3));
    }

    @Test
    void 原版着色器导入后头部被剥干净() {
        String src = VanillaImporter.toAuthorSource(VANILLA_BOX_BLUR, "minecraft:post/box_blur",
                List.of(new VanillaImporter.Uniform("BlurDir", "vec2", new float[]{1f, 0f, 0f, 0f}),
                        new VanillaImporter.Uniform("Radius", "float", new float[]{2f, 0f, 0f, 0f})));

        // 这些都由我们自己的头部统一提供，留着就是重复声明 = 编译失败
        assertFalse(src.contains("#version"));
        assertFalse(src.contains("#moj_import"));
        assertFalse(src.contains("uniform sampler2D InSampler"));
        assertFalse(src.contains("SamplerInfo"));
        assertFalse(src.contains("BlurConfig"));
        assertFalse(src.contains("in vec2 texCoord;"));
        assertFalse(src.contains("out vec4 fragColor;"));
        // 作者真正关心的那段必须原样还在
        assertTrue(src.contains("vec2 sampleStep = oneTexel * BlurDir;"));
    }

    @Test
    void 原版uniform块自动变成可调参数() {
        String src = VanillaImporter.toAuthorSource(VANILLA_BOX_BLUR, "minecraft:post/box_blur",
                List.of(new VanillaImporter.Uniform("BlurDir", "vec2", new float[]{1f, 0f, 0f, 0f}),
                        new VanillaImporter.Uniform("Radius", "float", new float[]{2f, 0f, 0f, 0f})));

        ShaderLayer layer = new ShaderLayer("l", src);
        assertEquals(2, layer.params().size(), "两个 uniform 都该变成面板上的控件");
        assertEquals("BlurDir", layer.params().get(0).name());
        assertEquals(ParamType.VEC2, layer.params().get(0).type());
        assertEquals(1f, layer.params().get(0).get(0), 1e-6, "默认值来自 post effect JSON");
        assertEquals("Radius", layer.params().get(1).name());
        assertEquals(2f, layer.params().get(1).get(0), 1e-6);
        // 上限按默认值推断：2 → 4，滑块拖起来才有意义
        assertEquals(4f, layer.params().get(1).max(), 1e-6);

        // 导进来的东西必须真的能生成出完整着色器，否则「可编辑」只是说说而已
        GlslCodegen.Output out = layer.generate(GtProfile.MC_26_3);
        assertTrue(out.wrapped());
        PostEffectJsonBuilder.verifyLayout(out.orderedParams(), out.source());
    }

    @Test
    void 多输入的原版着色器被判为不可导入() {
        // spiderclip 需要 BlurSampler 和自定义顶点输出 scaledCoord，
        // 单层单输入的模型表达不了——这种情况必须说清楚原因，而不是导出一个编译不过的东西
        String spiderclip = """
                #version 330
                uniform sampler2D InSampler;
                uniform sampler2D BlurSampler;
                in vec2 texCoord;
                in vec2 scaledCoord;
                out vec4 fragColor;
                void main() { fragColor = texture(BlurSampler, scaledCoord); }
                """;
        String reason = VanillaImporter.rejectReason(spiderclip);
        assertNotNull(reason);
        assertTrue(reason.contains("BlurSampler"), "原因里要点名是哪个采样器：" + reason);

        assertNull(VanillaImporter.rejectReason(VANILLA_BOX_BLUR), "box_blur 是单输入的，可以导入");
    }

    // ------------------------------------------------------------ 取色换算

    @Test
    void HSV与RGB互转在整圈上都自洽() {
        // 取色器把色相单独存着，靠的就是这对函数能来回换算；
        // 差一点点就会表现为「拖了明度之后颜色慢慢漂移」，肉眼极难定位
        for (int i = 0; i <= 12; i++) {
            float h = i / 12f;
            float[] rgb = ColorMath.hsvToRgb(h, 0.8f, 0.9f);
            float[] hsv = ColorMath.rgbToHsv(rgb[0], rgb[1], rgb[2]);
            assertEquals(h % 1f, hsv[0], 1e-3, "色相 " + h);
            assertEquals(0.8f, hsv[1], 1e-3);
            assertEquals(0.9f, hsv[2], 1e-3);
        }
    }

    @Test
    void 灰阶没有色相() {
        float[] hsv = ColorMath.rgbToHsv(0.5f, 0.5f, 0.5f);
        assertEquals(0f, hsv[1], 1e-6, "饱和度为 0");
    }

    /** 造一个有 n 个已启用图层的工程。默认工程是「无效果」的，通道相关的用例得自己开。 */
    private static ShaderProject enabledProject(int n) {
        ShaderProject p = ShaderProject.createDefault();
        p.clearLayers();
        for (int i = 0; i < n; i++) {
            ShaderLayer layer = new ShaderLayer("L" + i, Samples.all().get(i % Samples.all().size()).source());
            layer.setEnabled(true);
            p.addLayer(layer);
        }
        p.setSelectedIndex(0);
        return p;
    }

    // ------------------------------------------------------------ 工程序列化 / 快照

    @Test
    void 工程序列化往返不丢任何设置() {
        // 快照的「回退」就是把这份 JSON 读回来。这里少存一个字段，回退就回不到原样，
        // 而且现象是「回去了但不太对」——比直接报错难查得多，所以逐项钉住。
        ShaderProject src = ShaderProject.createDefault();
        src.clearLayers();
        src.setName("我的工程");
        src.setExportProfile(GtProfile.MC_26_3);
        src.viewport().set(0.1f, 0.2f, 0.7f, 0.9f);

        ShaderLayer a = new ShaderLayer("底色", Samples.all().get(0).source());
        a.setEnabled(true);
        a.setStrength(0.42f);
        a.setBlendMode(BlendMode.MULTIPLY);
        a.setBilinear(true);
        a.params().get(0).set(0, 0.375f);
        src.addLayer(a);

        ShaderLayer b = new ShaderLayer("停用的一层", Samples.all().get(1).source());
        b.setEnabled(false);
        src.addLayer(b);

        ShaderProject back = ProjectStore.fromJson(ProjectStore.toJson(src), "fallback");

        assertEquals("我的工程", back.name());
        assertEquals(GtProfile.MC_26_3, back.exportProfile());
        assertEquals(0.1f, back.viewport().x0(), 1e-6);
        assertEquals(0.9f, back.viewport().y1(), 1e-6);
        assertEquals(2, back.layers().size());

        ShaderLayer ra = back.layers().get(0);
        assertEquals("底色", ra.name());
        assertTrue(ra.isEnabled());
        assertEquals(0.42f, ra.strength(), 1e-6);
        assertEquals(BlendMode.MULTIPLY, ra.blendMode());
        assertTrue(ra.isBilinear());
        assertEquals(a.authorSource(), ra.authorSource());
        // 参数值是按名字回填的，顺序可能与声明顺序不同，得按名字找
        ShaderParam restored = ra.params().stream()
                .filter(p -> p.name().equals(a.params().get(0).name())).findFirst().orElse(null);
        assertNotNull(restored);
        assertEquals(0.375f, restored.get(0), 1e-6);

        assertFalse(back.layers().get(1).isEnabled());
    }

    // ------------------------------------------------------------ i18n

    @Test
    void 中英翻译键完全一致() {
        // 少一条 key 的直接后果是界面上冒出原始 key，而那只有切到该语言才看得见。
        // 用测试把两份语言文件钉成同一套 key，就不会靠肉眼去发现。
        var zh = loadLangKeys("zh_cn");
        var en = loadLangKeys("en_us");
        assertFalse(zh.isEmpty(), "zh_cn.json 应当能从 classpath 读到");

        var missingInEn = new java.util.TreeSet<>(zh);
        missingInEn.removeAll(en);
        var missingInZh = new java.util.TreeSet<>(en);
        missingInZh.removeAll(zh);

        assertTrue(missingInEn.isEmpty(), "en_us 缺少这些 key：" + missingInEn);
        assertTrue(missingInZh.isEmpty(), "zh_cn 缺少这些 key：" + missingInZh);
    }

    @Test
    void 语言可切换且带回退() {
        GtLang.setCurrentLang("zh_cn");
        String zh = GtLang.get("gtshaders.panel.tab.props");
        GtLang.setCurrentLang("en_us");
        String en = GtLang.get("gtshaders.panel.tab.props");
        assertFalse(zh.equals(en), "中英文案不应相同，说明确实按语言取到了不同的值");

        // jar 里没有这门语言时回退到英文，而不是显示成原始 key
        GtLang.setCurrentLang("xx_yy");
        assertEquals(en, GtLang.get("gtshaders.panel.tab.props"));

        // 完全不存在的 key 原样返回，让缺失一眼可见
        assertEquals("gtshaders.no.such.key", GtLang.get("gtshaders.no.such.key"));
        GtLang.setCurrentLang("zh_cn");
    }

    @Test
    void 按键分类使用原版派生的翻译键() {
        // 原版 KeyMapping.Category.label() 走 id.toLanguageKey("key.category")，
        // 对 gtshaders:main 就是 key.category.gtshaders.main。写错了控制设置里会显示原始 key。
        assertTrue(loadLangKeys("zh_cn").contains("key.category.gtshaders.main"));
        assertTrue(loadLangKeys("en_us").contains("key.category.gtshaders.main"));
    }

    private static java.util.Set<String> loadLangKeys(String lang) {
        try (var in = CodegenTest.class.getResourceAsStream("/assets/gtshaders/lang/" + lang + ".json")) {
            if (in == null) {
                return java.util.Set.of();
            }
            String text = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            JsonObject obj = com.google.gson.JsonParser.parseString(text).getAsJsonObject();
            return new java.util.TreeSet<>(obj.keySet());
        } catch (Exception e) {
            throw new AssertionError("读取 " + lang + ".json 失败", e);
        }
    }
}
