package mc.GTedd.cn.gtshaders;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import mc.GTedd.cn.gtshaders.codegen.GlslCodegen;
import mc.GTedd.cn.gtshaders.codegen.PostEffectJsonBuilder;
import mc.GTedd.cn.gtshaders.core.AnchorBinding;
import mc.GTedd.cn.gtshaders.core.AnchorSlot;
import mc.GTedd.cn.gtshaders.core.EmitterSlot;
import mc.GTedd.cn.gtshaders.core.GtProfile;
import mc.GTedd.cn.gtshaders.core.ShaderLayer;
import mc.GTedd.cn.gtshaders.core.ShaderProject;
import mc.GTedd.cn.gtshaders.core.TrailSlot;
import mc.GTedd.cn.gtshaders.workspace.ProjectStore;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 覆盖载体扩展：按需注入、三个可选块并存时的顺序、绑定字段的存档往返。
 *
 * <p>uniform 块里现在有<b>三个</b>按需注入的段（锚点、载体、轨迹），
 * 谁在前谁在后决定了后面每一个成员的字节偏移。三处（GLSL 声明、JSON 条目、
 * 每帧写入）各自写死了同一个顺序，改一处漏两处不会报错，只会让画面变成一堆
 * 莫名其妙的颜色。{@link #三段可选块的顺序是锚点载体轨迹()} 就是钉这个的。
 */
class EmitterTest {

    private static ShaderLayer layer(String body) {
        ShaderLayer l = new ShaderLayer("L", body);
        l.setEnabled(true);
        return l;
    }

    private static ShaderProject projectWith(String body) {
        ShaderProject p = new ShaderProject("emitter");
        p.clearLayers();
        p.addLayer(layer(body));
        return p;
    }

    private static final String EMITTER = """
            // @param name=Tint type=color3 default=#FFFFFF
            // @param name=Amount type=float min=0 max=1 default=0.5
            void main() {
                vec2 p = gtEmitterLocal(0);
                float k = float(gtEmitterType(0)) * gtEmitterFacing(0) * Amount;
                fragColor = vec4(texture(InSampler, texCoord).rgb + Tint * k * exp(-length(p)), 1.0);
            }
            """;

    /** 三个可选块全都用上。顺序全靠这段钉住。 */
    private static final String ALL_THREE = """
            // @param name=Amount type=float min=0 max=1 default=0.5
            void main() {
                float k = gtAnchorStrength(0) * gtEmitterFacing(0) * gtTrail(0.2) * Amount;
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
    void 没用到载体就一个字节都不注入() {
        GlslCodegen.Output out = layer(PLAIN).generate(GtProfile.MC_26_3);
        assertFalse(out.usesEmitters());
        assertFalse(out.source().contains(GlslCodegen.EMITTER_A_UNIFORM));
        assertFalse(out.source().contains("gtEmitterLocal"));
    }

    @Test
    void 用到载体才注入helper与uniform() {
        GlslCodegen.Output out = layer(EMITTER).generate(GtProfile.MC_26_3);
        assertTrue(out.usesEmitters());
        assertTrue(out.source().contains(
                "vec4 " + GlslCodegen.EMITTER_A_UNIFORM + "[" + AnchorSlot.SLOTS + "];"));
        assertTrue(out.source().contains(
                "vec4 " + GlslCodegen.EMITTER_B_UNIFORM + "[" + AnchorSlot.SLOTS + "];"));
        assertTrue(out.source().contains("vec2 gtEmitterLocal(int i)"));
        assertTrue(out.source().contains("#define GT_EMITTER_TYPES " + EmitterSlot.MAX_TYPES));
    }

    @Test
    void 用载体必定连带注入锚点() {
        // gtEmitterLocal 是在 gtAnchorDelta 之上算的。漏了这一步的话，
        // 只写 gtEmitter 的着色器会撞上「未定义的 gtAnchorDelta」——
        // 而那条报错指向的是我们生成的头部，作者根本无从下手
        GlslCodegen.Output out = layer(EMITTER).generate(GtProfile.MC_26_3);
        assertTrue(out.usesAnchors(), "载体扩展必须蕴含锚点");
        assertTrue(out.source().contains("vec2 gtAnchorDelta(int i)"));
        assertTrue(out.source().contains("vec4 " + GlslCodegen.ANCHOR_INFO_UNIFORM + ";"));
    }

    @Test
    void 载体helper排在自动生成段之内() {
        GlslCodegen.Output out = layer(EMITTER).generate(GtProfile.MC_26_3);
        int helper = out.source().indexOf("vec2 gtEmitterDir(int i)");
        int headerEnd = out.source().indexOf(GlslCodegen.HEADER_END);
        assertTrue(helper > 0 && helper < headerEnd);
    }

    @Test
    void 生成的着色器仍能原样切回作者源码() {
        GlslCodegen.Output out = layer(EMITTER).generate(GtProfile.MC_26_3);
        String back = GlslCodegen.extractAuthorBody(out.source());
        assertNotNull(back);
        assertEquals(EMITTER.stripTrailing(), back.stripTrailing());
    }

    // ------------------------------------------------------------ 三方布局一致

    @Test
    void 载体块在glsl与json里逐项对应() {
        ShaderProject.Build build = projectWith(EMITTER).generate(GtProfile.MC_26_3, "post/x");
        ShaderProject.PassBuild pass = build.passes().get(0);
        PostEffectJsonBuilder.verifyLayout(pass.output().orderedParams(), pass.output().source());

        JsonArray block = paramBlock(build);
        // 3 个系统 vec4 + 锚点表头 + A + B，然后才是载体
        int emitterHead = 4 + AnchorSlot.SLOTS * AnchorSlot.VEC4_PER_SLOT;
        for (int i = 0; i < AnchorSlot.SLOTS; i++) {
            assertEquals(GlslCodegen.EMITTER_A_UNIFORM + i, name(block, emitterHead + i));
        }
        for (int i = 0; i < AnchorSlot.SLOTS; i++) {
            assertEquals(GlslCodegen.EMITTER_B_UNIFORM + i,
                    name(block, emitterHead + AnchorSlot.SLOTS + i));
        }

        int head = emitterHead + AnchorSlot.SLOTS * EmitterSlot.VEC4_PER_SLOT;
        assertEquals(pass.output().orderedParams().size() + head, block.size(),
                "用户参数必须紧接在载体之后，不多不少");
    }

    @Test
    void 三段可选块的顺序是锚点载体轨迹() {
        ShaderProject.Build build = projectWith(ALL_THREE).generate(GtProfile.MC_26_3, "post/x");
        ShaderProject.PassBuild pass = build.passes().get(0);
        assertTrue(pass.output().usesAnchors());
        assertTrue(pass.output().usesEmitters());
        assertTrue(pass.output().usesTrails());

        String src = pass.output().source();
        int a = src.indexOf(GlslCodegen.ANCHOR_INFO_UNIFORM);
        int e = src.indexOf(GlslCodegen.EMITTER_A_UNIFORM);
        int t = src.indexOf(GlslCodegen.TRAIL_INFO_UNIFORM);
        assertTrue(a > 0 && a < e && e < t, "GLSL 块里必须是 锚点 → 载体 → 轨迹");

        JsonArray block = paramBlock(build);
        int anchorHead = 3;
        int emitterHead = anchorHead + 1 + AnchorSlot.SLOTS * AnchorSlot.VEC4_PER_SLOT;
        int trailHead = emitterHead + AnchorSlot.SLOTS * EmitterSlot.VEC4_PER_SLOT;
        assertEquals(GlslCodegen.ANCHOR_INFO_UNIFORM, name(block, anchorHead));
        assertEquals(GlslCodegen.EMITTER_A_UNIFORM + "0", name(block, emitterHead));
        assertEquals(GlslCodegen.TRAIL_INFO_UNIFORM, name(block, trailHead));

        int end = trailHead + 1 + TrailSlot.SLOTS * TrailSlot.VEC4_PER_SLOT;
        assertEquals(pass.output().orderedParams().size() + end, block.size());
        PostEffectJsonBuilder.verifyLayout(pass.output().orderedParams(), src);
    }

    @Test
    void 载体条目全部是vec4且初值为零() {
        ShaderProject.Build build = projectWith(EMITTER).generate(GtProfile.MC_26_3, "post/x");
        JsonArray block = paramBlock(build);
        int base = 4 + AnchorSlot.SLOTS * AnchorSlot.VEC4_PER_SLOT;
        for (int i = base; i < base + AnchorSlot.SLOTS * EmitterSlot.VEC4_PER_SLOT; i++) {
            JsonObject o = block.get(i).getAsJsonObject();
            assertEquals("vec4", o.get("type").getAsString());
            JsonArray v = o.getAsJsonArray("value");
            for (int c = 0; c < 4; c++) {
                assertEquals(0f, v.get(c).getAsFloat(), 1e-9, name(block, i) + " 初值必须是 0");
            }
        }
    }

    @Test
    void 没用载体的通道json里不出现载体条目() {
        ShaderProject.Build build = projectWith(PLAIN).generate(GtProfile.MC_26_3, "post/x");
        JsonArray block = paramBlock(build);
        for (int i = 0; i < block.size(); i++) {
            assertFalse(name(block, i).startsWith("GTEmitter"));
        }
    }

    // ------------------------------------------------------------ 绑定字段

    @Test
    void 载体字段存下来再读回来完全一致() {
        ShaderProject p = new ShaderProject("save");
        AnchorBinding b = new AnchorBinding("light");
        b.setFacing(AnchorBinding.Facing.FIXED);
        b.setYaw(-137f);
        b.setPitch(23f);
        b.setEmitterType(3);
        b.setCustom1(1.75f);
        b.setCustom2(-0.5f);
        b.setSpin(90f);
        p.addAnchor(b);

        ShaderProject back = ProjectStore.fromJson(ProjectStore.toJson(p), "save");
        assertNotNull(back);
        AnchorBinding r = back.anchors().get(0);
        assertEquals(AnchorBinding.Facing.FIXED, r.facing());
        assertEquals(-137f, r.yaw(), 1e-4);
        assertEquals(23f, r.pitch(), 1e-4);
        assertEquals(3, r.emitterType());
        assertEquals(1.75f, r.custom1(), 1e-4);
        assertEquals(-0.5f, r.custom2(), 1e-4);
        assertEquals(90f, r.spin(), 1e-4);
    }

    @Test
    void 没配载体的绑定不写多余的键() {
        // 工程文件是鼓励作者手改的。绝大多数绑定用不上载体，每条都多七行
        // 会让人以为这些是必填项
        ShaderProject p = new ShaderProject("clean");
        p.addAnchor(new AnchorBinding("plain"));
        JsonObject o = ProjectStore.toJson(p).getAsJsonArray("anchors").get(0).getAsJsonObject();
        for (String key : new String[]{"facing", "yaw", "pitch", "emitterType",
                "custom1", "custom2", "spin"}) {
            assertFalse(o.has(key), key + " 是默认值就不该写进文件");
        }
    }

    @Test
    void 载体字段跟着复制走() {
        AnchorBinding b = new AnchorBinding("src");
        b.setFacing(AnchorBinding.Facing.CAMERA);
        b.setEmitterType(5);
        b.setCustom1(2f);
        AnchorBinding c = b.copy();
        assertEquals(AnchorBinding.Facing.CAMERA, c.facing());
        assertEquals(5, c.emitterType());
        assertEquals(2f, c.custom1(), 1e-4);
        // 复制出来的是独立的一份，改一个不该动另一个
        c.setEmitterType(1);
        assertEquals(5, b.emitterType());
    }

    @Test
    void 子类型与自定义值都被钳在量程内() {
        AnchorBinding b = new AnchorBinding("clamp");
        b.setEmitterType(999);
        assertEquals(EmitterSlot.MAX_TYPES - 1, b.emitterType());
        b.setEmitterType(-5);
        assertEquals(0, b.emitterType());
        // 手滑拖出个几万会让整屏变成纯白，而那种故障看不出是哪一处出的问题
        b.setCustom1(1e9f);
        assertEquals(64f, b.custom1(), 1e-4);
        b.setCustom1(Float.NaN);
        assertEquals(0f, b.custom1(), 1e-4);
    }

    @Test
    void 朝向模式决定要不要解算() {
        AnchorBinding b = new AnchorBinding("f");
        assertFalse(b.usesFacing(), "默认不用朝向，圆对称效果不该为它付代价");
        b.setFacing(AnchorBinding.Facing.TARGET);
        assertTrue(b.usesFacing());
    }

    // ------------------------------------------------------------ 内置效果

    @Test
    void 五个载体效果都真的读了载体() {
        for (String id : new String[]{"fx_light", "fx_bloom", "fx_lightning",
                "fx_shockwave", "fx_camerashake"}) {
            String src = mc.GTedd.cn.gtshaders.library.EffectLibrary.loadSource(id);
            assertNotNull(src, "库里应当有 emitter/" + id);
            GlslCodegen.Output out = layer(src).generate(GtProfile.MC_26_3);
            assertTrue(out.usesEmitters(), id + " 必须触发载体注入");
            PostEffectJsonBuilder.verifyLayout(out.orderedParams(), out.source());
        }
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
