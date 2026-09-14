package mc.GTedd.cn.gtshaders.ui;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import org.jspecify.annotations.Nullable;
import mc.GTedd.cn.gtshaders.ai.AiConfig;
import mc.GTedd.cn.gtshaders.ai.AiLog;
import mc.GTedd.cn.gtshaders.ai.AiCredentials;
import mc.GTedd.cn.gtshaders.ai.AiEndpoint;
import mc.GTedd.cn.gtshaders.ai.AiException;
import mc.GTedd.cn.gtshaders.ai.AiProvider;
import mc.GTedd.cn.gtshaders.ai.ModelCatalog;
import mc.GTedd.cn.gtshaders.ai.ShaderSmith;
import mc.GTedd.cn.gtshaders.i18n.GtLang;
import mc.GTedd.cn.gtshaders.workspace.Workspace;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.UnaryOperator;

/**
 * AI 生成着色器的浮层：一句话进去，一个能在画面上跑的效果出来。
 *
 * <h2>为什么 key 输入框永远是空的</h2>
 *
 * <p>已经保存的 key <b>不回填</b>到输入框里，只在占位符上显示掩码。回填的话，
 * 玩家每次打开这个面板、每次直播或者截图求助，都会把明文暴露一次。
 * 代价是想改 key 得整串重敲——但改 key 是极低频操作，而暴露是每次打开都在发生的。
 *
 * <h2>为什么模型是拉下来选而不是敲</h2>
 *
 * <p>模型名必须和服务商文档一字不差，而它们长得都差不多。敲错一个字符，
 * 服务端回的是 404——在玩家眼里和「地址填错了」「服务挂了」完全一样，
 * 他会去反复核对一个本来就没错的地址。点一下从清单里选，这一整类错误就消失了。
 *
 * <p>拉清单同时也是<b>连接自检</b>：拉得到，说明地址、key、网络三样都对。
 * 所以这里没有单独的「测试连接」按钮——那会是同一件事的第二个入口。
 *
 * <h2>为什么状态字段是 volatile 而不是每次都切回主线程</h2>
 *
 * <p>生成过程跑在虚拟线程上，回调频率是每个 token 一次。为每个 token 往主线程队列里
 * 塞一个任务，等于把渲染线程的任务队列当成日志管道用。这里改成后台线程只写 volatile 字段、
 * 渲染线程每帧读一次——反正界面本来就是每帧重画的，中间那些没被读到的中间态没有任何意义。
 */
final class AiPanel {

    private static final int W = 520;
    private static final int HEAD_H = 40;
    private static final int ROW_H = 26;
    private static final int GAP = 10;
    private static final int BTN_H = 28;
    private static final int LABEL_W = 60;
    /** 流式预览只留最后这几行——它是进度指示，不是代码编辑器。 */
    private static final int PREVIEW_LINES = 4;
    /** 标题栏右侧被按钮占掉的宽度，拖动手柄要避开这一段。 */
    private static final int BUTTONS_W = 56;
    /** 拖到边缘时至少还得露出这么宽，否则面板就找不回来了。 */
    private static final int EDGE_KEEP = 80;
    /** 下拉列表一次最多露出这么多行，再多就滚。 */
    private static final int LIST_ROWS = 8;
    private static final int LIST_ROW_H = 18;

    /**
     * 四个字段的长度上限。
     *
     * <p>{@link TextField} 默认只给 64 个字符，那是按层名、搜索词这类短文本定的，
     * 这几个没有一个装得下：用英文描述一个效果轻易过百；OpenAI 兼容服务的
     * {@code sk-proj-…} 上百字符；中转服务的地址带一长串路径。
     *
     * <p>超出上限的字符是<b>静默丢弃</b>的——粘一把长 key 进来只进前 64 个，
     * 界面上看不出任何异常，最后只收到一句 401，玩家根本无从查起。
     */
    private static final int WISH_MAX = 400;
    private static final int URL_MAX = 256;
    private static final int LABEL_MAX = 48;
    private static final int KEY_MAX = 512;

    private final EditorScreen screen;

    private final TextField wishField = new TextField(TextField.Kind.TEXT, WISH_MAX);
    private final TextField labelField = new TextField(TextField.Kind.TEXT, LABEL_MAX);
    private final TextField baseUrlField = new TextField(TextField.Kind.TEXT, URL_MAX);
    private final TextField keyField = new TextField(TextField.Kind.TEXT, KEY_MAX);

    private boolean open;
    private boolean settingsOpen;
    private AiConfig config = AiConfig.defaults();

    /** 展开中的下拉：{@code "provider"} / {@code "model"} / null。 */
    private @Nullable String openList;
    private int listScroll;
    /** 展开的下拉按钮的矩形，列表画在它正下方。 */
    private int @Nullable [] listAnchor;

    /**
     * 玩家拖过之后的左上角位置；null 表示还没拖过，居中显示。
     *
     * <p>不落盘：这是「这一次想把它挪开看看底下的画面」，不是一项配置。
     * 存起来反而会让下次打开时面板出现在一个玩家已经忘了的角落。
     */
    private int @Nullable [] pos;

    private ShaderSmith.@Nullable Handle handle;

    // ---- 以下字段由后台线程写、渲染线程读 ----
    private volatile ShaderSmith.@Nullable Stage stage;
    private volatile int round;
    private volatile int budget;
    private volatile int received;
    private volatile String tail = "";
    private volatile @Nullable String errorTitle;
    private volatile @Nullable String errorDetail;
    private volatile boolean loadingModels;

    AiPanel(EditorScreen screen) {
        // 和 EffectBrowser 同理：registerField 要推迟到 openNow()，
        // 此刻 EditorScreen 的 allFields 还没初始化
        this.screen = screen;

        // 改完一失焦就写回配置。不这么做的话，玩家改了地址却没点任何「保存」，
        // 下次打开又变回旧的——而他并不知道这里还需要一次确认
        labelField.setOnCommit(() -> updateActive(p -> p.withLabel(labelField.text().trim())));
        baseUrlField.setOnCommit(() -> updateActive(p -> p.withBaseUrl(baseUrlField.text())));
    }

    boolean isOpen() {
        return open;
    }

    boolean isBusy() {
        ShaderSmith.Handle h = handle;
        return h != null && h.isRunning();
    }

    void openNow() {
        open = true;
        config = AiConfig.load(Workspace.rootDir());
        screen.registerField(wishField, labelField, baseUrlField, keyField);
        syncFieldsFromActive();
        keyField.setTextIfUnfocused("");
        openList = null;
        // 没配过 key 就直接把设置摊开——否则玩家点「生成」得到一句「没有 key」，
        // 却看不到该去哪儿填
        settingsOpen = !AiCredentials.has(config.active().baseUrl());
        // 必须先清掉别处的焦点：顶栏工程名之类的字段可能还 focused 着，
        // 而 focusedField() 取的是 allFields 里第一个 focused 的，
        // 那时敲进去的字会跑到面板外面某个看不见的框里
        screen.blurAllExcept(wishField);
        wishField.focus();
    }

    void close() {
        open = false;
        openList = null;
        wishField.blur();
        labelField.blur();
        baseUrlField.blur();
        keyField.blur();
    }

    /** 关编辑器时调用：不取消的话，那条虚拟线程会抱着一个已经没人看的界面继续跑完。 */
    void abort() {
        ShaderSmith.Handle h = handle;
        if (h != null) {
            h.cancel();
        }
        handle = null;
        stage = null;
        screen.abortAiSession();
    }

    // ------------------------------------------------------------------ 配置读写

    private AiProvider active() {
        return config.active();
    }

    private void syncFieldsFromActive() {
        AiProvider p = active();
        labelField.setTextIfUnfocused(p.label());
        baseUrlField.setTextIfUnfocused(p.baseUrl());
    }

    private void updateActive(UnaryOperator<AiProvider> edit) {
        config = config.withProvider(edit.apply(active()));
        persist();
    }

    private void persist() {
        config.save(Workspace.rootDir());
    }

    /** 当前正在编辑的那条对应的 endpoint。key 现取现用，不留在字段里。 */
    private AiEndpoint endpoint() {
        AiProvider p = active();
        return AiEndpoint.of(p, config, AiCredentials.get(p.baseUrl()));
    }

    // ------------------------------------------------------------------ 布局

    /**
     * 面板高度。<b>必须和 render 里各段实际吃掉的高度逐项对上</b>——按钮是按「底边往上量」
     * 定位的，这里少算一点，内容就会从按钮底下顶出去，多算一点则底部留一条空白。
     */
    private int height() {
        int h = HEAD_H + GAP + 14 + ROW_H + GAP;
        h += 24;
        if (settingsOpen) {
            h += (ROW_H + GAP) * 5;
            h += 14 + GAP;
            h += ROW_H + GAP;
        }
        h += statusHeight();
        return h + BTN_H + GAP * 2;
    }

    private int statusHeight() {
        if (errorTitle != null) {
            return 32;
        }
        return stage == null ? 18 : 16 + PREVIEW_LINES * 10 + 12;
    }

    private int[] metrics(int lw, int lh) {
        int w = Math.min(W, lw - 40);
        int h = Math.min(height(), lh - 40);
        int[] at = pos;
        if (at == null) {
            return new int[]{(lw - w) / 2, (lh - h) / 2, w, h};
        }
        // 拖到屏幕外就再也抓不回来了，所以两边都留一截必须可见：
        // 横向至少露出 EDGE_KEEP 宽，纵向至少露出整条标题栏
        int x = Math.clamp(at[0], EDGE_KEEP - w, lw - EDGE_KEEP);
        int y = Math.clamp(at[1], 0, Math.max(0, lh - HEAD_H));
        return new int[]{x, y, w, h};
    }

    /**
     * 标题栏上可以捏住拖动的那一段。
     *
     * <p><b>刻意不含右侧那两个按钮</b>：手柄盖在按钮上的话，
     * 「关闭」和「复制诊断」就变成了两个拖不动也点不着的装饰。
     *
     * @return {@code {x, y, w, h}}；面板没开时返回 null
     */
    int @Nullable [] dragHandle(int lw, int lh) {
        if (!open) {
            return null;
        }
        int[] m = metrics(lw, lh);
        return new int[]{m[0], m[1], Math.max(0, m[2] - BUTTONS_W), HEAD_H};
    }

    /** 当前左上角，供拖动时算按下点相对面板的偏移。 */
    int[] origin(int lw, int lh) {
        int[] m = metrics(lw, lh);
        return new int[]{m[0], m[1]};
    }

    void moveTo(int x, int y) {
        pos = new int[]{x, y};
        // 拖的时候把下拉收起来：它的位置是按下拉按钮算的，面板一动就对不上了
        openList = null;
    }

    // ------------------------------------------------------------------ 绘制

    void render(GuiGraphicsExtractor g, int lw, int lh, int mx, int my) {
        if (!open) {
            return;
        }
        int[] m = metrics(lw, lh);
        int x = m[0];
        int y = m[1];
        int w = m[2];
        int h = m[3];

        UiUtil.dropShadow(g, x, y, w, h, 4);
        UiUtil.roundRect(g, x, y, w, h, 8, Theme.PANEL);
        UiUtil.roundOutline(g, x, y, w, h, 8, Theme.BORDER);

        int cy = renderHeader(g, x, y, w, mx, my);
        cy = renderWish(g, x, cy, w, mx, my);
        cy = renderSettings(g, x, cy, w, mx, my);
        renderStatus(g, x, cy, w);
        renderButtons(g, x, y + h - BTN_H - GAP, w, mx, my);

        // 下拉列表最后画：它要盖住面板上的一切，而 region 表是反向扫的，
        // 后登记的先命中，于是「点列表项」不会被底下的输入框抢走
        renderOpenList(g, lw, lh, mx, my);
    }

    private int renderHeader(GuiGraphicsExtractor g, int x, int y, int w, int mx, int my) {
        Icons.sparkle(g, x + 14, y + 12, 16, Theme.ACCENT);
        UiUtil.textLeft(g, screen.font(), GtLang.get("gtshaders.ai.title"),
                x + 36, y + 15, Theme.TEXT);
        // 标题栏可拖，但不画悬停反馈也不弹提示：和系统窗口一样，捏住标题栏就走

        int cx = x + w - 30;
        boolean hot = screen.hovered(mx, my, cx, y + 12, 16, 16);
        Icons.close(g, cx, y + 12, 16, hot ? Theme.TEXT : Theme.TEXT_DIM);
        screen.addRegion(cx, y + 12, 16, 16, this::close, null);

        // 复制诊断：出问题时要能一键把时间线贴给别人看，
        // 否则远程排查只有界面上那一行归好类的错误，什么线索都没有
        int dx = cx - 22;
        boolean dhot = screen.hovered(mx, my, dx, y + 12, 16, 16);
        Icons.commit(g, dx, y + 12, 16, dhot ? Theme.TEXT : Theme.TEXT_DIM);
        screen.addRegion(dx, y + 12, 16, 16, this::copyDiagnostics, null);
        if (dhot) {
            screen.setTooltip(GtLang.get("gtshaders.ai.copy_diagnostics"), mx, my);
        }

        UiUtil.hLine(g, x + 1, x + w - 1, y + HEAD_H - 1, Theme.BORDER);
        return y + HEAD_H + GAP;
    }

    private int renderWish(GuiGraphicsExtractor g, int x, int y, int w, int mx, int my) {
        UiUtil.textLeft(g, screen.font(), GtLang.get("gtshaders.ai.wish"),
                x + 14, y, Theme.TEXT_SUB);
        int fy = y + 14;
        // 这里也不该是搜索框：玩家在写需求，不是在检索什么
        textInput(g, wishField, x + 14, fy, w - 28, ROW_H,
                GtLang.get("gtshaders.ai.wish_placeholder"), mx, my);
        return fy + ROW_H + GAP;
    }

    // ------------------------------------------------------------------ 设置区

    private int renderSettings(GuiGraphicsExtractor g, int x, int y, int w, int mx, int my) {
        AiProvider p = active();
        boolean hot = screen.hovered(mx, my, x + 14, y, w - 28, 18);
        Icons.caret(g, x + 14, y + 2, 12, hot ? Theme.TEXT : Theme.TEXT_SUB, settingsOpen);
        String summary = GtLang.get("gtshaders.ai.settings",
                p.displayName() + " · " + (p.model().isBlank() ? "?" : p.model()),
                AiCredentials.has(p.baseUrl())
                        ? AiCredentials.mask(AiCredentials.get(p.baseUrl()))
                        : GtLang.get("gtshaders.ai.key_missing"));
        UiUtil.textLeft(g, screen.font(), UiUtil.ellipsize(screen.font(), summary, w - 56),
                x + 30, y + 4, hot ? Theme.TEXT : Theme.TEXT_SUB);
        screen.addRegion(x + 14, y, w - 28, 18, () -> {
            settingsOpen = !settingsOpen;
            openList = null;
        }, null);
        int cy = y + 24;
        if (!settingsOpen) {
            return cy;
        }
        // 面板重开或切换服务商之后，输入框要跟上当前这条
        syncFieldsFromActive();

        cy = renderProviderRow(g, x, cy, w, mx, my);
        cy = renderLabelRow(g, x, cy, w, mx, my);
        cy = renderUrlRow(g, x, cy, w, mx, my);
        cy = renderModelRow(g, x, cy, w, mx, my);
        cy = renderKeyRow(g, x, cy, w, mx, my);
        cy = renderPrivacyNote(g, x, cy, w, mx, my);
        cy = renderOptionsRow(g, x, cy, w, mx, my);
        return cy;
    }

    /** 服务商：下拉 + 新建 + 删除。 */
    private int renderProviderRow(GuiGraphicsExtractor g, int x, int y, int w, int mx, int my) {
        label(g, GtLang.get("gtshaders.ai.provider"), x, y);
        int bw = 24;
        int fx = x + 14 + LABEL_W;
        int fw = w - 28 - LABEL_W - (bw + 4) * 2;
        dropdown(g, "provider", fx, y, fw, ROW_H, active().displayName(), mx, my);

        int bx = fx + fw + 4;
        iconBtn(g, bx, y, bw, ROW_H, Icons::plus, GtLang.get("gtshaders.ai.provider_add"),
                () -> openListAt("preset", new int[]{fx, y, fw, ROW_H}), mx, my);
        bx += bw + 4;
        // 只剩一条时不给删：删光了界面上就没有任何可选项，玩家会卡在
        // 一个既不能生成也不能配置的状态里
        iconBtn(g, bx, y, bw, ROW_H, Icons::trash, GtLang.get("gtshaders.ai.provider_remove"),
                config.providers().size() > 1 ? this::removeProvider : null, mx, my);
        return y + ROW_H + GAP;
    }

    /**
     * 名称 + 协议，挤在一行。
     *
     * <p>协议不单独占一行是因为它只有两个取值、改动频率极低；而名称通常很短，
     * 右边那一截本来就是空的。协议放在这儿也顺：它和地址是一组信息——
     * 填错了症状一样，都是 404。
     */
    private int renderLabelRow(GuiGraphicsExtractor g, int x, int y, int w, int mx, int my) {
        label(g, GtLang.get("gtshaders.ai.provider_label"), x, y);
        int protoW = 128;
        int fx = x + 14 + LABEL_W;
        int fw = w - 28 - LABEL_W - protoW - 4;
        textInput(g, labelField, fx, y, fw, ROW_H,
                GtLang.get("gtshaders.ai.provider_label_placeholder"), mx, my);
        cycleButton(g, fx + fw + 4, y, protoW, GtLang.get("gtshaders.ai.protocol"),
                GtLang.get(active().protocol().translationKey()),
                () -> updateActive(p -> p.withProtocol(p.protocol().next())), mx, my);
        if (screen.hovered(mx, my, fx + fw + 4, y, protoW, ROW_H)) {
            screen.setTooltip(GtLang.get("gtshaders.ai.protocol_hint"), mx, my);
        }
        return y + ROW_H + GAP;
    }

    private int renderUrlRow(GuiGraphicsExtractor g, int x, int y, int w, int mx, int my) {
        label(g, GtLang.get("gtshaders.ai.base_url"), x, y);
        textInput(g, baseUrlField, x + 14 + LABEL_W, y, w - 28 - LABEL_W, ROW_H,
                "https://api.deepseek.com", mx, my);
        return y + ROW_H + GAP;
    }

    /** 模型：下拉 + 拉取按钮。拉取同时就是连接自检。 */
    private int renderModelRow(GuiGraphicsExtractor g, int x, int y, int w, int mx, int my) {
        label(g, GtLang.get("gtshaders.ai.model"), x, y);
        int btnW = 62;
        int fx = x + 14 + LABEL_W;
        int fw = w - 28 - LABEL_W - btnW - 4;
        String model = active().model();
        dropdown(g, "model", fx, y, fw, ROW_H,
                model.isBlank() ? GtLang.get("gtshaders.ai.model_unset") : model, mx, my);
        button(g, fx + fw + 4, y, btnW, ROW_H,
                GtLang.get(loadingModels ? "gtshaders.ai.models_loading" : "gtshaders.ai.models_fetch"),
                loadingModels ? null : this::fetchModels, false, mx, my);
        return y + ROW_H + GAP;
    }

    private int renderKeyRow(GuiGraphicsExtractor g, int x, int y, int w, int mx, int my) {
        label(g, GtLang.get("gtshaders.ai.api_key"), x, y);
        int btnW = 52;
        int fx = x + 14 + LABEL_W;
        int fw = w - 28 - LABEL_W - btnW - 4;
        String url = active().baseUrl();
        String placeholder = AiCredentials.has(url)
                ? GtLang.get("gtshaders.ai.key_saved", AiCredentials.mask(AiCredentials.get(url)))
                : GtLang.get("gtshaders.ai.key_placeholder");
        textInput(g, keyField, fx, y, fw, ROW_H, placeholder, mx, my);
        button(g, fx + fw + 4, y, btnW, ROW_H, GtLang.get("gtshaders.ai.save_key"),
                this::saveKey, true, mx, my);
        return y + ROW_H + GAP;
    }

    private int renderPrivacyNote(GuiGraphicsExtractor g, int x, int y, int w, int mx, int my) {
        // 这句提示是这个功能里最该被读到的一行字
        String note = GtLang.get("gtshaders.ai.key_privacy");
        UiUtil.textLeft(g, screen.font(), UiUtil.ellipsize(screen.font(), note, w - 28),
                x + 14, y, Theme.SUCCESS);
        if (screen.hovered(mx, my, x + 14, y - 2, w - 28, 14)) {
            screen.setTooltip(AiCredentials.defaultFile().toString(), mx, my);
        }
        return y + 14 + GAP;
    }

    /** 流式 / 修错轮数 / 温度，一行三个循环切换按钮。 */
    private int renderOptionsRow(GuiGraphicsExtractor g, int x, int y, int w, int mx, int my) {
        int cellW = (w - 28 - 12) / 4;
        int cx = x + 14;
        cycleButton(g, cx, y, cellW, GtLang.get("gtshaders.ai.opt_stream"),
                GtLang.get(config.stream() ? "gtshaders.ai.on" : "gtshaders.ai.off"),
                () -> {
                    config = config.withStream(!config.stream());
                    persist();
                }, mx, my);
        cx += cellW + 4;
        cycleButton(g, cx, y, cellW, GtLang.get("gtshaders.ai.opt_repair"),
                String.valueOf(config.repairRounds()),
                () -> {
                    config = config.withRepairRounds((config.repairRounds() + 1) % 6);
                    persist();
                }, mx, my);
        cx += cellW + 4;
        cycleButton(g, cx, y, cellW, GtLang.get("gtshaders.ai.opt_temperature"),
                String.format(Locale.ROOT, "%.1f", config.temperature()),
                () -> {
                    float[] steps = {0.3f, 0.5f, 0.7f, 1.0f, 1.3f};
                    float next = steps[0];
                    for (int i = 0; i < steps.length; i++) {
                        if (Math.abs(steps[i] - config.temperature()) < 0.05f) {
                            next = steps[(i + 1) % steps.length];
                            break;
                        }
                    }
                    config = config.withTemperature(next);
                    persist();
                }, mx, my);
        cx += cellW + 4;
        // 输出上限必须能在界面上调：截断是最常见的失败，而它以前只能去改 ai.json
        cycleButton(g, cx, y, cellW, GtLang.get("gtshaders.ai.opt_max_tokens"),
                config.maxOutputTokens() <= AiConfig.MAX_OUTPUT_AUTO
                        ? GtLang.get("gtshaders.ai.opt_max_tokens_auto")
                        : String.valueOf(config.maxOutputTokens()),
                () -> {
                    // 从「自动」起循环。自动是默认也是推荐值：服务端会用模型自己的上限，
                    // 而这一代模型的输出能力差了两个数量级，替它们猜一个数没有意义
                    int[] steps = {AiConfig.MAX_OUTPUT_AUTO, 16384, 32768, 65536, 131072};
                    int next = steps[0];
                    for (int i = 0; i < steps.length; i++) {
                        if (steps[i] == config.maxOutputTokens()) {
                            next = steps[(i + 1) % steps.length];
                            break;
                        }
                    }
                    config = config.withMaxOutputTokens(next);
                    persist();
                }, mx, my);
        if (screen.hovered(mx, my, cx, y, cellW, ROW_H)) {
            screen.setTooltip(GtLang.get("gtshaders.ai.opt_max_tokens_hint"), mx, my);
        }
        return y + ROW_H + GAP;
    }

    // ------------------------------------------------------------------ 控件

    private void label(GuiGraphicsExtractor g, String text, int x, int y) {
        UiUtil.textLeft(g, screen.font(), text, x + 14,
                y + (ROW_H - screen.font().lineHeight) / 2, Theme.TEXT_SUB);
    }

    /**
     * 普通文本输入框。
     *
     * <p>不用 {@code screen.searchBox}：那个会画一个放大镜并把文本整体右移 18px。
     * 放大镜在「服务地址 / 模型 / API Key」上是错的图示，而且这几栏本来就窄，
     * 那 18px 是实打实的可见字符数。
     */
    private void textInput(GuiGraphicsExtractor g, TextField field, int x, int y, int w, int h,
                           String placeholder, int mx, int my) {
        boolean hot = screen.hovered(mx, my, x, y, w, h);
        field.render(g, screen.font(), x, y, w, h, hot, false);
        if (field.text().isEmpty() && !field.isFocused()) {
            UiUtil.textLeft(g, screen.font(),
                    UiUtil.ellipsize(screen.font(), placeholder, w - 10),
                    x + 5, y + (h - screen.font().lineHeight) / 2 + 1, Theme.TEXT_DIM);
        }
        screen.registerField(field);
        screen.addFieldRegion(field, x, y, w, h);
    }

    /** 下拉按钮。点一下展开列表，列表本体在 {@link #renderOpenList} 里画。 */
    private void dropdown(GuiGraphicsExtractor g, String key, int x, int y, int w, int h,
                          String text, int mx, int my) {
        boolean expanded = key.equals(openList);
        boolean hot = screen.hovered(mx, my, x, y, w, h);
        UiUtil.roundRect(g, x, y, w, h, 4, Theme.INPUT_BG);
        if (expanded || hot) {
            UiUtil.box(g, x, y, w, h, expanded ? Theme.ACCENT : Theme.BORDER_STRONG);
        }
        UiUtil.textLeft(g, screen.font(), UiUtil.ellipsize(screen.font(), text, w - 22),
                x + 6, y + (h - screen.font().lineHeight) / 2 + 1, Theme.TEXT);
        Icons.chevronDown(g, x + w - 16, y + (h - 12) / 2, 12, Theme.TEXT_DIM);
        int[] anchor = {x, y, w, h};
        screen.addRegion(x, y, w, h, () -> {
            if (key.equals(openList)) {
                openList = null;
            } else {
                openListAt(key, anchor);
            }
        }, null);
    }

    private void openListAt(String key, int[] anchor) {
        openList = key;
        listAnchor = anchor;
        listScroll = 0;
    }

    private void iconBtn(GuiGraphicsExtractor g, int x, int y, int w, int h, EditorScreen.IconDrawer icon,
                         String tip, @Nullable Runnable action, int mx, int my) {
        boolean enabled = action != null;
        boolean hot = enabled && screen.hovered(mx, my, x, y, w, h);
        UiUtil.roundRect(g, x, y, w, h, 4, hot ? Theme.BUTTON_HOVER : Theme.BUTTON_BG);
        UiUtil.roundOutline(g, x, y, w, h, 4, Theme.BORDER_STRONG);
        icon.draw(g, x + (w - 12) / 2, y + (h - 12) / 2, 12,
                enabled ? Theme.TEXT_SUB : Theme.TEXT_DIM);
        if (enabled) {
            screen.addRegion(x, y, w, h, action, null);
            if (hot) {
                screen.setTooltip(tip, mx, my);
            }
        }
    }

    /** 「标签 + 当前值」的循环切换按钮。点一下换下一个值。 */
    private void cycleButton(GuiGraphicsExtractor g, int x, int y, int w, String label,
                             String value, Runnable action, int mx, int my) {
        boolean hot = screen.hovered(mx, my, x, y, w, ROW_H);
        UiUtil.roundRect(g, x, y, w, ROW_H, 4, hot ? Theme.BUTTON_HOVER : Theme.INPUT_BG);
        int ty = y + (ROW_H - screen.font().lineHeight) / 2 + 1;
        UiUtil.textLeft(g, screen.font(), UiUtil.ellipsize(screen.font(), label, w - 30),
                x + 6, ty, Theme.TEXT_SUB);
        UiUtil.textRight(g, screen.font(), value, x + w - 6, ty, Theme.TEXT);
        screen.addRegion(x, y, w, ROW_H, action, null);
    }

    /** {@code action} 为 null 表示禁用：画成灰的，也不登记可点区域。 */
    private void button(GuiGraphicsExtractor g, int x, int y, int w, int h, String label,
                        @Nullable Runnable action, boolean primary, int mx, int my) {
        boolean enabled = action != null;
        boolean hot = enabled && screen.hovered(mx, my, x, y, w, h);
        int bg;
        int fg;
        if (!enabled) {
            bg = Theme.PANEL_SUNKEN;
            fg = Theme.TEXT_DIM;
        } else if (primary) {
            bg = hot ? Theme.ACCENT_HOVER : Theme.ACCENT;
            fg = Theme.TEXT_ON_ACCENT;
        } else {
            bg = hot ? Theme.BUTTON_HOVER : Theme.BUTTON_BG;
            fg = Theme.TEXT;
        }
        UiUtil.roundRect(g, x, y, w, h, 5, bg);
        if (!primary && enabled) {
            UiUtil.roundOutline(g, x, y, w, h, 5, Theme.BORDER_STRONG);
        }
        UiUtil.textCenter(g, screen.font(), label, x + w / 2,
                y + (h - screen.font().lineHeight) / 2 + 1, fg);
        if (enabled) {
            screen.addRegion(x, y, w, h, action, null);
        }
    }

    // ------------------------------------------------------------------ 下拉列表

    private List<String> listItems() {
        List<String> out = new ArrayList<>();
        if ("provider".equals(openList)) {
            for (AiProvider p : config.providers()) {
                out.add(p.displayName());
            }
        } else if ("model".equals(openList)) {
            out.addAll(ModelCatalog.cached(endpoint()));
        } else if ("preset".equals(openList)) {
            out.add(GtLang.get("gtshaders.ai.preset_copy"));
            for (AiProvider preset : AiProvider.presets()) {
                out.add(preset.label());
            }
            out.add(GtLang.get("gtshaders.ai.preset_blank"));
        }
        return out;
    }

    private void renderOpenList(GuiGraphicsExtractor g, int lw, int lh, int mx, int my) {
        String key = openList;
        int[] anchor = listAnchor;
        if (key == null || anchor == null) {
            return;
        }
        List<String> items = listItems();

        // 列表外点一下即收起。铺在列表本体之前登记，所以永远被本体压住
        screen.addRegion(0, 0, lw, lh, () -> openList = null, null);

        int x = anchor[0];
        int w = Math.max(anchor[2], 120);
        int rows = Math.min(Math.max(items.size(), 1), LIST_ROWS);
        int h = rows * LIST_ROW_H + 8;
        int y = anchor[1] + anchor[3] + 2;
        // 下面放不下就翻到按钮上方——否则列表会被面板底边裁掉一半
        if (y + h > lh - 4) {
            y = Math.max(4, anchor[1] - h - 2);
        }

        UiUtil.dropShadow(g, x, y, w, h, 3);
        UiUtil.roundRect(g, x, y, w, h, 5, Theme.PANEL);
        UiUtil.roundOutline(g, x, y, w, h, 5, Theme.BORDER_STRONG);

        if (items.isEmpty()) {
            UiUtil.textLeft(g, screen.font(),
                    UiUtil.ellipsize(screen.font(),
                            GtLang.get("gtshaders.ai.models_empty"), w - 12),
                    x + 6, y + 6, Theme.TEXT_DIM);
            return;
        }

        int maxScroll = Math.max(0, items.size() - LIST_ROWS);
        listScroll = Math.clamp(listScroll, 0, maxScroll);
        String current = "provider".equals(key) ? active().displayName() : active().model();

        for (int i = 0; i < rows; i++) {
            int index = i + listScroll;
            if (index >= items.size()) {
                break;
            }
            String item = items.get(index);
            int ry = y + 4 + i * LIST_ROW_H;
            boolean hot = screen.hovered(mx, my, x + 3, ry, w - 6, LIST_ROW_H);
            boolean selected = item.equals(current);
            if (hot || selected) {
                UiUtil.roundRect(g, x + 3, ry, w - 6, LIST_ROW_H, 3,
                        hot ? Theme.BUTTON_HOVER : Theme.ACCENT_SOFT);
            }
            UiUtil.textLeft(g, screen.font(), UiUtil.ellipsize(screen.font(), item, w - 14),
                    x + 7, ry + (LIST_ROW_H - screen.font().lineHeight) / 2 + 1,
                    selected ? Theme.ACCENT : Theme.TEXT);
            final int chosen = index;
            screen.addRegion(x + 3, ry, w - 6, LIST_ROW_H, () -> choose(key, chosen), null);
        }

        if (maxScroll > 0) {
            // 有得滚就得让人看出来，否则下面那些条目等于不存在
            int trackH = h - 8;
            int thumbH = Math.max(12, trackH * rows / items.size());
            int thumbY = y + 4 + (trackH - thumbH) * listScroll / maxScroll;
            UiUtil.roundRect(g, x + w - 5, y + 4, 2, trackH, 1, Theme.SCROLL_TRACK);
            UiUtil.roundRect(g, x + w - 5, thumbY, 2, thumbH, 1, Theme.SCROLL_THUMB);
        }
    }

    private void choose(String key, int index) {
        if ("provider".equals(key)) {
            List<AiProvider> ps = config.providers();
            if (index >= 0 && index < ps.size()) {
                // 切换前先把正在编辑的输入框收掉，否则 setTextIfUnfocused 不生效，
                // 新选中的那条会显示成上一条的地址
                labelField.blur();
                baseUrlField.blur();
                config = config.withActive(ps.get(index).id());
                persist();
                syncFieldsFromActive();
            }
        } else if ("model".equals(key)) {
            List<String> models = ModelCatalog.cached(endpoint());
            if (index >= 0 && index < models.size()) {
                updateActive(p -> p.withModel(models.get(index)));
            }
        } else if ("preset".equals(key)) {
            addFromPreset(index);
        }
        openList = null;
    }

    /**
     * 从「新建来源」列表加一条。
     *
     * <p>0 是复制当前，中间是内置预设，最后一个是空白。
     * 预设一定要换新 id：它们的 id 是固定的，直接加会把同名那条覆盖掉，
     * 表现成「点了新建却什么都没多出来」。
     */
    private void addFromPreset(int index) {
        labelField.blur();
        baseUrlField.blur();
        List<AiProvider> presets = AiProvider.presets();
        AiProvider added;
        if (index <= 0) {
            added = active().copyAsNew();
        } else if (index <= presets.size()) {
            added = presets.get(index - 1).withNewId();
        } else {
            added = AiProvider.blank();
        }
        config = config.plusProvider(added);
        persist();
        syncFieldsFromActive();
    }

    boolean mouseScrolled(double mx, double my, double sy) {
        if (!open || openList == null) {
            return false;
        }
        listScroll = Math.max(0, listScroll - (int) Math.signum(sy));
        return true;
    }

    // ------------------------------------------------------------------ 状态

    private void renderStatus(GuiGraphicsExtractor g, int x, int y, int w) {
        String err = errorTitle;
        if (err != null) {
            UiUtil.textLeft(g, screen.font(), UiUtil.ellipsize(screen.font(), err, w - 28),
                    x + 14, y, Theme.ERROR);
            String detail = errorDetail;
            if (detail != null && !detail.isBlank()) {
                UiUtil.textLeft(g, screen.font(),
                        UiUtil.ellipsize(screen.font(), detail, w - 28),
                        x + 14, y + 14, Theme.TEXT_DIM);
            }
            return;
        }

        ShaderSmith.Stage s = stage;
        if (s == null) {
            UiUtil.textLeft(g, screen.font(),
                    UiUtil.ellipsize(screen.font(), GtLang.get("gtshaders.ai.idle_hint"), w - 28),
                    x + 14, y, Theme.TEXT_DIM);
            return;
        }

        String label = switch (s) {
            // 关掉流式时一个字都不会回来，还显示「已收到 0 字」会让人以为卡死了
            case GENERATING -> config.stream()
                    ? GtLang.get("gtshaders.ai.stage.generating", received)
                    : GtLang.get("gtshaders.ai.stage.generating_blocking");
            case COMPILING -> GtLang.get("gtshaders.ai.stage.compiling");
            case REPAIRING -> GtLang.get("gtshaders.ai.stage.repairing", round, budget);
            case POLISHING -> GtLang.get("gtshaders.ai.stage.polishing");
        };
        UiUtil.textLeft(g, screen.font(), label, x + 14, y, Theme.ACCENT);

        // 流式尾巴。它的作用只是证明「还在动」——等待时最难受的是分不清慢和卡死
        String t = tail;
        int ty = y + 16;
        if (!t.isEmpty()) {
            UiUtil.roundRect(g, x + 14, ty, w - 28, PREVIEW_LINES * 10 + 6, 4, Theme.PANEL_SUNKEN);
            String[] lines = t.split("\n", -1);
            int from = Math.max(0, lines.length - PREVIEW_LINES);
            for (int i = from; i < lines.length; i++) {
                UiUtil.textLeft(g, screen.font(),
                        UiUtil.ellipsize(screen.font(), lines[i], w - 36),
                        x + 18, ty + 4 + (i - from) * 10, Theme.TEXT_DIM);
            }
        }
    }

    private void renderButtons(GuiGraphicsExtractor g, int x, int y, int w, int mx, int my) {
        int bw = 92;
        if (isBusy()) {
            button(g, x + w - 14 - bw, y, bw, BTN_H, GtLang.get("gtshaders.ai.cancel"),
                    this::cancel, false, mx, my);
            return;
        }
        boolean ready = !wishField.text().isBlank();
        button(g, x + w - 14 - bw, y, bw, BTN_H, GtLang.get("gtshaders.ai.generate"),
                ready ? this::generate : null, true, mx, my);
    }

    // ------------------------------------------------------------------ 动作

    private void removeProvider() {
        labelField.blur();
        baseUrlField.blur();
        config = config.minusProvider(active().id());
        persist();
        syncFieldsFromActive();
        openList = null;
    }

    /**
     * 把最近的 AI 时间线复制到剪贴板。
     *
     * <p>连同当前配置一起（<b>不含 key</b>，只给掩码）：远程排查时最先要问的
     * 就是「你用的哪个服务商、哪个模型、哪种协议」，让玩家一次贴全能省掉好几轮来回。
     */
    private void copyDiagnostics() {
        AiProvider p = active();
        String masked = AiCredentials.has(p.baseUrl())
                ? AiCredentials.mask(AiCredentials.get(p.baseUrl()))
                : "(none)";
        String report = String.join("\n",
                "GTShaders AI diagnostics",
                "provider=" + p.displayName()
                        + " protocol=" + p.protocol().name().toLowerCase(Locale.ROOT)
                        + " model=" + (p.model().isBlank() ? "(unset)" : p.model()),
                "baseUrl=" + p.baseUrl() + " -> " + AiEndpoint.of(p, config, "").requestUrl(),
                "key=" + masked,
                "stream=" + config.stream()
                        + " repairRounds=" + config.repairRounds()
                        + " temperature=" + config.temperature()
                        + " maxOutputTokens=" + config.maxOutputTokens(),
                "---",
                AiLog.recent());
        net.minecraft.client.Minecraft.getInstance().keyboardHandler.setClipboard(report);
        screen.showToast(GtLang.get("gtshaders.ai.diagnostics_copied"), false);
    }

    private void saveKey() {
        String typed = keyField.text().trim();
        AiCredentials.put(active().baseUrl(), typed);
        // 立刻清掉输入框：明文在界面上多留一秒都是白留的
        keyField.blur();
        keyField.setTextIfUnfocused("");
        screen.showToast(GtLang.get(typed.isEmpty()
                ? "gtshaders.ai.key_cleared" : "gtshaders.ai.key_stored"), false);
    }

    /**
     * 去服务商那儿要一份模型清单。
     *
     * <p>这同时就是「测试连接」：拉得到，说明地址、key、网络三样都对。
     * 失败时按类别给指引，和生成失败走同一套错误归类。
     */
    private void fetchModels() {
        baseUrlField.blur();
        AiEndpoint ep = endpoint();
        if (!ep.hasKey()) {
            fail(GtLang.get("gtshaders.ai.error.auth"), GtLang.get("gtshaders.ai.key_missing"));
            return;
        }
        errorTitle = null;
        errorDetail = null;
        loadingModels = true;
        ModelCatalog.forget(ep);
        Thread.ofVirtual().name("gtshaders-ai-models").start(() -> {
            try {
                List<String> models = ModelCatalog.fetch(ep);
                loadingModels = false;
                net.minecraft.client.Minecraft.getInstance().execute(() -> {
                    screen.showToast(GtLang.get("gtshaders.ai.models_ok", models.size()), false);
                    // 当前模型不在清单里（换了服务商、或者从没选过）就替他选第一个，
                    // 省掉「拉完还得再点一次」这一步
                    if (!models.contains(active().model())) {
                        // 不能取字母序第一个：DeepSeek 的清单里 vision-exp 就排在 pro 前面，
                        // 而拿视觉实验模型写 GLSL，思维链会把正文挤掉
                        updateActive(p -> p.withModel(ModelCatalog.preferredFor(models)));
                    }
                    openList = "model";
                    listScroll = 0;
                });
            } catch (AiException e) {
                loadingModels = false;
                fail(GtLang.get(e.langKey()), e.detail());
            } catch (Throwable t) {
                loadingModels = false;
                fail(GtLang.get("gtshaders.ai.error.bad_response"), String.valueOf(t.getMessage()));
            }
        });
    }

    private void cancel() {
        abort();
        errorTitle = GtLang.get("gtshaders.ai.cancelled");
        errorDetail = null;
    }

    private void generate() {
        errorTitle = null;
        errorDetail = null;
        tail = "";
        received = 0;
        openList = null;

        // 输入框里可能还有没提交的编辑：不收掉的话，玩家改完地址直接点生成，
        // 用的还是旧地址
        labelField.blur();
        baseUrlField.blur();

        AiProvider p = active();
        if (!p.isComplete()) {
            settingsOpen = true;
            fail(GtLang.get("gtshaders.ai.error.not_found"),
                    GtLang.get("gtshaders.ai.provider_incomplete"));
            return;
        }
        String key = AiCredentials.get(p.baseUrl());
        if (key.isEmpty()) {
            settingsOpen = true;
            fail(GtLang.get("gtshaders.ai.error.auth"), GtLang.get("gtshaders.ai.key_missing"));
            return;
        }
        if (!screen.beginAiSession()) {
            fail(GtLang.get("gtshaders.ai.error.no_world"), null);
            return;
        }

        stage = ShaderSmith.Stage.GENERATING;
        round = 0;
        budget = config.repairRounds();
        handle = ShaderSmith.start(AiEndpoint.of(p, config, key), config,
                wishField.text().trim(), screen::compileAiCandidate, new Listener());
    }

    private void fail(String title, @Nullable String detail) {
        stage = null;
        errorTitle = title;
        errorDetail = detail;
    }

    // ------------------------------------------------------------------ 回调

    /** 全部方法都在后台线程上被调用，只许写 volatile 字段和线程安全的东西。 */
    private final class Listener implements ShaderSmith.Listener {

        @Override
        public void onStage(ShaderSmith.Stage s, int r, int b) {
            stage = s;
            round = r;
            budget = b;
            if (s == ShaderSmith.Stage.REPAIRING || s == ShaderSmith.Stage.POLISHING) {
                // 新一轮重新开始吐字，旧的尾巴留着只会让人以为卡住了
                tail = "";
                received = 0;
            }
        }

        @Override
        public void onDelta(String text) {
            received += text.length();
            String t = tail + text;
            // 只留尾部：这是进度指示，不是编辑器，没必要把整份源码堆在内存里反复拼接
            int cut = t.length() - 400;
            tail = cut > 0 ? t.substring(cut) : t;
        }

        @Override
        public void onSuccess(String source, List<String> notes) {
            stage = null;
            tail = "";
            // 收尾要动工程和界面状态，必须回主线程
            net.minecraft.client.Minecraft.getInstance().execute(() -> {
                screen.finishAiSession(source, notes);
                close();
            });
        }

        @Override
        public void onFailure(AiException e) {
            fail(GtLang.get(e.langKey()), e.detail());
            net.minecraft.client.Minecraft.getInstance().execute(screen::abortAiSession);
        }
    }

    // ------------------------------------------------------------------ 输入

    boolean keyPressed(int key) {
        if (!open) {
            return false;
        }
        if (key == InputConstants.KEY_ESCAPE) {
            if (openList != null) {
                openList = null;
            } else if (isBusy()) {
                cancel();
            } else {
                close();
            }
            return true;
        }
        if (key == InputConstants.KEY_RETURN && wishField.isFocused()
                && !wishField.text().isBlank() && !isBusy()) {
            generate();
            return true;
        }
        // 其余按键一律放过。全吃掉的话 Ctrl+G 也会被吞，面板就再也关不上了；
        // 而玩家正在输入框里打字时，焦点字段在更前面就已经把按键接走了
        return false;
    }

    /** 供 {@link EditorScreen} 判断要不要把滚轮交给这里。 */
    boolean wantsScroll() {
        return open && openList != null;
    }
}
