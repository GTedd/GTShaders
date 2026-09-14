// 剧毒腐蚀 / Venom Corrosion
// 绿色的酸斑在画面上鼓起、爬开、把颜色蚀掉，边缘冒着更亮的气泡。
// 斑块的形状用「域扭曲」的 fbm：先拿一层噪声去推另一层的采样坐标，
// 得到的边界会自己往回卷，比直接阈值化的噪声有机得多。
//
// [en_us]
// Green acid blotches that eat away the picture's color.
// Green acid spots swell up, spread across the screen and corrode the color away, with brighter bubbles
// fizzing at their edges. The blotch shapes use "domain-warped" fbm: one noise layer pushes the sample
// coordinates of another, so the boundaries curl back on themselves and look far more organic than directly
// thresholded noise.
//
// @param name=Amount type=float min=0 max=1 default=0.55 zh_cn=腐蚀程度 en_us=Corrosion
// @param name=Speed type=float min=0 max=3 default=0.5 zh_cn=爬行速度 en_us=Crawl Speed
// @param name=Scale type=float min=1 max=20 default=4.5 zh_cn=斑块尺度 en_us=Blotch Scale
// @param name=Warp type=float min=0 max=3 default=1.2 zh_cn=边界卷曲 en_us=Domain Warp

// @group 颜色 / Color
// @param name=VenomColor type=color3 default=#5BD62A zh_cn=毒液色 en_us=Venom Color
// @param name=RimColor type=color3 default=#C8FF4F zh_cn=边缘色 en_us=Rim Color
// @param name=Darken type=float min=0 max=1 default=0.5 zh_cn=蚀暗程度 en_us=Eat Away
// @param name=Bubble type=float min=0 max=1 default=0.5 zh_cn=气泡 en_us=Bubbles

// @group 全局 / Global
// @param name=Sicken type=float min=0 max=1 default=0.35 zh_cn=整体泛绿 en_us=Global Sickness
// @param name=Pulse type=float min=0 max=4 default=1.2 zh_cn=脉动频率 en_us=Pulse Rate

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

float fbm(vec2 p) {
    float v = 0.0;
    float a = 0.5;
    for (int i = 0; i < 4; i++) {
        v += noise(p) * a;
        p *= 2.03;
        a *= 0.5;
    }
    return v;
}

void main() {
    vec2 asp = vec2(OutSize.x / max(OutSize.y, 1.0), 1.0);
    vec2 p = texCoord * asp * Scale;
    float t = GTTime * Speed;

    // 域扭曲：先用一层噪声算出偏移，再拿它去采第二层——边界因此会自己卷回来
    vec2 q = vec2(fbm(p + vec2(0.0, t)), fbm(p + vec2(5.2, 1.3 - t)));
    float n = fbm(p + q * Warp);

    float pulse = 0.5 + 0.5 * sin(GTTime * Pulse);
    float threshold = 1.0 - Amount * (0.85 + pulse * 0.15);
    float blob = smoothstep(threshold, threshold + 0.12, n);
    float rim = smoothstep(threshold - 0.06, threshold + 0.02, n)
              - smoothstep(threshold + 0.06, threshold + 0.16, n);

    vec3 src = texture(InSampler, texCoord).rgb;
    float g = dot(src, vec3(0.2126, 0.7152, 0.0722));

    vec3 col = src;
    col = mix(col, vec3(g) * (1.0 - Darken) * VenomColor, blob);
    col += RimColor * clamp(rim, 0.0, 1.0) * 0.8;

    // 气泡：稀疏的小亮圈，只长在斑块里
    if (Bubble > 0.001) {
        vec2 bp = texCoord * asp * 26.0;
        vec2 bi = floor(bp);
        float life = fract(hash(bi) + GTTime * 0.6);
        float rad = 0.12 + life * 0.3;
        float d = length(fract(bp) - 0.5);
        float ring = smoothstep(rad, rad - 0.06, d) - smoothstep(rad - 0.06, rad - 0.14, d);
        col += RimColor * ring * (1.0 - life) * blob * Bubble * 1.5;
    }

    col = mix(col, col * VenomColor, Sicken * (0.6 + pulse * 0.4));
    fragColor = vec4(col, 1.0);
}
