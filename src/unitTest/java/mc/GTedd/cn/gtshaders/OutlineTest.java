package mc.GTedd.cn.gtshaders;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import mc.GTedd.cn.gtshaders.codegen.GlslCodegen;
import mc.GTedd.cn.gtshaders.codegen.PostEffectJsonBuilder;
import mc.GTedd.cn.gtshaders.core.AnchorSlot;
import mc.GTedd.cn.gtshaders.core.GtProfile;
import mc.GTedd.cn.gtshaders.core.ShaderLayer;
import mc.GTedd.cn.gtshaders.core.ShaderProject;
import mc.GTedd.cn.gtshaders.export.ResourcePackExporter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 覆盖<b>实体轮廓层</b>：覆盖原版 {@code minecraft:entity_outline} 那条链的那一套。
 *
 * <h2>这条路径为什么值得单独钉</h2>
 *
 * <p>它是这个工具里唯一能拿到<b>逐实体信息</b>的后处理路径，而它成立的前提是一串
 * 从源码里读出来、彼此咬合的事实——任何一环记错都会在游戏里表现成「什么都没发生」：
 *
 * <ul>
 *   <li>{@code GameRenderer} 给 {@code postEffectId} 那条链传的是
 *       {@code MAIN_TARGETS = {main}}，所以 {@code /posteffect} <b>拿不到</b> {@code entity_outline}；</li>
 *   <li>{@code LevelRenderer} 给 {@code minecraft:entity_outline} 传的是
 *       {@code OUTLINE_TARGETS = {main, entity_outline}}，两张图都拿得到；</li>
 *   <li>结果必须落回 {@code minecraft:entity_outline}——引擎最后做
 *       {@code doEntityOutline()} 的 {@code blitAndBlendToTexture} 时拿的是它；</li>
 *   <li>{@code SamplerInfo} 里先一个 {@code OutSize}，再<b>按 inputs 声明顺序</b>每个输入一个
 *       {@code vec2}（{@code PostPass} 里那个循环），所以 GLSL 的第三个成员必须对应第二个 input。</li>
 * </ul>
 *
 * <p>最后一条尤其阴险：顺序反了两个尺寸互换，模糊半径之类会算错，<b>但不会有任何报错</b>。
 */
class OutlineTest {

    private static final String OUTLINE_BODY = """
            // @param name=Tint type=color3 default=#66E0FF zh_cn=染色
            // @param name=Glow type=float min=0 max=3 default=1.2 zh_cn=边缘发光
            void main() {
                float inside = gtMask();
                float edge = gtMaskEdge();
                vec3 col = mix(gtScene(), Tint, edge) * (0.4 + Glow * edge);
                fragColor = vec4(col, max(inside * 0.3, edge));
            }
            """;

    private static final String POST_BODY = """
            // @param name=Amount type=float min=0 max=1 default=0.5
            void main() {
                fragColor = vec4(texture(InSampler, texCoord).rgb * Amount, 1.0);
            }
            """;

    private static ShaderLayer outlineLayer(String body) {
        ShaderLayer l = new ShaderLayer("O", body);
        l.setOutline(true);
        l.setEnabled(true);
        return l;
    }

    private static ShaderLayer postLayer(String body) {
        ShaderLayer l = new ShaderLayer("P", body);
        l.setEnabled(true);
        return l;
    }

    private static ShaderProject project(ShaderLayer... layers) {
        ShaderProject p = new ShaderProject("outline");
        p.clearLayers();
        for (ShaderLayer l : layers) {
            p.addLayer(l);
        }
        p.setExportProfile(GtProfile.MC_26_3);
        return p;
    }

    // ------------------------------------------------------------ 双采样器

    @Test
    void 轮廓层多一个场景采样器() {
        String s = outlineLayer(OUTLINE_BODY).generate(GtProfile.MC_26_3).source();
        assertTrue(s.contains("uniform sampler2D InSampler;"), "遮罩采样器");
        assertTrue(s.contains("uniform sampler2D SceneSampler;"), "主画面采样器");
    }

    @Test
    void samplerInfo的三个vec2顺序与json输入顺序一致() {
        // 原版 PostPass 先写 OutSize，再按 inputs 的声明顺序每个输入写一个 vec2。
        // 顺序反了不会报错，只会让 InSize / SceneSize 互换——模糊半径之类会静悄悄算错
        String s = outlineLayer(OUTLINE_BODY).generate(GtProfile.MC_26_3).source();
        assertTrue(s.contains("""
                layout(std140) uniform SamplerInfo {
                    vec2 OutSize;
                    vec2 InSize;
                    vec2 SceneSize;
                };"""), "轮廓层的 SamplerInfo 布局");

        ShaderProject.Build build = project(outlineLayer(OUTLINE_BODY))
                .generateOutline(GtProfile.MC_26_3, "post/x");
        JsonObject json = PostEffectJsonBuilder.buildOutlineChain("gtshaders", build,
                new float[]{0, 0, 0, 1});
        JsonArray inputs = json.getAsJsonArray("passes").get(0).getAsJsonObject()
                .getAsJsonArray("inputs");
        assertEquals(2, inputs.size());
        assertEquals(GlslCodegen.MAIN_SAMPLER_NAME,
                inputs.get(0).getAsJsonObject().get("sampler_name").getAsString(),
                "第一个输入必须是遮罩，对应 SamplerInfo 的 InSize");
        assertEquals(PostEffectJsonBuilder.SCENE_SAMPLER_NAME,
                inputs.get(1).getAsJsonObject().get("sampler_name").getAsString(),
                "第二个输入必须是主画面，对应 SamplerInfo 的 SceneSize");
    }

    @Test
    void 后处理层不多出场景采样器() {
        String s = postLayer(POST_BODY).generate(GtProfile.MC_26_3).source();
        assertFalse(s.contains("SceneSampler"), "后处理层拿不到 entity_outline，也不该有第二个采样器");
        assertFalse(s.contains("vec2 SceneSize;"));
        assertTrue(s.contains("""
                layout(std140) uniform SamplerInfo {
                    vec2 OutSize;
                    vec2 InSize;
                };"""));
    }

    @Test
    void 轮廓helper只在轮廓层注入() {
        String outline = outlineLayer(OUTLINE_BODY).generate(GtProfile.MC_26_3).source();
        for (String fn : new String[]{"float gtMask()", "vec3 gtMaskColor()", "float gtMaskEdge()",
                "vec3 gtScene()", "vec3 gtSceneAt(vec2 uv)", "bool gtMaskIs("}) {
            assertTrue(outline.contains(fn), "缺 helper：" + fn);
        }
        String post = postLayer(POST_BODY).generate(GtProfile.MC_26_3).source();
        assertFalse(post.contains("gtMask"), "后处理层不该注入轮廓 helper");
    }

    @Test
    void 轮廓层没有混合外壳只有强度() {
        // 合成方式由引擎的 blitAndBlendToTexture 定死为 alpha 混合，
        // 再给一个混合模式只会让人拖了半天发现没用
        String s = outlineLayer(OUTLINE_BODY).generate(GtProfile.MC_26_3).source();
        assertFalse(s.contains("gtBlend"), "轮廓层不该有混合模式外壳");
        assertTrue(s.contains("fragColor.a = clamp(fragColor.a * GTStrength, 0.0, 1.0);"),
                "强度必须乘在 alpha 上，那才是这条路径里唯一有意义的整体淡出");
    }

    @Test
    void 轮廓层仍能原样切回作者源码() {
        GlslCodegen.Output out = outlineLayer(OUTLINE_BODY).generate(GtProfile.MC_26_3);
        String back = GlslCodegen.extractAuthorBody(out.source());
        assertNotNull(back);
        assertEquals(OUTLINE_BODY.stripTrailing(), back.stripTrailing());
    }

    @Test
    void 轮廓层的错误行号仍然准确() {
        GlslCodegen.Output out = outlineLayer(OUTLINE_BODY).generate(GtProfile.MC_26_3);
        String[] lines = out.source().split("\n", -1);
        assertEquals(GlslCodegen.HEADER_END, lines[out.headerLineCount() - 1]);
        assertTrue(lines[out.headerLineCount()].startsWith("// @param"));
    }

    // ------------------------------------------------------------ 链的走向

    @Test
    void 轮廓链在entityoutline与swap之间乒乓() {
        ShaderProject.Build build = project(outlineLayer(OUTLINE_BODY))
                .generateOutline(GtProfile.MC_26_3, "post/x");
        JsonObject json = PostEffectJsonBuilder.buildOutlineChain("gtshaders", build,
                new float[]{0, 0, 0, 1});

        JsonArray passes = json.getAsJsonArray("passes");
        assertEquals(2, passes.size(), "单通道是奇数，要补一次 blit 把结果送回 entity_outline");

        JsonObject first = passes.get(0).getAsJsonObject();
        assertEquals("minecraft:entity_outline",
                first.getAsJsonArray("inputs").get(0).getAsJsonObject().get("target").getAsString(),
                "遮罩来自 entity_outline");
        assertEquals("minecraft:main",
                first.getAsJsonArray("inputs").get(1).getAsJsonObject().get("target").getAsString(),
                "场景来自 main");
        assertEquals("swap", first.get("output").getAsString());

        JsonObject blit = passes.get(1).getAsJsonObject();
        assertEquals("minecraft:post/blit", blit.get("fragment_shader").getAsString());
        assertEquals("minecraft:entity_outline", blit.get("output").getAsString(),
                "结果必须落回 entity_outline —— 引擎最后 blitAndBlendToTexture 拿的是它");
    }

    @Test
    void 每一个通道都挂着场景输入() {
        // 中间通道读的是 swap，但仍然要能拿到主画面，否则折射这类效果只有第一趟能用
        ShaderProject.Build build = project(outlineLayer(OUTLINE_BODY), outlineLayer(OUTLINE_BODY))
                .generateOutline(GtProfile.MC_26_3, "post/x");
        JsonObject json = PostEffectJsonBuilder.buildOutlineChain("gtshaders", build,
                new float[]{0, 0, 0, 1});
        JsonArray passes = json.getAsJsonArray("passes");
        assertEquals(2, passes.size(), "两个通道是偶数，不需要补 blit");
        for (int i = 0; i < 2; i++) {
            JsonArray inputs = passes.get(i).getAsJsonObject().getAsJsonArray("inputs");
            assertEquals(2, inputs.size(), "第 " + i + " 个通道少了输入");
            assertEquals("minecraft:main",
                    inputs.get(1).getAsJsonObject().get("target").getAsString());
        }
        assertEquals("swap", passes.get(0).getAsJsonObject().get("output").getAsString());
        assertEquals("minecraft:entity_outline",
                passes.get(1).getAsJsonObject().get("output").getAsString());
    }

    // ------------------------------------------------------------ 与后处理链互不干扰

    @Test
    void 轮廓层不占后处理通道() {
        // 混进后处理链会让乒乓方向算错，而那会让整个画面黑掉且没有任何报错
        ShaderProject p = project(postLayer(POST_BODY), outlineLayer(OUTLINE_BODY),
                postLayer(POST_BODY));
        assertEquals(2, p.enabledPostLayers().size());
        assertEquals(1, p.enabledOutlineLayers().size());
        assertEquals(2, p.generate(GtProfile.MC_26_3, "post/x").passes().size());
        assertEquals(1, p.generateOutline(GtProfile.MC_26_3, "post/o").passes().size());
    }

    @Test
    void 一个工程只允许一个轮廓层() {
        // entity_outline 是引擎写死的 id，一个资源包只能有一条链
        ShaderProject p = new ShaderProject("x");
        p.clearLayers();
        ShaderLayer a = p.addOutlineLayer();
        ShaderLayer b = p.addOutlineLayer();
        assertEquals(1, p.layerCount());
        assertTrue(a == b, "再加一次应当直接选中已有那层，而不是加一个永远不生效的");
        assertTrue(a.isOutline());
        assertFalse(a.isPost());
    }

    @Test
    void 层类型三选一互斥() {
        ShaderLayer l = new ShaderLayer("x", POST_BODY);
        assertTrue(l.isPost());
        assertFalse(l.isCore());
        assertFalse(l.isOutline());

        l.setOutline(true);
        assertFalse(l.isPost());
        assertTrue(l.isOutline());
    }

    // ------------------------------------------------------------ 锚点共存

    @Test
    void 轮廓层也能用世界锚点且块布局仍然对齐() {
        String body = """
                // @param name=Tint type=color3 default=#FFFFFF
                void main() {
                    float k = gtMask() * gtAnchorStrength(0);
                    fragColor = vec4(mix(gtScene(), Tint, k), k);
                }
                """;
        ShaderProject.Build build = project(outlineLayer(body))
                .generateOutline(GtProfile.MC_26_3, "post/x");
        var pass = build.passes().get(0);
        assertTrue(pass.output().usesAnchors());
        assertTrue(pass.output().outline());
        PostEffectJsonBuilder.verifyLayout(pass.output().orderedParams(), pass.output().source());

        JsonObject json = PostEffectJsonBuilder.buildOutlineChain("gtshaders", build,
                new float[]{0, 0, 0, 1});
        JsonArray block = json.getAsJsonArray("passes").get(0).getAsJsonObject()
                .getAsJsonObject("uniforms").getAsJsonArray(GlslCodegen.PARAM_BLOCK);
        assertEquals(GlslCodegen.ANCHOR_INFO_UNIFORM,
                block.get(3).getAsJsonObject().get("name").getAsString());
        assertEquals(3 + 1 + AnchorSlot.SLOTS * AnchorSlot.VEC4_PER_SLOT + 1, block.size());
    }

    // ------------------------------------------------------------ 导出

    private static Map<String, String> exportPack(ShaderProject p) throws IOException {
        Path dir = Files.createTempDirectory("gtshaders-outline-test");
        try {
            ResourcePackExporter.Result r = ResourcePackExporter.export(p, dir,
                    ResourcePackExporter.Options.defaults("fx"));
            Map<String, String> out = new HashMap<>();
            try (ZipInputStream in = new ZipInputStream(Files.newInputStream(r.file()),
                    StandardCharsets.UTF_8)) {
                ZipEntry e;
                while ((e = in.getNextEntry()) != null) {
                    if (!e.isDirectory()) {
                        out.put(e.getName(), new String(in.readAllBytes(), StandardCharsets.UTF_8));
                    }
                }
            }
            return out;
        } finally {
            try (var walk = Files.walk(dir)) {
                walk.sorted((a, b) -> b.getNameCount() - a.getNameCount()).forEach(x -> {
                    try {
                        Files.deleteIfExists(x);
                    } catch (IOException ignored) {
                        // 临时目录清不掉不该让用例失败
                    }
                });
            }
        }
    }

    @Test
    void 导出的轮廓链落在原版固定路径上() throws IOException {
        Map<String, String> pack = exportPack(project(outlineLayer(OUTLINE_BODY)));
        assertTrue(pack.containsKey("assets/minecraft/post_effect/entity_outline.json"),
                "轮廓链必须覆盖原版那个固定 id，实际条目：" + pack.keySet());
        // 只有轮廓层时不该写主链的 JSON——空 passes 的 post effect 会在 /posteffect 列表里
        // 多出一个点了没反应的 id
        assertFalse(pack.containsKey("assets/gtshaders/post_effect/fx.json"),
                "一个后处理层都没有，不该写主链 JSON");
    }

    @Test
    void 两条链可以共存在同一个包里() throws IOException {
        Map<String, String> pack = exportPack(project(postLayer(POST_BODY), outlineLayer(OUTLINE_BODY)));
        assertTrue(pack.containsKey("assets/gtshaders/post_effect/fx.json"), "主链");
        assertTrue(pack.containsKey("assets/minecraft/post_effect/entity_outline.json"), "轮廓链");

        // 两条链引用的着色器都得在包里
        for (String jsonPath : new String[]{
                "assets/gtshaders/post_effect/fx.json",
                "assets/minecraft/post_effect/entity_outline.json"}) {
            JsonObject json = JsonParser.parseString(pack.get(jsonPath)).getAsJsonObject();
            for (var el : json.getAsJsonArray("passes")) {
                String ref = el.getAsJsonObject().get("fragment_shader").getAsString();
                if (ref.startsWith("minecraft:")) {
                    continue;
                }
                String expected = "assets/" + ref.substring(0, ref.indexOf(':'))
                        + "/shaders/" + ref.substring(ref.indexOf(':') + 1) + ".fsh";
                assertTrue(pack.containsKey(expected),
                        jsonPath + " 引用了 " + ref + " 但包里没有 " + expected);
            }
        }
    }

    @Test
    void 导出的轮廓着色器满足二六三格式规则() throws IOException {
        Map<String, String> pack = exportPack(project(outlineLayer(OUTLINE_BODY)));
        String fsh = pack.entrySet().stream()
                .filter(e -> e.getKey().endsWith(".fsh")).findFirst().orElseThrow().getValue();
        assertTrue(fsh.startsWith("#version 330"));
        assertTrue(fsh.contains("#extension GL_ARB_separate_shader_objects : require"));
        assertTrue(fsh.contains("layout(location = 0) out vec4 fragColor;"));
        assertTrue(fsh.contains("uniform sampler2D SceneSampler;"));
    }
}
