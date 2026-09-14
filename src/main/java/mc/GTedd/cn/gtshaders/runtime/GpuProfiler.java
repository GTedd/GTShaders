package mc.GTedd.cn.gtshaders.runtime;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.commands.GpuQueryPool;
import mc.GTedd.cn.gtshaders.GTShaders;
import net.minecraft.client.renderer.PostPass;
import org.jspecify.annotations.Nullable;

import java.util.Arrays;
import java.util.OptionalLong;

/**
 * 每层效果在 GPU 上花了多少毫秒。
 *
 * <h2>怎么量</h2>
 *
 * <p>在每个后处理通道执行的前后各打一个 GPU 时间戳（{@code CommandEncoder.writeTimestamp}，
 * GL 后端底层是 {@code glQueryCounter(GL_TIMESTAMP)}），两者之差乘以
 * {@code DeviceInfo.timestampPeriod()} 就是纳秒。挂点是 {@code PostPass} 里真正发出绘制命令的那个 lambda
 * （{@code PostPassProfileMixin}）。原版的 {@code TimerQuery} 是同一套用法。
 *
 * <h2>不阻塞渲染</h2>
 *
 * <p>时间戳要等 GPU 执行到那里才有值。照 SHADERed 的做法：每轮只量一帧，结果全部就绪之后，
 * 隔 {@link #INTERVAL_MS} 再开下一轮；取值用非阻塞查询，没就绪就下一帧再问。
 * SHADERed 在「通道未激活」时跳过了 {@code glEndQuery}，导致查询配不上对——
 * 这里开始与结束按通道下标成对记，只有两头都写过的才算数。
 *
 * <p><b>未在真机上验证</b>：挂点是编译器生成的 lambda 名字，注入失败时（{@code require = 0}）
 * 不会报错，表现是「计时一直没有结果」，面板上会如实显示成「无数据」。
 */
public final class GpuProfiler {

    /** 两轮测量之间至少隔多久。每帧都量会让查询对象一直处于等待中。 */
    private static final long INTERVAL_MS = 400;
    /** 最多量多少个通道。工程里的层数远到不了这个数，多给是为了不必中途重建查询池。 */
    private static final int MAX_PASSES = 64;

    private enum State {
        IDLE, RECORDING, AWAITING
    }

    private static boolean enabled;
    private static boolean unsupported;
    private static @Nullable GpuQueryPool pool;
    private static State state = State.IDLE;
    private static long lastRoundAt;
    private static final boolean[] started = new boolean[MAX_PASSES];
    private static final boolean[] ended = new boolean[MAX_PASSES];
    private static float[] results = new float[0];
    private static long resultsAt;

    private GpuProfiler() {
    }

    public static boolean isEnabled() {
        return enabled && !unsupported;
    }

    /** 这块设备或后端不支持时间戳查询。 */
    public static boolean isUnsupported() {
        return unsupported;
    }

    public static void setEnabled(boolean value) {
        if (enabled == value) {
            return;
        }
        enabled = value;
        if (!value) {
            close();
        }
    }

    /**
     * 最近一轮的结果：第 i 个元素是链上第 i 个通道的毫秒数，NaN 表示这个通道没量到。
     * 从没量到过时是空数组。
     */
    public static float[] results() {
        return results;
    }

    /** 最近一轮结果是多少毫秒之前拿到的；从没拿到过返回 -1。 */
    public static long resultsAgeMs() {
        return resultsAt == 0 ? -1 : System.currentTimeMillis() - resultsAt;
    }

    /** {@code applyPostEffects} 的 HEAD：先把上一轮的结果收回来，够时间了就开新一轮。 */
    public static void beginFrame() {
        if (!isEnabled()) {
            return;
        }
        if (state == State.AWAITING) {
            collect();
        }
        if (state != State.IDLE || System.currentTimeMillis() - lastRoundAt < INTERVAL_MS) {
            return;
        }
        if (pool == null) {
            try {
                pool = RenderSystem.getDevice().createTimestampQueryPool(MAX_PASSES * 2);
            } catch (RuntimeException e) {
                unsupported = true;
                GTShaders.LOGGER.warn("GPU 时间戳查询不可用，分层计时关闭", e);
                return;
            }
        }
        Arrays.fill(started, false);
        Arrays.fill(ended, false);
        state = State.RECORDING;
    }

    public static void onPassStart(PostPass pass) {
        write(pass, true);
    }

    public static void onPassEnd(PostPass pass) {
        write(pass, false);
    }

    private static void write(PostPass pass, boolean start) {
        if (state != State.RECORDING || pool == null) {
            return;
        }
        int index = PreviewRuntime.passIndexOf(pass);
        if (index < 0 || index >= MAX_PASSES) {
            return;
        }
        if (!start && !started[index]) {
            return;
        }
        try {
            RenderSystem.getDevice().createCommandEncoder().writeTimestamp(pool, index * 2 + (start ? 0 : 1));
            if (start) {
                started[index] = true;
            } else {
                ended[index] = true;
            }
        } catch (RuntimeException e) {
            unsupported = true;
            state = State.IDLE;
            GTShaders.LOGGER.warn("写 GPU 时间戳失败，分层计时关闭", e);
        }
    }

    /** {@code applyPostEffects} 的 TAIL：这一帧量完了，转入等结果。 */
    public static void endFrame() {
        if (state != State.RECORDING) {
            return;
        }
        boolean any = false;
        for (boolean b : ended) {
            any |= b;
        }
        lastRoundAt = System.currentTimeMillis();
        // 这一帧我们的链根本没跑（例如刚好在重编译）就不等了，直接进下一轮
        state = any ? State.AWAITING : State.IDLE;
    }

    private static void collect() {
        GpuQueryPool p = pool;
        if (p == null) {
            state = State.IDLE;
            return;
        }
        int count = 0;
        for (int i = 0; i < MAX_PASSES; i++) {
            if (ended[i]) {
                count = i + 1;
            }
        }
        float[] out = new float[count];
        double period = RenderSystem.getDevice().getDeviceInfo().timestampPeriod();
        for (int i = 0; i < count; i++) {
            if (!ended[i]) {
                out[i] = Float.NaN;
                continue;
            }
            OptionalLong a = p.getValue(i * 2);
            OptionalLong b = p.getValue(i * 2 + 1);
            if (a.isEmpty() || b.isEmpty()) {
                // 还没就绪：整轮一起等，不交半截结果
                return;
            }
            out[i] = (float) ((b.getAsLong() - a.getAsLong()) * period / 1_000_000.0);
        }
        results = out;
        resultsAt = System.currentTimeMillis();
        state = State.IDLE;
    }

    private static void close() {
        if (pool != null) {
            try {
                pool.close();
            } catch (RuntimeException ignored) {
                // 设备已经在关闭：放掉即可
            }
            pool = null;
        }
        state = State.IDLE;
        results = new float[0];
        resultsAt = 0;
    }
}
