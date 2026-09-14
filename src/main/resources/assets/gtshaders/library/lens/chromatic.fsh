// 色差 / Chromatic Aberration
// 两种色差分开做，因为成因不同：
//   横向（lateral）——三通道放大率不同，中心为零、越往外越明显；
//   纵向（longitudinal）——三通道焦平面不同，全画面均匀，表现为一律的紫绿边。
// 只做前者会缺少"镜头素质差"的那种通透感缺失。
//
// [en_us]
// Lateral plus longitudinal chromatic aberration.
// The two kinds are handled separately because they have different causes:
//   lateral: the three channels differ in magnification, zero at the center and stronger toward the edges;
//   longitudinal: the three channels differ in focal plane, uniform across the frame, seen as even
//   purple-green fringes.
// Doing only the first would miss the washed-out, low-clarity look of a "poor-quality lens".
//
// @param name=Lateral type=float min=0 max=0.05 default=0.008 zh_cn=横向色差 en_us=Lateral
// @param name=Longitudinal type=float min=0 max=0.01 default=0.0015 zh_cn=纵向色差 en_us=Longitudinal
// @param name=Direction type=vec2 min=-1 max=1 default=1,0 zh_cn=纵向偏移方向 en_us=Longitudinal Dir
// @param name=Samples type=int min=1 max=8 default=3 zh_cn=渐变采样数 en_us=Samples
// @param name=EdgeOnly type=float min=0 max=1 default=0.8 zh_cn=只作用于边缘 en_us=Edge Only

void main() {
    vec2 asp = vec2(OutSize.x / max(OutSize.y, 1.0), 1.0);
    vec2 c = texCoord - 0.5;
    float r = length(c * asp) / max(length(asp * 0.5), 1e-4);

    float w = mix(1.0, r * r, EdgeOnly);
    vec2 lat = c * Lateral * w;
    vec2 lon = normalize(Direction + 1e-5) / asp * Longitudinal;

    // 多点采样让色差呈渐变而不是三条硬边——真实色差是连续光谱造成的
    vec3 acc = vec3(0.0);
    float total = 0.0;
    for (int i = 0; i < 8; i++) {
        if (i >= Samples) {
            break;
        }
        float k = Samples > 1 ? float(i) / float(Samples - 1) - 0.5 : 0.0;
        float scale = 1.0 + k * 0.5;
        acc.r += texture(InSampler, texCoord + lat * scale + lon).r;
        acc.g += texture(InSampler, texCoord).g;
        acc.b += texture(InSampler, texCoord - lat * scale - lon).b;
        total += 1.0;
    }
    fragColor = vec4(acc / total, 1.0);
}
