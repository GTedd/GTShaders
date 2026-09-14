// 景深 / Depth of Field
// 后处理链拿不到深度缓冲，所以对焦平面只能由作者手动指定：
// 用「到焦点的屏幕距离」代替「到焦平面的世界距离」。
// 这对固定构图的截图/过场完全够用，但它不会跟着物体走——这是硬限制，不是没做完。
//
// 光斑形状用六边形采样核（模拟六片光圈叶片），比高斯圆更像真镜头。
//
// [en_us]
// Depth of field with a manually placed focus point.
// The post-processing chain has no access to the depth buffer, so the focus has to be set by hand by the
// author: "screen distance to the focus point" stands in for "world distance to the focal plane". That is
// plenty for screenshots and cutscenes with fixed framing, but it won't follow objects. That is a hard limit,
// not unfinished work.
//
// Bokeh uses a hexagonal sampling kernel (simulating six aperture blades), which looks more like a real lens
// than a Gaussian disc.
//
// @param name=Focus type=vec2 min=0 max=1 default=0.5,0.5 zh_cn=对焦点 en_us=Focus Point
// @param name=FocusRange type=float min=0.01 max=1 default=0.18 zh_cn=清晰范围 en_us=In-Focus Range
// @param name=Aperture type=float min=0 max=0.05 default=0.012 zh_cn=光圈(散景大小) en_us=Aperture
// @param name=Rings type=int min=1 max=4 default=3 zh_cn=采样环数 en_us=Sample Rings
// @param name=Highlight type=float min=0 max=3 default=1.2 zh_cn=高光增益 en_us=Bokeh Highlight

void main() {
    vec2 asp = vec2(OutSize.x / max(OutSize.y, 1.0), 1.0);
    float dist = length((texCoord - Focus) * asp);

    // 弥散圈半径：焦点处为 0，往外线性增大
    float coc = smoothstep(FocusRange, FocusRange + 0.45, dist) * Aperture;

    if (coc < 1e-5) {
        fragColor = texture(InSampler, texCoord);
        return;
    }

    vec3 acc = vec3(0.0);
    float total = 0.0;
    for (int ring = 1; ring <= 4; ring++) {
        if (ring > Rings) {
            break;
        }
        float rr = float(ring) / float(max(Rings, 1));
        int count = ring * 6;   // 每环 6 的倍数个采样点，天然铺成六边形
        for (int i = 0; i < 24; i++) {
            if (i >= count) {
                break;
            }
            float a = 6.28319 * float(i) / float(count);
            vec2 offset = vec2(cos(a), sin(a)) * rr * coc / asp;
            vec3 c = texture(InSampler, texCoord + offset).rgb;
            // 亮的采样点权重更高：散景里高光会明显盖过暗部，这一步是"光斑感"的来源
            float w = 1.0 + max(dot(c, vec3(0.333)) - 0.6, 0.0) * Highlight * 4.0;
            acc += c * w;
            total += w;
        }
    }
    // 别忘了中心那一个采样
    vec3 center = texture(InSampler, texCoord).rgb;
    acc += center;
    total += 1.0;

    fragColor = vec4(acc / total, 1.0);
}
