package mc.GTedd.cn.gtshaders.runtime;

import mc.GTedd.cn.gtshaders.codegen.DebugInstrument;
import mc.GTedd.cn.gtshaders.core.ShaderLayer;
import mc.GTedd.cn.gtshaders.core.ShaderParam;
import mc.GTedd.cn.gtshaders.core.ShaderProject;
import mc.GTedd.cn.gtshaders.i18n.GtLang;
import org.jspecify.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * 编辑器的调试视图：变量探针、异常检查，以及点画面取样。
 *
 * <h2>三种状态</h2>
 *
 * <ul>
 *   <li>{@link Mode#NONE}：正常预览。此时点画面取样读的是<b>每一层的输出</b>——
 *       逐层把链截到那一层、等一帧、回读一个像素，最后恢复完整的链。取样期间画面会闪几帧。</li>
 *   <li>{@link Mode#VALUE}：选中层带探针，画面显示那个值的伪彩色。点画面读出这个像素上的精确值——
 *       一帧只能带出一个分量，所以连读几帧（元信息 + 每个分量各一帧）。</li>
 *   <li>{@link Mode#UB}：选中层带异常检查，画面显示异常图。点画面读出这个像素第一次出事的类型与行号。</li>
 * </ul>
 *
 * <p>取样是一串「改参数 → 隔一帧回读 → 下一步」的步骤，由编辑器每帧调一次 {@link #pump} 推进，
 * 回读回调只负责记下结果。步骤之间要隔帧的原因见 {@link PixelReadback}。
 *
 * <p><b>这一整套都没有在真机上验证过</b>：回读的坐标方向、8 位缓冲按位写出是否无损、
 * 以及截断链在一帧之内是否一定生效，都只做到了字节码与编译层面的核对。
 * 读数自带一个检查——伪彩色画面上看到的分布应当与点出来的数对得上。
 */
public final class DebugSession {

    public enum Mode {
        NONE, VALUE, UB
    }

    /** 某一层输出在取样点上的颜色（0..255）。{@code layerName} 为 null 表示「加效果之前的画面」。 */
    public record LayerSample(@Nullable String layerName, int r, int g, int b, int a) {
    }

    /**
     * 探针读数。
     *
     * @param hit        这个像素有没有执行到探针那一行
     * @param components 值有几个分量
     * @param type       0 float · 1 int · 2 uint · 3 bool
     */
    public record ValueSample(boolean hit, int components, int type, float[] values) {
    }

    /**
     * 一次取样的结果。{@code done} 为 false 表示还在读；{@code error} 非空表示中途失败。
     *
     * @param exact 主缓冲确实是 8 位 RGBA；否则按位编码的读数不可信
     */
    public record Inspection(double u, double v, List<LayerSample> layers, @Nullable ValueSample value,
                             DebugInstrument.@Nullable UbSample ub, boolean exact, boolean done,
                             @Nullable String error) {
    }

    /** 一次回读等多久算失败：不在世界里、链没挂上时回读永远不会来。 */
    private static final long READBACK_TIMEOUT_MS = 3000;

    private static Mode mode = Mode.NONE;
    private static @Nullable ShaderLayer target;
    private static DebugInstrument.@Nullable Instrumented inst;
    private static int probeLine;
    private static String probeExpr = "";
    private static float rangeLo;
    private static float rangeHi = 1f;

    private interface Step {
        void run(ShaderProject project);
    }

    private static final Deque<Step> STEPS = new ArrayDeque<>();
    private static boolean busy;
    private static boolean inFlight;
    private static long inFlightSince;
    /** 取样结束时要不要重建完整的链（逐层取样把链截断过）。 */
    private static boolean restoreAfter;
    /** 每次中止或重新开始都换一个号，迟到的回调据此认出自己已经过期。 */
    private static int generation;

    private static double sampleU;
    private static double sampleV;
    private static final List<LayerSample> layerSamples = new ArrayList<>();
    private static int metaComponents;
    private static int metaType;
    private static boolean metaHit;
    private static float[] values = new float[4];
    private static DebugInstrument.@Nullable UbSample ubSample;
    private static boolean exact = true;
    private static @Nullable String error;
    private static @Nullable Inspection last;

    private DebugSession() {
    }

    public static boolean isActive() {
        return mode != Mode.NONE;
    }

    public static Mode mode() {
        return mode;
    }

    public static @Nullable ShaderLayer target() {
        return target;
    }

    public static int probeLine() {
        return probeLine;
    }

    public static String probeExpression() {
        return probeExpr;
    }

    public static boolean isBusy() {
        return busy;
    }

    public static @Nullable Inspection lastInspection() {
        return last;
    }

    public static float rangeLo() {
        return rangeLo;
    }

    public static float rangeHi() {
        return rangeHi;
    }

    /** 伪彩色的取值范围。只改 uniform，不重编译。 */
    public static void setRange(float lo, float hi) {
        rangeLo = lo;
        rangeHi = hi;
        applyRange();
    }

    private static void applyRange() {
        DebugInstrument.Instrumented i = inst;
        ShaderParam range = i == null ? null : i.range();
        if (range != null) {
            range.set(0, rangeLo);
            range.set(1, rangeHi);
        }
    }

    // ------------------------------------------------------------------ 开关

    /** 在第 {@code line} 行给 {@code layer} 插一个看 {@code expression} 的探针。 */
    public static GlslValidator.Result startValue(ShaderProject project, ShaderLayer layer, int line, String expression) {
        GlslValidator.Result bad = checkLayer(project, layer);
        if (bad != null) {
            return bad;
        }
        DebugInstrument.Outcome o = DebugInstrument.probe(layer.strippedBody(), line, expression);
        if (!o.ok()) {
            return failure(o, line);
        }
        cancelInspection();
        mode = Mode.VALUE;
        target = layer;
        probeLine = line;
        probeExpr = expression.strip();
        inst = o.value();
        applyRange();
        return PreviewRuntime.applyDebug(project, layer, inst);
    }

    /** 给 {@code layer} 开异常检查。 */
    public static GlslValidator.Result startUb(ShaderProject project, ShaderLayer layer) {
        GlslValidator.Result bad = checkLayer(project, layer);
        if (bad != null) {
            return bad;
        }
        cancelInspection();
        mode = Mode.UB;
        target = layer;
        inst = DebugInstrument.ub(layer.strippedBody()).value();
        return PreviewRuntime.applyDebug(project, layer, inst);
    }

    /** 回到正常预览。 */
    public static GlslValidator.Result stop(ShaderProject project) {
        cancelInspection();
        // 逐层取样做到一半时链是截断的，同样要恢复
        boolean needRestore = mode != Mode.NONE || restoreAfter;
        restoreAfter = false;
        mode = Mode.NONE;
        target = null;
        inst = null;
        return needRestore ? PreviewRuntime.applyProject(project) : PreviewRuntime.lastCompileOrSuccess();
    }

    /**
     * 源码改了之后重建调试视图：按原来的行号与表达式重新插桩。
     * 目标层被删掉或停用时退回正常预览。
     */
    public static GlslValidator.Result recompile(ShaderProject project) {
        ShaderLayer layer = target;
        if (mode == Mode.NONE || layer == null || !project.enabledPostLayers().contains(layer)) {
            mode = Mode.NONE;
            target = null;
            inst = null;
            cancelInspection();
            return PreviewRuntime.applyProject(project);
        }
        cancelInspection();
        if (mode == Mode.UB) {
            inst = DebugInstrument.ub(layer.strippedBody()).value();
        } else {
            DebugInstrument.Outcome o = DebugInstrument.probe(layer.strippedBody(), probeLine, probeExpr);
            if (!o.ok()) {
                // 行号因为编辑挪了位置之类：保持上一版调试视图，把原因报上去
                return failure(o, probeLine);
            }
            inst = o.value();
            applyRange();
        }
        return PreviewRuntime.applyDebug(project, layer, inst);
    }

    private static GlslValidator.@Nullable Result checkLayer(ShaderProject project, ShaderLayer layer) {
        if (layer == null || !layer.isPost() || !project.enabledPostLayers().contains(layer)) {
            return new GlslValidator.Result(false, List.of(new GlslValidator.Issue(-1,
                    GtLang.get("gtshaders.debug.err.layer"), true)), "");
        }
        return null;
    }

    private static GlslValidator.Result failure(DebugInstrument.Outcome o, int line) {
        String msg = GtLang.get(o.errorKey(), o.args());
        return new GlslValidator.Result(false, List.of(new GlslValidator.Issue(
                "gtshaders.debug.err.line_range".equals(o.errorKey()) ? -1 : line, msg, true)), "");
    }

    // ------------------------------------------------------------------ 取样

    /**
     * 在屏幕 (u, v) 处取样，u 向右、v 向下，0..1。取样进行中再点会被忽略。
     *
     * @return 没开始时返回原因（语言文本）；开始了返回 null
     */
    public static @Nullable String inspect(ShaderProject project, double u, double v) {
        if (busy) {
            return GtLang.get("gtshaders.debug.busy");
        }
        generation++;
        STEPS.clear();
        layerSamples.clear();
        values = new float[4];
        metaHit = false;
        metaComponents = 0;
        metaType = 0;
        ubSample = null;
        exact = true;
        error = null;
        sampleU = u;
        sampleV = v;
        // 读的是「这一刻」：时间还在走的话，逐帧读出来的几个分量会来自不同时刻
        if (PreviewRuntime.isPlaying() && !PreviewRuntime.isPackView()) {
            PreviewRuntime.setPlaying(false);
        }

        switch (mode) {
            case VALUE -> planValue();
            case UB -> planUb();
            case NONE -> {
                List<ShaderLayer> layers = project.enabledPostLayers();
                if (layers.isEmpty()) {
                    return GtLang.get("gtshaders.debug.err.no_layers");
                }
                planLayers(layers);
            }
        }
        busy = true;
        inFlight = false;
        publish(false);
        return null;
    }

    private static void planLayers(List<ShaderLayer> layers) {
        restoreAfter = true;
        // 先读加效果之前的画面：把我们的链整个摘掉
        STEPS.add(project -> {
            PreviewRuntime.clear();
            read((r, g, b, a, ok) -> layerSamples.add(new LayerSample(null, r, g, b, a)));
        });
        for (ShaderLayer layer : layers) {
            STEPS.add(project -> {
                GlslValidator.Result result = PreviewRuntime.applyDebug(project, layer, null);
                if (!result.ok()) {
                    abort(result.firstErrorMessage());
                    return;
                }
                read((r, g, b, a, ok) -> layerSamples.add(new LayerSample(layer.name(), r, g, b, a)));
            });
        }
    }

    private static void planValue() {
        restoreAfter = false;
        STEPS.add(project -> {
            select(DebugInstrument.SEL_META);
            read((r, g, b, a, ok) -> {
                exact &= ok;
                DebugInstrument.ProbeMeta meta = DebugInstrument.ProbeMeta.decode(r, g, b);
                metaHit = meta.hit();
                metaComponents = meta.components();
                metaType = meta.type();
                // 读到分量数之后才知道还要读几帧：没执行到这一行就不必再读
                if (meta.hit()) {
                    for (int c = meta.components() - 1; c >= 0; c--) {
                        int comp = c;
                        STEPS.addFirst(p -> {
                            select(DebugInstrument.SEL_COMPONENT + comp);
                            read((r2, g2, b2, a2, ok2) -> {
                                exact &= ok2;
                                values[comp] = DebugInstrument.decodeFloat(r2, g2, b2, a2);
                            });
                        });
                    }
                }
            });
        });
    }

    private static void planUb() {
        restoreAfter = false;
        STEPS.add(project -> {
            select(DebugInstrument.SEL_UB_EXACT);
            read((r, g, b, a, ok) -> {
                exact &= ok;
                ubSample = DebugInstrument.UbSample.decode(r, g, b, a);
            });
        });
    }

    private static void select(int sel) {
        DebugInstrument.Instrumented i = inst;
        if (i != null) {
            i.sel().set(0, sel);
        }
    }

    private static void read(PixelReadback.Callback onResult) {
        int gen = generation;
        inFlight = true;
        inFlightSince = System.currentTimeMillis();
        PixelReadback.request(sampleU, sampleV, (r, g, b, a, ok) -> {
            if (gen != generation) {
                return;
            }
            onResult.accept(r, g, b, a, ok);
            inFlight = false;
            publish(false);
        });
    }

    /** 由编辑器每帧调用：上一步的回读回来了就走下一步，全部走完就收尾。 */
    public static void pump(ShaderProject project) {
        if (!busy) {
            return;
        }
        if (inFlight) {
            if (System.currentTimeMillis() - inFlightSince > READBACK_TIMEOUT_MS) {
                abort(GtLang.get("gtshaders.debug.err.timeout"));
                finish(project);
            }
            return;
        }
        if (error != null) {
            finish(project);
            return;
        }
        Step next = STEPS.pollFirst();
        if (next == null) {
            finish(project);
            return;
        }
        next.run(project);
        if (error != null) {
            finish(project);
        }
    }

    private static void abort(String message) {
        error = message;
        STEPS.clear();
        inFlight = false;
    }

    private static void finish(ShaderProject project) {
        busy = false;
        inFlight = false;
        select(DebugInstrument.SEL_VIEW);
        if (restoreAfter) {
            restoreAfter = false;
            if (mode == Mode.NONE) {
                PreviewRuntime.applyProject(project);
            } else {
                recompile(project);
            }
        }
        publish(true);
    }

    private static void cancelInspection() {
        generation++;
        STEPS.clear();
        PixelReadback.cancelAll();
        busy = false;
        inFlight = false;
        select(DebugInstrument.SEL_VIEW);
    }

    private static void publish(boolean done) {
        ValueSample value = mode == Mode.VALUE && (done || metaComponents > 0 || metaHit)
                ? new ValueSample(metaHit, metaComponents, metaType, values.clone()) : null;
        last = new Inspection(sampleU, sampleV, List.copyOf(layerSamples), value, ubSample, exact, done, error);
    }

    /** 编辑器关闭时调用：中止取样，调试视图退回正常预览——不能让插桩的画面留在游戏里。 */
    public static void onEditorClosed(ShaderProject project) {
        if (busy || mode != Mode.NONE || restoreAfter) {
            stop(project);
        }
        last = null;
    }
}
