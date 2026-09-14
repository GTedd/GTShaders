// 传送闪烁 / Teleport Blink
// 画面被拆成竖向的光条散开、消失、再瞬间重组，中间夹一次紫色的过曝闪。
// 拆解和重组不是对称的：散开慢一点、合拢快到几乎瞬移，这样才像"被拽走"而不是"淡入淡出"。
//
// [en_us]
// The screen breaks into light strips and snaps back.
// The picture splits into vertical strips of light that scatter, vanish and then instantly reassemble, with a
// purple overexposed flash in between. Breaking apart and reassembling are not symmetric: scattering is a bit
// slower, and closing up is so fast it is almost instant. That is what makes it feel like "being yanked away"
// rather than a fade out and in.
//
// @param name=Period type=float min=0.5 max=20 default=3.5 zh_cn=触发周期(秒) en_us=Period
// @param name=Duration type=float min=0.1 max=3 default=0.7 zh_cn=一次用时(秒) en_us=Blink Duration
// @param name=Manual type=float min=0 max=1 default=0 zh_cn=手动进度 en_us=Manual Progress desc_zh_cn=大于 0 时接管周期，用来把闪烁停在某一帧上截图 desc_en_us=Overrides the cycle when above 0, so you can freeze a frame

// @group 拆解 / Shatter
// @param name=Slices type=float min=4 max=200 default=48 zh_cn=光条数 en_us=Slice Count
// @param name=Spread type=float min=0 max=0.6 default=0.2 zh_cn=散开距离 en_us=Spread
// @param name=Stretch type=float min=0 max=1 default=0.5 zh_cn=纵向拉伸 en_us=Vertical Stretch

// @group 外观 / Look
// @param name=WarpColor type=color3 default=#B45CFF zh_cn=传送色 en_us=Warp Color
// @param name=Flash type=float min=0 max=4 default=1.6 zh_cn=中段闪光 en_us=Mid Flash
// @param name=EdgeGlow type=float min=0 max=3 default=1.2 zh_cn=条边发光 en_us=Slice Glow

float hash11(float p) {
    return fract(sin(p * 73.156) * 42351.9137);
}

void main() {
    float t = Manual > 0.001
        ? Manual
        : clamp(mod(GTTime, max(Period, 0.2)) / max(Duration, 0.05), 0.0, 1.0);

    // 散开慢、合拢快：0→0.55 是拆，0.55→1 是合，后半段用 pow 压缩
    float k = t < 0.55
        ? t / 0.55
        : pow(1.0 - (t - 0.55) / 0.45, 2.6);
    k = clamp(k, 0.0, 1.0);

    float cell = texCoord.x * Slices;
    float id = floor(cell);
    float f = fract(cell);

    float dir = hash11(id * 1.93) > 0.5 ? 1.0 : -1.0;
    float mag = pow(hash11(id * 5.71), 1.6);
    vec2 uv = texCoord;
    uv.y += dir * mag * Spread * k;
    // 纵向拉伸：条被拽长，像信号被拉薄
    uv.y = (uv.y - 0.5) / (1.0 + Stretch * k * mag * 2.0) + 0.5;

    vec3 col;
    if (uv.y < 0.0 || uv.y > 1.0) {
        col = vec3(0.0);
    } else {
        col = texture(InSampler, uv).rgb;
        // 拆开后整体压暗，中段几乎全黑，重组时再回来
        col *= 1.0 - k * 0.55;
    }

    // 条与条之间的缝隙发紫光
    float seam = smoothstep(0.5, 0.0, abs(f - 0.5) * 2.0);
    col += WarpColor * (1.0 - seam) * k * EdgeGlow * 0.6;
    // 中段一次过曝
    col += WarpColor * pow(k, 4.0) * Flash;

    fragColor = vec4(col, 1.0);
}
