package mc.GTedd.cn.gtshaders.library;

import org.jspecify.annotations.Nullable;
import mc.GTedd.cn.gtshaders.codegen.ParamScanner;
import mc.GTedd.cn.gtshaders.codegen.Samples;
import mc.GTedd.cn.gtshaders.core.ShaderKind;
import mc.GTedd.cn.gtshaders.i18n.GtLang;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 统一效果目录：把散在四处的可用内容合并成一棵可浏览、可搜索的树。
 *
 * <h2>为什么需要它</h2>
 *
 * <p>现在能用的东西有一百二十多样，来源却有四个：{@link EffectLibrary} 的后处理效果、
 * {@link CoreLibrary} 的核心示例、{@link ShaderKind} 的核心种类空白模板、{@link Samples}
 * 的起手模板。它们各有各的索引、各有各的取名方式，界面每加一处入口就要把这四套都摊一遍。
 *
 * <p>更要命的是对初次接触的人：一百二十条纯文字菜单项，每条只有一个名字，
 * 既不知道「代码雨」和「扫描线」差在哪，也不知道点下去会发生什么。目录把
 * <b>名字、一句话说明、参数个数、所属类型</b>凑齐，浏览器才有东西可展示。
 *
 * <h2>说明从哪来</h2>
 *
 * <p>{@link SourceDoc} 直接读源码头部注释——不额外维护一份描述，见那个类的说明。
 *
 * <h2>原版效果为什么不在这里</h2>
 *
 * <p>扫描原版 post effect 要 {@code Minecraft} 实例，把它拉进来会让整个目录在单元测试里
 * 用不了。原版那一节由界面侧自己构造后接在末尾，本类只管「随 jar 一起发布、离线就能列出来」
 * 的那部分。
 */
public final class EffectCatalog {

    /** 一级分组 id。界面按这个顺序画分类树。 */
    public static final String TOP_POST = "post";
    public static final String TOP_CORE = "core";
    public static final String TOP_TEMPLATE = "template";
    public static final String TOP_VANILLA = "vanilla";

    public enum Kind {
        /** 内置后处理效果，加进来就是一个可编辑的效果层。 */
        POST,
        /** 核心着色器示例，钩子已经写好。 */
        CORE_EXAMPLE,
        /** 核心着色器空白模板，只有两个空钩子。 */
        CORE_BLANK,
        /** 起手模板：教怎么写的最小范本。 */
        TEMPLATE,
        /** 当前资源包里的原版 post effect。 */
        VANILLA
    }

    /**
     * 目录里的一条。
     *
     * @param kind       来源类型，决定「应用」时走哪条路径
     * @param id         该来源内的唯一 id
     * @param sectionId  所属分节
     * @param name       显示名
     * @param doc        源码头部注释解析出的标题与说明
     * @param paramCount 可调参数个数，0 表示这个效果没有参数
     * @param badge      列表右侧的小标签，没有就是空串
     * @param payload    原始条目，交给界面侧决定怎么用
     */
    public record Item(Kind kind, String id, String sectionId, String name,
                       SourceDoc doc, int paramCount, String badge, @Nullable Object payload) {

        /** 列表里显示的一句话说明；源码没写注释时退回空串，界面自己决定怎么占位。 */
        public String summary() {
            return doc.summary();
        }

        /** 搜索用的匹配串：名字、id、说明、标签全都能搜到。 */
        String haystack() {
            return (name + " " + id + " " + doc.title() + " " + doc.summary() + " " + badge)
                    .toLowerCase(Locale.ROOT);
        }
    }

    /**
     * 分类树的一节。
     *
     * @param topId 一级分组
     * @param id    分节 id，同一 topId 下唯一
     */
    public record Section(String topId, String id, String name, List<Item> items) {
    }

    private static List<Section> sections;
    private static String sectionsLang = "";

    private EffectCatalog() {
    }

    /** 随 jar 发布的那部分目录：后处理效果、核心着色器、起手模板。 */
    public static synchronized List<Section> builtin() {
        // 名字和说明在建目录时就按语言取好了，所以缓存也得按语言分：
        // 玩家在游戏设置里换了语言，下次打开效果库就该是新语言，而不是停在第一次打开时那门
        String lang = GtLang.currentLang();
        if (sections == null || !lang.equals(sectionsLang)) {
            sections = List.copyOf(build());
            sectionsLang = lang;
        }
        return sections;
    }

    /** 一级分组的显示名。 */
    public static String topName(String topId) {
        return GtLang.get("gtshaders.catalog.top." + topId);
    }

    /**
     * 分节是否落在当前选中的范围里。
     *
     * <p>选中项可以是<b>一个分节</b>，也可以是<b>整个一级分组</b>。后者是必需的：
     * 「起手模板」和「原版效果」各只有一个同名分节，树上不会再画子行，
     * 如果只认分节 id，那两栏就永远选不中——点了没反应。
     *
     * @param sectionId 空串表示「全部」
     */
    public static boolean inScope(Section s, String sectionId) {
        return sectionId.isEmpty() || s.id().equals(sectionId) || s.topId().equals(sectionId);
    }

    /** 全部内置条目，不分节。 */
    public static List<Item> allItems() {
        List<Item> out = new ArrayList<>();
        for (Section s : builtin()) {
            out.addAll(s.items());
        }
        return out;
    }

    /**
     * 跨类型模糊搜索。
     *
     * <p>按空格拆词后要求<b>全部命中</b>而不是任意命中：搜「核心 天空」时想要的是两个条件的交集，
     * 而任意命中会把所有核心着色器和所有天空效果一股脑倒出来，等于没搜。
     */
    public static List<Item> search(List<Section> pool, String query) {
        String q = query.trim().toLowerCase(Locale.ROOT);
        List<Item> out = new ArrayList<>();
        if (q.isEmpty()) {
            return out;
        }
        String[] terms = q.split("\\s+");
        for (Section s : pool) {
            for (Item it : s.items()) {
                String hay = it.haystack() + " " + s.name().toLowerCase(Locale.ROOT);
                boolean all = true;
                for (String t : terms) {
                    if (!hay.contains(t)) {
                        all = false;
                        break;
                    }
                }
                if (all) {
                    out.add(it);
                }
            }
        }
        return out;
    }

    private static List<Section> build() {
        List<Section> out = new ArrayList<>();

        // ---- 后处理效果：分节直接沿用库的分类 ----
        for (String cat : EffectLibrary.categories()) {
            List<Item> items = new ArrayList<>();
            for (EffectLibrary.Entry e : EffectLibrary.byCategory(cat)) {
                String src = EffectLibrary.loadSource(e.id());
                items.add(new Item(Kind.POST, e.id(), TOP_POST + "/" + cat,
                        e.displayName(), SourceDoc.parse(src), countParams(src),
                        badgeFor(src), e));
            }
            if (!items.isEmpty()) {
                out.add(new Section(TOP_POST, TOP_POST + "/" + cat,
                        EffectLibrary.categoryDisplayName(cat), List.copyOf(items)));
            }
        }

        // ---- 核心着色器：一节 = 一个种类分类，节内先列空白模板再列该种类的示例 ----
        // 这个顺序是刻意的：空白模板是"我要自己写"，示例是"我要现成的"。初次接触的人
        // 几乎总是先要现成的，但空白模板必须紧跟在种类名后面，否则找不到「这个种类怎么起手」。
        Map<String, List<CoreLibrary.Entry>> examplesByKind = new LinkedHashMap<>();
        for (CoreLibrary.Entry e : CoreLibrary.all()) {
            examplesByKind.computeIfAbsent(e.kindId(), k -> new ArrayList<>()).add(e);
        }
        for (String cat : ShaderKind.categories()) {
            List<Item> items = new ArrayList<>();
            for (ShaderKind.Entry k : ShaderKind.byCategory(cat)) {
                String sectionId = TOP_CORE + "/" + cat;
                items.add(new Item(Kind.CORE_BLANK, k.id(), sectionId,
                        k.displayName(), blankDoc(k), 0,
                        GtLang.get("gtshaders.catalog.badge.blank"), k));
                for (CoreLibrary.Entry ex : examplesByKind.getOrDefault(k.id(), List.of())) {
                    String src = CoreLibrary.loadSource(ex.id());
                    items.add(new Item(Kind.CORE_EXAMPLE, ex.id(), sectionId,
                            ex.displayName(), SourceDoc.parse(src), countParams(src),
                            k.displayName(), ex));
                }
            }
            if (!items.isEmpty()) {
                out.add(new Section(TOP_CORE, TOP_CORE + "/" + cat,
                        ShaderKind.categoryDisplayName(cat), List.copyOf(items)));
            }
        }

        // ---- 起手模板 ----
        List<Item> templates = new ArrayList<>();
        for (Samples.Sample s : Samples.all()) {
            templates.add(new Item(Kind.TEMPLATE, s.nameKey(), TOP_TEMPLATE,
                    GtLang.get(s.nameKey()), SourceDoc.parse(s.source()),
                    countParams(s.source()), "", s));
        }
        if (!templates.isEmpty()) {
            out.add(new Section(TOP_TEMPLATE, TOP_TEMPLATE, topName(TOP_TEMPLATE),
                    List.copyOf(templates)));
        }

        return out;
    }

    /** 空白模板没有源码可读，说明取种类自己的一句话注解。 */
    private static SourceDoc blankDoc(ShaderKind.Entry k) {
        String note = k.displayNote();
        return note.isEmpty() ? SourceDoc.EMPTY
                : new SourceDoc(k.displayName(), note, List.of(note));
    }

    /**
     * 给后处理效果打标签：锚点 / 可换图 / 动态 / 静态。
     *
     * <p>锚点标在最前：对「世界某一位置播放效果」这类需求，它是作者挑效果时
     * 第一眼要看的能力——能不能长在实体/坐标上，而不是铺满全屏。
     */
    private static String badgeFor(@Nullable String source) {
        if (source == null || source.isBlank()) {
            return "";
        }
        List<String> tags = new ArrayList<>();
        if (source.contains("gtAnchor")) {
            tags.add(GtLang.get("gtshaders.catalog.badge.anchor"));
        }
        if (source.contains("@texture")) {
            tags.add(GtLang.get("gtshaders.catalog.badge.texture"));
        }
        boolean dynamic = source.contains("GTTime") || source.contains("iTime")
                || source.contains("gtAnchorLife");
        tags.add(dynamic
                ? GtLang.get("gtshaders.catalog.badge.dynamic")
                : GtLang.get("gtshaders.catalog.badge.static"));
        return String.join(" · ", tags);
    }

    private static int countParams(@Nullable String source) {
        if (source == null || source.isBlank()) {
            return 0;
        }
        try {
            return ParamScanner.scan(source).params().size();
        } catch (RuntimeException e) {
            // 目录只是用来展示的，一个效果解析不了不该让整个浏览器打不开
            return 0;
        }
    }
}
