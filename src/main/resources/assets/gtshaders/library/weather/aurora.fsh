// 极光 / Aurora
// 极光是垂直的光幕：沿 x 方向缓慢起伏，沿 y 方向拉出长长的帘子。
// 颜色随高度从绿过渡到紫红——这是氧原子和氮分子在不同高度发光的真实结果，
// 单色极光会立刻显得很假。
//
// [en_us]
// Vertical curtains of aurora light drifting across the sky.
// Each curtain undulates slowly along x and stretches into long drapes along y.
// The color shifts from green to purple-red with altitude. That is the real result of oxygen atoms and
// nitrogen molecules glowing at different heights, and a single-color aurora looks fake right away.
//
// @param name=LowColor type=color3 default=#3BFF9E zh_cn=低空色 en_us=Low Color
// @param name=HighColor type=color3 default=#B44BFF zh_cn=高空色 en_us=High Color
// @param name=Intensity type=float min=0 max=1 default=0.6 zh_cn=强度 en_us=Intensity
// @param name=Curtains type=float min=1 max=12 default=4 zh_cn=光幕层数 en_us=Curtain Layers
// @param name=Speed type=float min=0 max=2 default=0.35 zh_cn=飘动速度 en_us=Drift Speed
// @param name=SkyOnly type=float min=0 max=1 default=0.55 zh_cn=只叠在天空 en_us=Sky Only

float hash(float x) {
    return fract(sin(x * 127.1) * 43758.5453);
}

float noise1(float x) {
    float i = floor(x);
    float f = fract(x);
    f = f * f * (3.0 - 2.0 * f);
    return mix(hash(i), hash(i + 1.0), f);
}

void main() {
    vec3 src = texture(InSampler, texCoord).rgb;
    float t = GTTime * Speed;

    float aurora = 0.0;
    vec3 tint = vec3(0.0);
    for (int i = 0; i < 12; i++) {
        if (float(i) >= Curtains) {
            break;
        }
        float fi = float(i);
        // 每层一条竖直的亮带，横向位置由一维噪声缓慢游走
        float center = 0.15 + 0.7 * hash(fi * 3.7);
        center += (noise1(t * 0.6 + fi * 5.0) - 0.5) * 0.35;
        // 带宽随高度变化，形成上宽下窄的帘子
        float width = 0.02 + 0.05 * (0.4 + texCoord.y);
        float band = smoothstep(width, 0.0, abs(texCoord.x - center));
        // 沿高度的褶皱
        band *= 0.55 + 0.45 * noise1(texCoord.y * 6.0 + t * 1.5 + fi * 9.0);
        // 底部淡出：极光不接地
        band *= smoothstep(0.15, 0.6, texCoord.y);
        aurora += band;
        tint += mix(LowColor, HighColor, clamp(texCoord.y, 0.0, 1.0)) * band;
    }
    if (aurora > 1e-4) {
        tint /= aurora;
    }

    // 只叠在天空：拿亮度当"是不是天空"的粗略判据
    float luma = dot(src, vec3(0.2126, 0.7152, 0.0722));
    float mask = mix(1.0, smoothstep(0.15, 0.55, luma), SkyOnly);

    fragColor = vec4(src + tint * clamp(aurora, 0.0, 2.0) * Intensity * mask, 1.0);
}
