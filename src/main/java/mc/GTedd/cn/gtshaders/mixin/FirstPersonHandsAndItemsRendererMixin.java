package mc.GTedd.cn.gtshaders.mixin;

import net.minecraft.client.renderer.FirstPersonHandsAndItemsRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.state.level.FirstPersonHandsAndItemsRenderState;
import net.minecraft.client.renderer.state.level.PlayerRenderState;
import com.mojang.blaze3d.vertex.PoseStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import mc.GTedd.cn.gtshaders.runtime.TrailRuntime;

/**
 * 圈出「正在渲染第一人称主手物品」这一小段时间，好让
 * {@link ItemStackRenderStateMixin} 知道该不该记这一帧的刀刃位置。
 *
 * <h2>为什么要两个 mixin 配合</h2>
 *
 * <p>刀刃的位置只有在物品真正被提交渲染的那一刻才拿得到——那时 {@code PoseStack} 上
 * 才叠满了握持、挥砍、晃动的全部变换。而提交发生在
 * {@code ItemStackRenderState.submit} 里，那个方法对<b>所有</b>物品都会走到：
 * 掉落物、盔甲架、GUI 里的图标、别人手上的剑。只看它分不出哪一次是「我手里那把」。
 *
 * <p>反过来，只 hook 这里也不行：{@code submitArmWithItem} 里有七八个分支
 * （吃东西、举盾、拉弓、地图、双持地图），每个分支叠的变换都不一样，
 * 而且 {@code PoseStack} 在方法返回前就 pop 回去了——TAIL 时刻矩阵早就没了。
 *
 * <p>所以拆成两半：这里管「什么时候」，那边管「是什么」。判据是<b>引用相等</b>——
 * {@code mainHandRenderState} 是一个每帧复用的对象，比对它本身比比对物品 id 精确得多
 * （两只手拿同一把剑时物品 id 是一样的）。
 *
 * <h2>为什么是 RETURN 而不是 TAIL</h2>
 *
 * <p>{@code submitHandsWithItems} 有两个返回点——它开头就会为「没有玩家/在睡觉」之类的
 * 情况早退。{@code TAIL} 只注入<b>最后一条</b> RETURN，走早退那条路时标志位就清不掉了。
 * {@code RETURN} 注入每一个返回点，两条路都覆盖到。
 *
 * <p>抛异常的那一帧仍然清不掉（{@code @Inject} 不是 finally）。这没有危害：
 * 下一帧 HEAD 会重新置位并覆盖目标引用，而中间那段时间里判据仍然要求
 * <b>引用等于主手的那个 renderState</b>——那个对象只在第一人称手部渲染里用得到，
 * 别的物品撞不上它。为这个装一层 try/finally 要改写方法体，代价大得多。
 */
@Mixin(FirstPersonHandsAndItemsRenderer.class)
public abstract class FirstPersonHandsAndItemsRendererMixin {

    @Inject(method = "submitHandsWithItems", at = @At("HEAD"))
    private void gtshaders$beginFirstPerson(float partialTick, PoseStack poseStack,
                                            SubmitNodeCollector collector,
                                            PlayerRenderState playerState,
                                            FirstPersonHandsAndItemsRenderState handState,
                                            CallbackInfo ci) {
        TrailRuntime.beginFirstPerson(handState.mainHandRenderState);
    }

    @Inject(method = "submitHandsWithItems", at = @At("RETURN"))
    private void gtshaders$endFirstPerson(float partialTick, PoseStack poseStack,
                                          SubmitNodeCollector collector,
                                          PlayerRenderState playerState,
                                          FirstPersonHandsAndItemsRenderState handState,
                                          CallbackInfo ci) {
        TrailRuntime.endFirstPerson();
    }
}
