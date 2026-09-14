// 白平衡 / White Balance
// 用色温（开尔文）和色调（绿-品红）两个轴校色，和相机上的旋钮一致。
// 色温到 RGB 用的是 Tanner Helland 的经验拟合——比"直接乘个蓝色"准确得多，
// 尤其在 3000K 以下和 8000K 以上不会失控。
//
// 默认色温刻意<b>不</b>是 6500K：参考白点正是 6500K，填它等于什么都不做——
// 从库里挑一个效果加进来却看不到任何变化，第一反应只会是「坏了」。
// 默认给一档暖调，想要中性把滑条拉回 6500 即可。
//
// [en_us]
// Camera-style white balance with temperature and tint.
// Corrects color along two axes, temperature (Kelvin) and tint (green-magenta), just like the dials on a camera.
// Temperature to RGB uses Tanner Helland's empirical fit, which is far more accurate than "just multiply in some
// blue" and, in particular, doesn't go haywire below 3000K or above 8000K.
//
// The default temperature is deliberately <b>not</b> 6500K: the reference white point is exactly 6500K, so that
// value does nothing at all. If you picked an effect from the library and saw no change, your first thought would
// be "it's broken". So the default is a warm setting; for neutral, just drag the slider back to 6500.
//
// @param name=Temperature type=float min=1500 max=12000 default=5200 zh_cn=色温(K) en_us=Temperature desc_zh_cn=6500K 是参考白点，填 6500 等于不做任何校正 desc_en_us=6500K is the reference white point; setting it there is a no-op
// @param name=Tint type=float min=-1 max=1 default=0 zh_cn=色调(绿-品红) en_us=Tint
// @param name=Strength type=float min=0 max=1 default=1 zh_cn=强度 en_us=Strength
// @param name=Exposure type=float min=0 max=3 default=1 zh_cn=曝光 en_us=Exposure

vec3 kelvinToRgb(float k) {
    k = clamp(k, 1000.0, 40000.0) / 100.0;
    vec3 c;
    if (k <= 66.0) {
        c.r = 1.0;
        c.g = clamp(0.39008158 * log(k) - 0.63184144, 0.0, 1.0);
        c.b = k <= 19.0 ? 0.0 : clamp(0.54320679 * log(k - 10.0) - 1.19625408, 0.0, 1.0);
    } else {
        c.r = clamp(1.29293618 * pow(k - 60.0, -0.1332047), 0.0, 1.0);
        c.g = clamp(1.12989086 * pow(k - 60.0, -0.0755148), 0.0, 1.0);
        c.b = 1.0;
    }
    return c;
}

void main() {
    vec3 col = texture(InSampler, texCoord).rgb * Exposure;

    // 目标白点除以参考白点（6500K），得到的才是"校正量"而不是"染色量"
    vec3 target = kelvinToRgb(Temperature);
    vec3 neutral = kelvinToRgb(6500.0);
    vec3 gain = target / max(neutral, vec3(1e-4));

    // 色调轴：绿与品红此消彼长，总亮度基本不变
    gain *= vec3(1.0 + Tint * 0.15, 1.0 - Tint * 0.3, 1.0 + Tint * 0.15);

    col *= mix(vec3(1.0), gain, Strength);
    fragColor = vec4(clamp(col, 0.0, 1.0), 1.0);
}
