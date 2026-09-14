package mc.GTedd.cn.gtshaders;

import mc.GTedd.cn.gtshaders.codegen.Samples;
import mc.GTedd.cn.gtshaders.i18n.GtLang;
import mc.GTedd.cn.gtshaders.library.CoreLibrary;
import mc.GTedd.cn.gtshaders.library.EffectLibrary;
import mc.GTedd.cn.gtshaders.library.SourceDoc;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 中英双语与「跟着游戏语言走」。
 *
 * <p>这里守两件只有换了语言才看得见的事：一是中文变体（繁体、港台）不该掉到英文，
 * 二是每个内置效果都真的带着英文说明——少了一份，英文玩家在效果库里看到的就是一段中文。
 */
class I18nTest {

    private static final Pattern CJK = Pattern.compile("[\\u3400-\\u9fff\\u3000-\\u303f\\uff00-\\uffef]");

    private static final String BILINGUAL = """
            // 闪光弹 / Flashbang
            // 致盲的三段观感：瞬间全白 → 白色退去露出画面。
            // 眼睛的恢复不是线性的。
            //
            // [en_us]
            // A flashbang in three stages. Instant white-out, then the picture fades back.
            // Eyes don't recover linearly.
            //
            // @param name=Bleach type=float
            void main() {}
            """;

    @AfterEach
    void resetLanguage() {
        GtLang.setCurrentLang(GtLang.FALLBACK_LANG);
    }

    // ------------------------------------------------------------ 语言回退

    @Test
    void 中文变体界面文案先退到简体而不是英文() {
        GtLang.setCurrentLang("zh_cn");
        String zh = GtLang.get("gtshaders.panel.tab.props");
        GtLang.setCurrentLang("en_us");
        String en = GtLang.get("gtshaders.panel.tab.props");
        assertNotEquals(zh, en);

        for (String variant : new String[]{"zh_tw", "zh_hk", "lzh"}) {
            GtLang.setCurrentLang(variant);
            assertEquals(zh, GtLang.get("gtshaders.panel.tab.props"), variant + " 应当显示简体中文");
            assertEquals(GtLang.CHINESE_LANG, GtLang.contentLang(), variant + " 的内容语言应当是简体");
        }

        GtLang.setCurrentLang("ja_jp");
        assertEquals(en, GtLang.get("gtshaders.panel.tab.props"), "非中文且没有语言包时退到英文");
        assertEquals("ja_jp", GtLang.contentLang());
    }

    @Test
    void 游戏语言代码大小写与连字符都能认() {
        assertTrue(GtLang.isChinese("zh_CN"));
        assertTrue(GtLang.isChinese("zh-TW"));
        assertFalse(GtLang.isChinese("en_us"));
        assertFalse(GtLang.isChinese(null));
    }

    // ------------------------------------------------------------ 源码说明

    @Test
    void 说明按语言挑段落标题取对应那一半() {
        SourceDoc zh = SourceDoc.parse(BILINGUAL, "zh_cn");
        assertEquals("闪光弹", zh.localTitle());
        assertEquals("致盲的三段观感", zh.summary());
        assertEquals(2, zh.detail().size(), "中文段不该混进英文");

        SourceDoc en = SourceDoc.parse(BILINGUAL, "en_us");
        assertEquals("Flashbang", en.localTitle());
        assertEquals("A flashbang in three stages", en.summary());
        assertEquals(2, en.detail().size(), "英文段不该混进中文，也不该带上 @param");

        assertEquals(zh.summary(), SourceDoc.parse(BILINGUAL, "zh_tw").summary(), "繁体读简体那段");
        assertEquals(en.summary(), SourceDoc.parse(BILINGUAL, "ja_jp").summary(), "其他语言读英文段");
        assertEquals("闪光弹 / Flashbang", en.title(), "原始标题保持完整，搜索要能两种语言都命中");
    }

    @Test
    void 没有语言段的第三方源码所有语言共用一份() {
        String plain = """
                // Toon Shading
                // Quantizes lighting into bands. Looks like a cartoon.
                void main() {}
                """;
        SourceDoc zh = SourceDoc.parse(plain, "zh_cn");
        SourceDoc en = SourceDoc.parse(plain, "en_us");
        assertEquals("Quantizes lighting into bands", zh.summary());
        assertEquals(zh.detail(), en.detail());
        assertEquals("Toon Shading", zh.localTitle(), "没有斜杠的标题原样返回");
    }

    @Test
    void 句末句点也算断句() {
        String src = """
                // 测试 / Test
                // 中文。
                //
                // [en_us]
                // A single sentence.
                """;
        assertEquals("A single sentence", SourceDoc.parse(src, "en_us").summary());
    }

    // ------------------------------------------------------------ 内置内容全都带英文

    @Test
    void 每个内置效果都带英文说明() {
        Map<String, String> sources = builtinSources();
        assertTrue(sources.size() > 150, "内置效果数量不对，可能读不到资源：" + sources.size());

        List<String> problems = new ArrayList<>();
        for (Map.Entry<String, String> e : sources.entrySet()) {
            String tag = e.getKey();
            String src = e.getValue();
            SourceDoc zh = SourceDoc.parse(src, "zh_cn");
            SourceDoc en = SourceDoc.parse(src, "en_us");
            if (!zh.title().contains(" / ")) {
                problems.add(tag + "：标题不是「中文 / English」");
            }
            if (en.summary().isBlank()) {
                problems.add(tag + "：缺少英文说明（头部注释里没有 // [en_us] 段）");
                continue;
            }
            if (en.detail().equals(zh.detail())) {
                problems.add(tag + "：英文段没有生效，两种语言拿到的是同一份说明");
            }
            for (String line : en.detail()) {
                if (CJK.matcher(line).find()) {
                    problems.add(tag + "：英文说明里有中文：" + line);
                    break;
                }
            }
            if (CJK.matcher(en.localTitle()).find()) {
                problems.add(tag + "：英文标题里有中文");
            }
            for (String line : src.split("\n")) {
                String t = line.trim();
                if (t.startsWith("// @param") && t.contains("desc_zh_cn=") && !t.contains("desc_en_us=")) {
                    problems.add(tag + "：参数说明只有中文：" + t);
                }
            }
        }
        assertTrue(problems.isEmpty(), "英文玩家会看到中文的地方：\n" + String.join("\n", problems));
    }

    private static Map<String, String> builtinSources() {
        Map<String, String> out = new LinkedHashMap<>();
        for (EffectLibrary.Entry e : EffectLibrary.all()) {
            String src = EffectLibrary.loadSource(e.id());
            assertNotNull(src, e.id());
            out.put("library/" + e.id(), src);
        }
        for (CoreLibrary.Entry e : CoreLibrary.all()) {
            String src = CoreLibrary.loadSource(e.id());
            assertNotNull(src, e.id());
            out.put("corelib/" + e.id(), src);
        }
        for (Samples.Sample s : Samples.all()) {
            out.put("sample/" + s.nameKey(), s.source());
        }
        for (String name : new String[]{"example_post.fsh", "example_warp.fsh"}) {
            out.put("ai/" + name, resource("/assets/gtshaders/ai/" + name));
        }
        return out;
    }

    private static String resource(String path) {
        try (InputStream in = I18nTest.class.getResourceAsStream(path)) {
            assertNotNull(in, path);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new AssertionError(path, e);
        }
    }
}
