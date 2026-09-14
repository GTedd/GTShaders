package mc.GTedd.cn.gtshaders;

import org.junit.jupiter.api.Test;
import mc.GTedd.cn.gtshaders.codegen.GlslCodegen;
import mc.GTedd.cn.gtshaders.core.GtProfile;
import mc.GTedd.cn.gtshaders.core.ShaderLayer;
import mc.GTedd.cn.gtshaders.core.ShaderParam;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * 钉住「在面板上改参数，画面立刻跟着变」这条链路。
 *
 * <h2>它是怎么断的</h2>
 *
 * <p>运行时每帧把参数写进 uniform 缓冲，读的是<b>编译那一刻</b>存进
 * {@code PassBuild.output().orderedParams()} 的那批 {@link ShaderParam} 对象。
 * 而作者一改源码，{@code ShaderLayer.rescan()} 就会把 {@code params} 整个换成新对象
 * （旧值按名字迁移过去）。
 *
 * <p>于是这两批对象在「改过源码、但还没重新编译完」的那段时间里是<b>两批不同的对象</b>：
 * 面板上拖的是新的，uniform 写的是旧的——调了没反应。自动编译有 450ms 延迟，
 * 编译失败时更是一直不会更新，所以这段窗口在实际使用中并不短。
 *
 * <p>颜色最容易撞上：它常常是「改完代码顺手调一下色」的操作，而且不像滑块那样
 * 有个数值能看出来到底改没改。
 */
class ParamLivenessTest {

    private static final String SRC = """
            // @param name=Tint type=color3 default=#FFFFFF zh_cn=染色
            // @param name=Amount type=float min=0 max=1 default=0.5 zh_cn=强度

            void main() {
                fragColor = vec4(texture(InSampler, texCoord).rgb * Tint * Amount, 1.0);
            }
            """;

    private static ShaderParam byName(java.util.List<ShaderParam> list, String name) {
        for (ShaderParam p : list) {
            if (p.name().equals(name)) {
                return p;
            }
        }
        return null;
    }

    @Test
    void 编译产物里的参数就是图层持有的那批活对象() {
        ShaderLayer layer = new ShaderLayer("t", SRC);
        GlslCodegen.Output out = layer.generate(GtProfile.MC_26_3);

        for (ShaderParam p : out.orderedParams()) {
            ShaderParam live = byName(layer.params(), p.name());
            assertNotNull(live, p.name() + " 不在图层的参数表里");
            assertSame(live, p, p.name() + " 不是同一个对象，面板上改它不会进 uniform");
        }
    }

    /**
     * 核心回归：改过源码之后，编译产物里的参数必须仍然指向图层当前持有的那批对象。
     *
     * <p>这里刻意<b>不</b>重新编译——重新编译当然会修好，问题正是出在两次编译之间。
     */
    @Test
    void 改过源码之后编译产物仍指向活对象() {
        ShaderLayer layer = new ShaderLayer("t", SRC);
        GlslCodegen.Output out = layer.generate(GtProfile.MC_26_3);

        // 作者在源码里加了一行注释——参数一个没变，但 rescan 已经把对象全换了一批
        layer.setAuthorSource(SRC + "\n// 随手加的一行注释\n");

        ShaderParam liveTint = byName(layer.params(), "Tint");
        assertNotNull(liveTint);
        // 面板上把染色调成纯红
        liveTint.setAll(new float[]{1f, 0f, 0f, 1f});

        ShaderParam uploaded = byName(out.orderedParams(), "Tint");
        assertNotNull(uploaded);
        assertEquals(1f, uploaded.get(0), 1e-4f, "写进 uniform 的红色分量没跟上");
        assertEquals(0f, uploaded.get(1), 1e-4f, "写进 uniform 的绿色分量没跟上——面板改了画面不会变");
        assertEquals(0f, uploaded.get(2), 1e-4f);
    }

    @Test
    void 改过源码之后数值参数同样跟得上() {
        ShaderLayer layer = new ShaderLayer("t", SRC);
        GlslCodegen.Output out = layer.generate(GtProfile.MC_26_3);
        layer.setAuthorSource(SRC + "\n// 注释\n");

        ShaderParam live = byName(layer.params(), "Amount");
        assertNotNull(live);
        live.set(0, 0.25f);

        ShaderParam uploaded = byName(out.orderedParams(), "Amount");
        assertNotNull(uploaded);
        assertEquals(0.25f, uploaded.get(0), 1e-4f);
    }

    /**
     * 参数被整个删掉时不能崩，也不能把别的参数的值写错位——uniform 块的字节布局
     * 是按 orderedParams 的顺序铺的，顺序错一位后面全乱。
     */
    @Test
    void 参数被删掉之后顺序与数量保持不变() {
        ShaderLayer layer = new ShaderLayer("t", SRC);
        GlslCodegen.Output out = layer.generate(GtProfile.MC_26_3);
        int before = out.orderedParams().size();

        layer.setAuthorSource("void main() { fragColor = vec4(1.0); }");

        assertEquals(before, out.orderedParams().size(),
                "编译产物的参数表在重新编译前不该变长或变短");
        assertEquals("Tint", out.orderedParams().get(0).name());
    }
}
