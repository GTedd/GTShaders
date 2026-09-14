// 反相 / Invert
// 三种反相各有用途：RGB 反相是负片；只反亮度保色相能得到"诡异但可读"的画面；
// 只反色相则像热成像的错觉。做成一个效果三种模式，比拆三个更好挑。
//
// [en_us]
// Inverts RGB, brightness only, or hue only.
// Each of the three inversions has its use: RGB inversion is a film negative, inverting only brightness while
// keeping hue gives an "eerie but readable" picture, and inverting only hue looks like an illusion of thermal
// imaging. One effect with three modes is easier to pick from than three separate effects.
//
// @param name=Mode type=int min=0 max=2 default=0 zh_cn=模式(0RGB 1亮度 2色相) en_us=Mode
// @param name=Amount type=float min=0 max=1 default=1 zh_cn=强度 en_us=Amount
// @param name=Threshold type=float min=0 max=1 default=0 zh_cn=只反超过此亮度 en_us=Above Luma

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
    vec3 src = texture(InSampler, texCoord).rgb;
    float luma = dot(src, vec3(0.2126, 0.7152, 0.0722));

    vec3 flipped;
    if (Mode == 1) {
        // 只反亮度：把 HSV 的 V 取反，色相饱和度不动
        vec3 hsv = rgb2hsv(src);
        hsv.z = 1.0 - hsv.z;
        flipped = hsv2rgb(hsv);
    } else if (Mode == 2) {
        // 只反色相：转半圈
        vec3 hsv = rgb2hsv(src);
        hsv.x = fract(hsv.x + 0.5);
        flipped = hsv2rgb(hsv);
    } else {
        flipped = 1.0 - src;
    }

    // 阈值：只对足够亮的地方反相，可以做出"高光变负片"的效果
    float mask = Threshold <= 0.0 ? 1.0 : smoothstep(Threshold - 0.02, Threshold + 0.02, luma);
    fragColor = vec4(mix(src, flipped, Amount * mask), 1.0);
}
