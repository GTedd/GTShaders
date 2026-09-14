package mc.GTedd.cn.gtshaders.mixin;

import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.GlobalSettingsUniform;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import mc.GTedd.cn.gtshaders.runtime.GpuProfiler;
import mc.GTedd.cn.gtshaders.runtime.PixelReadback;
import mc.GTedd.cn.gtshaders.runtime.PreviewRuntime;
import mc.GTedd.cn.gtshaders.runtime.WorldClock;

import java.util.Collections;
import java.util.List;

/**
 * 把 GTShaders 的效果挂进原版这一帧的后处理请求列表。
 *
 * <h2>为什么是 {@code preparePostEffects} 而不是 {@code update}</h2>
 *
 * <p>26.3 把后处理状态从「单个 {@code postEffectId} 字段」换成了<b>每帧重建的列表</b>。
 * {@code GameRenderer.update} 的顺序是：
 * <pre>
 *   requestedPostEffects.clear()
 *   add(minecraft:end_of_frame)
 *   addAll(player.getActivePostEffects())      // /posteffect 指令挂上的
 *   add(spectatedEntityPostEffect)             // 旁观苦力怕/蜘蛛那套滤镜
 * </pre>
 * 之后 {@code extract} 再把它拷进 {@code gameRenderState.requestedPostEffects}，
 * 拷贝时过滤掉 {@code failedPostEffects} 里的 id；{@code render()} 用的是拷贝后的那份。
 * 所以注入 {@code update} 的 TAIL 已经晚了——那时拷贝早就做完了。
 *
 * <p>直接注入 {@code preparePostEffects} 的 HEAD 有两个额外好处：
 * <ul>
 *   <li>绕开了那个 {@code failedPostEffects} 过滤。它是「失败过的 id 在资源重载前不再重试」的
 *       节流机制，对原版合理，但编辑器里作者改一行代码就该立刻重试，不能等资源重载。</li>
 *   <li>这里拿到的就是原版马上要遍历的那个列表，中间再没有第二次拷贝，不存在时序问题。</li>
 * </ul>
 *
 * <h2>为什么判据不能是「列表为空」</h2>
 *
 * <p>{@code render()} 在不渲染世界时会调 {@code preparePostEffects(Collections.emptyList())}，
 * 用途是把所有链的 persistent 目标关掉。那个列表<b>不可变</b>，往里 add 会抛。
 *
 * <p>看上去拿「空」当判据就够了——{@code update} 无条件 add 了 {@code end_of_frame}，
 * 列表似乎永远不空。<b>但那是错的</b>：原版资产里根本没有
 * {@code assets/minecraft/post_effect/end_of_frame.json}，这个 id 第一帧就
 * {@code isPostEffectValid} 失败进了 {@code failedPostEffects}，此后每一帧都被
 * {@code extract} 的过滤剔掉。于是普通游戏里传进来的是一个<b>空的、可变的</b>
 * {@code ArrayList}——而那正是最常见的挂载时机（玩家身上没有任何别的后处理效果）。
 * 用「空」当判据等于在最常见的情况下什么都不挂。
 *
 * <p>所以判据改成认那个不可变单例本身：{@code Collections.emptyList()} 返回的是
 * {@code EMPTY_LIST} 静态单例，恒等比较精确且零成本。外面再包一层
 * {@code UnsupportedOperationException} 兜底，万一将来原版换成别的不可变实现，
 * 表现是「预览挂不上」而不是「每帧刷崩」。
 */
@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {

    @Inject(method = "preparePostEffects", at = @At("HEAD"))
    private void gtshaders$appendOwnEffects(List<Identifier> requested, CallbackInfo ci) {
        if (requested == Collections.<Identifier>emptyList()) {
            return;
        }
        try {
            PreviewRuntime.appendActiveIds(requested);
        } catch (UnsupportedOperationException e) {
            // 原版换了别的不可变列表实现。这一帧挂不上，下一帧照常重试，不打日志免得刷屏。
        }
    }

    /** 分层计时：一轮测量从这一帧的后处理开始前算起。 */
    @Inject(method = "applyPostEffects", at = @At("HEAD"))
    private void gtshaders$beforePostEffects(CallbackInfo ci) {
        GpuProfiler.beginFrame();
    }

    /**
     * 后处理全部跑完、界面还没画的那一刻：调试视图在这里回读像素，分层计时在这里收尾。
     * 见 {@link PixelReadback} 为什么必须是这个时刻。
     */
    @Inject(method = "applyPostEffects", at = @At("TAIL"))
    private void gtshaders$afterPostEffects(CallbackInfo ci) {
        GpuProfiler.endFrame();
        PixelReadback.onPostEffectsApplied(((GameRenderer) (Object) this).mainRenderTarget());
    }

    /**
     * 接管世界时钟：把写进 {@code Globals} 的 {@code gameTime} 换成编辑器时间轴。
     *
     * <p>拦的是 {@code render()} 里那一次 {@code GlobalSettingsUniform.update} 调用，
     * 也就是**每帧唯一一处**把 {@code Globals} 灌进 UBO 的地方。改在这里，
     * 后处理层与核心着色器层同时生效，而两边的着色器源码都不用动。
     *
     * <p>只有 {@link WorldClock#isEngaged()} 为真时才替换，其余情况原样转交——
     * 包括资源包视角，那种场景下时间必须来自原版时钟。
     *
     * <p>{@code gameTime} 与 {@code partialTick} <b>必须成对替换</b>：原版把两者相加再折算，
     * 只换整数部分会把亚 tick 精度丢给原来的时钟，动画会以 20 Hz 一格一格跳。
     */
    @Redirect(
            method = "render",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/GlobalSettingsUniform;"
                            + "update(IIDJFILnet/minecraft/world/phys/Vec3;Z)V"
            )
    )
    private void gtshaders$overrideWorldClock(GlobalSettingsUniform instance, int width, int height,
                                              double glintAlpha, long gameTime, float partialTick,
                                              int menuBlurRadius, Vec3 cameraPos, boolean useRgss) {
        if (WorldClock.isEngaged()) {
            gameTime = WorldClock.gameTime();
            partialTick = WorldClock.partialTick();
        }
        instance.update(width, height, glintAlpha, gameTime, partialTick,
                menuBlurRadius, cameraPos, useRgss);
    }
}

