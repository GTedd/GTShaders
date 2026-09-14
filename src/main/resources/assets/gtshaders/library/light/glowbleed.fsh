// 辉光溢出 / Glow Bleed
// 亮的东西往四周洇开、把周围染上颜色，暗处几乎不受影响——过曝胶片和老式摄像管的通病，
// 用在氛围镜头上非常好使。
//
// 和普通泛光的区别：这里洇出来的<b>带着源头的颜色</b>并且会随时间缓慢脉动，
// 所以红色灯笼洇出的是红光，不是一团白雾。
//
// [en_us]
// Bright areas bleed colored glow into their surroundings.
// Bright things spread outward and tint what is around them, while dark areas are barely affected. It is the
// classic flaw of overexposed film and old camera tubes, and it works great for atmospheric shots.
//
// How it differs from plain bloom: the glow that bleeds out <b>keeps the color of its source</b> and slowly
// pulses over time, so a red lantern bleeds red light instead of a white haze.
//
// @param name=Threshold type=float min=0 max=1 default=0.6 zh_cn=起效亮度 en_us=Threshold
// @param name=Adapt type=float min=0 max=1 default=0.8 zh_cn=自动适应亮度 en_us=Auto Adapt desc_zh_cn=按当前画面的平均亮度自动调整阈值。关掉之后昏暗场景里完全不溢出 desc_en_us=Rescales the threshold by the frame's average brightness; with it off nothing bleeds in dark scenes
// @param name=Radius type=float min=0.002 max=0.08 default=0.02 zh_cn=溢出半径 en_us=Bleed Radius
// @param name=Rings type=float min=1 max=4 default=3 zh_cn=采样圈数 en_us=Sample Rings

// @group 外观 / Look
// @param name=Gain type=float min=0 max=4 default=1.3 zh_cn=溢出强度 en_us=Gain
// @param name=Saturate type=float min=0 max=3 default=1.5 zh_cn=溢出饱和 en_us=Bleed Saturation
// @param name=Pulse type=float min=0 max=1 default=0.25 zh_cn=脉动 en_us=Pulse
// @param name=PulseRate type=float min=0.1 max=4 default=0.7 zh_cn=脉动频率 en_us=Pulse Rate

// @group 底图 / Base
// @param name=Contrast type=float min=0.5 max=2 default=1.05 zh_cn=底图对比 en_us=Base Contrast
// @param name=Lift type=float min=0 max=0.3 default=0.02 zh_cn=暗部抬升 en_us=Shadow Lift

// 画面平均亮度：4×4 稀疏采样。绝对阈值默认场景是白天，进洞之后没东西够得着，
// 效果彻底静默——玩家转个视角就「好了」，非常像随机失灵。后处理拿不到 mip，只能这样估。
float sceneLuma() {
    float s = 0.0;
    for (int i = 0; i < 16; i++) {
        vec2 p = (vec2(float(i % 4), float(i / 4)) + 0.5) * 0.25;
        s += dot(texture(InSampler, p).rgb, vec3(0.2126, 0.7152, 0.0722));
    }
    return s / 16.0;
}

void main() {
    vec3 src = texture(InSampler, texCoord).rgb;
    vec2 aspFix = vec2(OutSize.y / max(OutSize.x, 1.0), 1.0);

    // 只算一次：环形采样循环里每取一点都算的话要多跑几百次
    float rel = clamp(sceneLuma() * 1.35 + 0.05, 0.03, 0.92);
    float th = mix(Threshold, rel, clamp(Adapt, 0.0, 1.0));

    float rings = max(floor(Rings), 1.0);
    vec3 acc = vec3(0.0);
    float total = 0.0;
    for (int r = 1; r <= 4; r++) {
        if (float(r) > rings) {
            break;
        }
        float rad = Radius * float(r) / rings;
        float w = 1.0 / float(r);
        for (int i = 0; i < 8; i++) {
            // 每圈错开半个角度，采样点才不会在同一条射线上重叠
            float a = (float(i) + 0.5 * float(r)) / 8.0 * 6.2831853;
            vec2 uv = clamp(texCoord + vec2(cos(a), sin(a)) * rad * aspFix, vec2(0.0), vec2(1.0));
            vec3 c = texture(InSampler, uv).rgb;
            float l = dot(c, vec3(0.2126, 0.7152, 0.0722));
            acc += c * smoothstep(th, min(th + 0.25, 1.0), l) * w;
            total += w;
        }
    }
    acc /= max(total, 1e-4);

    // 溢出的部分单独提饱和：这样红灯洇出红光，而不是洇出一团白
    float g = dot(acc, vec3(0.2126, 0.7152, 0.0722));
    acc = max(g + (acc - g) * Saturate, vec3(0.0));

    float pulse = 1.0 + sin(GTTime * PulseRate * 6.2831853) * Pulse * 0.4;

    vec3 col = (src - 0.5) * Contrast + 0.5 + Lift;
    col += acc * Gain * pulse;
    fragColor = vec4(max(col, vec3(0.0)), 1.0);
}
