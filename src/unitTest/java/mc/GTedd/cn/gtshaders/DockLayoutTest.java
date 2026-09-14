package mc.GTedd.cn.gtshaders;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import mc.GTedd.cn.gtshaders.ui.DockLayout;
import mc.GTedd.cn.gtshaders.ui.DockPanel;
import mc.GTedd.cn.gtshaders.ui.DockZone;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 钉住停靠布局那条唯一会「静默坏掉」的不变式：<b>每个面板恰好出现一次</b>。
 *
 * <p>少一个，那块内容就在界面上彻底消失，而且没有任何入口能找回来——用户只会觉得
 * 「版本记录不见了」。多一个，同一个面板被画两遍、注册两套点击区，点一下触发两次回调。
 * 两种都不抛异常、不报错，review 时也看不出来，只有真打开编辑器才发现。
 */
class DockLayoutTest {

    /** 把所有区里的面板摊平，用来数总数与查重。 */
    private static List<DockPanel> allPanels(DockLayout d) {
        List<DockPanel> out = new ArrayList<>();
        for (DockZone z : DockZone.values()) {
            out.addAll(d.panelsIn(z));
        }
        return out;
    }

    private static void assertExactlyOnce(DockLayout d, String when) {
        List<DockPanel> all = allPanels(d);
        assertEquals(DockPanel.values().length, all.size(),
                when + "：面板总数不对，" + all);
        for (DockPanel p : DockPanel.values()) {
            assertEquals(1, all.stream().filter(x -> x == p).count(),
                    when + "：" + p + " 出现的次数不是 1");
        }
    }

    @Test
    void 出厂布局里每个面板恰好一次() {
        assertExactlyOnce(new DockLayout(), "出厂");
    }

    @Test
    void 搬家之后仍然恰好一次() {
        DockLayout d = new DockLayout();
        d.move(DockPanel.VERSIONS, DockZone.BOTTOM, -1);
        d.move(DockPanel.DESIGN, DockZone.LEFT, 0);
        d.move(DockPanel.LAYERS, DockZone.FLOATING, -1);
        assertExactlyOnce(d, "三次搬家后");
        assertEquals(DockZone.BOTTOM, d.zoneOf(DockPanel.VERSIONS));
        assertEquals(DockZone.LEFT, d.zoneOf(DockPanel.DESIGN));
        assertEquals(DockZone.FLOATING, d.zoneOf(DockPanel.LAYERS));
    }

    @Test
    void 把一个区搬空是允许的() {
        DockLayout d = new DockLayout();
        for (DockPanel p : d.panelsIn(DockZone.LEFT)) {
            d.move(p, DockZone.RIGHT, -1);
        }
        assertTrue(d.isEmpty(DockZone.LEFT), "左区应当空了");
        assertExactlyOnce(d, "搬空左区后");
        // 空区没有当前项，调用方要靠这个 null 决定「整条栏都不画」
        org.junit.jupiter.api.Assertions.assertNull(d.active(DockZone.LEFT));
    }

    @Test
    void 存盘再读回来布局不变() {
        DockLayout a = new DockLayout();
        a.move(DockPanel.PIPELINE, DockZone.BOTTOM, -1);
        a.move(DockPanel.ASSETS, DockZone.FLOATING, -1);
        a.setFloatRect(DockPanel.ASSETS, 40, 60, 300, 220);
        a.setActive(DockZone.RIGHT, DockPanel.EXPORT);

        DockLayout b = new DockLayout();
        b.fromJson(JsonParser.parseString(a.toJson().toString()).getAsJsonObject());

        assertExactlyOnce(b, "读盘后");
        for (DockZone z : DockZone.values()) {
            assertEquals(a.panelsIn(z), b.panelsIn(z), z + " 区的面板顺序对不上");
        }
        assertEquals(DockPanel.EXPORT, b.active(DockZone.RIGHT), "选中项没存住");
        assertNotNull(b.floatRect(DockPanel.ASSETS), "浮动矩形没存住");
        assertEquals(300, b.floatRect(DockPanel.ASSETS)[2]);
    }

    /** 手改坏的配置不该让编辑器打不开，更不该让某块内容凭空消失。 */
    @Test
    void 配置里重复或缺失的面板会被修回来() {
        JsonObject broken = JsonParser.parseString("""
                {
                  "left": ["LAYERS", "LAYERS", "ASSETS"],
                  "right": ["LAYERS"],
                  "bottom": ["NOT_A_PANEL"],
                  "floating": {}
                }
                """).getAsJsonObject();
        DockLayout d = new DockLayout();
        d.fromJson(broken);
        assertExactlyOnce(d, "读了坏配置后");
        // 重复的只留最先遇到的那个
        assertEquals(DockZone.LEFT, d.zoneOf(DockPanel.LAYERS));
        // 配置里压根没提的面板回自己家
        assertEquals(DockPanel.DESIGN.home(), d.zoneOf(DockPanel.DESIGN));
    }

    @Test
    void 空配置读进来等于出厂布局() {
        DockLayout d = new DockLayout();
        d.fromJson(new JsonObject());
        assertExactlyOnce(d, "读了空配置后");
        for (DockPanel p : DockPanel.values()) {
            assertEquals(p.home(), d.zoneOf(p), p + " 应当回到默认区");
        }
    }

    @Test
    void 原地不动时要报告没变过() {
        DockLayout d = new DockLayout();
        List<DockPanel> left = d.panelsIn(DockZone.LEFT);
        assertFalse(d.move(left.get(0), DockZone.LEFT, 0),
                "挪到自己原本的位置不算变——拖动时每帧都会问，返回 true 会白白重排和存盘");
        assertTrue(d.move(left.get(0), DockZone.LEFT, 1), "换个位置就该算变了");
    }

    @Test
    void 搬出浮动区时丢掉浮动矩形() {
        DockLayout d = new DockLayout();
        d.move(DockPanel.EXPORT, DockZone.FLOATING, -1);
        d.setFloatRect(DockPanel.EXPORT, 10, 10, 200, 150);
        assertNotNull(d.floatRect(DockPanel.EXPORT));
        d.move(DockPanel.EXPORT, DockZone.RIGHT, -1);
        org.junit.jupiter.api.Assertions.assertNull(d.floatRect(DockPanel.EXPORT),
                "停靠回去之后不该还留着浮动矩形，否则再浮出来会用上一次的陈旧位置");
    }

    @Test
    void 重置回到出厂布局() {
        DockLayout d = new DockLayout();
        d.move(DockPanel.LAYERS, DockZone.BOTTOM, -1);
        d.move(DockPanel.DESIGN, DockZone.FLOATING, -1);
        d.reset();
        assertExactlyOnce(d, "重置后");
        for (DockPanel p : DockPanel.values()) {
            assertEquals(p.home(), d.zoneOf(p), p + " 没回到默认区");
        }
    }
}
