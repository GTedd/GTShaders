package mc.GTedd.cn.gtshaders.runtime;

import com.google.gson.JsonObject;
import com.mojang.blaze3d.pipeline.PipelineCache;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.buffers.Std140SizeCalculator;
import com.mojang.renderpearl.api.pipeline.ShaderType;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.serialization.JsonOps;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.PostChain;
import net.minecraft.client.renderer.PostChainConfig;
import net.minecraft.client.renderer.PostPass;
import net.minecraft.client.renderer.Projection;
import net.minecraft.client.renderer.ProjectionMatrixBuffer;
import net.minecraft.client.renderer.ShaderManager;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryStack;
import mc.GTedd.cn.gtshaders.GTShaders;
import mc.GTedd.cn.gtshaders.codegen.CoreShaderCodegen;
import mc.GTedd.cn.gtshaders.codegen.ParamScanner;
import mc.GTedd.cn.gtshaders.core.AnchorSlot;
import mc.GTedd.cn.gtshaders.core.ShaderKind;
import mc.GTedd.cn.gtshaders.core.ShaderLayer;
import mc.GTedd.cn.gtshaders.codegen.GlslCodegen;
import mc.GTedd.cn.gtshaders.codegen.PackParity;
import mc.GTedd.cn.gtshaders.codegen.PostEffectJsonBuilder;

import mc.GTedd.cn.gtshaders.codegen.ShaderTexture;
import mc.GTedd.cn.gtshaders.core.GtProfile;
import mc.GTedd.cn.gtshaders.core.ParamType;
import mc.GTedd.cn.gtshaders.core.ShaderParam;
import mc.GTedd.cn.gtshaders.core.ShaderProject;
import mc.GTedd.cn.gtshaders.core.EmitterSlot;
import mc.GTedd.cn.gtshaders.core.TrailSlot;
import mc.GTedd.cn.gtshaders.core.ViewportRect;
import mc.GTedd.cn.gtshaders.i18n.GtLang;
import mc.GTedd.cn.gtshaders.mixin.PipelineCacheAccessor;
import mc.GTedd.cn.gtshaders.mixin.PostChainAccessor;
import mc.GTedd.cn.gtshaders.mixin.PostPassAccessor;
import mc.GTedd.cn.gtshaders.mixin.RenderSystemAccessor;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 即时预览的运行时：把编辑器里的效果层变成正在玩家屏幕上跑的后处理链。
 *
 * <p><b>后处理这条路径不使用资源包，也不触发任何资源重载。</b>整条链路是：
 * <pre>
 *   效果层源码 → codegen → 自行预编译校验 → 内存源码 + PostChainConfig
 *              → ShaderConfigsMixin 供源码、ShaderManagerMixin 供链
 *              → 原版 PostChain.load → 原版 GameRenderer 每帧执行
 * </pre>
 * 编译器和渲染管线全程都是原版的，所以预览与导出物天然一致。
 *
 * <p>两条更新路径分得很清楚，这是流畅度的关键：
 * <ul>
 *   <li><b>源码/混合模式变了</b> → 换一批新的着色器 id 重建整条链
 *       （原版 shaderCache 以 id 为键，沿用旧 id 会拿到旧编译结果——热重载最容易踩的坑）</li>
 *   <li><b>只是参数值、强度或时间变了</b> → 直接往各通道的 uniform 缓冲写字节，<b>零编译</b></li>
 * </ul>
 *
 * <p><b>挂载方式（26.3）</b>：原版每帧重建一个后处理请求列表，我们在
 * {@code GameRenderer.preparePostEffects} 的入口把自己的 id 追加进去
 * （见 {@link #appendActiveIds}）。26.2 时代那套「写 postEffectId 字段 + 每帧补回」
 * 连同它要修的 bug（切视角时被原版清掉）一起消失了。
 * </ul>
 *
 * <p>核心着色器（{@link #applyCore}）走的是同一套内存源码机制，
 * 只是<b>让缓存失效的手段不同</b>：它覆盖的是 {@code minecraft:core/*} 这类固定 id，
 * 换不了 id，所以改用清空 {@code PipelineCache} 让管线整体作废再惰性重编。
 * 同样不需要资源包、不需要资源重载。
 * 两条路径的能力差别：后处理参数是 uniform、可以叠很多层；核心着色器参数编译成 const、
 * 一个种类只能有一层。
 */
public final class PreviewRuntime {

    public static final String NAMESPACE = "gtshaders";
    private static int generation;
    private static @Nullable Identifier effectId;
    /**
     * 正在直接挂着的原版效果 id（{@code minecraft:blur} 之类）。
     *
     * <p>与 {@link #effectId} 互斥：要么跑我们编译出来的链，要么把原版那条原封不动挂上去。
     * 直接挂原版时我们不参与任何编译，{@link #owns} 也仍然只认自己的 id——
     * 于是 mixin 不会去拦截它，整条链完全走原版路径，和 26.3 的 {@code /posteffect} 等价。
     */
    private static @Nullable Identifier vanillaEffectId;
    /** 着色器 id → 内存源码。多效果层时每个通道一份。 */
    private static final Map<Identifier, String> SHADER_SOURCES = new ConcurrentHashMap<>();
    /**
     * 被接管的核心着色器：{@code "<id>|vsh"} → 源码。
     *
     * <p>键里带上阶段，是因为核心着色器<b>顶点和片段都要给</b>，
     * 而后处理只有片段——两者不能共用一张按 id 索引的表。
     */
    private static final Map<String, String> CORE_SOURCES = new ConcurrentHashMap<>();
    private static @Nullable PostChainConfig config;
    private static @Nullable PostChain chain;
    private static List<ShaderProject.PassBuild> passes = List.of();
    /**
     * 实体轮廓链。和主链<b>完全并行的一套</b>，不共用任何状态。
     *
     * <p>它覆盖的是原版固定 id {@code minecraft:entity_outline}，而不是我们自己命名空间下
     * 带代次号的 id。所以让缓存失效的手段也不同：主链靠换 id，这里只能把
     * {@link #outlineChain} 关掉重建（原版 {@code ShaderManager} 每帧都会来问一次，
     * 我们返回新建的那条即可）。
     */
    private static final Identifier OUTLINE_EFFECT_ID =
            Identifier.fromNamespaceAndPath("minecraft", "entity_outline");
    private static @Nullable PostChainConfig outlineConfig;
    private static @Nullable PostChain outlineChain;
    private static List<ShaderProject.PassBuild> outlinePasses = List.of();
    /** 直接持有工程里那个取景框对象，这样界面上拖动它就能立刻反映到画面，无需重新编译。 */
    private static ViewportRect viewport = new ViewportRect();

    private static boolean active;
    /** 最近一次编译结果；从未编译过时为 null。 */
    private static GlslValidator.Result lastCompile;

    /** 播放状态与时间轴。时间由我们自己推进，所以可以暂停、可以回拨。 */
    private static boolean playing = true;
    private static float time;
    private static float deltaTime;
    private static int frameCounter;
    private static long lastNanos;
    /** 这一帧原版 {@code GameTime} 折成的秒数，{@link #advanceTime} 每帧刷新。 */
    private static float lastGameSeconds;
    /** 与 {@link mc.GTedd.cn.gtshaders.codegen.GlslCodegen#GAME_DAY_SECONDS} 是同一个数。 */
    private static final float GAME_DAY_SECONDS = 1200f;
    /**
     * 资源包视角：让预览拿到的每一个 uniform 都与纯资源包加载时<b>完全相同</b>——
     * 时间走原版时钟、锚点 / 载体 / 轨迹全为 0、{@code GTSystem} 是 JSON 里那份常量。
     * 关掉它就回到编辑器视角（可暂停的时钟、活的锚点）。两种视角跑的是同一份着色器源码。
     */
    private static boolean packView;
    /**
     * 按 {@code end_of_frame} 的方式挂载：排在列表最前，并顶替原版的 {@code minecraft:end_of_frame}。
     * 这与导出面板的「触发方式」联动——导出成 end_of_frame 的包在真实加载时就是这个位置，
     * 排在所有 {@code /posteffect} 效果之前；导出成指令触发的包则按 add 的先后追加在后面。
     */
    private static boolean mountAsEndOfFrame;
    private static final Identifier VANILLA_END_OF_FRAME =
            Identifier.fromNamespaceAndPath("minecraft", "end_of_frame");

    private PreviewRuntime() {
    }

    // ---------------------------------------------------------------- 供 Mixin 调用

    /**
     * 命中我们的着色器 id 时返回内存源码；其余返回 null 交还原版。
     *
     * <p>两类都走这里：后处理通道用的是我们自己命名空间下的 id，只有片段阶段；
     * 核心着色器覆盖的是 {@code minecraft:core/*}，顶点和片段都要给。
     */
    public static @Nullable String shaderSourceFor(Identifier id, ShaderType type) {
        String core = CORE_SOURCES.get(coreKey(id, type));
        if (core != null) {
            return core;
        }
        return type == ShaderType.FRAGMENT ? SHADER_SOURCES.get(id) : null;
    }

    private static String coreKey(Identifier id, ShaderType type) {
        return id + "|" + (type == ShaderType.VERTEX ? "vsh" : "fsh");
    }

    /**
     * 把工程里启用的核心着色器层编译进内存，并让游戏重新加载着色器。
     *
     * <p>和后处理一样<b>不需要资源包，也不需要重载资源</b>，只是让缓存失效的手段不同：
     * 后处理换一批着色器 id 就够了；核心着色器的 id 是原版固定的，换不了，
     * 所以改用清空 {@code PipelineCache} 让全部管线作废，
     * 之后它们会惰性重编——而重编时问的正是被我们接管的 {@code ShaderSource.getShader}。
     *
     * <p>代价是<b>所有</b>管线都要重编一次，会有一小下卡顿；
     * 但比 {@code reloadResourcePacks()} 便宜一个数量级，也不会闪屏，
     * 因此可以跟着编辑自动跑。
     *
     * @return 实际接管了几个着色器文件；0 表示没有启用的核心层或缺少原版模板
     */
    public static int applyCore(ShaderProject project, GtProfile profile) {
        CORE_SOURCES.clear();
        int applied = 0;
        for (ShaderLayer layer : project.enabledCoreLayers()) {
            ShaderKind.Entry kind = layer.kind();
            if (kind == null) {
                continue;
            }
            ParamScanner.Result scanned = ParamScanner.scan(layer.authorSource());
            for (String stage : kind.stages()) {
                String template = VanillaSource.template(kind, profile, stage);
                if (template == null) {
                    continue;
                }
                try {
                    CoreShaderCodegen.Output out = CoreShaderCodegen.generate(
                            kind, profile, stage, template, scanned.strippedBody(), scanned.params());
                    Identifier id = Identifier.fromNamespaceAndPath("minecraft", kind.path());
                    CORE_SOURCES.put(coreKey(id, "vsh".equals(stage)
                            ? ShaderType.VERTEX : ShaderType.FRAGMENT), out.source());
                    applied++;
                } catch (RuntimeException e) {
                    // 注入点找不到之类：这一层跳过，别把整批都拖垮
                    GTShaders.LOGGER.warn("核心着色器注入失败：{} {}", kind.id(), stage, e);
                }
            }
        }
        invalidatePipelines();
        return applied;
    }

    /** 撤掉所有核心着色器接管，恢复原版。 */
    public static void clearCore() {
        if (CORE_SOURCES.isEmpty()) {
            return;
        }
        CORE_SOURCES.clear();
        invalidatePipelines();
    }

    public static boolean hasCore() {
        return !CORE_SOURCES.isEmpty();
    }

    /** 当前接管了几个核心着色器文件。 */
    public static int coreCount() {
        return CORE_SOURCES.size();
    }

    /**
     * 让已编译的管线作废，好让原版按新源码重编。
     *
     * <p>核心着色器的 id 是原版固定的（{@code minecraft:core/entity}），
     * 没法像后处理那样靠换 id 绕开缓存，只能整体清掉。
     * 清完之后管线会惰性重编，而重编时会问 {@code ShaderSource} 要源码——那正是我们接管的地方
     * （见 {@code ShaderConfigsMixin}）。
     *
     * <p>26.3 没有 {@code GpuDevice.clearPipelineCache()} 了，编译产物归
     * {@link PipelineCache} 管；{@code clear()} 的效果与旧接口一致：关闭全部并清空。
     * 这一下会让原版几百条管线也跟着重编，有一次可见的卡顿——所以只有核心着色器走这条路，
     * 后处理那边用 {@link #releasePipelines} 精确剔除。
     */
    private static void invalidatePipelines() {
        VanillaSource.invalidate();
        PipelineCache cache = RenderSystemAccessor.gtshaders$currentPipelineCache();
        if (cache == null) {
            GTShaders.LOGGER.warn("管线缓存尚未就绪，核心着色器要等下次资源重载才生效");
            return;
        }
        try {
            cache.clear();
        } catch (RuntimeException e) {
            GTShaders.LOGGER.warn("清空管线缓存失败，核心着色器可能要等下次资源重载才生效", e);
        }
    }

    /** 该 post effect id 是否属于 GTShaders。 */
    public static boolean owns(@Nullable Identifier id) {
        return id != null && id.equals(effectId);
    }

    /**
     * 是否要接管原版的实体轮廓链。
     *
     * <p>只有真的有启用的轮廓层时才接管。没有就放行给原版——否则玩家给实体加个发光，
     * 看到的会是「什么轮廓都没有」，而他并没有要求过这件事。
     */
    public static boolean ownsOutline(@Nullable Identifier id) {
        return outlineConfig != null && OUTLINE_EFFECT_ID.equals(id);
    }

    /**
     * 返回我们的轮廓链。原版 {@code LevelRenderer} 每帧问一次，
     * 所以顺便在这里把参数与时间写进它的 uniform 缓冲。
     *
     * <p>注意它的 {@code allowedTargets} 是 {@code OUTLINE_TARGETS = {main, entity_outline}}，
     * 由调用方（原版）传进来，我们原样转交——声明了集合外的 target 会在这里抛，
     * 那正是我们希望尽早知道的。
     */
    public static @Nullable PostChain getOrBuildOutlineChain(Identifier id,
                                                             Set<Identifier> allowedTargets,
                                                             TextureManager textureManager,
                                                             Projection projection,
                                                             ProjectionMatrixBuffer projectionMatrixBuffer) {
        PostChainConfig cfg = outlineConfig;
        if (cfg == null) {
            return null;
        }
        if (outlineChain == null) {
            try {
                outlineChain = PostChain.load(cfg, textureManager, allowedTargets, id,
                        projection, projectionMatrixBuffer);
            } catch (ShaderManager.CompilationException e) {
                GTShaders.LOGGER.error("GTShaders 轮廓链构建失败", e);
                outlineConfig = null;
                return null;
            }
        }
        // 时间由主链那边推进；这里只写，不再 advance 一次，否则挂了轮廓层之后动画会走两倍速。
        // 但工程里只有轮廓层、没有主链时，就没人推进时钟了——那种情况下由这里来
        if (config == null) {
            advanceTime();
        }
        uploadUniformsFor(outlineChain, outlinePasses);

        return outlineChain;
    }

    /** 当前是否有生效的实体轮廓层。 */
    public static boolean hasOutline() {
        return outlineConfig != null && !outlinePasses.isEmpty();
    }

    public static int outlinePassCount() {
        return outlinePasses.size();
    }

    /**
     * 返回我们的 PostChain。原版每帧都会调一次，所以顺便在这里把参数与时间写进 uniform 缓冲——
     * 不需要额外的渲染钩子，而且此刻一定不在 RenderPass 内部，写缓冲是安全的。
     */
    public static @Nullable PostChain getOrBuildChain(Identifier id, Set<Identifier> allowedTargets,
                                                      TextureManager textureManager,
                                                      Projection projection,
                                                      ProjectionMatrixBuffer projectionMatrixBuffer) {
        PostChainConfig cfg = config;
        if (cfg == null) {
            return null;
        }
        if (chain == null) {
            // @texture 声明的贴图输入：先让 TextureManager 把图加载/注册好，再交给 PostChain。
            // 避免某些驱动/资源重载时机下 sampler 绑定到默认纹理，导致图标区域直接映射主画面。
            ImportedTextureManager.registerAll(textureManager);
            for (ShaderProject.PassBuild pass : passes) {
                for (ShaderTexture tex : pass.output().textures()) {
                    ImportedTextureManager.registerBuiltin(textureManager, tex.location());
                    textureManager.getTexture(Identifier.parse(tex.location()));
                }
            }
            try {
                chain = PostChain.load(cfg, textureManager, allowedTargets, id, projection, projectionMatrixBuffer);
            } catch (ShaderManager.CompilationException e) {
                // 走到这里说明预校验通过但原版链接失败（例如 uniform 块对不上）。
                // 直接停用预览，避免每帧重试刷屏。
                GTShaders.LOGGER.error("GTShaders 预览链构建失败", e);
                lastCompile = new GlslValidator.Result(false, List.of(new GlslValidator.Issue(
                        -1, GtLang.get("gtshaders.status.pipeline_link_failed", e.getMessage()), true)), "");
                disable();
                return null;
            }
        }
        advanceTime();
        // 锚点参数存的是绑定 id，槽位号要按<b>当前</b>的绑定顺序重算。每帧都解而不是只在
        // 编译时解一次：绑定可以在局内增删（绑定模式、B 键都会加），而那些操作不重编译，
        // 只解一次的话效果会指着一个已经漂走的槽位继续跑
        ShaderProject p = AnchorRuntime.project();
        if (p != null) {
            p.resolveAnchorRefs();
        }
        // 锚点每帧解算一次，结果写进每一个通道——所有层看到的是同一批锚点。
        // 放在 uploadUniforms 之前而不是里面：那里是按通道循环的，解一次就够的东西不该跟着通道数跑。
        AnchorRuntime.solve();
        // 轨迹同理，而且它比锚点更不能放进循环：重采样要遍历一百多个原始样本
        TrailRuntime.solve();
        uploadUniforms(chain);
        return chain;
    }

    // ---------------------------------------------------------------- 供 UI 调用

    /**
     * 编译并应用一个工程。编译失败时<b>不改变任何现有状态</b>——画面停在上一个能用的版本上，
     * 这样作者写到一半、语法还不完整的时候不会突然黑屏。
     *
     * @return 本次编译结果，UI 拿它渲染错误面板
     */
    public static GlslValidator.Result applyProject(ShaderProject project) {
        RenderSystem.assertOnRenderThread();

        // 在任何提前返回之前就把工程交给锚点运行时：即使这次没有任何启用的层（下面会走 clear()），
        // 锚点面板也要能继续解算并在画布上画出准星——那正是「还没写效果、先把锚点配好」的场景
        AnchorRuntime.setProject(project);

        // 编译要把槽位号烘进 post effect JSON 的默认值里，所以生成之前必须先解析引用
        project.resolveAnchorRefs();

        int nextGen = generation + 1;
        ShaderProject.Build build = project.generate(VanillaSource.runtimeProfile(), "post/preview" + nextGen);
        return applyBuild(project, build, nextGen);
    }

    /**
     * 应用调试视图用的链：启用层截到 {@code target} 为止，{@code target} 那层可带插桩。
     *
     * <p>和 {@link #applyProject} 走同一条应用路径（预校验、JSON、换代次、轮廓链），
     * 只是生成的链不同。所以调试视图下画面的挂载位置、资源包视角、锚点这些行为都与正常预览一致。
     *
     * @param inst null 表示不插桩，只截断
     */
    public static GlslValidator.Result applyDebug(ShaderProject project, ShaderLayer target,
                                                  mc.GTedd.cn.gtshaders.codegen.DebugInstrument.@Nullable Instrumented inst) {
        RenderSystem.assertOnRenderThread();
        AnchorRuntime.setProject(project);
        project.resolveAnchorRefs();
        int nextGen = generation + 1;
        ShaderProject.Build build = project.generateDebug(VanillaSource.runtimeProfile(),
                "post/debug" + nextGen, target, inst);
        return applyBuild(project, build, nextGen);
    }

    /**
     * 这个通道在我们主链上排第几（0 起）；不是我们主链上的通道返回 -1。
     * 分层计时靠它把原版的 {@code PostPass} 对回图层。
     */
    public static int passIndexOf(PostPass pass) {
        PostChain c = chain;
        if (c == null) {
            return -1;
        }
        List<PostPass> list = ((PostChainAccessor) c).gtshaders$passes();
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i) == pass) {
                return i;
            }
        }
        return -1;
    }

    /** 当前主链的通道信息（不含结尾的 blit），顺序与 {@link #passIndexOf} 一致。 */
    public static List<ShaderProject.PassBuild> passes() {
        return passes;
    }

    private static GlslValidator.Result applyBuild(ShaderProject project, ShaderProject.Build build, int nextGen) {
        // 没有任何启用的层 = 明确要求「什么都不做」。必须真的把效果从渲染器上摘掉，
        // 而不是跑一条空链——空链会白白多一次全屏 blit，也让状态栏显示得含混。
        if (build.passes().isEmpty()) {
            // 主链空了，但轮廓层可能还开着——它是独立的一条链，不该被一起清掉。
            // 「只挂一个实体轮廓效果、完全不动主画面」是很正常的用法
            clear();
            GlslValidator.Result outlineOnly = applyOutline(project, nextGen);
            if (outlineOnly != null) {
                lastCompile = outlineOnly;
            } else if (hasOutline()) {
                generation = nextGen;
            }
            return lastCompile;
        }

        // 逐个通道预校验。任意一个不过就整体放弃，保持画面停在上一个可用版本。
        Map<Identifier, String> freshSources = new HashMap<>();
        for (ShaderProject.PassBuild pass : build.passes()) {
            GlslCodegen.Output out = pass.output();
            GlslValidator.Result result = GlslValidator.validateFragment(out.source(), out.headerLineCount());
            if (!result.ok()) {
                lastCompile = result;
                return result;
            }
            try {
                PostEffectJsonBuilder.verifyLayout(out.orderedParams(), out.source());
            } catch (IllegalStateException e) {
                GlslValidator.Result layoutError = new GlslValidator.Result(false,
                        List.of(new GlslValidator.Issue(-1, e.getMessage(), true)), "");
                lastCompile = layoutError;
                return layoutError;
            }
            freshSources.put(Identifier.fromNamespaceAndPath(NAMESPACE, pass.shaderPath()), out.source());
        }

        // JSON 里的系统量用资源包那份常量，而不是编辑器此刻的时钟：预览链与导出物的 JSON
        // 因此只差着色器路径，每帧变化的东西全走 uniform 写入那条路
        JsonObject json = PostEffectJsonBuilder.buildChain(NAMESPACE, build, PostEffectJsonBuilder.PACK_SYSTEM);
        var parsed = PostChainConfig.CODEC.parse(JsonOps.INSTANCE, json);
        if (parsed.isError()) {
            String msg = parsed.error().map(Object::toString).orElse("unknown");
            GlslValidator.Result cfgError = new GlslValidator.Result(false, List.of(
                    new GlslValidator.Issue(-1, "post effect JSON: " + msg, true)), "");
            lastCompile = cfgError;
            return cfgError;
        }

        generation = nextGen;
        // 编译出自己的链就不再挂原版效果了：「直接预览某个原版效果」和「预览我正在调的效果」
        // 是两件互斥的事，同时挂上去只会让人分不清画面上看到的是哪一个
        if (vanillaEffectId != null) {
            disable();
            vanillaEffectId = null;
        }
        SHADER_SOURCES.clear();
        SHADER_SOURCES.putAll(freshSources);
        effectId = Identifier.fromNamespaceAndPath(NAMESPACE, "preview" + generation);
        config = parsed.result().orElseThrow();
        passes = build.passes();
        WARNED_LAYOUTS.clear();
        lastCompile = GlslValidator.Result.success();

        closeChain();
        activate();

        // 轮廓链单独编译：它写错了不该把整套后处理效果一起拖下水，反过来也一样。
        // 失败时把原因报上去，但主链已经生效的部分保持不动
        GlslValidator.Result outlineError = applyOutline(project, generation);
        if (outlineError != null) {
            lastCompile = outlineError;
        }
        return lastCompile;
    }

    /**
     * 回到「完全没有效果」的状态：停止向请求列表报到，丢掉内存源码与链。
     */
    public static void clear() {
        disable();
        effectId = null;
        vanillaEffectId = null;
        config = null;
        passes = List.of();
        SHADER_SOURCES.clear();
        closeChain();
        clearOutline();
        lastCompile = GlslValidator.Result.success();
    }

    /** 撤掉轮廓链接管，让原版那条描边效果回来。 */
    public static void clearOutline() {
        outlineConfig = null;
        outlinePasses = List.of();
        if (outlineChain != null) {
            releasePipelines(outlineChain);
            outlineChain.close();
            outlineChain = null;
        }
    }

    /** 当前是否真的有效果挂在渲染器上。 */
    public static boolean hasEffect() {
        return vanillaEffectId != null || (effectId != null && !passes.isEmpty());
    }

    /**
     * 直接挂一个原版效果，等价于玩家自己执行 {@code /posteffect add @s <id>}。
     *
     * <p>会先把我们自己那条链摘掉。26.3 的请求列表本来允许多个效果并存，
     * 技术上不必互斥；互斥是<b>界面语义</b>：点「直接预览」表达的是「我要看这一个」，
     * 而不是「在我正在调的效果上再叠一层」。
     */
    public static void applyVanilla(Identifier id) {
        clear();
        vanillaEffectId = id;
        activate();
    }

    /** 当前直接挂着的原版效果；没有则为 null。 */
    public static @Nullable Identifier vanillaEffect() {
        return vanillaEffectId;
    }

    /**
     * 把当前该挂的效果 id 追加进原版这一帧的后处理请求列表。
     *
     * <p>由 {@code GameRendererMixin} 在 {@code preparePostEffects} 的入口每帧调一次。
     * <b>这是 26.3 唯一的挂载方式</b>：26.2 时代是往 {@code GameRenderer.postEffectId}
     * 写一个字段、再靠 {@code keepAlive} 每帧补回（原版会在切视角时把它清掉）；
     * 26.3 的列表每帧从零重建，我们只要每帧报到即可，那套补救逻辑连同它修的 bug 一起消失了。
     *
     * <p>去重是必要的：玩家可能自己 {@code /posteffect add} 了同一个原版效果，
     * 同一个 id 在列表里出现两次就会被执行两遍。
     *
     * <p>轮廓链不在这里——它走的是 {@code LevelRenderer} 的 {@code minecraft:entity_outline}，
     * 那条路径拿得到逐实体遮罩，也不受这个列表管辖。
     */
    public static void appendActiveIds(List<Identifier> requested) {
        if (!active) {
            return;
        }
        Identifier vanilla = vanillaEffectId;
        if (vanilla != null && !requested.contains(vanilla)) {
            requested.add(vanilla);
        }
        Identifier own = effectId;
        if (own != null && !passes.isEmpty() && !requested.contains(own)) {
            if (mountAsEndOfFrame) {
                // 真实加载时我们的包会顶替掉别的包定义的 end_of_frame（同 id 后加载者胜），
                // 并排在所有 /posteffect 效果之前；预览照此办理
                requested.remove(VANILLA_END_OF_FRAME);
                requested.add(0, own);
            } else {
                requested.add(own);
            }
        }
    }

    /** 见 {@link #mountAsEndOfFrame}。切换只影响下一帧的挂载位置，不触发重编译。 */
    public static void setMountAsEndOfFrame(boolean value) {
        mountAsEndOfFrame = value;
    }

    public static boolean mountsAsEndOfFrame() {
        return mountAsEndOfFrame;
    }

    /** 见 {@link #packView}。切换只改每帧写入的 uniform 值，不触发重编译。 */
    public static void setPackView(boolean value) {
        packView = value;
    }

    public static boolean isPackView() {
        return packView;
    }

    /**
     * 当前挂着的链里，哪些量在纯资源包里没有来源（见 {@link PackParity}）。
     * 每次调用都重新扫一遍——通道数很少，而且只在导出面板打开时才有人问。
     */
    public static List<PackParity.Note> parityNotes() {
        return PackParity.audit(passes, outlinePasses);
    }

    /**
     * 开启预览。
     *
     * <p>只翻一个标志位——真正的挂载发生在 {@link #appendActiveIds}，每帧一次。
     */
    public static void activate() {
        active = true;
    }

    /** 关闭预览。下一帧起就不再往请求列表里报到，原版自然不会再执行我们的链。 */
    public static void disable() {
        active = false;
    }

    public static boolean isActive() {
        return active;
    }

    /** 同 {@link #lastCompile}，但从没编译过时给「成功」而不是 null。 */
    public static GlslValidator.Result lastCompileOrSuccess() {
        return lastCompile == null ? GlslValidator.Result.success() : lastCompile;
    }

    /** 最近一次编译结果，可能为 null（还没编译过）。 */
    public static GlslValidator.Result lastCompile() {
        return lastCompile;
    }

    /**
     * 当前编译产物里有没有哪个通道真的读了世界锚点。
     *
     * <p>局内的锚点浮层要靠它回答一个最容易被忽略的问题：绑定配得再对，工程里没有一层
     * 用到 {@code gtAnchor}，画面上就永远不会有任何变化——而这一点从锚点面板上完全看不出来。
     */
    public static boolean usesAnchors() {
        for (ShaderProject.PassBuild b : passes) {
            if (b.output().usesAnchors()) {
                return true;
            }
        }
        for (ShaderProject.PassBuild b : outlinePasses) {
            if (b.output().usesAnchors()) {
                return true;
            }
        }
        return false;
    }

    /**
     * 当前编译产物里有没有哪个通道真的读了武器轨迹。
     *
     * <p>{@code TrailRuntime} 拿它当总开关：没人读就一帧都不采。采集挂在
     * {@code ItemStackRenderState.submit} 上，那是每个物品每帧都会走到的热路径，
     * 不设这道门的话，装了 mod 却没在用刀光的人也要白付一份矩阵变换。
     */
    /** 当前编译产物里有没有哪个通道读了载体扩展。局内浮层拿它决定要不要报朝向。 */
    public static boolean usesEmitters() {
        for (ShaderProject.PassBuild b : passes) {
            if (b.output().usesEmitters()) {
                return true;
            }
        }
        for (ShaderProject.PassBuild b : outlinePasses) {
            if (b.output().usesEmitters()) {
                return true;
            }
        }
        return false;
    }

    public static boolean usesTrails() {
        for (ShaderProject.PassBuild b : passes) {
            if (b.output().usesTrails()) {
                return true;
            }
        }
        for (ShaderProject.PassBuild b : outlinePasses) {
            if (b.output().usesTrails()) {
                return true;
            }
        }
        return false;
    }

    /** 当前生效的渲染通道数（不含结尾的 blit）。 */
    public static int passCount() {
        return passes.size();
    }

    /** 资源包视角下时钟归原版管，永远在走。 */
    public static boolean isPlaying() {
        return packView || playing;
    }

    public static void setPlaying(boolean value) {
        playing = value;
    }

    /** @return false 表示资源包视角下没有暂停这回事，调用方可以据此提示 */
    public static boolean togglePlaying() {
        if (packView) {
            return false;
        }
        playing = !playing;
        return true;
    }

    /** 着色器此刻读到的 {@code GTTime}：编辑器视角是编辑器时钟，资源包视角是原版时钟折成的秒。 */
    public static float time() {
        return packView ? lastGameSeconds : time;
    }

    /**
     * 编辑器自己的时钟，<b>不随资源包视角切换</b>。锚点、轨迹这类 mod 侧的生命周期计时用它：
     * 它们记下的触发时刻要和后来比较的「现在」出自同一个钟，否则切一次视角，所有存活期全乱。
     */
    public static float editorTime() {
        return time;
    }



    public static void setTime(float seconds) {
        time = Math.max(0f, seconds);
    }

    public static void resetTime() {
        time = 0f;
        frameCounter = 0;
    }

    // ---------------------------------------------------------------- 内部

    private static void advanceTime() {
        lastGameSeconds = gameSecondsNow();
        long now = System.nanoTime();
        if (lastNanos == 0L) {
            lastNanos = now;
        }
        float dt = (now - lastNanos) / 1_000_000_000f;
        lastNanos = now;
        // 断点、加载卡顿之后 dt 可能是好几秒，直接累加会让动画瞬移；钳一下更符合直觉。
        deltaTime = Math.min(dt, 0.1f);
        if (playing) {
            time += deltaTime;
            frameCounter++;
        }
    }

    /**
     * 这一帧写进 {@code GTSystem} 的四个数。
     *
     * <p>着色器里 {@code GTTime = GameTime * 1200 + GTSystem.x}。资源包里 x 恒为 0，时间就是
     * 原版游戏时钟；编辑器把 x 写成「编辑器时钟 − 游戏时钟」，作者看到的仍是可暂停、可回拨的
     * 编辑器时钟，而着色器源码与资源包里那份逐字节相同。
     *
     * <p>资源包视角下直接给 {@link PostEffectJsonBuilder#PACK_SYSTEM}：那正是纯资源包加载时
     * 这个块的全部内容，时间随之切到原版时钟（每 1200 秒回绕、游戏暂停即静止）。
     */
    private static float[] systemVector() {
        if (packView) {
            return PostEffectJsonBuilder.PACK_SYSTEM;
        }
        // 接管世界时钟时 Globals.GameTime 已经等于编辑器时钟，偏移必须归零，否则时间叠两遍
        float offset = WorldClock.isEngaged() ? 0f : time - lastGameSeconds;
        return new float[]{offset, deltaTime, frameCounter, playing ? 1f : 0f};
    }


    /**
     * 按原版 {@code GlobalSettingsUniform.update} 的公式算出这一帧 {@code Globals.GameTime} 对应的秒数：
     * {@code ((gameTime % 24000) + partialTicks) / 24000 * 1200}。
     *
     * <p>必须与着色器里读到的是同一帧的同一个值：{@code render()} 先更新 Globals 块，再进
     * {@code preparePostEffects}（我们在那里被调用），中间没有别的 tick，所以直接从
     * {@code gameRenderState} 读就是它刚写进 UBO 的那份。
     * 不渲染世界时原版传 0，这里同样返回 0。
     */
    private static float gameSecondsNow() {
        var state = Minecraft.getInstance().gameRenderer.gameRenderState();
        if (state == null || !state.shouldRenderLevel) {
            return 0f;
        }
        var level = state.levelRenderState;
        float dayFraction = ((float) (level.gameTime % 24000L) + level.worldPartialTicks) / 24000f;
        return dayFraction * GAME_DAY_SECONDS;
    }

    /**
     * 把系统参数、图层参数与用户参数写进每个通道的 uniform 缓冲。
     *
     * <p>字节布局必须和原版 {@code PostPass} 构造时用 {@code Std140Builder} 写出来的完全一致，
     * 所以这里用同一套 {@code Std140Builder} / {@code Std140SizeCalculator} 并按同一顺序写入——
     * 手算 std140 偏移是这类代码最经典的出错点，能不算就不算。
     */
    private static void uploadUniforms(PostChain target) {
        uploadUniformsFor(target, passes);
    }

    /**
     * @param target     要写的链
     * @param passList   该链对应的通道信息。主链与轮廓链各有各的一份，
     *                   传错会把参数写到别人的缓冲里——而那不会报错，只会让画面变得莫名其妙
     */
    private static void uploadUniformsFor(@Nullable PostChain target,
                                          List<ShaderProject.PassBuild> passList) {
        if (target == null || passList.isEmpty()) {
            return;
        }
        List<PostPass> chainPasses = ((PostChainAccessor) target).gtshaders$passes();
        float[] sys = systemVector();
        // 惰性：一帧最多解一次，而且没有任何通道要相机时一次都不解。
        // 解算本身只是两次矩阵乘法，但它每帧、每通道都会被问到，白算没有道理
        float[] camera = null;

        for (int i = 0; i < passList.size() && i < chainPasses.size(); i++) {
            ShaderProject.PassBuild build = passList.get(i);
            // 取景框每帧从<b>层</b>上现取：它是层的属性（每层各管画面的一块），
            // 而且拖框属于「只改 uniform」的路径，不该触发重编译。
            // 用 build.viewportUv() 会拿到编译那一刻的快照——拖了框要等下次编译才动
            float[] uv = build.layer().viewport().toGlUv();
            GpuBuffer buffer = ((PostPassAccessor) chainPasses.get(i))
                    .gtshaders$customUniforms().get(GlslCodegen.PARAM_BLOCK);
            if (buffer == null || buffer.isClosed()) {
                continue;
            }
            List<ShaderParam> params = build.output().orderedParams();
            boolean anchored = build.output().usesAnchors();
            boolean trailed = build.output().usesTrails();
            boolean fxEmitters = build.output().usesEmitters();
            boolean worldCamera = build.output().usesCamera();

            Std140SizeCalculator sizer = new Std140SizeCalculator();
            sizer.putVec4();
            sizer.putVec4();
            sizer.putVec4();
            if (anchored) {
                // 1 个表头 + 每槽 2 个 vec4。std140 下 vec4 数组的跨距就是 16 字节，
                // 所以逐个 putVec4 和声明成数组算出来的尺寸完全一样
                for (int a = 0; a < 1 + AnchorSlot.SLOTS * AnchorSlot.VEC4_PER_SLOT; a++) {
                    sizer.putVec4();
                }
            }
            if (fxEmitters) {
                for (int e = 0; e < AnchorSlot.SLOTS * EmitterSlot.VEC4_PER_SLOT; e++) {
                    sizer.putVec4();
                }
            }
            if (trailed) {
                for (int t = 0; t < 1 + TrailSlot.SLOTS * TrailSlot.VEC4_PER_SLOT; t++) {
                    sizer.putVec4();
                }
            }
            if (worldCamera) {
                for (int c = 0; c < CameraRuntime.VEC4; c++) {
                    sizer.putVec4();
                }
            }
            for (ShaderParam p : params) {
                addSize(sizer, p.type());
            }
            int size = sizer.get();
            if (size > buffer.size()) {
                // 这里<b>绝不能静默跳过</b>：跳过意味着这个通道的 uniform 永远停在
                // JSON 里那份初始值上——画面看着是有效果的，但面板上怎么拖都不动，
                // 而且没有任何报错。上一次排查这个症状花了很久，就因为它一声不吭。
                warnUniformLayout(build, size, buffer.size());
                continue;
            }

            try (MemoryStack stack = MemoryStack.stackPush()) {
                ByteBuffer bytes = stack.malloc(size);
                Std140Builder builder = Std140Builder.intoBuffer(bytes);
                builder.putVec4(sys[0], sys[1], sys[2], sys[3]);
                builder.putVec4(build.layer().strength(), build.enabledIndex(),
                        build.enabledCount(), 0f);
                builder.putVec4(uv[0], uv[1], uv[2], uv[3]);
                // 资源包视角：这三样在纯资源包里没有来源，JSON 里全是 0，这里也写 0——
                // 作者看到的就是玩家会看到的退化形态，而不是被 mod 喂活的版本
                if (anchored) {
                    if (packView) {
                        zeroVec4(builder, 1 + AnchorSlot.SLOTS * AnchorSlot.VEC4_PER_SLOT);
                    } else {
                        writeAnchors(builder);
                    }
                }
                if (fxEmitters) {
                    if (packView) {
                        zeroVec4(builder, AnchorSlot.SLOTS * EmitterSlot.VEC4_PER_SLOT);
                    } else {
                        writeEmitters(builder);
                    }
                }
                if (trailed) {
                    if (packView) {
                        zeroVec4(builder, 1 + TrailSlot.SLOTS * TrailSlot.VEC4_PER_SLOT);
                    } else {
                        writeTrails(builder);
                    }
                }
                if (worldCamera) {
                    if (packView) {
                        // 资源包视角同样归零：作者看到的就是玩家装上包会看到的退化形态
                        zeroVec4(builder, CameraRuntime.VEC4);
                    } else {
                        if (camera == null) {
                            camera = CameraRuntime.solve();
                        }
                        for (int c = 0; c < CameraRuntime.VEC4; c++) {
                            builder.putVec4(camera[c * 4], camera[c * 4 + 1],
                                    camera[c * 4 + 2], camera[c * 4 + 3]);
                        }
                    }
                }
                for (ShaderParam p : params) {
                    // 按名字取图层当前持有的那份，而不是直接用编译时存下的引用。
                    // 正常情况下两者是同一个对象（rescan 会复用），这一步是兜底：
                    // 一旦哪天又出现「面板改了画面不动」，问题也不会静悄悄地藏在这里。
                    write(builder, live(build.layer(), p));
                }
                bytes.position(0);
                bytes.limit(size);
                RenderSystem.getDevice().createCommandEncoder()
                        .writeToBuffer(buffer.slice(0L, size), bytes);
            }
        }
    }

    private static void zeroVec4(Std140Builder builder, int count) {
        for (int i = 0; i < count; i++) {
            builder.putVec4(0f, 0f, 0f, 0f);
        }
    }

    /**
     * 把这一帧解算好的锚点写进 uniform 块。
     *
     * <p>顺序必须和 {@code GlslCodegen} 的声明、{@code PostEffectJsonBuilder} 的 JSON 条目
     * 三方一致：表头、整个 A 数组、整个 B 数组。<b>A 和 B 是两个独立数组而不是交错排列</b>，
     * 所以要分两轮写完，不能在一个循环里 A、B 交替 put。
     */
    private static void writeAnchors(Std140Builder builder) {
        AnchorSlot[] anchors = AnchorRuntime.slots();
        int active = 0;
        for (AnchorSlot s : anchors) {
            if (!s.isEmpty()) {
                active++;
            }
        }
        builder.putVec4(active, AnchorSlot.SLOTS, 0f, 0f);
        for (int i = 0; i < AnchorSlot.SLOTS; i++) {
            AnchorSlot s = i < anchors.length ? anchors[i] : AnchorSlot.EMPTY;
            builder.putVec4(s.u(), s.v(), s.depth(), s.strength());
        }
        for (int i = 0; i < AnchorSlot.SLOTS; i++) {
            AnchorSlot s = i < anchors.length ? anchors[i] : AnchorSlot.EMPTY;
            builder.putVec4(s.radius(), s.distance(), s.life(), s.visibility());
        }
        // C 组：视图空间方向 + 状态码。顺序同样是「整组排完再排下一组」
        for (int i = 0; i < AnchorSlot.SLOTS; i++) {
            AnchorSlot s = i < anchors.length ? anchors[i] : AnchorSlot.EMPTY;
            builder.putVec4(s.dirX(), s.dirY(), s.dirZ(), s.state());
        }
    }


    /**
     * 把这一帧解算好的载体扩展写进 uniform 块。
     *
     * <p>和 {@link #writeAnchors} 共用同一批槽位，所以<b>必须紧跟在它后面写</b>——
     * 顺序就是字节偏移。同样是 A 数组整段排完再排 B 数组。
     */
    private static void writeEmitters(Std140Builder builder) {
        EmitterSlot[] fx = AnchorRuntime.emitters();
        for (int i = 0; i < AnchorSlot.SLOTS; i++) {
            EmitterSlot e = i < fx.length ? fx[i] : EmitterSlot.EMPTY;
            builder.putVec4(e.dirU(), e.dirV(), e.facing(), e.roll());
        }
        for (int i = 0; i < AnchorSlot.SLOTS; i++) {
            EmitterSlot e = i < fx.length ? fx[i] : EmitterSlot.EMPTY;
            builder.putVec4(e.type(), e.custom1(), e.custom2(), e.spin());
        }
    }

    /**
     * 把这一帧解算好的轨迹写进 uniform 块。
     *
     * <p>和 {@link #writeAnchors} 一样，顺序必须与 {@code GlslCodegen} 的声明、
     * {@code PostEffectJsonBuilder} 的 JSON 条目三方一致：表头、整个 A 数组、整个 B 数组。
     * A 和 B 是两个独立数组而不是交错排列，所以要分两轮写完。
     */
    private static void writeTrails(Std140Builder builder) {
        TrailSlot[] trail = TrailRuntime.slots();
        int active = 0;
        for (TrailSlot s : trail) {
            if (!s.isEmpty()) {
                active++;
            }
        }
        builder.putVec4(active, TrailSlot.SLOTS, TrailRuntime.swing(), 0f);
        for (int i = 0; i < TrailSlot.SLOTS; i++) {
            TrailSlot s = i < trail.length ? trail[i] : TrailSlot.EMPTY;
            builder.putVec4(s.rootU(), s.rootV(), s.tipU(), s.tipV());
        }
        for (int i = 0; i < TrailSlot.SLOTS; i++) {
            TrailSlot s = i < trail.length ? trail[i] : TrailSlot.EMPTY;
            builder.putVec4(s.age(), s.depth(), s.valid(), s.speed());
        }
    }

    /** 已经报过布局不匹配的通道，避免每帧刷屏。 */
    private static final java.util.Set<String> WARNED_LAYOUTS = new java.util.HashSet<>();

    /**
     * 报告 uniform 块字节布局对不上。
     *
     * <p>同一个通道只报一次：这是每帧都会走到的路径，不去重的话日志一秒钟几百行。
     * 状态里也留一份，好让编辑器能把「参数拖了没反应」直接说出来，而不是让人自己猜。
     */
    private static void warnUniformLayout(ShaderProject.PassBuild build, int need, long have) {
        String key = build.shaderPath() + ":" + need + "/" + have;
        if (!WARNED_LAYOUTS.add(key)) {
            return;
        }
        GTShaders.LOGGER.error(
                "uniform 块布局不匹配：通道 {} 需要 {} 字节，原版只分配了 {} 字节。"
                        + "这一层的参数将停在初始值上，拖动面板不会有反应。",
                build.shaderPath(), need, have);
        lastCompile = new GlslValidator.Result(false, List.of(new GlslValidator.Issue(
                -1, GtLang.get("gtshaders.status.uniform_layout_mismatch",
                build.layer().name()), true)), "");
    }

    /**
     * 取图层当前持有的同名参数。
     *
     * <p>只认<b>同名且同类型</b>：类型对不上就说明作者已经把这个参数改成别的东西了，
     * 而 uniform 块的字节布局还是按编译那会儿铺的——按新类型写进去会让后面所有参数错位，
     * 那比「这一个参数没跟上」严重得多。这种情况下用编译时那份，等重新编译收拾。
     */
    private static ShaderParam live(ShaderLayer layer, ShaderParam compiled) {
        for (ShaderParam p : layer.params()) {
            if (p.name().equals(compiled.name()) && p.type() == compiled.type()) {
                return p;
            }
        }
        return compiled;
    }

    private static void addSize(Std140SizeCalculator sizer, ParamType type) {
        switch (type) {
            case FLOAT, BOOL -> sizer.putFloat();
            case INT -> sizer.putInt();
            case VEC2 -> sizer.putVec2();
            case VEC3, COLOR3 -> sizer.putVec3();
            case VEC4, COLOR4 -> sizer.putVec4();
        }
    }

    private static void write(Std140Builder builder, ShaderParam p) {
        float[] v = p.value();
        switch (p.type()) {
            case FLOAT, BOOL -> builder.putFloat(v[0]);
            case INT -> builder.putInt(Math.round(v[0]));
            case VEC2 -> builder.putVec2(v[0], v[1]);
            case VEC3, COLOR3 -> builder.putVec3(v[0], v[1], v[2]);
            case VEC4, COLOR4 -> builder.putVec4(v[0], v[1], v[2], v[3]);
        }
    }

    private static void closeChain() {
        if (chain != null) {
            releasePipelines(chain);
            chain.close();
            chain = null;
        }
    }

    /**
     * 把一条链编译出来的管线从全局管线缓存里摘掉并释放。
     *
     * <p>这是 26.3 才做得到的事，也是必须做的事。每改一次源码我们都要换一批新的着色器 id
     * （原版按 id 缓存，沿用旧 id 会拿到旧的编译结果），于是每次重编都会在
     * {@link PipelineCache} 里留下一条再也不会被用到的记录。不清就是纯泄漏。
     *
     * <p>26.2 时代只有「整体清空」一个接口，所以当时只能攒够 60 次编译再清一次——
     * 泄漏有上界，但每清一次原版管线都要跟着重编，会卡一下。现在按对象身份精确删，
     * 原版一条不动，既不泄漏也不卡。
     *
     * <p>时机很重要：必须在 {@code chain.close()} <b>之前</b>取 pass 列表（关了就取不到了），
     * 而这整个方法只在「换代次」时调用，此刻这条链已经不会再出现在原版的
     * {@code appliedPostEffects} 里，不存在在途帧仍在用它的情况。
     */
    private static void releasePipelines(PostChain target) {
        PipelineCache cache = RenderSystemAccessor.gtshaders$currentPipelineCache();
        if (cache == null) {
            return;
        }
        try {
            Map<RenderPipeline, CompiledRenderPipeline> table =
                    ((PipelineCacheAccessor) (Object) cache).gtshaders$cache();
            for (PostPass pass : ((PostChainAccessor) target).gtshaders$passes()) {
                CompiledRenderPipeline compiled =
                        table.remove(((PostPassAccessor) pass).gtshaders$pipeline());
                if (compiled != null) {
                    compiled.close();
                }
            }
        } catch (RuntimeException e) {
            // 回收失败不该影响功能，只是会漏一点显存
            GTShaders.LOGGER.warn("回收后处理管线失败", e);
        }
    }

    /**
     * 编译并应用工程里的实体轮廓层。
     *
     * <p>和主链分开走，是因为两者的失败必须互不牵连：轮廓层写错了不该让整套后处理效果消失，
     * 反过来也一样。编译失败时保留上一版能用的轮廓链。
     *
     * @return 失败原因；成功返回 null
     */
    private static GlslValidator.@Nullable Result applyOutline(ShaderProject project, int gen) {
        ShaderProject.Build build = project.generateOutline(
                VanillaSource.runtimeProfile(), "post/outline" + gen);
        if (build.passes().isEmpty()) {
            clearOutline();
            return null;
        }

        Map<Identifier, String> fresh = new HashMap<>();
        for (ShaderProject.PassBuild pass : build.passes()) {
            GlslCodegen.Output out = pass.output();
            GlslValidator.Result result = GlslValidator.validateFragment(out.source(), out.headerLineCount());
            if (!result.ok()) {
                return result;
            }
            try {
                PostEffectJsonBuilder.verifyLayout(out.orderedParams(), out.source());
            } catch (IllegalStateException e) {
                return new GlslValidator.Result(false,
                        List.of(new GlslValidator.Issue(-1, e.getMessage(), true)), "");
            }
            fresh.put(Identifier.fromNamespaceAndPath(NAMESPACE, pass.shaderPath()), out.source());
        }

        JsonObject json = PostEffectJsonBuilder.buildOutlineChain(NAMESPACE, build,
                PostEffectJsonBuilder.PACK_SYSTEM);
        var parsed = PostChainConfig.CODEC.parse(JsonOps.INSTANCE, json);
        if (parsed.isError()) {
            String msg = parsed.error().map(Object::toString).orElse("unknown");
            return new GlslValidator.Result(false, List.of(
                    new GlslValidator.Issue(-1, "entity_outline JSON: " + msg, true)), "");
        }

        SHADER_SOURCES.putAll(fresh);
        outlineConfig = parsed.result().orElseThrow();
        outlinePasses = build.passes();
        // 换了源码就必须丢掉旧链：它持有的是上一份编译结果
        if (outlineChain != null) {
            releasePipelines(outlineChain);
            outlineChain.close();
            outlineChain = null;
        }
        return null;
    }

    /** 资源重载会清空原版缓存，我们的链也必须一起作废重建。 */
    public static void onResourceReload() {
        closeChain();
        if (outlineChain != null) {
            releasePipelines(outlineChain);
            outlineChain.close();
            outlineChain = null;
        }
    }
}
