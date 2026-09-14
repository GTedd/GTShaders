package mc.GTedd.cn.gtshaders;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import mc.GTedd.cn.gtshaders.core.ShaderLayer;
import mc.GTedd.cn.gtshaders.core.ShaderProject;
import mc.GTedd.cn.gtshaders.workspace.ProjectStore;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 取景框下放到层之后要守住的几件事。
 *
 * <p>这块最容易悄悄坏掉：框存错地方不会报错，只会在下次打开工程时「所有框都变回全屏」，
 * 而那时候已经没法知道原来框在哪了。
 */
class LayerViewportTest {

    private static final String SRC = "void main() { fragColor = texture(InSampler, texCoord); }\n";

    @Test
    void 每层各有一个互不影响的框() {
        ShaderProject p = new ShaderProject("t");
        ShaderLayer a = new ShaderLayer("A", SRC);
        ShaderLayer b = new ShaderLayer("B", SRC);
        p.addLayer(a);
        p.addLayer(b);

        a.viewport().set(0.1f, 0.1f, 0.5f, 0.5f);
        assertTrue(b.viewport().isFullScreen(), "改 A 的框不该动到 B");
        assertEquals(0.1f, a.viewport().x0(), 1e-4f);
    }

    @Test
    void 层级取景框存得下也读得回() {
        ShaderProject p = new ShaderProject("t");
        ShaderLayer a = new ShaderLayer("A", SRC);
        ShaderLayer b = new ShaderLayer("B", SRC);
        p.addLayer(a);
        p.addLayer(b);
        a.viewport().set(0.2f, 0.3f, 0.8f, 0.9f);

        ShaderProject back = ProjectStore.fromJson(ProjectStore.toJson(p), "t");
        assertEquals(2, back.layers().size());
        assertEquals(0.2f, back.layers().get(0).viewport().x0(), 1e-4f);
        assertEquals(0.9f, back.layers().get(0).viewport().y1(), 1e-4f);
        assertTrue(back.layers().get(1).viewport().isFullScreen(), "没框过的层读回来还是全屏");
    }

    /**
     * 老工程（format ≤ 3）把框记在工程上。读进来要铺给每一层，
     * 否则一打开所有精心框好的区域都会变回全屏，而且无法挽回。
     */
    @Test
    void 老工程的工程级框会迁移到每一层() {
        ShaderProject p = new ShaderProject("t");
        p.addLayer(new ShaderLayer("A", SRC));
        p.addLayer(new ShaderLayer("B", SRC));
        JsonObject json = ProjectStore.toJson(p);
        // 模拟老格式：工程级有框，层级没有
        json.getAsJsonArray("viewport").set(0, new com.google.gson.JsonPrimitive(0.25f));
        json.getAsJsonArray("viewport").set(2, new com.google.gson.JsonPrimitive(0.75f));
        for (var el : json.getAsJsonArray("layers")) {
            el.getAsJsonObject().remove("viewport");
        }

        ShaderProject back = ProjectStore.fromJson(json, "t");
        for (ShaderLayer l : back.layers()) {
            assertEquals(0.25f, l.viewport().x0(), 1e-4f, "工程级的框没迁移到 " + l.name());
            assertEquals(0.75f, l.viewport().x1(), 1e-4f);
        }
    }

    @Test
    void 已保存的全屏层不继承旧工程的裁剪框() {
        ShaderProject p = new ShaderProject("migrated");
        p.viewport().set(0.25f, 0.2f, 0.75f, 0.8f);
        p.addLayer(new ShaderLayer("全屏", SRC));
        ShaderLayer cropped = new ShaderLayer("局部", SRC);
        cropped.viewport().set(0.1f, 0.1f, 0.4f, 0.5f);
        p.addLayer(cropped);

        ShaderProject back = ProjectStore.fromJson(ProjectStore.toJson(p), "t");
        assertTrue(back.layers().get(0).viewport().isFullScreen(),
                "全屏是明确保存的设置，不能用来判断是否缺少层级取景框");
        assertEquals(0.1f, back.layers().get(1).viewport().x0(), 1e-6f);
        assertEquals(0.5f, back.layers().get(1).viewport().y1(), 1e-6f);
    }

    @Test
    void 仅缺少取景框的层继承工程级框() {
        ShaderProject p = new ShaderProject("mixed");
        p.viewport().set(0.25f, 0.2f, 0.75f, 0.8f);
        p.addLayer(new ShaderLayer("旧层", SRC));
        p.addLayer(new ShaderLayer("全屏层", SRC));
        JsonObject json = ProjectStore.toJson(p);
        json.getAsJsonArray("layers").get(0).getAsJsonObject().remove("viewport");

        ShaderProject back = ProjectStore.fromJson(json, "t");
        assertEquals(0.25f, back.layers().get(0).viewport().x0(), 1e-6f);
        assertTrue(back.layers().get(1).viewport().isFullScreen());
    }

    @Test
    void 单层旧格式仍继承工程级框() {
        JsonObject json = new JsonObject();
        json.addProperty("format", 1);
        json.addProperty("source", SRC);
        json.add("viewport", com.google.gson.JsonParser.parseString("[0.2,0.3,0.8,0.9]"));

        ShaderProject back = ProjectStore.fromJson(json, "legacy");
        assertEquals(1, back.layers().size());
        assertEquals(0.2f, back.layers().get(0).viewport().x0(), 1e-6f);
        assertEquals(0.9f, back.layers().get(0).viewport().y1(), 1e-6f);
    }

    @Test
    void 复制一层时框跟着走() {
        ShaderLayer a = new ShaderLayer("A", SRC);
        a.viewport().set(0.1f, 0.2f, 0.6f, 0.7f);
        ShaderLayer c = a.copy("A copy");
        assertEquals(0.1f, c.viewport().x0(), 1e-4f);
        assertEquals(0.7f, c.viewport().y1(), 1e-4f);
    }

    // ---------------------------------------------------------------- 选中

    @Test
    void 可以什么都不选() {
        ShaderProject p = new ShaderProject("t");
        p.addLayer(new ShaderLayer("A", SRC));
        assertNotNull(p.selected());

        // 点画布空白处 = 取消选中。这个状态必须表达得出来，否则那个框永远赖在画面上
        p.setSelectedIndex(-1);
        assertEquals(-1, p.selectedIndex());
        assertNull(p.selected());
    }

    @Test
    void 零层时选中就是没有() {
        ShaderProject p = new ShaderProject("t");
        assertEquals(-1, p.selectedIndex());
        assertNull(p.selected());
    }

    @Test
    void 按对象删层不会删错() {
        ShaderProject p = new ShaderProject("t");
        ShaderLayer a = new ShaderLayer("A", SRC);
        ShaderLayer b = new ShaderLayer("B", SRC);
        ShaderLayer c = new ShaderLayer("C", SRC);
        p.addLayer(a);
        p.addLayer(b);
        p.addLayer(c);

        // 批量删除按对象走：按下标删的话，删掉 A 之后 B、C 就全挪位了
        assertTrue(p.removeLayer(a));
        assertTrue(p.removeLayer(c));
        assertEquals(1, p.layers().size());
        assertEquals("B", p.layers().get(0).name());
        assertTrue(p.selectedIndex() < p.layers().size(), "选中下标不该越界");
    }

    @Test
    void 删光之后选中回到没有() {
        ShaderProject p = new ShaderProject("t");
        ShaderLayer a = new ShaderLayer("A", SRC);
        p.addLayer(a);
        p.removeLayer(a);
        assertEquals(-1, p.selectedIndex());
        assertNull(p.selected());
    }
}
