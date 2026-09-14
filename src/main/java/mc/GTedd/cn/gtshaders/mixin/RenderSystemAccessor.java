package mc.GTedd.cn.gtshaders.mixin;

import com.mojang.blaze3d.pipeline.PipelineCache;
import com.mojang.blaze3d.systems.RenderSystem;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * 取当前生效的管线缓存。
 *
 * <p>26.3 删掉了 {@code GpuDevice.clearPipelineCache()}，编译产物改由
 * {@link PipelineCache} 对象持有——{@code ShaderManager.apply} 每次资源重载都会新建一个
 * （构造参数就是那次扫出来的 {@code Configs}），再 {@code RenderSystem.setCurrentPipelineCache} 装上。
 *
 * <p>我们需要它来做两件事，都没有公开接口可用：
 * <ul>
 *   <li><b>让核心着色器接管生效</b>：核心着色器覆盖的是原版固定 id，没法像后处理那样靠换 id 绕开缓存，
 *       只能把已编译的管线作废，让它们惰性重编；</li>
 *   <li><b>回收我们自己产生的管线</b>：每改一次源码就换一批新 id，旧的编译产物留在这个缓存里没人回收。</li>
 * </ul>
 *
 * <p>可能为 null：{@code ShaderManager} 第一次 apply 之前（启动早期）就是空的。
 */
@Mixin(RenderSystem.class)
public interface RenderSystemAccessor {

    @Accessor("currentPipelineCache")
    static @Nullable PipelineCache gtshaders$currentPipelineCache() {
        throw new AssertionError("mixin 未应用");
    }
}
