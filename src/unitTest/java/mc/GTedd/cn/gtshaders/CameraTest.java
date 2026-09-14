package mc.GTedd.cn.gtshaders;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.junit.jupiter.api.Test;
import mc.GTedd.cn.gtshaders.codegen.GlslCodegen;
import mc.GTedd.cn.gtshaders.codegen.PostEffectJsonBuilder;
import mc.GTedd.cn.gtshaders.core.BlendMode;
import mc.GTedd.cn.gtshaders.core.GtProfile;
import mc.GTedd.cn.gtshaders.core.ShaderLayer;
import mc.GTedd.cn.gtshaders.core.ShaderProject;
import mc.GTedd.cn.gtshaders.runtime.CameraRuntime;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 钉住世界相机的两份契约：uniform 块的字节布局，和投影矩阵的反解。
 *
 * <h2>为什么这两件事都需要测试</h2>
 *
 * <p>布局那一半的失败方式与锚点、轨迹完全一样，而且同样安静：GLSL 声明、post effect JSON、
 * 每帧写入三处各写各的，只要有一处漏了这四个 vec4，整块就从相机那里开始错位——
 * 用户参数会读到相机矩阵的分量，画面变得莫名其妙，而编译、加载、运行都不报一个字。
 *
 * <p>反解那一半则是纯数学，错了以后画面「有效果但不对」：网格贴不到地面上、
 * 雾的距离差一个量级。这类问题在真机上要靠肉眼比对才发现得了，而在这里
 * 一个已知的投影矩阵就能钉死。
 */
class CameraTest {

    /** 不碰相机也不碰深度。 */
    private static final String PLAIN = """
            void main() {
                fragColor = vec4(texture(InSampler, texCoord).rgb, 1.0);
            }
            """;

    /** 只要深度，不要相机。 */
    private static final String DEPTH_ONLY = """
            void main() {
                float e = gtDepthEdge(texCoord, 1.0);
                fragColor = vec4(vec3(e), 1.0);
            }
            """;

    /** 要相机。 */
    private static final String CAMERA = """
            void main() {
                vec3 w = gtWorldPos(texCoord);
                fragColor = vec4(fract(w), 1.0);
            }
            """;

    private static GlslCodegen.Output gen(String body) {
        return GlslCodegen.generate(GtProfile.MC_26_3, body, List.of(), BlendMode.NORMAL);
    }

    // ------------------------------------------------------------ 按需注入

    @Test
    void 不碰相机的效果一个相机uniform都不带() {
        GlslCodegen.Output out = gen(PLAIN);
        assertFalse(out.usesCamera(), "没写相机函数却打开了相机块");
        assertFalse(out.source().contains(GlslCodegen.CAMERA_PROJ_UNIFORM),
                "没用相机的着色器里出现了相机 uniform");
    }

    @Test
    void 只要深度不会顺带打开相机() {
        GlslCodegen.Output out = gen(DEPTH_ONLY);
        assertTrue(out.usesDepth(), "gtDepthEdge 没能打开深度输入");
        assertFalse(out.usesCamera(), "只用深度的效果不该付相机那 4 个 vec4 的钱");
    }

    @Test
    void 用了相机就一定同时打开深度() {
        GlslCodegen.Output out = gen(CAMERA);
        assertTrue(out.usesCamera(), "gtWorldPos 没能打开相机块");
        // 每一个相机 helper 都建立在 gtDepth 之上，少了深度输入会直接编译不过
        assertTrue(out.usesDepth(), "打开相机却没打开深度，gtLinearDepth 会找不到采样器");
        assertTrue(out.source().contains("gtLinearDepth"), "相机 helper 没有注入");
    }

    // ------------------------------------------------------------ 字节布局

    @Test
    void 相机块排在系统量之后用户参数之前() {
        ShaderLayer layer = new ShaderLayer("cam", """
                // @param name=Scale type=float min=0 max=4 default=1 zh_cn=缩放 en_us=Scale
                void main() {
                    fragColor = vec4(fract(gtWorldPos(texCoord) * Scale), 1.0);
                }
                """);
        GlslCodegen.Output out = layer.generate(GtProfile.MC_26_3);
        String src = out.source();

        int viewport = src.indexOf(GlslCodegen.VIEWPORT_UNIFORM);
        int proj = src.indexOf(GlslCodegen.CAMERA_PROJ_UNIFORM);
        int right = src.indexOf(GlslCodegen.CAMERA_RIGHT_UNIFORM);
        int up = src.indexOf(GlslCodegen.CAMERA_UP_UNIFORM);
        int back = src.indexOf(GlslCodegen.CAMERA_BACK_UNIFORM);
        int param = src.indexOf(" Scale;");

        assertTrue(viewport < proj, "相机块排到了系统量前面");
        assertTrue(proj < right && right < up && up < back, "相机四个 vec4 的顺序不对");
        assertTrue(back < param, "用户参数排到了相机块前面");

        // 这一句才是真正的守门人：块错位不会报编译错误，只会让画面莫名其妙
        PostEffectJsonBuilder.verifyLayout(out.orderedParams(), src);
    }

    @Test
    void 锚点轨迹与相机同时出现时相机排在最后一组() {
        // 这是最容易错位的组合：四组可选的 vec4 叠在一起，谁在前谁在后决定的是内存偏移。
        // 库里目前没有同时用到它们的效果，所以只能在这里造一个
        ShaderLayer layer = new ShaderLayer("all", """
                // @param name=Scale type=float min=0 max=4 default=1 zh_cn=缩放 en_us=Scale
                void main() {
                    vec2 a = gtAnchorUV(0);
                    vec2 e = gtEmitterDir(0);
                    float t = gtTrailAt(0.4).x;
                    vec3 w = gtWorldPos(texCoord);
                    fragColor = vec4(fract(w * Scale) + vec3(a.x + e.x + t), 1.0);
                }
                """);
        GlslCodegen.Output out = layer.generate(GtProfile.MC_26_3);
        String src = out.source();

        assertTrue(out.usesAnchors() && out.usesEmitters() && out.usesTrails() && out.usesCamera(),
                "四组可选 uniform 没有全部打开");
        assertTrue(src.indexOf(GlslCodegen.TRAIL_B_UNIFORM)
                        < src.indexOf(GlslCodegen.CAMERA_PROJ_UNIFORM),
                "相机块排到了轨迹前面");
        PostEffectJsonBuilder.verifyLayout(out.orderedParams(), src);
    }

    @Test
    void json里的相机条目与glsl逐个对得上() {
        ShaderProject p = new ShaderProject("C");
        p.clearLayers();
        ShaderLayer layer = new ShaderLayer("cam", CAMERA);
        layer.setEnabled(true);
        p.addLayer(layer);
        p.setExportProfile(GtProfile.MC_26_3);

        JsonObject chain = PostEffectJsonBuilder.buildChain("gtshaders",
                p.generate(GtProfile.MC_26_3, "post/x"), PostEffectJsonBuilder.PACK_SYSTEM);
        JsonArray block = chain.getAsJsonArray("passes").get(0).getAsJsonObject()
                .getAsJsonObject("uniforms").getAsJsonArray(GlslCodegen.PARAM_BLOCK);

        List<String> names = new ArrayList<>();
        for (int i = 0; i < block.size(); i++) {
            names.add(block.get(i).getAsJsonObject().get("name").getAsString());
        }
        assertEquals(List.of(GlslCodegen.SYSTEM_UNIFORM, GlslCodegen.LAYER_UNIFORM,
                        GlslCodegen.VIEWPORT_UNIFORM, GlslCodegen.CAMERA_PROJ_UNIFORM,
                        GlslCodegen.CAMERA_RIGHT_UNIFORM, GlslCodegen.CAMERA_UP_UNIFORM,
                        GlslCodegen.CAMERA_BACK_UNIFORM),
                names, "JSON 块的成员顺序与 GLSL 对不上");

        // 初值必须全 0：那是「纯资源包里没有相机」的形态，gtCameraLive() 据此转假
        for (int i = 3; i < 7; i++) {
            JsonArray value = block.get(i).getAsJsonObject().getAsJsonArray("value");
            for (int c = 0; c < 4; c++) {
                assertEquals(0f, value.get(c).getAsFloat(), 0f, "相机初值不是 0");
            }
        }
    }

    // ------------------------------------------------------------ 矩阵反解

    /** 竖直视野 70°，与 Minecraft 的默认 FOV 一致。 */
    private static final float FOV_Y = (float) Math.toRadians(70.0);
    private static final float ASPECT = 16f / 9f;
    private static final float Z_NEAR = 0.05f;

    /**
     * 反转深度、无限远的透视矩阵，也就是 26.2-snapshot-1 之后 Minecraft 在用的那种：
     * 近平面出 1、无穷远出 0。JOML 的构造器按<b>列</b>填。
     */
    private static Matrix4f reversedInfinite() {
        float p11 = 1f / (float) Math.tan(FOV_Y * 0.5);
        float p00 = p11 / ASPECT;
        return new Matrix4f(
                p00, 0f, 0f, 0f,
                0f, p11, 0f, 0f,
                0f, 0f, 0f, -1f,
                0f, 0f, Z_NEAR, 0f);
    }

    @Test
    void 从视图投影矩阵里反解出半视角与宽高比() {
        Matrix4f proj = reversedInfinite();
        Matrix4f view = new Matrix4f().rotationY((float) Math.toRadians(90.0));
        float[] out = CameraRuntime.pack(new Matrix4f(proj).mul(view), view);

        assertEquals(Math.tan(FOV_Y * 0.5), out[0], 1e-5, "半视角正切不对");
        assertEquals(ASPECT, out[1], 1e-5, "宽高比不对");
        assertEquals(0f, out[2], 1e-6, "深度线性化的常数项不对");
        assertEquals(Z_NEAR, out[3], 1e-7, "深度线性化的分子不对");
    }

    @Test
    void 反解出的系数能把设备深度还原成米() {
        Matrix4f proj = reversedInfinite();
        Matrix4f view = new Matrix4f();
        float[] out = CameraRuntime.pack(new Matrix4f(proj).mul(view), view);

        // 正前方 10 米处的一点，走一遍真实的投影得到设备深度
        Vector4f clip = proj.transform(new Vector4f(0f, 0f, -10f, 1f));
        float device = clip.z / clip.w;

        // 着色器里 gtLinearDepth 用的就是这条式子
        float linear = out[3] / (device + out[2]);
        assertEquals(10f, linear, 1e-3, "还原出来的距离不是 10 米");
    }

    @Test
    void 三根基向量是逆视图矩阵的三列() {
        Matrix4f view = new Matrix4f().rotationY((float) Math.toRadians(90.0));
        float[] out = CameraRuntime.pack(new Matrix4f(reversedInfinite()).mul(view), view);

        // 相机绕 Y 转 90° 之后，视图空间的「右」在世界空间指向 +Z
        assertEquals(0f, out[4], 1e-5);
        assertEquals(0f, out[5], 1e-5);
        assertEquals(1f, out[6], 1e-5);
        // 「上」不受偏航影响
        assertEquals(0f, out[8], 1e-5);
        assertEquals(1f, out[9], 1e-5);
        assertEquals(0f, out[10], 1e-5);
        // 「后」指向 −X
        assertEquals(-1f, out[12], 1e-5);
        assertEquals(0f, out[13], 1e-5);
        assertEquals(0f, out[14], 1e-5);
    }

    @Test
    void 拿到的不是反转深度的透视矩阵时整组归零() {
        // 传统深度的透视矩阵：ProjMat[3][2] 是负的，线性化公式的符号会整个反过来。
        // 与其画出一片鬼影，不如退回默认相机
        Matrix4f traditional = new Matrix4f().perspective(FOV_Y, ASPECT, Z_NEAR, 1000f);
        Matrix4f view = new Matrix4f();
        float[] out = CameraRuntime.pack(new Matrix4f(traditional).mul(view), view);
        for (int i = 0; i < out.length; i++) {
            assertEquals(0f, out[i], 0f, "第 " + i + " 个分量没有归零");
        }

        // 正交矩阵同理：ProjMat[2][3] 不是 −1，线性化那条式子根本不成立
        Matrix4f ortho = new Matrix4f().ortho(-1f, 1f, -1f, 1f, 0.1f, 100f);
        float[] flat = CameraRuntime.pack(new Matrix4f(ortho).mul(view), view);
        for (int i = 0; i < flat.length; i++) {
            assertEquals(0f, flat[i], 0f, "正交投影没有被挡住");
        }
    }
}
