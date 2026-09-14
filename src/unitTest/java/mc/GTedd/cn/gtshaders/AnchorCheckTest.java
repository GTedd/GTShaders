package mc.GTedd.cn.gtshaders;

import org.junit.jupiter.api.Test;
import mc.GTedd.cn.gtshaders.core.AnchorBinding;
import mc.GTedd.cn.gtshaders.core.ParamType;
import mc.GTedd.cn.gtshaders.core.ShaderLayer;
import mc.GTedd.cn.gtshaders.core.ShaderParam;
import mc.GTedd.cn.gtshaders.core.ShaderProject;
import mc.GTedd.cn.gtshaders.runtime.AnchorCheck;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 覆盖锚点排查链里唯一能离线测的一环：<b>有没有效果层在读这条绑定的槽位</b>。
 *
 * <p>这一环值得单独钉住，因为它是「配好了、也触发了、就是不亮」最常见的原因，
 * 而且从锚点面板上完全看不出来——面板只显示绑定自己的状态，不显示有没有人在读它。
 * 效果层的 {@code AnchorSlot} 参数默认指向槽位 0，新加的绑定却往往落在 #1、#2。
 *
 * <p>{@code AnchorCheck.check} 的其余环节要问 {@code PreviewRuntime}（进而碰 Minecraft），
 * 在这套没有 Minecraft 的用例里够不到，所以只测这个纯函数。
 */
class AnchorCheckTest {

    private static ShaderLayer anchorLayer() {
        ShaderLayer l = new ShaderLayer("L", """
                // @param name=AnchorSlot type=anchor min=0 max=7 default=0
                void main() {
                    fragColor = vec4(float(AnchorSlot) * 0.0);
                }
                """);
        l.setEnabled(true);
        return l;
    }

    /** 完全不碰锚点的层。 */
    private static ShaderLayer plainLayer() {
        ShaderLayer l = new ShaderLayer("P", """
                void main() {
                    fragColor = texture(InSampler, texCoord);
                }
                """);
        l.setEnabled(true);
        return l;
    }

    private static ShaderParam anchorParam(ShaderLayer l) {
        for (ShaderParam sp : l.params()) {
            if (sp.type() == ParamType.ANCHOR) {
                return sp;
            }
        }
        throw new AssertionError("层里没有扫出 anchor 类型的参数");
    }

    private static ShaderProject project(ShaderLayer... layers) {
        ShaderProject p = new ShaderProject("A");
        p.clearLayers();
        for (ShaderLayer l : layers) {
            p.addLayer(l);
        }
        return p;
    }

    @Test
    void 参数按id指向这条绑定时算读到了() {
        ShaderLayer l = anchorLayer();
        ShaderProject p = project(l);
        AnchorBinding b = new AnchorBinding("锚点 1");
        p.addAnchor(b);

        anchorParam(l).setAnchorRef(b.id());
        assertTrue(AnchorCheck.isSlotRead(p, b, 0));
    }

    @Test
    void 参数指向别的绑定时不算() {
        ShaderLayer l = anchorLayer();
        ShaderProject p = project(l);
        AnchorBinding first = new AnchorBinding("锚点 1");
        AnchorBinding second = new AnchorBinding("锚点 2");
        p.addAnchor(first);
        p.addAnchor(second);

        anchorParam(l).setAnchorRef(first.id());
        // 这正是「配好了却不亮」的典型：层读的是 #0，而你在调的是 #1
        assertFalse(AnchorCheck.isSlotRead(p, second, 1));
        assertTrue(AnchorCheck.isSlotRead(p, first, 0));
    }

    @Test
    void 没绑id时按裸槽位号算() {
        // 作者手填数字的老路径仍然有效
        ShaderLayer l = anchorLayer();
        ShaderProject p = project(l);
        AnchorBinding b = new AnchorBinding("锚点 1");
        p.addAnchor(b);

        ShaderParam sp = anchorParam(l);
        sp.setAnchorRef("");
        sp.set(0, 2f);
        assertTrue(AnchorCheck.isSlotRead(p, b, 2));
        assertFalse(AnchorCheck.isSlotRead(p, b, 0));
    }

    @Test
    void 裸槽位号落在多槽绑定的区间里也算() {
        // 一条绑定可以占多个槽位，读区间里任何一个都算读到了它
        ShaderLayer l = anchorLayer();
        ShaderProject p = project(l);
        AnchorBinding b = new AnchorBinding("锚点 1");
        b.setMaxSlots(3);
        p.addAnchor(b);

        ShaderParam sp = anchorParam(l);
        sp.setAnchorRef("");
        sp.set(0, 3f);
        assertTrue(AnchorCheck.isSlotRead(p, b, 1), "槽位 1..3 都属于这条绑定");
        assertFalse(AnchorCheck.isSlotRead(p, b, 4));
    }

    @Test
    void 没有任何层读锚点时不算() {
        ShaderProject p = project(plainLayer());
        AnchorBinding b = new AnchorBinding("锚点 1");
        p.addAnchor(b);
        assertFalse(AnchorCheck.isSlotRead(p, b, 0));
    }

    @Test
    void 停用的层不算数() {
        // 层关掉了就不参与渲染，它读不读锚点都与画面无关
        ShaderLayer l = anchorLayer();
        ShaderProject p = project(l);
        AnchorBinding b = new AnchorBinding("锚点 1");
        p.addAnchor(b);
        anchorParam(l).setAnchorRef(b.id());

        assertTrue(AnchorCheck.isSlotRead(p, b, 0));
        l.setEnabled(false);
        assertFalse(AnchorCheck.isSlotRead(p, b, 0));
    }
}
