// 色相旋转 / Hue Rotate
// 整体转色相，并可以只对某一段色相生效——后者才是它真正有用的地方：
// 「把草地的绿色调成秋天的橙色，但天空保持不变」这种需求靠全局旋转做不到。
//
// [en_us]
// Rotates hue, globally or within a single hue range.
// Limiting it to one range of hues is where it becomes truly useful: a request like "turn the green grass autumn
// orange but leave the sky alone" can't be done with a global rotation.
//
// @param name=Shift type=float min=-1 max=1 default=0.15 zh_cn=色相旋转量 en_us=Hue Shift
// @param name=Selective type=bool default=0 zh_cn=只作用于选定色相 en_us=Selective
// @param name=TargetHue type=float min=0 max=1 default=0.33 zh_cn=目标色相 en_us=Target Hue
// @param name=Range type=float min=0.01 max=0.5 default=0.12 zh_cn=色相容差 en_us=Hue Range
// @param name=Saturation type=float min=0 max=2 default=1 zh_cn=饱和度 en_us=Saturation
// @param name=Value type=float min=0 max=2 default=1 zh_cn=明度 en_us=Value

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

void main() {
    vec3 hsv = rgb2hsv(texture(InSampler, texCoord).rgb);

    float weight = 1.0;
    if (Selective > 0.5) {
        // 色相是环形的：0.95 和 0.02 只差 0.07 而不是 0.93
        float d = abs(hsv.x - TargetHue);
        d = min(d, 1.0 - d);
        weight = 1.0 - smoothstep(Range * 0.5, Range, d);
    }

    hsv.x = fract(hsv.x + Shift * weight);
    hsv.y = clamp(hsv.y * mix(1.0, Saturation, weight), 0.0, 1.0);
    hsv.z = clamp(hsv.z * mix(1.0, Value, weight), 0.0, 1.0);
    fragColor = vec4(hsv2rgb(hsv), 1.0);
}
