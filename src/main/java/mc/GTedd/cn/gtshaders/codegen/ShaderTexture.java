package mc.GTedd.cn.gtshaders.codegen;

/**
 * 后处理层的额外贴图输入。
 *
 * <p>post effect JSON 除了能读渲染目标，还能直接声明一张
 * 资源包贴图作为采样器输入（{@code PostChainConfig.TextureInput}）。
 * 这让后处理效果可以真正“嵌入自定义图片/图标”，而不是只能用 SDF 或点阵硬画。
 *
 * @param samplerName GLSL 采样器变量名，也是 JSON input 的 sampler_name
 * @param location    资源包里的贴图 id，如 {@code gtshaders:textures/gui/killicon.png}
 * @param width       贴图宽度（JSON 必需）
 * @param height      贴图高度（JSON 必需）
 * @param bilinear    是否线性过滤
 * @param displayName 界面显示名，来自 {@code zh_cn} / {@code en_us}
 */
public record ShaderTexture(String samplerName, String location,
                            int width, int height, boolean bilinear,
                            String displayName) {
}
