package mc.GTedd.cn.gtshaders.mixin;

import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import mc.GTedd.cn.gtshaders.runtime.BindMode;

/**
 * 绑定模式下改写鼠标左右键和 Esc 的含义。
 *
 * <h2>为什么必须在这里拦，而不是开一个 Screen</h2>
 *
 * <p>最省事的做法是把绑定模式做成一个 {@code Screen}。但 MC 一打开 Screen 就<b>释放鼠标</b>，
 * 视角立刻不能转了——而绑定模式的全部意义就是「用准星去瞄世界里的东西」。所以只能不开界面，
 * 在游戏正常运行的状态下把几个键的含义换掉，也就是 Xaero、Journeymap 那类 mod 的做法。
 *
 * <h2>为什么四个方法都要拦</h2>
 *
 * <p>少拦任何一个都会在生存模式下出事：
 * <ul>
 *   <li>{@code startAttack} 不拦 → 一边配绑定一边把面前那只怪打死，而它正是你要绑的目标</li>
 *   <li>{@code continueAttack} 不拦 → 左键按住不放会开始挖方块。这个方法是<b>每 tick</b> 调的，
 *       和 {@code startAttack} 是两条独立路径，拦一个不影响另一个</li>
 *   <li>{@code startUseItem} 不拦 → 右键切绑法的同时把水桶倒了、把末影珍珠扔了</li>
 *   <li>{@code pauseGame} 不拦 → Esc 弹出暂停菜单，而绑定模式还开着，回来一脸懵</li>
 * </ul>
 *
 * <p>{@code pauseGame} 在 26.3 里<b>只有 {@code KeyboardHandler} 一个调用点</b>，也就是按 Esc
 * 那一条路径；失焦自动暂停走的是另一条路。所以拦它不会波及「切出窗口就暂停」，
 * 换来的是 Esc 在绑定模式下有一个符合直觉的含义。
 *
 * <h2>为什么不判 {@code Screen} 是否打开</h2>
 *
 * <p>不需要。这四个方法本来就只在没有 Screen 的时候才会被调到，而绑定模式又要求没有 Screen。
 * 多加一层判断只会掩盖「模式状态没清干净」这类真问题。
 *
 * <h2>修饰键为什么直接调 {@code Minecraft} 自己的方法</h2>
 *
 * <p>26.3 把 {@code hasShiftDown()} / {@code hasControlDown()} 放在了 {@code Minecraft} 上，
 * 实现就是查 {@code InputConstants}，不依赖任何 Screen——正好是这里要的语义。
 * 别在 mixin 里另写同名的私有静态助手：mixin 会把它当成对目标同名方法的 overwrite，
 * 加载期直接抛 {@code InvalidMixinException: PRIVATE overwrite method hasShiftDown ...
 * cannot reduce visibility of PUBLIC target method}，整个客户端起不来。
 */
@Mixin(Minecraft.class)
public abstract class BindModeInputMixin {

    /**
     * 左键：绑。按住 Shift 是「新建一条再绑」而不是改现有的。
     *
     * <p>返回 {@code false} 而不是 {@code true}：这个返回值决定要不要播挥手动画，
     * 而绑定模式下手臂不该动——那会让人以为自己打了一下。
     */
    @Inject(method = "startAttack", at = @At("HEAD"), cancellable = true)
    private void gtshaders$bindOnAttack(CallbackInfoReturnable<Boolean> cir) {
        if (!BindMode.isActive()) {
            return;
        }
        Minecraft mc = (Minecraft) (Object) this;
        if (mc.hasControlDown()) {
            // 删除放在左键的修饰键上而不是另给一个键：绑定模式里手已经在鼠标上了，
            // 而且它自带两次确认，误触的代价被挡住了
            BindMode.deleteCurrent();
        } else {
            BindMode.bind(mc, mc.hasShiftDown());
        }
        cir.setReturnValue(false);
    }

    /** 中键：把当前绑定触发一次看看。原版这个键是「选取方块」，绑定模式下用不上。 */
    @Inject(method = "pickBlockOrEntity", at = @At("HEAD"), cancellable = true)
    private void gtshaders$simulateOnPick(CallbackInfo ci) {
        if (BindMode.isActive()) {
            BindMode.simulate();
            ci.cancel();
        }
    }

    /** 左键按住：绑定模式下什么都不做，否则会开始挖方块。 */
    @Inject(method = "continueAttack", at = @At("HEAD"), cancellable = true)
    private void gtshaders$blockContinueAttack(boolean leftDown, CallbackInfo ci) {
        if (BindMode.isActive()) {
            ci.cancel();
        }
    }

    /**
     * 右键：换绑法。按住 Shift 反向循环——绑法最多五项，多按两下能忍，
     * 但「手滑跳过了想要的那一项只能再绕一圈」在五项的循环里已经开始烦人了。
     */
    @Inject(method = "startUseItem", at = @At("HEAD"), cancellable = true)
    private void gtshaders$bindOnUse(CallbackInfo ci) {
        if (!BindMode.isActive()) {
            return;
        }
        Minecraft mc = (Minecraft) (Object) this;
        BindMode.cycleSemantic(mc, mc.hasShiftDown() ? -1 : 1);
        ci.cancel();
    }

    /** Esc：退出绑定模式，而不是弹暂停菜单。 */
    @Inject(method = "pauseGame", at = @At("HEAD"), cancellable = true)
    private void gtshaders$exitOnEscape(boolean pauseOnly, CallbackInfo ci) {
        if (BindMode.isActive()) {
            BindMode.exit();
            ci.cancel();
        }
    }
}
