package mc.GTedd.cn.gtshaders.mixin;

import net.minecraft.client.renderer.PostChain;
import net.minecraft.client.renderer.PostPass;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.List;

/**
 * 取出 PostChain 内部的 pass 列表。
 *
 * <p>需要它是为了拿到第一个 pass 的 uniform 缓冲，从而实现<b>不重编译就能改参数</b>：
 * 原版把 uniform 值在 {@code PostPass} 构造时一次性烘进 GPU buffer，之后每帧只绑定不重写，
 * 所以想让滑块拖动实时生效，只能绕到缓冲本身上去写。
 */
@Mixin(PostChain.class)
public interface PostChainAccessor {

    @Accessor("passes")
    List<PostPass> gtshaders$passes();
}
