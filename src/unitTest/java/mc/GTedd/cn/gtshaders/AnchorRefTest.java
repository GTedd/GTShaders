package mc.GTedd.cn.gtshaders;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import mc.GTedd.cn.gtshaders.core.AnchorBinding;
import mc.GTedd.cn.gtshaders.core.ParamType;
import mc.GTedd.cn.gtshaders.core.ShaderLayer;
import mc.GTedd.cn.gtshaders.core.ShaderParam;
import mc.GTedd.cn.gtshaders.core.ShaderProject;
import mc.GTedd.cn.gtshaders.workspace.ProjectStore;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 覆盖「效果层按名字指向锚点绑定」这条链。
 *
 * <p>要钉死的核心事实只有一条：<b>绑定列表怎么变，引用都得跟着走</b>。
 * 在此之前效果层里填的是 {@code anchorSlotBase} 算出来的一个整数，而那个整数会随着
 * 上面插入、删除、上下移动绑定而漂移——漂移之后没有任何报错，画面上只是效果
 * 跑到了别的目标身上。这是这套引用存在的全部理由，所以测试的重心在这儿。
 */
class AnchorRefTest {

    /** 一个带 anchor 参数的层。参数名无所谓，类型是 ANCHOR 才有意义。 */
    private static ShaderLayer anchorLayer() {
        ShaderLayer l = new ShaderLayer("L", """
                // @param name=AnchorSlot type=anchor min=0 max=7 default=0
                vec4 gt_main(vec4 c) {
                    return c + float(AnchorSlot) * 0.0;
                }
                """);
        l.setEnabled(true);
        return l;
    }

    private static ShaderParam anchorParam(ShaderProject p) {
        for (ShaderParam sp : p.layers().getFirst().params()) {
            if (sp.type() == ParamType.ANCHOR) {
                return sp;
            }
        }
        throw new AssertionError("层里没有扫出 anchor 类型的参数");
    }

    private static AnchorBinding binding(String name, int slots) {
        AnchorBinding b = new AnchorBinding(name);
        b.setMaxSlots(slots);
        return b;
    }

    @Test
    void 扫描器认得anchor类型() {
        assertEquals(ParamType.ANCHOR, ParamType.parse("anchor"));
        // 落地仍是 int，导出的 post effect JSON 才不需要为它特殊处理
        assertEquals("int", ParamType.ANCHOR.glslType());
        assertEquals("int", ParamType.ANCHOR.jsonType());
        assertTrue(ParamType.ANCHOR.isInteger());
    }

    @Test
    void 层能扫出anchor参数() {
        ShaderProject p = new ShaderProject("ref");
        p.clearLayers();
        p.addLayer(anchorLayer());
        assertEquals(ParamType.ANCHOR, anchorParam(p).type());
    }

    @Test
    void 引用解析成当前槽位号() {
        ShaderProject p = new ShaderProject("ref");
        p.clearLayers();
        p.addLayer(anchorLayer());
        AnchorBinding a = p.addAnchor(binding("A", 3));
        AnchorBinding b = p.addAnchor(binding("B", 1));

        anchorParam(p).setAnchorRef(b.id());
        p.resolveAnchorRefs();
        // A 占 0..2，所以 B 从 3 开始
        assertEquals(3, (int) anchorParam(p).get(0));
        assertEquals(0, p.anchorSlotBaseById(a.id()));
    }

    /**
     * 这套设计存在的理由本身。
     *
     * <p>在 B 前面插一条占 2 槽的绑定之后，B 的槽位号从 3 变成 5。填数字的做法在这里
     * 会静默指向 3 号槽——也就是新插进来那条绑定的第二个目标。
     */
    @Test
    void 前面插入绑定后引用跟着走() {
        ShaderProject p = new ShaderProject("ref");
        p.clearLayers();
        p.addLayer(anchorLayer());
        p.addAnchor(binding("A", 3));
        AnchorBinding b = p.addAnchor(binding("B", 1));

        anchorParam(p).setAnchorRef(b.id());
        p.resolveAnchorRefs();
        assertEquals(3, (int) anchorParam(p).get(0));

        // 在 A 和 B 之间插一条：新绑定加到末尾再上移，等价于面板上的「新建 + 上移」
        AnchorBinding mid = p.addAnchor(binding("MID", 2));
        p.moveAnchor(p.anchors().indexOf(mid), -1);
        p.resolveAnchorRefs();

        assertEquals(5, (int) anchorParam(p).get(0), "B 被挤到 5 号槽，引用必须跟着到 5");
    }

    /** 上下移动绑定顺序同理。 */
    @Test
    void 交换顺序后引用跟着走() {
        ShaderProject p = new ShaderProject("ref");
        p.clearLayers();
        p.addLayer(anchorLayer());
        AnchorBinding a = p.addAnchor(binding("A", 2));
        AnchorBinding b = p.addAnchor(binding("B", 1));

        anchorParam(p).setAnchorRef(a.id());
        p.resolveAnchorRefs();
        assertEquals(0, (int) anchorParam(p).get(0));

        p.moveAnchor(p.anchors().indexOf(a), 1);
        p.resolveAnchorRefs();
        assertEquals(1, (int) anchorParam(p).get(0), "A 排到 B 后面，起始槽位变成 1");
        assertEquals(0, p.anchorSlotBaseById(b.id()));
    }

    /**
     * 指向的绑定被停用或删掉时，值<b>保持不动</b>而不是清零。
     *
     * <p>清零会让效果静悄悄跳到 0 号绑定身上——那恰恰是这套引用要消灭的那类错误，
     * 不能由错误处理自己重新引入一个。界面负责把这种失配标出来，由人决定改指哪一条。
     */
    @Test
    void 绑定失效时不把值清零() {
        ShaderProject p = new ShaderProject("ref");
        p.clearLayers();
        p.addLayer(anchorLayer());
        p.addAnchor(binding("A", 3));
        AnchorBinding b = p.addAnchor(binding("B", 1));

        anchorParam(p).setAnchorRef(b.id());
        p.resolveAnchorRefs();
        assertEquals(3, (int) anchorParam(p).get(0));

        b.setEnabled(false);
        p.resolveAnchorRefs();
        assertEquals(-1, p.anchorSlotBaseById(b.id()), "停用的绑定拿不到槽位");
        assertEquals(3, (int) anchorParam(p).get(0), "值必须保持，不能悄悄回落到 0");

        p.removeAnchor(p.anchors().indexOf(b));
        p.resolveAnchorRefs();
        assertNull(p.findAnchorById(b.id()));
        assertEquals(3, (int) anchorParam(p).get(0));
    }

    /** 没有引用的参数就是裸数字，老工程和手写 {@code gtAnchorUV(3)} 的效果靠它。 */
    @Test
    void 空引用时保持裸数字() {
        ShaderProject p = new ShaderProject("ref");
        p.clearLayers();
        p.addLayer(anchorLayer());
        p.addAnchor(binding("A", 1));

        ShaderParam param = anchorParam(p);
        param.set(0, 5);
        p.resolveAnchorRefs();
        assertEquals(5, (int) param.get(0), "没写 anchorRef 就不该被改动");
    }

    @Test
    void 每条绑定的id互不相同() {
        AnchorBinding a = new AnchorBinding("A");
        AnchorBinding b = new AnchorBinding("A");
        assertNotEquals(a.id(), b.id(), "同名绑定也必须是两个不同的 id");
    }

    /**
     * 存档往返。
     *
     * <p>id 存不住的话，重开工程后所有引用全部失配——而那只会在下一次打开时才暴露出来，
     * 是最难联想到「是保存那一步丢了东西」的一类问题。
     */
    @Test
    void 存档往返保住id与引用() {
        ShaderProject p = new ShaderProject("ref");
        p.clearLayers();
        p.addLayer(anchorLayer());
        p.addAnchor(binding("A", 3));
        AnchorBinding b = p.addAnchor(binding("B", 1));
        anchorParam(p).setAnchorRef(b.id());
        p.resolveAnchorRefs();

        JsonObject json = ProjectStore.toJson(p);
        ShaderProject back = ProjectStore.fromJson(json, "ref");
        assertNotNull(back);

        AnchorBinding backB = back.findAnchorById(b.id());
        assertNotNull(backB, "绑定 id 必须原样存回来");
        assertEquals("B", backB.name());

        ShaderParam backParam = anchorParam(back);
        assertEquals(b.id(), backParam.anchorRef(), "参数指向的绑定 id 必须存回来");
        back.resolveAnchorRefs();
        assertEquals(3, (int) backParam.get(0));
    }

    /** 老工程没有 id 和 anchorRef，读出来应该是「按裸数字用」而不是报错或指错。 */
    @Test
    void 老工程缺id时退化成裸数字() {
        ShaderProject p = new ShaderProject("ref");
        p.clearLayers();
        p.addLayer(anchorLayer());
        p.addAnchor(binding("A", 1));
        anchorParam(p).set(0, 4);

        JsonObject json = ProjectStore.toJson(p);
        // 模拟 format 5 之前的存档：绑定没有 id，层没有 anchorRefs
        json.getAsJsonArray("anchors").get(0).getAsJsonObject().remove("id");
        json.getAsJsonArray("layers").get(0).getAsJsonObject().remove("anchorRefs");

        ShaderProject back = ProjectStore.fromJson(json, "ref");
        assertNotNull(back);
        ShaderParam backParam = anchorParam(back);
        assertTrue(backParam.anchorRef().isEmpty());
        back.resolveAnchorRefs();
        assertEquals(4, (int) backParam.get(0), "老工程存的数字必须原样生效");
    }
}
