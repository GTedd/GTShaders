package mc.GTedd.cn.gtshaders.mixin;

import com.mojang.blaze3d.pipeline.PipelineCache;
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Map;

/**
 * 直接拿到管线缓存的内部表，用来<b>精确剔除</b>我们自己编译出来的管线。
 *
 * <h2>为什么不能只用 {@code clear()}</h2>
 *
 * <p>{@link PipelineCache#clear()} 会关闭并清空<b>全部</b>管线，包括原版那几百条。
 * 它们会在下一次使用时逐个重编，带来一次肉眼可见的卡顿。
 * 26.2 时代我们只能这么做（当时的接口就只有「整体清空」一个），代价是「攒够 60 次编译再清一次」
 * 这种折中——泄漏有上界，但卡顿仍然会周期性出现。
 *
 * <p>26.3 的缓存是一张普通的 {@code Map}，于是可以只删自己的那几条：原版管线一条不动，
 * 既没有泄漏也没有卡顿。
 *
 * <h2>为什么可以按对象身份删</h2>
 *
 * <p>{@code RenderPipeline} 没有重写 {@code equals} / {@code hashCode}，这张表实际按<b>对象身份</b>索引。
 * 而 {@code PostChain.createPass} 每次加载都会 {@code RenderPipeline.builder()...build()} 出全新对象——
 * 这正是「不清就一直涨」的原因，也正是「拿着 pass 手里那个对象就能精确删掉」的原因。
 */
@Mixin(PipelineCache.class)
public interface PipelineCacheAccessor {

    @Accessor("cache")
    Map<RenderPipeline, CompiledRenderPipeline> gtshaders$cache();
}
