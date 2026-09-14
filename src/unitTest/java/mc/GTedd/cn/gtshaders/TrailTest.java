package mc.GTedd.cn.gtshaders;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import mc.GTedd.cn.gtshaders.codegen.GlslCodegen;
import mc.GTedd.cn.gtshaders.codegen.PostEffectJsonBuilder;
import mc.GTedd.cn.gtshaders.core.AnchorSlot;
import mc.GTedd.cn.gtshaders.core.GtProfile;
import mc.GTedd.cn.gtshaders.core.ShaderLayer;
import mc.GTedd.cn.gtshaders.core.ShaderProject;
import mc.GTedd.cn.gtshaders.core.TrailSlot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 覆盖武器轨迹：uniform 块的三方一致、按需注入、与锚点并存时的先后顺序。
 *
 * <p>钉的是和 {@code AnchorTest} 同一类东西——<b>错了不会报错</b>。轨迹在 std140 块里
 * 占 49 个 vec4，GLSL 声明、post effect JSON、每帧写入的字节三处只要有一处对不上，
 * 后面所有用户参数就整体错位，画面上表现为一堆莫名其妙的颜色而不是任何一条错误信息。
 *
 * <p><b>顺序那条尤其要钉死</b>：锚点和轨迹都是「按需注入的系统块」，两个都用上时
 * 谁排前面决定了所有后续成员的字节偏移。三处各自写死了同一个顺序，改一处漏两处
 * 是这段代码最可能出的错。
 */
class TrailTest {

    private static ShaderLayer layer(String body) {
        ShaderLayer l = new ShaderLayer("L", body);
        l.setEnabled(true);
        return l;
    }

    private static ShaderProject projectWith(String body) {
        ShaderProject p = new ShaderProject("trail");
        p.clearLayers();
        p.addLayer(layer(body));
        return p;
    }

    /** 一段用到轨迹的最小源码。 */
    private static final String TRAILED = """
            // @param name=Tint type=color3 default=#FFFFFF
            // @param name=Amount type=float min=0 max=1 default=0.5
            void main() {
                float k = gtTrail(0.2) * Amount;
                fragColor = vec4(texture(InSampler, texCoord).rgb + Tint * k, 1.0);
            }
            """;

    /** 同时用到锚点和轨迹——两个按需块并存时的顺序全靠这段钉住。 */
    private static final String BOTH = """
            // @param name=Amount type=float min=0 max=1 default=0.5
            void main() {
                float k = gtTrail(0.2) * gtAnchorStrength(0) * Amount;
                fragColor = vec4(texture(InSampler, texCoord).rgb + k, 1.0);
            }
            """;

    private static final String PLAIN = """
            // @param name=Amount type=float min=0 max=1 default=0.5
            void main() {
                fragColor = vec4(texture(InSampler, texCoord).rgb * Amount, 1.0);
            }
            """;

    // ------------------------------------------------------------ 按需注入

    @Test
    void 没用到轨迹就一个字节都不注入() {
        GlslCodegen.Output out = layer(PLAIN).generate(GtProfile.MC_26_3);
        assertFalse(out.usesTrails());
        assertFalse(out.source().contains(GlslCodegen.TRAIL_INFO_UNIFORM),
                "没用到轨迹的着色器不该出现轨迹 uniform");
        assertFalse(out.source().contains("gtTrailAt"), "helper 也不该注入");
    }

    @Test
    void 用到轨迹才注入helper与uniform() {
        GlslCodegen.Output out = layer(TRAILED).generate(GtProfile.MC_26_3);
        assertTrue(out.usesTrails());
        assertTrue(out.source().contains("vec4 " + GlslCodegen.TRAIL_INFO_UNIFORM + ";"));
        assertTrue(out.source().contains(
                "vec4 " + GlslCodegen.TRAIL_A_UNIFORM + "[" + TrailSlot.SLOTS + "];"));
        assertTrue(out.source().contains(
                "vec4 " + GlslCodegen.TRAIL_B_UNIFORM + "[" + TrailSlot.SLOTS + "];"));
        assertTrue(out.source().contains("vec4 gtTrailAtUV(vec2 p, float duration)"));
        assertTrue(out.source().contains("#define GT_TRAIL_SLOTS " + TrailSlot.SLOTS));
    }

    @Test
    void 轨迹helper排在自动生成段之内() {
        GlslCodegen.Output out = layer(TRAILED).generate(GtProfile.MC_26_3);
        int helper = out.source().indexOf("int gtTrailCount()");
        int headerEnd = out.source().indexOf(GlslCodegen.HEADER_END);
        assertTrue(helper > 0 && helper < headerEnd,
                "helper 必须落在头部里，否则会被 extractAuthorBody 当成作者源码切回来");
    }

    @Test
    void 注入轨迹不影响错误行号映射() {
        GlslCodegen.Output out = layer(TRAILED).generate(GtProfile.MC_26_3);
        String[] lines = out.source().split("\n", -1);
        assertEquals(GlslCodegen.HEADER_END, lines[out.headerLineCount() - 1]);
        assertTrue(lines[out.headerLineCount()].startsWith("// @param"),
                "头部之后紧跟着的应当就是作者源码的第一行");
    }

    @Test
    void 生成的着色器仍能原样切回作者源码() {
        GlslCodegen.Output out = layer(TRAILED).generate(GtProfile.MC_26_3);
        String back = GlslCodegen.extractAuthorBody(out.source());
        assertNotNull(back);
        assertEquals(TRAILED.stripTrailing(), back.stripTrailing());
    }

    // ------------------------------------------------------------ 三方布局一致

    @Test
    void 轨迹块在glsl与json里逐项对应() {
        ShaderProject p = projectWith(TRAILED);
        ShaderProject.Build build = p.generate(GtProfile.MC_26_3, "post/x");
        ShaderProject.PassBuild pass = build.passes().get(0);

        PostEffectJsonBuilder.verifyLayout(pass.output().orderedParams(), pass.output().source());

        JsonArray block = paramBlock(build);
        assertEquals(GlslCodegen.SYSTEM_UNIFORM, name(block, 0));
        assertEquals(GlslCodegen.LAYER_UNIFORM, name(block, 1));
        assertEquals(GlslCodegen.VIEWPORT_UNIFORM, name(block, 2));
        assertEquals(GlslCodegen.TRAIL_INFO_UNIFORM, name(block, 3));

        // A 数组整段排完再排 B 数组——不是交错。写入端也必须分两轮
        for (int i = 0; i < TrailSlot.SLOTS; i++) {
            assertEquals(GlslCodegen.TRAIL_A_UNIFORM + i, name(block, 4 + i));
        }
        for (int i = 0; i < TrailSlot.SLOTS; i++) {
            assertEquals(GlslCodegen.TRAIL_B_UNIFORM + i, name(block, 4 + TrailSlot.SLOTS + i));
        }

        int head = 4 + TrailSlot.SLOTS * TrailSlot.VEC4_PER_SLOT;
        assertEquals(pass.output().orderedParams().size() + head, block.size(),
                "用户参数必须紧接在轨迹之后，不多不少");
        for (int i = 0; i < pass.output().orderedParams().size(); i++) {
            assertEquals(pass.output().orderedParams().get(i).name(), name(block, head + i));
        }
    }

    @Test
    void 锚点在前轨迹在后() {
        // 三处（GLSL 声明、JSON 条目、PreviewRuntime 的写入）各自写死了这个顺序。
        // 改一处漏两处的话，用户参数会整体偏移 49 个 vec4，而且不报任何错
        ShaderProject.Build build = projectWith(BOTH).generate(GtProfile.MC_26_3, "post/x");
        ShaderProject.PassBuild pass = build.passes().get(0);
        assertTrue(pass.output().usesAnchors());
        assertTrue(pass.output().usesTrails());

        String src = pass.output().source();
        assertTrue(src.indexOf(GlslCodegen.ANCHOR_INFO_UNIFORM) < src.indexOf(GlslCodegen.TRAIL_INFO_UNIFORM),
                "GLSL 块里锚点必须排在轨迹之前");

        JsonArray block = paramBlock(build);
        int anchorHead = 3;
        int trailHead = anchorHead + 1 + AnchorSlot.SLOTS * AnchorSlot.VEC4_PER_SLOT;
        assertEquals(GlslCodegen.ANCHOR_INFO_UNIFORM, name(block, anchorHead));
        assertEquals(GlslCodegen.TRAIL_INFO_UNIFORM, name(block, trailHead));
        assertEquals(GlslCodegen.TRAIL_B_UNIFORM + (TrailSlot.SLOTS - 1),
                name(block, trailHead + TrailSlot.SLOTS * TrailSlot.VEC4_PER_SLOT));

        // verifyLayout 会独立地再核一遍两个块的先后
        PostEffectJsonBuilder.verifyLayout(pass.output().orderedParams(), src);
    }

    @Test
    void 轨迹条目全部是vec4且初值为零() {
        // 初值 0 就是「一帧轨迹都没有」。导出成纯资源包、没有 mod 每帧改写它时，
        // 刀光必须干脆不出现，而不是拿未初始化的值画出一条位置错乱的带子
        ShaderProject.Build build = projectWith(TRAILED).generate(GtProfile.MC_26_3, "post/x");
        JsonArray block = paramBlock(build);
        for (int i = 3; i < 4 + TrailSlot.SLOTS * TrailSlot.VEC4_PER_SLOT; i++) {
            JsonObject e = block.get(i).getAsJsonObject();
            assertEquals("vec4", e.get("type").getAsString(), name(block, i) + " 必须是 vec4");
            JsonArray v = e.getAsJsonArray("value");
            assertEquals(4, v.size());
            for (int c = 0; c < 4; c++) {
                assertEquals(0f, v.get(c).getAsFloat(), 1e-9, name(block, i) + " 初值必须是 0");
            }
        }
    }

    @Test
    void 没用轨迹的通道json里不出现轨迹条目() {
        ShaderProject.Build build = projectWith(PLAIN).generate(GtProfile.MC_26_3, "post/x");
        JsonArray block = paramBlock(build);
        for (int i = 0; i < block.size(); i++) {
            assertFalse(name(block, i).startsWith("GTTrail"),
                    "没用到轨迹的通道不该白白多出 49 个 vec4");
        }
    }

    @Test
    void 同一条链里用轨迹与不用轨迹的层互不干扰() {
        ShaderProject p = new ShaderProject("mixed");
        p.clearLayers();
        p.addLayer(layer(TRAILED));
        p.addLayer(layer(PLAIN));
        ShaderProject.Build build = p.generate(GtProfile.MC_26_3, "post/x");
        assertEquals(2, build.passes().size());
        assertTrue(build.passes().get(0).output().usesTrails());
        assertFalse(build.passes().get(1).output().usesTrails());
        for (ShaderProject.PassBuild pass : build.passes()) {
            PostEffectJsonBuilder.verifyLayout(pass.output().orderedParams(), pass.output().source());
        }
    }

    // ------------------------------------------------------------ 内置效果

    @Test
    void 内置刀光效果确实用到了轨迹() {
        // 这个效果的全部价值就是它读得到真实刀刃轨迹。哪天 helper 改名而它没跟着改，
        // 表现是「装了 mod 也永远不出光」——不看这条测试根本发现不了
        String src = mc.GTedd.cn.gtshaders.library.EffectLibrary.loadSource("bladeflash");
        assertNotNull(src, "库里应当有 combat/bladeflash");
        GlslCodegen.Output out = layer(src).generate(GtProfile.MC_26_3);
        assertTrue(out.usesTrails(), "刀光效果必须触发轨迹注入");
        PostEffectJsonBuilder.verifyLayout(out.orderedParams(), out.source());
    }

    // ------------------------------------------------------------ 工具

    private static JsonArray paramBlock(ShaderProject.Build build) {
        JsonObject json = PostEffectJsonBuilder.buildChain("gtshaders", build, new float[]{0, 0, 0, 1});
        return json.getAsJsonArray("passes").get(0).getAsJsonObject()
                .getAsJsonObject("uniforms").getAsJsonArray(GlslCodegen.PARAM_BLOCK);
    }

    private static String name(JsonArray block, int i) {
        return block.get(i).getAsJsonObject().get("name").getAsString();
    }
}
