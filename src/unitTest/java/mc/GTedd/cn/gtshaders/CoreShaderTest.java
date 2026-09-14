package mc.GTedd.cn.gtshaders;

import org.junit.jupiter.api.Test;
import mc.GTedd.cn.gtshaders.codegen.CoreShaderCodegen;
import mc.GTedd.cn.gtshaders.codegen.ParamScanner;
import mc.GTedd.cn.gtshaders.core.GtProfile;
import mc.GTedd.cn.gtshaders.core.ShaderKind;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 覆盖核心着色器的种类表与钩子注入。
 *
 * <p>这里守的是三条会「静默出错」的性质：注入点找不到时必须抛而不是生成一个没接上的着色器、
 * 另一个阶段的钩子必须删干净、行号偏移必须准确。三条都不会在编译期暴露。
 */
class CoreShaderTest {

    /** 一份足够像 26.3 原版的最小模板，够把注入逻辑的每条分支都走到。 */
    private static final String FSH_TEMPLATE = """
            #version 330
            #extension GL_ARB_separate_shader_objects : require

            #include <minecraft:fog.glsl>

            layout(location = 2) in vec4 vertexColor;

            #ifndef OIT_ALPHA_ONLY
            layout(location = 0) out vec4 fragColor;
            #endif

            void main() {
                vec4 color = vertexColor;
                #ifdef OIT_ALPHA_ONLY
                executeAlphaOnlyPhase(gl_FragCoord.z, color.a);
                #else
                fragColor = calculateFinalColor(color);
                #endif
            }
            """;

    private static final String VSH_TEMPLATE = """
            #version 330
            layout(location = 0) in vec3 Position;

            void main() {
                gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);
            }
            """;

    private static final String HOOK = """
            vec3 gtVertex(vec3 position) {
                return position + vec3(0.0, float(GT_VERTEX_INDEX) * 0.01, 0.0);
            }

            vec4 gtFragment(vec4 color) {
                return vec4(1.0 - color.rgb, color.a);
            }
            """;

    private static ShaderKind.Entry entity() {
        ShaderKind.Entry e = ShaderKind.find("entity");
        assertNotNull(e, "kinds.json 里应该有 entity");
        return e;
    }

    // ------------------------------------------------------------ 种类表

    @Test
    void 种类表读得到且分类都已声明() {
        List<ShaderKind.Entry> all = ShaderKind.all();
        assertFalse(all.isEmpty(), "kinds.json 没读到");
        for (ShaderKind.Entry e : all) {
            assertTrue(ShaderKind.categories().contains(e.category()),
                    e.id() + " 的分类 " + e.category() + " 没声明");
            assertFalse(e.name("zh_cn").equals(e.id()), e.id() + " 缺中文名");
            assertFalse(e.name("en_us").equals(e.id()), e.id() + " 缺英文名");
            assertFalse(e.stages().isEmpty(), e.id() + " 没写阶段");
        }
    }

    @Test
    void 改过名的种类用的是26_3的路径() {
        // 26.3 把 rendertype_clouds 改成了 clouds。用错名字的话导出的包会被安静地忽略——
        // 资源包里多一个谁也不读的文件，不报错，效果也不出现
        ShaderKind.Entry clouds = ShaderKind.find("clouds");
        assertNotNull(clouds);
        assertEquals("core/clouds", clouds.path());

        assertEquals("core/entity", entity().path());
    }

    // ------------------------------------------------------------ 注入

    @Test
    void 片段钩子包住原来的赋值右值() {
        CoreShaderCodegen.Output out = CoreShaderCodegen.generate(
                entity(), GtProfile.MC_26_3, "fsh", FSH_TEMPLATE, HOOK, List.of());
        assertTrue(out.hooked());
        assertTrue(out.source().contains("fragColor = gtFragment(calculateFinalColor(color));"),
                "没把原来的右值包进钩子：\n" + out.source());
    }

    @Test
    void 顶点钩子包住位置表达式() {
        CoreShaderCodegen.Output out = CoreShaderCodegen.generate(
                entity(), GtProfile.MC_26_3, "vsh", VSH_TEMPLATE, HOOK, List.of());
        assertTrue(out.hooked());
        assertTrue(out.source().contains("vec4(gtVertex(Position), 1.0)"),
                "没把位置表达式包进钩子：\n" + out.source());
    }

    @Test
    void 只保留本阶段用得上的那个钩子() {
        // 作者的 gtVertex 里用了 GT_VERTEX_INDEX（顶点专属），
        // 原样塞进片段着色器就是一句未定义变量——必须删干净
        String fsh = CoreShaderCodegen.generate(
                entity(), GtProfile.MC_26_3, "fsh", FSH_TEMPLATE, HOOK, List.of()).source();
        assertFalse(fsh.contains("vec3 gtVertex"), "片段着色器里不该有顶点钩子");
        assertTrue(fsh.contains("vec4 gtFragment"));

        String vsh = CoreShaderCodegen.generate(
                entity(), GtProfile.MC_26_3, "vsh", VSH_TEMPLATE, HOOK, List.of()).source();
        assertFalse(vsh.contains("vec4 gtFragment"), "顶点着色器里不该有片段钩子");
        assertTrue(vsh.contains("vec3 gtVertex"));
    }

    @Test
    void 删掉另一个钩子时行数不变() {
        // 行数变了的话，驱动报的行号换算回作者源码就会整体偏移，比不报错还难查
        String kept = CoreShaderCodegen.dropOtherHook(HOOK, true);
        assertEquals(HOOK.split("\n", -1).length, kept.split("\n", -1).length);
    }

    @Test
    void 顶点序号别名指向Vulkan风格的内置量() {
        // 26.3 用 ShaderC 按 Vulkan GLSL 语义编译，只认 gl_VertexIndex，没有兼容宏。
        // 写成 gl_VertexID 在本机驱动上照样能编过，进游戏才失败——所以必须由测试守住
        String vsh = CoreShaderCodegen.generate(
                entity(), GtProfile.MC_26_3, "vsh", VSH_TEMPLATE, HOOK, List.of()).source();
        assertTrue(vsh.contains("#define GT_VERTEX_INDEX gl_VertexIndex"));
        assertTrue(vsh.contains("#define GT_INSTANCE_INDEX gl_InstanceIndex"));
        assertFalse(vsh.contains("gl_VertexID"));
    }

    @Test
    void 有OIT时注入块跟着条件编译() {
        // alpha 阶段没有 fragColor 也没有大部分 varying，
        // 注入的代码引用了它们，不跟着一起 #ifndef 就编译不过
        String fsh = CoreShaderCodegen.generate(
                entity(), GtProfile.MC_26_3, "fsh", FSH_TEMPLATE, HOOK, List.of()).source();
        int begin = fsh.indexOf("// ==== GTShaders 注入开始 ====");
        int end = fsh.indexOf("// ==== GTShaders 注入结束 ====");
        String block = fsh.substring(begin, end);
        assertTrue(block.contains("#ifndef OIT_ALPHA_ONLY"));
        assertTrue(block.contains("#endif"));
    }

    @Test
    void 没有OIT的模板不加多余的条件编译() {
        String plain = FSH_TEMPLATE.replace("OIT_ALPHA_ONLY", "SOMETHING_ELSE");
        String fsh = CoreShaderCodegen.generate(
                entity(), GtProfile.MC_26_3, "fsh", plain, HOOK, List.of()).source();
        int begin = fsh.indexOf("// ==== GTShaders 注入开始 ====");
        int end = fsh.indexOf("// ==== GTShaders 注入结束 ====");
        assertFalse(fsh.substring(begin, end).contains("#ifndef"));
    }

    @Test
    void 找不到注入点时直接抛而不是安静地生成没接上的着色器() {
        // 这是最要命的失败模式：编译通过、游戏正常、效果没有，无从下手
        String noAnchor = """
                #version 330
                out vec4 result;
                void main() { result = vec4(1.0); }
                """;
        assertThrows(IllegalStateException.class, () -> CoreShaderCodegen.generate(
                entity(), GtProfile.MC_26_3, "fsh", noAnchor, HOOK, List.of()));
    }

    @Test
    void 没写钩子就原样返回模板加一个空注入块() {
        CoreShaderCodegen.Output out = CoreShaderCodegen.generate(
                entity(), GtProfile.MC_26_3, "fsh", FSH_TEMPLATE, "// 什么都没写\n", List.of());
        assertFalse(out.hooked());
        assertTrue(out.source().contains("fragColor = calculateFinalColor(color);"),
                "没写钩子时不该改动原版逻辑");
    }

    // ------------------------------------------------------------ 参数

    @Test
    void 参数编译成const而不是uniform() {
        // 资源包不能给核心着色器加 uniform 块（块由 Java 侧管线声明），只能走 const
        ParamScanner.Result scanned = ParamScanner.scan("""
                // @param name=Amount type=float min=0 max=1 default=0.25
                // @param name=Tint type=color3 default=#FF8000
                // @param name=Steps type=int min=1 max=8 default=3
                vec4 gtFragment(vec4 color) { return color * Amount; }
                """);
        String fsh = CoreShaderCodegen.generate(entity(), GtProfile.MC_26_3, "fsh",
                FSH_TEMPLATE, scanned.strippedBody(), scanned.params()).source();

        assertTrue(fsh.contains("const float Amount = 0.25;"), fsh);
        assertTrue(fsh.contains("const int Steps = 3;"), fsh);
        assertTrue(fsh.contains("const vec3 Tint = vec3("), fsh);
        assertFalse(fsh.contains("uniform GTParams"), "核心着色器不该出现 GTParams 块");
    }

    @Test
    void 浮点字面量必须带小数点() {
        // GLSL 里 1 是 int，赋给 float 会类型不匹配。整数值的参数最容易踩
        ParamScanner.Result scanned = ParamScanner.scan("""
                // @param name=One type=float min=0 max=2 default=1
                vec4 gtFragment(vec4 color) { return color * One; }
                """);
        String fsh = CoreShaderCodegen.generate(entity(), GtProfile.MC_26_3, "fsh",
                FSH_TEMPLATE, scanned.strippedBody(), scanned.params()).source();
        assertTrue(fsh.contains("const float One = 1.0;"), fsh);
    }

    @Test
    void 行号能换算回作者源码() {
        CoreShaderCodegen.Output out = CoreShaderCodegen.generate(
                entity(), GtProfile.MC_26_3, "fsh", FSH_TEMPLATE, HOOK, List.of());
        // 注入块的第一行就是作者源码的第一行
        assertEquals(1, CoreShaderCodegen.toAuthorLine(out.injectedAtLine(), out.injectedAtLine()));
        assertEquals(-1, CoreShaderCodegen.toAuthorLine(1, out.injectedAtLine()));

        String[] lines = out.source().split("\n", -1);
        assertTrue(lines[out.injectedAtLine() - 1].contains("gtVertex")
                        || lines[out.injectedAtLine() - 1].isBlank(),
                "injectedAtLine 指向的不是作者源码的第一行：" + lines[out.injectedAtLine() - 1]);
    }

    // ------------------------------------------------------------ 图集采样 helper

    /** 带 Sampler0 与 texCoord0 的模板——item / terrain 这类采图集的种类长这样。 */
    private static final String ATLAS_FSH_TEMPLATE = """
            #version 330
            #extension GL_ARB_separate_shader_objects : require

            #include <minecraft:fog.glsl>

            uniform sampler2D Sampler0;

            layout(location = 2) in vec4 vertexColor;
            layout(location = 5) in vec2 texCoord0;

            #ifndef OIT_ALPHA_ONLY
            layout(location = 0) out vec4 fragColor;
            #endif

            void main() {
                vec4 color = texture(Sampler0, texCoord0) * vertexColor;
                #ifdef OIT_ALPHA_ONLY
                executeAlphaOnlyPhase(gl_FragCoord.z, color.a);
                #else
                fragColor = calculateFinalColor(color);
                #endif
            }
            """;

    private static final String SPRITE_HOOK = """
            vec4 gtFragment(vec4 color) {
                return vec4(gtSpriteUV(vec2(16.0)), 0.0, color.a);
            }
            """;

    @Test
    void 用了gtSprite才注入图集helper() {
        // 没用到就一行都不多——和 gtProbe / gtAnchor 同一条规矩。
        // 每个核心着色器都白多十几个函数的话，产物读起来也不像一份正常的着色器了
        CoreShaderCodegen.Output plain = CoreShaderCodegen.generate(
                entity(), GtProfile.MC_26_3, "fsh", ATLAS_FSH_TEMPLATE, HOOK, List.of());
        assertFalse(plain.source().contains("vec2 gtSpriteUV"),
                "没用到 gtSprite 却注入了图集 helper");

        CoreShaderCodegen.Output used = CoreShaderCodegen.generate(
                entity(), GtProfile.MC_26_3, "fsh", ATLAS_FSH_TEMPLATE, SPRITE_HOOK, List.of());
        assertTrue(used.source().contains("vec2 gtSpriteUV(vec2 imgSize)"),
                "用了 gtSpriteUV 却没注入定义：" + used.source());
        assertTrue(used.source().contains("vec2 gtSpriteCoord"),
                "核心公式 gtSpriteCoord 没注入");
    }

    @Test
    void 顶点阶段不注入图集helper() {
        // texCoord0 是片段阶段的 varying，顶点阶段只有 UV0。
        // 注进去就是一句「未定义的 texCoord0」，而报错指向的是作者没写过的行
        CoreShaderCodegen.Output out = CoreShaderCodegen.generate(
                entity(), GtProfile.MC_26_3, "vsh", VSH_TEMPLATE, SPRITE_HOOK, List.of());
        assertFalse(out.source().contains("vec2 gtSpriteUV(vec2 imgSize)"),
                "顶点阶段不该注入图集 helper");
    }

    @Test
    void 没有纹理的种类不注入图集helper() {
        // lightmap / sky / lines 没有 Sampler0。硬注入的话报错会落在生成的代码上，
        // 作者在编辑器里看到的是一段自己没写过的东西；不注入则报「未定义的 gtSpriteUV」，
        // 而那一行正是他自己写的
        String noTexture = """
                #version 330
                layout(location = 0) out vec4 fragColor;

                void main() {
                    fragColor = vec4(1.0);
                }
                """;
        CoreShaderCodegen.Output out = CoreShaderCodegen.generate(
                entity(), GtProfile.MC_26_3, "fsh", noTexture, SPRITE_HOOK, List.of());
        assertFalse(out.source().contains("vec2 gtSpriteUV(vec2 imgSize)"),
                "模板里没有 Sampler0，不该注入图集 helper");
    }

    @Test
    void 图集helper不打乱作者的报错行号() {
        // helper 注入在参数常量之前，而 injectedAtLine 是在那整段之后才数的——
        // 多出来的十几行必须被一起算进去，否则作者的每一条报错都会整体偏移
        CoreShaderCodegen.Output out = CoreShaderCodegen.generate(
                entity(), GtProfile.MC_26_3, "fsh", ATLAS_FSH_TEMPLATE, SPRITE_HOOK, List.of());
        String[] lines = out.source().lines().toArray(String[]::new);
        assertTrue(lines[out.injectedAtLine() - 1].startsWith("vec4 gtFragment"),
                "injectedAtLine 指向的不是作者源码的第一行：" + lines[out.injectedAtLine() - 1]);
    }

    // ------------------------------------------------------------ 示例库

    @Test
    void 示例库每条都读得到且种类存在() {
        List<mc.GTedd.cn.gtshaders.library.CoreLibrary.Entry> all =
                mc.GTedd.cn.gtshaders.library.CoreLibrary.all();
        assertFalse(all.isEmpty(), "corelib/index.json 没读到");
        for (mc.GTedd.cn.gtshaders.library.CoreLibrary.Entry e : all) {
            assertNotNull(mc.GTedd.cn.gtshaders.library.CoreLibrary.loadSource(e.id()),
                    e.id() + " 在索引里但读不到源码");
            assertNotNull(ShaderKind.find(e.kindId()),
                    e.id() + " 指向的种类不存在：" + e.kindId());
            assertFalse(e.name("zh_cn").equals(e.id()), e.id() + " 缺中文名");
            assertFalse(e.name("en_us").equals(e.id()), e.id() + " 缺英文名");
        }
    }

    @Test
    void 示例只写钩子不写main() {
        // 写了 main 就会被当成「整份接管」，注入逻辑整个绕过去——
        // 示例的价值恰恰在于演示钩子怎么写，不能自己先破了例
        for (mc.GTedd.cn.gtshaders.library.CoreLibrary.Entry e :
                mc.GTedd.cn.gtshaders.library.CoreLibrary.all()) {
            String src = mc.GTedd.cn.gtshaders.library.CoreLibrary.loadSource(e.id());
            assertFalse(CoreShaderCodegen.isRawOverride(src),
                    e.id() + " 里写了 main()，会被当成整份接管");
            assertTrue(src.contains("gtFragment") || src.contains("gtVertex"),
                    e.id() + " 一个钩子都没写");
        }
    }

    @Test
    void 示例的钩子必须是它那个种类支持的() {
        // 给只有片段钩子的种类写顶点钩子，作者会白写——代码在，但永远不会被调用
        for (mc.GTedd.cn.gtshaders.library.CoreLibrary.Entry e :
                mc.GTedd.cn.gtshaders.library.CoreLibrary.all()) {
            ShaderKind.Entry kind = ShaderKind.find(e.kindId());
            String src = mc.GTedd.cn.gtshaders.library.CoreLibrary.loadSource(e.id());
            if (src.contains("vec3 gtVertex")) {
                assertTrue(kind.vertexHook() && kind.hasVertexStage(),
                        e.id() + " 写了顶点钩子，但 " + kind.id() + " 不支持");
            }
            if (src.contains("vec4 gtFragment")) {
                assertTrue(kind.fragmentHook(),
                        e.id() + " 写了片段钩子，但 " + kind.id() + " 不支持");
            }
        }
    }

    @Test
    void 用到GameTime时自动补上Globals块() {
        // 这是新手最先想用的东西，而多数核心着色器并不引 globals.glsl。
        // 不自动补的话，第一个想做「随时间变化」的人就会卡在一句未定义变量上
        String tpl = """
                #version 330
                #include <minecraft:fog.glsl>
                layout(location = 0) out vec4 fragColor;
                void main() { fragColor = vec4(1.0); }
                """;
        String out = CoreShaderCodegen.generate(entity(), GtProfile.MC_26_3, "fsh", tpl,
                "vec4 gtFragment(vec4 c) { return c * GameTime; }", List.of()).source();
        assertTrue(out.contains("#include <minecraft:globals.glsl>"), out);
    }

    @Test
    void 条件引入的Globals被解开而不是再插一条() {
        // 26.3 的 item.fsh / entity.fsh 把 globals 包在 #ifdef GLINT 里。
        // 再插一条的话，GLINT 变体里 Globals 会被声明两次——那是编译错误
        String tpl = """
                #version 330
                #ifdef GLINT
                #include <minecraft:globals.glsl>
                #endif
                #include <minecraft:fog.glsl>
                layout(location = 0) out vec4 fragColor;
                void main() { fragColor = vec4(1.0); }
                """;
        String out = CoreShaderCodegen.generate(entity(), GtProfile.MC_26_3, "fsh", tpl,
                "vec4 gtFragment(vec4 c) { return c * GameTime; }", List.of()).source();
        assertEquals(1, countOccurrences(out, "globals.glsl"),
                "globals 被引了不止一次：\n" + out);
        assertFalse(out.contains("#ifdef GLINT\n#include <minecraft:globals.glsl>"),
                "条件没解开");
    }

    private static int countOccurrences(String haystack, String needle) {
        int n = 0;
        int at = 0;
        while ((at = haystack.indexOf(needle, at)) >= 0) {
            n++;
            at += needle.length();
        }
        return n;
    }

    @Test
    void 写了main就整份接管不再注入() {
        String raw = """
                #version 330
                layout(location = 0) out vec4 fragColor;
                void main() { fragColor = vec4(0.5); }
                """;
        CoreShaderCodegen.Output out = CoreShaderCodegen.generate(
                entity(), GtProfile.MC_26_3, "fsh", FSH_TEMPLATE, raw, List.of());
        assertFalse(out.hooked());
        assertFalse(out.source().contains("calculateFinalColor"),
                "整份接管时不该把原版模板混进来");
        assertTrue(out.source().contains("fragColor = vec4(0.5);"));
    }

    @Test
    void 拖入的原版核心着色器能认出种类() {
        // 文件名是原版固定的，那反而是最可靠的线索——内容可能已经被改得面目全非
        assertEquals("entity", mc.GTedd.cn.gtshaders.codegen.ShaderImport.detectCoreKind("entity.fsh"));
        assertEquals("item", mc.GTedd.cn.gtshaders.codegen.ShaderImport.detectCoreKind("core/item.vsh"));
        assertEquals("lines", mc.GTedd.cn.gtshaders.codegen.ShaderImport.detectCoreKind("rendertype_lines.fsh"));
        // 改过名的两版都要认得出
        assertEquals("clouds", mc.GTedd.cn.gtshaders.codegen.ShaderImport.detectCoreKind("clouds.fsh"));
        assertEquals("clouds", mc.GTedd.cn.gtshaders.codegen.ShaderImport.detectCoreKind("rendertype_clouds.fsh"));
        assertEquals(null, mc.GTedd.cn.gtshaders.codegen.ShaderImport.detectCoreKind("box_blur.fsh"));
    }

    // ------------------------------------------------------------ 与工程模型的集成

    @Test
    void 核心层不占后处理通道() {
        // 混进后处理链的话乒乓方向会算错，画面整个黑掉——而且完全不报错
        mc.GTedd.cn.gtshaders.core.ShaderProject p = new mc.GTedd.cn.gtshaders.core.ShaderProject("mix");
        p.clearLayers();

        mc.GTedd.cn.gtshaders.core.ShaderLayer post = new mc.GTedd.cn.gtshaders.core.ShaderLayer(
                "post", "void main() { fragColor = texture(InSampler, texCoord); }");
        post.setEnabled(true);
        p.addLayer(post);

        p.addCoreLayer(entity()).setEnabled(true);

        assertEquals(2, p.enabledLayers().size());
        assertEquals(1, p.enabledPostLayers().size(), "核心层混进了后处理链");
        assertEquals(1, p.enabledCoreLayers().size());
        assertEquals(1, p.generate(GtProfile.MC_26_3, "post/x").passes().size());
    }

    @Test
    void 同一个种类只保留一层() {
        // 核心着色器是覆盖不是叠加，两层同种类只有后一层生效，
        // 多出来的那层只会让人以为自己写的东西没生效
        mc.GTedd.cn.gtshaders.core.ShaderProject p = new mc.GTedd.cn.gtshaders.core.ShaderProject("dup");
        p.clearLayers();
        mc.GTedd.cn.gtshaders.core.ShaderLayer a = p.addCoreLayer(entity());
        mc.GTedd.cn.gtshaders.core.ShaderLayer b = p.addCoreLayer(entity());
        assertEquals(a, b, "同种类应当复用已有的层而不是再加一个");
        assertEquals(1, p.layerCount());
    }

    @Test
    void 核心层的种类能存能读() {
        mc.GTedd.cn.gtshaders.core.ShaderProject p = new mc.GTedd.cn.gtshaders.core.ShaderProject("io");
        p.clearLayers();
        p.addCoreLayer(entity()).setEnabled(true);
        p.addCoreLayer(ShaderKind.find("sky"));

        mc.GTedd.cn.gtshaders.core.ShaderProject back = mc.GTedd.cn.gtshaders.workspace.ProjectStore.fromJson(
                mc.GTedd.cn.gtshaders.workspace.ProjectStore.toJson(p), "io");

        assertEquals(2, back.layerCount());
        assertEquals("entity", back.layers().get(0).kindId());
        assertEquals("sky", back.layers().get(1).kindId());
        assertTrue(back.layers().get(0).isCore());
    }

    @Test
    void 老工程文件里没有kind字段时当成后处理层() {
        // 向后兼容：kind 缺省即"后处理"，老工程不该被读成核心层
        com.google.gson.JsonObject root = new com.google.gson.JsonObject();
        root.addProperty("name", "old");
        com.google.gson.JsonArray layers = new com.google.gson.JsonArray();
        com.google.gson.JsonObject lo = new com.google.gson.JsonObject();
        lo.addProperty("name", "L1");
        lo.addProperty("source", "void main() { fragColor = vec4(1.0); }");
        layers.add(lo);
        root.add("layers", layers);

        mc.GTedd.cn.gtshaders.core.ShaderProject p =
                mc.GTedd.cn.gtshaders.workspace.ProjectStore.fromJson(root, "old");
        assertFalse(p.layers().get(0).isCore());
        assertEquals(null, p.layers().get(0).kindId());
    }

    @Test
    void 起手模板按种类给出对应的钩子() {
        String withVertex = CoreShaderCodegen.defaultBody(entity());
        assertTrue(withVertex.contains("vec3 gtVertex"));
        assertTrue(withVertex.contains("vec4 gtFragment"));

        // lightmap 只有片段阶段，不该给顶点钩子——给了作者会白写
        ShaderKind.Entry lightmap = ShaderKind.find("lightmap");
        assertNotNull(lightmap);
        String fragOnly = CoreShaderCodegen.defaultBody(lightmap);
        assertFalse(fragOnly.contains("vec3 gtVertex"));
        assertTrue(fragOnly.contains("vec4 gtFragment"));
    }
}
