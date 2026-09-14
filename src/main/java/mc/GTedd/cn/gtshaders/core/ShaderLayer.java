package mc.GTedd.cn.gtshaders.core;

import org.jspecify.annotations.Nullable;
import mc.GTedd.cn.gtshaders.codegen.GlslCodegen;
import mc.GTedd.cn.gtshaders.codegen.ShaderTexture;
import mc.GTedd.cn.gtshaders.codegen.ParamScanner;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一个效果层，对应导出后 post effect 链里的一个渲染通道。
 *
 * <p>「源码是唯一真相」在这里依然成立：参数表永远由源码扫描得来，不允许脱离源码单独编辑。
 * 这样就不存在「面板上有个参数但代码里没有」的不一致状态。重扫时按名字+类型保留已调好的值，
 * 否则作者每敲一个字符，辛苦拖好的滑块就全被打回默认。
 */
public final class ShaderLayer {

    private String name;
    private String authorSource;
    /**
     * 默认<b>停用</b>。新建的层必须由作者显式勾选「启用」才会进入渲染链——
     * 打开编辑器就自动给玩家套上一层没要求过的效果，是最容易让人以为「坏了」的行为。
     */
    private boolean enabled;
    /** 与下层画面的混合强度 0..1。改它只需重写 uniform，不用重编译。 */
    private float strength = 1f;
    /** 混合方式。改它要重编译，因为混合表达式是生成进着色器里的。 */
    private BlendMode blendMode = BlendMode.NORMAL;
    /**
     * 输入是否用线性采样。
     *
     * <p>对逐像素取样的效果毫无区别（输入输出同尺寸时正好落在纹素中心），
     * 但模糊类效果会<b>刻意在纹素之间取样</b>来减半采样次数——原版 box_blur 就是这么写的，
     * 它的输入必须是 bilinear，否则模糊会变成硬邦邦的重影。
     */
    private boolean bilinear;

    /**
     * 这一层覆盖的原版核心着色器种类；null 表示它是一个普通的后处理通道。
     *
     * <p>把两种东西放进同一个图层列表，是为了让编辑器里已有的一切——代码编辑器、参数面板、
     * 启停、重命名、快照回退——对两者一视同仁。它们的差别只在<b>怎么落地</b>：
     * 后处理层串成一条 post effect 链，核心着色器层各自覆盖一个原版文件。
     *
     * <p>两者的能力边界差别很大，别当成同一种东西用：
     * <ul>
     *   <li>后处理的参数是 uniform，拖滑块实时生效；核心着色器的参数编译成 const，改了要重新生成。</li>
     *   <li>后处理可以叠很多层；核心着色器一个种类只能有一层——它是覆盖，不是叠加。</li>
     * </ul>
     */
    private @Nullable String kindId;

    /**
     * 这一层是不是<b>实体轮廓层</b>——覆盖原版 {@code minecraft:entity_outline} 那条链。
     *
     * <p>和 {@link #kindId} 互斥。三种层各自落地的地方完全不同：
     * <ul>
     *   <li>后处理层 → 串成一条 post effect 链，进后处理请求列表（只拿得到 {@code minecraft:main}）</li>
     *   <li>核心着色器层 → 各自覆盖一个原版 {@code core/*} 文件</li>
     *   <li><b>实体轮廓层</b> → 覆盖 {@code assets/minecraft/post_effect/entity_outline.json}，
     *       能同时读到逐实体剪影遮罩与主画面</li>
     * </ul>
     *
     * <p>做成独立的布尔而不是塞进 {@code kindId}：轮廓层用的是 post effect 的那套 codegen
     * （片段着色器 + uniform 块 + 可拖参数），只是多一个输入采样器；
     * 而核心着色器层走的是完全另一套（钩子注入原版模板、参数编译成 const）。
     * 混进 {@code kindId} 会让每一处 {@code isCore()} 分支都得再判一次「是不是那个特殊值」。
     */
    private boolean outline;

    private final List<ShaderParam> params = new ArrayList<>();
    private List<ShaderTexture> textures = List.of();
    /**
     * 这一层作用到画面的哪一块。
     *
     * <p>放在层上而不是工程上：一条链里每一层本来就可以各管一块——
     * 「暗角铺满全屏、扫描线只压下半屏」是很自然的组合，而共用一个框根本表达不了。
     * 画布上于是每层一个可拖的框，选中谁就编辑谁。
     */
    private final ViewportRect viewport = new ViewportRect();
    private final List<String> scanWarnings = new ArrayList<>();

    private String generatedSource = "";
    private int headerLineCount;
    private boolean sourceDirty = true;

    public ShaderLayer(String name, String authorSource) {
        this.name = name;
        this.authorSource = authorSource == null ? "" : authorSource;
        rescan();
    }

    /** 覆盖的核心着色器种类 id；null 表示这是后处理层。 */
    public @Nullable String kindId() {
        return kindId;
    }

    public void setKindId(@Nullable String kindId) {
        if (java.util.Objects.equals(this.kindId, kindId)) {
            return;
        }
        this.kindId = kindId;
        this.sourceDirty = true;
    }

    /** 是否是覆盖原版核心着色器的层。 */
    public boolean isCore() {
        return kindId != null;
    }

    /** 是否是覆盖原版 {@code entity_outline} 链的实体轮廓层。 */
    public boolean isOutline() {
        return outline;
    }

    public void setOutline(boolean outline) {
        if (this.outline != outline) {
            this.outline = outline;
            // 输入采样器数量、SamplerInfo 的成员数、main 外壳全都不一样，必须整份重新生成
            this.sourceDirty = true;
        }
    }

    /** 普通后处理层：既不覆盖核心着色器，也不是轮廓层。 */
    public boolean isPost() {
        return kindId == null && !outline;
    }

    /** 对应的种类元数据；不是核心层或种类表里没有时返回 null。 */
    public ShaderKind.@Nullable Entry kind() {
        return kindId == null ? null : ShaderKind.find(kindId);
    }

    public String name() {
        return name;
    }

    public void setName(String name) {
        if (name != null && !name.isBlank()) {
            this.name = name;
        }
    }

    public String authorSource() {
        return authorSource;
    }

    public void setAuthorSource(String source) {
        if (source == null) {
            source = "";
        }
        if (source.equals(this.authorSource)) {
            return;
        }
        this.authorSource = source;
        this.sourceDirty = true;
        rescan();
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        if (this.enabled != enabled) {
            this.enabled = enabled;
            // 启停会改变整条链的通道数量与乒乓方向，必须重建
            this.sourceDirty = true;
        }
    }

    public float strength() {
        return strength;
    }

    public void setStrength(float strength) {
        this.strength = Math.max(0f, Math.min(1f, strength));
    }

    public boolean isBilinear() {
        return bilinear;
    }

    public void setBilinear(boolean bilinear) {
        if (this.bilinear != bilinear) {
            this.bilinear = bilinear;
            // 采样方式写在 post effect JSON 的 inputs 里，改它要重建整条链
            this.sourceDirty = true;
        }
    }

    public BlendMode blendMode() {
        return blendMode;
    }

    public void setBlendMode(BlendMode mode) {
        if (mode != null && mode != this.blendMode) {
            this.blendMode = mode;
            this.sourceDirty = true;
        }
    }

    public ViewportRect viewport() {
        return viewport;
    }

    public List<ShaderParam> params() {
        return Collections.unmodifiableList(params);
    }

    /** @texture 声明的额外贴图输入。 */
    public List<ShaderTexture> textures() {
        return textures;
    }

    public List<String> scanWarnings() {
        return Collections.unmodifiableList(scanWarnings);
    }

    public boolean isSourceDirty() {
        return sourceDirty;
    }

    public String generatedSource() {
        return generatedSource;
    }

    public int headerLineCount() {
        return headerLineCount;
    }

    private void rescan() {
        Map<String, ShaderParam> previous = new LinkedHashMap<>();
        for (ShaderParam p : params) {
            previous.put(p.name(), p);
        }

        ParamScanner.Result result = ParamScanner.scan(authorSource);
        params.clear();
        scanWarnings.clear();
        scanWarnings.addAll(result.warnings());
        textures = GlslCodegen.parseTextures(authorSource);

        for (ShaderParam fresh : result.params()) {
            ShaderParam old = previous.get(fresh.name());
            // 形状完全没变（类型与取值范围都一样）时<b>复用旧对象本身</b>，只把元数据更新上去。
            //
            // 这不是省一次分配那么简单：运行时每帧写 uniform 用的是编译那一刻存下来的
            // ShaderParam 引用。无脑换一批新对象的话，作者改一行注释就会让面板上那批
            // 变成孤儿——拖了没反应，要等下一次编译完成才恢复。而自动编译有几百毫秒延迟、
            // 编译失败时更是一直不会更新，这个窗口在实际使用中相当常见。
            if (old != null && old.sameShapeAs(fresh)) {
                old.adoptMetadata(fresh);
                params.add(old);
                continue;
            }
            // 形状变了就只能换对象。名字与类型都一致才迁移旧值：把标量值套到刚改成颜色的
            // 参数上，只会得到一个莫名其妙的深色，还不如用新类型的默认值。
            if (old != null && old.type() == fresh.type()) {
                fresh.setAll(old.value());
            }
            params.add(fresh);
        }
    }

    /**
     * 生成本层的完整着色器源码。
     *
     * @param profile 目标版本 profile
     */
    public GlslCodegen.Output generate(GtProfile profile) {
        ParamScanner.Result scanned = ParamScanner.scan(authorSource);
        // 用重扫结果里的参数对象会丢掉当前值，按名字换回本层持有的那份（值是活的），
        // 这样拖滑块才能不经重编译就实时反映到画面上。
        List<ShaderParam> live = new ArrayList<>();
        for (ShaderParam p : scanned.params()) {
            live.add(findByName(p.name(), p));
        }
        GlslCodegen.Output out = outline
                ? GlslCodegen.generateOutline(profile, scanned.strippedBody(), live)
                : GlslCodegen.generate(profile, scanned.strippedBody(), live, blendMode);
        this.generatedSource = out.source();
        this.headerLineCount = out.headerLineCount();
        this.sourceDirty = false;
        return out;
    }

    /**
     * 剥掉裸 uniform 声明之后的作者源码，行数与 {@link #authorSource()} 相同。
     * 调试插桩要在这一份上做——生成器吃的就是它，插桩后的行号才能与编译器报的一致。
     */
    public String strippedBody() {
        return ParamScanner.scan(authorSource).strippedBody();
    }

    /**
     * 按插桩改写后的源码生成，<b>不</b>改动本层记下的生成产物与脏标记。
     *
     * <p>调试视图是临时的：右栏「管线」页显示的行数、导出时读的源码，都不该因为开了一次探针而变。
     * 只支持后处理层，轮廓层与核心着色器层没有调试视图。
     */
    public GlslCodegen.Output generateInstrumented(GtProfile profile,
                                                   mc.GTedd.cn.gtshaders.codegen.DebugInstrument.Instrumented inst) {
        ParamScanner.Result scanned = ParamScanner.scan(authorSource);
        List<ShaderParam> live = new ArrayList<>();
        for (ShaderParam p : scanned.params()) {
            live.add(findByName(p.name(), p));
        }
        return GlslCodegen.generate(profile, inst.body(), live, blendMode, inst.hook());
    }

    private ShaderParam findByName(String name, ShaderParam fallback) {
        for (ShaderParam p : params) {
            if (p.name().equals(name)) {
                return p;
            }
        }
        return fallback;
    }

    /** 把编译器报的行号换算成作者源码的行号；-1 表示错误落在生成的头部。 */
    public int toAuthorLine(int compilerLine) {
        return GlslCodegen.toAuthorLine(compilerLine, headerLineCount);
    }

    public ShaderLayer copy(String newName) {
        ShaderLayer c = new ShaderLayer(newName, authorSource);
        c.kindId = kindId;
        c.outline = outline;
        c.enabled = enabled;
        c.strength = strength;
        c.blendMode = blendMode;
        c.bilinear = bilinear;
        c.viewport.set(viewport.x0(), viewport.y0(), viewport.x1(), viewport.y1());
        for (int i = 0; i < Math.min(c.params.size(), params.size()); i++) {
            c.params.get(i).setAll(params.get(i).value());
        }
        return c;
    }
}
