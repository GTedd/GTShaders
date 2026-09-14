package mc.GTedd.cn.gtshaders.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import mc.GTedd.cn.gtshaders.runtime.BindMode;

/**
 * 绑定模式下滚轮切换「正在编辑哪一条绑定」，而不是切物品栏。
 *
 * <h2>为什么滚轮值得占用</h2>
 *
 * <p>一个工程可以有八条绑定。要在世界里逐条编辑，就得有一个不离开准星的办法切换当前项——
 * 数字键 1-9 也能做，但它们在游戏里是物品栏，拦掉之后手会本能地找不着北；滚轮的
 * 「在一列东西里前后翻」这个手感本来就和要做的事一样。
 *
 * <h2>为什么拦 {@code onScroll} 而不是别处</h2>
 *
 * <p>这是 GLFW 滚轮回调的唯一入口，它下面才分叉成「切物品栏」和「旁观者调速」等等。
 * 在分叉之后再拦要拦好几处，而且每个版本的分叉都不一样。
 *
 * <p>它在主线程被调用（GLFW 事件由主循环 {@code pollEvents} 派发），所以直接改工程状态是安全的。
 *
 * <h2>方向</h2>
 *
 * <p>向上滚 = 上一条。和原版物品栏「向上滚选左边那格」是同一个方向感，
 * 也和列表 UI 里滚轮向上翻的方向一致。
 */
@Mixin(MouseHandler.class)
public abstract class BindModeScrollMixin {

    @Inject(method = "onScroll", at = @At("HEAD"), cancellable = true)
    private void gtshaders$scrollBindings(long window, double xOffset, double yOffset, CallbackInfo ci) {
        if (!BindMode.isActive()) {
            return;
        }
        // 事件可能来自别的窗口（输入法候选窗之类）；只认游戏主窗口，和原版第一行的判断一致
        if (window != Minecraft.getInstance().getWindow().handle()) {
            return;
        }
        // 高精度触控板会连续送来很小的增量，signum 一下避免一次轻扫翻过好几条
        if (yOffset != 0) {
            BindMode.cycleBinding(yOffset > 0 ? -1 : 1);
        }
        ci.cancel();
    }
}
