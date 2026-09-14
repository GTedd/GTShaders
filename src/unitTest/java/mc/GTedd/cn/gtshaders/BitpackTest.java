package mc.GTedd.cn.gtshaders;

import org.junit.jupiter.api.Test;
import mc.GTedd.cn.gtshaders.codegen.GlslCodegen;
import mc.GTedd.cn.gtshaders.core.BlendMode;
import mc.GTedd.cn.gtshaders.core.GtProfile;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 钉住无损位打包（{@code gtPackFloat} / {@code gtUnpackFloat}）的两件事：
 * 按需注入，以及往返真的一位不差。
 *
 * <p>后者不是形式主义。把位模式塞进 8 位通道，只要有一个字节错 1，
 * 读回来的就可能是差了一个数量级的数——而且不报错，只是结果不对。
 * 这里用 Java 按 GLSL 的算法重跑一遍，把「一位不差」变成会红的断言，
 * 顺带钉住解码里那个 {@code +0.5} 到底在防什么（见最后一条）。
 */
class BitpackTest {

    private static final String PLAIN = """
            void main() {
                fragColor = vec4(texture(InSampler, texCoord).rgb, 1.0);
            }
            """;

    /** 只解包不打包——读数据表的效果都长这样，是最常见的用法。 */
    private static final String UNPACK_ONLY = """
            // @texture name=Curve path=gtshaders:effect/curve width=256 height=1
            void main() {
                float k = gtUnpackFloatAt(Curve, ivec2(int(texCoord.x * 255.0), 0));
                fragColor = vec4(texture(InSampler, texCoord).rgb * k, 1.0);
            }
            """;

    /** 只打包不解包——把算好的值写给下一层。 */
    private static final String PACK_ONLY = """
            void main() {
                fragColor = gtPackFloat(GTTime);
            }
            """;

    private static GlslCodegen.Output gen(String body) {
        return GlslCodegen.generate(GtProfile.MC_26_3, body, List.of(), BlendMode.NORMAL);
    }

    @Test
    void 没用到就一行都不注入() {
        String src = gen(PLAIN).source();
        assertFalse(src.contains("gtPackFloat"), "没用到位打包却注入了 helper");
        assertFalse(src.contains("gtUnpackFloat"), "没用到位打包却注入了 helper");
    }

    @Test
    void 只解包也要注入() {
        String src = gen(UNPACK_ONLY).source();
        assertTrue(src.contains("float gtUnpackFloat(vec4 c)"), "解包端没拿到 helper");
        assertTrue(src.contains("float gtUnpackFloatAt(sampler2D s, ivec2 px)"),
                "取纹素的 helper 没注入");
        // 打包端一并给出：判据是「用到位打包」而不是「用到哪一半」，注多几行没有代价
        assertTrue(src.contains("vec4 gtPackFloat(float v)"), "打包端应当一并注入");
    }

    @Test
    void 只打包也要注入() {
        assertTrue(gen(PACK_ONLY).source().contains("vec4 gtPackFloat(float v)"),
                "打包端没拿到 helper");
    }

    /** 位打包不依赖任何 uniform 或采样器输入，所以不该连带把深度/锚点拽进来。 */
    @Test
    void 不连带拉进别的输入() {
        GlslCodegen.Output out = gen(PACK_ONLY);
        assertFalse(out.usesDepth(), "位打包不该要深度输入");
        assertFalse(out.usesAnchors(), "位打包不该要锚点 uniform");
    }

    // ---- 往返：按 GLSL 的算法在 JVM 上重跑一遍 ----

    /** 对应 GLSL 的 {@code gtPackFloat}：位模式拆成四个字节，再按纹素归一化到 0..1。 */
    private static float[] packFloat(float v) {
        int u = Float.floatToRawIntBits(v);
        return new float[]{
                (u & 0xff) / 255f, ((u >>> 8) & 0xff) / 255f,
                ((u >>> 16) & 0xff) / 255f, ((u >>> 24) & 0xff) / 255f};
    }

    /** 对应 GLSL 的 {@code gtUnpackFloat}。{@code round} 就是那个关键的 {@code +0.5}。 */
    private static float unpackFloat(float[] c) {
        int b0 = (int) (c[0] * 255f + 0.5f);
        int b1 = (int) (c[1] * 255f + 0.5f);
        int b2 = (int) (c[2] * 255f + 0.5f);
        int b3 = (int) (c[3] * 255f + 0.5f);
        return Float.intBitsToFloat(b0 | (b1 << 8) | (b2 << 16) | (b3 << 24));
    }

    @Test
    void 往返一位不差() {
        float[] samples = {
                0f, -0f, 1f, -1f, 0.5f, -0.5f,
                3.14159265f, -2.71828f,
                1e-30f, 1e30f, -1e-30f, -1e30f,
                Float.MIN_VALUE, Float.MAX_VALUE,
                1200f,                       // GTTime 的量级
                1f / 3f, 0.1f, 65504f,
        };
        for (float v : samples) {
            assertEquals(Float.floatToRawIntBits(v),
                    Float.floatToRawIntBits(unpackFloat(packFloat(v))),
                    () -> "往返丢位：" + v);
        }
    }

    /**
     * 非有限值也要能过。这不是炫技：状态缓冲刚被 {@code clear_color} 清成 0 时，
     * 一个「还没写过」的槽位读出来是 0，而计算中途出现的 inf/NaN 如果在传输里被悄悄改成别的数，
     * 排查时会以为是算法错了。
     */
    @Test
    void 非有限值也不变形() {
        for (float v : new float[]{Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY}) {
            assertEquals(Float.floatToRawIntBits(v),
                    Float.floatToRawIntBits(unpackFloat(packFloat(v))),
                    () -> "往返丢位：" + v);
        }
        assertTrue(Float.isNaN(unpackFloat(packFloat(Float.NaN))), "NaN 往返后不再是 NaN");
    }

    /**
     * 全部 256 个字节值都要能原样回来——这是「一位不差」的完整证明，
     * 前面那几个抽样只是让失败信息好读。
     */
    @Test
    void 每个字节值都能原样回来() {
        for (int k = 0; k < 256; k++) {
            float c = k / 255f;
            assertEquals(k, (int) (c * 255f + 0.5f), "字节 " + k + " 往返后变了");
        }
    }

    /**
     * 钉住 {@code +0.5} 真正的作用：<b>对采样路径上的微小扰动免疫</b>。
     *
     * <p>先说清楚它<b>不</b>是为了什么。在纯 IEEE754 里 {@code k/255.0} 再乘回 255
     * 是精确的，256 个字节值一个都不会错（上面那条测试就是证据），所以「不加 0.5 会算错」
     * 这个说法在 CPU 上站不住。
     *
     * <p>它防的是 GPU 那一侧：UNORM8 转 float 由硬件做，规范只保证误差在一个 ULP 内，
     * 不保证逐位等于 {@code k/255.0}；驱动也常把除法优化成乘 {@code 1/255}，
     * 而 {@code 1/255} 本身就不可精确表示。所以采回来的值可能是 {@code k/255 ± ε}。
     * 截断在这时会整整差 1——如果那一位落在指数域，就是差一个数量级。
     */
    @Test
    void 四舍五入抵得住采样扰动() {
        // 一个 ULP 量级的扰动，模拟硬件把 UNORM8 转 float 时的偏差
        float eps = 1e-6f;
        int roundBroken = 0;
        int truncBroken = 0;
        for (int k = 0; k < 256; k++) {
            for (float d : new float[]{-eps, eps}) {
                float c = k / 255f + d;
                if ((int) (c * 255f + 0.5f) != k) {
                    roundBroken++;
                }
                if ((int) (c * 255f) != k) {
                    truncBroken++;
                }
            }
        }
        assertEquals(0, roundBroken, "四舍五入写法扛不住采样扰动，那这套编码根本不成立");
        assertTrue(truncBroken > 0,
                "截断写法居然也扛住了扰动——若真如此，helper 里那句 +0.5 的注释需要重写");
    }
}
