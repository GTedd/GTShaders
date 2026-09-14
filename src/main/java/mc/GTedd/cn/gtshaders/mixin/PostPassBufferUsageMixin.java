package mc.GTedd.cn.gtshaders.mixin;

import com.mojang.renderpearl.api.buffers.GpuBuffer;
import net.minecraft.client.renderer.PostPass;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * 给后处理 pass 的 uniform 缓冲补上 {@code USAGE_COPY_DST}。
 *
 * <p>原版只申请了 {@code USAGE_UNIFORM}，而 {@code CommandEncoder.writeToBuffer} 会显式检查
 * {@code USAGE_COPY_DST}，缺了就直接抛 IllegalStateException。补上这个标志位之后，
 * 参数调节和时间推进才能靠「直接写缓冲」实现——否则每改一个数值都要重建整条 PostChain，
 * 那意味着重新链接管线并泄漏旧的 GL program，拖动滑块会在几秒内把显存刷爆。
 *
 * <p>这个标志位只是一个能力声明，对原版自己的 pass 没有任何行为影响，所以不做 id 过滤，
 * 全量加上反而更简单可靠。
 *
 * <p>26.3 只改了目标方法的<b>描述符</b>：{@code GpuDevice} 与 {@code GpuBuffer} 搬到了
 * {@code com.mojang.renderpearl.api} 下。方法本身、参数位置、{@code USAGE_*} 的取值都没变。
 */
@Mixin(PostPass.class)
public class PostPassBufferUsageMixin {

    @ModifyArg(
            method = "<init>",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/mojang/renderpearl/api/device/GpuDevice;createBuffer(Ljava/util/function/Supplier;ILjava/nio/ByteBuffer;)Lcom/mojang/renderpearl/api/buffers/GpuBuffer;"
            ),
            index = 1
    )
    private int gtshaders$allowBufferWrites(int usage) {
        return usage | GpuBuffer.USAGE_COPY_DST;
    }
}
