package mc.GTedd.cn.gtshaders;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import mc.GTedd.cn.gtshaders.codegen.GlslCodegen;
import mc.GTedd.cn.gtshaders.codegen.PostEffectJsonBuilder;
import mc.GTedd.cn.gtshaders.core.AnchorBinding;
import mc.GTedd.cn.gtshaders.core.AnchorSlot;
import mc.GTedd.cn.gtshaders.core.GtProfile;
import mc.GTedd.cn.gtshaders.core.ShaderLayer;
import mc.GTedd.cn.gtshaders.core.ShaderProject;
import mc.GTedd.cn.gtshaders.workspace.ProjectStore;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 覆盖世界锚点：uniform 块的三方一致、槽位分配、生命周期曲线、绑定的存取。
 *
 * <p>这里钉的东西和 {@code CodegenTest} 是同一类：<b>错了不会报错</b>。
 * 锚点在 std140 块里占 17 个 vec4，GLSL 声明、post effect JSON、每帧写入的字节
 * 三处只要有一处对不上，后面所有用户参数就整体错位——画面上表现为一堆莫名其妙的颜色，
 * 而不是任何一条错误信息。
 */
class AnchorTest {

    private static ShaderLayer layer(String body) {
        ShaderLayer l = new ShaderLayer("L", body);
        l.setEnabled(true);
        return l;
    }

    private static ShaderProject projectWith(String body) {
        ShaderProject p = new ShaderProject("anchor");
        p.clearLayers();
        p.addLayer(layer(body));
        return p;
    }

    /** 一段用到锚点的最小源码。 */
    private static final String ANCHORED = """
            // @param name=Tint type=color3 default=#FFFFFF
            // @param name=Amount type=float min=0 max=1 default=0.5
            void main() {
                vec2 c = gtAnchorUV(0);
                float r = gtAnchorRange(0);
                float k = step(r, gtAnchorRadius(0)) * gtAnchorVisible(0) * gtAnchorStrength(0);
                fragColor = vec4(mix(texture(InSampler, texCoord).rgb, Tint, k * Amount), 1.0);
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
    void 没用到锚点就一个字节都不注入() {
        GlslCodegen.Output out = layer(PLAIN).generate(GtProfile.MC_26_3);
        assertFalse(out.usesAnchors());
        assertFalse(out.source().contains(GlslCodegen.ANCHOR_INFO_UNIFORM),
                "没用到锚点的着色器不该出现锚点 uniform");
        assertFalse(out.source().contains("gtAnchorUV"), "helper 也不该注入");
    }

    @Test
    void 用到锚点才注入helper与uniform() {
        GlslCodegen.Output out = layer(ANCHORED).generate(GtProfile.MC_26_3);
        assertTrue(out.usesAnchors());
        assertTrue(out.source().contains("vec4 " + GlslCodegen.ANCHOR_INFO_UNIFORM + ";"));
        assertTrue(out.source().contains(
                "vec4 " + GlslCodegen.ANCHOR_A_UNIFORM + "[" + AnchorSlot.SLOTS + "];"));
        assertTrue(out.source().contains(
                "vec4 " + GlslCodegen.ANCHOR_B_UNIFORM + "[" + AnchorSlot.SLOTS + "];"));
        assertTrue(out.source().contains("float gtAnchorRange(int i)"));
    }

    @Test
    void 锚点helper排在自动生成段之内() {
        GlslCodegen.Output out = layer(ANCHORED).generate(GtProfile.MC_26_3);
        int helper = out.source().indexOf("int gtAnchorCount()");
        int headerEnd = out.source().indexOf(GlslCodegen.HEADER_END);
        assertTrue(helper > 0 && helper < headerEnd,
                "helper 必须落在头部里，否则会被 extractAuthorBody 当成作者源码切回来");
    }

    @Test
    void 注入锚点不影响错误行号映射() {
        // headerLineCount 是驱动报的行号减回作者行号的唯一依据。注入 17 个 vec4 和一堆 helper
        // 之后如果这个偏移算错了，编辑器里标红的就是另一行——那比不标还糟
        GlslCodegen.Output out = layer(ANCHORED).generate(GtProfile.MC_26_3);
        String[] lines = out.source().split("\n", -1);
        assertEquals(GlslCodegen.HEADER_END, lines[out.headerLineCount() - 1]);
        assertTrue(lines[out.headerLineCount()].startsWith("// @param"),
                "头部之后紧跟着的应当就是作者源码的第一行");
    }

    // ------------------------------------------------------------ 三方布局一致

    @Test
    void 锚点块在glsl与json里逐项对应() {
        ShaderProject p = projectWith(ANCHORED);
        ShaderProject.Build build = p.generate(GtProfile.MC_26_3, "post/x");
        ShaderProject.PassBuild pass = build.passes().get(0);

        // 运行时也会做这一步，对不上直接抛
        PostEffectJsonBuilder.verifyLayout(pass.output().orderedParams(), pass.output().source());

        JsonObject json = PostEffectJsonBuilder.buildChain("gtshaders", build, new float[]{0, 0, 0, 1});
        JsonArray block = json.getAsJsonArray("passes").get(0).getAsJsonObject()
                .getAsJsonObject("uniforms").getAsJsonArray(GlslCodegen.PARAM_BLOCK);

        assertEquals(GlslCodegen.SYSTEM_UNIFORM, name(block, 0));
        assertEquals(GlslCodegen.LAYER_UNIFORM, name(block, 1));
        assertEquals(GlslCodegen.VIEWPORT_UNIFORM, name(block, 2));
        assertEquals(GlslCodegen.ANCHOR_INFO_UNIFORM, name(block, 3));

        // A 数组整段排完再排 B 数组——不是交错。写入端也必须分两轮，交错会让整块错位
        for (int i = 0; i < AnchorSlot.SLOTS; i++) {
            assertEquals(GlslCodegen.ANCHOR_A_UNIFORM + i, name(block, 4 + i));
        }
        for (int i = 0; i < AnchorSlot.SLOTS; i++) {
            assertEquals(GlslCodegen.ANCHOR_B_UNIFORM + i, name(block, 4 + AnchorSlot.SLOTS + i));
        }

        int head = 4 + AnchorSlot.SLOTS * AnchorSlot.VEC4_PER_SLOT;
        assertEquals(pass.output().orderedParams().size() + head, block.size(),
                "用户参数必须紧接在锚点之后，不多不少");
        for (int i = 0; i < pass.output().orderedParams().size(); i++) {
            assertEquals(pass.output().orderedParams().get(i).name(), name(block, head + i));
        }
    }

    @Test
    void 锚点条目全部是vec4且初值为零() {
        // 初值 0 就是「所有槽位都空」。导出成纯资源包、没有 mod 每帧改写它时，
        // 效果必须退回屏幕空间形态，而不是拿未初始化的值去画
        ShaderProject.Build build = projectWith(ANCHORED).generate(GtProfile.MC_26_3, "post/x");
        JsonObject json = PostEffectJsonBuilder.buildChain("gtshaders", build, new float[]{0, 0, 0, 1});
        JsonArray block = json.getAsJsonArray("passes").get(0).getAsJsonObject()
                .getAsJsonObject("uniforms").getAsJsonArray(GlslCodegen.PARAM_BLOCK);

        for (int i = 3; i < 4 + AnchorSlot.SLOTS * AnchorSlot.VEC4_PER_SLOT; i++) {
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
    void 没用锚点的通道json里不出现锚点条目() {
        ShaderProject.Build build = projectWith(PLAIN).generate(GtProfile.MC_26_3, "post/x");
        JsonObject json = PostEffectJsonBuilder.buildChain("gtshaders", build, new float[]{0, 0, 0, 1});
        JsonArray block = json.getAsJsonArray("passes").get(0).getAsJsonObject()
                .getAsJsonObject("uniforms").getAsJsonArray(GlslCodegen.PARAM_BLOCK);
        for (int i = 0; i < block.size(); i++) {
            assertFalse(name(block, i).startsWith("GTAnchor"),
                    "没用到锚点的通道不该白白多出 17 个 vec4");
        }
    }

    @Test
    void 同一条链里用锚点与不用锚点的层互不干扰() {
        // 每个通道各有各的 uniform 块，一层用锚点不该逼着另一层也带上
        ShaderProject p = new ShaderProject("mixed");
        p.clearLayers();
        p.addLayer(layer(ANCHORED));
        p.addLayer(layer(PLAIN));
        ShaderProject.Build build = p.generate(GtProfile.MC_26_3, "post/x");
        assertEquals(2, build.passes().size());
        assertTrue(build.passes().get(0).output().usesAnchors());
        assertFalse(build.passes().get(1).output().usesAnchors());
        for (ShaderProject.PassBuild pass : build.passes()) {
            PostEffectJsonBuilder.verifyLayout(pass.output().orderedParams(), pass.output().source());
        }
    }

    @Test
    void 生成的着色器仍能原样切回作者源码() {
        // 锚点 helper 是头部的一部分，切回来时必须被切掉；否则导出的 fsh 拖回编辑器会越滚越长
        GlslCodegen.Output out = layer(ANCHORED).generate(GtProfile.MC_26_3);
        String back = GlslCodegen.extractAuthorBody(out.source());
        assertNotNull(back);
        assertEquals(ANCHORED.stripTrailing(), back.stripTrailing());
    }

    // ------------------------------------------------------------ 触发器与配套默认值

    /**
     * 这一组用例钉的是「配了锚点却完全不生效」那个故障。
     *
     * <p>链条是这样的：界面新建绑定永远用 {@code ALWAYS}，而 {@code ALWAYS} 的时长<b>就是 0</b>
     * （{@code isPersistent()} 正是靠它判定）。作者随后在下拉框里把触发器改成「死亡时」，
     * 时长却还是 0 —— 于是 {@code life} 恒等于 1，效果一上来就停在「已经播完」那一帧。
     * 天降激光在 t=1 时光柱、冲击环、落点闪光的包络全是 0，画面上什么都不会出现。
     *
     * <p>全程没有任何报错，面板上每一项看起来都填得好好的。只能靠用例挡。
     */
    @Test
    void 从常驻切到事件型会带上非零时长() {
        AnchorBinding b = AnchorBinding.withTrigger("x", AnchorBinding.Trigger.ALWAYS);
        assertTrue(b.isPersistent(), "常驻绑定的时长本来就是 0");

        b.setTrigger(AnchorBinding.Trigger.ON_DEATH);
        assertFalse(b.isPersistent(),
                "事件型绑定带着 0 时长 = life 恒为 1，效果永远停在结束那一帧");
        assertTrue(b.duration() > 0f);
    }

    @Test
    void 事件型之间互相切换不动作者调过的数值() {
        AnchorBinding b = AnchorBinding.withTrigger("x", AnchorBinding.Trigger.ON_DEATH);
        b.setDuration(7.5f);
        b.setEasing(AnchorBinding.Easing.PULSE);

        b.setTrigger(AnchorBinding.Trigger.ON_HURT);
        assertEquals(7.5f, b.duration(), 1e-4f, "同为事件型时不该重置作者调过的时长");
        assertEquals(AnchorBinding.Easing.PULSE, b.easing());
    }

    @Test
    void 事件型触发器的默认来源必须能真的点亮() {
        // LOOK_AT 配「死亡时」在实践中等于永不触发：实体倒下的那一 tick，
        // 准星几乎不可能还正好指着它。默认值不该把人引到这条死路上
        for (AnchorBinding.Trigger t : AnchorBinding.Trigger.values()) {
            if (!t.isEvent() || t == AnchorBinding.Trigger.MANUAL) {
                continue;
            }
            AnchorBinding b = AnchorBinding.withTrigger("x", t);
            assertTrue(b.source().canFireEvents(),
                    t + " 的默认来源 " + b.source() + " 配事件触发永远不会点亮");
            assertEquals("", b.warning(), t + " 的默认配置本身就是有问题的");
        }
    }

    /**
     * 手动触发不该被当成「靠世界事件采集」的触发器。
     *
     * <p>这是实际踩到的一个 bug：在面板上把来源选成「准星指向的实体」，再把触发器改成
     * 「仅手动」，来源会被<b>悄悄改回</b>「实体类型」——因为 {@code applyTriggerDefaults}
     * 用的是 {@code isEvent()} 而不是 {@code needsEventSource()}，而 {@code warning()}
     * 用的却是后者。两处判据分叉，于是界面一边说这个组合没问题、一边把它改掉，
     * 表现出来是「这个下拉选不上」。
     */
    @Test
    void 手动触发不会被改掉来源() {
        for (AnchorBinding.Source src : AnchorBinding.Source.values()) {
            AnchorBinding b = AnchorBinding.withTrigger("x", AnchorBinding.Trigger.ALWAYS);
            b.setSource(src);
            b.setTrigger(AnchorBinding.Trigger.MANUAL);
            assertEquals(src, b.source(),
                    "手动触发配 " + src + " 完全合法，simulate 认得每一种来源");
            assertEquals("", b.warning(), "既然合法就不该报警告");
        }
        assertFalse(AnchorBinding.Trigger.MANUAL.needsEventSource());
        assertTrue(AnchorBinding.Trigger.ON_DEATH.needsEventSource());
    }

    /**
     * 手动触发的默认值要能直接播完一段完整演出。
     *
     * <p>2.5 秒对奇点炸弹这类效果只够播个开头就硬切；钉住则会让绑在实体上的效果
     * 留在按下那一刻的坐标，怪走开效果还在原地。两者都是「配了、但看起来不对」，
     * 比「完全没反应」更难查。
     */
    @Test
    void 手动触发默认跟随且时长足够() {
        AnchorBinding b = AnchorBinding.withTrigger("x", AnchorBinding.Trigger.MANUAL);
        assertEquals(6f, b.duration(), 1e-4f);
        assertFalse(b.isStick(), "跟着目标走，否则绑在怪身上的效果会留在原地");
    }

    /**
     * 屏幕半径上限默认是打开的。
     *
     * <p>屏幕半径 = {@code worldRadius / (dist · 2 · tan(fov/2))}，距离在分母上，
     * 所以走近目标时无界增长：默认的 1.5 格半径在 1 格外投影出 1.07，比整个屏幕还高。
     * 没有上限的话，把奇点炸弹绑到一只怪身上再走近它，整个屏幕会变成纯黑。
     */
    @Test
    void 屏幕半径上限默认打开() {
        AnchorBinding b = new AnchorBinding("x");
        assertEquals(AnchorBinding.DEFAULT_MAX_SCREEN_RADIUS, b.maxScreenRadius(), 1e-6f);
        assertTrue(b.maxScreenRadius() > 0f, "默认必须是打开的，这是一张安全网不是可选项");
        assertTrue(b.maxScreenRadius() < 1f, "上限本身超过一屏高就失去意义了");

        // 0 是「不限」的表达，得留得住——真想要糊满屏幕的人靠它
        b.setMaxScreenRadius(0f);
        assertEquals(0f, b.maxScreenRadius(), 1e-6f);
        // 负数和过大值都夹回合法区间
        b.setMaxScreenRadius(-5f);
        assertEquals(0f, b.maxScreenRadius(), 1e-6f);
        b.setMaxScreenRadius(99f);
        assertEquals(2f, b.maxScreenRadius(), 1e-6f);
    }

    @Test
    void 认得出永远不会生效的组合() {
        AnchorBinding b = AnchorBinding.withTrigger("x", AnchorBinding.Trigger.ON_DEATH);
        assertEquals("", b.warning());

        // 视线落点身上没有实体，事件采集那一趟根本扫不到它
        b.setSource(AnchorBinding.Source.LOOK_HIT);
        assertFalse(b.warning().isEmpty(), "这个组合永远不会触发，界面必须说出来");

        // 手动触发走的是另一条解算路径，认得视线落点，不该被误报
        AnchorBinding manual = AnchorBinding.withTrigger("m", AnchorBinding.Trigger.MANUAL);
        manual.setSource(AnchorBinding.Source.LOOK_HIT);
        assertEquals("", manual.warning());
    }

    /**
     * 存档往返必须无损。
     *
     * <p>{@code setTrigger} 现在会连带铺一套默认值，于是读档时的<b>调用顺序</b>成了正确性的一部分：
     * 触发器要是排在时长后面读，那套默认值就会盖掉文件里存的真值——存进去 7.5 秒，
     * 读出来变成 2.5 秒，而且不会有任何提示。
     */
    @Test
    void 绑定存档往返之后每一项都不变() {
        ShaderProject p = new ShaderProject("rt");
        AnchorBinding b = AnchorBinding.withTrigger("死亡黑洞", AnchorBinding.Trigger.ON_DEATH);
        b.setSource(AnchorBinding.Source.ENTITY_TYPE);
        b.setSelector("minecraft:zombie");
        b.setDuration(7.5f);
        b.setEasing(AnchorBinding.Easing.PULSE);
        b.setStick(false);
        b.setWorldRadius(4.25f);
        b.setMaxScreenRadius(0.8f);
        p.addAnchor(b);

        ShaderProject back = mc.GTedd.cn.gtshaders.workspace.ProjectStore.fromJson(
                mc.GTedd.cn.gtshaders.workspace.ProjectStore.toJson(p), "rt");
        AnchorBinding r = back.anchors().get(0);
        assertEquals(AnchorBinding.Trigger.ON_DEATH, r.trigger());
        assertEquals(AnchorBinding.Source.ENTITY_TYPE, r.source());
        assertEquals("minecraft:zombie", r.selector());
        assertEquals(7.5f, r.duration(), 1e-4f, "时长被触发器的默认值盖掉了");
        assertEquals(AnchorBinding.Easing.PULSE, r.easing());
        assertFalse(r.isStick());
        assertEquals(4.25f, r.worldRadius(), 1e-4f);
        assertEquals(0.8f, r.maxScreenRadius(), 1e-4f);
    }

    // ------------------------------------------------------------ 槽位分配

    @Test
    void 槽位按绑定顺序累加分配() {
        ShaderProject p = new ShaderProject("slots");
        AnchorBinding a = new AnchorBinding("a");
        AnchorBinding b = new AnchorBinding("b");
        AnchorBinding c = new AnchorBinding("c");
        a.setMaxSlots(1);
        b.setMaxSlots(3);
        c.setMaxSlots(2);
        p.addAnchor(a);
        p.addAnchor(b);
        p.addAnchor(c);

        assertEquals(0, p.anchorSlotBase(0));
        assertEquals(1, p.anchorSlotBase(1));
        assertEquals(4, p.anchorSlotBase(2));
        assertEquals(6, p.anchorSlotsUsed());
    }

    @Test
    void 停用的绑定不占槽位() {
        // 停用一条绑定会让后面的槽位号整体前移，界面必须把号显示出来——
        // 否则作者源码里写死的 gtAnchor(1) 会静悄悄指到另一个东西上
        ShaderProject p = new ShaderProject("slots");
        AnchorBinding a = new AnchorBinding("a");
        AnchorBinding b = new AnchorBinding("b");
        a.setMaxSlots(2);
        b.setMaxSlots(2);
        p.addAnchor(a);
        p.addAnchor(b);
        assertEquals(2, p.anchorSlotBase(1));

        a.setEnabled(false);
        assertEquals(0, p.anchorSlotBase(1));
        assertEquals(2, p.anchorSlotsUsed());
    }

    @Test
    void 槽位排满之后再加的绑定拿不到号() {
        ShaderProject p = new ShaderProject("slots");
        AnchorBinding big = new AnchorBinding("big");
        big.setMaxSlots(AnchorSlot.SLOTS);
        p.addAnchor(big);
        p.addAnchor(new AnchorBinding("overflow"));
        assertEquals(0, p.anchorSlotBase(0));
        assertEquals(-1, p.anchorSlotBase(1), "挤不进去要明确返回 -1，界面据此提示而不是画一个错的号");
    }

    @Test
    void 上限被钳在槽位总数以内() {
        AnchorBinding b = new AnchorBinding("b");
        b.setMaxSlots(999);
        assertEquals(AnchorSlot.SLOTS, b.maxSlots());
        b.setMaxSlots(0);
        assertEquals(1, b.maxSlots());
    }

    // ------------------------------------------------------------ 生命周期曲线

    @Test
    void 缓动曲线的端点都对() {
        for (AnchorBinding.Easing e : AnchorBinding.Easing.values()) {
            assertEquals(e == AnchorBinding.Easing.HOLD ? 1f : 0f, e.apply(0f), 1e-6,
                    e + " 在 t=0 处");
            float end = e.apply(1f);
            if (e == AnchorBinding.Easing.PULSE) {
                assertEquals(0f, end, 1e-6, "脉冲必须回到 0，否则一闪而过的效果会卡在最亮处");
            } else {
                assertEquals(1f, end, 1e-6, e + " 在 t=1 处");
            }
            // 全程不出界，否则乘上去会让颜色溢出
            for (int i = 0; i <= 20; i++) {
                float v = e.apply(i / 20f);
                assertTrue(v >= -1e-6 && v <= 1f + 1e-6, e + " 在 t=" + (i / 20f) + " 处越界：" + v);
            }
        }
    }

    @Test
    void 常驻绑定不应用缓动() {
        // 脉冲曲线在 t=1 处是 0。常驻的东西「进度 100%」时消失显然不是任何人想要的
        AnchorBinding b = new AnchorBinding("b");
        b.setDuration(0f);
        b.setEasing(AnchorBinding.Easing.PULSE);
        b.setStrength(0.8f);
        assertTrue(b.isPersistent());
        assertEquals(0.8f, b.strengthAt(1f), 1e-6);
        assertEquals(0.8f, b.strengthAt(0f), 1e-6);
    }

    @Test
    void 事件绑定的强度跟着曲线走() {
        AnchorBinding b = new AnchorBinding("b");
        b.setDuration(2f);
        b.setEasing(AnchorBinding.Easing.EASE_IN);
        b.setStrength(1f);
        assertEquals(0f, b.strengthAt(0f), 1e-6);
        assertEquals(0.25f, b.strengthAt(0.5f), 1e-6);
        assertEquals(1f, b.strengthAt(1f), 1e-6);
        // 越界的进度被钳住，不会算出负强度或超过 1
        assertEquals(0f, b.strengthAt(-3f), 1e-6);
        assertEquals(1f, b.strengthAt(9f), 1e-6);
    }

    @Test
    void 按触发器给的默认值符合各自的节奏() {
        AnchorBinding death = AnchorBinding.withTrigger("死亡", AnchorBinding.Trigger.ON_DEATH);
        assertTrue(death.isStick(), "死亡锚点要钉住：尸体几秒后就没了，跟着它会让效果跟着消失");
        assertFalse(death.isPersistent());

        AnchorBinding hurt = AnchorBinding.withTrigger("受伤", AnchorBinding.Trigger.ON_HURT);
        assertFalse(hurt.isStick(), "受伤锚点要跟随：挨打的人还在跑");
        assertEquals(AnchorBinding.Easing.PULSE, hurt.easing());

        AnchorBinding manual = AnchorBinding.withTrigger("手动", AnchorBinding.Trigger.MANUAL);
        assertFalse(manual.isStick(), "手动锚点要跟随：最常见的用法是「看着那只怪按一下」");
        assertEquals(6f, manual.duration(), 1e-4f, "演出型效果光蓄力就要三秒以上");

        AnchorBinding always = AnchorBinding.withTrigger("常驻", AnchorBinding.Trigger.ALWAYS);
        assertTrue(always.isPersistent());
        assertFalse(always.trigger().isEvent());
    }

    // ------------------------------------------------------------ 选择器解析

    @Test
    void 裸id被补上命名空间() {
        assertEquals("minecraft:zombie", AnchorBinding.normalizeId("zombie"));
        assertEquals("minecraft:zombie", AnchorBinding.normalizeId("  ZOMBIE "));
        assertEquals("mymod:boss", AnchorBinding.normalizeId("mymod:boss"));
        assertEquals("", AnchorBinding.normalizeId("   "));
        assertEquals("", AnchorBinding.normalizeId(null));
    }

    @Test
    void 坐标选择器容忍多种分隔() {
        assertArrayEqualsD(new double[]{1, 2, 3}, AnchorBinding.parseBlockPos("1,2,3"));
        assertArrayEqualsD(new double[]{1, 2, 3}, AnchorBinding.parseBlockPos("1 2 3"));
        assertArrayEqualsD(new double[]{-4.5, 64, 12}, AnchorBinding.parseBlockPos("-4.5, 64, 12"));
        // 中文逗号是中文输入法下最容易打出来的，直接认掉而不是让人对着「无效坐标」发呆
        assertArrayEqualsD(new double[]{1, 2, 3}, AnchorBinding.parseBlockPos("1，2，3"));
    }

    @Test
    void 坏掉的坐标选择器返回null而不是零点() {
        assertNull(AnchorBinding.parseBlockPos("1,2"));
        assertNull(AnchorBinding.parseBlockPos("a,b,c"));
        assertNull(AnchorBinding.parseBlockPos(""));
        assertNull(AnchorBinding.parseBlockPos(null));
    }

    private static void assertArrayEqualsD(double[] expected, double[] actual) {
        assertNotNull(actual);
        assertEquals(expected.length, actual.length);
        for (int i = 0; i < expected.length; i++) {
            assertEquals(expected[i], actual[i], 1e-9);
        }
    }

    // ------------------------------------------------------------ 存取

    @Test
    void 绑定存下来再读回来完全一致() {
        ShaderProject p = new ShaderProject("save");
        AnchorBinding b = AnchorBinding.withTrigger("死亡黑洞", AnchorBinding.Trigger.ON_DEATH);
        b.setSource(AnchorBinding.Source.ENTITY_TYPE);
        b.setSelector("minecraft:zombie");
        b.setMaxSlots(3);
        b.setOcclusion(true);
        b.setWorldRadius(4.25f);
        b.setStrength(0.75f);
        b.setYOffset(1.5f);
        b.setMaxDistance(48f);
        b.setEasing(AnchorBinding.Easing.EASE_IN_OUT);
        p.addAnchor(b);

        ShaderProject back = ProjectStore.fromJson(
                JsonParser.parseString(ProjectStore.toJson(p).toString()).getAsJsonObject(), "x");
        assertEquals(1, back.anchorCount());
        AnchorBinding r = back.anchors().get(0);
        assertEquals("死亡黑洞", r.name());
        assertEquals(AnchorBinding.Source.ENTITY_TYPE, r.source());
        assertEquals("minecraft:zombie", r.selector());
        assertEquals(AnchorBinding.Trigger.ON_DEATH, r.trigger());
        assertEquals(3, r.maxSlots());
        assertTrue(r.isOcclusion());
        assertEquals(4.25f, r.worldRadius(), 1e-6);
        assertEquals(0.75f, r.strength(), 1e-6);
        assertEquals(1.5f, r.yOffset(), 1e-6);
        assertEquals(48f, r.maxDistance(), 1e-6);
        assertEquals(AnchorBinding.Easing.EASE_IN_OUT, r.easing());
    }

    @Test
    void 没有绑定时不写anchors键() {
        ShaderProject p = new ShaderProject("empty");
        assertFalse(ProjectStore.toJson(p).has("anchors"),
                "空数组和「根本没用锚点」是一回事，不写这个键让老工具读到的东西完全一样");
    }

    @Test
    void 老工程文件照常打开() {
        // format 2 的工程没有 anchors 段，读进来就是零条绑定
        String old = """
                {"format":2,"name":"old","layers":[
                  {"name":"L","enabled":true,"source":"void main(){fragColor=vec4(1.0);}","values":{}}
                ]}
                """;
        ShaderProject p = ProjectStore.fromJson(JsonParser.parseString(old).getAsJsonObject(), "x");
        assertEquals(1, p.layerCount());
        assertEquals(0, p.anchorCount());
    }

    @Test
    void 认不出的枚举值回落到默认而不是打不开工程() {
        // 工程文件是鼓励手改的，写错一个枚举名不该让整条绑定消失
        String broken = """
                {"format":3,"name":"x","layers":[],"anchors":[
                  {"name":"b","source":"nonsense","trigger":"whatever","easing":"nope"}
                ]}
                """;
        ShaderProject p = ProjectStore.fromJson(JsonParser.parseString(broken).getAsJsonObject(), "x");
        assertEquals(1, p.anchorCount());
        AnchorBinding b = p.anchors().get(0);
        assertEquals(AnchorBinding.Source.SELF, b.source());
        assertEquals(AnchorBinding.Trigger.ALWAYS, b.trigger());
        assertEquals(AnchorBinding.Easing.HOLD, b.easing());
    }

    @Test
    void 工程复制会带上绑定且互不影响() {
        ShaderProject p = new ShaderProject("src");
        p.addAnchor(new AnchorBinding("原件"));
        ShaderProject c = p.copy();
        assertEquals(1, c.anchorCount());
        c.anchors().get(0).setName("改过的");
        assertEquals("原件", p.anchors().get(0).name(),
                "快照回退靠的是深拷贝，共享同一个对象会让回退根本回不去");
    }

    @Test
    void 移动绑定会改变槽位号() {
        ShaderProject p = new ShaderProject("move");
        AnchorBinding a = new AnchorBinding("a");
        AnchorBinding b = new AnchorBinding("b");
        a.setMaxSlots(2);
        p.addAnchor(a);
        p.addAnchor(b);
        assertEquals(2, p.anchorSlotBase(1));

        p.moveAnchor(1, -1);
        assertEquals("b", p.anchors().get(0).name());
        assertEquals(1, p.anchorSlotBase(1), "a 移到后面，它的起始槽位跟着变");
    }

    private static String name(JsonArray block, int i) {
        return block.get(i).getAsJsonObject().get("name").getAsString();
    }
}
