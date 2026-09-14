package mc.GTedd.cn.gtshaders;

import org.junit.jupiter.api.Test;
import mc.GTedd.cn.gtshaders.codegen.ParamScanner;
import mc.GTedd.cn.gtshaders.core.ParamType;
import mc.GTedd.cn.gtshaders.core.ShaderParam;
import mc.GTedd.cn.gtshaders.library.EffectCatalog;
import mc.GTedd.cn.gtshaders.library.SourceDoc;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 覆盖效果浏览器背后的目录、源码说明解析，以及参数的名字启发式。
 *
 * <p>这几件事的共同点是<b>坏掉了不会报错</b>：说明解析错了只是详情面板空一块，
 * 启发式猜错了只是滑块范围不合适——都不会抛异常、不会编译失败，只会让第一次用的人
 * 觉得「这东西怪怪的」然后放弃。所以只能靠测试钉住。
 */
class CatalogTest {

    // ---------------------------------------------------------------- 说明解析

    @Test
    void 从头部注释里读出标题与摘要() {
        SourceDoc doc = SourceDoc.parse("""
                // 闪光弹 / Flashbang
                // 致盲的三段观感：瞬间全白 → 白色退去露出画面。
                // 眼睛的恢复不是线性的。
                //
                // @param name=Bleach type=float
                void main() {}
                """, "zh_cn");
        assertEquals("闪光弹 / Flashbang", doc.title());
        assertEquals("闪光弹", doc.localTitle());
        assertEquals("致盲的三段观感", doc.summary(), "摘要应当在第一个冒号处断开");
        assertEquals(2, doc.detail().size(), "@param 之后的内容不该进说明");
    }

    @Test
    void 没有注释的源码不会炸() {
        assertTrue(SourceDoc.parse("void main() { fragColor = vec4(1.0); }").isEmpty());
        assertTrue(SourceDoc.parse("").isEmpty());
        assertTrue(SourceDoc.parse(null).isEmpty());
    }

    @Test
    void 过长的首行当作说明而不是标题() {
        SourceDoc doc = SourceDoc.parse(
                "// 这一行特别特别长，长到不可能是谁给效果起的名字，它其实是说明的开头。\n"
                        + "void main() {}\n");
        assertEquals("", doc.title());
        assertFalse(doc.summary().isEmpty());
    }

    @Test
    void 小数点不会被当成断句() {
        SourceDoc doc = SourceDoc.parse("// T\n// Scale is 0.5 by default and looks fine\n");
        assertEquals("Scale is 0.5 by default and looks fine", doc.summary());
    }

    // ---------------------------------------------------------------- 目录

    @Test
    void 目录覆盖三大来源且每条都有名字() {
        List<EffectCatalog.Section> sections = EffectCatalog.builtin();
        assertFalse(sections.isEmpty());

        boolean post = false;
        boolean core = false;
        boolean template = false;
        for (EffectCatalog.Section s : sections) {
            post |= s.topId().equals(EffectCatalog.TOP_POST);
            core |= s.topId().equals(EffectCatalog.TOP_CORE);
            template |= s.topId().equals(EffectCatalog.TOP_TEMPLATE);
            assertFalse(s.items().isEmpty(), s.id() + " 是个空分节，不该出现在树里");
            for (EffectCatalog.Item it : s.items()) {
                assertFalse(it.name().isBlank(), it.id() + " 没有显示名");
                assertNotNull(it.kind());
            }
        }
        assertTrue(post, "缺少后处理效果");
        assertTrue(core, "缺少核心着色器");
        assertTrue(template, "缺少起手模板");
    }

    @Test
    void 内置后处理效果全都解析出了说明与参数() {
        int checked = 0;
        for (EffectCatalog.Item it : EffectCatalog.allItems()) {
            if (it.kind() != EffectCatalog.Kind.POST) {
                continue;
            }
            checked++;
            // 库里每个效果的头部注释都是按约定写的；哪个漏了，浏览器里就会空一行
            assertFalse(it.doc().title().isBlank(), it.id() + " 的源码缺少「中文名 / English」标题行");
            assertFalse(it.summary().isBlank(), it.id() + " 没解析出一句话说明");
            assertTrue(it.paramCount() > 0, it.id() + " 一个可调参数都没有");
        }
        assertTrue(checked >= 130, "后处理效果至少 130 个，实际 " + checked);
    }

    /**
     * 每个后处理分节都得有足够多的内容。
     *
     * <p>只剩一两条的分节在分类树上白占一行：点进去发现没东西，比不列出来更让人困惑。
     * 加分类时最容易犯的错就是开了个坑只填一个效果。
     */
    @Test
    void 每个后处理分节都不至于只有零星几条() {
        int sections = 0;
        for (EffectCatalog.Section s : EffectCatalog.builtin()) {
            if (!s.topId().equals(EffectCatalog.TOP_POST)) {
                continue;
            }
            sections++;
            assertTrue(s.items().size() >= 5,
                    s.id() + " 只有 " + s.items().size() + " 个效果，撑不起分类树上的一行");
        }
        assertTrue(sections >= 12, "后处理分类至少 12 个，实际 " + sections);
    }

    @Test
    void 起手模板全都有标题与说明() {
        int checked = 0;
        for (EffectCatalog.Item it : EffectCatalog.allItems()) {
            if (it.kind() != EffectCatalog.Kind.TEMPLATE) {
                continue;
            }
            checked++;
            // 模板是给第一次写着色器的人看的，没说明等于没用。这里钉住是因为
            // 加模板时最容易漏的就是顶上那段注释——漏了不会报错，只是库里空一行
            assertFalse(it.doc().title().isBlank(), it.id() + " 的源码缺少标题行");
            assertFalse(it.summary().isBlank(), it.id() + " 没解析出一句话说明");
        }
        assertTrue(checked >= 9, "起手模板至少 9 个，实际 " + checked);
    }

    @Test
    void 核心着色器示例全都有说明() {
        for (EffectCatalog.Item it : EffectCatalog.allItems()) {
            if (it.kind() == EffectCatalog.Kind.CORE_EXAMPLE) {
                assertFalse(it.summary().isBlank(), it.id() + " 没解析出一句话说明");
            }
        }
    }

    @Test
    void 搜索要求全部词命中() {
        List<EffectCatalog.Section> pool = EffectCatalog.builtin();
        List<EffectCatalog.Item> hit = EffectCatalog.search(pool, "黑洞");
        assertFalse(hit.isEmpty(), "搜「黑洞」应当能搜到");

        // 两个词的交集必须比任一单词都小，否则就是退化成了「任意命中」
        int single = EffectCatalog.search(pool, "光").size();
        int both = EffectCatalog.search(pool, "光 天空").size();
        assertTrue(both <= single);
        assertTrue(EffectCatalog.search(pool, "").isEmpty(), "空串不该返回全部");
    }

    // ---------------------------------------------------------------- 选中范围

    @Test
    void 选中一级分组能列出该组全部内容() {
        List<EffectCatalog.Section> pool = EffectCatalog.builtin();
        int post = 0;
        int template = 0;
        for (EffectCatalog.Section s : pool) {
            if (EffectCatalog.inScope(s, EffectCatalog.TOP_POST)) {
                post += s.items().size();
            }
            if (EffectCatalog.inScope(s, EffectCatalog.TOP_TEMPLATE)) {
                template += s.items().size();
            }
        }
        assertTrue(post >= 130, "选中「后处理效果」应当列出全部效果，实际 " + post);
        // 起手模板只有一个同名分节，树上不画子行——只认分节 id 的话这一栏永远打不开
        assertTrue(template >= 9, "选中「起手模板」应当列出全部模板，实际 " + template);
    }

    @Test
    void 每个一级分组都选得中() {
        for (EffectCatalog.Section s : EffectCatalog.builtin()) {
            assertTrue(EffectCatalog.inScope(s, s.topId()), s.id() + " 的一级分组选不中");
            assertTrue(EffectCatalog.inScope(s, s.id()), s.id() + " 自身选不中");
            assertTrue(EffectCatalog.inScope(s, ""), "空串应当表示全部");
        }
    }

    // ---------------------------------------------------------------- 参数启发式

    private static ShaderParam scanOne(String source) {
        List<ShaderParam> ps = ParamScanner.scan(source).params();
        assertEquals(1, ps.size(), "应当只扫出一个参数");
        return ps.get(0);
    }

    @Test
    void 角度参数拿到度数范围而不是零到一() {
        ShaderParam p = scanOne("uniform float Angle;\n");
        assertEquals(0f, p.min());
        assertEquals(360f, p.max(), "0..1 的角度滑块拖满也看不出变化");
        assertTrue(p.isInferred());
    }

    @Test
    void 采样次数是整数控件() {
        assertEquals(ParamType.INT, scanOne("uniform float SampleCount;\n").type());
        assertEquals(ParamType.INT, scanOne("uniform int Steps;\n").type());
    }

    @Test
    void 名字像开关的浮点提升成勾选框() {
        ShaderParam p = scanOne("uniform float UseProbe;\n");
        assertEquals(ParamType.BOOL, p.type());
        // GLSL 里仍然是 float，作者写的 UseProbe > 0.5 不能因此编译不过
        assertEquals("float", p.type().glslType());
    }

    @Test
    void 三分量默认当颜色但方向除外() {
        assertEquals(ParamType.COLOR3, scanOne("uniform vec3 GlowTint;\n").type());
        ShaderParam dir = scanOne("uniform vec3 LightDir;\n");
        assertEquals(ParamType.VEC3, dir.type());
        assertEquals(-1f, dir.min(), "方向要能取负");
    }

    @Test
    void 猜不出来时退回原先的保守范围() {
        ShaderParam p = scanOne("uniform float Wobbliness;\n");
        assertEquals(ParamType.FLOAT, p.type());
        assertEquals(0f, p.min());
        assertEquals(1f, p.max());
    }

    @Test
    void 显式注解压过启发式并且不标记为推断() {
        ShaderParam p = scanOne("// @param name=Angle type=float min=0 max=1 default=0.25\n");
        assertEquals(1f, p.max(), "作者写死的范围不能被启发式改掉");
        assertFalse(p.isInferred());
    }

    // ---------------------------------------------------------------- 分组与说明

    @Test
    void group注解按位置生效并在下一个group处切换() {
        List<ShaderParam> ps = ParamScanner.scan("""
                // @group 外观 / Look
                // @param name=Tint type=color3
                // @param name=Bleach type=float min=0 max=1
                // @group zh_cn=时间 en_us=Timing
                // @param name=Speed type=float min=0 max=5
                """).params();
        assertEquals(3, ps.size());
        assertEquals("Look", ps.get(0).groupKey());
        assertEquals("Look", ps.get(1).groupKey(), "组要一直延续到下一个 @group");
        assertEquals("Timing", ps.get(2).groupKey());
        assertEquals("外观", ps.get(0).resolveGroup("zh_cn"));
        assertEquals("时间", ps.get(2).resolveGroup("zh_cn"));
    }

    @Test
    void 空的group注解退回不分组() {
        List<ShaderParam> ps = ParamScanner.scan("""
                // @group 外观
                // @param name=A type=float
                // @group
                // @param name=B type=float
                """).params();
        assertEquals("外观", ps.get(0).groupKey());
        assertEquals("", ps.get(1).groupKey());
    }

    @Test
    void 裸uniform同样继承位置分组() {
        List<ShaderParam> ps = ParamScanner.scan("""
                // @group zh_cn=时间 en_us=Timing
                uniform float Speed;
                """).params();
        assertEquals("Timing", ps.get(0).groupKey());
    }

    @Test
    void 说明支持通用与分语言两种写法() {
        ShaderParam a = scanOne("// @param name=X type=float desc=通用说明\n");
        assertEquals("通用说明", a.resolveDesc("zh_cn"));

        ShaderParam b = scanOne(
                "// @param name=X type=float desc_zh_cn=中文说明 desc_en_us=English note\n");
        assertEquals("中文说明", b.resolveDesc("zh_cn"));
        assertEquals("English note", b.resolveDesc("en_us"));
    }

    @Test
    void 说明与分组不会被误当成显示名() {
        ShaderParam p = scanOne(
                "// @param name=X type=float zh_cn=强度 desc_zh_cn=说明 group_en_us=Look\n");
        assertEquals("强度", p.resolveLabel("zh_cn", k -> null),
                "desc_/group_ 前缀的 key 不能混进显示名");
        assertEquals("说明", p.resolveDesc("zh_cn"));
        assertEquals("Look", p.groupKey());
    }

    @Test
    void 复制参数时说明与分组一起带走() {
        ShaderParam p = scanOne("// @param name=X type=float desc=说明 group=Look\n");
        ShaderParam c = p.copy();
        assertEquals("说明", c.resolveDesc("zh_cn"));
        assertEquals("Look", c.groupKey());
    }
}
