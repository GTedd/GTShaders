// 阈值化 / Threshold
// 二值化，但带抖动。硬阈值会把渐变区切成大块色斑，
// 叠一层 Bayer 有序抖动之后，同样只有两种颜色却能表现出连续的层次。
//
// [en_us]
// Two-color thresholding with ordered dithering.
// Binarization, but with dithering. A hard threshold chops gradients into big blotches of color. With a layer of
// Bayer ordered dithering on top, the same two colors can show continuous tonal gradation.
//
// @param name=Level type=float min=0 max=1 default=0.5 zh_cn=阈值 en_us=Threshold
// @param name=Softness type=float min=0 max=0.5 default=0.02 zh_cn=过渡柔和 en_us=Softness
// @param name=Dither type=float min=0 max=1 default=0.5 zh_cn=抖动 en_us=Dither
// @param name=DitherScale type=float min=1 max=8 default=1 zh_cn=抖动颗粒 en_us=Dither Pixel
// @param name=DarkColor type=color3 default=#000000 zh_cn=暗色 en_us=Dark
// @param name=LightColor type=color3 default=#FFFFFF zh_cn=亮色 en_us=Light

// mat4 按列主序填充，于是 BAYER[x][y] 正好取到坐标 (x,y) 的阈值
const mat4 BAYER = mat4(
     0.0, 12.0,  3.0, 15.0,
     8.0,  4.0, 11.0,  7.0,
     2.0, 14.0,  1.0, 13.0,
    10.0,  6.0,  9.0,  5.0) / 16.0;

void main() {
    vec3 src = texture(InSampler, texCoord).rgb;
    float luma = dot(src, vec3(0.2126, 0.7152, 0.0722));

    // 抖动阈值取自屏幕像素坐标，图案必须钉死在像素网格上才不会糊
    ivec2 c = ivec2(mod(floor(gl_FragCoord.xy / max(DitherScale, 1.0)), 4.0));
    float bias = (BAYER[c.x][c.y] - 0.5) * Dither * 0.5;

    float k = smoothstep(Level - Softness + bias, Level + Softness + bias, luma);
    fragColor = vec4(mix(DarkColor, LightColor, k), 1.0);
}
