package mc.GTedd.cn.gtshaders.ui;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import org.jspecify.annotations.Nullable;
import mc.GTedd.cn.gtshaders.i18n.GtLang;
import mc.GTedd.cn.gtshaders.library.EffectCatalog;
import mc.GTedd.cn.gtshaders.library.SourceDoc;
import mc.GTedd.cn.gtshaders.runtime.VanillaEffects;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 效果库浏览器：一百二十多样内容的统一入口。
 *
 * <h2>为什么不继续用下拉菜单</h2>
 *
 * <p>原来这些东西全塞在顶栏那个 236px 宽的下拉里，每条只有一个名字。对已经知道自己要什么的人
 * 够用，对第一次打开的人几乎是不可用的：既不知道「代码雨」和「扫描线」差在哪，也不知道
 * 点下去会发生什么、会不会把手上的东西弄没。
 *
 * <p>所以换成三列：<b>左边分类、中间条目带一句话说明、右边详情加一个明确的按钮</b>。
 * 关键是中间那句说明和右边的详情——它们把「点之前先看看」这件事变成可能，
 * 而下拉菜单里除了点下去别无他法。
 *
 * <h2>为什么点条目不直接应用</h2>
 *
 * <p>应用会改动当前工程（加一层，或者整个替换）。对熟手这是想要的快捷，对新手是「我只是想看看
 * 结果东西没了」。所以单击只选中、右侧出详情，真正动手要按右下角那个按钮，或者双击——
 * 双击是给已经熟了的人留的快路。
 *
 * <h2>为什么独立成类</h2>
 *
 * <p>{@link EditorScreen} 已经近三千行。浏览器有自己的选中态、滚动、搜索和三列布局，
 * 塞进去只会让两边都更难改。它借用 EditorScreen 的 region 登记与提示气泡（同包可见），
 * 但状态完全是自己的。
 */
final class EffectBrowser {

    private static final int MAX_W = 760;
    private static final int MAX_H = 470;
    private static final int HEAD_H = 40;
    private static final int TREE_W = 156;
    private static final int DETAIL_W = 208;
    private static final int ROW_H = 34;
    private static final int TREE_ROW_H = 20;
    /** 中列再窄就放不下「名字 + 一行说明」了，宁可砍掉右边的详情列。 */
    private static final int MIN_LIST_W = 150;

    private final EditorScreen screen;
    private final TextField searchField = new TextField(TextField.Kind.TEXT);

    private boolean open;
    /** 当前选中的分节 id；空串表示「全部」。 */
    private String sectionId = "";
    private EffectCatalog.@Nullable Item selected;
    private int listScroll;
    private int detailScroll;
    private int listContentH;
    /**
     * 分类树自己的滚动。
     *
     * <p>加到十几个分类之后，树的总高度已经超过浮层——不滚的话最下面那几栏会被 scissor
     * 直接裁掉，界面上<b>看不见也点不到</b>，和「这个分类不存在」没有区别。
     */
    private int treeScroll;
    private int treeContentH;

    /** 一级分组的展开状态。默认全展开——收起来等于把内容又藏了一次。 */
    private final Map<String, Boolean> topExpanded = new LinkedHashMap<>();

    /** 打开时快照一次的完整目录（含原版效果那一节）。 */
    private List<EffectCatalog.Section> pool = List.of();

    /** 上一次点中的条目与时刻，用来判双击。 */
    private EffectCatalog.@Nullable Item lastClicked;
    private long lastClickAt;

    EffectBrowser(EditorScreen screen) {
        // 这里<b>不能</b>调 screen.registerField()：本对象是 EditorScreen 的字段初始化器建的，
        // 那一刻 EditorScreen 后面声明的 allFields 还是 null，直接 NPE——而且是打开编辑器
        // 就崩，编译和单元测试都看不见。注册推迟到 openNow()，反正搜索框在浏览器没开时
        // 也不需要参与全局焦点管理。
        this.screen = screen;
    }

    boolean isOpen() {
        return open;
    }

    void openNow() {
        open = true;
        listScroll = 0;
        detailScroll = 0;
        treeScroll = 0;
        screen.registerField(searchField);
        searchField.blur();
        searchField.setTextIfUnfocused("");
        // 扫描原版效果要读资源包，只在打开时做一次——每帧扫一遍等于每帧一次磁盘 I/O
        pool = withVanilla(EffectCatalog.builtin());
        if (selected == null) {
            selectFirst();
        }
    }

    void close() {
        open = false;
        searchField.blur();
    }

    /**
     * 原版效果那一节由这里拼上去：{@link EffectCatalog} 刻意不碰 {@code Minecraft}，
     * 否则整个目录在单元测试里就用不了了。
     */
    private List<EffectCatalog.Section> withVanilla(List<EffectCatalog.Section> builtin) {
        List<EffectCatalog.Section> out = new ArrayList<>(builtin);
        List<EffectCatalog.Item> items = new ArrayList<>();
        for (VanillaEffects.Entry e : VanillaEffects.scan()) {
            String badge = e.importable()
                    ? GtLang.get("gtshaders.catalog.badge.importable")
                    : GtLang.get("gtshaders.catalog.badge.preview_only");
            String note = e.applicable()
                    ? GtLang.get("gtshaders.catalog.vanilla_note", e.editablePasses())
                    : String.valueOf(e.reason());
            items.add(new EffectCatalog.Item(EffectCatalog.Kind.VANILLA, e.id().toString(),
                    EffectCatalog.TOP_VANILLA, e.label(),
                    new SourceDoc(e.label(), note, List.of(note)), 0, badge, e));
        }
        if (!items.isEmpty()) {
            out.add(new EffectCatalog.Section(EffectCatalog.TOP_VANILLA, EffectCatalog.TOP_VANILLA,
                    EffectCatalog.topName(EffectCatalog.TOP_VANILLA), List.copyOf(items)));
        }
        return List.copyOf(out);
    }

    private void selectFirst() {
        List<EffectCatalog.Item> items = visibleItems();
        selected = items.isEmpty() ? null : items.get(0);
    }

    // ---------------------------------------------------------------- 渲染

    /**
     * 浮层的位置与三列宽度：{@code {x, y, w, h, treeW, detailW}}。
     *
     * <p>三列不能写死。{@code lw} 是<b>逻辑</b>宽度，等于屏幕宽除以编辑器缩放——
     * 小窗口配上 1.6x 缩放，它会掉到 360 上下，按固定 156+208 分下去中间那列直接是负数，
     * 于是 scissor 收到一个反向矩形。所以按比例分，每列各有下限。
     *
     * <p>挤不下时先砍详情列：说明在中间每一行都有一句摘要，而分类树和列表少任何一个
     * 这个浮层就没法用了。砍到 0 就整列不画。
     */
    private int[] metrics(int lw, int lh) {
        int w = Math.max(240, Math.min(MAX_W, lw - 40));
        int h = Math.max(160, Math.min(MAX_H, lh - 40));
        int x = (lw - w) / 2;
        int y = (lh - h) / 2;

        int treeW = Math.max(92, Math.min(TREE_W, w * 22 / 100));
        int detailW = Math.min(DETAIL_W, w * 30 / 100);
        if (w - treeW - detailW < MIN_LIST_W) {
            detailW = Math.max(0, w - treeW - MIN_LIST_W);
        }
        if (detailW < 96) {
            detailW = 0;
        }
        return new int[]{x, y, w, h, treeW, detailW};
    }

    void render(GuiGraphicsExtractor g, int lw, int lh, int mx, int my) {
        int[] m = metrics(lw, lh);
        int x = m[0];
        int y = m[1];
        int w = m[2];
        int h = m[3];
        int treeW = m[4];
        int detailW = m[5];

        // 压暗背景，同时吃掉浮层之外的点击。先登记所以永远被本体压住
        g.fill(0, 0, lw, lh, 0x66000000);
        screen.addRegion(0, 0, lw, lh, this::close, null);

        UiUtil.dropShadow(g, x, y, w, h, 6);
        UiUtil.roundRect(g, x, y, w, h, 8, Theme.PANEL);
        UiUtil.box(g, x, y, w, h, Theme.BORDER_STRONG);

        renderHeader(g, x, y, w, mx, my);

        int bodyTop = y + HEAD_H;
        int bodyBottom = y + h;
        int listX = x + treeW;
        int listW = w - treeW - detailW;

        if (detailW > 0) {
            UiUtil.vLine(g, listX, bodyTop, bodyBottom - 1, Theme.BORDER);
            renderTree(g, x, bodyTop, treeW, bodyBottom, mx, my);
            renderList(g, listX, bodyTop, listW, bodyBottom, mx, my);
            int detailX = x + w - detailW;
            UiUtil.vLine(g, detailX, bodyTop, bodyBottom - 1, Theme.BORDER);
            renderDetail(g, detailX, bodyTop, detailW, bodyBottom, mx, my);
            return;
        }

        // 详情列被挤掉了。那个「添加到工程」按钮不能跟着消失——它是这个浮层唯一
        // 明确的落地动作，只剩双击的话等于把功能藏起来了。改成横在底部。
        int barH = 34;
        int listBottom = bodyBottom - barH;
        UiUtil.vLine(g, listX, bodyTop, listBottom - 1, Theme.BORDER);
        renderTree(g, x, bodyTop, treeW, listBottom, mx, my);
        renderList(g, listX, bodyTop, listW, listBottom, mx, my);

        UiUtil.hLine(g, x + 1, x + w - 1, listBottom, Theme.BORDER);
        EffectCatalog.Item it = selected;
        if (it != null) {
            primaryButton(g, x + 10, listBottom + 5, w - 20, barH - 11,
                    it.kind() == EffectCatalog.Kind.VANILLA
                            ? GtLang.get("gtshaders.browser.preview")
                            : GtLang.get("gtshaders.browser.add"),
                    () -> apply(it), mx, my);
        }
    }

    private void renderHeader(GuiGraphicsExtractor g, int x, int y, int w, int mx, int my) {
        UiUtil.textLeft(g, screen.font(), GtLang.get("gtshaders.browser.title"), x + 14, y + 15,
                Theme.TEXT);
        int titleW = UiText.width(GtLang.get("gtshaders.browser.title"));

        int searchW = Math.min(280, w - titleW - 120);
        int sx = x + w - searchW - 42;
        screen.searchBox(g, searchField, sx, y + 9, searchW, 22,
                GtLang.get("gtshaders.browser.search"), mx, my);

        int cx = x + w - 30;
        boolean hot = screen.hovered(mx, my, cx, y + 9, 22, 22);
        if (hot) {
            UiUtil.roundRect(g, cx, y + 9, 22, 22, 4, Theme.BUTTON_HOVER);
        }
        Icons.close(g, cx + 4, y + 13, 14, Theme.TEXT_SUB);
        screen.addRegion(cx, y + 9, 22, 22, this::close, null);

        UiUtil.hLine(g, x + 1, x + w - 1, y + HEAD_H - 1, Theme.BORDER);
    }

    /**
     * 左列分类树：一级分组 + 分节，每行右侧带条数。
     *
     * <p>条数不是装饰——它是新手判断「这一栏值不值得点开」的唯一线索。
     */
    private void renderTree(GuiGraphicsExtractor g, int x, int top, int w, int bottom,
                            int mx, int my) {
        int viewH = bottom - top - 8;
        treeScroll = Math.max(0, Math.min(treeScroll, Math.max(0, treeContentH - viewH)));

        g.enableScissor(x + 1, top, x + w, bottom - 1);
        screen.clipRegions(x + 1, top, x + w, bottom - 1);
        int start = top + 6 - treeScroll;
        int y = start;

        y = treeRow(g, x, y, w, GtLang.get("gtshaders.browser.all"), totalCount(), 0,
                "".equals(sectionId), () -> {
                    sectionId = "";
                    listScroll = 0;
                }, mx, my, null);

        String lastTop = null;
        for (EffectCatalog.Section s : pool) {
            if (!s.topId().equals(lastTop)) {
                lastTop = s.topId();
                final String topId = lastTop;
                boolean expanded = topExpanded.getOrDefault(topId, Boolean.TRUE);
                boolean onlySection = countSectionsOfTop(topId) <= 1;
                boolean active = topId.equals(sectionId);
                y += 4;
                // 只有一个分节的组（起手模板、原版效果）下面不会再画子行，
                // 所以那个三角画出来只会骗人——没有东西可以展开
                y = treeRow(g, x, y, w, EffectCatalog.topName(topId),
                        countOfTop(topId), 0, active,
                        () -> selectTop(topId, expanded, onlySection), mx, my,
                        onlySection ? null : expanded);
            }
            if (!topExpanded.getOrDefault(s.topId(), Boolean.TRUE)) {
                continue;
            }
            // 一级分组只有一个同名分节时（起手模板、原版效果）不再重复画一行
            if (s.id().equals(s.topId())) {
                continue;
            }
            final String id = s.id();
            y = treeRow(g, x, y, w, s.name(), s.items().size(), 12, id.equals(sectionId), () -> {
                sectionId = id;
                listScroll = 0;
            }, mx, my, null);
        }
        g.disableScissor();
        screen.unclipRegions();

        // 内容高度只能这样量出来：行高不是常数（一级分组前面有 4px 间距，收起来的组不画子行）
        treeContentH = y - start + 8;
        if (treeContentH > viewH) {
            int barH = Math.max(20, viewH * viewH / treeContentH);
            int barY = top + 4 + (viewH - barH) * treeScroll / Math.max(1, treeContentH - viewH);
            g.fill(x + w - 4, barY, x + w - 1, barY + barH, Theme.SCROLL_THUMB);
        }
    }

    /**
     * 点一级分组行。
     *
     * <p>原来这一行只切折叠状态，从不动 {@code sectionId}。对「后处理效果」还看得出反应
     * （子分类收起来了），但<b>「起手模板」和「原版效果」只有一个同名分节、下面根本不画子行</b>
     * ——于是点它们什么都不会发生，那两栏等于打不开。
     *
     * <p>现在一级分组本身就是一个可选的范围：点它，中列列出这一组的全部内容。已经选中时
     * 再点才折叠子分类；只有一个分节的组没有可折叠的东西，就一直保持选中。
     */
    private void selectTop(String topId, boolean expanded, boolean onlySection) {
        if (topId.equals(sectionId) && !onlySection) {
            topExpanded.put(topId, !expanded);
            return;
        }
        sectionId = topId;
        listScroll = 0;
        topExpanded.put(topId, Boolean.TRUE);
    }

    private int countSectionsOfTop(String topId) {
        int n = 0;
        for (EffectCatalog.Section s : pool) {
            if (s.topId().equals(topId)) {
                n++;
            }
        }
        return n;
    }

    /**
     * @param caret 折叠状态：null 表示这一行没有可展开的东西，不画三角
     */
    private int treeRow(GuiGraphicsExtractor g, int x, int y, int w, String label, int count,
                        int indent, boolean active, Runnable click, int mx, int my,
                        @Nullable Boolean caret) {
        int rx = x + 6;
        int rw = w - 12;
        boolean hot = screen.hovered(mx, my, rx, y, rw, TREE_ROW_H);
        if (active) {
            UiUtil.roundRect(g, rx, y, rw, TREE_ROW_H, 4, Theme.ACCENT_SOFT);
        } else if (hot) {
            UiUtil.roundRect(g, rx, y, rw, TREE_ROW_H, 4, Theme.BUTTON_HOVER);
        }
        String countText = count > 0 ? String.valueOf(count) : "";
        int countW = countText.isEmpty() ? 0 : UiText.width(countText) + 8;
        int ty = y + (TREE_ROW_H - screen.font().lineHeight) / 2 + 1;
        int textX = rx + 6 + indent;
        if (caret != null) {
            Icons.caret(g, textX - 1, y + (TREE_ROW_H - 10) / 2, 10,
                    active ? Theme.ACCENT : Theme.TEXT_DIM, caret);
            textX += 11;
        }
        UiUtil.textLeft(g, screen.font(),
                UiUtil.ellipsize(screen.font(), label, rw - 12 - indent - countW
                        - (caret != null ? 11 : 0)),
                textX, ty, active ? Theme.ACCENT : Theme.TEXT);
        if (!countText.isEmpty()) {
            UiUtil.textRight(g, screen.font(), countText, rx + rw - 6, ty, Theme.TEXT_DIM);
        }
        screen.addRegion(rx, y, rw, TREE_ROW_H, click, null);
        return y + TREE_ROW_H;
    }

    /** 中列：条目行 = 名字 + 一句话说明 + 参数个数。 */
    private void renderList(GuiGraphicsExtractor g, int x, int top, int w, int bottom,
                            int mx, int my) {
        List<EffectCatalog.Item> items = visibleItems();
        int viewH = bottom - top - 8;
        listScroll = Math.max(0, Math.min(listScroll, Math.max(0, listContentH - viewH)));

        g.enableScissor(x + 1, top, x + w, bottom - 1);
        screen.clipRegions(x + 1, top, x + w, bottom - 1);
        int y = top + 4 - listScroll;

        if (items.isEmpty()) {
            UiUtil.textLeft(g, screen.font(), GtLang.get("gtshaders.browser.empty"),
                    x + 12, top + 14, Theme.TEXT_DIM);
        }
        for (EffectCatalog.Item it : items) {
            // 滚出视野的行连 region 都不登记：一百多条全登记会让命中测试白跑
            if (y + ROW_H >= top && y <= bottom) {
                itemRow(g, it, x, y, w, mx, my);
            }
            y += ROW_H;
        }
        g.disableScissor();
        screen.unclipRegions();
        listContentH = items.size() * ROW_H + 8;

        if (listContentH > viewH) {
            int barH = Math.max(20, viewH * viewH / listContentH);
            int barY = top + 4 + (viewH - barH) * listScroll / Math.max(1, listContentH - viewH);
            g.fill(x + w - 4, barY, x + w - 1, barY + barH, Theme.SCROLL_THUMB);
        }
    }

    private void itemRow(GuiGraphicsExtractor g, EffectCatalog.Item it, int x, int y, int w,
                         int mx, int my) {
        int rx = x + 6;
        int rw = w - 14;
        boolean active = it == selected;
        boolean hot = screen.hovered(mx, my, rx, y, rw, ROW_H - 2);
        if (active) {
            UiUtil.roundRect(g, rx, y, rw, ROW_H - 2, 4, Theme.ACCENT_SOFT);
        } else if (hot) {
            UiUtil.roundRect(g, rx, y, rw, ROW_H - 2, 4, Theme.BUTTON_HOVER);
        }

        String meta = it.paramCount() > 0
                ? GtLang.get("gtshaders.browser.param_count", it.paramCount()) : "";
        int metaW = meta.isEmpty() ? 0 : UiText.width(meta) + 10;
        UiUtil.textLeft(g, screen.font(),
                UiUtil.ellipsize(screen.font(), it.name(), rw - 16 - metaW),
                rx + 8, y + 4, active ? Theme.ACCENT : Theme.TEXT);
        if (!meta.isEmpty()) {
            UiUtil.textRight(g, screen.font(), meta, rx + rw - 8, y + 4, Theme.TEXT_DIM);
        }
        // 第二行：标识（锚点/可换图/动态）用强调色先画，说明跟在后面。
        // 以前是「有说明就只看说明」，锚点标识只在详情页露脸——对「这效果能不能长在
        // 世界里某个位置」这种第一眼问题反而看不见。改成标识优先、说明随后的布局。
        String badge = it.badge();
        String sub = it.summary();
        int maxW = rw - 16;
        if (!badge.isEmpty()) {
            int badgeW = Math.min(maxW, UiText.width(badge));
            UiUtil.textLeft(g, screen.font(), badge, rx + 8, y + 16, Theme.ACCENT);
            if (!sub.isEmpty()) {
                int restW = maxW - badgeW - 4;
                if (restW > 12) {
                    UiUtil.textLeft(g, screen.font(),
                            UiUtil.ellipsize(screen.font(), " · " + sub, restW),
                            rx + 8 + badgeW, y + 16, Theme.TEXT_DIM);
                }
            }
        } else if (!sub.isEmpty()) {
            UiUtil.textLeft(g, screen.font(), UiUtil.ellipsize(screen.font(), sub, maxW),
                    rx + 8, y + 16, Theme.TEXT_DIM);
        }

        screen.addRegion(rx, y, rw, ROW_H - 2, () -> onItemClicked(it), null);
    }

    /** 单击选中、双击直接应用——两个速度都留一条路。 */
    private void onItemClicked(EffectCatalog.Item it) {
        long now = System.currentTimeMillis();
        boolean doubleClick = it == lastClicked && now - lastClickAt < 350L;
        lastClicked = it;
        lastClickAt = now;
        selected = it;
        detailScroll = 0;
        if (doubleClick) {
            apply(it);
        }
    }

    /** 右列：完整说明 + 类型 + 一个明确的按钮。 */
    private void renderDetail(GuiGraphicsExtractor g, int x, int top, int w, int bottom,
                              int mx, int my) {
        EffectCatalog.Item it = selected;
        int btnH = 26;
        int btnTop = bottom - btnH - 12;

        if (it == null) {
            UiUtil.textLeft(g, screen.font(),
                    UiUtil.ellipsize(screen.font(), GtLang.get("gtshaders.browser.pick"), w - 24),
                    x + 12, top + 14, Theme.TEXT_DIM);
            return;
        }

        g.enableScissor(x + 1, top, x + w, btnTop - 4);
        int y = top + 10 - detailScroll;
        UiUtil.textLeft(g, screen.font(), UiUtil.ellipsize(screen.font(), it.name(), w - 24),
                x + 12, y, Theme.TEXT);
        y += screen.font().lineHeight + 4;

        String kindLabel = GtLang.get("gtshaders.catalog.kind." + it.kind().name().toLowerCase(java.util.Locale.ROOT));
        UiUtil.textLeft(g, screen.font(), kindLabel, x + 12, y, Theme.ACCENT);
        y += screen.font().lineHeight + 2;
        if (!it.badge().isEmpty()) {
            UiUtil.textLeft(g, screen.font(), UiUtil.ellipsize(screen.font(), it.badge(), w - 24),
                    x + 12, y, Theme.ACCENT);
            y += screen.font().lineHeight + 2;
        }
        y += 6;

        for (String line : it.doc().detail()) {
            if (line.isEmpty()) {
                y += 5;
                continue;
            }
            y = screen.wrapText(g, line, x + 12, y, w - 24, Theme.TEXT_SUB);
        }

        if (it.paramCount() > 0) {
            y += 6;
            UiUtil.textLeft(g, screen.font(),
                    GtLang.get("gtshaders.browser.param_count", it.paramCount()),
                    x + 12, y, Theme.TEXT_DIM);
            y += screen.font().lineHeight;
        }
        g.disableScissor();
        screen.unclipRegions();
        int contentH = y + detailScroll - (top + 10);
        detailScroll = Math.max(0, Math.min(detailScroll,
                Math.max(0, contentH - (btnTop - top - 16))));

        // 主按钮永远钉在底部：滚动的是说明，不是「怎么用它」
        String label = it.kind() == EffectCatalog.Kind.VANILLA
                ? GtLang.get("gtshaders.browser.preview")
                : GtLang.get("gtshaders.browser.add");
        primaryButton(g, x + 12, btnTop, w - 24, btnH, label, () -> apply(it), mx, my);

        // 原版效果多一条路：直接看 vs 拆成可编辑工程，代价完全不同，所以分成两个动作
        if (it.kind() == EffectCatalog.Kind.VANILLA
                && it.payload() instanceof VanillaEffects.Entry e && e.importable()) {
            int ix = x + 12;
            int iy = btnTop - 24;
            boolean hot = screen.hovered(mx, my, ix, iy, w - 24, 20);
            UiUtil.roundRect(g, ix, iy, w - 24, 20, 4, hot ? Theme.BUTTON_HOVER : Theme.PANEL_SUNKEN);
            UiUtil.box(g, ix, iy, w - 24, 20, Theme.BORDER);
            UiUtil.textCenter(g, screen.font(), GtLang.get("gtshaders.vanilla.import"),
                    ix + (w - 24) / 2, iy + (20 - screen.font().lineHeight) / 2 + 1, Theme.TEXT_SUB);
            screen.addRegion(ix, iy, w - 24, 20, () -> {
                close();
                screen.importVanilla(e);
            }, null);
            if (hot) {
                screen.setTooltip(GtLang.get("gtshaders.vanilla.import_hint", e.editablePasses()),
                        mx, my);
            }
        }
    }

    private void primaryButton(GuiGraphicsExtractor g, int x, int y, int w, int h, String label,
                               Runnable action, int mx, int my) {
        boolean hot = screen.hovered(mx, my, x, y, w, h);
        UiUtil.roundRect(g, x, y, w, h, 5, hot ? Theme.ACCENT_HOVER : Theme.ACCENT);
        UiUtil.textCenter(g, screen.font(), label, x + w / 2,
                y + (h - screen.font().lineHeight) / 2 + 1, Theme.TEXT_ON_ACCENT);
        screen.addRegion(x, y, w, h, action, null);
    }

    // ---------------------------------------------------------------- 动作

    private void apply(EffectCatalog.Item it) {
        close();
        screen.applyCatalogItem(it);
    }

    // ---------------------------------------------------------------- 数据

    /** 搜索优先于分类选择：输入框里有字时，左树的选中只是个摆设。 */
    private List<EffectCatalog.Item> visibleItems() {
        String q = searchField.text().trim();
        if (!q.isEmpty()) {
            return EffectCatalog.search(pool, q);
        }
        List<EffectCatalog.Item> out = new ArrayList<>();
        for (EffectCatalog.Section s : pool) {
            // 选中项可以是一个分节，也可以是整个一级分组，判定收在 EffectCatalog 里
            if (EffectCatalog.inScope(s, sectionId)) {
                out.addAll(s.items());
            }
        }
        return out;
    }

    private int totalCount() {
        int n = 0;
        for (EffectCatalog.Section s : pool) {
            n += s.items().size();
        }
        return n;
    }

    private int countOfTop(String topId) {
        int n = 0;
        for (EffectCatalog.Section s : pool) {
            if (s.topId().equals(topId)) {
                n += s.items().size();
            }
        }
        return n;
    }

    // ---------------------------------------------------------------- 输入

    boolean mouseScrolled(double mx, double my, double sy, int lw, int lh) {
        if (!open) {
            return false;
        }
        int[] m = metrics(lw, lh);
        int x = m[0];
        int y = m[1];
        int w = m[2];
        int h = m[3];
        if (mx < x || mx >= x + w || my < y || my >= y + h) {
            // 浮层盖住整屏，外面的滚轮不该穿透到画布把预览缩放掉
            return true;
        }
        int step = (int) Math.signum(sy) * 32;
        if (m[5] > 0 && mx >= x + w - m[5]) {
            detailScroll = Math.max(0, detailScroll - step);
        } else if (mx >= x + m[4]) {
            listScroll = Math.max(0, listScroll - step);
        } else {
            // 分类树也得能滚：十几个分类已经放不下一屏了
            treeScroll = Math.max(0, treeScroll - step);
        }
        return true;
    }

    /** @return true 表示这次按键被浏览器消化掉了 */
    boolean keyPressed(int key) {
        if (!open) {
            return false;
        }
        if (key == InputConstants.KEY_ESCAPE) {
            close();
            return true;
        }
        if (key == InputConstants.KEY_RETURN
                || key == InputConstants.KEY_NUMPADENTER) {
            if (selected != null) {
                apply(selected);
            }
            return true;
        }
        if (key == InputConstants.KEY_DOWN || key == InputConstants.KEY_UP) {
            moveSelection(key == InputConstants.KEY_DOWN ? 1 : -1);
            return true;
        }
        return false;
    }

    /** 方向键换选中项，并把它滚进视野——否则连按几下选中项就跑到屏幕外了。 */
    private void moveSelection(int delta) {
        List<EffectCatalog.Item> items = visibleItems();
        if (items.isEmpty()) {
            return;
        }
        int i = items.indexOf(selected);
        int next = Math.max(0, Math.min(items.size() - 1, i < 0 ? 0 : i + delta));
        selected = items.get(next);
        detailScroll = 0;
        listScroll = Math.max(listScroll, (next + 1) * ROW_H - (MAX_H - HEAD_H - 16));
        listScroll = Math.min(listScroll, next * ROW_H);
    }
}
