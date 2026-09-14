package mc.GTedd.cn.gtshaders.runtime;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.textures.GpuTexture;
import mc.GTedd.cn.gtshaders.GTShaders;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * 从主缓冲回读单个像素。
 *
 * <h2>在哪一刻读</h2>
 *
 * <p>{@code GameRenderer.applyPostEffects} 结束的那一刻（{@code GameRendererMixin} 挂在它的 TAIL）：
 * 这时世界画完了、后处理链全部跑完了，而界面还没开始往主缓冲上画。早一步读到的是没加效果的画面，
 * 晚一步读到的可能是编辑器自己的面板。
 *
 * <h2>为什么请求要隔一帧才读</h2>
 *
 * <p>调试视图靠改隐藏参数切换「这一帧输出什么」，而参数是在下一帧的 {@code preparePostEffects} 里
 * 才写进 uniform 缓冲的。请求在第 F 帧发出，最早在第 F+1 帧的 TAIL 才一定读到新参数的结果。
 *
 * <p>读取本身是异步的：{@code copyTextureToBuffer} 把回调排进渲染线程的栅栏任务，
 * 等 GPU 真的做完才映射缓冲。回调仍在渲染线程上跑，可以直接改界面状态。
 *
 * <p>坐标：请求用屏幕空间、左上角为原点的 0..1；主缓冲的纹理原点在<b>左下角</b>
 * （原版 {@code Screenshot} 按 {@code height - y - 1} 翻行，是同一个约定），这里负责翻过来。
 */
public final class PixelReadback {

    /**
     * @param exact 主缓冲是否确实是 8 位 RGBA。不是的话字节照样给，但按位编码的探针值不可信
     */
    public interface Callback {
        void accept(int r, int g, int b, int a, boolean exact);
    }

    private record Request(double u, double v, long notBeforeFrame, Callback callback) {
    }

    private static final List<Request> PENDING = new ArrayList<>();
    private static long frame;
    private static boolean warned;

    private PixelReadback() {
    }

    /** 请求读屏幕上 (u, v) 处的像素，u 向右、v 向下，都是 0..1。 */
    public static void request(double u, double v, Callback callback) {
        PENDING.add(new Request(u, v, frame + 1, callback));
    }

    /** 取消全部还没读的请求。关闭编辑器、中止取样时用。 */
    public static void cancelAll() {
        PENDING.clear();
    }

    public static boolean hasPending() {
        return !PENDING.isEmpty();
    }

    /** 由 {@code GameRendererMixin} 在 {@code applyPostEffects} 的 TAIL 调用。 */
    public static void onPostEffectsApplied(RenderTarget main) {
        try {
            serve(main);
        } finally {
            frame++;
        }
    }

    private static void serve(RenderTarget main) {
        if (PENDING.isEmpty()) {
            return;
        }
        GpuTexture texture = main.getColorTexture();
        if (texture == null || texture.isClosed()) {
            return;
        }
        GpuFormat format = texture.getFormat();
        boolean exact = format == GpuFormat.RGBA8_UNORM;
        int block = format.blockSize();
        Iterator<Request> it = PENDING.iterator();
        while (it.hasNext()) {
            Request r = it.next();
            if (r.notBeforeFrame() > frame) {
                continue;
            }
            it.remove();
            int px = Math.max(0, Math.min(main.width - 1, (int) Math.floor(r.u() * main.width)));
            int py = Math.max(0, Math.min(main.height - 1, main.height - 1 - (int) Math.floor(r.v() * main.height)));
            try {
                GpuBuffer buffer = RenderSystem.getDevice().createBuffer(() -> "GTShaders pixel readback",
                        GpuBuffer.USAGE_MAP_READ | GpuBuffer.USAGE_COPY_DST, block);
                RenderSystem.getDevice().createCommandEncoder().copyTextureToBuffer(texture, buffer, 0L, () -> {
                    try (var view = buffer.map(true, false)) {
                        ByteBuffer data = view.data();
                        int n = data.remaining();
                        int red = n > 0 ? data.get(0) & 255 : 0;
                        int green = n > 1 ? data.get(1) & 255 : 0;
                        int blue = n > 2 ? data.get(2) & 255 : 0;
                        int alpha = n > 3 ? data.get(3) & 255 : 255;
                        r.callback().accept(red, green, blue, alpha, exact);
                    } catch (RuntimeException e) {
                        GTShaders.LOGGER.warn("像素回读映射失败", e);
                    } finally {
                        buffer.close();
                    }
                }, 0, px, py, 1, 1);
            } catch (RuntimeException e) {
                // 回读失败只影响调试读数，不能拖垮渲染。只报一次，免得每帧刷屏
                if (!warned) {
                    warned = true;
                    GTShaders.LOGGER.warn("像素回读失败，调试读数不可用", e);
                }
            }
        }
    }
}
