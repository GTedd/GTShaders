package mc.GTedd.cn.gtshaders;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import mc.GTedd.cn.gtshaders.codegen.GlslCodegen;
import mc.GTedd.cn.gtshaders.codegen.PostEffectJsonBuilder;
import mc.GTedd.cn.gtshaders.core.BlendMode;
import mc.GTedd.cn.gtshaders.core.GtProfile;
import mc.GTedd.cn.gtshaders.core.ShaderLayer;
import mc.GTedd.cn.gtshaders.core.ShaderProject;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 钉住场景深度输入的契约。
 *
 * <p>这一处出错的方式极其安静：{@code SamplerInfo} 是个 std140 块，原版
 * {@code PostPass} 按 JSON 里 inputs 的<b>声明顺序</b>逐个写 vec2，从不反射 GLSL 侧
 * 声明了什么。所以两边顺序或数量对不上时，**编译通过、加载通过、不报任何错**，
 * 只是块整个错位——`InSize` 读到 `OutSize` 的值，模糊半径之类全算错。
 *
 * <p>另一条同样安静的是「深度输入不能跟着乒乓走」：{@code swap} 是我们自建的中转缓冲，
 * 没有深度附件，指过去只会拿到空的。
 */
class DepthInputTest {

    private static final String PLAIN = """
            void main() {
                fragColor = vec4(texture(InSampler, texCoord).rgb, 1.0);
            }
            """;

    private static final String DEPTH = """
            void main() {
                float e = gtDepthEdge(texCoord, 1.0);
                vec3 c = gtIsSky(texCoord) ? vec3(0.0) : texture(InSampler, texCoord).rgb;
                fragColor = vec4(c * (1.0 - step(0.004, e)), 1.0);
            }
            """;

    private static final String DEPTH_AND_TEXTURE = """
            // @texture name=Icon path=gtshaders:killicon/foo width=64 height=64
            void main() {
                float d = gtDepth(texCoord);
                fragColor = vec4(texture(Icon, texCoord).rgb * d, 1.0);
            }
            """;

    private static GlslCodegen.Output gen(String body) {
        return GlslCodegen.generate(GtProfile.MC_26_3, body, List.of(), BlendMode.NORMAL);
    }

    private static ShaderProject project(String... bodies) {
        ShaderProject p = new ShaderProject("D");
        p.clearLayers();
        for (String b : bodies) {
            ShaderLayer l = new ShaderLayer("L", b);
            l.setEnabled(true);
            p.addLayer(l);
        }
        p.setExportProfile(GtProfile.MC_26_3);
        return p;
    }

    private static JsonArray inputsOf(JsonObject chain, int pass) {
        return chain.getAsJsonArray("passes").get(pass).getAsJsonObject().getAsJsonArray("inputs");
    }

    private static JsonObject buildChain(ShaderProject p) {
        return PostEffectJsonBuilder.buildChain("gtshaders",
                p.generate(GtProfile.MC_26_3, "post/x"), PostEffectJsonBuilder.PACK_SYSTEM);
    }

    // ------------------------------------------------------------ 按需注入

    @Test
    void 没用到深度就一个字都不多() {
        GlslCodegen.Output out = gen(PLAIN);
        assertFalse(out.usesDepth());
        assertFalse(out.source().contains("InDepthSampler"), out.source());
        assertFalse(out.source().contains("InDepthSize"), out.source());
        // 不用的效果不该多一次纹理绑定
        assertEquals(1, inputsOf(buildChain(project(PLAIN)), 0).size());
    }

    @Test
    void 用了gtDepth才注入() {
        GlslCodegen.Output out = gen(DEPTH);
        assertTrue(out.usesDepth());
        assertTrue(out.source().contains("uniform sampler2D InDepthSampler;"), out.source());
        assertTrue(out.source().contains("float gtDepth(vec2 uv)"), out.source());
        assertTrue(out.source().contains("bool gtIsSky(vec2 uv)"), out.source());
    }

    // ------------------------------------------------------------ JSON 与 GLSL 的顺序必须一致

    @Test
    void JSON里深度输入排在主输入之后() {
        JsonArray inputs = inputsOf(buildChain(project(DEPTH)), 0);
        assertEquals(2, inputs.size(), inputs.toString());
        assertEquals(GlslCodegen.MAIN_SAMPLER_NAME,
                inputs.get(0).getAsJsonObject().get("sampler_name").getAsString());
        JsonObject depth = inputs.get(1).getAsJsonObject();
        assertEquals(GlslCodegen.DEPTH_SAMPLER_NAME, depth.get("sampler_name").getAsString());
        assertTrue(depth.get("use_depth_buffer").getAsBoolean(), depth.toString());
    }

    @Test
    void SamplerInfo的成员顺序与inputs一致() {
        String src = gen(DEPTH).source();
        int block = src.indexOf("uniform SamplerInfo");
        int out = src.indexOf("vec2 OutSize;", block);
        int in = src.indexOf("vec2 InSize;", block);
        int depth = src.indexOf("vec2 InDepthSize;", block);
        assertTrue(out > 0 && in > out && depth > in,
                "SamplerInfo 顺序必须是 OutSize → InSize → InDepthSize：\n" + src);
    }

    @Test
    void 与贴图共存时深度排在贴图之前() {
        // 顺序定为 In → InDepth → 贴图。两边都要按这个来，否则贴图尺寸会读到深度尺寸
        JsonArray inputs = inputsOf(buildChain(project(DEPTH_AND_TEXTURE)), 0);
        assertEquals(3, inputs.size(), inputs.toString());
        assertEquals("In", inputs.get(0).getAsJsonObject().get("sampler_name").getAsString());
        assertEquals("InDepth", inputs.get(1).getAsJsonObject().get("sampler_name").getAsString());
        assertEquals("Icon", inputs.get(2).getAsJsonObject().get("sampler_name").getAsString());

        String src = gen(DEPTH_AND_TEXTURE).source();
        int block = src.indexOf("uniform SamplerInfo");
        assertTrue(src.indexOf("vec2 InDepthSize;", block) < src.indexOf("IconSamplerSize;", block),
                "GLSL 侧顺序与 JSON 对不上：\n" + src);
    }

    // ------------------------------------------------------------ 不跟着乒乓

    @Test
    void 深度输入永远指向main而不跟着乒乓() {
        // 三层链：第 0 层读 main、第 1 层读 swap、第 2 层再读 main。
        // 颜色输入跟着换，深度输入必须始终是 minecraft:main——swap 没有深度附件
        JsonObject chain = buildChain(project(DEPTH, DEPTH, DEPTH));
        assertEquals("minecraft:main",
                inputsOf(chain, 0).get(0).getAsJsonObject().get("target").getAsString());
        assertEquals("swap",
                inputsOf(chain, 1).get(0).getAsJsonObject().get("target").getAsString());
        for (int pass = 0; pass < 3; pass++) {
            JsonObject depth = inputsOf(chain, pass).get(1).getAsJsonObject();
            assertEquals("minecraft:main", depth.get("target").getAsString(),
                    "第 " + pass + " 个通道的深度输入指错了目标");
            assertTrue(depth.get("use_depth_buffer").getAsBoolean());
        }
    }

    // ------------------------------------------------------------ 轮廓层

    @Test
    void 轮廓层不注入深度() {
        // 轮廓层已经有遮罩 + 场景两个输入，再插一个会打乱 SamplerInfo 顺序，
        // 而逐实体描边本来就有比深度更准的信息
        GlslCodegen.Output out = GlslCodegen.generateOutline(GtProfile.MC_26_3, DEPTH, List.of());
        assertFalse(out.usesDepth());
        assertFalse(out.source().contains("InDepthSampler"), out.source());
    }
}
