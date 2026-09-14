package mc.GTedd.cn.gtshaders.ui;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;
import mc.GTedd.cn.gtshaders.GTShaders;
import mc.GTedd.cn.gtshaders.codegen.Samples;
import mc.GTedd.cn.gtshaders.codegen.ShaderImport;
import mc.GTedd.cn.gtshaders.codegen.ShaderTexture;
import mc.GTedd.cn.gtshaders.core.AnchorBinding;
import mc.GTedd.cn.gtshaders.core.AnchorSlot;
import mc.GTedd.cn.gtshaders.core.EmitterSlot;
import mc.GTedd.cn.gtshaders.core.ParamType;
import mc.GTedd.cn.gtshaders.core.ShaderKind;
import mc.GTedd.cn.gtshaders.core.ShaderLayer;
import mc.GTedd.cn.gtshaders.core.ShaderParam;
import mc.GTedd.cn.gtshaders.core.ShaderProject;
import mc.GTedd.cn.gtshaders.core.ViewportRect;
import mc.GTedd.cn.gtshaders.export.CoreShaderExporter;
import mc.GTedd.cn.gtshaders.codegen.PackParity;
import mc.GTedd.cn.gtshaders.export.ExportTarget;
import mc.GTedd.cn.gtshaders.export.ResourcePackExporter;

import mc.GTedd.cn.gtshaders.i18n.GtLang;
import mc.GTedd.cn.gtshaders.library.CoreLibrary;
import mc.GTedd.cn.gtshaders.library.EffectCatalog;
import mc.GTedd.cn.gtshaders.library.EffectLibrary;
import mc.GTedd.cn.gtshaders.library.SourceDoc;
import mc.GTedd.cn.gtshaders.runtime.AnchorCheck;
import mc.GTedd.cn.gtshaders.runtime.DebugSession;
import mc.GTedd.cn.gtshaders.codegen.DebugInstrument;
import mc.GTedd.cn.gtshaders.runtime.GpuProfiler;
import mc.GTedd.cn.gtshaders.runtime.AnchorRuntime;
import mc.GTedd.cn.gtshaders.runtime.BindMode;
import mc.GTedd.cn.gtshaders.runtime.PackVerify;
import mc.GTedd.cn.gtshaders.runtime.WorldClock;


import mc.GTedd.cn.gtshaders.runtime.GlowRuntime;
import mc.GTedd.cn.gtshaders.runtime.GlslValidator;
import mc.GTedd.cn.gtshaders.runtime.ImportedTextureManager;
import mc.GTedd.cn.gtshaders.runtime.PreviewRuntime;
import mc.GTedd.cn.gtshaders.runtime.VanillaSource;
import mc.GTedd.cn.gtshaders.runtime.VanillaEffects;
import mc.GTedd.cn.gtshaders.workspace.HistoryStore;
import mc.GTedd.cn.gtshaders.workspace.ProjectStore;
import mc.GTedd.cn.gtshaders.workspace.SnapshotStore;
import mc.GTedd.cn.gtshaders.workspace.Workspace;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.DoubleConsumer;

/**
 * 工作台主界面，按 MasterGo 的布局语言组织：
 * <pre>
 *   ┌──────────── 顶栏：文件名 · 工具组 · 缩放/播放/导出 ────────────┐
 *   │ 左栏 ║                                        ║ 右栏         │
 *   │ 图层 ║   画布（透出实时预览 + 效果作用区域框） ║ 设计/管线/   │
 *   │ 资源 ║   ┌ 浮动「Shader 设置」┐               ║ 导出         │
 *   └──────────────────────── 状态栏 ──────────────────────────────┘
 *          ↑ 可拖拽的分隔线                        ↑
 * </pre>
 *
 * <p>三个关键设计决定：
 *
 * <p><b>1. 中央刻意不画任何东西。</b>预览效果作用于整个游戏画面，中间越空越能看清正在调的东西。
 * 也正因如此没有沿用原版的模糊背景——那层模糊会把要看的画面糊掉。
 *
 * <p><b>2. 画布上的取景框是真实的「效果作用区域」，不是装饰。</b>
 * 它对应着色器里的 {@code GTViewport} uniform，框外像素直接输出原画面，
 * 于是拖动它就得到一条实时的 before/after 分界线。右栏的 X/Y/W/H 与它双向绑定——
 * 这正好把 MasterGo 右栏那组变换控件用在了有意义的地方。
 *
 * <p><b>3. 编辑器有自己的缩放，不跟随 Minecraft 的 GUI Scale。</b>
 * 后者档位太粗且通常偏大，而工作台需要更高的信息密度。实现上整体套一层
 * {@code pose()} 矩阵缩放，所有绘制与命中测试都在「逻辑坐标」下进行；
 * 原版的 {@code enableScissor} 会跟随 pose 变换，所以裁剪不会错位。
 */
public class EditorScreen extends Screen {

    private static final int TOP_H = 38;
    private static final int STATUS_H = 22;
    private static final int ROW_H = 22;
    private static final int ICON = 22;
    private static final int PAD = 8;
    /** 折叠后剩下的窄条宽度，点它可以展开。 */
    private static final int COLLAPSED_W = 16;
    /** 分隔线的可拖拽热区宽度。做得比视觉宽度宽，否则很难抓住。 */
    private static final int SPLITTER_HIT = 6;
    /** 停止输入多久之后自动编译。太短会在打字中途反复编译，太长又失去「实时」的感觉。 */
    private static final long AUTOCOMPILE_DELAY_MS = 450;
    /** 色块上按住多久算「长按」，超过就弹出取色浮层并进入连续取色。 */
    private static final long COLOR_LONG_PRESS_MS = 180;
    private static final int MENU_W = 236;
    /**
     * 「滑块 + 数值框」并排所需的最小宽度，单位是<b>屏幕物理像素</b>。低于它就把滑块收起来，
     * 改成点击呼出。
     *
     * <p>数值框固定 44px、间距 6px，剩给滑块的必须还有五十来个像素才拖得准；
     * 再窄下去两个控件就要贴在一起了。
     */
    private static final int PARAM_INLINE_MIN_W = 104;
    /** 呼出的滑条浮层尺寸。做得比行内滑块宽，这本来就是为了「窄面板下也能拖准」而存在的。 */
    private static final int SLIDER_POPUP_W = 168;
    private static final int SLIDER_POPUP_H = 32;
    /**
     * 「导出资源包」二级菜单的宽度。
     *
     * <p>比右栏还宽：里面要放的是一整条绝对路径，而路径这东西看不全就等于没显示——
     * 用户点开它就是为了确认「到底写到哪儿去」。
     */
    private static final int EXPORT_MENU_W = 304;

    /**
     * 内置贴图库：可在「可换图」效果的参数面板里逐张切换。
     *
     * <p>只放本工程自绘的图。拖进编辑器的 PNG 会接在后面一起参与切换，
     * 所以多贴图的效果（按击杀类型换图那种）每一张都能换成玩家自己的素材。
     */
    private static final String[] TEXTURE_LIBRARY = {
            "gtshaders:gui/killicon"
    };

    /**
     * 当前会话的工程，跨 Screen 存活。
     *
     * <p>编辑器每次按 Insert 都是 {@code new EditorScreen(...)}。工程如果跟着 Screen 走，
     * 关掉再打开就是一个全新的空工程，而 {@link #init} 里的 {@code compileNow()} 会把这个
     * 空工程应用上去——正在渲染的效果被一起清掉。<b>打开编辑器只是打开窗口，不该动效果。</b>
     * 工程属于会话，不属于某一次打开的窗口。
     */
    private static @Nullable ShaderProject sessionProject;

    private final @Nullable Screen parent;
    private final LayoutConfig layout = LayoutConfig.load();
    private ShaderProject project;
    private final CodeEditor codeEditor = new CodeEditor();

    /** 逻辑尺寸 = 实际尺寸 / 缩放。所有布局计算都用它。 */
    private int lw;
    private int lh;

    // ---- 即时模式的可点区域 ----
    private final List<Region> regions = new ArrayList<>();
    private @Nullable Region dragTarget;
    /** 当前 region 的裁剪区 {x0,y0,x1,y1}，null 表示不裁剪。 */
    private int @Nullable [] regionClip;

    static final class Region {
        final int x;
        final int y;
        final int w;
        final int h;
        @Nullable Runnable click;
        @Nullable DoubleConsumer drag;

        /** 右键回调。null 表示这块区域没有上下文菜单。 */
        @Nullable Runnable secondary;

        /**
         * 这块区域是某个面板的标签，按住能把面板拖走。
         *
         * <p>没走 {@link #drag} 那条路是因为两者语义完全不同：{@code drag} 收的是
         * 0~1 的归一化位置（滑条用），而拖面板要的是「拖到哪个区」——那得看鼠标的绝对位置，
         * 归一化到标签自己的宽度上毫无意义。
         */
        @Nullable DockPanel panel;

        Region(int x, int y, int w, int h) {
            this.x = x;
            this.y = y;
            this.w = w;
            this.h = h;
        }

        boolean hit(double mx, double my) {
            return mx >= x && mx < x + w && my >= y && my < y + h;
        }
    }

    /** 当前正在进行的拖拽种类。 */
    private enum Drag {
        NONE, PANEL, PANEL_RESIZE, SPLIT_LEFT, SPLIT_RIGHT, SPLIT_BOTTOM,
        VIEWPORT_MOVE, VIEWPORT_EDGE, SLIDER,
        /** 捏着面板标签把它拖去别的停靠区。 */
        DOCK_TAB,
        /** 拖浮动面板的标题栏。拖到边缘会变成重新停靠，所以它也要算落位区。 */
        FLOAT_MOVE,
        /** 拖浮动面板右下角改大小。 */
        FLOAT_RESIZE,
        /** 拖 AI 面板的标题栏。 */
        AI_PANEL,
        /** 画布上的自定义贴图变换框。 */
        ICON_MOVE, ICON_CORNER, ICON_EDGE_X, ICON_EDGE_Y,
        /** 色块已按下、还没判定是短按还是长按。 */
        COLOR_PRESS,
        /** 取色浮层里的连续取色。 */
        COLOR_SCRUB
    }

    private Drag drag = Drag.NONE;
    private int dragOffX;
    private int dragOffY;
    /** 拖取景框边/角时的方向：-1/0/+1。 */
    private int edgeX;
    private int edgeY;

    // ---- 取色 ----
    private final EffectBrowser browser = new EffectBrowser(this);
    private final AiPanel aiPanel = new AiPanel(this);
    /** 顶栏那条缩放滑条。动效状态都在它自己身上，这里只持有一份。 */
    private final ZoomSlider zoomSlider = new ZoomSlider();
    /** 滑条这一帧登记的可拖区域。滚轮命中判定要用它。 */
    private @Nullable Region zoomRegion;
    /**
     * 正在拖缩放滑条。
     *
     * <p>不能拿 {@code dragTarget == zoomRegion} 来判：region 表每帧清空重建，
     * {@code zoomRegion} 每帧都是<b>新对象</b>，而 {@code dragTarget} 指向按下那一帧的旧对象，
     * 两者永远不相等。用一个标志位反而简单可靠。
     */
    private boolean zoomDragging;

    /**
     * AI 正在写的那一层。
     *
     * <p>整个会话复用同一层，而不是每轮生成新加一层：一次生成可能编译失败、重修两三轮，
     * 每轮都加一层的话，玩家最后会看到一叠只有最后一个能用的垃圾图层，
     * 还得自己挨个删掉。复用它，中间过程对玩家来说就只是「同一层在变好」。
     */
    private @Nullable ShaderLayer aiLayer;
    private final ColorPicker colorPicker = new ColorPicker();
    private @Nullable ShaderParam colorPressParam;
    private String colorPressKey = "";
    private long colorPressAt;
    private int colorAnchorX;
    private int colorAnchorY;
    /** 本次点击刚被顺手关掉的取色器 key；用来把「再点一次色块」变成收起而不是重新弹出。 */
    private String pickerClosedKey = "";
    /** 本次点击带的修饰键。region 的回调是无参的，靠这个字段把 Ctrl 之类传进去。 */
    private int clickMods;

    /** 顶栏的菜单浮层是否展开。 */
    private boolean menuOpen;
    private List<HistoryStore.Entry> menuHistory = List.of();
    /** 原版效果清单。扫描要读资源文件，只在展开菜单时做一次。 */
    private int menuScroll;
    /** 效果库里当前展开的那一栏；null 表示全部折起。 */
    /** 核心着色器里当前展开的那一栏。 */
    /** 上一帧量出来的菜单内容高度，用来决定要不要滚动。 */
    private int menuContentH = 320;
    /** 菜单本体的矩形，滚轮命中用。 */
    private int[] menuBounds = {0, 0, 0, 0};
    /**
     * 工程列表的缓存。左栏每帧都要画它，直接列目录等于每帧一次磁盘 I/O；
     * 工程文件又只可能被我们自己改，2 秒的滞后完全无感。
     */
    private List<Path> cachedProjects = List.of();
    private long projectsCachedAt;

    /** 每个停靠区各自的滚动位置。面板搬家后滚动状态跟着区走，而不是跟着面板。 */
    private final java.util.Map<DockZone, Integer> zoneScroll =
            new java.util.EnumMap<>(DockZone.class);
    /** 正在被拖走的面板。null 表示没人在拖。 */
    private @Nullable DockPanel draggingPanel;
    /** 拖动中鼠标悬停的目标区。null 表示当前位置不落任何区（松手就回原位）。 */
    private @Nullable DockZone dropZone;
    /** 正在被移动/缩放的浮动面板。 */
    private @Nullable DockPanel floatTarget;
    private int floatOffX;
    private int floatOffY;
    private int panelScroll;
    private boolean panelSourceTab;

    private boolean autoCompile = true;
    private long lastEditAt;
    private boolean pendingCompile;

    private String toast = "";
    private long toastUntil;
    private boolean toastError;
    private @Nullable String tooltip;
    private int tooltipX;
    private int tooltipY;

    /** 「导出资源包」点开的二级菜单开着没有；它管的是落点——写到哪个目录、叫什么名字。 */
    private boolean exportMenuOpen;
    private int exportMenuX;
    private int exportMenuY;

    /** 窄面板下呼出的数值滑条浮层；null 表示没打开。 */
    private @Nullable String sliderPopupKey;
    private @Nullable ShaderParam sliderPopupParam;
    private int sliderPopupComp;
    private int sliderPopupX;
    private int sliderPopupY;

    private final TextField projectNameField = new TextField(TextField.Kind.TEXT);
    private final TextField layerNameField = new TextField(TextField.Kind.TEXT);
    private final TextField effectIdField = new TextField(TextField.Kind.TEXT);
    private final TextField paramSearchField = new TextField(TextField.Kind.TEXT);
    private final TextField layerSearchField = new TextField(TextField.Kind.TEXT);
    private final TextField vpX = new TextField(TextField.Kind.NUMBER);
    private final TextField vpY = new TextField(TextField.Kind.NUMBER);
    private final TextField vpW = new TextField(TextField.Kind.NUMBER);
    private final TextField vpH = new TextField(TextField.Kind.NUMBER);
    private final TextField commitMessageField = new TextField(TextField.Kind.TEXT);
    private final TextField anchorNameField = new TextField(TextField.Kind.TEXT);
    private final TextField anchorSelectorField = new TextField(TextField.Kind.TEXT);
    /** 调试页：探针表达式、行号、伪彩色范围。 */
    private final TextField probeExprField = new TextField(TextField.Kind.TEXT, 160);
    private final TextField probeLineField = new TextField(TextField.Kind.NUMBER);
    private final TextField rangeLoField = new TextField(TextField.Kind.NUMBER);
    private final TextField rangeHiField = new TextField(TextField.Kind.NUMBER);
    /**
     * 「点画面取样」开着没有。开着时点画布空白处不再取消选中，而是读那个像素——
     * 两件事都发生在同一个手势上，只能二选一，所以做成显式开关。
     */
    private boolean inspectTool;
    /** 本次点击的逻辑坐标。region 的回调是无参的，取样要知道点在哪。 */
    private double clickLx;
    private double clickLy;
    /**
     * 导出落点的两个输入框。
     *
     * <p>长度上限给得比默认的 64 大：目录装的是一整条绝对路径，
     * 「D:\我的世界\.minecraft\resourcepacks」这种一粘就过半了。
     */
    private final TextField exportDirField = new TextField(TextField.Kind.TEXT, 240);
    private final TextField exportNameField = new TextField(TextField.Kind.TEXT, 96);
    private final Map<String, TextField> numberFields = new HashMap<>();
    private final Map<String, TextField> hexFields = new HashMap<>();
    /** 快照条目的重命名输入框，按文件名缓存。 */
    private final Map<String, TextField> snapshotNameFields = new HashMap<>();
    /** 正在被重命名的快照文件名；null 表示没有。 */
    private @Nullable String renamingSnapshot;
    private List<SnapshotStore.Entry> cachedSnapshots = List.of();
    private long snapshotsCachedAt;
    private final Set<String> expandedColors = new LinkedHashSet<>();
    /** 收起来的参数分组。存「收起」而不是「展开」：默认全开，新加的组自然可见。 */
    private final Set<String> collapsedGroups = new HashSet<>();

    /**
     * 多选中的层。存对象而不是下标：删掉一层之后所有下标都会挪位，
     * 而对象引用不会——批量操作正好发生在「刚删了一个」之后。
     */
    private final Set<ShaderLayer> multiSelected = new LinkedHashSet<>();

    /** 右键菜单：位置 + 若干条目。null 表示没打开。 */
    private @Nullable ContextMenu contextMenu;

    private static final class ContextMenu {
        final int x;
        final int y;
        final List<Item> items = new ArrayList<>();

        ContextMenu(int x, int y) {
            this.x = x;
            this.y = y;
        }

        record Item(String label, boolean enabled, boolean separatorBefore, Runnable action) {
        }

        ContextMenu add(String label, boolean enabled, Runnable action) {
            items.add(new Item(label, enabled, false, action));
            return this;
        }

        ContextMenu addSeparated(String label, boolean enabled, Runnable action) {
            items.add(new Item(label, enabled, true, action));
            return this;
        }
    }
    private final List<TextField> allFields = new ArrayList<>();

    private ResourcePackExporter.Options exportOptions =
            ResourcePackExporter.Options.defaults("custom");

    public EditorScreen(@Nullable Screen parent) {
        super(Component.literal("GTShaders"));
        this.parent = parent;
        if (sessionProject == null) {
            // 首次打开：一层空白、停用状态，画面与没装这个 mod 完全一样
            sessionProject = ShaderProject.createDefault();
        }
        this.project = sessionProject;
    }

    /**
     * 本局的当前工程。
     *
     * <p>局内的快捷键要在<b>编辑器关着</b>的时候往工程里加锚点绑定——而「看着一只怪按一下」
     * 正是绑实体最直觉的做法，编辑器一打开鼠标就归界面了，根本没法再用准星去瞄。
     * 所以它必须拿得到和编辑器同一个对象，改完下一帧就生效。
     *
     * @return 还没打开过编辑器时为 null
     */
    public static @Nullable ShaderProject sessionProject() {
        return sessionProject;
    }

    /** 换掉当前工程。必须走这里，否则会话槽不会跟着更新，关掉编辑器再打开就退回旧工程。 */
    private void setProject(ShaderProject next) {
        this.project = next;
        sessionProject = next;
    }

    // ------------------------------------------------------------------ 生命周期

    @Override
    protected void init() {
        // 存盘的 uiScale 可能来自旧版本的档位表，也可能玩家中途改了游戏的 GUI Scale——
        // 两种情况都会让净倍数变成非整数。开屏先吸附到最近的合法档位。
        layout.snapScale(guiScale(), hasVectorFont());
        afterScaleChanged();
        codeEditor.setOnChange(this::onSourceEdited);
        // 补全与悬停要的作者名字：取图层上那份源码与参数表（编辑时 onSourceEdited 已经同步过去了）
        codeEditor.setSymbolSource(() -> {
            ShaderLayer l = project.selected();
            return l == null ? List.of()
                    : mc.GTedd.cn.gtshaders.codegen.GlslSymbols.userSymbols(l.authorSource(), l.params());
        });
        syncCodeEditorFromLayer();
        projectNameField.setOnCommit(() -> project.setName(projectNameField.text()));
        layerNameField.setOnCommit(() -> {
            ShaderLayer l = project.selected();
            if (l != null) {
                l.setName(layerNameField.text());
            }
        });
        effectIdField.setOnCommit(() ->
                exportOptions = exportOptions.withEffectId(effectIdField.text()));
        effectIdField.setTextIfUnfocused(exportOptions.effectId());
        // 导出落点是上次存盘的那一份：把目录指到 resourcepacks/ 属于一次性设置，
        // 每次打开编辑器重敲一遍绝对路径没人受得了
        exportOptions = exportOptions.withDir(layout.exportDir).withFileName(layout.exportName);
        exportDirField.setOnCommit(() ->
                exportOptions = exportOptions.withDir(exportDirField.text()));
        exportNameField.setOnCommit(() ->
                exportOptions = exportOptions.withFileName(exportNameField.text()));
        exportDirField.setTextIfUnfocused(exportOptions.dir());
        exportNameField.setTextIfUnfocused(exportOptions.fileName());
        // 挂载位置跟着导出面板的「触发方式」走：预览要与导出物在列表里占同一个位置
        PreviewRuntime.setMountAsEndOfFrame(
                exportOptions.mode() == ResourcePackExporter.Mode.END_OF_FRAME);
        bindViewportFields();
        bindAnchorFields();
        registerField(projectNameField, layerNameField, effectIdField,
                paramSearchField, layerSearchField, commitMessageField, vpX, vpY, vpW, vpH,
                anchorNameField, anchorSelectorField, exportDirField, exportNameField,
                probeExprField, probeLineField, rangeLoField, rangeHiField);
        rangeLoField.setOnCommit(() -> {
            Float v = rangeLoField.asFloat();
            if (v != null) {
                DebugSession.setRange(v, DebugSession.rangeHi());
            }
        });
        rangeHiField.setOnCommit(() -> {
            Float v = rangeHiField.asFloat();
            if (v != null) {
                DebugSession.setRange(DebugSession.rangeLo(), v);
            }
        });
        // 运行时已经挂着效果（我们自己的链，或玩家直接挂的原版效果）就什么都不做：
        // 重编一遍没有收益，在主菜单里还会弹一句「需要世界」的无意义报错。
        // 只有确实没东西在跑时才编译一次，让状态栏与错误标记有内容可显示。
        if (!PreviewRuntime.hasEffect()) {
            compileNow();
        }
    }

    /**
     * 当前正在编辑的取景框——就是选中层的那个。
     *
     * <p>没有选中层时返回工程上那个「默认框」：它不参与渲染，只是给输入框一个不会 NPE 的
     * 落脚处，同时充当新层的初始值。
     */
    private ViewportRect activeViewport() {
        ShaderLayer layer = project.selected();
        return layer == null ? project.viewport() : layer.viewport();
    }

    private void bindViewportFields() {
        // 四个输入框以百分比呈现：像素值会随窗口尺寸变化，百分比才是这个框真正的语义。
        // 每次提交时现取当前框，不能在这里捕获一个：框是跟着选中层走的，
        // 捕获一次的话切了层还在改上一层的框
        vpX.setOnCommit(() -> applyViewportField(vpX, val -> {
            ViewportRect v = activeViewport();
            v.set(val / 100f, v.y0(), val / 100f + v.width(), v.y1());
        }));
        vpY.setOnCommit(() -> applyViewportField(vpY, val -> {
            ViewportRect v = activeViewport();
            v.set(v.x0(), val / 100f, v.x1(), val / 100f + v.height());
        }));
        vpW.setOnCommit(() -> applyViewportField(vpW, val -> {
            ViewportRect v = activeViewport();
            v.set(v.x0(), v.y0(), v.x0() + val / 100f, v.y1());
        }));
        vpH.setOnCommit(() -> applyViewportField(vpH, val -> {
            ViewportRect v = activeViewport();
            v.set(v.x0(), v.y0(), v.x1(), v.y0() + val / 100f);
        }));
    }

    /**
     * 锚点那两个输入框只在提交时写回。
     *
     * <p>不逐字符写回是有原因的：选择器边打边生效的话，打 {@code minecraft:zombie} 的过程中会
     * 依次匹配到 {@code minecraft:z}、{@code minecraft:zo}……全都匹配不到任何实体，
     * 于是锚点在打字过程中一直是灭的，看着像配错了。
     */
    private void bindAnchorFields() {
        anchorNameField.setOnCommit(() -> {
            AnchorBinding b = project.selectedAnchor();
            if (b != null) {
                b.setName(anchorNameField.text());
            }
        });
        anchorSelectorField.setOnCommit(() -> {
            AnchorBinding b = project.selectedAnchor();
            if (b != null) {
                b.setSelector(anchorSelectorField.text());
            }
        });
    }

    private void applyViewportField(TextField f, java.util.function.Consumer<Float> apply) {
        Float val = f.asFloat();
        if (val != null) {
            apply.accept(val);
        }
    }

    void registerField(TextField... fields) {
        for (TextField f : fields) {
            if (!allFields.contains(f)) {
                allFields.add(f);
            }
        }
    }

    @Override
    public void tick() {
        if (autoCompile && pendingCompile
                && System.currentTimeMillis() - lastEditAt > AUTOCOMPILE_DELAY_MS) {
            compileNow();
        }
    }

    @Override
    public boolean isPauseScreen() {
        // 必须为 false：暂停了世界就不再推进，预览里的动画会停住
        return false;
    }

    @Override
    public void onClose() {
        // 不取消的话，那条虚拟线程会抱着一个已经没人看的界面把整轮生成跑完，
        // 还会在结束时往一个关掉了的面板上写状态
        aiPanel.abort();
        // 调试视图是编辑器里的临时状态：插着桩的画面、截断的链都不能留在游戏里
        DebugSession.onEditorClosed(project);
        GpuProfiler.setEnabled(false);
        inspectTool = false;
        layout.save();
        // 每个字号是一张字形图集，留在显存里没有意义——下次打开会按当时的缩放重新烘焙
        UiFont.get().releaseBaked();
        if (this.minecraft != null) {
            this.minecraft.gui.setScreen(parent);
        }
    }

    // ------------------------------------------------------------------ 布局换算

    /** 浏览器等同包组件要用到字体与逻辑尺寸；{@code font} 是 Screen 的 protected 成员，转一手。 */
    net.minecraft.client.gui.Font font() {
        return this.font;
    }

    int logicalWidth() {
        return lw;
    }

    int logicalHeight() {
        return lh;
    }

    private float scale() {
        return layout.renderScale();
    }

    /**
     * Minecraft 当前的 GUI Scale。缩放档位要靠它算——屏幕上的实际放大倍数是两者之积，
     * 只有那个积是整数，位图字体才不会糊。
     */
    private double guiScale() {
        return this.minecraft == null ? 1.0 : this.minecraft.getWindow().getGuiScale();
    }

    /**
     * 编辑器整体在屏幕上的实际放大倍数。代码区的缩放档位要以它为基准再算一层——
     * 那一层是套在整体缩放之内的第三层放大。
     *
     * <p><b>不取整</b>：界面用上矢量字体后这个值可以是 1.4，而代码区仍然烧原版位图字体，
     * 它那一层的整数约束得按真实基准算，取整会算出一组反而让代码区发虚的档位。
     */
    private float baseNet() {
        return Math.max(0.1f, (float) guiScale() * layout.renderScale());
    }

    /**
     * 改整体缩放。
     *
     * <p>必须连带吸附代码区的缩放：那一层的合法档位是以 {@link #baseNet()} 为基准算的，
     * 外层一变，原本合法的 zoom 就可能变成非整数倍。所有入口（滑条拖动、滑条上滚轮、
     * 两个快捷键、右键重置）都要经过 {@link #afterScaleChanged()}，
     * 漏掉任何一个都会让代码区悄悄糊掉。
     */
    private void changeScale(int delta) {
        layout.cycleScale(delta, guiScale(), hasVectorFont());
        afterScaleChanged();
    }

    private void resetScale() {
        layout.resetScale(guiScale(), hasVectorFont());
        afterScaleChanged();
    }

    /**
     * 缩放变了之后要收的两个尾巴：代码区的档位以整体倍数为基准，得跟着吸附；
     * 新缩放对应的字号还没烘焙过，趁现在（不在渲染帧里）把它备好。
     */
    private void afterScaleChanged() {
        codeEditor.snapZoom(baseNet());
        UiFont.get().prepare(layout.renderScale(), Math.max(1, (int) Math.round(guiScale())));
    }

    /**
     * 有没有矢量字体可用。
     *
     * <p>问 {@link UiFont} 而不是 {@code UiText.isVector()}：后者只在渲染帧里才有意义，
     * 而这个判断在 {@code init()} 里就要用。
     */
    private boolean hasVectorFont() {
        return UiFont.get().isAvailable();
    }

    /** 左栏实际占多宽。面板被全部拖走之后这一栏就不存在了，画布顺势占回去。 */
    private int leftW() {
        if (layout.dock.isEmpty(DockZone.LEFT)) {
            return 0;
        }
        return layout.leftCollapsed ? COLLAPSED_W : layout.leftWidth;
    }

    private int rightW() {
        if (layout.dock.isEmpty(DockZone.RIGHT)) {
            return 0;
        }
        return layout.rightCollapsed ? COLLAPSED_W : layout.rightWidth;
    }

    /** 底部停靠区的高度。没有面板停在那儿就是 0。 */
    private int bottomH() {
        return layout.dock.isEmpty(DockZone.BOTTOM) ? 0 : layout.bottomHeight;
    }

    private int statusTop() {
        return lh - (layout.statusVisible ? STATUS_H : 0);
    }

    /**
     * 画布的底边。
     *
     * <p>和 {@link #statusTop()} 的区别是它还要给底部停靠区让位。画布相关的计算一律用它，
     * 用 {@code statusTop()} 会让预览画到底部面板下面去。
     */
    private int canvasBottom() {
        return statusTop() - bottomH();
    }

    /**
     * 鼠标此刻悬在哪个停靠区上。不在任何区上返回 null。
     *
     * <p>只认真正显示着的区：折叠的栏和空的区都不该吃掉滚轮，
     * 否则在折叠条上滚一下会莫名其妙地滚动一块看不见的内容。
     */
    private @Nullable DockZone zoneAt(double mx, double my) {
        for (DockPanel p : layout.dock.panelsIn(DockZone.FLOATING)) {
            int[] r = layout.dock.floatRect(p);
            if (r != null && mx >= r[0] && mx < r[0] + r[2] && my >= r[1] && my < r[1] + r[3]) {
                return DockZone.FLOATING;
            }
        }
        if (my >= TOP_H && my < canvasBottom()) {
            if (leftW() > 0 && !layout.leftCollapsed && mx < leftW()) {
                return DockZone.LEFT;
            }
            if (rightW() > 0 && !layout.rightCollapsed && mx >= lw - rightW()) {
                return DockZone.RIGHT;
            }
        }
        if (bottomH() > 0 && my >= canvasBottom() && my < statusTop()
                && mx >= leftW() && mx < lw - rightW()) {
            return DockZone.BOTTOM;
        }
        return null;
    }

    /** 滚一个停靠区。四个区共用一份滚动状态表，省得每加一个区就多两个字段。 */
    private void scrollZone(DockZone zone, double sy) {
        int cur = zoneScroll.getOrDefault(zone, 0);
        zoneScroll.put(zone, Math.max(0, cur - (int) Math.signum(sy) * 20));
    }

    private double lx(double screenX) {
        return screenX / scale();
    }

    private double ly(double screenY) {
        return screenY / scale();
    }

    // ------------------------------------------------------------------ 动作

    private void syncCodeEditorFromLayer() {
        ShaderLayer layer = project.selected();
        codeEditor.setText(layer == null ? "" : layer.authorSource());
    }

    /**
     * 参数被拖动 / 键入 / 取色之后。
     *
     * <p>后处理层什么都不用做——它的参数是 uniform，运行时每帧现取现写，本来就是实时的。
     *
     * <p><b>核心着色器层不一样</b>：它的参数编译成 {@code const} 写死在源码里，
     * 不重新生成就永远是旧值。以前只能手动按一次「立即同步」，于是拖了半天没反应，
     * 看起来就像「参数调整无效」。这里复用自动编译那套去抖：拖动过程中不编译，
     * 松手几百毫秒后自动同步一次——既不会拖一下卡一下，也不用记着去按按钮。
     */
    private void onParamEdited() {
        ShaderLayer layer = project.selected();
        if (layer != null && layer.isCore()) {
            lastEditAt = System.currentTimeMillis();
            pendingCompile = true;
        }
    }

    private void onSourceEdited() {
        ShaderLayer layer = project.selected();
        if (layer != null) {
            layer.setAuthorSource(codeEditor.getText());
        }
        lastEditAt = System.currentTimeMillis();
        pendingCompile = true;
    }

    private void compileNow() {
        compileNowResult();
    }

    /**
     * 同 {@link #compileNow()}，但把编译结果交回来——AI 的自动修错要靠它拿到错误行。
     *
     * @return null 表示这一次并没有真的编译（没有可编译的层，或者不在世界里）
     */
    private GlslValidator.@Nullable Result compileNowResult() {
        if (DebugSession.isBusy()) {
            // 正在逐帧取样：这时重建链会让读数读到一半的画面。等取样完再编
            pendingCompile = true;
            return null;
        }
        pendingCompile = false;
        // 核心着色器和后处理走两条独立的路径，各自跟随。
        // 先做核心：它清空管线缓存，而后处理那条链的编译要在缓存清空<b>之后</b>发生，
        // 顺序反了的话刚建好的链会被立刻作废，白编译一次。
        syncCoreShaders();

        // 一个效果都没启用时不需要世界、也不需要编译，直接把效果从渲染器上摘掉。
        // 放在世界检查之前：在主菜单里点「清除全部效果」也该真的生效
        if (project.enabledPostLayers().isEmpty()) {
            PreviewRuntime.clear();
            codeEditor.setErrorLines(Set.of());
            return null;
        }
        if (this.minecraft == null || this.minecraft.level == null) {
            showToast(GtLang.get("gtshaders.status.need_world"), true);
            return null;
        }
        // 真实加载验证期间画面上跑的是资源包本身，预览链不能再挂回去盖在上面。
        // 静默跳过：自动编译会跟着每次敲键触发，弹提示只会刷屏；状态栏一直写着验证中
        if (PackVerify.isActive() || PackVerify.isBusy()) {
            return null;
        }
        GlslValidator.Result result = DebugSession.isActive()
                ? DebugSession.recompile(project)
                : PreviewRuntime.applyProject(project);
        Set<Integer> errorLines = new HashSet<>();
        Map<Integer, String> errorMessages = new HashMap<>();
        for (GlslValidator.Issue issue : result.issues()) {
            if (issue.error() && issue.line() > 0) {
                errorLines.add(issue.line());
                errorMessages.merge(issue.line(), issue.message(), (a, b) -> a + "\n" + b);
            }
        }
        codeEditor.setErrorLines(errorLines);
        codeEditor.setErrorMessages(errorMessages);
        // AI 会话期间不弹失败提示：那些失败是自动修错的正常中间态，会连着弹三四条把屏幕刷满，
        // 而玩家真正该看的进度就在 AI 面板上写着
        if (!result.ok() && aiLayer == null) {
            showToast(GtLang.get("gtshaders.status.failed") + " · " + result.firstErrorMessage(), true);
        }
        return result;
    }

    private void selectLayer(int index) {
        project.setSelectedIndex(index);
        syncCodeEditorFromLayer();
        panelScroll = 0;
        // 浮层锚在上一层的某一行上，换层之后那行已经不在了，留着只会指向错的参数
        closeSliderPopup();
    }

    private void addLayer() {
        project.addLayer();
        syncCodeEditorFromLayer();
        showToast(GtLang.get("gtshaders.status.layer_added"), false);
        compileNow();
    }

    /**
     * 点图层行。Ctrl 加选、普通点击独占选中。
     *
     * <p>Ctrl 加选存在的意义是批量：选好几层之后一次性删掉 / 启停 / 复制，
     * 不用一层层重复同一个动作。
     */
    private void clickLayerRow(int index, ShaderLayer layer) {
        if ((clickMods & InputConstants.MOD_CONTROL) != 0) {
            if (!multiSelected.remove(layer)) {
                multiSelected.add(layer);
            }
            // 加选的同时也把它设成当前编辑的层，否则「选了但面板没跟过来」很怪
            selectLayer(index);
            return;
        }
        multiSelected.clear();
        selectLayer(index);
    }

    /** 参与批量操作的层：有多选就用多选，没有就是当前这一层。 */
    private List<ShaderLayer> batchTargets(ShaderLayer fallback) {
        return multiSelected.isEmpty() ? List.of(fallback) : List.copyOf(multiSelected);
    }

    private void openLayerMenu(int mx, int my, int index, ShaderLayer layer) {
        List<ShaderLayer> targets = batchTargets(layer);
        int n = targets.size();
        boolean many = n > 1;
        openContextMenu(mx, my, menu -> {
            menu.add(many ? GtLang.get("gtshaders.ctx.enable_n", n)
                            : GtLang.get(layer.isEnabled() ? "gtshaders.ctx.disable" : "gtshaders.ctx.enable"),
                    true, () -> {
                        boolean turnOn = many || !layer.isEnabled();
                        for (ShaderLayer t : targets) {
                            t.setEnabled(many ? turnOn : !t.isEnabled());
                        }
                        compileNow();
                    });
            menu.add(GtLang.get("gtshaders.ctx.duplicate"), true, () -> {
                for (ShaderLayer t : targets) {
                    project.addLayer(t.copy(t.name() + " copy"));
                }
                multiSelected.clear();
                syncCodeEditorFromLayer();
                compileNow();
            });
            menu.add(GtLang.get("gtshaders.ctx.rename"), !many, () -> {
                focusPanel(DockPanel.DESIGN);
                layout.rightCollapsed = false;
                blurAllExcept(layerNameField);
                layerNameField.focus();
            });
            menu.addSeparated(GtLang.get("gtshaders.ctx.move_up"), !many && index < project.layers().size() - 1,
                    () -> moveLayer(1));
            menu.add(GtLang.get("gtshaders.ctx.move_down"), !many && index > 0, () -> moveLayer(-1));
            menu.addSeparated(many ? GtLang.get("gtshaders.ctx.delete_n", n)
                            : GtLang.get("gtshaders.ctx.delete"), true,
                    () -> {
                        for (ShaderLayer t : targets) {
                            project.removeLayer(t);
                        }
                        multiSelected.clear();
                        syncCodeEditorFromLayer();
                        showToast(GtLang.get("gtshaders.status.layer_removed"), false);
                        compileNow();
                    });
        });
    }

    private void duplicateLayer() {
        if (project.duplicateSelected() != null) {
            syncCodeEditorFromLayer();
            compileNow();
        }
    }

    private void deleteLayer() {
        if (project.removeSelected()) {
            syncCodeEditorFromLayer();
            showToast(GtLang.get("gtshaders.status.layer_removed"), false);
            compileNow();
        }
    }

    /** 新建：回到「一层空白、且没有任何效果生效」的初始状态。 */
    private void newProject() {
        setProject(ShaderProject.createDefault());
        projectNameField.setTextIfUnfocused(project.name());
        bindViewportFields();
        syncCodeEditorFromLayer();
        colorPicker.close();
        showToast(GtLang.get("gtshaders.status.new_project"), false);
        compileNow();
    }

    /**
     * 清除全部效果：把所有层停用，源码原样留着。
     *
     * <p>刻意不删层——「先看看关掉是什么样」是调效果时最频繁的动作，
     * 真删了就得重写，代价和收益完全不对等。要彻底清空走「新建」。
     */
    private void clearEffects() {
        project.disableAllLayers();
        showToast(GtLang.get("gtshaders.status.cleared"), false);
        compileNow();
    }

    /**
     * 插入示例模板。
     *
     * <p>只写源码、<b>不替作者勾选启用</b>——插入模板和「让它立刻作用到画面上」是两件事，
     * 后者必须是玩家自己按下的。
     */
    /**
     * 直接挂上一个原版效果。不编译、不改工程——整条链完全走原版路径，
     * 等价于在 26.3 里敲 {@code /posteffect add @s <id>}。
     */
    void applyVanilla(VanillaEffects.Entry entry) {
        if (this.minecraft != null && this.minecraft.level == null) {
            showToast(GtLang.get("gtshaders.status.need_world"), true);
            return;
        }
        VanillaEffects.apply(entry.id());
        showToast(GtLang.get("gtshaders.status.vanilla_applied", entry.label()), false);
    }

    /** 把一个原版效果导入成可编辑工程：每个通道一层，uniform 变成面板上的滑块。 */
    void importVanilla(VanillaEffects.Entry entry) {
        try {
            setProject(VanillaEffects.importProject(entry.id()));
            projectNameField.setTextIfUnfocused(project.name());
            bindViewportFields();
            syncCodeEditorFromLayer();
            colorPicker.close();
            panelSourceTab = false;
            layout.panelVisible = true;
            showToast(GtLang.get("gtshaders.status.vanilla_imported",
                    entry.label(), project.layerCount()), false);
            compileNow();
        } catch (Exception e) {
            GTShaders.LOGGER.error("导入原版效果失败", e);
            showToast(GtLang.get("gtshaders.status.vanilla_import_failed",
                    String.valueOf(e.getMessage())), true);
        }
    }

    void applySample(Samples.Sample sample) {
        ShaderLayer layer = project.selected();
        if (layer == null) {
            layer = project.addLayer();
        }
        layer.setName(GtLang.get(sample.nameKey()));
        layer.setAuthorSource(sample.source());
        syncCodeEditorFromLayer();
        panelSourceTab = true;
        layout.panelVisible = true;
        showToast(GtLang.get("gtshaders.status.sample_inserted"), false);
        compileNow();
    }

    /**
     * 从内置效果库插入一个效果。
     *
     * <p>和示例模板的区别在于<b>直接启用</b>：库里的东西是成品，挑它就是想立刻看到画面，
     * 而示例是拿来读的范本，插进去之后通常还要改。
     */
    void applyLibraryEffect(EffectLibrary.Entry entry) {
        String source = EffectLibrary.loadSource(entry.id());
        if (source == null) {
            showToast(GtLang.get("gtshaders.status.library_missing", entry.id()), true);
            return;
        }
        ShaderLayer layer = new ShaderLayer(entry.displayName(), source);
        layer.setOutline(entry.outline());
        layer.setEnabled(true);
        project.addLayer(layer);
        if (entry.outline() && !GlowRuntime.anyGlowingVisible()) {
            // 轮廓链只在有实体发光时才被引擎执行。挑了个逐实体效果却什么都没发生，
            // 十有八九就是这个原因——与其让人自己去猜，不如当场说清楚
            showToast(GtLang.get("gtshaders.outline.need_glowing"), true);
        }
        syncCodeEditorFromLayer();
        panelSourceTab = false;
        layout.panelVisible = true;
        showToast(GtLang.get("gtshaders.status.library_inserted", entry.displayName()), false);
        compileNow();
    }

    /**
     * 把文件拖进窗口即可导入。
     *
     * <p>这一条省掉的不只是「打开文件、全选、复制、粘贴」四步——更重要的是
     * {@code shaderlib/} 里那些<b>已经是 26.3 成品</b>的 .fsh 可以直接拖回来接着改，
     * 于是「发布的着色器」和「在编辑的着色器」终于是同一份文件。
     *
     * <p>一次拖多个文件时，每个着色器各占一层，按拖入顺序叠上去。
     */
    @Override
    public void onFilesDrop(List<Path> files) {
        if (files == null || files.isEmpty()) {
            return;
        }
        int imported = 0;
        int failed = 0;
        String lastName = "";
        for (Path file : files) {
            String result = importDroppedFile(file);
            if (result == null) {
                failed++;
            } else {
                imported++;
                lastName = result;
            }
        }
        if (imported > 0) {
            syncCodeEditorFromLayer();
            layout.panelVisible = true;
            compileNow();
            showToast(imported == 1
                    ? GtLang.get("gtshaders.status.dropped_one", lastName)
                    : GtLang.get("gtshaders.status.dropped_many", imported), false);
        }
        if (failed > 0 && imported == 0) {
            showToast(GtLang.get("gtshaders.status.dropped_none"), true);
        }
    }

    /** 导入拖入的 PNG 作为当前可换图效果的自定义贴图。 */
    private @Nullable String importDroppedTexture(Path file) {
        ShaderLayer layer = project.selected();
        if (layer == null || layer.textures().isEmpty()) {
            return null;
        }
        try {
            Identifier id = ImportedTextureManager.importPng(file);
            ShaderTexture tex = layer.textures().get(0);
            String updated = replaceTexturePath(layer.authorSource(), tex.samplerName(), id.toString());
            if (updated == null) {
                return null;
            }
            layer.setAuthorSource(updated);
            syncCodeEditorFromLayer();
            compileNow();
            return id.toString();
        } catch (Exception e) {
            GTShaders.LOGGER.warn("导入贴图失败：{}", file, e);
            return null;
        }
    }

    /**
     * 导入一个拖进来的文件。
     *
     * @return 导入成功时返回它的显示名；不认识或读失败时返回 null
     */
    private @Nullable String importDroppedFile(Path file) {
        String fileName = file.getFileName() == null ? "" : file.getFileName().toString();
        try {
            if (!Files.isRegularFile(file)) {
                return null;
            }
            String text = Files.readString(file, StandardCharsets.UTF_8);

            // 工程文件优先：它整包替换当前工程，语义和"加一层"完全不同，不能认错
            if (fileName.toLowerCase(Locale.ROOT).endsWith(".json") && text.contains("\"layers\"")) {
                JsonObject root = JsonParser.parseString(text).getAsJsonObject();
                setProject(ProjectStore.fromJson(root, projectNameFromFile(fileName)));
                projectNameField.setTextIfUnfocused(project.name());
                bindViewportFields();
                colorPicker.close();
                return project.name();
            }

            // 自定义贴图：拖入 PNG 时，如果当前选中的是可换图效果，就把图片导入并替换第一张贴图。
            if (fileName.toLowerCase(Locale.ROOT).endsWith(".png")) {
                return importDroppedTexture(file);
            }

            if (!ShaderImport.looksLikeShaderFile(fileName)) {
                return null;
            }
            ShaderImport.Result result = ShaderImport.read(text, fileName);
            if (result == null) {
                return null;
            }
            ShaderLayer layer = new ShaderLayer(result.name(), result.authorSource());
            if (result.coreKindId() != null) {
                // 核心着色器<b>不自动启用</b>：它覆盖原版文件，一启用就会改变游戏里所有同类对象，
                // 那必须是玩家自己按下的决定。后处理层拖进来就看效果，两者语义不同
                layer.setKindId(result.coreKindId());
            } else {
                layer.setEnabled(true);
            }
            project.addLayer(layer);
            return result.name();
        } catch (Exception e) {
            GTShaders.LOGGER.warn("拖入的文件读不了：{}", fileName, e);
            return null;
        }
    }

    /** 从文件名推一个工程名，去掉 {@code .gtshader.json} 这类复合后缀。 */
    private static String projectNameFromFile(String fileName) {
        String n = fileName;
        while (n.contains(".")) {
            int dot = n.lastIndexOf('.');
            if (dot <= 0) {
                break;
            }
            n = n.substring(0, dot);
        }
        return n.isBlank() ? "untitled" : n;
    }

    /**
     * 加一个覆盖原版核心着色器的层。
     *
     * <p>刻意<b>不自动启用</b>：核心着色器是覆盖原版文件，一启用就会改变游戏里所有同类对象的样子，
     * 那必须是玩家自己按下的决定。这一点和效果库不同——库里是成品，挑了就是想看效果。
     */
    void addCoreLayer(ShaderKind.Entry kind) {
        project.addCoreLayer(kind);
        syncCodeEditorFromLayer();
        panelSourceTab = true;
        layout.panelVisible = true;
        showToast(GtLang.get("gtshaders.status.core_added", kind.displayName()), false);
    }

    /** 从核心示例库插入一个现成效果。同样不自动启用，理由见 {@link #addCoreLayer}。 */
    /**
     * 效果库浏览器选中一条后落地。
     *
     * <p>派发集中在这里而不是散在浏览器里：浏览器只认「目录里的一条」，不该知道后处理层、
     * 核心着色器层、原版效果三者在工程模型里的差别——那是这边的事。
     */
    void applyCatalogItem(EffectCatalog.Item item) {
        switch (item.kind()) {
            case POST -> {
                if (item.payload() instanceof EffectLibrary.Entry e) {
                    applyLibraryEffect(e);
                }
            }
            case TEMPLATE -> {
                if (item.payload() instanceof Samples.Sample sample) {
                    applySample(sample);
                }
            }
            case CORE_BLANK -> {
                if (item.payload() instanceof ShaderKind.Entry k) {
                    addCoreLayer(k);
                }
            }
            case CORE_EXAMPLE -> {
                if (item.payload() instanceof CoreLibrary.Entry ex) {
                    ShaderKind.Entry k = ShaderKind.find(ex.kindId());
                    if (k != null) {
                        addCoreExample(k, ex);
                    }
                }
            }
            case VANILLA -> {
                if (item.payload() instanceof VanillaEffects.Entry e) {
                    if (e.applicable()) {
                        applyVanilla(e);
                    } else {
                        showToast(String.valueOf(e.reason()), true);
                    }
                }
            }
        }
    }

    void addCoreExample(ShaderKind.Entry kind, CoreLibrary.Entry example) {
        String source = CoreLibrary.loadSource(example.id());
        if (source == null) {
            showToast(GtLang.get("gtshaders.status.library_missing", example.id()), true);
            return;
        }
        ShaderLayer layer = project.addCoreLayer(kind);
        layer.setName(example.displayName());
        layer.setAuthorSource(source);
        syncCodeEditorFromLayer();
        panelSourceTab = false;
        layout.panelVisible = true;
        showToast(GtLang.get("gtshaders.status.core_added", example.displayName()), false);
    }

    /**
     * 让被接管的核心着色器跟上当前工程。
     *
     * <p>跟着自动编译一起跑，所以核心着色器和后处理一样是<b>实时预览</b>的。
     * 代价是每次都要清空管线缓存、让全部管线重编一次，会有一小下卡顿——
     * 比资源重载便宜得多，但仍然比后处理贵，所以「一个核心层都没有」时必须早退，
     * 否则纯后处理的用户会白白承担这笔开销。
     */
    private void syncCoreShaders() {
        boolean want = !project.enabledCoreLayers().isEmpty();
        if (!want) {
            PreviewRuntime.clearCore();
            return;
        }
        try {
            int n = PreviewRuntime.applyCore(project, VanillaSource.runtimeProfile());
            if (n == 0) {
                showToast(CoreShaderExporter.missingAssetsHint(VanillaSource.runtimeProfile()), true);
            }
        } catch (Exception e) {
            GTShaders.LOGGER.error("接管核心着色器失败", e);
            showToast(GtLang.get("gtshaders.status.core_failed", String.valueOf(e.getMessage())), true);
        }
    }

    /** 菜单里的显式动作：立刻同步一次，并给一句反馈。 */
    private void applyCoreShaders() {
        if (project.enabledCoreLayers().isEmpty()) {
            showToast(GtLang.get("gtshaders.status.no_core"), true);
            return;
        }
        syncCoreShaders();
        if (PreviewRuntime.hasCore()) {
            showToast(GtLang.get("gtshaders.status.core_applied", PreviewRuntime.coreCount()), false);
        }
    }

    private void restoreCoreShaders() {
        PreviewRuntime.clearCore();
        showToast(GtLang.get("gtshaders.status.core_restored"), false);
    }

    private void moveLayer(int delta) {
        if (project.moveSelected(delta)) {
            compileNow();
        }
    }

    private void saveProject() {
        try {
            Path file = ProjectStore.save(project);
            HistoryStore.record(project.name(), file, System.currentTimeMillis());
            projectsCachedAt = 0L;
            menuHistory = HistoryStore.loadExisting();
            showToast(GtLang.get("gtshaders.status.saved", file.getFileName().toString()), false);
        } catch (Exception e) {
            GTShaders.LOGGER.error("保存工程失败", e);
            showToast(GtLang.get("gtshaders.status.save_failed", String.valueOf(e.getMessage())), true);
        }
    }

    private void loadProject(Path file) {
        try {
            setProject(ProjectStore.load(file));
            projectNameField.setTextIfUnfocused(project.name());
            bindViewportFields();
            syncCodeEditorFromLayer();
            colorPicker.close();
            HistoryStore.record(project.name(), file, System.currentTimeMillis());
            showToast(GtLang.get("gtshaders.status.loaded", ProjectStore.displayName(file)), false);
            compileNow();
        } catch (Exception e) {
            GTShaders.LOGGER.error("载入工程失败", e);
            showToast(GtLang.get("gtshaders.status.load_failed", String.valueOf(e.getMessage())), true);
        }
    }

    private void exportPack() {
        exportPackFiles();
    }

    /**
     * 打开「导出资源包」的二级菜单。
     *
     * <p>三个入口（顶栏按钮、左上菜单、右栏导出页的按钮）都走这里，不再一点就直接写文件：
     * 导出是<b>产出一个要发给别人的文件</b>，在按下去之前看一眼它叫什么、落在哪，
     * 比事后去满硬盘找那个 zip 便宜得多。落点记在 layout.json 里，所以这一步通常只是确认。
     *
     * @param anchorX 浮层左上角期望落点，通常取触发它的那个控件下方；超出屏幕会被夹回来
     */
    private void openExportMenu(int anchorX, int anchorY) {
        exportMenuOpen = true;
        menuOpen = false;
        // 这几层浮层都要盖住半个屏幕，留着的话第一次点击只会把它们收掉
        closeSliderPopup();
        colorPicker.close();
        exportMenuX = anchorX;
        exportMenuY = anchorY;
        exportDirField.setTextIfUnfocused(exportOptions.dir());
        exportNameField.setTextIfUnfocused(exportOptions.fileName());
    }

    /** 收起二级菜单。收之前把两个输入框的内容落实到选项并存盘——它们是「设置」，不是一次性输入。 */
    private void closeExportMenu() {
        applyExportTarget();
        exportMenuOpen = false;
    }

    /**
     * 把两个输入框的当前内容落到 {@link #exportOptions} 并存盘。
     *
     * <p>先 blur 再读：输入框正在编辑时 onCommit 还没跑，直接读 exportOptions 拿到的是上一次的值——
     * 于是「敲完路径直接点导出」会导出到旧路径上，而画面上明明写着新的。
     */
    private void applyExportTarget() {
        blurAllExcept(null);
        exportOptions = exportOptions
                .withDir(exportDirField.text())
                .withFileName(exportNameField.text());
        if (!layout.exportDir.equals(exportOptions.dir())
                || !layout.exportName.equals(exportOptions.fileName())) {
            layout.exportDir = exportOptions.dir();
            layout.exportName = exportOptions.fileName();
            layout.save();
        }
    }

    /**
     * 核心着色器包的文件名。
     *
     * <p>自定义名叫「我的包」，它就叫「我的包_core」：两个包必然落在同一个目录里，
     * 名字得能一眼看出是一对，也不能撞在一起。没自定义就返回空串，交给导出器按工程名取默认。
     */
    private static String coreFileName(String custom) {
        return custom.isBlank() ? "" : custom + "_core";
    }

    /**
     * 导出，并把写出来的 zip 按「后处理包在前、核心着色器包在后」的顺序交回来。
     * 真实加载验证要拿它们去装；普通导出只看 toast。
     *
     * @return 空表示没导出成功（没效果或出错，已经弹过提示）
     */
    private List<Path> exportPackFiles() {
        List<Path> files = new ArrayList<>();
        if (!project.hasEffect()) {
            // 导出一个空链只会得到一个什么都不做的资源包，不如当场说清楚
            showToast(GtLang.get("gtshaders.status.no_effect"), true);
            return files;
        }
        Path exportDir;
        try {
            exportDir = ExportTarget.directory(exportOptions.dir(), Workspace.exportDir());
        } catch (InvalidPathException e) {
            // 路径压根解析不了（Windows 上的 a:b 之类）。这时候别去试写文件——
            // 抛出来的会是一句看不懂的 InvalidPathException，指不出是哪一栏填错了
            showToast(GtLang.get("gtshaders.export.bad_dir", exportOptions.dir()), true);
            return files;
        }
        try {
            StringBuilder done = new StringBuilder();
            // 后处理和核心着色器是两种不同的落地方式，产出两个独立的包：
            // 前者是新增一个自定义 post effect（26.3 还能用指令开关），
            // 后者是覆盖原版文件（启用即生效）。混在一个包里会让人搞不清哪部分该怎么触发。
            if (!project.enabledPostLayers().isEmpty()) {
                // 给的是<b>默认</b>目录而不是上面解析好的那个：自定义目录写在 options 里，
                // 由导出器自己解析。传解析后的进去，相对路径「我的包」会被再解析一次，
                // 落成 export/我的包/我的包
                ResourcePackExporter.Result r =
                        ResourcePackExporter.export(project, Workspace.exportDir(), exportOptions);
                files.add(r.file());
                done.append(GtLang.get("gtshaders.export.done", r.file().getFileName().toString()))
                        .append(" · ").append(r.usageHint());
                if (!r.notes().isEmpty()) {
                    // 有 mod 专属输入的效果导出后会退化，当场点明，细节在包里的 README 与导出面板
                    done.append(" · ").append(GtLang.get("gtshaders.export.parity_count", r.notes().size()));
                }
            }
            List<ShaderLayer> coreLayers = project.enabledCoreLayers();
            if (!coreLayers.isEmpty()) {
                List<CoreShaderExporter.Job> jobs = new ArrayList<>();
                for (ShaderLayer l : coreLayers) {
                    ShaderKind.Entry kind = l.kind();
                    if (kind != null) {
                        jobs.add(new CoreShaderExporter.Job(kind, l.authorSource(),
                                project.exportProfile()));
                    }
                }
                if (!jobs.isEmpty()) {
                    CoreShaderExporter.Result r = CoreShaderExporter.export(
                            project.name(), jobs, exportDir, coreFileName(exportOptions.fileName()));
                    files.add(r.file());
                    if (!done.isEmpty()) {
                        done.append('\n');
                    }
                    done.append(GtLang.get("gtshaders.export.done",
                            r.file().getFileName().toString()));
                    if (!r.missing().isEmpty()) {
                        // 缺模板时不能装作导出成功——那会让用户拿着一个少了文件的包去排查
                        done.append(" · ")
                                .append(CoreShaderExporter.missingAssetsHint(project.exportProfile()));
                    }
                }
            }
            if (!exportOptions.dir().isBlank() && !done.isEmpty()) {
                // 自定义目录下只报文件名等于什么都没说：用户要确认的恰恰是「有没有落到我指的地方」
                done.append('\n')
                        .append(GtLang.get("gtshaders.export.into_dir", exportDir.toString()));
            }
            showToast(done.toString(), false);
        } catch (Exception e) {
            GTShaders.LOGGER.error("导出资源包失败", e);
            showToast(GtLang.get("gtshaders.export.failed", String.valueOf(e.getMessage())), true);
            files.clear();
        }
        return files;
    }

    /**
     * 导出，然后把包当成普通资源包装进游戏、走原版重载、按触发方式挂上。
     *
     * <p>这是「预览与真实加载无异」的最后一道验证：预览链绕过了资源包加载，
     * 而格式区间、贴图路径、着色器引用这些错误只在加载那一刻出现。
     * 装的是刚导出的那份 zip，不是内存里的东西——和玩家拿到的是同一个文件。
     */
    private void verifyPack() {
        if (PackVerify.isBusy()) {
            return;
        }
        if (this.minecraft == null || this.minecraft.level == null) {
            showToast(GtLang.get("gtshaders.status.need_world"), true);
            return;
        }
        List<Path> files = exportPackFiles();
        if (files.isEmpty()) {
            return;
        }
        Identifier effect = null;
        if (exportOptions.mode() == ResourcePackExporter.Mode.POST_EFFECT_COMMAND
                && !project.enabledPostLayers().isEmpty()) {
            effect = Identifier.fromNamespaceAndPath(exportOptions.namespace(),
                    ResourcePackExporter.sanitize(exportOptions.effectId()));
        }
        Identifier finalEffect = effect;
        PackVerify.start(files, effect).whenComplete((v, err) -> {
            if (err != null) {
                showToast(GtLang.get("gtshaders.verify.failed", String.valueOf(err.getMessage())), true);
            } else {
                showToast(GtLang.get("gtshaders.verify.running",
                        finalEffect == null ? "end_of_frame" : finalEffect.toString()), false);
            }
        });
    }

    /** 撤掉验证包、重载，然后把工程重新编译挂回预览。 */
    private void stopVerify() {
        if (PackVerify.isBusy()) {
            return;
        }
        PackVerify.stop().whenComplete((v, err) -> {
            if (err != null) {
                showToast(GtLang.get("gtshaders.verify.failed", String.valueOf(err.getMessage())), true);
            } else {
                showToast(GtLang.get("gtshaders.verify.stopped"), false);
            }
            compileNow();
        });
    }

    private void reloadLanguages() {
        List<String> loaded = GtLang.reloadExternal(Workspace.langDir());
        showToast(GtLang.get("gtshaders.status.lang_reloaded",
                loaded.isEmpty() ? GtLang.get("gtshaders.status.lang_none") : String.join(", ", loaded)), false);
    }

    void showToast(String message, boolean error) {
        this.toast = message;
        this.toastError = error;
        this.toastUntil = System.currentTimeMillis() + 5000;
    }

    // ------------------------------------------------------------------ 绘制

    /** 不画背景：编辑器要浮在正在运行的效果之上，遮住画面就看不到预览了。 */
    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
        float s = scale();
        lw = (int) Math.ceil(this.width / s);
        lh = (int) Math.ceil(this.height / s);
        int mx = (int) (mouseX / s);
        int my = (int) (mouseY / s);

        regions.clear();
        tooltip = null;
        DebugSession.pump(project);
        // 这一帧的文字用哪套字体、按什么倍数抵消缩放，在这里定一次。
        // 代码区不参与——它直接调 g.text 走原版位图字体，因为 GLSL 要靠等宽对齐，
        // 而系统 UI 字体（雅黑、苹方）几乎都是变宽的。
        UiText.begin(this.font, layout.renderScale(), Math.max(1, (int) Math.round(guiScale())));
        promoteLongPressToScrub(mx, my);

        g.pose().pushMatrix();
        g.pose().scale(s, s);

        renderCanvas(g, mx, my);
        renderTopBar(g, mx, my);
        renderLeftPanel(g, mx, my);
        renderRightPanel(g, mx, my);
        renderBottomDock(g, mx, my);
        renderSplitters(g, mx, my);
        renderFloatingDocks(g, mx, my);
        renderDockDragOverlay(g, mx, my);
        if (layout.panelVisible) {
            renderFloatingPanel(g, mx, my);
        }
        if (layout.statusVisible) {
            renderStatusBar(g, mx, my);
        }
        // 浮层压在最上面：菜单和取色器都要盖住面板，而 region 表是反向扫的，
        // 最后登记的自然优先命中
        if (menuOpen) {
            renderMenu(g, mx, my);
        }
        if (browser.isOpen()) {
            browser.render(g, lw, lh, mx, my);
        }
        if (aiPanel.isOpen()) {
            aiPanel.render(g, lw, lh, mx, my);
        }
        if (sliderPopupOpen()) {
            renderSliderPopup(g, mx, my);
        }
        if (exportMenuOpen) {
            renderExportMenu(g, mx, my);
        }
        colorPicker.render(g, this.font, mx, my);
        // 右键菜单画在最后：它是当下最该被看见、也最该先接到点击的东西
        renderContextMenu(g, mx, my);

        g.pose().popMatrix();

        // 提示气泡由原版延迟到独立时机绘制，那时我们这层 pose 缩放矩阵早已弹出，
        // 所以它收到的坐标会被当成屏幕坐标用。传逻辑坐标进去，气泡就会随缩放比例偏移
        // ——缩放 100% 时正好对上，一旦缩放就漂走。这里换算回屏幕坐标。
        if (tooltip != null) {
            g.setTooltipForNextFrame(this.font, Component.literal(tooltip),
                    Math.round(tooltipX * s), Math.round(tooltipY * s));
        }
    }

    // ---- 顶栏 ----

    private void renderTopBar(GuiGraphicsExtractor g, int mx, int my) {
        g.fill(0, 0, lw, TOP_H, Theme.PANEL);
        UiUtil.hLine(g, 0, lw, TOP_H - 1, Theme.BORDER);

        int y = (TOP_H - ICON) / 2;
        int x = PAD;

        iconButton(g, x, y, Icons::layers, GtLang.get("gtshaders.topbar.menu"),
                menuOpen, this::toggleMenu, mx, my);
        x += ICON + 6;
        String workspace = GtLang.get("gtshaders.topbar.workspace");
        UiUtil.textLeft(g, this.font, workspace, x, y + 7, Theme.TEXT_DIM);
        x += UiText.width(workspace) + 6;
        UiUtil.textLeft(g, this.font, "/", x, y + 7, Theme.TEXT_DIM);
        x += 8;
        projectNameField.setTextIfUnfocused(project.name());
        int nameW = 110;
        projectNameField.render(g, this.font, x, y, nameW, ICON, hovered(mx, my, x, y, nameW, ICON), false);
        addFieldRegion(projectNameField, x, y, nameW, ICON);
        x += nameW + 10;

        // 中：工具组，做成一整块圆角底衬，与 MasterGo 的居中工具条一致
        int toolCount = 9;
        int groupW = toolCount * (ICON + 2) + 12;
        int gx = (lw - groupW) / 2;

        // 效果库入口。带文字（而不是混进中间那排图标）是因为第一次打开编辑器的人
        // 只需要找到这一个按钮；但样式跟其它控件一致——常驻按钮不该一直用强调色喊人。
        // 宽度不够就退化成纯图标：宁可少一行字，也不能压到中间的工具组上。
        String libLabel = GtLang.get("gtshaders.topbar.library");
        int libW = UiText.width(libLabel) + ICON + 10;
        if (x + libW < gx - 10) {
            boolean libHot = hovered(mx, my, x, y, libW, ICON);
            UiUtil.roundRect(g, x, y, libW, ICON, 5,
                    libHot ? Theme.BUTTON_HOVER : Theme.PANEL_ALT);
            UiUtil.box(g, x, y, libW, ICON, Theme.BORDER);
            Icons.grid(g, x + 5, y + 4, ICON - 8, Theme.TEXT_SUB);
            UiUtil.textLeft(g, this.font, libLabel, x + ICON, y + (ICON - this.font.lineHeight) / 2 + 1,
                    Theme.TEXT_SUB);
            addRegion(x, y, libW, ICON, this::openBrowser, null);
            if (libHot) {
                setTooltip(GtLang.get("gtshaders.topbar.library_hint"), mx, my);
            }
        } else if (x + ICON < gx - 6) {
            iconButton(g, x, y, Icons::grid, GtLang.get("gtshaders.topbar.library_hint"),
                    browser.isOpen(), this::openBrowser, mx, my);
        }
        // AI 生成紧挨着效果库：两者是同一件事的两条路——从现成的里挑，或者让它写一个新的
        int aiX = x + (x + libW < gx - 10 ? libW : ICON) + 4;
        if (aiX + ICON < gx - 6) {
            iconButton(g, aiX, y, Icons::sparkle, GtLang.get("gtshaders.ai.open_hint"),
                    aiPanel.isOpen(), this::openAiPanel, mx, my);
        }
        UiUtil.roundRect(g, gx, y - 2, groupW, ICON + 4, 6, Theme.PANEL_ALT);
        UiUtil.box(g, gx, y - 2, groupW, ICON + 4, Theme.BORDER);
        int tx = gx + 6;
        iconButton(g, tx, y, Icons::cursor, GtLang.get("gtshaders.tool.select"), true, () -> {
        }, mx, my);
        tx += ICON + 2;
        iconButton(g, tx, y, Icons::plus, GtLang.get("gtshaders.tool.add_layer"), false, this::addLayer, mx, my);
        tx += ICON + 2;
        iconButton(g, tx, y, Icons::duplicate, GtLang.get("gtshaders.tool.duplicate_layer"), false,
                this::duplicateLayer, mx, my);
        tx += ICON + 2;
        iconButton(g, tx, y, Icons::trash, GtLang.get("gtshaders.tool.delete_layer"), false,
                this::deleteLayer, mx, my);
        tx += ICON + 2;
        iconButton(g, tx, y, Icons::arrowUp, GtLang.get("gtshaders.tool.move_up"), false,
                () -> moveLayer(-1), mx, my);
        tx += ICON + 2;
        iconButton(g, tx, y, Icons::arrowDown, GtLang.get("gtshaders.tool.move_down"), false,
                () -> moveLayer(1), mx, my);
        tx += ICON + 2;
        iconButton(g, tx, y, Icons::refresh, GtLang.get("gtshaders.tool.compile"), false,
                this::compileNow, mx, my);
        tx += ICON + 2;
        iconButton(g, tx, y, Icons::reset, GtLang.get("gtshaders.tool.autocompile"), autoCompile,
                () -> autoCompile = !autoCompile, mx, my);
        tx += ICON + 2;
        iconButton(g, tx, y, Icons::frame, GtLang.get("gtshaders.tool.toggle_panel"), layout.panelVisible,
                () -> layout.panelVisible = !layout.panelVisible, mx, my);

        // 右：缩放 / 播放 / 语言 / 保存 / 导出
        int rx = lw - PAD;
        // 比原来的加减号宽一截：滑条要摊开全部档位才有意义，太窄了刻度会挤成一团
        rx -= 108;
        zoomControl(g, rx, y, 108, ICON, mx, my);
        rx -= ICON + 6;
        boolean playing = PreviewRuntime.isPlaying();
        iconButton(g, rx, y, playing ? Icons::pause : Icons::play,
                GtLang.get(playing ? "gtshaders.tool.pause" : "gtshaders.tool.play"), playing,
                () -> {
                    if (!PreviewRuntime.togglePlaying()) {
                        // 资源包视角下时钟归原版管，没有暂停这回事——说清楚，别让按钮像是坏了
                        showToast(GtLang.get("gtshaders.status.pack_view_clock"), false);
                    }
                }, mx, my);
        rx -= ICON + 2;
        iconButton(g, rx, y, Icons::globe, GtLang.get("gtshaders.tool.reload_lang"), false,
                this::reloadLanguages, mx, my);
        rx -= ICON + 2;
        iconButton(g, rx, y, Icons::save, GtLang.get("gtshaders.tool.save"), false, this::saveProject, mx, my);
        rx -= 72;
        int exportX = rx;
        primaryButton(g, rx, y, 68, ICON, GtLang.get("gtshaders.tool.export"),
                () -> openExportMenu(exportX + 68 - EXPORT_MENU_W, TOP_H + 4), mx, my);
    }

    /**
     * 缩放控件：一条带刻度的滑条 + 百分比。
     *
     * <p>档位不是随便给的：每一档都保证 {@code guiScale × BASE_SCALE × uiScale} 是整数，
     * 字体才不会糊，所以档位数量随游戏的 GUI Scale 变化。滑条上的刻度点就是那些档位——
     * 有几个点就有几档，一眼可数，不必再靠悬停提示去解释「为什么只有三档」。
     *
     * <p>拖动、点轨道任意位置、滚轮都能改；右键回到 100%。
     * 视觉与动效在 {@link ZoomSlider} 里，这里只管取值、登记交互和落盘。
     */
    private void zoomControl(GuiGraphicsExtractor g, int x, int y, int w, int h, int mx, int my) {
        float[] steps = layout.scaleSteps(guiScale(), hasVectorFont());
        int index = layout.currentStepIndex(guiScale(), hasVectorFont());
        boolean hover = hovered(mx, my, x, y, w, h);
        boolean dragging = zoomDragging && drag == Drag.SLIDER;

        int[] hit = zoomSlider.render(g, this.font, x, y, w, h, steps, index,
                layout.scaleLabel(), hover, dragging);

        zoomRegion = addRegion(hit[0], hit[1], hit[2], hit[3], null, t -> {
            zoomDragging = true;
            applyScaleAt(t);
        });
        zoomRegion.secondary = this::resetScale;
        if (hover) {
            // 先说怎么用——滑条能拖能滚能右键重置，这三件事都不是自明的。
            // 再说为什么只有这些档，而两种字体下的原因完全不同，得说不同的话
            UiFont f = UiFont.get();
            String why = f.isAvailable()
                    ? GtLang.get("gtshaders.view.zoom_hint_vector", f.sourceName())
                    : GtLang.get("gtshaders.view.zoom_hint",
                            layout.scaleDetail(guiScale()),
                            layout.scaleSteps(guiScale(), false).length);
            setTooltip(GtLang.get("gtshaders.view.zoom_slider") + "  ·  " + why, mx, my);
        }
    }

    /**
     * 滑条拖到了归一化位置 t。
     *
     * <p>拖动时这个方法每帧都会被调到，所以<b>必须</b>先问档位有没有真的变——
     * {@link #afterScaleChanged()} 会重新烘焙字号，无脑调用会在一次拖动里烘焙上百次。
     */
    private void applyScaleAt(double t) {
        if (layout.setScaleAt(t, guiScale(), hasVectorFont())) {
            afterScaleChanged();
        }
    }

    // ---- 菜单浮层 ----

    /**
     * 打开效果库。
     *
     * <p>浏览器画在最上层，但取色器和数值滑条是<b>在它之后</b>绘制的——留着的话会浮在
     * 浏览器上面，点一下还先命中它们。这三样都是浮层，同屏并存没有意义，直接收掉。
     */
    private void openBrowser() {
        if (browser.isOpen()) {
            browser.close();
            return;
        }
        menuOpen = false;
        closeSliderPopup();
        colorPicker.close();
        aiPanel.close();
        browser.openNow();
    }

    /** 打开 AI 生成面板。和效果库互斥——两个浮层都占着屏幕中央，同屏并存没有意义。 */
    private void openAiPanel() {
        if (aiPanel.isOpen()) {
            aiPanel.close();
            return;
        }
        menuOpen = false;
        closeSliderPopup();
        colorPicker.close();
        browser.close();
        aiPanel.openNow();
    }

    // ------------------------------------------------------------------ AI 会话

    /**
     * 开一次 AI 会话。
     *
     * @return false 表示当下根本编译不了（不在世界里）。这时候要在发请求<b>之前</b>就告诉玩家：
     *         等模型写完十几秒再说「没法预览」，那十几秒和那笔 token 都是白花的
     */
    boolean beginAiSession() {
        if (this.minecraft == null || this.minecraft.level == null) {
            return false;
        }
        aiLayer = null;
        return true;
    }

    /**
     * 编译一版 AI 产出并把它留在画面上。
     *
     * <p><b>由后台线程调用，会一直阻塞到渲染线程做完。</b>编译要碰 GL 上下文，只能在主线程做；
     * 而调用方在等网络，本来就在后台。这里是两条线程唯一的交汇点。
     *
     * @return 空列表表示编译通过，否则是可以直接回喂给模型的错误行
     */
    List<String> compileAiCandidate(String source) {
        net.minecraft.client.Minecraft mc = this.minecraft;
        if (mc == null) {
            throw new IllegalStateException("client gone");
        }
        java.util.concurrent.CompletableFuture<List<String>> done =
                new java.util.concurrent.CompletableFuture<>();
        mc.execute(() -> {
            try {
                done.complete(applyAiCandidate(source));
            } catch (Throwable t) {
                done.completeExceptionally(t);
            }
        });
        try {
            // 主线程正常几毫秒就做完了。给足余量是为了「玩家把窗口最小化」这类情况，
            // 但不能不设上限——等不到就永远挂着，那条虚拟线程再也不会回来
            return done.get(30, java.util.concurrent.TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("compile interrupted", e);
        } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException e) {
            throw new IllegalStateException("compile did not finish", e);
        }
    }

    /** 只在渲染线程上跑。 */
    private List<String> applyAiCandidate(String source) {
        ShaderLayer layer = aiLayer;
        if (layer == null || !project.layers().contains(layer)) {
            layer = new ShaderLayer(GtLang.get("gtshaders.ai.layer_name"), source);
            layer.setEnabled(true);
            project.addLayer(layer);
            aiLayer = layer;
        } else {
            layer.setAuthorSource(source);
        }
        // 选中它：右栏的参数、代码编辑器、取景框都跟着选中层走，
        // 不选中的话玩家看得到画面变了却找不到是哪一层在变
        int index = project.layers().indexOf(layer);
        if (index >= 0) {
            project.setSelectedIndex(index);
        }
        syncCodeEditorFromLayer();
        layout.panelVisible = true;

        GlslValidator.Result result = compileNowResult();
        if (result == null || result.ok()) {
            return List.of();
        }
        List<String> errors = new ArrayList<>();
        for (GlslValidator.Issue issue : result.issues()) {
            if (issue.error()) {
                errors.add(issue.line() > 0
                        ? GtLang.get("gtshaders.status.line", issue.line(), issue.message())
                        : issue.message());
            }
        }
        if (errors.isEmpty()) {
            // 只有警告却没编译过：把原始日志交出去，总比递一个空列表说「没错」强
            errors.add(result.firstErrorMessage());
        }
        return errors;
    }

    /** 生成成功。此时那一层已经在工程里、也已经在画面上了，这里只做命名和收尾。 */
    void finishAiSession(String source, List<String> notes) {
        ShaderLayer layer = aiLayer;
        if (layer != null) {
            // 层名取源码头部注释里的标题——作者写的名字永远比「AI 生成」有信息量
            String title = mc.GTedd.cn.gtshaders.library.SourceDoc.parse(source).localTitle();
            if (!title.isBlank()) {
                layer.setName(title);
            }
        }
        aiLayer = null;
        showToast(GtLang.get("gtshaders.ai.done"), false);
        if (notes.contains("reverted")) {
            // 最后一轮改坏了、退回了上一个能用的版本。不说的话玩家会以为要求没被采纳
            showToast(GtLang.get("gtshaders.ai.reverted"), true);
        }
        for (String note : notes) {
            // 修错轮数用完了、坑还留着。这时候更要说一声：这类效果编译得过、跑得动，
            // 就是看不出变化，玩家不会往「写法有问题」上想
            if (note.startsWith("pitfall:")) {
                showToast(GtLang.get(mc.GTedd.cn.gtshaders.ai.PitfallCheck.langKeyFor(
                        note.substring("pitfall:".length()))), true);
            }
        }
    }

    /** 会话中止：失败、取消，或者玩家直接关掉了编辑器。 */
    void abortAiSession() {
        aiLayer = null;
    }

    /** 展开菜单时才读一次历史与原版效果清单：菜单每帧重绘，在里面读文件等于每帧一次磁盘 I/O。 */
    private void toggleMenu() {
        menuOpen = !menuOpen;
        if (menuOpen) {
            menuScroll = 0;
            menuHistory = HistoryStore.loadExisting();
            // 菜单要盖住整屏，留着滑条浮层的话第一次点击只会把它关掉
            closeSliderPopup();
        }
    }

    /** 工程列表：带 2 秒缓存，避免左栏每帧列一次目录。 */
    private List<Path> projects() {
        long now = System.currentTimeMillis();
        if (now - projectsCachedAt > 2000L) {
            cachedProjects = ProjectStore.listProjects();
            projectsCachedAt = now;
        }
        return cachedProjects;
    }

    /**
     * 顶栏菜单：工程管理 · 最近使用 · 原版效果 · 示例模板 · 清除效果。
     *
     * <p>做成浮层而不是塞进左栏，是因为这几件事都是「偶尔做一次、做完就走」的操作，
     * 常驻在栏里只会挤掉真正天天要用的图层与参数。
     *
     * <p>内容可滚动而不是按窗口高度裁剪条目：原版效果的数量取决于装了什么资源包，
     * 事先按固定条数排版迟早会有装不下的一天。
     */
    private void renderMenu(GuiGraphicsExtractor g, int mx, int my) {
        long now = System.currentTimeMillis();

        int mxs = PAD;
        int mys = TOP_H + 2;
        int available = Math.max(80, statusTop() - mys - 4);
        int h = Math.min(menuContentH + 12, available);
        menuScroll = Math.max(0, Math.min(menuScroll, Math.max(0, menuContentH + 12 - h)));

        // 浮层之外点一下即收起。铺在菜单本体之前登记，所以永远被本体压住
        addRegion(0, 0, lw, lh, () -> menuOpen = false, null);

        UiUtil.dropShadow(g, mxs, mys, MENU_W, h, 4);
        UiUtil.roundRect(g, mxs, mys, MENU_W, h, 6, Theme.PANEL);
        UiUtil.box(g, mxs, mys, MENU_W, h, Theme.BORDER_STRONG);
        menuBounds = new int[]{mxs, mys, MENU_W, h};

        g.enableScissor(mxs + 1, mys + 1, mxs + MENU_W - 1, mys + h - 1);
        clipRegions(mxs + 1, mys + 1, mxs + MENU_W - 1, mys + h - 1);
        int top = mys + 6 - menuScroll;
        int y = top;

        y = menuItem(g, mxs, y, GtLang.get("gtshaders.menu.new"), "Ctrl+N", true, this::newProject, mx, my);
        y = menuItem(g, mxs, y, GtLang.get("gtshaders.menu.save"), "Ctrl+S", true, this::saveProject, mx, my);
        final int exportItemY = y;
        y = menuItem(g, mxs, y, GtLang.get("gtshaders.menu.export"), "", project.hasEffect(),
                () -> openExportMenu(mxs + MENU_W + 4, exportItemY), mx, my);

        y = menuSection(g, mxs, y, GtLang.get("gtshaders.menu.recent"));
        if (menuHistory.isEmpty()) {
            UiUtil.textLeft(g, this.font, GtLang.get("gtshaders.menu.recent_empty"),
                    mxs + 14, y + 5, Theme.TEXT_DIM);
            y += ROW_H;
        } else {
            for (HistoryStore.Entry e : menuHistory) {
                y = menuItem(g, mxs, y, e.name(), HistoryStore.relativeTime(e.usedAt(), now), true,
                        () -> {
                            menuOpen = false;
                            loadProject(e.file());
                        }, mx, my);
            }
        }

        // 效果库、核心着色器、起手模板、原版效果统统搬进了浏览器。这里只留一个入口：
        // 同一批东西在两个地方都能点到，只会让人先犹豫「这两个是不是不一样」。
        y = menuSection(g, mxs, y, "");
        y = menuItem(g, mxs, y, GtLang.get("gtshaders.menu.browse"), "Ctrl+L", true, () -> {
            menuOpen = false;
            openBrowser();
        }, mx, my);
        // 面板拖乱了总得有条退路：拖走容易，一个一个拖回去很烦
        y = menuItem(g, mxs, y, GtLang.get("gtshaders.dock.reset"), "", true, () -> {
            menuOpen = false;
            layout.dock.reset();
            layout.leftCollapsed = false;
            layout.rightCollapsed = false;
            zoneScroll.clear();
            layout.save();
        }, mx, my);

        y = menuSection(g, mxs, y, "");
        // 核心着色器的接管/还原都要重载资源，所以做成两个显式动作而不是自动跟随
        y = menuItem(g, mxs, y, GtLang.get("gtshaders.menu.core_apply"), "",
                !project.enabledCoreLayers().isEmpty(), () -> {
                    menuOpen = false;
                    applyCoreShaders();
                }, mx, my);
        y = menuItem(g, mxs, y, GtLang.get("gtshaders.menu.core_restore"), "",
                PreviewRuntime.hasCore(), () -> {
                    menuOpen = false;
                    restoreCoreShaders();
                }, mx, my);
        y = menuItem(g, mxs, y, GtLang.get("gtshaders.menu.clear_effects"), "", project.hasEffect(),
                () -> {
                    menuOpen = false;
                    clearEffects();
                }, mx, my);
        y = menuItem(g, mxs, y, GtLang.get("gtshaders.tool.reload_lang"), "", true, () -> {
            menuOpen = false;
            reloadLanguages();
        }, mx, my);
        y = menuItem(g, mxs, y, GtLang.get("gtshaders.menu.close"), "Esc", true, this::onClose, mx, my);
        g.disableScissor();
        unclipRegions();

        // 下一帧才用得上，但一帧的滞后在这里完全看不出来
        menuContentH = y - top;

        if (menuContentH + 12 > h) {
            int trackX = mxs + MENU_W - 4;
            int barH = Math.max(16, h * h / (menuContentH + 12));
            int maxScroll = menuContentH + 12 - h;
            int barY = mys + (h - barH) * menuScroll / Math.max(1, maxScroll);
            g.fill(trackX, barY, trackX + 3, barY + barH, Theme.SCROLL_THUMB);
        }
    }

    private int menuSection(GuiGraphicsExtractor g, int x, int y, String title) {
        y += 4;
        UiUtil.hLine(g, x + 8, x + MENU_W - 8, y, Theme.BORDER);
        y += 5;
        if (!title.isEmpty()) {
            UiUtil.textLeft(g, this.font, title, x + 10, y, Theme.TEXT_DIM);
            y += this.font.lineHeight + 3;
        }
        return y;
    }

    private int menuItem(GuiGraphicsExtractor g, int x, int y, String label, String hint,
                         boolean enabled, Runnable action, int mx, int my) {
        boolean hover = enabled && hovered(mx, my, x + 4, y, MENU_W - 8, ROW_H);
        if (hover) {
            UiUtil.roundRect(g, x + 4, y, MENU_W - 8, ROW_H, 4, Theme.BUTTON_HOVER);
        }
        int hintW = hint.isEmpty() ? 0 : UiText.width(hint) + 8;
        UiUtil.textLeft(g, this.font,
                UiUtil.ellipsize(this.font, label, MENU_W - 24 - hintW), x + 10,
                y + (ROW_H - this.font.lineHeight) / 2 + 1, enabled ? Theme.TEXT : Theme.TEXT_DIM);
        if (!hint.isEmpty()) {
            UiUtil.textRight(g, this.font, hint, x + MENU_W - 10,
                    y + (ROW_H - this.font.lineHeight) / 2 + 1, Theme.TEXT_DIM);
        }
        if (enabled) {
            addRegion(x + 4, y, MENU_W - 8, ROW_H, action, null);
        } else {
            // 不可用的项也要吃掉点击，否则会穿透到下面那层「点外面收起菜单」的区域
            addRegion(x + 4, y, MENU_W - 8, ROW_H, () -> {
            }, null);
        }
        return y + ROW_H;
    }

    // ---- 分隔线 ----

    private void renderSplitters(GuiGraphicsExtractor g, int mx, int my) {
        int top = TOP_H;
        // 竖线贯穿到状态栏：左右栏本来就是通到底的，底部区夹在它们中间
        int bottom = statusTop();

        // 区是空的（面板全被拖走了）就没有边界可拖，画一条能抓的线反而莫名其妙
        int lx = leftW();
        if (lx > 0) {
            boolean lHover = hovered(mx, my, lx - SPLITTER_HIT / 2, top, SPLITTER_HIT, bottom - top)
                    || drag == Drag.SPLIT_LEFT;
            UiUtil.vLine(g, lx - 1, top, bottom, lHover ? Theme.ACCENT : Theme.BORDER);
            addRegion(lx - SPLITTER_HIT / 2, top, SPLITTER_HIT, bottom - top, null, null);
        }

        int rightWidth = rightW();
        if (rightWidth > 0) {
            int rx = lw - rightWidth;
            boolean rHover = hovered(mx, my, rx - SPLITTER_HIT / 2, top, SPLITTER_HIT, bottom - top)
                    || drag == Drag.SPLIT_RIGHT;
            UiUtil.vLine(g, rx, top, bottom, rHover ? Theme.ACCENT : Theme.BORDER);
            addRegion(rx - SPLITTER_HIT / 2, top, SPLITTER_HIT, bottom - top, null, null);
        }

        // 底部停靠区的上边界。横向只跨左右栏之间那一段，和两条竖线正好围成画布
        if (bottomH() > 0) {
            int by = canvasBottom();
            int bx0 = leftW();
            int bx1 = lw - rightW();
            boolean bHover = hovered(mx, my, bx0, by - SPLITTER_HIT / 2,
                    bx1 - bx0, SPLITTER_HIT) || drag == Drag.SPLIT_BOTTOM;
            UiUtil.hLine(g, bx0, bx1, by, bHover ? Theme.ACCENT : Theme.BORDER);
            addRegion(bx0, by - SPLITTER_HIT / 2, bx1 - bx0, SPLITTER_HIT, null, null);
        }
    }

    // ---- 左栏 ----

    private void renderLeftPanel(GuiGraphicsExtractor g, int mx, int my) {
        int w = leftW();
        if (w <= 0) {
            return;
        }
        if (layout.leftCollapsed) {
            g.fill(0, TOP_H, w, statusTop(), Theme.PANEL);
            collapsedStrip(g, 0, TOP_H, statusTop(), true,
                    () -> layout.leftCollapsed = false, mx, my);
            return;
        }
        // 高度用 statusTop 而不是 canvasBottom：底部停靠区夹在<b>左右栏之间</b>，
        // 两侧的栏是贯穿到底的。用 canvasBottom 会让底部区下方那两截露出空白
        renderDockZone(g, DockZone.LEFT, 0, TOP_H, w, statusTop() - TOP_H, mx, my);
    }

    /**
     * 画一个停靠区：底 + 可拖的标签行 + 当前面板的内容。
     *
     * <p>三个停靠区走的是同一段代码，所以「面板搬到哪都长一个样」是结构上保证的，
     * 而不是靠三处实现各自对齐。
     *
     * <p>内容统一套 scissor 与滚动：左栏那三个面板原本靠 {@code bottom} 自己截断行数，
     * 搬进滚动坐标系后 {@code bottom} 要跟着加上滚动量，否则内容一上移就被提前截断，
     * 表现是「往下滚就什么都没有了」。
     */
    private void renderDockZone(GuiGraphicsExtractor g, DockZone zone,
                                int x, int y, int w, int h, int mx, int my) {
        List<DockPanel> panels = layout.dock.panelsIn(zone);
        if (panels.isEmpty() || w <= 0 || h <= 0) {
            return;
        }
        int bottom = y + h;
        g.fill(x, y, x + w, bottom, Theme.PANEL);

        int contentTop = dockTabRow(g, zone, panels, x, y + 6, w, mx, my);
        if (zone != DockZone.BOTTOM) {
            // 折叠按钮放在标签行右端，和 MasterGo 面板右上角的收起入口位置一致
            iconButton(g, x + w - ICON - 4, y + 6, Icons::minus,
                    GtLang.get("gtshaders.view.collapse"), false,
                    () -> setCollapsed(zone, true), mx, my);
        }

        DockPanel active = layout.dock.active(zone);
        if (active == null || contentTop >= bottom) {
            return;
        }
        int scroll = zoneScroll.getOrDefault(zone, 0);
        g.enableScissor(x, contentTop, x + w, bottom);
        clipRegions(x, contentTop, x + w, bottom);
        renderPanelBody(g, active, x, contentTop - scroll, bottom + scroll, w, mx, my);
        g.disableScissor();
        unclipRegions();
    }

    /** 把面板内容分发到原来那七个渲染方法。 */
    private void renderPanelBody(GuiGraphicsExtractor g, DockPanel panel,
                                 int x, int y, int bottom, int w, int mx, int my) {
        switch (panel) {
            case LAYERS -> renderLayerList(g, x, y, bottom, w, mx, my);
            case ASSETS -> renderAssetList(g, x, y, bottom, w, mx, my);
            case VERSIONS -> renderSnapshotList(g, x, y, bottom, w, mx, my);
            case DESIGN -> renderDesignTab(g, x, y, w, mx, my);
            case ANCHOR -> renderAnchorTab(g, x, y, w, mx, my);
            case PIPELINE -> renderPipelineTab(g, x, y, w, mx, my);
            case EXPORT -> renderExportTab(g, x, y, w, mx, my);
            case DEBUG -> renderDebugTab(g, x, y, w, mx, my);
        }
    }

    /**
     * 可拖走的标签行。
     *
     * <p>和普通 {@link #tabRow} 的区别只有一处：每个标签登记的 region 上挂了
     * {@link Region#panel}，于是按住它就是「捏住这个面板」而不是「点一下切换」。
     * 切换在按下的瞬间就发生，拖不拖得动是后话——这样单击的手感和以前完全一样。
     *
     * @return 内容区的顶边
     */
    private int dockTabRow(GuiGraphicsExtractor g, DockZone zone, List<DockPanel> panels,
                           int x, int y, int w, int mx, int my) {
        DockPanel active = layout.dock.active(zone);
        int tx = x + PAD;
        int rowH = this.font.lineHeight + 10;
        for (DockPanel p : panels) {
            String label = p.title();
            int tw = UiText.width(label) + 12;
            boolean sel = p == active;
            boolean lifted = p == draggingPanel;
            UiUtil.textLeft(g, this.font, label, tx + 6, y + 4,
                    lifted ? Theme.TEXT_DIM : (sel ? Theme.TEXT : Theme.TEXT_DIM));
            if (sel && !lifted) {
                // 选中态用下划线而不是整块底色，和 MasterGo 顶部那排标签一致
                g.fill(tx + 4, y + this.font.lineHeight + 6, tx + tw - 4,
                        y + this.font.lineHeight + 8, Theme.ACCENT);
            }
            Region r = addRegion(tx, y, tw, rowH, () -> layout.dock.setActive(zone, p), null);
            r.panel = p;
            tx += tw;
        }
        int bottom = y + this.font.lineHeight + 8;
        UiUtil.hLine(g, x, x + w, bottom, Theme.BORDER);
        return bottom + 8;
    }

    /**
     * 鼠标停在这个位置，松手会落到哪个区。
     *
     * <p>判据是<b>贴边</b>而不是「压在现有面板上」：区可能是空的（没有面板就没有可压的东西），
     * 而空区恰恰最需要能拖进去。所以按画布的边缘划带——靠左的一条竖带算左栏，
     * 靠右算右栏，靠下算底部，中间那一大片算浮动。
     *
     * <p>带宽取画布的四分之一并夹在 60~160 之间：纯比例在超宽屏上会宽得离谱，
     * 纯固定值在小窗口里又会把整个画布吃掉。
     */
    private @Nullable DockZone dropZoneAt(double mx, double my) {
        int top = TOP_H;
        int bot = statusTop();
        if (my < top || my >= bot || mx < 0 || mx >= lw) {
            return null;
        }
        int bandX = Math.max(60, Math.min(160, lw / 4));
        int bandY = Math.max(60, Math.min(160, (bot - top) / 4));
        if (mx < bandX) {
            return DockZone.LEFT;
        }
        if (mx >= lw - bandX) {
            return DockZone.RIGHT;
        }
        if (my >= bot - bandY) {
            return DockZone.BOTTOM;
        }
        return DockZone.FLOATING;
    }

    /** 目标区的矩形，用来画高亮。 */
    private int[] zoneRect(DockZone zone) {
        int top = TOP_H;
        int bot = statusTop();
        int bandX = Math.max(60, Math.min(160, lw / 4));
        int bandY = Math.max(60, Math.min(160, (bot - top) / 4));
        return switch (zone) {
            case LEFT -> new int[]{0, top, Math.max(bandX, leftW()), bot - top};
            case RIGHT -> {
                int w = Math.max(bandX, rightW());
                yield new int[]{lw - w, top, w, bot - top};
            }
            case BOTTOM -> {
                int h = Math.max(bandY, bottomH());
                int x = leftW();
                yield new int[]{x, bot - h, Math.max(0, lw - x - rightW()), h};
            }
            case FLOATING -> new int[]{leftW(), top, Math.max(0, lw - leftW() - rightW()), bot - top};
        };
    }

    /**
     * 拖面板时的落位提示：目标区整块高亮 + 一圈描边 + 跟着鼠标的标签。
     *
     * <p>画在所有面板<b>之后</b>，否则会被后画的面板盖住。
     */
    private void renderDockDragOverlay(GuiGraphicsExtractor g, int mx, int my) {
        DockPanel panel = drag == Drag.DOCK_TAB ? draggingPanel
                : (drag == Drag.FLOAT_MOVE ? floatTarget : null);
        if (panel == null) {
            return;
        }
        DockZone zone = dropZone;
        if (zone != null) {
            int[] r = zoneRect(zone);
            g.fill(r[0], r[1], r[0] + r[2], r[1] + r[3], Theme.DOCK_DROP_FILL);
            UiUtil.roundOutline(g, r[0], r[1], r[2], r[3], 4, Theme.ACCENT);
        }
        if (drag == Drag.FLOAT_MOVE) {
            // 浮窗本体已经跟着鼠标走了，再挂一个标签是重复的
            return;
        }
        // 跟手的标签：拖动过程中鼠标下面得有东西，否则不知道自己正拖着什么
        String label = panel.title();
        int tw = UiText.width(label) + 16;
        int th = this.font.lineHeight + 8;
        int tx = mx + 10;
        int ty = my + 10;
        UiUtil.roundRect(g, tx, ty, tw, th, 4, Theme.ACCENT);
        UiUtil.textLeft(g, this.font, label, tx + 8, ty + 5, Theme.TEXT_ON_ACCENT);
    }

    /** 浮动面板的标题栏高度。 */
    private static final int FLOAT_TITLE_H = 24;

    /**
     * 浮动出来的面板，一个一个独立小窗。
     *
     * <p>画在停靠区<b>之后</b>：它们本来就该浮在上面。同一批 region 是后登记的，
     * 而 region 表反向扫描，所以点击也会优先落到浮窗上，和视觉一致。
     *
     * <p>每帧把矩形夹回屏幕内。窗口大小变了（或者玩家把它拖出去过）之后，
     * 一个跑到界外的浮窗是找不回来的——没有任务栏可以点。
     */
    private void renderFloatingDocks(GuiGraphicsExtractor g, int mx, int my) {
        for (DockPanel panel : layout.dock.panelsIn(DockZone.FLOATING)) {
            int[] r = layout.dock.floatRect(panel);
            if (r == null) {
                continue;
            }
            int w = Math.max(180, Math.min(r[2], lw));
            int h = Math.max(120, Math.min(r[3], Math.max(120, statusTop() - TOP_H)));
            int x = Math.max(0, Math.min(r[0], lw - w));
            int y = Math.max(TOP_H, Math.min(r[1], statusTop() - FLOAT_TITLE_H));
            if (x != r[0] || y != r[1] || w != r[2] || h != r[3]) {
                layout.dock.setFloatRect(panel, x, y, w, h);
            }

            UiUtil.dropShadow(g, x, y, w, h, 4);
            UiUtil.roundRect(g, x, y, w, h, 6, Theme.PANEL);
            UiUtil.roundOutline(g, x, y, w, h, 6, Theme.BORDER_STRONG);

            // 标题栏：拖它移动窗口，拖到屏幕边缘则重新停靠
            g.fill(x + 1, y + 1, x + w - 1, y + FLOAT_TITLE_H, Theme.PANEL_ALT);
            UiUtil.textLeft(g, this.font, UiUtil.ellipsize(this.font, panel.title(), w - 40),
                    x + 8, y + (FLOAT_TITLE_H - this.font.lineHeight) / 2 + 1, Theme.TEXT);
            UiUtil.hLine(g, x + 1, x + w - 1, y + FLOAT_TITLE_H, Theme.BORDER);
            Region title = addRegion(x, y, w - ICON - 4, FLOAT_TITLE_H, null, null);
            title.panel = null;

            // 送回它的老家。浮窗没有关闭按钮——面板不能被关掉，只能换个地方待着
            iconButtonBare(g, x + w - ICON - 2, y + (FLOAT_TITLE_H - ICON) / 2, Icons::minus,
                    GtLang.get("gtshaders.dock.redock"), Theme.TEXT_SUB,
                    () -> {
                        if (layout.dock.move(panel, panel.home(), -1)) {
                            layout.save();
                        }
                    }, mx, my);

            int contentTop = y + FLOAT_TITLE_H + 4;
            int bottom = y + h;
            if (contentTop < bottom) {
                g.enableScissor(x, contentTop, x + w, bottom);
                clipRegions(x, contentTop, x + w, bottom);
                renderPanelBody(g, panel, x, contentTop, bottom, w, mx, my);
                g.disableScissor();
                unclipRegions();
            }

            // 右下角的缩放手柄
            int hs = 10;
            addRegion(x + w - hs, y + h - hs, hs, hs, null, null);
            UiUtil.vLine(g, x + w - 4, y + h - 8, y + h - 2, Theme.BORDER_STRONG);
            UiUtil.hLine(g, x + w - 8, x + w - 2, y + h - 4, Theme.BORDER_STRONG);
        }
    }

    /**
     * 浮窗上的按下判定。
     *
     * <p>必须在 region 扫描<b>之前</b>手工判一遍：标题栏和缩放手柄要的是「按住不放然后拖」，
     * 而 region 那套只有「点一下就触发」。
     *
     * @return 是否已经消费掉这次点击
     */
    private boolean pressFloatingDock(double mx, double my) {
        List<DockPanel> floats = layout.dock.panelsIn(DockZone.FLOATING);
        // 反向遍历：后画的在上面
        for (int i = floats.size() - 1; i >= 0; i--) {
            DockPanel panel = floats.get(i);
            int[] r = layout.dock.floatRect(panel);
            if (r == null) {
                continue;
            }
            int x = r[0];
            int y = r[1];
            int w = r[2];
            int h = r[3];
            if (mx >= x + w - 12 && mx < x + w && my >= y + h - 12 && my < y + h) {
                floatTarget = panel;
                drag = Drag.FLOAT_RESIZE;
                return true;
            }
            if (mx >= x && mx < x + w - ICON - 4 && my >= y && my < y + FLOAT_TITLE_H) {
                floatTarget = panel;
                floatOffX = (int) (mx - x);
                floatOffY = (int) (my - y);
                drag = Drag.FLOAT_MOVE;
                dropZone = null;
                return true;
            }
        }
        return false;
    }

    /**
     * 松手：把面板放进目标区。
     *
     * <p>没有目标区（拖到界外）就当作取消，面板留在原处——比「掉到某个默认区」好，
     * 拖到一半反悔是很常见的动作。
     *
     * <p>落进已经不再存在的区不用担心：{@link DockLayout} 保证每个面板恰好在一个区里，
     * 而空区会让对应的栏宽变成 0、自动从界面上消失。
     */
    private void finishPanelDrag() {
        DockPanel panel = draggingPanel;
        DockZone target = dropZone;
        draggingPanel = null;
        dropZone = null;
        if (panel == null || target == null) {
            return;
        }
        if (target == DockZone.FLOATING) {
            // 还没给过位置的浮动面板，落在画布中间偏上，别正好压住取景框
            if (layout.dock.floatRect(panel) == null) {
                int w = Math.min(320, Math.max(220, lw / 4));
                int h = Math.min(360, Math.max(180, (statusTop() - TOP_H) / 2));
                layout.dock.setFloatRect(panel,
                        Math.max(0, (lw - w) / 2), TOP_H + 24, w, h);
            }
        }
        if (layout.dock.move(panel, target, -1)) {
            // 搬家之后原区和新区的内容长度都变了，滚动位置不该跟着带过去
            zoneScroll.remove(target);
            layout.save();
        }
    }

    private void setCollapsed(DockZone zone, boolean collapsed) {
        if (zone == DockZone.LEFT) {
            layout.leftCollapsed = collapsed;
        } else if (zone == DockZone.RIGHT) {
            layout.rightCollapsed = collapsed;
        }
    }

    /** 把某个面板切到前台，不管它此刻停在哪个区。 */
    private void focusPanel(DockPanel panel) {
        layout.dock.setActive(layout.dock.zoneOf(panel), panel);
    }

    /** 这个面板此刻是不是真的看得见——所在区没折叠、且它是该区的当前项。 */
    private boolean isPanelShowing(DockPanel panel) {
        DockZone zone = layout.dock.zoneOf(panel);
        if (layout.dock.active(zone) != panel) {
            return false;
        }
        return switch (zone) {
            case LEFT -> !layout.leftCollapsed && leftW() > 0;
            case RIGHT -> !layout.rightCollapsed && rightW() > 0;
            case BOTTOM -> bottomH() > 0;
            case FLOATING -> true;
        };
    }

    private void collapsedStrip(GuiGraphicsExtractor g, int x, int top, int bottom,
                                boolean left, Runnable expand, int mx, int my) {
        boolean hover = hovered(mx, my, x, top, COLLAPSED_W, bottom - top);
        if (hover) {
            g.fill(x, top, x + COLLAPSED_W, bottom, Theme.BUTTON_HOVER);
        }
        Icons.plus(g, x + 2, (top + bottom) / 2 - 6, 12, Theme.TEXT_DIM);
        addRegion(x, top, COLLAPSED_W, bottom - top, expand, null);
        if (hover) {
            setTooltip(GtLang.get("gtshaders.view.expand"), mx, my);
        }
    }

    private void renderLayerList(GuiGraphicsExtractor g, int x0, int y, int bottom, int w, int mx, int my) {
        final int L = x0 + PAD;          // 内容左边界
        final int R = x0 + w - PAD;      // 内容右边界
        final int CW = w - PAD * 2;      // 内容宽度（不含左右留白）
        UiUtil.textLeft(g, this.font, GtLang.get("gtshaders.left.section.layers"), L, y + 4, Theme.TEXT);
        iconButton(g, R - ICON, y, Icons::plus, GtLang.get("gtshaders.tool.add_layer"),
                false, this::addLayer, mx, my);
        iconButton(g, R - ICON * 2 - 2, y, Icons::grid,
                GtLang.get("gtshaders.topbar.library_hint"), browser.isOpen(), this::openBrowser, mx, my);
        y += ICON + 4;
        UiUtil.hLine(g, L, R, y, Theme.BORDER);
        y += 6;

        searchBox(g, layerSearchField, L, y, CW, ROW_H,
                GtLang.get("gtshaders.left.search_layers"), mx, my);
        y += ROW_H + 4;
        // 右键菜单和 Ctrl 多选都是「不说就不会有人试」的交互，写一行出来
        if (!project.layers().isEmpty()) {
            UiUtil.textLeft(g, this.font,
                    UiUtil.ellipsize(this.font, GtLang.get("gtshaders.left.multi_hint"), CW),
                    L, y, Theme.TEXT_DIM);
            y += this.font.lineHeight + 4;
        }

        String filter = layerSearchField.text().trim().toLowerCase(Locale.ROOT);
        List<ShaderLayer> layers = project.layers();
        if (layers.isEmpty()) {
            // 零层是合法状态（效果全清空），得说清楚接下来该干什么，
            // 否则一片空白很像是界面坏了
            UiUtil.textLeft(g, this.font,
                    UiUtil.ellipsize(this.font, GtLang.get("gtshaders.left.empty_layers"), CW),
                    L, y + 2, Theme.TEXT_DIM);
            y += this.font.lineHeight + 4;
            y = wrapText(g, GtLang.get("gtshaders.left.empty_hint"), L, y, CW, Theme.TEXT_DIM);
            y += 4;
            primaryButton(g, L, y, CW, ROW_H,
                    GtLang.get("gtshaders.welcome.open_library"), this::openBrowser, mx, my);
            y += ROW_H + 4;
            secondaryButton(g, L, y, CW, ROW_H, GtLang.get("gtshaders.tool.add_layer"),
                    this::addLayer, mx, my);
            y += ROW_H;
        }
        // 从后往前列：MasterGo 的图层面板里越靠上的图层显示在越上面，
        // 而渲染顺序是从下往上——效果层同理，最后一个通道叠在最上面
        for (int idx = layers.size() - 1; idx >= 0; idx--) {
            if (y + ROW_H > bottom) {
                break;
            }
            ShaderLayer layer = layers.get(idx);
            if (!filter.isEmpty() && !layer.name().toLowerCase(Locale.ROOT).contains(filter)) {
                continue;
            }
            boolean selected = idx == project.selectedIndex();
            boolean marked = multiSelected.contains(layer);
            int rowX = L - 2;
            int rowW = CW + 4;
            if (selected) {
                UiUtil.roundRect(g, rowX, y, rowW, ROW_H, 4, Theme.ACCENT_SOFT);
            } else if (marked) {
                // 多选中但不是当前编辑的那层：底色浅一档，两者要能一眼分开
                UiUtil.roundRect(g, rowX, y, rowW, ROW_H, 4, Theme.PANEL_SUNKEN);
            } else if (hovered(mx, my, rowX, y, rowW, ROW_H)) {
                UiUtil.roundRect(g, rowX, y, rowW, ROW_H, 4, Theme.BUTTON_HOVER);
            }
            if (marked) {
                UiUtil.roundOutline(g, rowX, y, rowW, ROW_H, 4, Theme.ACCENT);
            }

            final ShaderLayer target = layer;
            int eyeX = rowX + 4;
            iconButtonBare(g, eyeX, y + (ROW_H - ICON) / 2, layer.isEnabled() ? Icons::eye : Icons::eyeOff,
                    GtLang.get("gtshaders.right.enabled"),
                    layer.isEnabled() ? Theme.TEXT_SUB : Theme.TEXT_DIM,
                    () -> {
                        target.setEnabled(!target.isEnabled());
                        compileNow();
                    }, mx, my);

            int textX = eyeX + ICON + 2;
            int badgeW = Math.min(46, rowW / 3);
            UiUtil.textLeft(g, this.font,
                    UiUtil.ellipsize(this.font, layer.name(), rowW - (textX - rowX) - badgeW - 8),
                    textX, y + (ROW_H - this.font.lineHeight) / 2 + 1,
                    layer.isEnabled() ? Theme.TEXT : Theme.TEXT_DIM);
            // 核心层没有混合模式，那个位置改成显示它改的是哪一类东西——
            // 一列全写着「正常」的徽标，对分辨两种层毫无帮助
            ShaderKind.Entry kind = layer.kind();
            String badge = kind != null ? kind.displayName()
                    : GtLang.get(layer.blendMode().translationKey());
            UiUtil.textRight(g, this.font, UiUtil.ellipsize(this.font, badge, badgeW),
                    rowX + rowW - 6, y + (ROW_H - this.font.lineHeight) / 2 + 1,
                    kind != null ? Theme.ACCENT : Theme.TEXT_DIM);

            final int index = idx;
            Region row = addRegion(textX, y, rowW - (textX - rowX), ROW_H,
                    () -> clickLayerRow(index, target), null);
            onRightClick(row, () -> {
                // 右键点到没在多选里的层，就先把选中挪过去——右键一个东西却对另一批下手，
                // 是最容易误删的交互
                if (!multiSelected.contains(target)) {
                    multiSelected.clear();
                    selectLayer(index);
                }
                openLayerMenu(mx, my, index, target);
            });
            y += ROW_H + 2;
        }

        y += 8;
        UiUtil.textLeft(g, this.font, GtLang.get("gtshaders.left.section.project"), L, y, Theme.TEXT);
        // 新建一个具名工程。工程不再只有一个改不掉名字的默认项——
        // 「这一套留着、另起一套试别的」是常态，而不是特例
        iconButtonBare(g, R - ICON, y - 5, Icons::plus,
                GtLang.get("gtshaders.ctx.new_project"), Theme.TEXT_SUB, this::newNamedProject, mx, my);
        y += this.font.lineHeight + 3;
        UiUtil.hLine(g, L, R, y, Theme.BORDER);
        y += 5;
        List<Path> projects = projects();
        if (projects.isEmpty()) {
            UiUtil.textLeft(g, this.font,
                    UiUtil.ellipsize(this.font, GtLang.get("gtshaders.left.no_projects"), CW),
                    L, y + 2, Theme.TEXT_DIM);
        } else {
            for (Path p : projects) {
                if (y + ROW_H > bottom) {
                    break;
                }
                // 当前打开的那个要标出来：工程列表里认不出「我在哪」，
                // 就会反复点开同一个然后以为没反应
                boolean current = ProjectStore.displayName(p).equals(project.name());
                if (current) {
                    UiUtil.roundRect(g, L - 2, y, CW + 4, ROW_H, 4, Theme.ACCENT_SOFT);
                } else if (hovered(mx, my, L - 2, y, CW + 4, ROW_H)) {
                    UiUtil.roundRect(g, L - 2, y, CW + 4, ROW_H, 4, Theme.BUTTON_HOVER);
                }
                UiUtil.textLeft(g, this.font,
                        UiUtil.ellipsize(this.font, ProjectStore.displayName(p), CW - 8),
                        L + 2, y + (ROW_H - this.font.lineHeight) / 2 + 1,
                        current ? Theme.ACCENT : Theme.TEXT_SUB);
                Region row = addRegion(L - 2, y, CW + 4, ROW_H, () -> loadProject(p), null);
                onRightClick(row, () -> openProjectMenu(mx, my, p));
                y += ROW_H;
            }
        }
    }

    private void openProjectMenu(int mx, int my, Path file) {
        openContextMenu(mx, my, menu -> {
            menu.add(GtLang.get("gtshaders.ctx.open"), true, () -> loadProject(file));
            menu.addSeparated(GtLang.get("gtshaders.ctx.delete_project"), true,
                    () -> deleteProject(file));
        });
    }

    /**
     * 删掉一个工程文件。
     *
     * <p>删的是磁盘上那份，当前打开的工程原样留在内存里——刚删完还能改、还能换个名字存回去。
     * 直接把界面也清空的话，误删就真的没救了。
     */
    private void deleteProject(Path file) {
        try {
            Files.deleteIfExists(file);
            projectsCachedAt = 0;
            showToast(GtLang.get("gtshaders.status.project_deleted",
                    ProjectStore.displayName(file)), false);
        } catch (IOException e) {
            showToast(GtLang.get("gtshaders.status.project_delete_failed",
                    String.valueOf(e.getMessage())), true);
        }
    }

    /**
     * 新建一个具名工程：把当前这套留在磁盘上，然后开一个空的。
     *
     * <p>先存再开，是因为「新建」最常见的用法就是「这套先放着，我另试一套」——
     * 不先落盘的话，刚调好的东西会在这一步无声消失。
     */
    private void newNamedProject() {
        // 当前这套先落盘。没有效果的空工程不值得存，否则每按一次 + 就多一个空壳
        if (project.hasEffect()) {
            saveProject();
        }
        newProject();
        project.setName(uniqueProjectName());
        projectNameField.setTextIfUnfocused(project.name());
        // 新工程必须<b>立刻存一份</b>：工程列表列的是磁盘上的文件，
        // 只改内存里的名字的话，列表里根本不会多出这一项——看起来就是「按了没反应」
        try {
            Path file = ProjectStore.save(project);
            HistoryStore.record(project.name(), file, System.currentTimeMillis());
        } catch (IOException e) {
            showToast(GtLang.get("gtshaders.status.save_failed", String.valueOf(e.getMessage())), true);
        }
        projectsCachedAt = 0;
        blurAllExcept(projectNameField);
        projectNameField.focus();
        showToast(GtLang.get("gtshaders.status.project_created", project.name()), false);
    }

    /** 找一个没被占用的工程名，免得新建两次就把上一个覆盖掉。 */
    private String uniqueProjectName() {
        String base = GtLang.get("gtshaders.project.untitled");
        java.util.Set<String> used = new HashSet<>();
        for (Path p : ProjectStore.listProjects()) {
            used.add(p.getFileName().toString());
        }
        // 按「保存后真正会落在磁盘上的文件名」判重，而不是按显示名——
        // 显示名是从文件名反推的，二者一旦脱节（旧版中文名工程名会被收敛成 untitled），
        // 这里就会反复选中同一个名字，把上一个工程覆盖掉
        if (!used.contains(ProjectStore.fileNameFor(base))) {
            return base;
        }
        for (int i = 2; i < 999; i++) {
            String candidate = base + " " + i;
            if (!used.contains(ProjectStore.fileNameFor(candidate))) {
                return candidate;
            }
        }
        return base;
    }

    /**
     * 版本页：把当前效果钉成一个可命名的快照，之后随时点回去。
     *
     * <p>为什么需要它：调着色器就是「改一点看一眼」，上一版好看这一版难看是常态。
     * 而代码编辑器的撤销栈只管文本——图层启不启用、混合模式、每个参数拖到了哪，它一概不知道。
     * 快照存的是整个工程，所以回退才是真的回得去。
     */
    private void renderSnapshotList(GuiGraphicsExtractor g, int x0, int y, int bottom, int w, int mx, int my) {
        final int L = x0 + PAD;          // 内容左边界
        final int R = x0 + w - PAD;      // 内容右边界
        final int CW = w - PAD * 2;      // 内容宽度（不含左右留白）
        int inputW = CW - ICON - 4;
        commitMessageField.render(g, this.font, L, y, inputW, ROW_H,
                hovered(mx, my, L, y, inputW, ROW_H), false);
        if (commitMessageField.text().isEmpty() && !commitMessageField.isFocused()) {
            UiUtil.textLeft(g, this.font,
                    UiUtil.ellipsize(this.font, GtLang.get("gtshaders.snapshot.placeholder"), inputW - 10),
                    L + 5, y + (ROW_H - this.font.lineHeight) / 2 + 1, Theme.TEXT_DIM);
        }
        addFieldRegion(commitMessageField, L, y, inputW, ROW_H);
        iconButton(g, R - ICON, y, Icons::commit, GtLang.get("gtshaders.snapshot.commit_hint"),
                false, this::commitSnapshot, mx, my);
        y += ROW_H + 6;

        UiUtil.textLeft(g, this.font,
                UiUtil.ellipsize(this.font, GtLang.get("gtshaders.snapshot.hint"), CW),
                L, y, Theme.TEXT_DIM);
        y += this.font.lineHeight + 4;
        UiUtil.hLine(g, L, R, y, Theme.BORDER);
        y += 6;

        List<SnapshotStore.Entry> snapshots = snapshots();
        if (snapshots.isEmpty()) {
            UiUtil.textLeft(g, this.font,
                    UiUtil.ellipsize(this.font, GtLang.get("gtshaders.snapshot.empty"), CW),
                    L, y + 2, Theme.TEXT_DIM);
            return;
        }

        long now = System.currentTimeMillis();
        int rowH = ROW_H + 10;
        for (SnapshotStore.Entry e : snapshots) {
            if (y + rowH > bottom) {
                break;
            }
            String fileKey = e.file().getFileName().toString();
            boolean renaming = fileKey.equals(renamingSnapshot);
            if (renaming && !snapshotNameField(e).isFocused()) {
                // 按 Esc 撤销编辑时 TextField 只是丢掉焦点、不会走 onCommit，
                // 界面得自己退出重命名态，否则会卡在一个再也没人管的输入框上
                renamingSnapshot = null;
                renaming = false;
            }
            boolean hot = hovered(mx, my, L - 2, y, CW + 4, rowH);
            if (hot && !renaming) {
                UiUtil.roundRect(g, L - 2, y, CW + 4, rowH, 4, Theme.BUTTON_HOVER);
            }

            int actionsW = ICON * 2;
            int textW = CW - actionsW - 4;

            if (renaming) {
                TextField f = snapshotNameField(e);
                f.render(g, this.font, L, y + 2, textW, ROW_H,
                        hovered(mx, my, L, y + 2, textW, ROW_H), false);
                addFieldRegion(f, L, y + 2, textW, ROW_H);
            } else {
                UiUtil.textLeft(g, this.font, UiUtil.ellipsize(this.font, e.message(), textW),
                        L, y + 4, Theme.TEXT);
                String meta = HistoryStore.relativeTime(e.createdAt(), now) + " · "
                        + GtLang.get("gtshaders.snapshot.meta", e.enabled(), e.layers());
                UiUtil.textLeft(g, this.font, UiUtil.ellipsize(this.font, meta, textW),
                        L, y + 4 + this.font.lineHeight + 1, Theme.TEXT_DIM);
                // 点整行 = 回到这一版。列表上最高频的动作，留给不带修饰键的单击
                addRegion(L - 2, y, textW + 4, rowH, () -> restoreSnapshot(e), null);
            }

            int ax = R - actionsW;
            iconButtonBare(g, ax, y + (rowH - ICON) / 2, Icons::pencil,
                    GtLang.get("gtshaders.snapshot.rename_hint"), Theme.TEXT_DIM,
                    () -> toggleRenameSnapshot(e), mx, my);
            iconButtonBare(g, ax + ICON, y + (rowH - ICON) / 2, Icons::trash,
                    GtLang.get("gtshaders.snapshot.delete_hint"), Theme.TEXT_DIM,
                    () -> deleteSnapshot(e), mx, my);
            y += rowH;
        }
    }

    /** 快照列表带 2 秒缓存，理由和工程列表一样：左栏每帧重绘，直接列目录等于每帧一次磁盘 I/O。 */
    private List<SnapshotStore.Entry> snapshots() {
        long now = System.currentTimeMillis();
        if (now - snapshotsCachedAt > 2000L) {
            cachedSnapshots = SnapshotStore.list();
            snapshotsCachedAt = now;
        }
        return cachedSnapshots;
    }

    private TextField snapshotNameField(SnapshotStore.Entry entry) {
        TextField f = snapshotNameFields.computeIfAbsent(entry.file().getFileName().toString(), k -> {
            TextField nf = new TextField(TextField.Kind.TEXT);
            registerField(nf);
            return nf;
        });
        // 每帧重绑：onCommit 要闭包住当前这条 entry，而列表是会变的
        f.setOnCommit(() -> applyRename(entry, f));
        f.setTextIfUnfocused(entry.message());
        return f;
    }

    private void commitSnapshot() {
        // 输入框可能还握着焦点，先提交它，否则拿到的是上一次的文本
        commitMessageField.blur();
        String message = commitMessageField.text().trim();
        if (message.isEmpty()) {
            message = GtLang.get("gtshaders.snapshot.default_name",
                    new java.text.SimpleDateFormat("HH:mm:ss").format(new java.util.Date()));
        }
        try {
            SnapshotStore.commit(project, message, System.currentTimeMillis());
            commitMessageField.setTextIfUnfocused("");
            snapshotsCachedAt = 0;
            focusPanel(DockPanel.VERSIONS);
            showToast(GtLang.get("gtshaders.snapshot.committed", message), false);
        } catch (IOException e) {
            showToast(GtLang.get("gtshaders.snapshot.commit_failed", String.valueOf(e.getMessage())), true);
        }
    }

    /**
     * 回到某一版。
     *
     * <p>刻意<b>不</b>先把当前状态自动提交一份：那会让列表被一堆没人要的自动条目淹没，
     * 而「提交」本来就该是玩家明确按下的动作。想留退路就先点提交。
     */
    private void restoreSnapshot(SnapshotStore.Entry entry) {
        try {
            setProject(SnapshotStore.restore(entry));
            projectNameField.setTextIfUnfocused(project.name());
            bindViewportFields();
            syncCodeEditorFromLayer();
            colorPicker.close();
            closeSliderPopup();
            showToast(GtLang.get("gtshaders.snapshot.restored", entry.message()), false);
            compileNow();
        } catch (IOException | RuntimeException e) {
            showToast(GtLang.get("gtshaders.snapshot.restore_failed", String.valueOf(e.getMessage())), true);
        }
    }

    private void toggleRenameSnapshot(SnapshotStore.Entry entry) {
        String fileKey = entry.file().getFileName().toString();
        TextField f = snapshotNameField(entry);
        if (fileKey.equals(renamingSnapshot)) {
            f.blur();   // → onCommit → applyRename
            return;
        }
        renamingSnapshot = fileKey;
        blurAllExcept(f);
        f.focus();
    }

    /** 由 {@link TextField#blur()} 触发：回车、Tab、点别处都会走到这里。 */
    private void applyRename(SnapshotStore.Entry entry, TextField f) {
        if (entry.file().getFileName().toString().equals(renamingSnapshot)) {
            renamingSnapshot = null;
        }
        String name = f.text().trim();
        if (name.isEmpty() || name.equals(entry.message())) {
            return;
        }
        try {
            SnapshotStore.rename(entry, name);
            snapshotsCachedAt = 0;
        } catch (IOException e) {
            showToast(GtLang.get("gtshaders.snapshot.rename_failed", String.valueOf(e.getMessage())), true);
        }
    }

    private void deleteSnapshot(SnapshotStore.Entry entry) {
        if (SnapshotStore.delete(entry)) {
            snapshotNameFields.remove(entry.file().getFileName().toString());
            if (entry.file().getFileName().toString().equals(renamingSnapshot)) {
                renamingSnapshot = null;
            }
            snapshotsCachedAt = 0;
            showToast(GtLang.get("gtshaders.snapshot.deleted", entry.message()), false);
        }
    }

    private void renderAssetList(GuiGraphicsExtractor g, int x0, int y, int bottom, int w, int mx, int my) {
        final int L = x0 + PAD;          // 内容左边界
        final int R = x0 + w - PAD;      // 内容右边界
        final int CW = w - PAD * 2;      // 内容宽度（不含左右留白）
        UiUtil.textLeft(g, this.font,
                UiUtil.ellipsize(this.font, GtLang.get("gtshaders.left.assets_hint"), CW),
                L, y + 4, Theme.TEXT_DIM);
        y += this.font.lineHeight + 8;
        UiUtil.hLine(g, L, R, y, Theme.BORDER);
        y += 6;

        ShaderLayer layer = project.selected();
        if (layer != null) {
            for (ShaderParam p : layer.params()) {
                if (y + 14 > bottom) {
                    break;
                }
                UiUtil.textLeft(g, this.font, p.name(), L, y, Theme.TEXT);
                UiUtil.textRight(g, this.font, p.type().glslType(), R, y, Theme.TEXT_DIM);
                y += 14;
            }
            y += 8;
        }

        UiUtil.textLeft(g, this.font, GtLang.get("gtshaders.left.assets.builtins"), L, y, Theme.TEXT);
        y += this.font.lineHeight + 3;
        UiUtil.hLine(g, L, R, y, Theme.BORDER);
        y += 5;
        // 顺序即常用度：写效果时最先够得着的排在最前，原版 Globals 块的成员垫底。
        // 列表放不下时下面的循环会直接截断，所以别把常用的排到后面去。
        String[][] builtins = {
                {"InSampler", "sampler2D"}, {"texCoord", "vec2"},
                {"gl_FragCoord", "vec4"},
                {"OutSize", "vec2"}, {"InSize", "vec2"},
                {"GTTime", "float"}, {"GTDeltaTime", "float"}, {"GTFrame", "float"},
                {"GTStrength", "float"},
                {"GTViewport", "vec4"}, {"GTViewportUV", "vec2"},
                {"GTLayerIndex", "float"}, {"GTLayerCount", "float"},
                {"iTime", "float"}, {"iResolution", "vec3"}, {"iChannel0", "sampler2D"},
                {"ScreenSize", "vec2"}, {"GameTime", "float"},
                {"CameraBlockPos", "ivec3"}, {"CameraOffset", "vec3"},
        };
        for (String[] b : builtins) {
            if (y + 14 > bottom) {
                break;
            }
            UiUtil.textLeft(g, this.font, b[0], L, y, Theme.TEXT_SUB);
            UiUtil.textRight(g, this.font, b[1], R, y, Theme.TEXT_DIM);
            y += 14;
        }
    }

    // ---- 画布 ----

    /** 某一层的取景框在逻辑坐标下的实际矩形。它必须与效果真正作用的屏幕区域 1:1 对应。 */
    private int[] frameRect(ShaderLayer layer) {
        ViewportRect v = layer.viewport();
        return new int[]{
                Math.round(v.x0() * lw), Math.round(v.y0() * lh),
                Math.round(v.x1() * lw), Math.round(v.y1() * lh)};
    }

    /**
     * 画布。
     *
     * <h3>每层一个框</h3>
     *
     * <p>取景框是<b>层</b>的属性，不是整个工程的：一条链里每层各管画面的一块是很自然的
     * 组合（暗角铺满全屏、扫描线只压下半屏），共用一个框根本表达不了。
     *
     * <p>所以画布的行为对齐设计软件里的「选中对象」：没选中的层画一圈淡淡的轮廓当提示，
     * 选中的那层才有控制点、尺寸徽标和跟着它走的控件组；点空白处取消选中，框和控件组一起消失。
     */
    private void renderCanvas(GuiGraphicsExtractor g, int mx, int my) {
        int cx0 = leftW();
        int cx1 = lw - rightW();
        // 点画布空白处取消选中。最先登记，所以任何框、控制点、控件组都压在它上面
        addRegion(cx0, TOP_H, Math.max(0, cx1 - cx0), Math.max(0, canvasBottom() - TOP_H),
                () -> project.setSelectedIndex(-1), null);

        ShaderLayer sel = project.selected();

        // 未选中的层：只画一圈淡轮廓 + 一块可点区域，用来告诉你「这里还有一层」并且点得到
        for (int i = project.layers().size() - 1; i >= 0; i--) {
            ShaderLayer layer = project.layers().get(i);
            if (layer == sel || !layer.isPost() || layer.viewport().isFullScreen()) {
                continue;
            }
            int[] r = frameRect(layer);
            UiUtil.box(g, r[0], r[1], r[2] - r[0], r[3] - r[1], Theme.CANVAS_FRAME_IDLE);
            final int index = i;
            addRegion(r[0], r[1], r[2] - r[0], r[3] - r[1], () -> selectLayer(index), null);
        }

        if (sel != null) {
            renderSelectedFrame(g, sel, mx, my);
            renderIconTransform(g, sel, mx, my);
        }

        renderAnchorOverlay(g, mx, my);

        if (inspectTool) {
            // 压在所有框与控制点上面：开着取样时，点画布的意思就是「读这里」
            addRegion(cx0, TOP_H, Math.max(0, cx1 - cx0), Math.max(0, canvasBottom() - TOP_H),
                    this::inspectAtClick, null);
            renderInspectMarker(g);
        }

        renderCanvasTools(g, sel, mx, my);

        int hintY = TOP_H + ICON + 24;
        if (this.minecraft != null && this.minecraft.level == null) {
            centerHint(g, hintY, GtLang.get("gtshaders.canvas.need_world"));
        } else if (project.layers().isEmpty()) {
            // 一层都没有 = 第一次打开。此时给一句「还没启用」毫无用处——
            // 真正缺的是「那我现在该点哪」，所以这里给的是一张带按钮的卡片。
            renderWelcome(g, hintY, mx, my);
        } else if (!project.hasEffect()) {
            // 有层但都没启用。光说「勾选启用」没用——第一次用的人不知道去哪勾，
            // 于是会一直点别的地方试，最后以为是坏了。直接给一个能按的按钮。
            renderEnableHint(g, hintY, mx, my);
        }
        // 「预览已关闭」不再浮在画面中间：那是个自己按 Delete 关掉的状态，
        // 用户完全知道自己做了什么，却要一直看着一句提示挡在画布上。
        // 状态栏里仍然有它，需要确认时看那里
    }

    /**
     * 空工程的引导卡。
     *
     * <p>唯一的主按钮是「打开效果库」——第一次用的人不需要知道图层、参数、profile 这些，
     * 只需要看到一个效果真的出现在自己的游戏画面上。其余的路（拖文件、快捷键）写成小字，
     * 是提示不是选择题：并排三个同等重量的按钮，等于让人先做一道不必要的判断。
     */
    private void renderWelcome(GuiGraphicsExtractor g, int y, int mx, int my) {
        int w = Math.min(320, Math.max(200, lw - leftW() - rightW() - 40));
        int x = (leftW() + lw - rightW() - w) / 2;
        int pad = 14;

        // 先量高度再画背景：文字换行的行数取决于宽度与语言，写死会在英文下溢出
        int textW = w - pad * 2;
        int lines = 1 + measureWrapped(GtLang.get("gtshaders.welcome.body"), textW);
        int h = pad + this.font.lineHeight + 4
                + lines * (this.font.lineHeight + 1) + 10
                + ROW_H + 10
                + (this.font.lineHeight + 2) * 2 + pad;

        UiUtil.dropShadow(g, x, y, w, h, 4);
        UiUtil.roundRect(g, x, y, w, h, 8, Theme.PANEL);
        UiUtil.box(g, x, y, w, h, Theme.BORDER_STRONG);

        int ty = y + pad;
        UiUtil.textLeft(g, this.font, GtLang.get("gtshaders.welcome.title"), x + pad, ty, Theme.TEXT);
        ty += this.font.lineHeight + 4;
        ty = wrapText(g, GtLang.get("gtshaders.welcome.body"), x + pad, ty, textW, Theme.TEXT_SUB);
        ty += 10;

        primaryButton(g, x + pad, ty, textW, ROW_H, GtLang.get("gtshaders.welcome.open_library"),
                this::openBrowser, mx, my);
        ty += ROW_H + 10;

        UiUtil.textLeft(g, this.font,
                UiUtil.ellipsize(this.font, GtLang.get("gtshaders.welcome.drop"), textW),
                x + pad, ty, Theme.TEXT_DIM);
        ty += this.font.lineHeight + 2;
        UiUtil.textLeft(g, this.font,
                UiUtil.ellipsize(this.font, GtLang.get("gtshaders.welcome.keys"), textW),
                x + pad, ty, Theme.TEXT_DIM);
    }

    /** 只数行数、不绘制。走的是同一个 {@link #wrapLines}，量出来的高度不会和实际画的对不上。 */
    private int measureWrapped(String text, int w) {
        return Math.max(1, wrapLines(text, w).size());
    }

    /** 选中层的框：控制点 + 尺寸徽标 + 可拖动。 */
    private void renderSelectedFrame(GuiGraphicsExtractor g, ShaderLayer layer, int mx, int my) {
        int[] f = frameRect(layer);
        int fx = f[0];
        int fy = f[1];
        int fw = f[2] - f[0];
        int fh = f[3] - f[1];

        UiUtil.box(g, fx, fy, fw, fh, Theme.CANVAS_FRAME);

        // 全屏时框贴着屏幕边缘、会被面板压住，此时不画控制点，
        // 免得出现「看着能拖其实抓不到」的错觉——要调整先点右栏的「自定义区域」
        if (!layer.viewport().isFullScreen()) {
            int hs = 7;
            int[][] handles = {
                    {fx, fy, -1, -1}, {f[2] - hs, fy, 1, -1},
                    {fx, f[3] - hs, -1, 1}, {f[2] - hs, f[3] - hs, 1, 1},
            };
            for (int[] hd : handles) {
                g.fill(hd[0], hd[1], hd[0] + hs, hd[1] + hs, Theme.CANVAS_HANDLE_FILL);
                UiUtil.box(g, hd[0], hd[1], hs, hs, Theme.CANVAS_FRAME);
                final int ex = hd[2];
                final int ey = hd[3];
                addRegion(hd[0] - 2, hd[1] - 2, hs + 4, hs + 4, () -> beginEdgeDrag(ex, ey), null);
            }
            addRegion(fx + hs, fy - 3, fw - hs * 2, 6, () -> beginEdgeDrag(0, -1), null);
            addRegion(fx + hs, f[3] - 3, fw - hs * 2, 6, () -> beginEdgeDrag(0, 1), null);
            addRegion(fx - 3, fy + hs, 6, fh - hs * 2, () -> beginEdgeDrag(-1, 0), null);
            addRegion(f[2] - 3, fy + hs, 6, fh - hs * 2, () -> beginEdgeDrag(1, 0), null);
            addRegion(fx + hs, fy + hs, Math.max(0, fw - hs * 2), Math.max(0, fh - hs * 2),
                    this::beginMoveDrag, null);
        } else {
            // 全屏框内部照样要吃掉点击，否则点一下就被下面「取消选中」那块接走了
            addRegion(fx, fy, fw, fh, () -> {
            }, null);
        }

        String size = GtLang.get("gtshaders.canvas.size",
                Math.round(layer.viewport().width() * this.width),
                Math.round(layer.viewport().height() * this.height));
        int badgeW = UiText.width(size) + 12;
        int badgeX = Math.max(leftW() + 4, Math.min(lw - rightW() - badgeW - 4, fx + (fw - badgeW) / 2));
        int badgeY = Math.min(canvasBottom() - 22, f[3] + 6);
        UiUtil.roundRect(g, badgeX, badgeY, badgeW, 16, 4, Theme.CANVAS_BADGE);
        UiUtil.textCenter(g, this.font, size, badgeX + badgeW / 2, badgeY + 4, Theme.TEXT_ON_ACCENT);
    }

    // ------------------------------------------------------------------ 画布上的自定义贴图变换

    /** 当前选中的“可换图”效果是否支持画布直接微操。 */
    private boolean hasIconTransform(ShaderLayer layer) {
        return layer != null && !layer.textures().isEmpty()
                && findParam(layer, "Position") != null && findParam(layer, "Size") != null
                && findParam(layer, "StretchX") != null && findParam(layer, "StretchY") != null;
    }

    private static @Nullable ShaderParam findParam(ShaderLayer layer, String name) {
        for (ShaderParam p : layer.params()) {
            if (p.name().equals(name)) {
                return p;
            }
        }
        return null;
    }

    private static float paramFloat(ShaderLayer layer, String name, float def) {
        ShaderParam p = findParam(layer, name);
        return p != null && p.type().components() >= 1 ? p.get(0) : def;
    }

    private static float paramVec2X(ShaderLayer layer, String name, float def) {
        ShaderParam p = findParam(layer, name);
        return p != null && p.type().components() >= 2 ? p.get(0) : def;
    }

    private static float paramVec2Y(ShaderLayer layer, String name, float def) {
        ShaderParam p = findParam(layer, name);
        return p != null && p.type().components() >= 2 ? p.get(1) : def;
    }

    private static void setParamFloat(ShaderLayer layer, String name, float value) {
        ShaderParam p = findParam(layer, name);
        if (p != null) {
            p.set(0, value);
        }
    }

    private static void setParamVec2(ShaderLayer layer, String name, float x, float y) {
        ShaderParam p = findParam(layer, name);
        if (p != null && p.type().components() >= 2) {
            p.set(0, x);
            p.set(1, y);
        }
    }

    /** 画布上的贴图变换框：移动、四角等比缩放、左右/上下拉伸。 */
    private void renderIconTransform(GuiGraphicsExtractor g, ShaderLayer layer, int mx, int my) {
        if (!hasIconTransform(layer)) {
            return;
        }
        float posX = paramVec2X(layer, "Position", 0.5f) * lw;
        // Position 是 GL UV 坐标（y 向上），画布是 GUI 坐标（y 向下），必须翻转。
        float posY = (1f - paramVec2Y(layer, "Position", 0.5f)) * lh;
        float size = Math.max(0.01f, paramFloat(layer, "Size", 0.1f));
        float globalScale = Math.max(0.01f, paramFloat(layer, "Scale", 1.0f));
        float stretchX = Math.max(0.2f, paramFloat(layer, "StretchX", 1.0f));
        float stretchY = Math.max(0.2f, paramFloat(layer, "StretchY", 1.0f));
        int halfW = Math.max(2, Math.round(size * globalScale * stretchX * lw / 2f));
        int halfH = Math.max(2, Math.round(size * globalScale * stretchY * lh / 2f));
        int x0 = Math.round(posX) - halfW;
        int y0 = Math.round(posY) - halfH;
        int x1 = Math.round(posX) + halfW;
        int y1 = Math.round(posY) + halfH;

        UiUtil.box(g, x0, y0, x1 - x0, y1 - y0, Theme.ACCENT);
        int hs = 7;
        int[][] handles = {
                {x0, y0}, {x1 - hs, y0}, {x0, y1 - hs}, {x1 - hs, y1 - hs}
        };
        for (int[] hd : handles) {
            g.fill(hd[0], hd[1], hd[0] + hs, hd[1] + hs, Theme.CANVAS_HANDLE_FILL);
            UiUtil.box(g, hd[0], hd[1], hs, hs, Theme.ACCENT);
        }
        // 边缘中点：左右拉伸、上下拉伸
        int midX = (x0 + x1) / 2;
        int midY = (y0 + y1) / 2;
        g.fill(x0 + hs, y0 - 2, x1 - hs, y0 + 2, Theme.CANVAS_HANDLE_FILL);
        g.fill(x0 + hs, y1 - 2, x1 - hs, y1 + 2, Theme.CANVAS_HANDLE_FILL);
        g.fill(x0 - 2, y0 + hs, x0 + 2, y1 - hs, Theme.CANVAS_HANDLE_FILL);
        g.fill(x1 - 2, y0 + hs, x1 + 2, y1 - hs, Theme.CANVAS_HANDLE_FILL);

        Region iconBox = addRegion(x0 + hs, y0 + hs, Math.max(0, x1 - x0 - hs * 2), Math.max(0, y1 - y0 - hs * 2),
                this::beginIconMove, null);
        onRightClick(iconBox, () -> openIconContextMenu(mx, my, layer));
        for (int[] hd : handles) {
            addRegion(hd[0] - 2, hd[1] - 2, hs + 4, hs + 4, this::beginIconCorner, null);
        }
        addRegion(x0 + hs, y0 - 3, Math.max(0, x1 - x0 - hs * 2), 8, this::beginIconEdgeY, null);
        addRegion(x0 + hs, y1 - 5, Math.max(0, x1 - x0 - hs * 2), 8, this::beginIconEdgeY, null);
        addRegion(x0 - 5, y0 + hs, 8, Math.max(0, y1 - y0 - hs * 2), this::beginIconEdgeX, null);
        addRegion(x1 - 3, y0 + hs, 8, Math.max(0, y1 - y0 - hs * 2), this::beginIconEdgeX, null);
    }

    private void beginIconMove() {
        ShaderLayer layer = project.selected();
        if (layer == null) {
            return;
        }
        float posX = paramVec2X(layer, "Position", 0.5f) * lw;
        float posY = (1f - paramVec2Y(layer, "Position", 0.5f)) * lh;
        drag = Drag.ICON_MOVE;
        dragOffX = (int) Math.round(mouseLogicalX() - posX);
        dragOffY = (int) Math.round(mouseLogicalY() - posY);
    }

    private void beginIconCorner() {
        drag = Drag.ICON_CORNER;
    }

    private void beginIconEdgeX() {
        drag = Drag.ICON_EDGE_X;
    }

    private void beginIconEdgeY() {
        drag = Drag.ICON_EDGE_Y;
    }

    private void openIconContextMenu(int mx, int my, ShaderLayer layer) {
        openContextMenu(mx, my, menu -> {
            menu.add(GtLang.get("gtshaders.icon.flip_x"), true, () -> {
                ShaderParam p = findParam(layer, "FlipX");
                if (p != null) {
                    p.set(0, p.get(0) >= 0.5f ? 0f : 1f);
                    onParamEdited();
                }
            });
            menu.add(GtLang.get("gtshaders.icon.flip_y"), true, () -> {
                ShaderParam p = findParam(layer, "FlipY");
                if (p != null) {
                    p.set(0, p.get(0) >= 0.5f ? 0f : 1f);
                    onParamEdited();
                }
            });
            menu.add(GtLang.get("gtshaders.icon.rotate_90"), true, () -> {
                ShaderParam p = findParam(layer, "Rotate");
                if (p != null) {
                    p.set(0, (p.get(0) + 90f) % 360f);
                    onParamEdited();
                }
            });
            menu.add(GtLang.get("gtshaders.icon.reset"), true, () -> {
                setParamVec2(layer, "Position", 0.5f, 0.35f);
                setParamFloat(layer, "Size", 0.1f);
                setParamFloat(layer, "StretchX", 1.0f);
                setParamFloat(layer, "StretchY", 1.0f);
                ShaderParam flipX = findParam(layer, "FlipX");
                if (flipX != null) flipX.set(0, 0f);
                ShaderParam flipY = findParam(layer, "FlipY");
                if (flipY != null) flipY.set(0, 0f);
                ShaderParam rotate = findParam(layer, "Rotate");
                if (rotate != null) rotate.set(0, 0f);
                onParamEdited();
            });
        });
    }

    private float mouseLogicalX() {
        return (float) (this.minecraft.mouseHandler.xpos() / scale());
    }

    private float mouseLogicalY() {
        return (float) (this.minecraft.mouseHandler.ypos() / scale());
    }

    /**
     * 跟着选中框走的控件组。
     *
     * <p>贴在框的正上方，而不是钉在画布顶部正中——控件是<b>作用在这一层上</b>的，
     * 它离那一层的框越近，「我在改哪个东西」就越不用想。没有选中层时整组不画：
     * 那时候没有任何东西可以对它下手。
     *
     * <p>放不下就翻到框内侧：框贴着画布顶边时，控件组挪到框里面比压进顶栏强。
     */
    private void renderCanvasTools(GuiGraphicsExtractor g, @Nullable ShaderLayer layer, int mx, int my) {
        if (layer == null) {
            return;
        }
        int count = 4;
        int barW = ICON * count + (count + 1) * 4;
        int barH = ICON + 8;
        int[] f = frameRect(layer);

        int barX = Math.max(leftW() + 4, Math.min(lw - rightW() - barW - 4,
                f[0] + (f[2] - f[0] - barW) / 2));
        int barY = f[1] - barH - 4;
        if (barY < TOP_H + 4) {
            barY = Math.min(canvasBottom() - barH - 4, f[1] + 4);
        }

        UiUtil.dropShadow(g, barX, barY, barW, barH, 2);
        UiUtil.roundRect(g, barX, barY, barW, barH, 6, Theme.PANEL);
        UiUtil.box(g, barX, barY, barW, barH, Theme.BORDER);

        int bx = barX + 4;
        int by = barY + 4;
        iconButton(g, bx, by, layer.isEnabled() ? Icons::eye : Icons::eyeOff,
                GtLang.get("gtshaders.right.enabled"), layer.isEnabled(), () -> {
                    layer.setEnabled(!layer.isEnabled());
                    compileNow();
                }, mx, my);
        bx += ICON + 4;
        iconButton(g, bx, by, Icons::frame, GtLang.get("gtshaders.canvas.fit"), false,
                () -> layer.viewport().reset(), mx, my);
        bx += ICON + 4;
        iconButton(g, bx, by, Icons::duplicate, GtLang.get("gtshaders.tool.duplicate_layer"), false,
                this::duplicateLayer, mx, my);
        bx += ICON + 4;
        iconButton(g, bx, by, Icons::trash, GtLang.get("gtshaders.tool.delete_layer"), false,
                this::deleteLayer, mx, my);

        // 层名贴在控件组上方，一眼确认自己正在改哪一层
        String label = UiUtil.ellipsize(this.font, layer.name(), Math.max(40, barW + 40));
        UiUtil.textCenter(g, this.font, label, barX + barW / 2,
                Math.max(TOP_H + 2, barY - this.font.lineHeight - 2), Theme.TEXT_SUB);
    }

    /**
     * 「有层但没启用」时的提示条，带一个直接启用的按钮。
     *
     * <p>起手模板和核心示例是<b>刻意不自动启用</b>的——它们是拿来读、拿来改的范本，
     * 插进来就直接改画面并不合适。但这个设计有个代价：加完之后画面纹丝不动，
     * 而「去哪勾选」在界面上一点线索都没有。按钮补的就是这条线索。
     */
    private void renderEnableHint(GuiGraphicsExtractor g, int y, int mx, int my) {
        ShaderLayer target = project.selected();
        if (target == null) {
            for (ShaderLayer l : project.layers()) {
                if (!l.isEnabled()) {
                    target = l;
                    break;
                }
            }
        }
        if (target == null) {
            centerHint(g, y, GtLang.get("gtshaders.canvas.no_effect"));
            return;
        }

        String text = GtLang.get("gtshaders.canvas.no_effect");
        String btn = GtLang.get("gtshaders.canvas.enable_now", target.name());
        int btnW = UiText.width(btn) + 20;
        int w = Math.max(UiText.width(text) + 20, btnW + 20);
        int h = 20 + ROW_H + 6;
        int x = (leftW() + lw - rightW() - w) / 2;

        UiUtil.dropShadow(g, x, y, w, h, 3);
        UiUtil.roundRect(g, x, y, w, h, 5, Theme.PANEL);
        UiUtil.box(g, x, y, w, h, Theme.BORDER);
        UiUtil.textCenter(g, this.font, text, x + w / 2, y + 6, Theme.TEXT_SUB);

        final ShaderLayer layer = target;
        primaryButton(g, x + (w - btnW) / 2, y + 20, btnW, ROW_H, btn, () -> {
            layer.setEnabled(true);
            compileNow();
        }, mx, my);
    }

    private void centerHint(GuiGraphicsExtractor g, int y, String text) {
        int w = UiText.width(text) + 20;
        int x = (leftW() + lw - rightW() - w) / 2;
        UiUtil.roundRect(g, x, y, w, 20, 5, Theme.PANEL);
        UiUtil.box(g, x, y, w, 20, Theme.BORDER);
        UiUtil.textCenter(g, this.font, text, x + w / 2, y + 6, Theme.TEXT_SUB);
    }

    private void beginEdgeDrag(int ex, int ey) {
        drag = Drag.VIEWPORT_EDGE;
        edgeX = ex;
        edgeY = ey;
    }

    private void beginMoveDrag() {
        drag = Drag.VIEWPORT_MOVE;
    }

    // ---- 右栏 ----

    private void renderRightPanel(GuiGraphicsExtractor g, int mx, int my) {
        int w = rightW();
        if (w <= 0) {
            return;
        }
        int x0 = lw - w;
        if (layout.rightCollapsed) {
            g.fill(x0, TOP_H, lw, statusTop(), Theme.PANEL);
            collapsedStrip(g, x0, TOP_H, statusTop(), false,
                    () -> layout.rightCollapsed = false, mx, my);
            return;
        }
        renderDockZone(g, DockZone.RIGHT, x0, TOP_H, w, statusTop() - TOP_H, mx, my);
    }

    /**
     * 底部停靠区。
     *
     * <p>横跨左右栏之间那一段，而不是整个屏幕宽——这样三个区互不重叠，
     * 拖动分隔线时也不必考虑谁盖住谁。空的时候整个区不存在，
     * 画布自动占回那块地方。
     */
    private void renderBottomDock(GuiGraphicsExtractor g, int mx, int my) {
        int h = bottomH();
        if (h <= 0) {
            return;
        }
        int x = leftW();
        int w = Math.max(0, lw - x - rightW());
        renderDockZone(g, DockZone.BOTTOM, x, canvasBottom(), w, h, mx, my);
    }

    /**
     * 右栏「设计」页。
     *
     * <h3>为什么按层的类型分支</h3>
     *
     * <p>核心着色器层没有强度、没有混合模式、也不受作用区域约束——那三样是后处理链专有的。
     * 原来不分类型全画出来，于是选中一个核心层会看到三个拖了不起作用的控件，
     * 而「不起作用」这件事界面上一个字都没说。对第一次用的人这是最坏的一类困惑：
     * 他会以为是自己哪里没设对，然后一直调下去。
     *
     * <p>顺序也换了：图层属性提到最前。用户刚点完一个层，最想看的是这个层的东西，
     * 而作用区域是<b>整条后处理链</b>的设置，跟当前选中项无关。
     */
    private void renderDesignTab(GuiGraphicsExtractor g, int x0, int y, int w, int mx, int my) {
        int px = x0 + PAD;
        int pw = w - PAD * 2;
        ShaderLayer layer = project.selected();

        if (layer == null) {
            y = section(g, GtLang.get("gtshaders.right.section.layer"), px, y, pw);
            UiUtil.textLeft(g, this.font, GtLang.get("gtshaders.right.no_selection"), px, y, Theme.TEXT_DIM);
            y += ROW_H;
        } else if (layer.isCore()) {
            y = renderCoreLayerDesign(g, layer, px, y, pw, mx, my);
        } else if (layer.isOutline()) {
            y = renderOutlineLayerDesign(g, layer, px, y, pw, mx, my);
        } else {
            y = renderPostLayerDesign(g, layer, px, y, pw, mx, my);
        }

        // ---- 效果作用区域：跟着选中层走，每层各管画面的一块 ----
        y += 6;
        ViewportRect v = activeViewport();
        y = section(g, GtLang.get("gtshaders.right.section.viewport"), px, y, pw);
        if (layer != null && layer.isCore()) {
            // 选中的是核心层时说清楚这一段管不到它，否则下面四个输入框看着就像该有用
            y = wrapText(g, GtLang.get("gtshaders.right.viewport_core_na"), px, y, pw, Theme.TEXT_DIM);
            y += 4;
        }
        y = labelledRow(g, GtLang.get("gtshaders.right.viewport_mode"), px, y, pw, (fx, fy, fw) ->
                dropdownButton(g, fx, fy, fw, ROW_H,
                        GtLang.get(v.isFullScreen()
                                ? "gtshaders.right.viewport_full" : "gtshaders.right.viewport_custom"),
                        () -> {
                            if (v.isFullScreen()) {
                                // 从全屏切到自定义时给一个居中的 60% 区域：
                                // 直接留在全屏的话框贴着屏幕边缘，控制点会被面板压住抓不到
                                v.set(0.2f, 0.2f, 0.8f, 0.8f);
                            } else {
                                v.reset();
                            }
                        }, mx, my));
        y = viewportRow(g, px, y, pw, "X", vpX, v.x0() * 100f, mx, my);
        y = viewportRow(g, px, y, pw, "Y", vpY, v.y0() * 100f, mx, my);
        y = viewportRow(g, px, y, pw, "W", vpW, v.width() * 100f, mx, my);
        y = viewportRow(g, px, y, pw, "H", vpH, v.height() * 100f, mx, my);

        y += 6;
        y = section(g, GtLang.get("gtshaders.right.section.time"), px, y, pw);
        y = labelledRow(g, "t", px, y, pw, (fx, fy, fw) -> {
            UiUtil.textLeft(g, this.font, String.format(Locale.ROOT, "%.2f s", PreviewRuntime.time()),
                    fx, fy + 7, Theme.TEXT_SUB);
            int bw = Math.min(64, fw);
            secondaryButton(g, fx + fw - bw, fy, bw, ROW_H, GtLang.get("gtshaders.tool.reset_time"),
                    PreviewRuntime::resetTime, mx, my);
        });
        // 接管世界时钟：核心着色器层直接读原版 GameTime，不接管的话播放/暂停对它们完全无效
        y = labelledRow(g, GtLang.get("gtshaders.right.world_clock"), px, y, pw, (fx, fy, fw) -> {
            boolean on = WorldClock.isFrozen();
            UiUtil.toggle(g, fx, fy + 4, 28, 14, on);
            addRegion(fx, fy, 28, ROW_H, () -> WorldClock.setFrozen(!on), null);
        });
        y = wrapText(g, GtLang.get(PreviewRuntime.isPackView()
                        ? "gtshaders.right.world_clock_pack_view"
                        : "gtshaders.right.world_clock_hint"),
                px, y, pw, Theme.TEXT_DIM);
    }

    /** 共有的两行：名字与启用开关。两种层都有，抽出来免得改一处漏一处。 */
    private int layerCommonRows(GuiGraphicsExtractor g, ShaderLayer layer, int px, int y, int pw,
                                int mx, int my) {
        y = labelledRow(g, GtLang.get("gtshaders.right.layer_name"), px, y, pw, (fx, fy, fw) -> {
            layerNameField.setTextIfUnfocused(layer.name());
            layerNameField.render(g, this.font, fx, fy, fw, ROW_H,
                    hovered(mx, my, fx, fy, fw, ROW_H), false);
            addFieldRegion(layerNameField, fx, fy, fw, ROW_H);
        });
        return labelledRow(g, GtLang.get("gtshaders.right.enabled"), px, y, pw, (fx, fy, fw) -> {
            UiUtil.toggle(g, fx, fy + 4, 28, 14, layer.isEnabled());
            addRegion(fx, fy, 28, ROW_H, () -> {
                layer.setEnabled(!layer.isEnabled());
                compileNow();
            }, null);
        });
    }

    /**
     * 右栏「设计」页里，选中实体轮廓层时的那一段。
     *
     * <p>刻意<b>不显示混合模式</b>：轮廓层的合成方式由引擎的 {@code blitAndBlendToTexture}
     * 定死为 alpha 混合，给一个拖了不起作用的下拉只会让人一直调下去。
     * 强度仍然显示——它乘在输出 alpha 上，是真正生效的整体淡出。
     *
     * <p>最上面那块发光状态是这一页的重点：轮廓链只在
     * {@code featureFrame.hasAnyOutline()} 为真时才被执行，一个发光实体都没有时
     * 这一层根本不跑，而那和「效果写错了」在画面上完全一样。
     */
    private int renderOutlineLayerDesign(GuiGraphicsExtractor g, ShaderLayer layer, int px, int y,
                                         int pw, int mx, int my) {
        y = section(g, GtLang.get("gtshaders.outline.layer_badge"), px, y, pw);
        y = wrapText(g, GtLang.get("gtshaders.outline.layer_note"), px, y, pw, Theme.TEXT_DIM);
        y += 8;

        boolean anyGlowing = this.minecraft != null && this.minecraft.level != null
                && GlowRuntime.anyGlowingVisible();
        if (!anyGlowing) {
            y = wrapText(g, GtLang.get("gtshaders.outline.no_glow_warning"), px, y, pw, Theme.ERROR);
            y += 4;
        }
        UiUtil.textLeft(g, this.font, GtLang.get("gtshaders.outline.glow_count", GlowRuntime.count()),
                px, y, Theme.TEXT_DIM);
        y += this.font.lineHeight + 4;
        if (GlowRuntime.count() > 0) {
            secondaryButton(g, px, y, pw, ROW_H, GtLang.get("gtshaders.outline.clear_glow"),
                    GlowRuntime::clear, mx, my);
            y += ROW_H + 6;
        }

        y += 4;
        y = section(g, GtLang.get("gtshaders.right.section.layer"), px, y, pw);
        y = layerCommonRows(g, layer, px, y, pw, mx, my);
        y = labelledRow(g, GtLang.get("gtshaders.right.strength"), px, y, pw, (fx, fy, fw) -> {
            UiUtil.slider(g, fx, fy + 8, Math.max(20, fw - 44), 5, layer.strength());
            addRegion(fx, fy, Math.max(20, fw - 44), ROW_H, null,
                    t -> layer.setStrength((float) t));
            UiUtil.textRight(g, this.font, String.format(Locale.ROOT, "%.2f", layer.strength()),
                    fx + fw, fy + (ROW_H - this.font.lineHeight) / 2 + 1, Theme.TEXT_SUB);
        });
        return y;
    }

    private int renderPostLayerDesign(GuiGraphicsExtractor g, ShaderLayer layer, int px, int y,
                                      int pw, int mx, int my) {
        y = section(g, GtLang.get("gtshaders.right.section.layer"), px, y, pw);
        y = layerCommonRows(g, layer, px, y, pw, mx, my);
        y = labelledRow(g, GtLang.get("gtshaders.right.bilinear"), px, y, pw, (fx, fy, fw) -> {
            UiUtil.toggle(g, fx, fy + 4, 28, 14, layer.isBilinear());
            addRegion(fx, fy, 28, ROW_H, () -> {
                layer.setBilinear(!layer.isBilinear());
                compileNow();
            }, null);
            if (hovered(mx, my, fx, fy, 28, ROW_H)) {
                setTooltip(GtLang.get("gtshaders.right.bilinear_hint"), mx, my);
            }
        });
        y = labelledRow(g, GtLang.get("gtshaders.right.blend_mode"), px, y, pw, (fx, fy, fw) ->
                dropdownButton(g, fx, fy, fw, ROW_H, GtLang.get(layer.blendMode().translationKey()), () -> {
                    layer.setBlendMode(layer.blendMode().next());
                    compileNow();
                }, mx, my));
        return labelledRow(g, GtLang.get("gtshaders.right.opacity"), px, y, pw, (fx, fy, fw) -> {
            int numW = 42;
            int sw = Math.max(20, fw - numW - 6);
            UiUtil.slider(g, fx, fy + 7, sw, 6, layer.strength());
            addRegion(fx, fy, sw, ROW_H, null, t -> layer.setStrength((float) t));
            TextField f = numberField("strength", () -> fmt(layer.strength()), layer::setStrength);
            f.render(g, this.font, fx + sw + 6, fy, numW, ROW_H,
                    hovered(mx, my, fx + sw + 6, fy, numW, ROW_H), true);
            addFieldRegion(f, fx + sw + 6, fy, numW, ROW_H);
        });
    }

    /**
     * 核心层的设计页：先说清楚「改的是什么」，再给那两行能改的东西。
     *
     * <p>作用范围那句提示是必要的：一份 {@code entity.fsh} 管住了全部生物、盔甲架、方块实体，
     * 想只影响其中一种在资源包这一层根本做不到。不写出来的话，第一个想「只让僵尸发光」的人
     * 会以为是自己写错了。
     */
    private int renderCoreLayerDesign(GuiGraphicsExtractor g, ShaderLayer layer, int px, int y,
                                      int pw, int mx, int my) {
        y = section(g, GtLang.get("gtshaders.right.section.core"), px, y, pw);

        ShaderKind.Entry kind = layer.kind();
        if (kind == null) {
            y = wrapText(g, GtLang.get("gtshaders.right.core_unknown", String.valueOf(layer.kindId())),
                    px, y, pw, Theme.ERROR);
        } else {
            UiUtil.textLeft(g, this.font, UiUtil.ellipsize(this.font, kind.displayName(), pw),
                    px, y, Theme.TEXT);
            y += this.font.lineHeight + 2;
            // 文件路径两个版本可能不同（clouds / rendertype_clouds），照 profile 显示实际那个
            UiUtil.textLeft(g, this.font,
                    UiUtil.ellipsize(this.font, kind.path() + ".fsh", pw),
                    px, y, Theme.TEXT_DIM);
            y += this.font.lineHeight + 4;
            if (!kind.displayNote().isEmpty()) {
                y = wrapText(g, kind.displayNote(), px, y, pw, Theme.TEXT_SUB);
            }
            y = wrapText(g, GtLang.get("gtshaders.right.core_scope_hint"), px, y + 2, pw, Theme.TEXT_DIM);
        }

        y += 8;
        y = section(g, GtLang.get("gtshaders.right.section.layer"), px, y, pw);
        y = layerCommonRows(g, layer, px, y, pw, mx, my);

        // 核心着色器的生效方式和后处理不一样，把这条路径明确摆出来
        y += 4;
        secondaryButton(g, px, y, pw, ROW_H, GtLang.get("gtshaders.menu.core_apply"),
                this::applyCoreShaders, mx, my);
        return y + ROW_H + 2;
    }

    /** 作用区域的一行：标签 + 滑块 + 百分比输入框，拖动与键入双向生效。 */
    private int viewportRow(GuiGraphicsExtractor g, int px, int y, int pw, String label,
                            TextField field, float percent, int mx, int my) {
        ViewportRect v = activeViewport();
        UiUtil.textLeft(g, this.font, label, px, y + (ROW_H - this.font.lineHeight) / 2 + 1, Theme.TEXT_SUB);
        int numW = 46;
        int sx = px + 18;
        int sw = Math.max(20, pw - 18 - numW - 6);
        UiUtil.slider(g, sx, y + 8, sw, 5, percent / 100f);
        addRegion(sx, y, sw, ROW_H, null, t -> {
            float val = (float) t;
            switch (label) {
                case "X" -> v.set(val, v.y0(), val + v.width(), v.y1());
                case "Y" -> v.set(v.x0(), val, v.x1(), val + v.height());
                case "W" -> v.set(v.x0(), v.y0(), v.x0() + Math.max(0.02f, val), v.y1());
                default -> v.set(v.x0(), v.y0(), v.x1(), v.y0() + Math.max(0.02f, val));
            }
        });
        field.setTextIfUnfocused(String.format(Locale.ROOT, "%.1f", percent));
        int fx = px + pw - numW;
        field.render(g, this.font, fx, y, numW, ROW_H, hovered(mx, my, fx, y, numW, ROW_H), true);
        addFieldRegion(field, fx, y, numW, ROW_H);
        return y + ROW_H + 4;
    }

    /**
     * 在画布上叠画当前活跃的锚点：十字准星 + 半径圈 + 槽位号。
     *
     * <p>这是整个锚点系统里唯一「能直接看见」的部分，也是它值得做的理由。
     * 锚点本身不产生任何像素——效果画在哪、画多大，全由这几个数决定，
     * 而在没有这层叠加之前，作者只能靠反复试效果去猜锚点到底落在哪。
     *
     * <p>只在锚点页画。别的页面上它是纯干扰——预览区留空的意义就是让人看清正在调的东西，
     * 而在调混合模式的时候，屏幕上多几个圈只会碍事。
     */
    private void renderAnchorOverlay(GuiGraphicsExtractor g, int mx, int my) {
        if (!isPanelShowing(DockPanel.ANCHOR)
                || this.minecraft == null || this.minecraft.level == null) {
            return;
        }
        // 编辑器打开时后处理链未必在跑（比如一层都没启用），锚点却应该照常预览，
        // 所以这里自己解算一次而不是等 PreviewRuntime 的那一次
        AnchorSlot[] all = AnchorRuntime.solve();
        int selBase = project.anchorSlotBase(project.selectedAnchorIndex());
        AnchorBinding sel = project.selectedAnchor();
        int selEnd = sel == null || selBase < 0 ? -1 : selBase + sel.maxSlots();

        for (int i = 0; i < all.length; i++) {
            AnchorSlot s = all[i];
            if (s.isEmpty()) {
                continue;
            }
            // UV 的 v 轴原点在下（GL 约定），而屏幕坐标原点在上，这里要翻一次
            int cx = Math.round(s.u() * this.width);
            int cy = Math.round((1f - s.v()) * this.height);
            boolean current = selBase >= 0 && i >= selBase && i < selEnd;
            // 选中绑定的锚点用强调色，其余压暗——一屏八个圈全一样亮的话反而找不着重点
            int color = current ? Theme.ACCENT : Theme.TEXT_DIM;
            // 被遮挡或在屏幕外的用错误色，一眼看出「锚点是有的，只是你看不见它」
            if (s.visibility() <= 0f) {
                color = Theme.ERROR;
            }

            // 半径圈：radius 是纵向 UV 单位，所以乘屏幕高度，横向再按宽高比换算回来
            int ry = Math.round(s.radius() * this.height);
            int rx = ry;
            if (ry > 2 && ry < this.height * 2) {
                UiUtil.ring(g, cx, cy, rx, ry, color);
            }

            int arm = 6;
            UiUtil.hLine(g, cx - arm, cx + arm, cy, color);
            UiUtil.vLine(g, cx, cy - arm, cy + arm, color);

            String tag = "#" + i;
            UiUtil.textLeft(g, this.font, tag, cx + arm + 2, cy - this.font.lineHeight / 2, color);
            if (hovered(mx, my, cx - arm, cy - arm, arm * 2, arm * 2)) {
                setTooltip(String.format(Locale.ROOT, "#%d  %.1fm  life %.2f",
                        i, s.distance(), s.life()), mx, my);
            }
        }
    }

    // ------------------------------------------------------------------ 锚点页

    /**
     * 右栏「锚点」页：把世界里的实体/物品/坐标绑到着色器能读的槽位上。
     *
     * <h3>为什么这一页要显示实时数值</h3>
     *
     * <p>底部那块「实时状态」不是调试信息，是这一页存在的理由。锚点是<b>看不见的中间量</b>——
     * 效果没出现时，可能是绑定没匹配到目标、可能是目标在屏幕外、可能是被墙挡住了、
     * 也可能是着色器里槽位号写错了。这四种情况在画面上长得一模一样（什么都没有），
     * 而把 UV、半径、距离、生命进度直接列出来，四种立刻分得开。
     *
     * <p>同理，每条绑定后面的槽位号 {@code #n} 必须显示：它由列表顺序决定，
     * 删掉或停用上面一条就会整体前移，而作者源码里 {@code gtAnchor(1)} 是写死的。
     */
    private void renderAnchorTab(GuiGraphicsExtractor g, int x0, int y, int w, int mx, int my) {
        int px = x0 + PAD;
        int pw = w - PAD * 2;

        y = section(g, GtLang.get("gtshaders.anchor.section.list"), px, y, pw);
        int used = project.anchorSlotsUsed();
        UiUtil.textLeft(g, this.font,
                GtLang.get("gtshaders.anchor.slots_used", used, AnchorSlot.SLOTS),
                px, y, used > AnchorSlot.SLOTS ? Theme.ERROR : Theme.TEXT_DIM);
        iconButton(g, px + pw - ICON, y - 4, Icons::plus,
                GtLang.get("gtshaders.anchor.add"), false, this::addAnchorBinding, mx, my);
        y += this.font.lineHeight + 6;

        List<AnchorBinding> anchors = project.anchors();
        if (anchors.isEmpty()) {
            y = wrapText(g, GtLang.get("gtshaders.anchor.empty"), px, y, pw, Theme.TEXT_DIM);
            y += 6;
        }
        for (int i = 0; i < anchors.size(); i++) {
            y = anchorRow(g, anchors.get(i), i, px, y, pw, mx, my);
        }

        AnchorBinding sel = project.selectedAnchor();
        if (sel == null) {
            return;
        }

        y += 8;
        y = section(g, GtLang.get("gtshaders.anchor.section.settings"), px, y, pw);
        y = renderAnchorSettings(g, sel, px, y, pw, mx, my);

        y += 8;
        y = section(g, GtLang.get("gtshaders.anchor.section.usage"), px, y, pw);
        y = renderAnchorUsage(g, sel, px, y, pw);

        y += 8;
        y = section(g, GtLang.get("gtshaders.anchor.section.live"), px, y, pw);
        renderAnchorLive(g, sel, px, y, pw);
    }

    /** 列表里的一条绑定：启停、名字、槽位号、模拟触发、删除。 */
    private int anchorRow(GuiGraphicsExtractor g, AnchorBinding b, int index,
                          int px, int y, int pw, int mx, int my) {
        int h = ROW_H + this.font.lineHeight + 2;
        boolean selected = index == project.selectedAnchorIndex();
        if (selected) {
            UiUtil.roundRect(g, px - 2, y - 2, pw + 4, h + 2, 4, Theme.PANEL_SUNKEN);
        }

        iconButtonBare(g, px, y, b.isEnabled() ? Icons::eye : Icons::eyeOff,
                GtLang.get(b.isEnabled() ? "gtshaders.left.hide" : "gtshaders.left.show"),
                b.isEnabled() ? Theme.TEXT_SUB : Theme.TEXT_DIM,
                () -> b.setEnabled(!b.isEnabled()), mx, my);

        int slot = project.anchorSlotBase(index);
        // 挤不进槽位的绑定要一眼看得出来，否则作者会一直等一个永远不会亮的锚点
        String tag = slot < 0 ? GtLang.get("gtshaders.anchor.slot_overflow") : "#" + slot;
        int tagW = UiText.width(tag) + 4;
        int actionsW = ICON * 2;
        int nameW = Math.max(20, pw - ICON - tagW - actionsW - 8);
        UiUtil.textLeft(g, this.font, UiUtil.ellipsize(this.font, b.name(), nameW),
                px + ICON + 2, y + (ROW_H - this.font.lineHeight) / 2 + 1,
                b.isEnabled() ? Theme.TEXT : Theme.TEXT_DIM);
        UiUtil.textLeft(g, this.font, tag, px + ICON + 2 + nameW,
                y + (ROW_H - this.font.lineHeight) / 2 + 1,
                slot < 0 ? Theme.ERROR : Theme.ACCENT);

        iconButtonBare(g, px + pw - ICON * 2, y, Icons::play,
                GtLang.get("gtshaders.anchor.simulate"), Theme.TEXT_SUB,
                () -> simulateAnchor(b), mx, my);
        iconButtonBare(g, px + pw - ICON, y, Icons::trash,
                GtLang.get("gtshaders.anchor.remove"), Theme.TEXT_DIM,
                () -> removeAnchorBinding(index), mx, my);

        // 第二行是配置摘要，省得为了看一眼「这条绑的是谁」而逐条点开
        String summary = b.source().displayName() + " · " + b.trigger().displayName()
                + (b.selector().isEmpty() ? "" : " · " + b.selector());
        UiUtil.textLeft(g, this.font,
                UiUtil.ellipsize(this.font, summary, pw - ICON - 2), px + ICON + 2, y + ROW_H,
                Theme.TEXT_DIM);

        addRegion(px + ICON, y, pw - ICON * 3, h, () -> {
            project.setSelectedAnchorIndex(index);
            syncAnchorFields();
        }, null);
        return y + h + 4;
    }

    private int renderAnchorSettings(GuiGraphicsExtractor g, AnchorBinding b,
                                     int px, int y, int pw, int mx, int my) {
        y = labelledRow(g, GtLang.get("gtshaders.anchor.name"), px, y, pw, (fx, fy, fw) -> {
            anchorNameField.setTextIfUnfocused(b.name());
            anchorNameField.render(g, this.font, fx, fy, fw, ROW_H,
                    hovered(mx, my, fx, fy, fw, ROW_H), false);
            addFieldRegion(anchorNameField, fx, fy, fw, ROW_H);
        });

        y = labelledRow(g, GtLang.get("gtshaders.anchor.source"), px, y, pw, (fx, fy, fw) ->
                dropdownButton(g, fx, fy, fw, ROW_H, b.source().displayName(),
                        () -> b.setSource(next(AnchorBinding.Source.values(), b.source())), mx, my));

        if (b.source().needsSelector()) {
            y = labelledRow(g, GtLang.get("gtshaders.anchor.selector"), px, y, pw, (fx, fy, fw) -> {
                anchorSelectorField.setTextIfUnfocused(b.selector());
                anchorSelectorField.render(g, this.font, fx, fy, fw, ROW_H,
                        hovered(mx, my, fx, fy, fw, ROW_H), false);
                addFieldRegion(anchorSelectorField, fx, fy, fw, ROW_H);
            });
            y = wrapText(g, GtLang.get(selectorHintKey(b.source())), px, y, pw, Theme.TEXT_DIM);
            y += 4;
        }

        // 「出去绑」紧跟着来源和选择器：这两行是整个面板里唯一没法在这儿填好的东西
        // （UUID 记不住，实体 id 要背，坐标得先开 F3 抄），而它们恰好是最先要填的。
        // 人卡在这儿的时候，出路应该就在视线正前方，而不是滚到面板底部去找
        primaryButton(g, px, y, pw, ROW_H, GtLang.get("gtshaders.anchor.go_bind"),
                () -> enterBindMode(b), mx, my);
        y += ROW_H + 8;

        y = labelledRow(g, GtLang.get("gtshaders.anchor.trigger"), px, y, pw, (fx, fy, fw) ->
                dropdownButton(g, fx, fy, fw, ROW_H, b.trigger().displayName(), () -> {
                    b.setTrigger(next(AnchorBinding.Trigger.values(), b.trigger()));
                    // 换触发器就把这条绑定正在播的事件清掉：上一个触发器留下的事件
                    // 按新时长算生命进度会得到莫名其妙的结果
                    AnchorRuntime.stop(b);
                }, mx, my));

        // 「配好了但永远不会生效」的组合必须由界面主动说出来：锚点这条链上出问题时
        // 没有任何报错——着色器照常编译、画面照常渲染，只是效果不出现
        String warn = b.warning();
        if (!warn.isEmpty()) {
            y = wrapText(g, GtLang.get(warn), px, y, pw, Theme.WARNING);
            y += 6;
        }

        // 「钉住还是跟随」只有事件型触发器才有意义，常驻的锚点每帧都在跟着目标算
        if (b.trigger().isEvent()) {
            y = labelledRow(g, GtLang.get("gtshaders.anchor.follow"), px, y, pw, (fx, fy, fw) ->
                    dropdownButton(g, fx, fy, fw, ROW_H,
                            GtLang.get(b.isStick()
                                    ? "gtshaders.anchor.follow.stick" : "gtshaders.anchor.follow.track"),
                            () -> b.setStick(!b.isStick()), mx, my));
            y = anchorSlider(g, GtLang.get("gtshaders.anchor.duration"), px, y, pw,
                    b.duration(), 0f, 10f,
                    b.isPersistent() ? GtLang.get("gtshaders.anchor.duration_persistent")
                            : String.format(Locale.ROOT, "%.2f s", b.duration()),
                    b::setDuration);
            y = labelledRow(g, GtLang.get("gtshaders.anchor.easing"), px, y, pw, (fx, fy, fw) ->
                    dropdownButton(g, fx, fy, fw, ROW_H, b.easing().displayName(),
                            () -> b.setEasing(next(AnchorBinding.Easing.values(), b.easing())), mx, my));
        }

        y = anchorSlider(g, GtLang.get("gtshaders.anchor.radius"), px, y, pw,
                b.worldRadius(), 0.05f, 16f,
                String.format(Locale.ROOT, "%.2f", b.worldRadius()), b::setWorldRadius);
        // 紧跟着世界半径：这两个说的是同一件事的两端——一个是世界里多大，
        // 一个是不管多近、屏幕上最多占多大。分开放的话，撞上「走近就糊满屏」的人
        // 不会想到解药在另一个地方
        y = anchorSlider(g, GtLang.get("gtshaders.anchor.max_screen_radius"), px, y, pw,
                b.maxScreenRadius(), 0f, 2f,
                b.maxScreenRadius() <= 0f
                        ? GtLang.get("gtshaders.anchor.screen_radius_unlimited")
                        : String.format(Locale.ROOT, "%.2f", b.maxScreenRadius()),
                b::setMaxScreenRadius);
        y = anchorSlider(g, GtLang.get("gtshaders.anchor.strength"), px, y, pw,
                b.strength(), 0f, 1f,
                String.format(Locale.ROOT, "%.2f", b.strength()), b::setStrength);
        y = anchorSlider(g, GtLang.get("gtshaders.anchor.y_offset"), px, y, pw,
                b.yOffset(), -2f, 8f,
                String.format(Locale.ROOT, "%.2f", b.yOffset()), b::setYOffset);
        y = anchorSlider(g, GtLang.get("gtshaders.anchor.max_slots"), px, y, pw,
                b.maxSlots(), 1f, AnchorSlot.SLOTS,
                String.valueOf(b.maxSlots()), v -> b.setMaxSlots(Math.round(v)));
        y = anchorSlider(g, GtLang.get("gtshaders.anchor.max_distance"), px, y, pw,
                b.maxDistance(), 0f, 128f,
                b.maxDistance() <= 0 ? GtLang.get("gtshaders.anchor.distance_unlimited")
                        : String.format(Locale.ROOT, "%.0f", b.maxDistance()),
                b::setMaxDistance);

        y = labelledRow(g, GtLang.get("gtshaders.anchor.occlusion"), px, y, pw, (fx, fy, fw) -> {
            UiUtil.toggle(g, fx, fy + 4, 28, 14, b.isOcclusion());
            addRegion(fx, fy, 28, ROW_H, () -> b.setOcclusion(!b.isOcclusion()), null);
        });

        y = anchorEmitterSection(g, b, px, y, pw, mx, my);

        y += 4;
        int half = (pw - 6) / 2;
        primaryButton(g, px, y, half, ROW_H, GtLang.get("gtshaders.anchor.simulate"),
                () -> simulateAnchor(b), mx, my);
        secondaryButton(g, px + half + 6, y, pw - half - 6, ROW_H,
                GtLang.get("gtshaders.anchor.stop"), () -> AnchorRuntime.stop(b), mx, my);
        return y + ROW_H + 6;
    }

    /**
     * 载体扩展那一节：朝向、子类型、两个自定义值、自转。
     *
     * <h2>为什么按需展开</h2>
     *
     * <p>这五行只对「载体特效」那一类效果有意义，而锚点面板本来已经十几行了。
     * 判据是<b>工程里有没有哪一层真的在读 {@code gtEmitter}</b>——和 {@code AnchorHud}
     * 那句「没有效果层在读锚点」同一个思路：配了但没人读，画面上不会有任何变化，
     * 而这一点从面板上完全看不出来，只能由界面主动说。
     *
     * <p>没人读时也不是整段藏掉，而是留一行灰字说明。整段消失的话，
     * 照着文档来配载体的人会以为是版本不对或者装错了。
     */
    private int anchorEmitterSection(GuiGraphicsExtractor g, AnchorBinding b,
                                     int px, int y, int pw, int mx, int my) {
        y += 6;
        y = section(g, GtLang.get("gtshaders.anchor.emitter_section"), px, y, pw);
        if (!PreviewRuntime.usesEmitters()) {
            return wrapText(g, GtLang.get("gtshaders.anchor.emitter_unused"), px, y, pw, Theme.TEXT_DIM);
        }

        y = labelledRow(g, GtLang.get("gtshaders.anchor.facing"), px, y, pw, (fx, fy, fw) ->
                dropdownButton(g, fx, fy, fw, ROW_H, b.facing().displayName(),
                        () -> b.setFacing(next(AnchorBinding.Facing.values(), b.facing())), mx, my));

        if (b.facing() == AnchorBinding.Facing.TARGET) {
            // 目标身上没有视线（方块坐标、视线落点）时会退回下面那两个角度。
            // 不说的话，那种绑定配了 TARGET 却纹丝不动，看不出是回落了
            y = wrapText(g, GtLang.get("gtshaders.anchor.facing_target_hint"), px, y, pw, Theme.TEXT_DIM);
            y += 4;
        }
        if (b.facing() == AnchorBinding.Facing.FIXED || b.facing() == AnchorBinding.Facing.TARGET) {
            y = anchorSlider(g, GtLang.get("gtshaders.anchor.yaw"), px, y, pw,
                    b.yaw(), -180f, 180f,
                    String.format(Locale.ROOT, "%.0f°", b.yaw()), b::setYaw);
            y = anchorSlider(g, GtLang.get("gtshaders.anchor.pitch"), px, y, pw,
                    b.pitch(), -90f, 90f,
                    String.format(Locale.ROOT, "%.0f°", b.pitch()), b::setPitch);
            y = secondaryRow(g, px, y, pw, GtLang.get("gtshaders.anchor.face_from_player"),
                    () -> faceFromPlayer(b), mx, my);
        }

        y = anchorSlider(g, GtLang.get("gtshaders.anchor.emitter_type"), px, y, pw,
                b.emitterType(), 0f, EmitterSlot.MAX_TYPES - 1,
                String.valueOf(b.emitterType()), v -> b.setEmitterType(Math.round(v)));
        y = anchorSlider(g, GtLang.get("gtshaders.anchor.custom1"), px, y, pw,
                b.custom1(), -4f, 4f,
                String.format(Locale.ROOT, "%.2f", b.custom1()), b::setCustom1);
        y = anchorSlider(g, GtLang.get("gtshaders.anchor.custom2"), px, y, pw,
                b.custom2(), -4f, 4f,
                String.format(Locale.ROOT, "%.2f", b.custom2()), b::setCustom2);
        y = anchorSlider(g, GtLang.get("gtshaders.anchor.spin"), px, y, pw,
                b.spin(), -360f, 360f,
                String.format(Locale.ROOT, "%.0f°/s", b.spin()), b::setSpin);
        return y;
    }

    /**
     * 关掉编辑器，带着这条绑定进世界内绑定模式，退出时自动回来。
     *
     * <p>没有这个按钮的话，「在面板里发现要换个目标」的完整路径是
     * Esc → Home → 绑 → Esc → Insert → 翻回锚点页 → 找回刚才那一条，六步里有两步
     * 是纯粹的导航。而这条路径恰恰是调锚点效果时走得最多的一条。
     *
     * <p>回程是一个闭包传给 {@code BindMode}，这样 {@code runtime} 包不必反过来依赖 {@code ui}。
     */
    private void enterBindMode(AnchorBinding b) {
        if (this.minecraft == null) {
            return;
        }
        Minecraft mc = this.minecraft;
        Screen back = this.parent;
        ShaderProject p = this.project;
        int index = p.anchors().indexOf(b);
        // 先关界面再进模式：模式的左右键靠 mixin 拦 Minecraft 的输入路径，
        // 而那条路径在有 Screen 打开时压根不会被走到
        mc.gui.setScreen(null);
        BindMode.enter(p, index, () -> mc.gui.setScreen(new EditorScreen(back)));
    }

    /** 把玩家此刻的朝向抄进这条绑定。手填 yaw/pitch 几乎不可能一次对，看着调才对。 */
    private void faceFromPlayer(AnchorBinding b) {
        if (this.minecraft == null || this.minecraft.player == null) {
            return;
        }
        b.setYaw(this.minecraft.player.getYRot());
        b.setPitch(this.minecraft.player.getXRot());
        b.setFacing(AnchorBinding.Facing.FIXED);
    }

    /** 一行只占右半边的次要按钮，用来配在滑条组下面做一次性动作。 */
    private int secondaryRow(GuiGraphicsExtractor g, int px, int y, int pw, String label,
                             Runnable action, int mx, int my) {
        return labelledRow(g, "", px, y, pw, (fx, fy, fw) ->
                secondaryButton(g, fx, fy, fw, ROW_H, label, action, mx, my));
    }

    /** 一行滑块 + 右侧数值。锚点的量纲各不相同，所以数值文本由调用方自己格式化。 */
    private int anchorSlider(GuiGraphicsExtractor g, String label, int px, int y, int pw,
                             float value, float min, float max, String text,
                             java.util.function.Consumer<Float> apply) {
        return labelledRow(g, label, px, y, pw, (fx, fy, fw) -> {
            int numW = Math.min(56, fw / 2);
            int sw = Math.max(20, fw - numW - 4);
            float t = max > min ? (value - min) / (max - min) : 0f;
            UiUtil.slider(g, fx, fy + 8, sw, 5, Math.max(0f, Math.min(1f, t)));
            addRegion(fx, fy, sw, ROW_H, null, d -> apply.accept(min + (float) d * (max - min)));
            UiUtil.textRight(g, this.font, text, fx + fw,
                    fy + (ROW_H - this.font.lineHeight) / 2 + 1, Theme.TEXT_SUB);
        });
    }

    /** 告诉作者这条绑定在源码里该怎么写。槽位号是算出来的，不是让人自己数的。 */
    private int renderAnchorUsage(GuiGraphicsExtractor g, AnchorBinding b, int px, int y, int pw) {
        int slot = project.anchorSlotBase(project.selectedAnchorIndex());
        if (slot < 0) {
            return wrapText(g, GtLang.get("gtshaders.anchor.usage_overflow"), px, y, pw, Theme.ERROR);
        }
        for (String call : new String[]{
                "gtAnchorUV(" + slot + ")",
                "gtAnchorRadius(" + slot + ")",
                "gtAnchorRange(" + slot + ")",
                "gtAnchorStrength(" + slot + ")",
                "gtAnchorLife(" + slot + ")",
                "gtAnchorVisible(" + slot + ")"}) {
            UiUtil.textLeft(g, this.font, call, px, y, Theme.TEXT_SUB);
            y += this.font.lineHeight + 1;
        }
        // 载体那几个只在配了朝向时列出来：没配朝向的绑定写 gtEmitterDir 会恒得到 (0,0)，
        // 把它摆在这里等于推荐一个不会动的调用
        if (b.usesFacing() || b.emitterType() != 0 || b.custom1() != 0f || b.custom2() != 0f) {
            for (String call : new String[]{
                    "gtEmitterLocal(" + slot + ")",
                    "gtEmitterDir(" + slot + ")",
                    "gtEmitterFacing(" + slot + ")",
                    "gtEmitterType(" + slot + ")",
                    "gtEmitterCustom1(" + slot + ")"}) {
                UiUtil.textLeft(g, this.font, call, px, y, Theme.TEXT_SUB);
                y += this.font.lineHeight + 1;
            }
        }
        y += 4;
        // 这一条必须写在最显眼的地方：导出成纯资源包之后锚点会静悄悄失效，
        // 而「效果在编辑器里好好的、发出去就不动了」是最难自己想明白的一类问题
        return wrapText(g, GtLang.get("gtshaders.anchor.export_note"), px, y, pw, Theme.TEXT_DIM);
    }

    /** 这条绑定当前解出来的每个槽位的实时数值。 */
    private void renderAnchorLive(GuiGraphicsExtractor g, AnchorBinding b, int px, int y, int pw) {
        if (this.minecraft == null || this.minecraft.level == null) {
            wrapText(g, GtLang.get("gtshaders.canvas.need_world"), px, y, pw, Theme.TEXT_DIM);
            return;
        }
        // 判据与局内按 B 键那条路径同源（AnchorCheck），否则同一个问题在面板和聊天里
        // 会得到两种说法——那比没有提示更糟
        AnchorCheck.Report report = AnchorCheck.check(project, b);
        if (!report.ok()) {
            wrapText(g, wiringText(b, report), px, y, pw, Theme.ERROR);
            return;
        }

        int base = report.slot();
        AnchorSlot[] all = AnchorRuntime.slots();
        boolean any = false;
        for (int i = 0; i < b.maxSlots() && base + i < all.length; i++) {
            AnchorSlot s = all[base + i];
            if (s.isEmpty()) {
                continue;
            }
            any = true;
            UiUtil.textLeft(g, this.font, String.format(Locale.ROOT,
                            "#%d  uv %.3f,%.3f  r %.3f", base + i, s.u(), s.v(), s.radius()),
                    px, y, Theme.TEXT_SUB);
            y += this.font.lineHeight + 1;
            UiUtil.textLeft(g, this.font, String.format(Locale.ROOT,
                            "     %.1fm  life %.2f  vis %.0f", s.distance(), s.life(), s.visibility()),
                    px, y, s.visibility() > 0 ? Theme.TEXT_DIM : Theme.ERROR);
            y += this.font.lineHeight + 3;
            if (s.visibility() <= 0f) {
                y = wrapText(g, GtLang.get("gtshaders.anchor.check.occluded"), px, y, pw, Theme.TEXT_DIM);
            }
        }
        if (!any) {
            // 配置链是通的，那就只剩「目标没解算出来」这一种可能，可以直说
            wrapText(g, GtLang.get("gtshaders.anchor.check.unresolved"), px, y, pw, Theme.TEXT_DIM);
        }
    }

    /** 配置链上第一个断掉的环节。文案与 {@code GTShadersClient} 那侧共用同一批键。 */
    private String wiringText(AnchorBinding b, AnchorCheck.Report report) {
        return switch (report.stage()) {
            case DISABLED -> GtLang.get("gtshaders.anchor.trigger_disabled", b.name());
            case NO_SLOT -> GtLang.get("gtshaders.anchor.check.no_slot");
            case NO_PREVIEW -> GtLang.get("gtshaders.bind.no_preview");
            case NO_ANCHOR_LAYER -> GtLang.get("gtshaders.anchor.quick.no_layer");
            case SLOT_UNREAD -> GtLang.get("gtshaders.anchor.check.slot_unread",
                    String.valueOf(report.slot()), b.name());
            case OK -> "";
        };
    }


    private static String selectorHintKey(AnchorBinding.Source source) {
        return switch (source) {
            case ENTITY_TYPE -> "gtshaders.anchor.hint.entity_type";
            case ENTITY_UUID -> "gtshaders.anchor.hint.entity_uuid";
            case ITEM_ENTITY -> "gtshaders.anchor.hint.item_entity";
            case HELD_ITEM -> "gtshaders.anchor.hint.held_item";
            default -> "gtshaders.anchor.hint.block_pos";
        };
    }

    /** 循环切到下一个枚举值。界面上没有真正的下拉控件，点一下换一个足够用且不遮挡预览。 */
    private static <T extends Enum<T>> T next(T[] values, T current) {
        return values[(current.ordinal() + 1) % values.length];
    }

    private void addAnchorBinding() {
        AnchorBinding b = AnchorBinding.withTrigger(
                GtLang.get("gtshaders.anchor.default_name", project.anchorCount() + 1),
                AnchorBinding.Trigger.ALWAYS);
        project.addAnchor(b);
        syncAnchorFields();
        // 锚点是纯 uniform 路径，加一条绑定不需要重编译——这也是它能做到拖着调的原因
        showToast(GtLang.get("gtshaders.anchor.added",
                project.anchorSlotBase(project.anchorCount() - 1)), false);
    }

    private void removeAnchorBinding(int index) {
        AnchorBinding b = index >= 0 && index < project.anchorCount()
                ? project.anchors().get(index) : null;
        if (b != null) {
            AnchorRuntime.stop(b);
        }
        project.removeAnchor(index);
        syncAnchorFields();
    }

    private void simulateAnchor(AnchorBinding b) {
        if (this.minecraft == null || this.minecraft.level == null) {
            showToast(GtLang.get("gtshaders.canvas.need_world"), true);
            return;
        }
        AnchorRuntime.simulate(b);
        showToast(GtLang.get("gtshaders.anchor.simulated", b.name()), false);
    }

    /** 切换选中的绑定时，两个输入框要跟着换内容，否则会把上一条的名字写到这一条上。 */
    private void syncAnchorFields() {
        AnchorBinding b = project.selectedAnchor();
        anchorNameField.blur();
        anchorSelectorField.blur();
        anchorNameField.setTextIfUnfocused(b == null ? "" : b.name());
        anchorSelectorField.setTextIfUnfocused(b == null ? "" : b.selector());
    }

    private void renderPipelineTab(GuiGraphicsExtractor g, int x0, int y, int w, int mx, int my) {
        int px = x0 + PAD;
        int pw = w - PAD * 2;

        y = section(g, GtLang.get("gtshaders.pipeline.passes"), px, y, pw);
        List<ShaderLayer> enabled = project.enabledLayers();
        for (int i = 0; i < enabled.size(); i++) {
            ShaderLayer l = enabled.get(i);
            boolean readsMain = i % 2 == 0;
            UiUtil.textLeft(g, this.font, GtLang.get("gtshaders.pipeline.pass_n", i), px, y, Theme.TEXT);
            UiUtil.textRight(g, this.font, UiUtil.ellipsize(this.font, l.name(), pw / 2),
                    px + pw, y, Theme.TEXT_SUB);
            y += this.font.lineHeight + 2;
            UiUtil.textLeft(g, this.font,
                    (readsMain ? "main" : "swap") + "  ->  " + (readsMain ? "swap" : "main"),
                    px + 8, y, Theme.TEXT_DIM);
            y += this.font.lineHeight + 6;
        }
        if (enabled.size() % 2 == 1) {
            UiUtil.textLeft(g, this.font, "blit  swap  ->  main", px + 8, y, Theme.TEXT_DIM);
            y += this.font.lineHeight + 6;
        }
        if (enabled.isEmpty()) {
            UiUtil.textLeft(g, this.font, GtLang.get("gtshaders.pipeline.disabled"), px, y, Theme.TEXT_DIM);
            y += this.font.lineHeight + 6;
        }
        if (GpuProfiler.isEnabled()) {
            y += 2;
            y = section(g, GtLang.get("gtshaders.debug.profiler"), px, y, pw);
            y = renderTimings(g, px, y, pw, false);
        }

        ShaderLayer sel = project.selected();
        if (sel == null) {
            return;
        }
        y += 4;
        y = section(g, GtLang.get("gtshaders.pipeline.uniform_layout"), px, y, pw);
        for (String sys : new String[]{"vec4 GTSystem", "vec4 GTLayer", "vec4 GTViewport"}) {
            UiUtil.textLeft(g, this.font, sys, px, y, Theme.TEXT_DIM);
            y += this.font.lineHeight + 2;
        }
        for (ShaderParam p : mc.GTedd.cn.gtshaders.codegen.GlslCodegen.orderParams(sel.params())) {
            UiUtil.textLeft(g, this.font,
                    UiUtil.ellipsize(this.font, p.type().glslType() + " " + p.name(), pw),
                    px, y, Theme.TEXT_SUB);
            y += this.font.lineHeight + 2;
        }

        y += 6;
        int lines = sel.generatedSource().isEmpty() ? 0 : sel.generatedSource().split("\n", -1).length;
        UiUtil.textLeft(g, this.font, GtLang.get("gtshaders.pipeline.generated_lines", lines),
                px, y, Theme.TEXT_DIM);
        y += this.font.lineHeight + 2;
        UiUtil.textLeft(g, this.font, GtLang.get("gtshaders.pipeline.header_lines", sel.headerLineCount()),
                px, y, Theme.TEXT_DIM);
    }

    private void renderExportTab(GuiGraphicsExtractor g, int x0, int y, int w, int mx, int my) {
        int px = x0 + PAD;
        int pw = w - PAD * 2;

        y = section(g, GtLang.get("gtshaders.right.tab.export"), px, y, pw);
        // 目标版本只剩一个，做成只读的一行：它仍然值得显示——导出的包能在哪个版本上加载，
        // 是拿到包的人第一个要问的问题
        y = labelledRow(g, GtLang.get("gtshaders.export.profile"), px, y, pw, (fx, fy, fw) ->
                UiUtil.textLeft(g, this.font, GtLang.get(project.exportProfile().translationKey()),
                        fx + 4, fy + (ROW_H - 8) / 2, Theme.TEXT));
        y = labelledRow(g, GtLang.get("gtshaders.export.mode"), px, y, pw, (fx, fy, fw) ->
                dropdownButton(g, fx, fy, fw, ROW_H,
                        GtLang.get(exportOptions.mode().translationKey()),
                        () -> {
                            exportOptions = exportOptions.withMode(exportOptions.mode().next());
                            // 挂载位置随之切换：end_of_frame 排最前，指令触发排最后
                            PreviewRuntime.setMountAsEndOfFrame(
                                    exportOptions.mode() == ResourcePackExporter.Mode.END_OF_FRAME);
                        }, mx, my));

        if (exportOptions.mode() == ResourcePackExporter.Mode.POST_EFFECT_COMMAND) {
            y = labelledRow(g, GtLang.get("gtshaders.export.id"), px, y, pw, (fx, fy, fw) -> {
                effectIdField.render(g, this.font, fx, fy, fw, ROW_H,
                        hovered(mx, my, fx, fy, fw, ROW_H), false);
                addFieldRegion(effectIdField, fx, fy, fw, ROW_H);
            });
        }

        // 资源包视角：让预览拿到的每个 uniform 都和纯资源包加载时一样——
        // 时间走原版时钟、锚点 / 轨迹归零。这是「所见即所得」的最后一道闸，放在导出按钮旁边
        y = labelledRow(g, GtLang.get("gtshaders.export.pack_view"), px, y, pw, (fx, fy, fw) -> {
            boolean on = PreviewRuntime.isPackView();
            UiUtil.toggle(g, fx, fy + 4, 28, 14, on);
            addRegion(fx, fy, 28, ROW_H, () -> PreviewRuntime.setPackView(!on), null);
        });
        y = wrapText(g, GtLang.get("gtshaders.export.pack_view_hint"), px, y, pw, Theme.TEXT_DIM);
        List<PackParity.Note> notes = PreviewRuntime.parityNotes();
        if (!notes.isEmpty()) {
            y += 2;
            y = wrapText(g, GtLang.get("gtshaders.export.parity_title"), px, y, pw, Theme.TEXT_SUB);
            for (PackParity.Note n : notes) {
                y = wrapText(g, "· " + n, px, y, pw, Theme.TEXT_DIM);
            }
        }

        y += 4;
        String hint = ResourcePackExporter.usageHint(exportOptions,
                exportOptions.mode() == ResourcePackExporter.Mode.POST_EFFECT_COMMAND
                        ? exportOptions.namespace() : "minecraft",
                switch (exportOptions.mode()) {
                    case END_OF_FRAME -> "end_of_frame";
                    default -> ResourcePackExporter.sanitize(exportOptions.effectId());
                });
        y = wrapText(g, hint, px, y, pw, Theme.TEXT_SUB);
        y += 8;
        final int runY = y;
        primaryButton(g, px, y, pw, ROW_H + 4, GtLang.get("gtshaders.export.run"),
                () -> openExportMenu(px - EXPORT_MENU_W - 6, runY - 8), mx, my);
        y += ROW_H + 8;
        // 真实加载验证：装进 resourcepacks/ 走原版重载。验证中这个按钮变成「回到预览」
        if (PackVerify.isActive()) {
            secondaryButton(g, px, y, pw, ROW_H + 4, GtLang.get("gtshaders.verify.stop"),
                    this::stopVerify, mx, my);
        } else {
            secondaryButton(g, px, y, pw, ROW_H + 4, GtLang.get("gtshaders.verify.run"),
                    this::verifyPack, mx, my);
        }
        y += ROW_H + 6;
        y = wrapText(g, GtLang.get("gtshaders.verify.hint"), px, y, pw, Theme.TEXT_DIM);
    }

    // ---- 调试页 ----

    /**
     * 右栏「调试」页：点画面取样、变量探针、异常检查、分层 GPU 计时。
     *
     * <p>思路借鉴 SHADERed（dfranx，MIT）的像素检查、Frame Analysis 与 Profiler，未复制代码；
     * 实现上探针与异常检查都让 GPU 真算，见 {@code DebugInstrument}。
     * 全部作用在<b>选中的那一层</b>上，而且只支持启用中的后处理层——
     * 核心着色器与轮廓层走的不是同一条链，插桩和截断都不适用。
     */
    private void renderDebugTab(GuiGraphicsExtractor g, int x0, int y, int w, int mx, int my) {
        int px = x0 + PAD;
        int pw = w - PAD * 2;
        ShaderLayer sel = project.selected();
        boolean usable = sel != null && sel.isPost() && project.enabledPostLayers().contains(sel);
        DebugSession.Mode mode = DebugSession.mode();

        // ---- 取样 ----
        y = section(g, GtLang.get("gtshaders.debug.inspect"), px, y, pw);
        y = labelledRow(g, GtLang.get("gtshaders.debug.inspect_tool"), px, y, pw, (fx, fy, fw) -> {
            UiUtil.toggle(g, fx, fy + 4, 28, 14, inspectTool);
            addRegion(fx, fy, 28, ROW_H, () -> inspectTool = !inspectTool, null);
        });
        y = wrapText(g, GtLang.get(switch (mode) {
            case VALUE -> "gtshaders.debug.inspect_hint_value";
            case UB -> "gtshaders.debug.inspect_hint_ub";
            case NONE -> "gtshaders.debug.inspect_hint_layers";
        }), px, y, pw, Theme.TEXT_DIM);
        y = renderInspection(g, px, y + 4, pw, mx, my);

        // ---- 变量探针 ----
        y += 8;
        y = section(g, GtLang.get("gtshaders.debug.probe"), px, y, pw);
        if (!usable && mode == DebugSession.Mode.NONE) {
            y = wrapText(g, GtLang.get("gtshaders.debug.need_layer"), px, y, pw, Theme.TEXT_DIM);
        }
        y = labelledRow(g, GtLang.get("gtshaders.debug.expr"), px, y, pw, (fx, fy, fw) -> {
            probeExprField.render(g, this.font, fx, fy, fw, ROW_H, hovered(mx, my, fx, fy, fw, ROW_H), false);
            addFieldRegion(probeExprField, fx, fy, fw, ROW_H);
        });
        y = labelledRow(g, GtLang.get("gtshaders.debug.line"), px, y, pw, (fx, fy, fw) -> {
            int bw = Math.min(fw - 50, UiText.width(GtLang.get("gtshaders.debug.from_cursor")) + 14);
            probeLineField.render(g, this.font, fx, fy, fw - bw - 6, ROW_H,
                    hovered(mx, my, fx, fy, fw - bw - 6, ROW_H), true);
            addFieldRegion(probeLineField, fx, fy, fw - bw - 6, ROW_H);
            secondaryButton(g, fx + fw - bw, fy, bw, ROW_H, GtLang.get("gtshaders.debug.from_cursor"),
                    this::fillProbeFromCursor, mx, my);
        });
        if (mode == DebugSession.Mode.VALUE) {
            ShaderLayer t = DebugSession.target();
            y = wrapText(g, GtLang.get("gtshaders.debug.probe_active", t == null ? "-" : t.name(),
                    DebugSession.probeLine(), DebugSession.probeExpression()), px, y, pw, Theme.ACCENT);
            y = labelledRow(g, GtLang.get("gtshaders.debug.range"), px, y, pw, (fx, fy, fw) -> {
                int half = (fw - 6) / 2;
                rangeLoField.setTextIfUnfocused(trimNumber(DebugSession.rangeLo()));
                rangeHiField.setTextIfUnfocused(trimNumber(DebugSession.rangeHi()));
                rangeLoField.render(g, this.font, fx, fy, half, ROW_H, hovered(mx, my, fx, fy, half, ROW_H), true);
                addFieldRegion(rangeLoField, fx, fy, half, ROW_H);
                rangeHiField.render(g, this.font, fx + half + 6, fy, half, ROW_H,
                        hovered(mx, my, fx + half + 6, fy, half, ROW_H), true);
                addFieldRegion(rangeHiField, fx + half + 6, fy, half, ROW_H);
            });
            y = wrapText(g, GtLang.get("gtshaders.debug.probe_legend"), px, y, pw, Theme.TEXT_DIM);
            y += 4;
            secondaryButton(g, px, y, pw, ROW_H + 2, GtLang.get("gtshaders.debug.stop"), this::stopDebug, mx, my);
            y += ROW_H + 8;
        } else {
            y += 4;
            primaryButton(g, px, y, pw, ROW_H + 2, GtLang.get("gtshaders.debug.probe_start"),
                    this::startProbeFromFields, mx, my);
            y += ROW_H + 6;
            y = wrapText(g, GtLang.get("gtshaders.debug.probe_hint"), px, y, pw, Theme.TEXT_DIM);
        }

        // ---- 异常检查 ----
        y += 8;
        y = section(g, GtLang.get("gtshaders.debug.ub"), px, y, pw);
        y = wrapText(g, GtLang.get("gtshaders.debug.ub_hint"), px, y, pw, Theme.TEXT_DIM);
        y += 4;
        if (mode == DebugSession.Mode.UB) {
            y = wrapText(g, GtLang.get("gtshaders.debug.ub_legend"), px, y, pw, Theme.ACCENT);
            y += 2;
            secondaryButton(g, px, y, pw, ROW_H + 2, GtLang.get("gtshaders.debug.stop"), this::stopDebug, mx, my);
        } else {
            secondaryButton(g, px, y, pw, ROW_H + 2, GtLang.get("gtshaders.debug.ub_start"), () -> {
                ShaderLayer layer = project.selected();
                if (layer != null) {
                    showDebugResult(DebugSession.startUb(project, layer));
                }
            }, mx, my);
        }
        y += ROW_H + 8;

        // ---- GPU 计时 ----
        y += 4;
        y = section(g, GtLang.get("gtshaders.debug.profiler"), px, y, pw);
        y = labelledRow(g, GtLang.get("gtshaders.debug.profiler_toggle"), px, y, pw, (fx, fy, fw) -> {
            boolean on = GpuProfiler.isEnabled();
            UiUtil.toggle(g, fx, fy + 4, 28, 14, on);
            addRegion(fx, fy, 28, ROW_H, () -> GpuProfiler.setEnabled(!on), null);
        });
        if (GpuProfiler.isUnsupported()) {
            y = wrapText(g, GtLang.get("gtshaders.debug.profiler_unsupported"), px, y, pw, Theme.WARNING);
        } else if (GpuProfiler.isEnabled()) {
            y = renderTimings(g, px, y, pw, true);
        }
        wrapText(g, GtLang.get("gtshaders.debug.profiler_hint"), px, y + 2, pw, Theme.TEXT_DIM);
    }

    /** 分层耗时。{@code bars} 为 true 时每层画一条以 16.7ms（60 帧的整帧预算）为满格的横条。 */
    private int renderTimings(GuiGraphicsExtractor g, int px, int y, int pw, boolean bars) {
        float[] ms = GpuProfiler.results();
        if (ms.length == 0) {
            return wrapText(g, GtLang.get("gtshaders.debug.profiler_waiting"), px, y, pw, Theme.TEXT_DIM);
        }
        List<ShaderProject.PassBuild> passes = PreviewRuntime.passes();
        float total = 0f;
        for (int i = 0; i < ms.length; i++) {
            String name = i < passes.size() ? passes.get(i).layer().name() : "blit";
            String value = Float.isNaN(ms[i]) ? "—" : String.format(Locale.ROOT, "%.2f ms", ms[i]);
            if (!Float.isNaN(ms[i])) {
                total += ms[i];
            }
            UiUtil.textLeft(g, this.font, UiUtil.ellipsize(this.font, name, pw - 70), px, y, Theme.TEXT_SUB);
            UiUtil.textRight(g, this.font, value, px + pw, y, Theme.TEXT);
            y += this.font.lineHeight + 2;
            if (bars && !Float.isNaN(ms[i])) {
                int bw = Math.round(Math.min(1f, ms[i] / 16.7f) * pw);
                g.fill(px, y, px + pw, y + 2, Theme.SLIDER_TRACK);
                g.fill(px, y, px + Math.max(1, bw), y + 2, ms[i] > 4f ? Theme.WARNING : Theme.ACCENT);
                y += 5;
            }
        }
        UiUtil.textLeft(g, this.font, GtLang.get("gtshaders.debug.profiler_total"), px, y, Theme.TEXT_DIM);
        UiUtil.textRight(g, this.font, String.format(Locale.ROOT, "%.2f ms", total), px + pw, y, Theme.TEXT_SUB);
        return y + this.font.lineHeight + 4;
    }

    private int renderInspection(GuiGraphicsExtractor g, int px, int y, int pw, int mx, int my) {
        DebugSession.Inspection in = DebugSession.lastInspection();
        if (in == null) {
            return y;
        }
        String head = in.done()
                ? GtLang.get("gtshaders.debug.at", Math.round(in.u() * 100), Math.round(in.v() * 100))
                : GtLang.get("gtshaders.debug.reading");
        UiUtil.textLeft(g, this.font, head, px, y, in.done() ? Theme.TEXT_SUB : Theme.ACCENT);
        y += this.font.lineHeight + 4;
        for (DebugSession.LayerSample s : in.layers()) {
            UiUtil.swatch(g, px, y, 10, Theme.rgb(s.r() / 255f, s.g() / 255f, s.b() / 255f));
            String name = s.layerName() == null ? GtLang.get("gtshaders.debug.scene") : s.layerName();
            UiUtil.textLeft(g, this.font, UiUtil.ellipsize(this.font, name, pw / 2 - 16), px + 14, y, Theme.TEXT_SUB);
            UiUtil.textRight(g, this.font, String.format(Locale.ROOT, "%d %d %d  #%02X%02X%02X",
                    s.r(), s.g(), s.b(), s.r(), s.g(), s.b()), px + pw, y, Theme.TEXT);
            y += this.font.lineHeight + 3;
        }
        DebugSession.ValueSample v = in.value();
        if (v != null && (in.done() || v.hit())) {
            if (!v.hit()) {
                y = wrapText(g, GtLang.get("gtshaders.debug.not_hit"), px, y, pw, Theme.WARNING);
            } else {
                y = wrapText(g, formatValue(v), px, y, pw, Theme.TEXT);
            }
        }
        DebugInstrument.UbSample ub = in.ub();
        if (ub != null) {
            if (ub.kind() == DebugInstrument.UbKind.NONE) {
                y = wrapText(g, GtLang.get("gtshaders.debug.ub_none"), px, y, pw, Theme.SUCCESS);
            } else {
                String where = ub.line() > 0 ? GtLang.get("gtshaders.debug.ub_line", ub.line()) : "";
                y = wrapText(g, GtLang.get(ub.kind().translationKey()) + where
                        + GtLang.get("gtshaders.debug.ub_count", ub.count() >= 255 ? "255+" : ub.count()),
                        px, y, pw, Theme.ERROR);
                if (ub.line() > 0) {
                    final int line = ub.line();
                    secondaryButton(g, px, y + 2, pw, ROW_H, GtLang.get("gtshaders.debug.goto_line", line), () -> {
                        panelSourceTab = true;
                        layout.panelVisible = true;
                        codeEditor.gotoLine(line, this.font);
                    }, mx, my);
                    y += ROW_H + 4;
                }
            }
        }
        if (!in.exact()) {
            y = wrapText(g, GtLang.get("gtshaders.debug.inexact"), px, y, pw, Theme.WARNING);
        }
        if (in.error() != null) {
            y = wrapText(g, in.error(), px, y, pw, Theme.ERROR);
        }
        return y;
    }

    private static String formatValue(DebugSession.ValueSample v) {
        int n = Math.max(1, v.components());
        String type = switch (v.type()) {
            case 1 -> n == 1 ? "int" : "ivec" + n;
            case 2 -> "uint";
            case 3 -> "bool";
            default -> n == 1 ? "float" : "vec" + n;
        };
        StringBuilder sb = new StringBuilder(type).append(n == 1 ? " " : " (");
        for (int i = 0; i < n; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            float f = v.values()[i];
            sb.append(switch (v.type()) {
                case 1, 2 -> Long.toString((long) f);
                case 3 -> f != 0f ? "true" : "false";
                default -> Float.isNaN(f) ? "NaN" : Float.isInfinite(f) ? (f > 0 ? "+Inf" : "-Inf")
                        : String.format(Locale.ROOT, "%.6g", f);
            });
        }
        return n == 1 ? sb.toString() : sb.append(')').toString();
    }

    /** 画布上标出最近一次取样的位置。 */
    private void renderInspectMarker(GuiGraphicsExtractor g) {
        DebugSession.Inspection in = DebugSession.lastInspection();
        if (in == null) {
            return;
        }
        int cx = (int) Math.round(in.u() * lw);
        int cy = (int) Math.round(in.v() * lh);
        int color = in.done() ? Theme.ACCENT : Theme.WARNING;
        g.fill(cx - 7, cy, cx - 2, cy + 1, color);
        g.fill(cx + 2, cy, cx + 7, cy + 1, color);
        g.fill(cx, cy - 7, cx + 1, cy - 2, color);
        g.fill(cx, cy + 2, cx + 1, cy + 7, color);
    }

    private void inspectAtClick() {
        String problem = DebugSession.inspect(project, clickLx / Math.max(1, lw), clickLy / Math.max(1, lh));
        if (problem != null) {
            showToast(problem, true);
        } else {
            focusPanel(DockPanel.DEBUG);
        }
    }

    private void fillProbeFromCursor() {
        String expr = codeEditor.probeExpression();
        if (!expr.isEmpty()) {
            probeExprField.setTextIfUnfocused(expr);
        }
        probeLineField.setTextIfUnfocused(Integer.toString(codeEditor.cursorLine1()));
    }

    private void startProbeFromFields() {
        ShaderLayer layer = project.selected();
        if (layer == null) {
            showToast(GtLang.get("gtshaders.debug.err.layer"), true);
            return;
        }
        if (probeExprField.text().isBlank() || probeLineField.asFloat() == null) {
            fillProbeFromCursor();
        }
        Float line = probeLineField.asFloat();
        showDebugResult(DebugSession.startValue(project, layer, line == null ? 0 : Math.round(line),
                probeExprField.text()));
    }

    /**
     * Ctrl+E：拿代码区光标处的词（或单行选区）在光标所在行开探针；探针已开时再按一次关掉。
     *
     * <p>这是整套调试里最常用的一步，值得一个不用离开代码区的快捷键——
     * 光标停在 {@code luma} 上按一下，画面就变成 luma 的分布。
     */
    private void toggleQuickProbe() {
        if (DebugSession.mode() == DebugSession.Mode.VALUE) {
            stopDebug();
            return;
        }
        String expr = codeEditor.probeExpression();
        if (expr.isEmpty()) {
            showToast(GtLang.get("gtshaders.debug.err.empty_expr"), true);
            return;
        }
        probeExprField.setTextIfUnfocused(expr);
        probeLineField.setTextIfUnfocused(Integer.toString(codeEditor.cursorLine1()));
        ShaderLayer layer = project.selected();
        if (layer == null) {
            showToast(GtLang.get("gtshaders.debug.err.layer"), true);
            return;
        }
        showDebugResult(DebugSession.startValue(project, layer, codeEditor.cursorLine1(), expr));
    }

    private void stopDebug() {
        showDebugResult(DebugSession.stop(project));
    }

    /** 调试视图开关之后：错误照常标进代码区，成功时说一声现在画面上显示的是什么。 */
    private void showDebugResult(GlslValidator.Result result) {
        Set<Integer> errorLines = new HashSet<>();
        Map<Integer, String> messages = new HashMap<>();
        for (GlslValidator.Issue issue : result.issues()) {
            if (issue.error() && issue.line() > 0) {
                errorLines.add(issue.line());
                messages.merge(issue.line(), issue.message(), (a, b) -> a + "\n" + b);
            }
        }
        codeEditor.setErrorLines(errorLines);
        codeEditor.setErrorMessages(messages);
        if (!result.ok()) {
            showToast(result.firstErrorMessage(), true);
            return;
        }
        switch (DebugSession.mode()) {
            case VALUE -> showToast(GtLang.get("gtshaders.debug.probe_on"), false);
            case UB -> showToast(GtLang.get("gtshaders.debug.ub_on"), false);
            case NONE -> showToast(GtLang.get("gtshaders.debug.off"), false);
        }
        focusPanel(DockPanel.DEBUG);
    }

    // ---- 浮动面板 ----

    private void renderFloatingPanel(GuiGraphicsExtractor g, int mx, int my) {
        if (layout.panelX < 0 || layout.panelY < 0) {
            // 默认贴在右栏左侧，而不是压在画布正中——初次打开就挡住预览是最糟的第一印象
            layout.panelX = Math.max(leftW() + 8, lw - rightW() - layout.panelWidth - 12);
            layout.panelY = TOP_H + 56;
        }
        // 窗口变小或换了缩放后，面板可能整体跑到屏幕外，每帧夹一次保证还能抓回来
        layout.panelX = Math.max(2, Math.min(layout.panelX, lw - 60));
        layout.panelY = Math.max(TOP_H + 2, Math.min(layout.panelY, statusTop() - 40));
        int pw = Math.min(layout.panelWidth, lw - layout.panelX - 4);
        int ph = Math.min(layout.panelHeight, statusTop() - layout.panelY - 4);
        int pxx = layout.panelX;
        int pyy = layout.panelY;

        UiUtil.dropShadow(g, pxx, pyy, pw, ph, 4);
        UiUtil.roundRect(g, pxx, pyy, pw, ph, 8, Theme.PANEL);
        UiUtil.box(g, pxx, pyy, pw, ph, Theme.BORDER);

        int titleH = 28;
        // 标题带上当前层名：这个面板跟着选中层走，两个层时不写出来根本分不清在调哪一个，
        // 而「我明明改了却没变」有很大一部分其实是改在了另一层上
        ShaderLayer titleLayer = project.selected();
        String title = GtLang.get("gtshaders.panel.shader_settings");
        if (titleLayer != null) {
            title = title + " · " + titleLayer.name();
        }
        UiUtil.textLeft(g, this.font, UiUtil.ellipsize(this.font, title, pw - 60),
                pxx + 12, pyy + (titleH - this.font.lineHeight) / 2 + 1, Theme.TEXT);
        iconButton(g, pxx + pw - ICON - 6, pyy + (titleH - ICON) / 2, Icons::close,
                GtLang.get("gtshaders.panel.close"), false, () -> layout.panelVisible = false, mx, my);
        UiUtil.hLine(g, pxx + 1, pxx + pw - 1, pyy + titleH, Theme.BORDER);

        int y = pyy + titleH + 4;
        y = tabRow(g, pxx + 4, y, pw - 8, new String[]{
                GtLang.get("gtshaders.panel.tab.props"),
                GtLang.get("gtshaders.panel.tab.source")
        }, panelSourceTab ? 1 : 0, i -> panelSourceTab = i == 1, mx, my);

        int contentTop = y;
        int contentBottom = pyy + ph - 10;
        g.enableScissor(pxx + 1, contentTop, pxx + pw - 1, contentBottom);
        clipRegions(pxx + 1, contentTop, pxx + pw - 1, contentBottom);
        if (project.selected() == null) {
            // 一层都没有时，源码框和参数表都无处可依，直接给出唯一有意义的下一步
            int ty = contentTop + 8;
            ty = wrapText(g, GtLang.get("gtshaders.panel.no_layer"), pxx + 12, ty, pw - 24, Theme.TEXT_DIM);
            secondaryButton(g, pxx + 12, ty + 6, pw - 24, ROW_H,
                    GtLang.get("gtshaders.tool.add_layer"), this::addLayer, mx, my);
        } else if (panelSourceTab) {
            codeEditor.setBounds(pxx + 6, contentTop, pw - 12, Math.max(20, contentBottom - contentTop));
            codeEditor.render(g, this.font, mx, my);
            if (!overlayOpen() && contextMenu == null) {
                String tip = codeHoverTip(codeEditor.hover(mx, my, this.font));
                if (tip != null) {
                    setTooltip(tip, mx, my);
                }
            }
        } else {
            renderParams(g, pxx, pw, contentTop - panelScroll, contentBottom, mx, my);
        }
        g.disableScissor();
        unclipRegions();

        // 右下角尺寸手柄
        int hs = 10;
        int hx = pxx + pw - hs - 2;
        int hy = pyy + ph - hs - 2;
        for (int i = 0; i < 3; i++) {
            g.fill(hx + i * 3, hy + hs - 2 - i * 3, hx + hs - 1, hy + hs - 1 - i * 3, Theme.BORDER_STRONG);
        }
        addRegion(hx - 2, hy - 2, hs + 4, hs + 4, () -> drag = Drag.PANEL_RESIZE, null);
    }

    /**
     * 代码区悬停说明：报错行给编译器原文，名字给签名与说明。
     *
     * <p>报错排在最前：鼠标停在一行红底上，想看的一定是它为什么红。
     */
    private @Nullable String codeHoverTip(CodeEditor.@Nullable Hover hover) {
        if (hover == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        if (hover.error() != null) {
            sb.append(GtLang.get("gtshaders.editor.hover.error", hover.line())).append('\n').append(hover.error());
        }
        if (hover.word() != null) {
            var sym = mc.GTedd.cn.gtshaders.codegen.GlslSymbols.lookup(hover.word(), codeEditor.userSymbols());
            if (sym != null && sym.kind() != mc.GTedd.cn.gtshaders.codegen.GlslSymbols.Kind.KEYWORD) {
                if (!sb.isEmpty()) {
                    sb.append("\n\n");
                }
                if (sym.signatures().isEmpty()) {
                    sb.append(sym.name());
                } else {
                    sb.append(String.join("\n", sym.signatures()));
                }
                if (sym.line() > 0 && sym.kind() != mc.GTedd.cn.gtshaders.codegen.GlslSymbols.Kind.PARAM) {
                    sb.append("  · ").append(GtLang.get("gtshaders.editor.hover.defined_at", sym.line()));
                }
                if (!sym.doc().isEmpty()) {
                    sb.append('\n').append(sym.doc());
                }
                if (sym.kind() == mc.GTedd.cn.gtshaders.codegen.GlslSymbols.Kind.FUNCTION && sym.line() > 0) {
                    sb.append('\n').append(GtLang.get("gtshaders.editor.hover.ctrl_click"));
                }
            }
        }
        return sb.isEmpty() ? null : sb.toString();
    }

    private void renderParams(GuiGraphicsExtractor g, int panelLeft, int panelW,
                              int y, int bottom, int mx, int my) {
        int px = panelLeft + 12;
        int pw = panelW - 24;
        ShaderLayer layer = project.selected();
        if (layer == null) {
            return;
        }

        List<ShaderParam> params = layer.params();

        // 搜索框旁边跟一个「全部恢复默认」。调参最怕的是把某个值拖坏了又想不起原来多少，
        // 而参数一多，逐个改回去根本不现实——这个按钮是撤销之外的兜底。
        int resetW = params.isEmpty() ? 0 : UiText.width(GtLang.get("gtshaders.param.reset")) + 16;
        searchBox(g, paramSearchField, px, y, pw - resetW - (resetW > 0 ? 6 : 0), ROW_H,
                GtLang.get("gtshaders.left.search_params"), mx, my);
        if (resetW > 0) {
            int rx = px + pw - resetW;
            boolean rhot = hovered(mx, my, rx, y, resetW, ROW_H);
            UiUtil.roundRect(g, rx, y, resetW, ROW_H, 4,
                    rhot ? Theme.BUTTON_HOVER : Theme.PANEL_SUNKEN);
            UiUtil.box(g, rx, y, resetW, ROW_H, Theme.BORDER);
            UiUtil.textCenter(g, this.font, GtLang.get("gtshaders.param.reset"), rx + resetW / 2,
                    y + (ROW_H - this.font.lineHeight) / 2 + 1, Theme.TEXT_SUB);
            addRegion(rx, y, resetW, ROW_H, () -> {
                for (ShaderParam p : params) {
                    p.resetToDefault();
                }
                onSourceEdited();
            }, null);
            if (rhot) {
                setTooltip(GtLang.get("gtshaders.param.reset_hint"), mx, my);
            }
        }
        y += ROW_H + 6;

        // 效果说明：直接取源码顶上那段注释。对着一堆「Bleach / Glare」的滑块，
        // 知道自己在调的是闪光弹，比任何一个参数名都重要。
        y = renderLayerSummary(g, layer, px, y, pw);

        // 可换图效果：把 @texture 声明的贴图输入直接列在参数上方，点“选择”轮换内置贴图。
        if (!layer.textures().isEmpty()) {
            y = renderTextureConfig(g, layer, px, y, pw, mx, my);
        }

        if (params.isEmpty()) {
            y = wrapText(g, GtLang.get("gtshaders.panel.no_params"), px, y, pw, Theme.TEXT_DIM);
            wrapText(g, GtLang.get("gtshaders.panel.no_params_hint"), px, y + 4, pw, Theme.TEXT_DIM);
            return;
        }

        String filter = paramSearchField.text().trim().toLowerCase(Locale.ROOT);
        int shown = 0;
        int labelW = Math.max(60, Math.min(100, pw / 3));

        // 按 @group 归组，保持源码里的先后顺序。一个组都没有时不画组标题——
        // 只有三个参数还要先展开一个「常规」，是在给简单情形凭空加一层。
        Map<String, List<ShaderParam>> grouped = new LinkedHashMap<>();
        for (ShaderParam p : params) {
            grouped.computeIfAbsent(p.groupKey(), k -> new ArrayList<>()).add(p);
        }
        boolean showGroups = grouped.size() > 1 || !grouped.containsKey("");

        for (Map.Entry<String, List<ShaderParam>> group : grouped.entrySet()) {
            List<ShaderParam> visible = new ArrayList<>();
            for (ShaderParam p : group.getValue()) {
                if (matchesFilter(p, filter)) {
                    visible.add(p);
                }
            }
            if (visible.isEmpty()) {
                continue;
            }
            shown += visible.size();

            boolean collapsed = false;
            if (showGroups) {
                String groupKey = System.identityHashCode(layer) + "/" + group.getKey();
                // 搜索时强制展开：搜出来的东西藏在一个收起的组里，等于没搜到
                collapsed = filter.isEmpty() && collapsedGroups.contains(groupKey);
                String title = group.getKey().isEmpty()
                        ? GtLang.get("gtshaders.param.group_default")
                        : visible.get(0).resolveGroup(GtLang.contentLang());
                y = groupHeader(g, px, y, pw, title, visible.size(), collapsed,
                        () -> toggleGroup(groupKey), mx, my);
            }
            if (collapsed) {
                continue;
            }

            for (ShaderParam p : visible) {
                if (y > bottom) {
                    break;
                }
                y = paramRow(g, layer, p, px, y, pw, labelW, mx, my);
            }
        }
        if (shown == 0) {
            UiUtil.textLeft(g, this.font, GtLang.get("gtshaders.panel.no_match"), px, y, Theme.TEXT_DIM);
        }
    }

    private boolean matchesFilter(ShaderParam p, String filter) {
        if (filter.isEmpty()) {
            return true;
        }
        String label = p.resolveLabel(GtLang.contentLang(), GtLang::getOrNull);
        return label.toLowerCase(Locale.ROOT).contains(filter)
                || p.name().toLowerCase(Locale.ROOT).contains(filter)
                || p.resolveDesc(GtLang.contentLang()).toLowerCase(Locale.ROOT).contains(filter);
    }

    private void toggleGroup(String key) {
        if (!collapsedGroups.remove(key)) {
            collapsedGroups.add(key);
        }
    }

    /** 分组标题行：三角 + 名字 + 参数个数，整行可点。 */
    private int groupHeader(GuiGraphicsExtractor g, int px, int y, int pw, String title, int count,
                            boolean collapsed, Runnable toggle, int mx, int my) {
        int h = 18;
        boolean hot = hovered(mx, my, px, y, pw, h);
        if (hot) {
            UiUtil.roundRect(g, px - 4, y, pw + 8, h, 4, Theme.BUTTON_HOVER);
        }
        int ty = y + (h - this.font.lineHeight) / 2 + 1;
        Icons.caret(g, px - 1, y + (h - 10) / 2, 10, Theme.TEXT_DIM, !collapsed);
        UiUtil.textLeft(g, this.font, UiUtil.ellipsize(this.font, title, pw - 40), px + 12, ty,
                Theme.TEXT_SUB);
        UiUtil.textRight(g, this.font, String.valueOf(count), px + pw, ty, Theme.TEXT_DIM);
        addRegion(px - 4, y, pw + 8, h, toggle, null);
        return y + h + 2;
    }

    /** 可换图效果的贴图配置区。 */
    private int renderTextureConfig(GuiGraphicsExtractor g, ShaderLayer layer, int px, int y,
                                    int pw, int mx, int my) {
        UiUtil.textLeft(g, this.font, GtLang.get("gtshaders.panel.textures"), px, y, Theme.ACCENT);
        y += this.font.lineHeight + 3;
        for (ShaderTexture tex : layer.textures()) {
            int rowH = 18;
            boolean hot = hovered(mx, my, px, y, pw, rowH);
            if (hot) {
                UiUtil.roundRect(g, px - 4, y, pw + 8, rowH, 4, Theme.BUTTON_HOVER);
            }
            String label = UiUtil.ellipsize(this.font, tex.displayName() + " · " + tex.samplerName(), pw - 74);
            UiUtil.textLeft(g, this.font, label, px, y + (rowH - this.font.lineHeight) / 2 + 1, Theme.TEXT_SUB);
            String shortPath = tex.location();
            int slash = Math.max(shortPath.lastIndexOf('/'), shortPath.lastIndexOf(':'));
            if (slash >= 0 && slash < shortPath.length() - 1) {
                shortPath = shortPath.substring(slash + 1);
            }
            UiUtil.textRight(g, this.font, UiUtil.ellipsize(this.font, shortPath, 90),
                    px + pw - 70, y + (rowH - this.font.lineHeight) / 2 + 1, Theme.TEXT_DIM);
            int bw = 64;
            int bx = px + pw - bw;
            boolean bhot = hovered(mx, my, bx, y, bw, rowH);
            UiUtil.roundRect(g, bx, y, bw, rowH, 4, bhot ? Theme.BUTTON_HOVER : Theme.PANEL_SUNKEN);
            UiUtil.box(g, bx, y, bw, rowH, Theme.BORDER);
            UiUtil.textCenter(g, this.font, GtLang.get("gtshaders.panel.texture_pick"),
                    bx + bw / 2, y + (rowH - this.font.lineHeight) / 2 + 1, Theme.TEXT_SUB);
            ShaderTexture texRef = tex;
            addRegion(bx, y, bw, rowH, () -> cycleTexture(layer, texRef), null);
            if (bhot) {
                setTooltip(GtLang.get("gtshaders.panel.texture_pick_hint"), mx, my);
            }
            y += rowH + 2;
        }
        return y + 4;
    }

    /** 把某个贴图输入切换到下一张：内置图标之后接着拖进来的 PNG。 */
    private void cycleTexture(ShaderLayer layer, ShaderTexture tex) {
        // 导入目录可能在编辑器外被改过，切换前扫一遍，新放进去的 PNG 也能选到
        ImportedTextureManager.registerAll(Minecraft.getInstance().getTextureManager());
        List<String> choices = new ArrayList<>(List.of(TEXTURE_LIBRARY));
        choices.addAll(ImportedTextureManager.importedLocations());
        String next = choices.get((choices.indexOf(tex.location()) + 1) % choices.size());
        String updated = replaceTexturePath(layer.authorSource(), tex.samplerName(), next);
        if (updated != null) {
            layer.setAuthorSource(updated);
            syncCodeEditorFromLayer();
            compileNow();
        }
    }

    /** 把 {@code // @texture ... name=XXX ... path=旧} 中的 path 替换成新值。 */
    private static @Nullable String replaceTexturePath(String source, String samplerName, String newPath) {
        if (source == null) {
            return null;
        }
        String[] lines = source.split("\n", -1);
        boolean changed = false;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (!line.matches("^\s*//\s*@texture\s+.*$")) {
                continue;
            }
            if (!line.contains("name=" + samplerName) && !line.contains("name = " + samplerName)) {
                continue;
            }
            String replaced = line.replaceFirst("(?i)(path\s*=\s*)[^\s]+", "$1" + newPath);
            if (!replaced.equals(line)) {
                lines[i] = replaced;
                changed = true;
                break;
            }
        }
        return changed ? String.join("\n", lines) : null;
    }

    /**
     * 当前层是什么效果：标题 + 一句话。源码没写注释就整段不画，不留一块空白占着地方。
     */
    private int renderLayerSummary(GuiGraphicsExtractor g, ShaderLayer layer, int px, int y, int pw) {
        SourceDoc doc = SourceDoc.parse(layer.authorSource());
        if (doc.isEmpty()) {
            return y;
        }
        if (!doc.title().isEmpty()) {
            UiUtil.textLeft(g, this.font, UiUtil.ellipsize(this.font, doc.localTitle(), pw),
                    px, y, Theme.TEXT);
            y += this.font.lineHeight + 2;
        }
        if (!doc.summary().isEmpty()) {
            y = wrapText(g, doc.summary(), px, y, pw, Theme.TEXT_DIM);
        }
        return y + 6;
    }

    /**
     * 一个参数：标签 + 控件。标签上悬停出说明。
     *
     * <p>说明这件事对第一次用的人是分水岭：{@code Bleach} 是什么、拖到头会怎样、
     * 现在这个范围是作者定的还是我们猜的——不写出来，就只能靠一个个拖过去试。
     */
    private int paramRow(GuiGraphicsExtractor g, ShaderLayer layer, ShaderParam p,
                         int px, int y, int pw, int labelW, int mx, int my) {
        String label = p.resolveLabel(GtLang.contentLang(), GtLang::getOrNull);
        // 驱动器只给后处理与轮廓层的 float 参数：核心着色器的参数编译成 const，没有时间可以驱动
        boolean drivable = p.type() == ParamType.FLOAT && !layer.isCore();
        int labelRoom = drivable ? labelW - 18 : labelW - 6;
        UiUtil.textLeft(g, this.font, UiUtil.ellipsize(this.font, label, labelRoom),
                px, y + 7, p.isDriven() ? Theme.ACCENT : Theme.TEXT_SUB);
        if (hovered(mx, my, px, y, labelRoom + 2, ROW_H)) {
            setTooltip(paramTooltip(p), mx, my);
        }
        if (drivable) {
            int tx = px + labelW - 16;
            boolean hot = hovered(mx, my, tx, y + 3, 14, ROW_H - 6);
            if (p.isDriven() || hot) {
                UiUtil.roundRect(g, tx, y + 3, 14, ROW_H - 6, 3,
                        p.isDriven() ? Theme.BUTTON_ACTIVE_BG : Theme.BUTTON_HOVER);
            }
            UiUtil.textLeft(g, this.font, "~", tx + 4, y + 7, p.isDriven() ? Theme.ACCENT : Theme.TEXT_DIM);
            addRegion(tx, y + 3, 14, ROW_H - 6, () -> setDriver(layer, p,
                    p.isDriven() ? null : mc.GTedd.cn.gtshaders.core.ParamDriver.defaultsFor(p)), null);
            if (hot) {
                setTooltip(GtLang.get(p.isDriven() ? "gtshaders.driver.off_hint" : "gtshaders.driver.on_hint"), mx, my);
            }
            if (p.isDriven()) {
                return driverRows(g, layer, p, px, y, pw, labelW, mx, my);
            }
        }

        if (p.type().isColor()) {
            return colorRow(g, layer, p, px, y, pw, labelW, mx, my);
        }
        if (p.type() == ParamType.BOOL) {
            UiUtil.toggle(g, px + labelW, y + 4, 28, 14, p.get(0) >= 0.5f);
            addRegion(px + labelW, y, 28, ROW_H, () -> {
                p.set(0, p.get(0) >= 0.5f ? 0f : 1f);
                onParamEdited();
            }, null);
            return y + ROW_H + 6;
        }
        if (p.type() == ParamType.ANCHOR) {
            return anchorParamRow(g, p, px, y, pw, labelW, mx, my);
        }
        return numericRow(g, layer, p, px, y, pw, labelW, mx, my);
    }

    /**
     * 锚点参数：从工程的绑定列表里选一条，而不是填一个槽位号。
     *
     * <h2>为什么不能是数字框</h2>
     *
     * <p>槽位号是 {@code anchorSlotBase} 按绑定<b>在列表里的顺序</b>累加出来的。填了 2 之后
     * 再往上面插一条绑定，这个 2 就指向了别的目标——而画面上唯一的表现是效果跑到了别处，
     * 没有报错、没有警告，甚至不容易怀疑到「是我加了一条绑定」头上。
     *
     * <p>选绑定存的是它的稳定 id，槽位号在每次编译和每帧上传前重算
     * （{@code ShaderProject.resolveAnchorRefs}），于是顺序怎么变都指得对。
     *
     * <h2>失配和「按号码」两种状态都要说出来</h2>
     *
     * <p>指向的绑定被删了、或者被停用了，下拉上会红字标出来而不是悄悄回落到 0 号——
     * 悄悄回落正是这套设计要消灭的那类错误。老工程存的是裸数字，那种情况显示成
     * 「按号码 #N」，说清它<b>不会</b>跟着顺序走，愿意的话点一下换成按绑定。
     */
    private int anchorParamRow(GuiGraphicsExtractor g, ShaderParam p,
                               int px, int y, int pw, int labelW, int mx, int my) {
        ShaderProject project = this.project;
        int fx = px + labelW;
        int fw = pw - labelW;

        List<AnchorBinding> anchors = project.anchors();
        AnchorBinding bound = p.anchorRef().isEmpty() ? null : project.findAnchorById(p.anchorRef());

        String text;
        int color = Theme.TEXT;
        if (bound != null) {
            int slot = project.anchorSlotBaseById(p.anchorRef());
            text = GtLang.get("gtshaders.param.anchor_bound",
                    slot < 0 ? "-" : String.valueOf(slot), bound.name());
            if (slot < 0) {
                // 绑定还在，但被停用了或者挤出了槽位上限——效果拿不到它
                color = Theme.WARNING;
            }
        } else if (!p.anchorRef().isEmpty()) {
            text = GtLang.get("gtshaders.param.anchor_missing");
            color = Theme.WARNING;
        } else {
            text = GtLang.get("gtshaders.param.anchor_raw", (int) p.get(0));
            color = Theme.TEXT_DIM;
        }

        dropdownButton(g, fx, y, fw, ROW_H, text, () -> cycleAnchorRef(p, anchors), mx, my);
        // 按钮自己的文字颜色由 dropdownButton 定，警告色再补画一次盖上去
        if (color != Theme.TEXT) {
            UiUtil.textLeft(g, this.font, UiUtil.ellipsize(this.font, text, fw - 20),
                    fx + 6, y + 7, color);
        }
        return y + ROW_H + 6;
    }

    /**
     * 在「按号码」和每一条绑定之间循环。
     *
     * <p>把「按号码」留在循环里而不是一选就回不去：源码里手写 {@code gtAnchorUV(3)} 的效果
     * 仍然存在，那种效果的参数应该能表达「我就是要 3 号槽，别管绑定列表」。
     */
    private void cycleAnchorRef(ShaderParam p, List<AnchorBinding> anchors) {
        if (anchors.isEmpty()) {
            return;
        }
        int cur = -1;
        for (int i = 0; i < anchors.size(); i++) {
            if (anchors.get(i).id().equals(p.anchorRef())) {
                cur = i;
                break;
            }
        }
        int next = cur + 1;
        if (next >= anchors.size()) {
            // 绕回「按号码」，值保持在当前解析出来的那个数上——切回去不该顺带改变画面
            p.setAnchorRef("");
        } else {
            p.setAnchorRef(anchors.get(next).id());
        }
        this.project.resolveAnchorRefs();
        onParamEdited();
    }

    /**
     * 被驱动的参数：波形、此刻驱动到的值、以及起止 / 周期 / 相位四个数。
     *
     * <p>这里改的是<b>源码</b>里那行 {@code @param} 注解（见 {@link mc.GTedd.cn.gtshaders.core.ParamDriver}
     * 为什么不存进工程文件），所以每次改动都会走一次去抖后的重编译，和改源码是同一条路。
     */
    private int driverRows(GuiGraphicsExtractor g, ShaderLayer layer, ShaderParam p,
                           int px, int y, int pw, int labelW, int mx, int my) {
        mc.GTedd.cn.gtshaders.core.ParamDriver d = p.driver();
        int fx = px + labelW;
        int fw = pw - labelW;
        int valueW = 44;
        dropdownButton(g, fx, y, fw - valueW - 6, ROW_H, GtLang.get(d.wave().translationKey()),
                () -> setDriver(layer, p, d.withWave(d.wave().next())), mx, my);
        UiUtil.textRight(g, this.font, trimNumber(d.evaluate(PreviewRuntime.time())),
                px + pw, y + (ROW_H - this.font.lineHeight) / 2 + 1, Theme.ACCENT);
        y += ROW_H + 4;

        String key = paramKey(layer, p, -2);
        String[] labels = {GtLang.get("gtshaders.driver.from"), GtLang.get("gtshaders.driver.to"),
                GtLang.get("gtshaders.driver.period"), GtLang.get("gtshaders.driver.phase")};
        float[] values = {d.from(), d.to(), d.period(), d.phase()};
        for (int row = 0; row < 2; row++) {
            int cellW = (pw - 6) / 2;
            for (int col = 0; col < 2; col++) {
                int idx = row * 2 + col;
                int cx = px + col * (cellW + 6);
                int lw2 = Math.min(cellW / 2, UiText.width(labels[idx]) + 6);
                UiUtil.textLeft(g, this.font, UiUtil.ellipsize(this.font, labels[idx], lw2 - 2),
                        cx, y + 7, Theme.TEXT_DIM);
                TextField f = numberField(key + ":" + idx, () -> trimNumber(values[idx]), v -> setDriver(layer, p,
                        switch (idx) {
                            case 0 -> d.withFrom(v);
                            case 1 -> d.withTo(v);
                            case 2 -> d.withPeriod(v);
                            default -> d.withPhase(v);
                        }));
                int nx = cx + lw2;
                int nw = cellW - lw2;
                f.render(g, this.font, nx, y, nw, ROW_H, hovered(mx, my, nx, y, nw, ROW_H), true);
                addFieldRegion(f, nx, y, nw, ROW_H);
            }
            y += ROW_H + 4;
        }
        return y + 2;
    }

    /** 开、关或修改一个参数的驱动器：改写源码里那行注解，走和手改源码同一条路（可撤销、自动重编译）。 */
    private void setDriver(ShaderLayer layer, ShaderParam p, mc.GTedd.cn.gtshaders.core.@Nullable ParamDriver driver) {
        String next = mc.GTedd.cn.gtshaders.codegen.ParamScanner.withDriver(layer.authorSource(), p.name(), driver);
        if (next == null) {
            showToast(GtLang.get("gtshaders.driver.not_found", p.name()), true);
            return;
        }
        if (layer == project.selected()) {
            codeEditor.applyExternalEdit(next);
        } else {
            layer.setAuthorSource(next);
            lastEditAt = System.currentTimeMillis();
            pendingCompile = true;
        }
    }

    /** 参数提示：作者写的说明优先，没写就退回「变量名 + 取值范围」这类客观事实。 */
    private String paramTooltip(ShaderParam p) {
        StringBuilder sb = new StringBuilder();
        String desc = p.resolveDesc(GtLang.contentLang());
        if (!desc.isEmpty()) {
            sb.append(desc).append('\n');
        }
        sb.append(p.name());
        if (!p.type().isColor() && p.type() != ParamType.BOOL && p.min() < p.max()) {
            sb.append("  ").append(GtLang.get("gtshaders.param.range",
                    trimNumber(p.min()), trimNumber(p.max())));
        }
        if (p.isInferred()) {
            // 猜出来的范围要说一声，否则作者会以为是自己写错了
            sb.append('\n').append(GtLang.get("gtshaders.param.inferred"));
        }
        return sb.toString();
    }

    private static String trimNumber(float v) {
        return v == Math.rint(v) && Math.abs(v) < 1e7f
                ? Integer.toString((int) v)
                : String.format(Locale.ROOT, "%.2f", v);
    }

    private int numericRow(GuiGraphicsExtractor g, ShaderLayer layer, ShaderParam p,
                           int px, int y, int pw, int labelW, int mx, int my) {
        for (int c = 0; c < p.type().components(); c++) {
            y = valueRow(g, layer, p, c, px, y, pw, labelW, mx, my);
        }
        return y + 2;
    }

    /**
     * 一个数值分量的可调行。宽度够就「滑块 + 数值框」并排，不够就切紧凑模式。
     *
     * <p>紧凑模式的由来：面板收窄或整体缩放调小之后，并排布局里滑块会被挤到只剩十几个像素，
     * 既拖不准也和右侧的数值框贴在一起。与其让两个控件互相挤，不如把滑块<b>收起来</b>——
     * 平时只显示一枚带进度底纹的数值胶囊，点一下才把滑条浮出来。
     *
     * <p>交互与颜色行保持一致：<b>单击</b>呼出滑条，<b>Ctrl+单击</b>切键入模式。
     * 常用的那个动作留给不带修饰键的单击，精确键入是低频且意图明确的，多按一个 Ctrl 换不误触。
     */
    private int valueRow(GuiGraphicsExtractor g, ShaderLayer layer, ShaderParam p, int comp,
                         int px, int y, int pw, int labelW, int mx, int my) {
        String key = paramKey(layer, p, comp);
        TextField f = numberField(key, () -> p.formatValue(comp), v -> {
            p.set(comp, v);
            onParamEdited();
        });
        int avail = pw - labelW;

        // 按<b>物理</b>像素判断，不是逻辑宽度：编辑器缩放只改 pose 矩阵，逻辑宽度一动不动，
        // 但同样的 sw 落到屏幕上可能只剩几十个像素——滑块看着还在，手上已经拖不准了。
        if (avail * scale() >= PARAM_INLINE_MIN_W) {
            int numW = 44;
            int sx = px + labelW;
            int sw = avail - numW - 6;
            UiUtil.slider(g, sx, y + 8, sw, 5, p.normalized(comp));
            addRegion(sx, y, sw, ROW_H, null, t -> {
                p.setNormalized(comp, (float) t);
                onParamEdited();
            });

            int fx = px + pw - numW;
            f.render(g, this.font, fx, y, numW, ROW_H, hovered(mx, my, fx, y, numW, ROW_H), true);
            addFieldRegion(f, fx, y, numW, ROW_H);
            return y + ROW_H + 4;
        }

        // ---- 紧凑模式 ----
        int cx = px + labelW;
        int cw = Math.max(36, avail);
        if (f.isFocused()) {
            // 正在键入：直接显示成输入框，此时不需要任何底纹干扰
            f.render(g, this.font, cx, y, cw, ROW_H, true, true);
            addFieldRegion(f, cx, y, cw, ROW_H);
            return y + ROW_H + 4;
        }

        boolean hot = hovered(mx, my, cx, y, cw, ROW_H);
        boolean open = key.equals(sliderPopupKey);
        UiUtil.roundRect(g, cx, y, cw, ROW_H, 4, Theme.INPUT_BG);
        // 底纹就是这个分量在 min..max 上的位置：不占额外高度，扫一眼也能知道大概调到哪了
        int fill = Math.round((cw - 2) * Math.max(0f, Math.min(1f, p.normalized(comp))));
        if (fill > 0) {
            UiUtil.roundRect(g, cx + 1, y + ROW_H - 4, fill, 2, 1, Theme.SLIDER_FILL);
        }
        if (open) {
            UiUtil.box(g, cx, y, cw, ROW_H, Theme.ACCENT);
        } else if (hot) {
            UiUtil.box(g, cx, y, cw, ROW_H, Theme.BORDER_STRONG);
        }
        UiUtil.textRight(g, this.font, UiUtil.ellipsize(this.font, p.formatValue(comp), cw - 10),
                cx + cw - 5, y + (ROW_H - this.font.lineHeight) / 2 + 1, Theme.TEXT);

        final int anchorX = cx;
        final int anchorY = y + ROW_H + 2;
        addRegion(cx, y, cw, ROW_H, () -> {
            if ((clickMods & InputConstants.MOD_CONTROL) != 0) {
                closeSliderPopup();
                blurAllExcept(f);
                f.focus();
            } else {
                toggleSliderPopup(key, p, comp, anchorX, anchorY);
            }
        }, null);
        if (hot) {
            setTooltip(GtLang.get("gtshaders.param.compact_hint"), mx, my);
        }
        return y + ROW_H + 4;
    }

    /**
     * 颜色参数一行：色块 + 十六进制码 + 展开箭头。
     *
     * <p>三种取色路径在这一行上分工明确：
     * <b>单击色块</b>弹出取色浮层、<b>按住色块</b>直接在色盘上连续划选、
     * <b>Ctrl+左键点色号</b>切成键入模式。最后一条是刻意要按住 Ctrl 的：
     * 色号那块地方最顺手，留给「点开取色器」这个高频操作，
     * 而精确键入是低频且有明确意图的，多按一个键换来的是不会误触。
     */
    private int colorRow(GuiGraphicsExtractor g, ShaderLayer layer, ShaderParam p,
                         int px, int y, int pw, int labelW, int mx, int my) {
        int bx = px + labelW;
        int bw = Math.max(40, pw - labelW);
        String key = paramKey(layer, p, -1);
        UiUtil.roundRect(g, bx, y, bw, ROW_H, 4, Theme.INPUT_BG);
        if (colorPicker.isFor(key)) {
            UiUtil.box(g, bx, y, bw, ROW_H, Theme.ACCENT);
        }

        int swX = bx + 4;
        int swY = y + 5;
        UiUtil.swatch(g, swX, swY, 12, Theme.rgb(p.get(0), p.get(1), p.get(2)));
        final int anchorY = y + ROW_H + 2;
        addRegion(swX - 2, y + 3, 18, 16,
                () -> beginColorPress(key, p, bx, anchorY), null);
        if (hovered(mx, my, swX - 2, y + 3, 18, 16)) {
            setTooltip(GtLang.get("gtshaders.color.hint"), mx, my);
        }

        TextField hex = hexFields.computeIfAbsent(key, k -> {
            TextField f = new TextField(TextField.Kind.HEX);
            registerField(f);
            return f;
        });
        hex.setOnCommit(() -> {
            Integer v = hex.asHex();
            if (v != null) {
                p.set(0, ((v >> 16) & 0xFF) / 255f);
                p.set(1, ((v >> 8) & 0xFF) / 255f);
                p.set(2, (v & 0xFF) / 255f);
            }
        });
        hex.setTextIfUnfocused(p.hex());
        int hx = bx + 22;
        int hw = Math.max(20, bw - 26 - 14);
        hex.render(g, this.font, hx, y + 2, hw, ROW_H - 4, hovered(mx, my, hx, y, hw, ROW_H), false);
        addRegion(hx, y, hw, ROW_H, () -> {
            if ((clickMods & InputConstants.MOD_CONTROL) != 0) {
                blurAllExcept(hex);
                hex.focus();
            } else {
                toggleColorPicker(key, p, bx, anchorY);
            }
        }, null);
        if (hovered(mx, my, hx, y, hw, ROW_H)) {
            setTooltip(GtLang.get("gtshaders.color.hex_hint"), mx, my);
        }

        // 展开箭头：把 R/G/B(/A) 四条滑块折进去，需要按分量微调时才展开
        int ex = bx + bw - 16;
        Icons.chevronDown(g, ex, y + (ROW_H - 12) / 2, 12,
                expandedColors.contains(key) ? Theme.ACCENT : Theme.TEXT_DIM);
        addRegion(ex - 2, y, 18, ROW_H, () -> {
            // 用带图层标识的 key，不是参数名：两个层都有 Tint 时，
            // 按参数名存会让在 A 层展开的状态跟着跑到 B 层去
            if (!expandedColors.remove(key)) {
                expandedColors.add(key);
            }
        }, null);
        y += ROW_H + 4;

        if (expandedColors.contains(key)) {
            String[] names = {"R", "G", "B", "A"};
            for (int c = 0; c < p.type().components(); c++) {
                UiUtil.textLeft(g, this.font, names[c], px + 10, y + 6, Theme.TEXT_DIM);
                y = valueRow(g, layer, p, c, px, y, pw, labelW, mx, my);
            }
        }
        return y + 2;
    }

    private String paramKey(ShaderLayer layer, ShaderParam p, int comp) {
        return System.identityHashCode(layer) + ":" + p.name() + ":" + comp;
    }

    // ---- 数值滑条浮层 ----

    private void toggleSliderPopup(String key, ShaderParam p, int comp, int anchorX, int anchorY) {
        if (key.equals(sliderPopupKey)) {
            closeSliderPopup();
            return;
        }
        blurAllExcept(null);
        sliderPopupKey = key;
        sliderPopupParam = p;
        sliderPopupComp = comp;
        // 夹回屏幕内：参数行贴着面板右缘时，浮层按锚点直接放会跑出去一截
        sliderPopupX = Math.max(PAD, Math.min(anchorX, lw - SLIDER_POPUP_W - PAD));
        sliderPopupY = Math.min(anchorY, Math.max(PAD, statusTop() - SLIDER_POPUP_H - PAD));
    }

    private void closeSliderPopup() {
        sliderPopupKey = null;
        sliderPopupParam = null;
    }

    private boolean sliderPopupOpen() {
        return sliderPopupKey != null && sliderPopupParam != null;
    }

    /**
     * 画呼出的滑条。
     *
     * <p>先铺一张覆盖全屏的关闭区，再登记浮层自身——region 表是反向扫的，
     * 后登记的先命中，于是「点浮层里」和「点浮层外关掉」这两件事不会打架。
     */
    private void renderSliderPopup(GuiGraphicsExtractor g, int mx, int my) {
        ShaderParam p = sliderPopupParam;
        if (p == null) {
            return;
        }
        addRegion(0, 0, lw, lh, this::closeSliderPopup, null);

        int x = sliderPopupX;
        int y = sliderPopupY;
        UiUtil.dropShadow(g, x, y, SLIDER_POPUP_W, SLIDER_POPUP_H, 4);
        UiUtil.roundRect(g, x, y, SLIDER_POPUP_W, SLIDER_POPUP_H, 6, Theme.PANEL);
        UiUtil.roundOutline(g, x, y, SLIDER_POPUP_W, SLIDER_POPUP_H, 6, Theme.BORDER_STRONG);

        final int comp = sliderPopupComp;
        int sx = x + 10;
        int sw = SLIDER_POPUP_W - 20;
        UiUtil.slider(g, sx, y + 8, sw, 6, p.normalized(comp));
        // 热区铺满浮层高度，不必精确压在那条 6px 的轨道上才拖得动
        addRegion(sx - 6, y, sw + 12, SLIDER_POPUP_H, null,
                t -> {
                    p.setNormalized(comp, (float) t);
                    onParamEdited();
                });

        int ty = y + SLIDER_POPUP_H - this.font.lineHeight - 3;
        UiUtil.textLeft(g, this.font, fmt(p.min()), sx, ty, Theme.TEXT_DIM);
        UiUtil.textCenter(g, this.font, p.formatValue(comp), x + SLIDER_POPUP_W / 2, ty, Theme.TEXT);
        UiUtil.textRight(g, this.font, fmt(p.max()), sx + sw, ty, Theme.TEXT_DIM);
    }

    /**
     * 「导出资源包」的二级菜单：这次的产物写到哪、叫什么。
     *
     * <p>只放目录与文件名两件事。触发方式、目标版本回答的是「这个包是什么」，它们留在右栏导出页；
     * 这里回答的是另一个问题——两者混在一块，每次导出都得重新审一遍与本次无关的选项。
     *
     * <p>路径与文件名<b>都允许中文</b>：产物是拿去发给别人的文件，名字本来就该是人话。
     * zip <i>里面</i>的资源路径仍然是 ASCII 收敛过的（原版加载器的硬性要求），两件事互不影响。
     */
    private void renderExportMenu(GuiGraphicsExtractor g, int mx, int my) {
        int pad = PAD + 2;
        int innerW = EXPORT_MENU_W - pad * 2;
        int lineH = this.font.lineHeight + 1;
        int presetH = 18;

        // 先把要写的话算出来才知道浮层多高：路径长短差着好几行，写死高度必然裁掉一截
        Path dir = null;
        String pathError = null;
        try {
            dir = ExportTarget.directory(exportDirField.text(), Workspace.exportDir());
        } catch (InvalidPathException e) {
            pathError = GtLang.get("gtshaders.export.bad_dir", exportDirField.text());
        }
        List<String> target = dir != null
                ? new ArrayList<>(wrapLines(
                        GtLang.get("gtshaders.export.target_dir", dir.toString()), innerW))
                : new ArrayList<>(wrapLines(pathError, innerW));
        if (dir != null) {
            target.addAll(wrapLines(GtLang.get("gtshaders.export.target_file", exportFileNames()), innerW));
        }
        List<String> hint = wrapLines(GtLang.get("gtshaders.export.name_hint"), innerW);

        int h = 8 + this.font.lineHeight + 8
                + this.font.lineHeight + 2 + ROW_H + 4
                + presetH + 8
                + this.font.lineHeight + 2 + ROW_H + 6
                + (target.size() + hint.size()) * lineH + 8
                + ROW_H + 4 + 8;
        int x = Math.max(4, Math.min(exportMenuX, lw - EXPORT_MENU_W - 4));
        int y = Math.max(TOP_H + 2, Math.min(exportMenuY, Math.max(TOP_H + 2, lh - h - 4)));

        // 点浮层外面收起。先登记，于是浮层自身和它上面的控件永远压在这一层之上
        addRegion(0, 0, lw, lh, this::closeExportMenu, null);

        UiUtil.dropShadow(g, x, y, EXPORT_MENU_W, h, 4);
        UiUtil.roundRect(g, x, y, EXPORT_MENU_W, h, 6, Theme.PANEL);
        UiUtil.box(g, x, y, EXPORT_MENU_W, h, Theme.BORDER_STRONG);
        // 浮层本体吞掉点击：这里面有输入框，点空白处只该收掉焦点，不该把整个菜单关了
        addRegion(x, y, EXPORT_MENU_W, h, () -> blurAllExcept(null), null);

        int cx = x + pad;
        int cy = y + 8;
        UiUtil.textLeft(g, this.font, GtLang.get("gtshaders.export.menu_title"), cx, cy, Theme.TEXT);
        cy += this.font.lineHeight + 8;

        UiUtil.textLeft(g, this.font, GtLang.get("gtshaders.export.dir"), cx, cy, Theme.TEXT_SUB);
        cy += this.font.lineHeight + 2;
        // 输入框让出一格给「浏览」：手打或粘一条绝对路径是这一栏最难用的地方，
        // 而选目录本来就该是鼠标的事
        placeholderField(g, exportDirField, cx, cy, innerW - ICON - 4, ROW_H,
                Workspace.exportDir().toString(), mx, my);
        // 路径栏里眼下是串废话时也别把选择框扔到系统默认位置去，退回默认导出目录更接近本意
        Path browseFrom = dir != null ? dir : Workspace.exportDir();
        iconButton(g, cx + innerW - ICON, cy, Icons::folder, GtLang.get("gtshaders.export.browse_hint"),
                FolderPicker.isOpen(), () -> browseExportDir(browseFrom), mx, my);
        cy += ROW_H + 4;

        // 两个预设：实际用到的目的地几乎只有这两个，让人手敲一整条绝对路径纯属受罪
        int presetW = (innerW - 6) / 2;
        secondaryButton(g, cx, cy, presetW, presetH, GtLang.get("gtshaders.export.preset_default"),
                () -> setExportDir(""), mx, my);
        Path packs = resourcePackDir();
        secondaryButton(g, cx + presetW + 6, cy, innerW - presetW - 6, presetH,
                GtLang.get("gtshaders.export.preset_packs"),
                () -> setExportDir(packs == null ? "" : packs.toString()), mx, my);
        cy += presetH + 8;

        UiUtil.textLeft(g, this.font, GtLang.get("gtshaders.export.name"), cx, cy, Theme.TEXT_SUB);
        cy += this.font.lineHeight + 2;
        int zipW = UiText.width(".zip") + 6;
        placeholderField(g, exportNameField, cx, cy, innerW - zipW, ROW_H,
                ResourcePackExporter.defaultFileName(project), mx, my);
        // 后缀单独画在框外：它不该被当成名字的一部分敲进去，也不该被删掉
        UiUtil.textLeft(g, this.font, ".zip", cx + innerW - zipW + 4,
                cy + (ROW_H - this.font.lineHeight) / 2 + 1, Theme.TEXT_DIM);
        cy += ROW_H + 6;

        for (String line : target) {
            UiUtil.textLeft(g, this.font, line, cx, cy, dir == null ? Theme.ERROR : Theme.TEXT_SUB);
            cy += lineH;
        }
        for (String line : hint) {
            UiUtil.textLeft(g, this.font, line, cx, cy, Theme.TEXT_DIM);
            cy += lineH;
        }
        cy += 8;

        if (dir == null) {
            // 路径解析不了就别给「开始导出」：点下去只能得到一句异常
            secondaryButton(g, cx, cy, innerW, ROW_H + 4, GtLang.get("gtshaders.export.run"),
                    () -> {
                    }, mx, my);
        } else {
            primaryButton(g, cx, cy, innerW, ROW_H + 4, GtLang.get("gtshaders.export.run"),
                    this::runExportFromMenu, mx, my);
        }
    }

    /**
     * 输入框 + 留空时的灰色占位。
     *
     * <p>占位写的是<b>留空会用什么</b>，不是「请输入…」：这两个框本来就可以留空，
     * 而留空之后会发生什么正是用户此刻唯一想知道的事。
     */
    private void placeholderField(GuiGraphicsExtractor g, TextField field, int x, int y, int w, int h,
                                  String placeholder, int mx, int my) {
        field.render(g, this.font, x, y, w, h, hovered(mx, my, x, y, w, h), false);
        if (field.text().isEmpty() && !field.isFocused()) {
            UiUtil.textLeft(g, this.font, UiUtil.ellipsize(this.font, placeholder, w - 10),
                    x + 5, y + (h - this.font.lineHeight) / 2 + 1, Theme.TEXT_DIM);
        }
        addFieldRegion(field, x, y, w, h);
    }

    /** 这次导出会写出哪几个文件。名字按真正导出时那套规则算，画面上写的和写出来的不可能对不上。 */
    private String exportFileNames() {
        String custom = exportNameField.text();
        List<String> names = new ArrayList<>();
        if (!project.enabledPostLayers().isEmpty()) {
            names.add(ExportTarget.fileName(custom,
                    ResourcePackExporter.defaultFileName(project)) + ".zip");
        }
        if (!project.enabledCoreLayers().isEmpty()) {
            names.add(ExportTarget.fileName(coreFileName(custom),
                    CoreShaderExporter.defaultFileName(project.name(), project.exportProfile())) + ".zip");
        }
        return names.isEmpty() ? GtLang.get("gtshaders.status.no_effect") : String.join(" · ", names);
    }

    private void setExportDir(String dir) {
        blurAllExcept(null);
        exportDirField.setTextIfUnfocused(dir);
        exportOptions = exportOptions.withDir(dir);
    }

    /**
     * 唤起系统的文件夹选择框。
     *
     * <p>对话框是异步的，这个方法立刻返回，浮层照常画着、游戏照常跑。选完之后<b>当场落盘</b>：
     * 挑目录这件事花的功夫远大于点一下按钮，不该因为随后关掉了编辑器就白挑一次。
     *
     * @param from 打开时定位到哪儿；就是这一栏当前解析出来的目录，为 null 表示那串路径根本不合法
     */
    private void browseExportDir(@Nullable Path from) {
        FolderPicker.open(from,
                picked -> {
                    setExportDir(picked.toString());
                    applyExportTarget();
                },
                error -> showToast(GtLang.get("gtshaders.export.browse_failed", error), true));
    }

    /** 游戏的 resourcepacks/ 目录，也就是导出物最终要去的地方；拿不到客户端时为 null。 */
    private @Nullable Path resourcePackDir() {
        return this.minecraft == null ? null : this.minecraft.getResourcePackDirectory();
    }

    private void runExportFromMenu() {
        applyExportTarget();
        exportMenuOpen = false;
        exportPack();
    }

    // ---- 取色浮层 ----

    /** 色块按下：先记着，等 {@link #promoteLongPressToScrub} 判定是短按还是长按。 */
    private void beginColorPress(String key, ShaderParam p, int anchorX, int anchorY) {
        drag = Drag.COLOR_PRESS;
        colorPressKey = key;
        colorPressParam = p;
        colorPressAt = System.currentTimeMillis();
        colorAnchorX = anchorX;
        colorAnchorY = anchorY;
    }

    /**
     * 按住超过阈值就自动弹出取色浮层并进入连续取色。
     *
     * <p>放在渲染里判定而不是等鼠标移动：按住不动也应该弹出来，
     * 否则「长按」这个动作要靠抖一下鼠标才生效，手感很怪。
     */
    private void promoteLongPressToScrub(int mx, int my) {
        if (drag == Drag.COLOR_PRESS && System.currentTimeMillis() - colorPressAt >= COLOR_LONG_PRESS_MS) {
            beginColorScrub(mx, my);
        }
    }

    private void beginColorScrub(double mx, double my) {
        if (colorPressParam == null) {
            return;
        }
        colorPicker.open(colorPressKey, colorPressParam, colorAnchorX, colorAnchorY, lw, lh, true);
        drag = Drag.COLOR_SCRUB;
        colorPicker.mouseDragged(mx, my);
    }

    private void toggleColorPicker(String key, @Nullable ShaderParam p, int anchorX, int anchorY) {
        if (p == null || key.equals(pickerClosedKey)) {
            // 本次点击开头已经把它顺手关掉了，这里就是「再点一次收起」，不要又弹出来
            return;
        }
        colorPicker.open(key, p, anchorX, anchorY, lw, lh, false);
    }

    private TextField numberField(String key, java.util.function.Supplier<String> value,
                                  java.util.function.Consumer<Float> apply) {
        TextField f = numberFields.computeIfAbsent(key, k -> {
            TextField nf = new TextField(TextField.Kind.NUMBER);
            registerField(nf);
            return nf;
        });
        f.setOnCommit(() -> {
            Float v = f.asFloat();
            if (v != null) {
                apply.accept(v);
            }
        });
        f.setTextIfUnfocused(value.get());
        return f;
    }

    // ---- 状态栏 ----

    private void renderStatusBar(GuiGraphicsExtractor g, int mx, int my) {
        int y = statusTop();
        g.fill(0, y, lw, lh, Theme.PANEL);
        UiUtil.hLine(g, 0, lw, y, Theme.BORDER);

        GlslValidator.Result r = PreviewRuntime.lastCompile();
        String status;
        int color;
        if (r == null) {
            status = "—";
            color = Theme.TEXT_DIM;
        } else if (r.ok()) {
            status = GtLang.get("gtshaders.status.ok");
            color = Theme.SUCCESS;
        } else {
            status = GtLang.get("gtshaders.status.failed") + " · " + r.firstErrorMessage();
            color = Theme.ERROR;
        }
        int dot = y + STATUS_H / 2 - 2;
        g.fill(PAD, dot, PAD + 4, dot + 4, color);
        UiUtil.textLeft(g, this.font, UiUtil.ellipsize(this.font, status, lw / 2),
                PAD + 10, y + (STATUS_H - this.font.lineHeight) / 2 + 1, color);

        // 「没有任何效果」和「有效果但预览关着」是两回事，混成一句会让人以为是预览坏了
        String previewState;
        if (!project.hasEffect()) {
            previewState = GtLang.get("gtshaders.status.no_effect");
        } else {
            previewState = GtLang.get(PreviewRuntime.isActive()
                    ? "gtshaders.status.preview_on" : "gtshaders.status.preview_off");
        }
        if (PackVerify.isActive()) {
            previewState = GtLang.get("gtshaders.status.verifying", String.valueOf(PackVerify.activePackId()));
        } else if (PreviewRuntime.isPackView()) {
            previewState = GtLang.get("gtshaders.status.pack_view") + " · " + previewState;
        }
        String right = String.format(Locale.ROOT, "t=%.2fs   %s×   %s",
                PreviewRuntime.time(), PreviewRuntime.passCount(), previewState);
        UiUtil.textRight(g, this.font, right, lw - PAD,
                y + (STATUS_H - this.font.lineHeight) / 2 + 1, Theme.TEXT_DIM);

        if (!toast.isEmpty() && System.currentTimeMillis() < toastUntil) {
            String msg = UiUtil.ellipsize(this.font, toast, lw - 120);
            int tw = UiText.width(msg) + 20;
            int tx = (lw - tw) / 2;
            int ty = y - 34;
            UiUtil.dropShadow(g, tx, ty, tw, 24, 3);
            UiUtil.roundRect(g, tx, ty, tw, 24, 5, Theme.PANEL);
            UiUtil.box(g, tx, ty, tw, 24, toastError ? Theme.ERROR : Theme.ACCENT);
            UiUtil.textCenter(g, this.font, msg, tx + tw / 2, ty + 8,
                    toastError ? Theme.ERROR : Theme.TEXT);
        }
    }

    // ------------------------------------------------------------------ 控件绘制辅助

    private interface FieldPainter {
        /** 行内控件的绘制回调。行的 y 通过参数传入，避免在 lambda 里捕获会被重新赋值的局部变量。 */
        void paint(int fieldX, int fieldY, int fieldW);
    }

    private int labelledRow(GuiGraphicsExtractor g, String label, int x, int y, int w, FieldPainter painter) {
        int labelW = Math.max(44, Math.min(72, w / 3));
        UiUtil.textLeft(g, this.font, UiUtil.ellipsize(this.font, label, labelW - 4),
                x, y + (ROW_H - this.font.lineHeight) / 2 + 1, Theme.TEXT_SUB);
        painter.paint(x + labelW, y, w - labelW);
        return y + ROW_H + 6;
    }

    private int section(GuiGraphicsExtractor g, String title, int x, int y, int w) {
        UiUtil.textLeft(g, this.font, UiUtil.ellipsize(this.font, title, w), x, y, Theme.TEXT);
        UiUtil.hLine(g, x, x + w, y + this.font.lineHeight + 4, Theme.BORDER);
        return y + this.font.lineHeight + 10;
    }

    private int tabRow(GuiGraphicsExtractor g, int x, int y, int w, String[] labels, int active,
                       java.util.function.IntConsumer onSelect, int mx, int my) {
        int tx = x + PAD;
        for (int i = 0; i < labels.length; i++) {
            int tw = UiText.width(labels[i]) + 12;
            boolean sel = i == active;
            UiUtil.textLeft(g, this.font, labels[i], tx + 6, y + 4, sel ? Theme.TEXT : Theme.TEXT_DIM);
            if (sel) {
                // 选中态用下划线而不是整块底色，和 MasterGo 顶部那排标签一致
                g.fill(tx + 4, y + this.font.lineHeight + 6, tx + tw - 4,
                        y + this.font.lineHeight + 8, Theme.ACCENT);
            }
            final int index = i;
            addRegion(tx, y, tw, this.font.lineHeight + 10, () -> onSelect.accept(index), null);
            tx += tw;
        }
        int bottom = y + this.font.lineHeight + 8;
        UiUtil.hLine(g, x, x + w, bottom, Theme.BORDER);
        return bottom + 8;
    }

    void searchBox(GuiGraphicsExtractor g, TextField field, int x, int y, int w, int h,
                           String placeholder, int mx, int my) {
        UiUtil.roundRect(g, x, y, w, h, 4, Theme.INPUT_BG);
        Icons.search(g, x + 4, y + (h - 14) / 2, 14, Theme.TEXT_DIM);
        if (field.text().isEmpty() && !field.isFocused()) {
            UiUtil.textLeft(g, this.font, UiUtil.ellipsize(this.font, placeholder, w - 26),
                    x + 22, y + (h - this.font.lineHeight) / 2 + 1, Theme.TEXT_DIM);
        } else {
            field.render(g, this.font, x + 18, y, w - 18, h, false, false);
        }
        registerField(field);
        addFieldRegion(field, x, y, w, h);
    }

    private void iconButton(GuiGraphicsExtractor g, int x, int y, IconDrawer icon, String tip,
                            boolean active, Runnable action, int mx, int my) {
        boolean hover = hovered(mx, my, x, y, ICON, ICON);
        if (active) {
            UiUtil.roundRect(g, x, y, ICON, ICON, 4, Theme.BUTTON_ACTIVE_BG);
        } else if (hover) {
            UiUtil.roundRect(g, x, y, ICON, ICON, 4, Theme.BUTTON_HOVER);
        }
        icon.draw(g, x + 3, y + 3, ICON - 6, active ? Theme.ACCENT : Theme.TEXT_SUB);
        addRegion(x, y, ICON, ICON, action, null);
        if (hover) {
            setTooltip(tip, mx, my);
        }
    }

    private void iconButtonBare(GuiGraphicsExtractor g, int x, int y, IconDrawer icon, String tip,
                                int color, Runnable action, int mx, int my) {
        icon.draw(g, x + 3, y + 3, ICON - 6, color);
        addRegion(x, y, ICON, ICON, action, null);
        if (hovered(mx, my, x, y, ICON, ICON)) {
            setTooltip(tip, mx, my);
        }
    }

    private void primaryButton(GuiGraphicsExtractor g, int x, int y, int w, int h, String label,
                               Runnable action, int mx, int my) {
        boolean hover = hovered(mx, my, x, y, w, h);
        UiUtil.roundRect(g, x, y, w, h, 5, hover ? Theme.ACCENT_HOVER : Theme.ACCENT);
        UiUtil.textCenter(g, this.font, UiUtil.ellipsize(this.font, label, w - 8),
                x + w / 2, y + (h - this.font.lineHeight) / 2 + 1, Theme.TEXT_ON_ACCENT);
        addRegion(x, y, w, h, action, null);
    }

    private void secondaryButton(GuiGraphicsExtractor g, int x, int y, int w, int h, String label,
                                 Runnable action, int mx, int my) {
        boolean hover = hovered(mx, my, x, y, w, h);
        UiUtil.roundRect(g, x, y, w, h, 4, hover ? Theme.BUTTON_HOVER : Theme.PANEL_SUNKEN);
        UiUtil.box(g, x, y, w, h, Theme.BORDER);
        UiUtil.textCenter(g, this.font, UiUtil.ellipsize(this.font, label, w - 8),
                x + w / 2, y + (h - this.font.lineHeight) / 2 + 1, Theme.TEXT_SUB);
        addRegion(x, y, w, h, action, null);
    }

    /** 下拉样式的按钮：点一下切到下一个值。原版没有现成的下拉控件，循环切换足够用且不遮挡画面。 */
    private void dropdownButton(GuiGraphicsExtractor g, int x, int y, int w, int h, String label,
                                Runnable action, int mx, int my) {
        boolean hover = hovered(mx, my, x, y, w, h);
        UiUtil.roundRect(g, x, y, w, h, 4, Theme.INPUT_BG);
        if (hover) {
            UiUtil.box(g, x, y, w, h, Theme.BORDER_STRONG);
        }
        UiUtil.textLeft(g, this.font, UiUtil.ellipsize(this.font, label, w - 22),
                x + 6, y + (h - this.font.lineHeight) / 2 + 1, Theme.TEXT);
        Icons.chevronDown(g, x + w - 16, y + (h - 12) / 2, 12, Theme.TEXT_DIM);
        addRegion(x, y, w, h, action, null);
    }

    int wrapText(GuiGraphicsExtractor g, String text, int x, int y, int w, int color) {
        for (String line : wrapLines(text, w)) {
            UiUtil.textLeft(g, this.font, line, x, y, color);
            y += this.font.lineHeight + 1;
        }
        return y;
    }

    /**
     * 断行。
     *
     * <p>先按空格分词，<b>再对单个超宽的词按字符切</b>——第二步是中文必须的：一整句中文
     * 没有空格，只按词切等于一个词，超宽也换不了行，直接糊出面板边界。界面是中文优先的，
     * 而效果说明、参数提示、引导文案全是整句中文，这条不做整套排版都不成立。
     *
     * <p>只有这一个断行实现：绘制与量高共用它，两边的结果不可能对不上。
     */
    private List<String> wrapLines(String text, int w) {
        List<String> out = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : text.split(" ")) {
            String candidate = line.isEmpty() ? word : line + " " + word;
            if (UiText.width(candidate) <= w) {
                line = new StringBuilder(candidate);
                continue;
            }
            if (!line.isEmpty()) {
                out.add(line.toString());
                line = new StringBuilder();
            }
            // 到这里 word 是新一行的开头；它自己就超宽的话逐字符切
            if (UiText.width(word) <= w) {
                line = new StringBuilder(word);
                continue;
            }
            StringBuilder chunk = new StringBuilder();
            for (int i = 0; i < word.length(); i++) {
                char c = word.charAt(i);
                if (!chunk.isEmpty() && UiText.width(chunk.toString() + c) > w) {
                    out.add(chunk.toString());
                    chunk = new StringBuilder();
                }
                chunk.append(c);
            }
            line = chunk;
        }
        if (!line.isEmpty()) {
            out.add(line.toString());
        }
        return out;
    }

    @FunctionalInterface
    interface IconDrawer {
        void draw(GuiGraphicsExtractor g, int x, int y, int size, int color);
    }

    boolean hovered(int mx, int my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    /**
     * 滚轮事件不带修饰键信息，只能现场问输入层。
     *
     * <p>{@code Screen} 已经没有 {@code hasShiftDown()} 这类静态帮手了。
     *
     * <p>26.3 把输入后端从 GLFW 换成了 SDL3，{@code isKeyDown} 随之<b>不再需要窗口句柄</b>
     * （旧签名是 {@code isKeyDown(Window, int)}）——SDL 的键盘状态是进程全局的。
     * 传进来的键码也已经是 SDL scancode，不是 GLFW 键码。
     */
    private boolean keyHeld(int left, int right) {
        return InputConstants.isKeyDown(left) || InputConstants.isKeyDown(right);
    }

    private boolean hasAltDown() {
        return keyHeld(InputConstants.KEY_LALT, InputConstants.KEY_RALT);
    }

    private boolean hasShiftDown() {
        return keyHeld(InputConstants.KEY_LSHIFT, InputConstants.KEY_RSHIFT);
    }

    void setTooltip(String text, int mx, int my) {
        if (text != null && !text.isEmpty()) {
            tooltip = text;
            tooltipX = mx;
            tooltipY = my;
        }
    }

    /**
     * 登记一块可点区域。
     *
     * <p>会先和当前的裁剪区求交（见 {@link #clipRegions}）：<b>看不见的东西不该点得到</b>。
     * 面板内容都是滚动的，滚出视野的那些行如果照样登记，region 就会落到面板<b>外面</b>去，
     * 而 region 表是反向扫描的（后登记的优先），于是浮动面板里一行早已滚上去的参数
     * 能把顶栏的按钮挡住——点下去毫无反应，或者弹出一个完全无关的取色器。
     */
    Region addRegion(int x, int y, int w, int h, @Nullable Runnable click,
                             @Nullable DoubleConsumer dragHandler) {
        int[] clip = regionClip;
        if (clip != null) {
            int x0 = Math.max(x, clip[0]);
            int y0 = Math.max(y, clip[1]);
            int x1 = Math.min(x + w, clip[2]);
            int y1 = Math.min(y + h, clip[3]);
            if (x1 <= x0 || y1 <= y0) {
                // 完全在裁剪区之外：连登记都不登记，返回一个游离的 Region 免得调用方拿到 null
                return new Region(x, y, 0, 0);
            }
            x = x0;
            y = y0;
            w = x1 - x0;
            h = y1 - y0;
        }
        Region r = new Region(x, y, w, h);
        r.click = click;
        r.drag = dragHandler;
        regions.add(r);
        return r;
    }

    /**
     * 右键菜单。
     *
     * <p>压在所有东西最上面，点任何地方（包括菜单自己的条目）都会收起——
     * 上下文菜单本来就是「选一件事然后消失」，留在屏幕上没有任何意义。
     */
    private void renderContextMenu(GuiGraphicsExtractor g, int mx, int my) {
        ContextMenu menu = contextMenu;
        if (menu == null) {
            return;
        }
        int rowH = 20;
        int w = 96;
        for (ContextMenu.Item it : menu.items) {
            w = Math.max(w, UiText.width(it.label()) + 24);
        }
        int sep = 0;
        for (ContextMenu.Item it : menu.items) {
            if (it.separatorBefore()) {
                sep++;
            }
        }
        int h = menu.items.size() * rowH + 8 + sep * 5;
        int x = Math.max(2, Math.min(menu.x, lw - w - 2));
        int y = Math.max(2, Math.min(menu.y, lh - h - 2));

        // 点菜单外面收起。先登记，所以条目永远压在它上面
        addRegion(0, 0, lw, lh, () -> contextMenu = null, null);

        UiUtil.dropShadow(g, x, y, w, h, 4);
        UiUtil.roundRect(g, x, y, w, h, 5, Theme.PANEL);
        UiUtil.box(g, x, y, w, h, Theme.BORDER_STRONG);

        int ry = y + 4;
        for (ContextMenu.Item it : menu.items) {
            if (it.separatorBefore()) {
                UiUtil.hLine(g, x + 6, x + w - 6, ry + 2, Theme.BORDER);
                ry += 5;
            }
            boolean hot = it.enabled() && hovered(mx, my, x + 2, ry, w - 4, rowH);
            if (hot) {
                UiUtil.roundRect(g, x + 2, ry, w - 4, rowH, 4, Theme.BUTTON_HOVER);
            }
            UiUtil.textLeft(g, this.font, UiUtil.ellipsize(this.font, it.label(), w - 20),
                    x + 10, ry + (rowH - this.font.lineHeight) / 2 + 1,
                    it.enabled() ? Theme.TEXT : Theme.TEXT_DIM);
            final Runnable action = it.action();
            final boolean enabled = it.enabled();
            addRegion(x + 2, ry, w - 4, rowH, () -> {
                contextMenu = null;
                if (enabled) {
                    action.run();
                }
            }, null);
            ry += rowH;
        }
    }

    private void openContextMenu(int mx, int my, java.util.function.Consumer<ContextMenu> build) {
        ContextMenu menu = new ContextMenu(mx, my);
        build.accept(menu);
        if (!menu.items.isEmpty()) {
            contextMenu = menu;
        }
    }

    /** 给刚登记的那块区域挂上右键菜单。分开写是因为绝大多数区域没有右键行为。 */
    private void onRightClick(Region r, Runnable action) {
        r.secondary = action;
    }

    /**
     * 把随后登记的 region 限制在这块矩形内，和 {@code enableScissor} 配套使用。
     *
     * <p>不做成栈：编辑器里没有嵌套裁剪，一层就够，多一层结构反而多一处能忘记出栈的地方。
     */
    void clipRegions(int x0, int y0, int x1, int y1) {
        regionClip = new int[]{x0, y0, x1, y1};
    }

    void unclipRegions() {
        regionClip = null;
    }

    void addFieldRegion(TextField field, int x, int y, int w, int h) {
        addRegion(x, y, w, h, () -> {
            blurAllExcept(field);
            field.focus();
        }, null);
    }

    void blurAllExcept(@Nullable TextField keep) {
        for (TextField f : allFields) {
            if (f != keep) {
                f.blur();
            }
        }
        for (TextField f : numberFields.values()) {
            if (f != keep) {
                f.blur();
            }
        }
        for (TextField f : hexFields.values()) {
            if (f != keep) {
                f.blur();
            }
        }
    }

    private @Nullable TextField focusedField() {
        for (TextField f : allFields) {
            if (f.isFocused()) {
                return f;
            }
        }
        return null;
    }

    private static String fmt(float v) {
        return String.format(Locale.ROOT, "%.2f", v);
    }

    // ------------------------------------------------------------------ 输入

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        double mx = lx(event.x());
        double my = ly(event.y());
        clickLx = mx;
        clickLy = my;
        int top = TOP_H;
        // 竖分隔线贯穿到状态栏，命中范围要跟绘制一致
        int bottom = statusTop();
        clickMods = event.modifiers();
        pickerClosedKey = "";

        // 右键：只找挂了上下文菜单的区域，其余一概当作「收起菜单」
        //
        // 常量不能写字面量：26.3 的输入层从 GLFW 换成了 SDL3，鼠标编号整体变了——
        // GLFW 是 左0 右1 中2，SDL 是 左1 中2 右3。这里原本写死的 1 在 26.2 是右键，
        // 到 26.3 变成了<b>左键</b>，于是每一次左键点击都掉进这个分支、直接 return true，
        // 表现就是「整个编辑器点不动」。
        if (event.button() == InputConstants.MOUSE_BUTTON_RIGHT) {
            if (contextMenu != null) {
                contextMenu = null;
                return true;
            }
            for (int i = regions.size() - 1; i >= 0; i--) {
                Region r = regions.get(i);
                if (r.secondary != null && mx >= r.x && mx < r.x + r.w
                        && my >= r.y && my < r.y + r.h) {
                    r.secondary.run();
                    return true;
                }
            }
            return true;
        }

        // 取色浮层压在最上层，先它判定
        if (colorPicker.isOpen() && colorPicker.isOver(mx, my)) {
            colorPicker.mouseClicked(mx, my);
            onParamEdited();
            drag = Drag.COLOR_SCRUB;
            return true;
        }
        // 点在浮层之外：先收起来，再照常派发这次点击。
        // 记下 key 一直留到松手，是为了让「点色块收起」不会在松手时又把它弹回来
        if (colorPicker.isOpen()) {
            pickerClosedKey = colorPicker.key();
            colorPicker.close();
        }
        return dispatchClick(event, doubleClick, mx, my, top, bottom);
    }

    /**
     * 有没有铺满屏幕的浮层开着。
     *
     * <p>这些浮层在 region 表里都先铺了一张全屏关闭区，本体压在它上面。而分隔线、浮动面板标题栏、
     * 代码区这几处是在 region 循环<b>之前</b>抢先判定的——不让开的话，浮层只要压在它们身上，
     * 里面的按钮和输入框就点不着，而画面上它明明在最上层。
     */
    private boolean overlayOpen() {
        return menuOpen || exportMenuOpen;
    }

    private boolean dispatchClick(MouseButtonEvent event, boolean doubleClick,
                                  double mx, double my, int top, int bottom) {
        // 浮层展开时把这几条「压在面板边缘的」优先判定全让开：菜单本体正好横跨左栏分隔线，
        // 导出浮层则压在右栏上，不让开的话点浮层里的东西会变成拖分隔线
        if (!overlayOpen()) {
            // 分隔线优先：它压在面板边缘上，先判定才抓得住
            if (my >= top && my < bottom) {
                if (leftW() > 0 && !layout.leftCollapsed
                        && Math.abs(mx - leftW()) <= SPLITTER_HIT) {
                    drag = Drag.SPLIT_LEFT;
                    return true;
                }
                if (rightW() > 0 && !layout.rightCollapsed
                        && Math.abs(mx - (lw - rightW())) <= SPLITTER_HIT) {
                    drag = Drag.SPLIT_RIGHT;
                    return true;
                }
            }
            // 底部区的上边界。注意是 canvasBottom 不是 bottom——后者已经是贯穿到底的
            // statusTop 了，拿它判会让热区落在状态栏上，那条线怎么抓都抓不住。
            // 只在左右栏之间那一段可抓，否则会和竖分隔线抢命中
            if (bottomH() > 0 && Math.abs(my - canvasBottom()) <= SPLITTER_HIT
                    && mx > leftW() && mx < lw - rightW()) {
                drag = Drag.SPLIT_BOTTOM;
                return true;
            }

            // 浮动面板标题栏拖动
            if (layout.panelVisible
                    && mx >= layout.panelX && mx < layout.panelX + layout.panelWidth - ICON - 10
                    && my >= layout.panelY && my < layout.panelY + 28) {
                drag = Drag.PANEL;
                dragOffX = (int) (mx - layout.panelX);
                dragOffY = (int) (my - layout.panelY);
                return true;
            }
        }

        // 浮窗压在停靠区之上，标题栏与缩放手柄要在 region 扫描之前抢先判定
        if (!overlayOpen() && pressFloatingDock(mx, my)) {
            codeEditor.setFocused(false);
            blurAllExcept(null);
            return true;
        }

        // AI 面板是最上层的浮层，标题栏可以捏住拖走。放在 region 循环之前判，
        // 而手柄矩形不含右侧那两个按钮，所以按钮照样点得到
        int[] aiHandle = aiPanel.dragHandle(lw, lh);
        if (aiHandle != null && mx >= aiHandle[0] && mx < aiHandle[0] + aiHandle[2]
                && my >= aiHandle[1] && my < aiHandle[1] + aiHandle[3]) {
            int[] o = aiPanel.origin(lw, lh);
            dragOffX = (int) mx - o[0];
            dragOffY = (int) my - o[1];
            drag = Drag.AI_PANEL;
            codeEditor.setFocused(false);
            blurAllExcept(null);
            return true;
        }

        if (!overlayOpen() && layout.panelVisible && panelSourceTab && project.selected() != null
                && codeEditor.isOver(mx, my)) {
            blurAllExcept(null);
            codeEditor.mouseClicked(mx, my, this.font, (event.modifiers() & InputConstants.MOD_SHIFT) != 0,
                    (event.modifiers() & InputConstants.MOD_CONTROL) != 0);
            return true;
        }

        // 后登记的区域画在上层，所以反向遍历
        for (int i = regions.size() - 1; i >= 0; i--) {
            Region r = regions.get(i);
            if (!r.hit(mx, my)) {
                continue;
            }
            if (r.panel != null) {
                // 按下就切到这个面板——单击的手感和以前一样；拖不拖得动是后话
                layout.dock.setActive(layout.dock.zoneOf(r.panel), r.panel);
                draggingPanel = r.panel;
                dropZone = null;
                drag = Drag.DOCK_TAB;
                codeEditor.setFocused(false);
                blurAllExcept(null);
                return true;
            }
            if (r.drag != null) {
                dragTarget = r;
                drag = Drag.SLIDER;
                r.drag.accept(normalized(mx, r));
                codeEditor.setFocused(false);
                blurAllExcept(null);
                return true;
            }
            if (r.click != null) {
                codeEditor.setFocused(false);
                r.click.run();
                return true;
            }
            return true;
        }
        blurAllExcept(null);
        codeEditor.setFocused(false);
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
        double mx = lx(event.x());
        double my = ly(event.y());
        ViewportRect v = activeViewport();

        switch (drag) {
            case PANEL -> {
                layout.panelX = (int) (mx - dragOffX);
                layout.panelY = (int) (my - dragOffY);
                return true;
            }
            case AI_PANEL -> {
                aiPanel.moveTo((int) mx - dragOffX, (int) my - dragOffY);
                return true;
            }
            case PANEL_RESIZE -> {
                layout.panelWidth = (int) (mx - layout.panelX) + 4;
                layout.panelHeight = (int) (my - layout.panelY) + 4;
                layout.clamp();
                return true;
            }
            case SPLIT_LEFT -> {
                layout.leftWidth = (int) mx;
                layout.clamp();
                return true;
            }
            case SPLIT_RIGHT -> {
                layout.rightWidth = (int) (lw - mx);
                layout.clamp();
                return true;
            }
            case SPLIT_BOTTOM -> {
                layout.bottomHeight = (int) (statusTop() - my);
                layout.clamp();
                return true;
            }
            case DOCK_TAB -> {
                dropZone = dropZoneAt(mx, my);
                return true;
            }
            case FLOAT_MOVE -> {
                DockPanel fp = floatTarget;
                if (fp != null) {
                    int[] r = layout.dock.floatRect(fp);
                    if (r != null) {
                        layout.dock.setFloatRect(fp, (int) mx - floatOffX, (int) my - floatOffY,
                                r[2], r[3]);
                    }
                    // 拖到边缘就不再是「挪个位置」而是「重新停靠」，这时才提示落位
                    DockZone z = dropZoneAt(mx, my);
                    dropZone = z == DockZone.FLOATING ? null : z;
                }
                return true;
            }
            case FLOAT_RESIZE -> {
                DockPanel fp = floatTarget;
                if (fp != null) {
                    int[] r = layout.dock.floatRect(fp);
                    if (r != null) {
                        layout.dock.setFloatRect(fp, r[0], r[1],
                                Math.max(180, (int) mx - r[0]), Math.max(120, (int) my - r[1]));
                    }
                }
                return true;
            }
            case VIEWPORT_MOVE -> {
                v.translate((float) (dx / scale() / lw), (float) (dy / scale() / lh));
                return true;
            }
            case VIEWPORT_EDGE -> {
                v.dragEdge(edgeX, edgeY, (float) (mx / lw), (float) (my / lh));
                return true;
            }
            case ICON_MOVE -> {
                ShaderLayer layer = project.selected();
                if (layer != null) {
                    setParamVec2(layer, "Position",
                            (float) Math.max(0.0, Math.min(1.0, (mx - dragOffX) / lw)),
                            1f - (float) Math.max(0.0, Math.min(1.0, (my - dragOffY) / lh)));
                    onParamEdited();
                }
                return true;
            }
            case ICON_CORNER -> {
                ShaderLayer layer = project.selected();
                if (layer != null) {
                    float centerX = paramVec2X(layer, "Position", 0.5f) * lw;
                    float centerY = (1f - paramVec2Y(layer, "Position", 0.5f)) * lh;
                    float stretchX = Math.max(0.2f, paramFloat(layer, "StretchX", 1.0f));
                    float stretchY = Math.max(0.2f, paramFloat(layer, "StretchY", 1.0f));
                    float globalScale = Math.max(0.01f, paramFloat(layer, "Scale", 1.0f));
                    float newHalfW = Math.max(2f, (float) Math.abs(mx - centerX));
                    float newHalfH = Math.max(2f, (float) Math.abs(my - centerY));
                    float sizeX = (newHalfW / lw) / stretchX / globalScale;
                    float sizeY = (newHalfH / lh) / stretchY / globalScale;
                    float newSize = (float) Math.max(0.02, Math.min(0.8, (sizeX + sizeY) * 0.5));
                    setParamFloat(layer, "Size", newSize);
                    onParamEdited();
                }
                return true;
            }
            case ICON_EDGE_X -> {
                ShaderLayer layer = project.selected();
                if (layer != null) {
                    float centerX = paramVec2X(layer, "Position", 0.5f) * lw;
                    float size = Math.max(0.01f, paramFloat(layer, "Size", 0.1f));
                    float globalScale = Math.max(0.01f, paramFloat(layer, "Scale", 1.0f));
                    float half = Math.max(1f, size * globalScale * lw / 2f);
                    float newStretch = (float) Math.max(0.2, Math.min(3.0, (mx - centerX) / half));
                    setParamFloat(layer, "StretchX", newStretch);
                    onParamEdited();
                }
                return true;
            }
            case ICON_EDGE_Y -> {
                ShaderLayer layer = project.selected();
                if (layer != null) {
                    float centerY = (1f - paramVec2Y(layer, "Position", 0.5f)) * lh;
                    float size = Math.max(0.01f, paramFloat(layer, "Size", 0.1f));
                    float globalScale = Math.max(0.01f, paramFloat(layer, "Scale", 1.0f));
                    float half = Math.max(1f, size * globalScale * lh / 2f);
                    float newStretch = (float) Math.max(0.2, Math.min(3.0, (my - centerY) / half));
                    setParamFloat(layer, "StretchY", newStretch);
                    onParamEdited();
                }
                return true;
            }
            case SLIDER -> {
                if (dragTarget != null && dragTarget.drag != null) {
                    dragTarget.drag.accept(normalized(mx, dragTarget));
                }
                return true;
            }
            case COLOR_PRESS -> {
                // 按下就往外划：不必等够长按时间，划走本身已经足够表明意图
                beginColorScrub(mx, my);
                return true;
            }
            case COLOR_SCRUB -> {
                colorPicker.mouseDragged(mx, my);
                onParamEdited();
                return true;
            }
            default -> {
            }
        }
        if (layout.panelVisible && panelSourceTab && codeEditor.isFocused()) {
            codeEditor.mouseDragged(mx, my, this.font);
            return true;
        }
        return super.mouseDragged(event, dx, dy);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (drag == Drag.DOCK_TAB) {
            finishPanelDrag();
        }
        if (drag == Drag.FLOAT_MOVE || drag == Drag.FLOAT_RESIZE) {
            // 拖到边缘的浮窗要真的停靠回去，否则「拖回左边」这个最自然的动作没有效果
            DockPanel fp = floatTarget;
            DockZone target = dropZone;
            floatTarget = null;
            dropZone = null;
            if (fp != null && target != null && target != DockZone.FLOATING) {
                layout.dock.move(fp, target, -1);
                zoneScroll.remove(target);
            }
            layout.save();
        }
        if (drag == Drag.SPLIT_LEFT || drag == Drag.SPLIT_RIGHT || drag == Drag.SPLIT_BOTTOM
                || drag == Drag.PANEL || drag == Drag.PANEL_RESIZE || zoomDragging) {
            // 布局类拖拽结束才落盘，拖动过程中反复写文件没有意义
            layout.save();
        }
        zoomDragging = false;
        if (drag == Drag.COLOR_PRESS) {
            // 没按够时间就松手 = 普通单击：切换常驻浮层
            toggleColorPicker(colorPressKey, colorPressParam, colorAnchorX, colorAnchorY);
        } else if (drag == Drag.COLOR_SCRUB && colorPicker.isTransient()) {
            if (colorPicker.wasTouched()) {
                // 长按划选到颜色了，松手即定即收
                colorPicker.close();
            } else {
                // 按住却一次都没划到颜色上：收掉只会像「闪了一下」，转成常驻让人接着调
                colorPicker.makeSticky();
            }
        }
        if (drag == Drag.COLOR_PRESS || drag == Drag.COLOR_SCRUB) {
            colorPressParam = null;
        }
        codeEditor.mouseReleased();
        drag = Drag.NONE;
        dragTarget = null;
        return super.mouseReleased(event);
    }

    private double normalized(double mx, Region r) {
        return Math.max(0, Math.min(1, (mx - r.x) / Math.max(1, r.w)));
    }

    @Override
    public boolean mouseScrolled(double screenX, double screenY, double sx, double sy) {
        double mx = lx(screenX);
        double my = ly(screenY);

        // AI 面板的下拉列表展开时，滚轮归它——否则滚的是底下的画布，
        // 而列表里那些滚不到的模型等于不存在
        if (aiPanel.mouseScrolled(mx, my, sy)) {
            return true;
        }
        if (browser.mouseScrolled(mx, my, sy, lw, lh)) {
            return true;
        }
        // 缩放滑条上滚轮 = 上下一档。放在最前面判：它在顶栏，
        // 下面那些区域判定都是「大块矩形」，晚一步就会被画布或右栏抢走
        Region zoom = zoomRegion;
        if (zoom != null && zoom.hit(mx, my)) {
            changeScale(sy > 0 ? 1 : -1);
            return true;
        }
        if (menuOpen && mx >= menuBounds[0] && mx < menuBounds[0] + menuBounds[2]
                && my >= menuBounds[1] && my < menuBounds[1] + menuBounds[3]) {
            menuScroll = Math.max(0, menuScroll - (int) Math.signum(sy) * 24);
            return true;
        }
        boolean alt = hasAltDown();
        boolean shift = hasShiftDown();
        if (layout.panelVisible && panelSourceTab && project.selected() != null
                && codeEditor.mouseScrolled(mx, my, sy, alt, shift, this.font, baseNet())) {
            return true;
        }
        if (layout.panelVisible && mx >= layout.panelX && mx < layout.panelX + layout.panelWidth
                && my >= layout.panelY && my < layout.panelY + layout.panelHeight) {
            panelScroll = Math.max(0, panelScroll - (int) Math.signum(sy) * 20);
            return true;
        }
        // 停靠区滚轮。三个区共用一条判定，省得每加一个区就多一段几乎一样的代码
        DockZone hoveredZone = zoneAt(mx, my);
        if (hoveredZone != null) {
            scrollZone(hoveredZone, sy);
            return true;
        }
        // 画布区滚轮：以鼠标为锚点缩放效果作用区域
        if (mx > leftW() && mx < lw - rightW() && my > TOP_H && my < canvasBottom()) {
            float factor = sy > 0 ? 0.92f : 1.08f;
            if (project.selected() != null) {
                activeViewport().zoomAround((float) (mx / lw), (float) (my / lh), factor);
            }
            return true;
        }
        return super.mouseScrolled(screenX, screenY, sx, sy);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        TextField focused = focusedField();
        if (focused != null && focused.keyPressed(event.key(), event.modifiers())) {
            return true;
        }

        // AI 面板同样是模态的，而且它开着时浏览器一定是关的，先问它
        if (aiPanel.keyPressed(event.key())) {
            return true;
        }

        // 浏览器是模态的：开着的时候方向键、回车、Esc 都归它，不能漏给下面的编辑器
        if (browser.keyPressed(event.key())) {
            return true;
        }

        boolean ctrl = (event.modifiers() & InputConstants.MOD_CONTROL) != 0;
        if (ctrl) {
            switch (event.key()) {
                case InputConstants.KEY_L -> {
                    openBrowser();
                    return true;
                }
                case InputConstants.KEY_G -> {
                    openAiPanel();
                    return true;
                }
                case InputConstants.KEY_S -> {
                    saveProject();
                    return true;
                }
                case InputConstants.KEY_N -> {
                    newProject();
                    return true;
                }
                case InputConstants.KEY_E -> {
                    toggleQuickProbe();
                    return true;
                }
                case InputConstants.KEY_K -> {
                    // git 的心智：Ctrl+S 覆盖同一个工程文件，Ctrl+K 在时间线上多钉一个点
                    commitSnapshot();
                    return true;
                }
                case InputConstants.KEY_RETURN, InputConstants.KEY_NUMPADENTER -> {
                    compileNow();
                    return true;
                }
                case InputConstants.KEY_EQUALS, InputConstants.KEY_ADD -> {
                    changeScale(1);
                    return true;
                }
                case InputConstants.KEY_MINUS, 86 /* SDL_SCANCODE_KP_MINUS，InputConstants 未给常量 */ -> {
                    changeScale(-1);
                    return true;
                }
                case InputConstants.KEY_0, InputConstants.KEY_NUMPAD0 -> {
                    resetScale();
                    return true;
                }
                default -> {
                }
            }
        }
        // Tab 收起两侧面板，把画面完全让给预览
        if (!codeEditor.isFocused() && event.key() == InputConstants.KEY_TAB) {
            boolean hide = !layout.leftCollapsed || !layout.rightCollapsed;
            layout.leftCollapsed = hide;
            layout.rightCollapsed = hide;
            layout.statusVisible = !hide;
            return true;
        }
        if (layout.panelVisible && panelSourceTab && project.selected() != null
                && codeEditor.keyPressed(event.key(), event.modifiers(), this.font)) {
            // 粘贴时换掉了看不见的坏字符，得说一声——否则代码「自己变了」比不变还吓人
            int cleaned = codeEditor.takeSanitizedCount();
            if (cleaned > 0) {
                showToast(GtLang.get("gtshaders.status.sanitized", cleaned), false);
            }
            return true;
        }
        if (event.key() == InputConstants.KEY_ESCAPE) {
            // Esc 逐层退出：浮层 → 输入焦点 → 界面。写代码时误触退出很恼人
            if (colorPicker.isOpen()) {
                colorPicker.close();
                return true;
            }
            if (sliderPopupOpen()) {
                closeSliderPopup();
                return true;
            }
            if (exportMenuOpen) {
                // 输入框自己会先吃掉一次 Esc（撤销这次编辑），所以焦点在框里时要按两下才收起浮层
                closeExportMenu();
                return true;
            }
            if (menuOpen) {
                menuOpen = false;
                return true;
            }
            if (codeEditor.isFocused()) {
                codeEditor.setFocused(false);
                return true;
            }
            onClose();
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public boolean charTyped(CharacterEvent event) {
        TextField focused = focusedField();
        if (focused != null && focused.charTyped(event.codepoint())) {
            return true;
        }
        if (layout.panelVisible && panelSourceTab && codeEditor.charTyped(event.codepoint())) {
            return true;
        }
        return super.charTyped(event);
    }
}
