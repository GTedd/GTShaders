package mc.GTedd.cn.gtshaders.mixin;

import com.mojang.renderpearl.api.pipeline.ShaderType;
import net.minecraft.client.renderer.ShaderManager;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import mc.GTedd.cn.gtshaders.runtime.PreviewRuntime;
import mc.GTedd.cn.gtshaders.runtime.VanillaSource;

/**
 * 把 GTShaders 的内存着色器源码喂给原版编译器。这是整个即时预览的支点。
 *
 * <h2>26.3 为什么换了地方</h2>
 *
 * <p>26.2 时这件事挂在 {@code ShaderManager.getShader}。26.3 把那个方法<b>删掉</b>了，
 * 着色器源改由一个接口对象提供，链路是：
 * <pre>
 *   PostChain.createPass
 *     → RenderSystem.getCompiledPipelineNullable(pipeline)
 *       → RenderSystem.currentPipelineCache.get(pipeline)      // 由 ShaderManager.apply 装好
 *         → 未命中：GpuDevice.compilePipeline(pipeline, shaderSource, backgroundExecutor).join()
 *           → shaderSource.getShader(id, type)                 // ← 我们接在这里
 * </pre>
 * 那个 {@code shaderSource} 就是 {@code ShaderManager$Configs}——一个 record，
 * 它的 {@code getShader} 实现只是从资源包扫出来的 Map 里取一次，取不到返回 null。
 * 我们抢在它前面返回内存源码，GPU 拿到的就是编辑器里刚敲的那份。
 *
 * <h2>两个必须记住的后果</h2>
 *
 * <ul>
 *   <li><b>这个方法跑在后台线程上</b>（{@code Util.backgroundExecutor()}，由 {@code join()} 等待）。
 *       所以 {@link PreviewRuntime} 里存源码的表必须是并发安全的，不能是普通 HashMap。</li>
 *   <li>编译走的是 ShaderC → SPIR-V → SPIRV-Cross → 驱动，<b>不再是驱动直接编 GLSL</b>。
 *       预校验要用同一个编译器才有意义，见 {@code GlslValidator}。</li>
 * </ul>
 *
 * <p>仍然坚持复用原版的编译器与管线：工具的最终产物是资源包，
 * <b>预览必须走和导出物完全相同的编译路径</b>，否则就会出现「编辑器里好好的，进游戏一加载就崩」。
 */
@Mixin(ShaderManager.Configs.class)
public abstract class ShaderConfigsMixin {

    @Inject(method = "getShader", at = @At("HEAD"), cancellable = true)
    private void gtshaders$provideShaderSource(Identifier id, ShaderType type,
                                               CallbackInfoReturnable<String> cir) {
        // 我们自己在取原版模板时必须放行，否则拿回来的是上一次注入的产物，
        // 下一次生成会在它上面再注入一遍，越滚越大
        if (VanillaSource.isBypassing()) {
            return;
        }
        String source = PreviewRuntime.shaderSourceFor(id, type);
        if (source != null) {
            cir.setReturnValue(source);
        }
    }
}
