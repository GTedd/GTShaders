package mc.GTedd.cn.gtshaders.runtime;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import mc.GTedd.cn.gtshaders.core.AnchorBinding;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 覆盖绑定模式的「绑法」——把来源、选择器、触发器、时长、钉住五项一次配对这件事。
 *
 * <p>这五项在面板上是五个独立控件，组合起来大部分是<b>静默无效</b>的：配错了着色器照常编译、
 * 画面照常渲染，只是效果永远不出现。绑法把有效组合固化成了几个有名字的选项，
 * 而这些测试钉的就是每一个选项配出来的东西真的能跑。
 */
class BindModeTest {

    private static AnchorBinding fresh() {
        // 故意从一个「配错了」的状态出发：常驻触发器 + 方块坐标来源。
        // 绑法必须把每一项都写对，而不是依赖某几项碰巧已经是想要的值
        AnchorBinding b = new AnchorBinding("X");
        b.setSource(AnchorBinding.Source.BLOCK_POS);
        b.setTrigger(AnchorBinding.Trigger.ALWAYS);
        return b;
    }

    private static BindMode.BindTarget blockPick() {
        return new BindMode.BindTarget(BindMode.Kind.BLOCK, "", "", "12,64,-8", false);
    }

    private static BindMode.BindTarget skyPick() {
        return new BindMode.BindTarget(BindMode.Kind.MISS, "", "", "0.00,80.00,0.00", false);
    }

    @Test
    void 钉在坐标写的是方块整数坐标() {
        AnchorBinding b = fresh();
        BindMode.Semantic.HERE.apply(b, blockPick());

        assertEquals(AnchorBinding.Source.BLOCK_POS, b.source());
        assertEquals(AnchorBinding.Trigger.ALWAYS, b.trigger());
        // 解算侧 parseBlockPos 认的就是这个格式，写成小数会解析失败而且没有任何提示
        assertEquals("12,64,-8", b.selector());
        assertEquals(1, b.maxSlots());
    }

    /**
     * 打空（对着天）时也要写出一个能解析的坐标。
     *
     * <p>写空串的话，这条绑定会变成一条永远解不出目标的死绑定——而面板上看不出任何异常。
     */
    @Test
    void 打空时钉在落点坐标() {
        AnchorBinding b = fresh();
        BindMode.Semantic.HERE.apply(b, skyPick());
        assertFalse(b.selector().isBlank());
        assertEquals(3, b.selector().split(",").length);
    }

    /**
     * 「准星落点 · 手动」必须把时长和 Y 偏移一起改掉。
     *
     * <p>时长留在默认的 2.5 秒，蓄力型效果只会播个开头就硬切；
     * Y 偏移留在默认的 1.0，落点会浮在地面上方一格——而这两处都是
     * 「配了、但看起来不对」而不是「配了、完全没反应」，最难联想到原因。
     */
    @Test
    void 准星落点手动一次配全五项() {
        AnchorBinding b = fresh();
        b.setDuration(0.1f);
        b.setYOffset(3f);
        BindMode.Semantic.LOOK_SHOT.apply(b, skyPick());

        assertEquals(AnchorBinding.Source.LOOK_HIT, b.source());
        assertEquals(AnchorBinding.Trigger.MANUAL, b.trigger());
        assertEquals(6f, b.duration(), 1e-6);
        assertEquals(0f, b.yOffset(), 1e-6);
        assertTrue(b.selector().isEmpty());
    }

    @Test
    void 我自己不需要选择器() {
        AnchorBinding b = fresh();
        b.setSelector("1,2,3");
        BindMode.Semantic.SELF.apply(b, skyPick());

        assertEquals(AnchorBinding.Source.SELF, b.source());
        assertEquals(AnchorBinding.Trigger.ALWAYS, b.trigger());
        assertTrue(b.selector().isEmpty(), "换绑法必须把上一种绑法留下的选择器清掉");
    }

    /**
     * 事件型绑法配出来的来源必须真的能触发事件。
     *
     * <p>{@code Source.canFireEvents()} 是解算侧的判据：方块坐标和视线落点身上没有实体，
     * 事件采集那一趟压根扫不到它们。绑法要是配出这种组合，就是造出了一条永不点亮的绑定。
     */
    @Test
    void 事件型绑法配出的来源能触发事件() {
        for (BindMode.Semantic s : BindMode.Semantic.values()) {
            AnchorBinding b = fresh();
            s.apply(b, blockPick());
            if (b.trigger().needsEventSource()) {
                assertTrue(b.source().canFireEvents(),
                        s + " 配出了一条永远不会触发的绑定：" + b.source() + " + " + b.trigger());
            }
        }
    }

    /** 事件型绑定的时长必须非 0，否则 life 恒为 1，效果一上来就停在「已播完」那一帧。 */
    @Test
    void 事件型绑法的时长非零() {
        for (BindMode.Semantic s : BindMode.Semantic.values()) {
            AnchorBinding b = fresh();
            b.setDuration(0f);
            s.apply(b, blockPick());
            if (b.trigger().isEvent()) {
                assertTrue(b.duration() > 0f, s + " 留下了 0 时长的事件型绑定");
            }
        }
    }

    @Test
    void 候选列表按准星上下文收敛() {
        // 世界还没就绪时只给「我自己」——它是唯一不依赖准星的绑法
        assertEquals(List.of(BindMode.Semantic.SELF), BindMode.candidates(null));

        List<BindMode.Semantic> onBlock = BindMode.candidates(blockPick());
        assertSame(BindMode.Semantic.HERE, onBlock.getFirst(),
                "指着方块时最常见的意图是钉在这儿，它该是默认项");
        assertFalse(onBlock.contains(BindMode.Semantic.KIND_ON_DEATH),
                "方块上没有实体，「这一类死了」在这个上下文里配了也不会触发");

        List<BindMode.Semantic> onSky = BindMode.candidates(skyPick());
        assertSame(BindMode.Semantic.LOOK_SHOT, onSky.getFirst());
    }

    /** 每一批候选都必须非空，否则 {@code candidates().getFirst()} 会在切绑法时抛异常。 */
    @Test
    void 每种上下文的候选都非空() {
        assertFalse(BindMode.candidates(null).isEmpty());
        assertFalse(BindMode.candidates(blockPick()).isEmpty());
        assertFalse(BindMode.candidates(skyPick()).isEmpty());
    }

    /**
     * 每个绑法都要有名字和一句说明，中英各一份。
     *
     * <p>漏了的话，状态栏上会直接显示 {@code gtshaders.bind.semantic.xxx} 这样的原始 key——
     * 而它只在切到那一个绑法时才看得见，肉眼巡检基本发现不了。
     */
    @Test
    void 每个绑法的中英文案齐全() {
        var zh = langKeys("zh_cn");
        var en = langKeys("en_us");
        for (BindMode.Semantic s : BindMode.Semantic.values()) {
            String base = "gtshaders.bind.semantic."
                    + s.name().toLowerCase(java.util.Locale.ROOT);
            for (String key : List.of(base, base + ".hint")) {
                assertTrue(zh.contains(key), "zh_cn 缺少 " + key);
                assertTrue(en.contains(key), "en_us 缺少 " + key);
            }
        }
    }

    private static TreeSet<String> langKeys(String lang) {
        try (var in = BindModeTest.class.getResourceAsStream(
                "/assets/gtshaders/lang/" + lang + ".json")) {
            if (in == null) {
                throw new AssertionError(lang + ".json 读不到");
            }
            JsonObject obj = JsonParser.parseString(
                    new String(in.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
            return new TreeSet<>(obj.keySet());
        } catch (Exception e) {
            throw new AssertionError("读取 " + lang + ".json 失败", e);
        }
    }
}
