package mc.GTedd.cn.gtshaders.mixin;

import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import net.minecraft.client.renderer.PostPass;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Map;

/**
 * 取出 pass 的自定义 uniform 缓冲（块名 → GpuBuffer）与它用的管线对象。
 *
 * <p>{@code customUniforms} 配合 {@link PostPassBufferUsageMixin} 给缓冲加上 {@code USAGE_COPY_DST}，
 * 就可以每帧直接把新的参数值和时间写进去，完全跳过重新编译。
 * 原版把 uniform 值在 {@code PostPass} 构造时一次性烘进 GPU buffer，之后每帧只绑定不重写，
 * 所以想让滑块拖动实时生效，只能绕到缓冲本身上去写。
 *
 * <p>{@code pipeline} 是 26.3 新加的需求：关闭一条链时要把它编译出来的管线从
 * {@link PipelineCacheAccessor 管线缓存}里精确摘掉，否则每改一次源码就漏一条。
 */
@Mixin(PostPass.class)
public interface PostPassAccessor {

    @Accessor("customUniforms")
    Map<String, GpuBuffer> gtshaders$customUniforms();

    @Accessor("pipeline")
    RenderPipeline gtshaders$pipeline();
}
