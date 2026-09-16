package mc.GTedd.cn.gtshaders.mixin;

import mc.GTedd.cn.gtshaders.runtime.GpuProfiler;
import net.minecraft.client.renderer.PostPass;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 分层 GPU 计时的挂点：{@code PostPass.addToFrame} 登记给 FrameGraph 的那个执行体。
 *
 * <p>{@code addToFrame} 本身只是登记，真正发出绘制命令的是它传给 {@code FramePass.executes} 的
 * lambda，编译后名叫 {@code lambda$addToFrame$1}（26.3 字节码核对过：它里面调
 * {@code createRenderPass}、{@code setPipeline}、{@code draw}）。计时要包住的正是这一段。
 *
 * <p>lambda 的编号是编译器给的，原版改动这个类时可能变，所以两处注入都是 {@code require = 0}：
 * 对不上时不会让游戏崩，只是计时拿不到数据，界面上显示「无数据」。
 */
@Mixin(PostPass.class)
public abstract class PostPassProfileMixin {

    @Inject(method = "lambda$addToFrame$1", at = @At("HEAD"), require = 0)
    private void gtshaders$profileStart(CallbackInfo ci) {
        GpuProfiler.onPassStart((PostPass) (Object) this);
    }

    @Inject(method = "lambda$addToFrame$1", at = @At("RETURN"), require = 0)
    private void gtshaders$profileEnd(CallbackInfo ci) {
        GpuProfiler.onPassEnd((PostPass) (Object) this);
    }
}
