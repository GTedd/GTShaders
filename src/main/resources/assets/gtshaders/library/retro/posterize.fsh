// 色调分离 / Posterize
// 把连续色阶砍成有限几级。直接对 RGB 量化会让色相飘（比如橙色掉成红或黄），
// 所以这里在 HSV 空间量化：亮度分级最狠、饱和度次之、色相基本不动。
//
// [en_us]
// Cuts continuous tones down to a few levels.
// Quantizing RGB directly makes hues drift (orange falling to red or yellow, for example), so this quantizes in
// HSV space: brightness gets the coarsest steps, saturation less so, and hue is left mostly untouched.
//
// @param name=ValueLevels type=float min=2 max=16 default=5 zh_cn=明度级数 en_us=Value Levels
// @param name=SatLevels type=float min=2 max=16 default=4 zh_cn=饱和度级数 en_us=Saturation Levels
// @param name=HueLevels type=float min=3 max=32 default=12 zh_cn=色相级数 en_us=Hue Levels
// @param name=Boost type=float min=0 max=2 default=1.2 zh_cn=饱和度提升 en_us=Saturation Boost

vec3 rgb2hsv(vec3 c) {
    vec4 k = vec4(0.0, -1.0 / 3.0, 2.0 / 3.0, -1.0);
    vec4 p = c.g < c.b ? vec4(c.bg, k.wz) : vec4(c.gb, k.xy);
    vec4 q = c.r < p.x ? vec4(p.xyw, c.r) : vec4(c.r, p.yzx);
    float d = q.x - min(q.w, q.y);
    return vec3(abs(q.z + (q.w - q.y) / (6.0 * d + 1e-10)), d / (q.x + 1e-10), q.x);
}

vec3 hsv2rgb(vec3 c) {
    vec3 k = fract(vec3(c.x) + vec3(0.0, 2.0 / 3.0, 1.0 / 3.0));
    vec3 rgb = clamp(abs(k * 6.0 - 3.0) - 1.0, 0.0, 1.0);
    return c.z * mix(vec3(1.0), rgb, c.y);
}

float quantize(float v, float levels) {
    float n = max(levels - 1.0, 1.0);
    return floor(v * n + 0.5) / n;
}

void main() {
    vec3 hsv = rgb2hsv(texture(InSampler, texCoord).rgb);
    hsv.x = quantize(hsv.x, HueLevels);
    hsv.y = clamp(quantize(hsv.y, SatLevels) * Boost, 0.0, 1.0);
    hsv.z = quantize(hsv.z, ValueLevels);
    fragColor = vec4(hsv2rgb(hsv), 1.0);
}
