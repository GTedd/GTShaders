// 灼烧 / Burning
// 火焰糊在脸上的两件事：底部往上舔的火舌，以及热空气造成的折射抖动。
// 火舌用"噪声 - 高度"取阈值，噪声整体上移就成了往上窜的动画。
//
// [en_us]
// Fire in your face: licking flames and heat shimmer.
// Two things happen when fire is right in your face: flames licking up from the bottom, and refraction wobble
// from the hot air. The flames threshold "noise - height", so scrolling the whole noise upward animates them
// shooting up.
//
// @param name=Heat type=float min=0 max=1 default=0.75 zh_cn=火势 en_us=Heat
// @param name=FlameColor type=color3 default=#FF7A18 zh_cn=火焰色 en_us=Flame Color
// @param name=CoreColor type=color3 default=#FFE38A zh_cn=焰心色 en_us=Core Color
// @param name=Distort type=float min=0 max=0.03 default=0.008 zh_cn=热扭曲 en_us=Heat Distort
// @param name=Speed type=float min=0 max=6 default=2.2 zh_cn=窜升速度 en_us=Rise Speed

float hash(vec2 p) {
    return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453);
}

float noise(vec2 p) {
    vec2 i = floor(p);
    vec2 f = fract(p);
    f = f * f * (3.0 - 2.0 * f);
    return mix(mix(hash(i), hash(i + vec2(1.0, 0.0)), f.x),
               mix(hash(i + vec2(0.0, 1.0)), hash(i + vec2(1.0, 1.0)), f.x), f.y);
}

// 分形噪声：叠三层不同尺度，火焰边缘才有大轮廓 + 小碎屑的层次
float fbm(vec2 p) {
    return noise(p) * 0.5 + noise(p * 2.1) * 0.3 + noise(p * 4.3) * 0.2;
}

void main() {
    float t = GTTime * Speed;

    vec2 uv = texCoord;
    uv += vec2(fbm(texCoord * 6.0 + vec2(0.0, t)) - 0.5) * Distort * Heat;
    vec3 col = texture(InSampler, uv).rgb;

    // texCoord.y 向上为正，所以底部是 y 小的一侧
    float h = texCoord.y;
    float f = fbm(vec2(texCoord.x * 5.0, texCoord.y * 3.0 - t * 0.8));
    float flame = smoothstep(h, h - 0.35, f * Heat * 1.6);

    col = mix(col, FlameColor, clamp(flame, 0.0, 1.0) * 0.85);
    col = mix(col, CoreColor, clamp(flame - 0.6, 0.0, 1.0) * 1.6);
    col += FlameColor * Heat * 0.08;   // 整体的余温，让画面不至于只有底部变化
    fragColor = vec4(col, 1.0);
}
