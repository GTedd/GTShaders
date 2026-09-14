package mc.GTedd.cn.gtshaders.core;

import org.jspecify.annotations.Nullable;
import mc.GTedd.cn.gtshaders.codegen.GlslCodegen;
import mc.GTedd.cn.gtshaders.i18n.GtLang;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 一个工程：若干个按顺序叠加的效果层。
 *
 * <p>层的顺序就是渲染通道的顺序：第一层拿到原始画面，之后每一层都拿到上一层的输出。
 * 这与 MasterGo 图层面板的语义一致（列表靠上的先渲染、后面的叠在上面），
 * 也正好对应 post effect JSON 里 passes 数组的顺序。
 *
 * <p>停用的层不参与生成——它不会占用一个渲染通道，也就不会影响乒乓缓冲的方向。
 */
public final class ShaderProject {

    private String name;
    private GtProfile exportProfile = GtProfile.MC_26_3;
    private final List<ShaderLayer> layers = new ArrayList<>();
    private int selectedIndex;
    /** 旧格式的工程级取景框，保留用于兼容；实际效果区域使用各层自己的取景框。 */
    private final ViewportRect viewport = new ViewportRect();
    /**
     * 锚点绑定。和取景框一样属于整个工程而不是单层：锚点解算每帧只做一次，
     * 解出来的那份数据写进<b>每一个</b>通道的 uniform，所以每层都能读到同一批锚点。
     * 让每层各配一份只会带来「同一个僵尸在两层里是不同槽位」这种没人想要的自由度。
     */
    private final List<AnchorBinding> anchors = new ArrayList<>();
    private int selectedAnchorIndex;

    /**
     * 一个待生成的渲染通道：哪一层、生成产物、它在链里的位置，以及效果作用区域。
     *
     * @param viewportUv 已经转成 GL 纹理坐标的取景框，直接写进 uniform
     */
    public record PassBuild(ShaderLayer layer, GlslCodegen.Output output, String shaderPath,
                            int passIndex, int enabledIndex, int enabledCount, float[] viewportUv) {
    }

    /**
     * @param passes         按顺序排列的渲染通道
     * @param needsFinalBlit 通道数为奇数时，结果停在中转缓冲里，需要再拷回主缓冲
     */
    public record Build(List<PassBuild> passes, boolean needsFinalBlit) {
    }

    public ShaderProject(String name) {
        this.name = name;
    }

    /**
     * 新建工程：一层<b>空白且停用</b>的效果层。
     *
     * <p>刻意不预置任何效果——打开编辑器时玩家的画面必须和没装这个 mod 时一模一样。
     * 那一层只是给作者一个能立刻开始敲代码的地方，写完勾选「启用」才会进链。
     * 想要现成的效果，走菜单里的示例模板。
     */
    public static ShaderProject createDefault() {
        ShaderProject p = new ShaderProject(GtLang.get("gtshaders.project.default_name"));
        p.layers.add(new ShaderLayer(GtLang.get("gtshaders.layer.default_name", 1),
                GlslCodegen.defaultAuthorBody()));
        return p;
    }

    /** 当前是否有任何效果在生效。全部停用（或一层都没有）时为 false，此时画面应当完全是原样。 */
    public boolean hasEffect() {
        return !enabledLayers().isEmpty();
    }

    public String name() {
        return name;
    }

    public void setName(String name) {
        this.name = name == null || name.isBlank()
                ? GtLang.get("gtshaders.project.default_name") : name;
    }

    public GtProfile exportProfile() {
        return exportProfile;
    }

    public void setExportProfile(GtProfile profile) {
        if (profile != null) {
            this.exportProfile = profile;
        }
    }

    public List<ShaderLayer> layers() {
        return Collections.unmodifiableList(layers);
    }

    public int layerCount() {
        return layers.size();
    }

    /**
     * 当前选中的层，<b>-1 表示什么都没选</b>。
     *
     * <p>「没选中」必须是个能表达的状态：画布上每层各有一个可拖的取景框，点空白处
     * 就该把选中取消掉——否则总有一层是选中的，那个框和跟着它的控件组永远赖在画面上。
     */
    public int selectedIndex() {
        if (layers.isEmpty() || selectedIndex < 0) {
            return -1;
        }
        return Math.min(selectedIndex, layers.size() - 1);
    }

    /** @param index 传 -1 取消选中 */
    public void setSelectedIndex(int index) {
        if (index < 0) {
            selectedIndex = -1;
        } else if (index < layers.size()) {
            selectedIndex = index;
        }
    }

    public @Nullable ShaderLayer selected() {
        int i = selectedIndex();
        return i < 0 ? null : layers.get(i);
    }

    /** 参与渲染的层。停用的层直接不占通道。 */
    public List<ShaderLayer> enabledLayers() {
        List<ShaderLayer> out = new ArrayList<>();
        for (ShaderLayer l : layers) {
            if (l.isEnabled()) {
                out.add(l);
            }
        }
        return out;
    }

    /**
     * 参与后处理链的层。
     *
     * <p>核心着色器层必须排除在外：它们覆盖的是原版文件，不占后处理通道。
     * 混进来的话会让乒乓方向算错，画面整个黑掉——而且是在完全没有报错的情况下。
     */
    public List<ShaderLayer> enabledPostLayers() {
        List<ShaderLayer> out = new ArrayList<>();
        for (ShaderLayer l : layers) {
            if (l.isEnabled() && l.isPost()) {
                out.add(l);
            }
        }
        return out;
    }

    /**
     * 参与实体轮廓链的层。
     *
     * <p>它们和后处理层<b>各自成一条独立的链</b>，不共用通道也不共用乒乓缓冲：
     * 后处理链走后处理请求列表（只拿得到 {@code minecraft:main}），
     * 轮廓链覆盖 {@code minecraft:entity_outline}（拿得到剪影遮罩 + 主画面）。
     * 混在一起算通道号会让两条链的乒乓方向全错。
     */
    public List<ShaderLayer> enabledOutlineLayers() {
        List<ShaderLayer> out = new ArrayList<>();
        for (ShaderLayer l : layers) {
            if (l.isEnabled() && l.isOutline()) {
                out.add(l);
            }
        }
        return out;
    }

    /** 参与覆盖原版核心着色器的层。同一个种类只取<b>最后一个</b>——覆盖不是叠加。 */
    public List<ShaderLayer> enabledCoreLayers() {
        java.util.LinkedHashMap<String, ShaderLayer> byKind = new java.util.LinkedHashMap<>();
        for (ShaderLayer l : layers) {
            if (l.isEnabled() && l.isCore()) {
                byKind.put(l.kindId(), l);
            }
        }
        return new ArrayList<>(byKind.values());
    }

    /**
     * 新增一个实体轮廓层。
     *
     * <p>同一个工程只允许有一个：轮廓链的 id 是原版固定的 {@code minecraft:entity_outline}，
     * 一个资源包里只能有一份。已经有了就直接选中它，而不是再加一个永远不会生效的。
     * （多通道仍然可以——那是同一层内的事，靠下面 {@link #generateOutline} 串起来。）
     */
    public ShaderLayer addOutlineLayer() {
        for (int i = 0; i < layers.size(); i++) {
            if (layers.get(i).isOutline()) {
                selectedIndex = i;
                return layers.get(i);
            }
        }
        ShaderLayer layer = new ShaderLayer(GtLang.get("gtshaders.outline.default_name"),
                GlslCodegen.defaultOutlineBody());
        layer.setOutline(true);
        layers.add(layer);
        selectedIndex = layers.size() - 1;
        return layer;
    }

    public ShaderLayer addLayer() {
        ShaderLayer layer = new ShaderLayer(
                GtLang.get("gtshaders.layer.default_name", layers.size() + 1),
                GlslCodegen.newLayerBody());
        layers.add(layer);
        selectedIndex = layers.size() - 1;
        return layer;
    }

    /**
     * 新增一个覆盖原版核心着色器的层。
     *
     * <p>同一个种类已经有层时直接选中它而不是再加一个——核心着色器是覆盖，
     * 两层同种类只有后一层生效，多出来的那层只会让人以为自己写的东西没生效。
     */
    public ShaderLayer addCoreLayer(ShaderKind.Entry kind) {
        for (int i = 0; i < layers.size(); i++) {
            if (kind.id().equals(layers.get(i).kindId())) {
                selectedIndex = i;
                return layers.get(i);
            }
        }
        ShaderLayer layer = new ShaderLayer(kind.displayName(),
                mc.GTedd.cn.gtshaders.codegen.CoreShaderCodegen.defaultBody(kind));
        layer.setKindId(kind.id());
        layers.add(layer);
        selectedIndex = layers.size() - 1;
        return layer;
    }

    /**
     * 按对象删一层。批量删除必须按对象而不是下标——删掉一个之后后面所有下标都会挪位，
     * 照着一串旧下标删下去会删错东西。
     */
    public boolean removeLayer(ShaderLayer layer) {
        int i = layers.indexOf(layer);
        if (i < 0) {
            return false;
        }
        layers.remove(i);
        if (layers.isEmpty()) {
            selectedIndex = -1;
        } else if (selectedIndex >= layers.size()) {
            selectedIndex = layers.size() - 1;
        }
        return true;
    }

    public void addLayer(ShaderLayer layer) {
        layers.add(layer);
        selectedIndex = layers.size() - 1;
    }

    public ShaderLayer duplicateSelected() {
        ShaderLayer src = selected();
        if (src == null) {
            return null;
        }
        ShaderLayer copy = src.copy(src.name() + " ·2");
        layers.add(selectedIndex() + 1, copy);
        selectedIndex = selectedIndex() + 1;
        return copy;
    }

    /**
     * 删除当前选中的层。
     *
     * <p>允许一路删到零层——「把效果全部清掉、回到干净画面」是必须能做到的操作，
     * 为了保证链非空而强留一层，反而会让人怀疑效果没真的关掉。
     *
     * @return 是否真的删掉了；一层都没有时返回 false
     */
    public boolean removeSelected() {
        if (layers.isEmpty()) {
            return false;
        }
        layers.remove(selectedIndex());
        selectedIndex = Math.max(0, Math.min(selectedIndex, layers.size() - 1));
        return true;
    }

    /** 清空所有效果层。 */
    public void clearLayers() {
        layers.clear();
        selectedIndex = 0;
    }

    /**
     * 停用所有效果层，但保留源码。
     *
     * @return 是否有层被真的停用（全都已经停用时返回 false）
     */
    public boolean disableAllLayers() {
        boolean changed = false;
        for (ShaderLayer l : layers) {
            if (l.isEnabled()) {
                l.setEnabled(false);
                changed = true;
            }
        }
        return changed;
    }

    /** @param delta -1 上移、+1 下移 */
    public boolean moveSelected(int delta) {
        int from = selectedIndex();
        int to = from + delta;
        if (to < 0 || to >= layers.size()) {
            return false;
        }
        Collections.swap(layers, from, to);
        selectedIndex = to;
        return true;
    }

    /** 任意一层的源码/混合设置被改动过，就需要重建整条链。 */
    public boolean isDirty() {
        for (ShaderLayer l : layers) {
            if (l.isSourceDirty()) {
                return true;
            }
        }
        return false;
    }

    /**
     * 为所有启用的层生成着色器与通道信息。
     *
     * @param profile     目标版本 profile
     * @param pathPrefix  着色器资源路径前缀（预览用带代次号的路径，导出用工程名）
     */
    public ViewportRect viewport() {
        return viewport;
    }

    // ---------------------------------------------------------------- 锚点绑定

    public List<AnchorBinding> anchors() {
        return Collections.unmodifiableList(anchors);
    }

    public int anchorCount() {
        return anchors.size();
    }

    public int selectedAnchorIndex() {
        return Math.max(0, Math.min(selectedAnchorIndex, anchors.size() - 1));
    }

    public void setSelectedAnchorIndex(int index) {
        if (index >= 0 && index < anchors.size()) {
            selectedAnchorIndex = index;
        }
    }

    public AnchorBinding selectedAnchor() {
        return anchors.isEmpty() ? null : anchors.get(selectedAnchorIndex());
    }

    /**
     * 这条绑定占用的第一个槽位号——也就是着色器里该写 {@code gtAnchor(几)}。
     *
     * <p>槽位按列表顺序累加分配，所以这个值会随着上面的绑定增删而变。界面必须把它显示出来，
     * 否则作者删掉第一条绑定之后，源码里的 {@code gtAnchor(1)} 会静悄悄指向另一个东西。
     *
     * @return 起始槽位号；超出 {@link AnchorSlot#SLOTS} 时返回 -1，表示这条绑定挤不进去了
     */
    public int anchorSlotBase(int bindingIndex) {
        int base = 0;
        for (int i = 0; i < anchors.size() && i < bindingIndex; i++) {
            if (anchors.get(i).isEnabled()) {
                base += anchors.get(i).maxSlots();
            }
        }
        return base < AnchorSlot.SLOTS ? base : -1;
    }

    /** 按稳定 id 找绑定；找不到返回 {@code null}（绑定被删了，或者工程换过）。 */
    public @Nullable AnchorBinding findAnchorById(String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        for (AnchorBinding b : anchors) {
            if (b.id().equals(id)) {
                return b;
            }
        }
        return null;
    }

    /**
     * 这条绑定<b>此刻</b>占的起始槽位号。
     *
     * @return 槽位号；id 找不到、绑定被停用、或者挤不进 {@link AnchorSlot#SLOTS} 时返回 -1
     */
    public int anchorSlotBaseById(String id) {
        for (int i = 0; i < anchors.size(); i++) {
            if (anchors.get(i).id().equals(id)) {
                return anchors.get(i).isEnabled() ? anchorSlotBase(i) : -1;
            }
        }
        return -1;
    }

    /**
     * 把所有 {@link ParamType#ANCHOR} 参数的 {@code anchorRef} 重新解析成当前槽位号，写回参数值。
     *
     * <p><b>必须在编译和上传 uniform 之前调用</b>——绑定的增删和排序随时可能发生，
     * 而 codegen、uniform 上传、资源包导出这三条路径读的都是 {@code value[0]}。
     * 在这里集中解析一次，那三条路径就不必知道 anchorRef 的存在。
     *
     * <p>解析不到（绑定被删了、或者被停用了）时<b>保留上一次的值不动</b>，而不是清零：
     * 清零会让效果静默跳到 0 号绑定身上——那正是这套引用要消灭的那类错误。
     * 界面会把这种失配标红，由人来决定改指哪一条。
     */
    public void resolveAnchorRefs() {
        for (ShaderLayer layer : layers) {
            for (ShaderParam p : layer.params()) {
                if (p.type() != ParamType.ANCHOR || p.anchorRef().isEmpty()) {
                    continue;
                }
                int slot = anchorSlotBaseById(p.anchorRef());
                if (slot >= 0) {
                    p.set(0, slot);
                }
            }
        }
    }

    /** 已经分配出去的槽位总数，用来在界面上显示「还剩几个」。 */
    public int anchorSlotsUsed() {
        int used = 0;
        for (AnchorBinding b : anchors) {
            if (b.isEnabled()) {
                used += b.maxSlots();
            }
        }
        return used;
    }

    public AnchorBinding addAnchor(AnchorBinding binding) {
        anchors.add(binding);
        selectedAnchorIndex = anchors.size() - 1;
        return binding;
    }

    public boolean removeAnchor(int index) {
        if (index < 0 || index >= anchors.size()) {
            return false;
        }
        anchors.remove(index);
        selectedAnchorIndex = Math.max(0, Math.min(selectedAnchorIndex, anchors.size() - 1));
        return true;
    }

    /** @param delta -1 上移、+1 下移。顺序即槽位分配顺序，所以移动会改变 {@link #anchorSlotBase}。 */
    public boolean moveAnchor(int index, int delta) {
        int to = index + delta;
        if (index < 0 || index >= anchors.size() || to < 0 || to >= anchors.size()) {
            return false;
        }
        Collections.swap(anchors, index, to);
        selectedAnchorIndex = to;
        return true;
    }

    public void clearAnchors() {
        anchors.clear();
        selectedAnchorIndex = 0;
    }

    public Build generate(GtProfile profile, String pathPrefix) {
        List<ShaderLayer> enabled = enabledPostLayers();
        List<PassBuild> passes = new ArrayList<>();
        for (int i = 0; i < enabled.size(); i++) {
            ShaderLayer layer = enabled.get(i);
            GlslCodegen.Output out = layer.generate(profile);
            // 只有一个通道时不加序号后缀：产物文件名直接就是效果名（blackhole.fsh 而不是
            // blackhole_0.fsh）。库里的效果全是单通道，而这些文件是要被人翻看、拖动、
            // 在文档里引用的——多出来的 _0 只会让人以为还有个 _1。
            String path = enabled.size() == 1 ? pathPrefix : pathPrefix + "_" + i;
            // 取景框逐层取：一条链里每层可以各管画面的一块
            passes.add(new PassBuild(layer, out, path, i, i, enabled.size(),
                    layer.viewport().toGlUv()));
        }
        // 通道在主缓冲与中转缓冲之间乒乓：第 0 个写中转、第 1 个写主缓冲……
        // 奇数个通道时最终结果停在中转缓冲，必须再拷回主缓冲才看得见。
        boolean needsFinalBlit = enabled.size() % 2 == 1;
        return new Build(passes, needsFinalBlit);
    }

    /**
     * 调试视图用的链：启用的后处理层<b>截到 {@code target} 为止</b>，{@code target} 那一层可以带插桩。
     *
     * <p>截断有两个用处：看某一层的输出时，后面的层不能再改它；插桩把值编码进颜色之后，
     * 后面的层更不能再拿这份颜色去算。所以被调试的那层永远是链上最后一个通道。
     *
     * <p>{@code enabledCount} 仍取完整的启用层数：作者可能读 {@code GTLayerCount}，
     * 调试时它不该跟着变。
     *
     * @param inst 为 null 时不插桩，只截断——用来看这一层原样的输出
     * @return target 不是启用的后处理层时返回空链
     */
    public Build generateDebug(GtProfile profile, String pathPrefix, ShaderLayer target,
                               mc.GTedd.cn.gtshaders.codegen.DebugInstrument.@Nullable Instrumented inst) {
        List<ShaderLayer> enabled = enabledPostLayers();
        int last = -1;
        for (int i = 0; i < enabled.size(); i++) {
            if (enabled.get(i) == target) {
                last = i;
                break;
            }
        }
        if (last < 0) {
            return new Build(List.of(), false);
        }
        List<PassBuild> passes = new ArrayList<>();
        for (int i = 0; i <= last; i++) {
            ShaderLayer layer = enabled.get(i);
            GlslCodegen.Output out = i == last && inst != null
                    ? layer.generateInstrumented(profile, inst)
                    : layer.generate(profile);
            passes.add(new PassBuild(layer, out, pathPrefix + "_" + i, i, i, enabled.size(),
                    layer.viewport().toGlUv()));
        }
        return new Build(passes, (last + 1) % 2 == 1);
    }

    /**
     * 为启用的实体轮廓层生成着色器与通道信息。
     *
     * <p>和 {@link #generate} 是同一套结构，只是这条链的两端不同：起点读
     * {@code minecraft:entity_outline}、终点也要写回它，中间在 {@code swap} 之间乒乓。
     * 通道数为奇数时同样要补一次 blit，否则结果停在 {@code swap} 里，
     * 而引擎最后做 {@code blitAndBlendToTexture} 拿的是 {@code entity_outline}——画面上什么都不会有。
     */
    public Build generateOutline(GtProfile profile, String pathPrefix) {
        List<ShaderLayer> enabled = enabledOutlineLayers();
        List<PassBuild> passes = new ArrayList<>();
        for (int i = 0; i < enabled.size(); i++) {
            ShaderLayer layer = enabled.get(i);
            GlslCodegen.Output out = layer.generate(profile);
            String path = enabled.size() == 1 ? pathPrefix : pathPrefix + "_" + i;
            passes.add(new PassBuild(layer, out, path, i, i, enabled.size(),
                    layer.viewport().toGlUv()));
        }
        return new Build(passes, enabled.size() % 2 == 1);
    }

    public ShaderProject copy() {
        ShaderProject c = new ShaderProject(name);
        c.exportProfile = exportProfile;
        c.viewport.set(viewport.x0(), viewport.y0(), viewport.x1(), viewport.y1());
        for (ShaderLayer l : layers) {
            c.layers.add(l.copy(l.name()));
        }
        for (AnchorBinding a : anchors) {
            c.anchors.add(a.copy());
        }
        c.selectedIndex = selectedIndex;
        c.selectedAnchorIndex = selectedAnchorIndex;
        return c;
    }
}
