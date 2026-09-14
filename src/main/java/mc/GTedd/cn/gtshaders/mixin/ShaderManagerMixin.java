package mc.GTedd.cn.gtshaders.mixin;

import net.minecraft.client.renderer.PostChain;
import net.minecraft.client.renderer.Projection;
import net.minecraft.client.renderer.ProjectionMatrixBuffer;
import net.minecraft.client.renderer.ShaderManager;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import mc.GTedd.cn.gtshaders.runtime.PreviewRuntime;

import java.util.Set;

/**
 * 把 GTShaders 自建的后处理链挂进原版的着色器管线。
 *
 * <p>着色器<b>源码</b>的注入不在这里，而在 {@link ShaderConfigsMixin}——26.3 删掉了
 * {@code ShaderManager.getShader}，源码改由 {@code Configs} 这个 {@code ShaderSource} 提供。
 * 这个类现在只管两件事：<b>放行校验</b>与<b>提供链对象</b>。
 */
@Mixin(ShaderManager.class)
public abstract class ShaderManagerMixin {

    @Shadow
    @Final
    private TextureManager textureManager;

    @Shadow
    @Final
    private Projection postChainProjection;

    @Shadow
    @Final
    private ProjectionMatrixBuffer postChainProjectionMatrixBuffer;

    /**
     * 让原版认为我们的效果 id 是合法的。
     *
     * <p>26.3 新增了这道校验，{@code GameRenderer.preparePostEffects} 会先问它再去取链。
     * 原版的判据是「{@code Configs.postChains} 里有没有这个 id」——而我们的链是运行时拼出来的，
     * 从来不在资源包里，必然被判成「不存在」，然后连 {@code getPostChain} 都不会被调到，
     * 日志里只留下一句 {@code Requested post effect does not exist}。
     *
     * <p>所以这一步不是优化，是<b>必需</b>的：不放行，整个预览就是黑的。
     */
    @Inject(method = "isPostEffectValid", at = @At("HEAD"), cancellable = true)
    private void gtshaders$validateOwnEffects(Identifier id, Set<Identifier> allowedTargets,
                                              CallbackInfoReturnable<Boolean> cir) {
        if (PreviewRuntime.owns(id) || PreviewRuntime.ownsOutline(id)) {
            cir.setReturnValue(true);
        }
    }

    /**
     * 两类链都在这里接管。
     *
     * <p><b>{@code minecraft:entity_outline} 是特别的那一个</b>：它不是我们自己命名空间下的 id，
     * 而是原版固定的。之所以值得单独拦，是因为 {@code allowedTargets} 不一样——
     * {@code LevelRenderer} 加载它时传的是 {@code OUTLINE_TARGETS = {main, entity_outline}}，
     * 而 {@code GameRenderer} 给后处理请求列表那条路传的只有 {@code MAIN_TARGETS = {main}}。
     * 换句话说，<b>只有站在这个位置上，后处理才拿得到逐实体的剪影遮罩</b>。
     *
     * <p>没有启用的轮廓层时 {@code ownsOutline} 返回 false，原版那条描边效果原样放行。
     */
    @Inject(method = "getPostChain", at = @At("HEAD"), cancellable = true)
    private void gtshaders$providePostChain(Identifier id, Set<Identifier> allowedTargets,
                                            CallbackInfoReturnable<PostChain> cir) {
        if (PreviewRuntime.owns(id)) {
            cir.setReturnValue(PreviewRuntime.getOrBuildChain(
                    id, allowedTargets, this.textureManager,
                    this.postChainProjection, this.postChainProjectionMatrixBuffer));
        } else if (PreviewRuntime.ownsOutline(id)) {
            cir.setReturnValue(PreviewRuntime.getOrBuildOutlineChain(
                    id, allowedTargets, this.textureManager,
                    this.postChainProjection, this.postChainProjectionMatrixBuffer));
        }
    }
}
