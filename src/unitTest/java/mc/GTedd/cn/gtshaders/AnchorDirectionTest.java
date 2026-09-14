package mc.GTedd.cn.gtshaders;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import mc.GTedd.cn.gtshaders.codegen.GlslCodegen;
import mc.GTedd.cn.gtshaders.codegen.PostEffectJsonBuilder;
import mc.GTedd.cn.gtshaders.core.AnchorSlot;
import mc.GTedd.cn.gtshaders.core.BlendMode;
import mc.GTedd.cn.gtshaders.core.GtProfile;
import mc.GTedd.cn.gtshaders.core.ShaderLayer;
import mc.GTedd.cn.gtshaders.core.ShaderProject;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 钉住锚点的<b>方向组</b>（{@code GTAnchorC}）以及它带来的布局改动。
 *
 * <p>加这一组是为了让「目标转出视野后效果照样生效」成为可能：A、B 描述的是
 * 「落在屏幕哪里」，转到身后就没意义了，而方向在任何角度都成立。
 *
 * <h2>为什么布局值得单独测</h2>
 *
 * <p>一个槽位占几个 vec4 这件事写在四个地方：{@link AnchorSlot} 的字段、
 * {@code GlslCodegen} 的 std140 声明、{@code PostEffectJsonBuilder} 铺进 JSON 的条目、
 * {@code PreviewRuntime} 每帧写入的顺序。改一处漏一处<b>不会报任何错</b>，
 * 只会让整个 uniform 块错位——画面变得莫名其妙，而且查起来毫无线索。
 */
class AnchorDirectionTest {

    private static final String ANCHORED = """
            void main() {
                vec3 d = gtAnchorDir(0);
                float k = gtAnchorStrength(0) * gtAnchorFront(0);
                vec2 s = gtAnchorScreenDir(0);
                if (gtAnchorBehind(0)) { k *= 0.5; }
                fragColor = vec4(texture(InSampler, texCoord + s * k * 0.01).rgb + d.z * 0.0, 1.0);
            }
            """;

    private static GlslCodegen.Output gen() {
        return GlslCodegen.generate(GtProfile.MC_26_3, ANCHORED, List.of(), BlendMode.NORMAL);
    }

    // ------------------------------------------------------------ 布局

    @Test
    void record字段数与每槽vec4数一致() {
        // 这条是四处同步的源头：record 加了字段却忘了改 VEC4_PER_SLOT，
        // 后面三处全都会按旧尺寸写，整块错位
        int components = AnchorSlot.class.getRecordComponents().length;
        assertEquals(AnchorSlot.VEC4_PER_SLOT * 4, components,
                "AnchorSlot 有 " + components + " 个字段，VEC4_PER_SLOT="
                        + AnchorSlot.VEC4_PER_SLOT + "，对不上");
    }

    @Test
    void GLSL声明了三组锚点数组() {
        String src = gen().source();
        for (String u : new String[]{GlslCodegen.ANCHOR_A_UNIFORM,
                GlslCodegen.ANCHOR_B_UNIFORM, GlslCodegen.ANCHOR_C_UNIFORM}) {
            assertTrue(src.contains("vec4 " + u + "[" + AnchorSlot.SLOTS + "]"),
                    "缺 " + u + " 的声明:\n" + src);
        }
    }

    @Test
    void JSON条目数等于表头加三组() {
        ShaderProject p = new ShaderProject("A");
        p.clearLayers();
        ShaderLayer l = new ShaderLayer("L", ANCHORED);
        l.setEnabled(true);
        p.addLayer(l);

        JsonObject chain = PostEffectJsonBuilder.buildChain("gtshaders",
                p.generate(GtProfile.MC_26_3, "post/x"), PostEffectJsonBuilder.PACK_SYSTEM);
        JsonArray block = chain.getAsJsonArray("passes").get(0).getAsJsonObject()
                .getAsJsonObject("uniforms").getAsJsonArray(GlslCodegen.PARAM_BLOCK);

        long anchors = 0;
        for (var e : block) {
            String name = e.getAsJsonObject().get("name").getAsString();
            if (name.startsWith("GTAnchor")) {
                anchors++;
            }
        }
        // 1 个表头 + 每槽 VEC4_PER_SLOT 个
        assertEquals(1L + AnchorSlot.SLOTS * AnchorSlot.VEC4_PER_SLOT, anchors,
                "JSON 里的锚点条目数与布局对不上");
    }

    @Test
    void 布局校验能通过() {
        // verifyLayout 会逐项核对 GLSL 块与 JSON 的对应关系，漏声明 GTAnchorC 会在这里抛
        GlslCodegen.Output out = gen();
        PostEffectJsonBuilder.verifyLayout(out.orderedParams(), out.source());
    }

    // ------------------------------------------------------------ helper

    @Test
    void 注入了看不见时可用的那组helper() {
        String src = gen().source();
        for (String fn : new String[]{"gtAnchorDir", "gtAnchorState", "gtAnchorBehind",
                "gtAnchorOnScreen", "gtAnchorOccluded", "gtAnchorScreenDir", "gtAnchorFront"}) {
            assertTrue(src.contains(fn + "(int i)"), "缺 helper " + fn + ":\n" + src);
        }
    }

    @Test
    void 锚点与深度可以在同一个效果里共存() {
        // 「红外探测」那类效果两样都要：锚点给爆点位置，深度给三维波前与结构轮廓。
        // 两套注入各改各的地方（锚点加 uniform 块成员、深度加输入与 SamplerInfo 成员），
        // 撞车的话表现是整块错位而不报错，所以组合这条单独钉一次
        String body = """
                void main() {
                    float e = gtDepthEdge(texCoord, 1.0);
                    float r = gtAnchorRange(0);
                    fragColor = vec4(vec3(e + r + gtDepth(texCoord)), 1.0);
                }
                """;
        GlslCodegen.Output out = GlslCodegen.generate(GtProfile.MC_26_3, body, List.of(),
                BlendMode.NORMAL);
        assertTrue(out.usesAnchors());
        assertTrue(out.usesDepth());

        ShaderProject p = new ShaderProject("Combo");
        p.clearLayers();
        ShaderLayer l = new ShaderLayer("L", body);
        l.setEnabled(true);
        p.addLayer(l);
        JsonObject chain = PostEffectJsonBuilder.buildChain("gtshaders",
                p.generate(GtProfile.MC_26_3, "post/x"), PostEffectJsonBuilder.PACK_SYSTEM);
        JsonObject pass = chain.getAsJsonArray("passes").get(0).getAsJsonObject();

        // 深度输入进 inputs，锚点进 uniform 块，两者互不影响
        JsonArray inputs = pass.getAsJsonArray("inputs");
        assertEquals(2, inputs.size(), inputs.toString());
        assertEquals(GlslCodegen.DEPTH_SAMPLER_NAME,
                inputs.get(1).getAsJsonObject().get("sampler_name").getAsString());
        assertTrue(out.source().contains("vec2 InDepthSize;"), out.source());
        assertTrue(out.source().contains("vec4 " + GlslCodegen.ANCHOR_C_UNIFORM), out.source());

        // 布局校验必须仍然过——它逐项核对 GLSL 块与 JSON 的对应关系
        PostEffectJsonBuilder.verifyLayout(out.orderedParams(), out.source());
    }

    @Test
    void 没用锚点的效果一个字都不多() {
        GlslCodegen.Output plain = GlslCodegen.generate(GtProfile.MC_26_3, """
                void main() { fragColor = texture(InSampler, texCoord); }
                """, List.of(), BlendMode.NORMAL);
        assertFalse(plain.usesAnchors());
        assertFalse(plain.source().contains(GlslCodegen.ANCHOR_C_UNIFORM), plain.source());
    }

    // ------------------------------------------------------------ 空槽

    @Test
    void 空槽的方向与状态都是零() {
        // 导出成纯资源包后没有 mod 写 uniform，全部槽位就是这个值。
        // dir 为零向量时 gtAnchorDir 会返回正前方兜底，效果不会因此指向一个随机方向
        AnchorSlot e = AnchorSlot.EMPTY;
        assertTrue(e.isEmpty());
        assertEquals(0f, e.dirX());
        assertEquals(0f, e.dirY());
        assertEquals(0f, e.dirZ());
        assertEquals(AnchorSlot.STATE_ON_SCREEN, e.state());
    }

    @Test
    void 四个状态码互不相同() {
        // 着色器侧用 int(state + 0.5) 比较，取值必须是彼此隔开的整数
        float[] all = {AnchorSlot.STATE_ON_SCREEN, AnchorSlot.STATE_OCCLUDED,
                AnchorSlot.STATE_OFFSCREEN, AnchorSlot.STATE_BEHIND};
        for (int i = 0; i < all.length; i++) {
            assertEquals(i, (int) all[i], "状态码必须是 0..3 的连续整数");
            for (int j = i + 1; j < all.length; j++) {
                assertTrue(all[i] != all[j], "状态码重复");
            }
        }
    }
}
