package mc.GTedd.cn.gtshaders.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import mc.GTedd.cn.gtshaders.runtime.TrailRuntime;

/**
 * 在第一人称主手物品提交渲染的那一刻，把它的变换矩阵交给 {@link TrailRuntime}。
 *
 * <p>这是「刀在哪」唯一拿得准的时刻：{@code PoseStack} 上此时叠满了握持姿态、
 * 挥砍动画、受伤晃动、走路摇摆的全部变换，正是玩家眼睛看到的那个位置。
 * 早一步（{@code submitArmWithItem} 的 HEAD）少了姿态，晚一步（TAIL）矩阵已经 pop 掉了。
 *
 * <p>过滤靠 {@link FirstPersonHandsAndItemsRendererMixin} 圈出来的那段时间 + 引用比对，
 * 理由见它的类注释。没有效果用到轨迹时 {@link TrailRuntime#capture} 立刻返回，
 * 这条注入在绝大多数会话里就是一次布尔判断。
 */
@Mixin(ItemStackRenderState.class)
public abstract class ItemStackRenderStateMixin {

    @Inject(method = "submit", at = @At("HEAD"))
    private void gtshaders$captureTrail(PoseStack poseStack, SubmitNodeCollector collector,
                                        int light, int overlay, int outline, CallbackInfo ci) {
        ItemStackRenderState self = (ItemStackRenderState) (Object) this;
        if (!TrailRuntime.isFirstPersonMainHand(self)) {
            return;
        }
        TrailRuntime.capture(poseStack.last().pose(), self.getModelBoundingBox());
    }
}
