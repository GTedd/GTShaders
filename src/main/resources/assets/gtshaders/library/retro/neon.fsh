// 霓虹描边 / Neon Outline
// 边缘检测 + 按色相上色 + 辉光。辉光是灵魂：霓虹管的光会溢出到管子外面，
// 所以这里对边缘图再做一次多方向扩散采样，而不是只把线画亮。
//
// [en_us]
// Neon outlines: edge detection, hue coloring and glow.
// The glow is the soul of it: light from a neon tube spills out past the tube, so the edge map gets another
// multi-directional spread sample here instead of just drawing the lines brighter.
//
// @param name=EdgeGain type=float min=0 max=8 default=3 zh_cn=描边强度 en_us=Edge Gain
// @param name=Threshold type=float min=0 max=0.5 default=0.06 zh_cn=阈值 en_us=Threshold
// @param name=Glow type=float min=0 max=0.02 default=0.006 zh_cn=辉光半径 en_us=Glow Radius
// @param name=NeonColor type=color3 default=#FF3CAC zh_cn=霓虹色 en_us=Neon Color
// @param name=HueShift type=float min=0 max=1 default=0.6 zh_cn=按位置变色 en_us=Hue By Position
// @param name=Darkness type=float min=0 max=1 default=0.85 zh_cn=背景压暗 en_us=Background Dim

float luma(vec3 c) {
    return dot(c, vec3(0.2126, 0.7152, 0.0722));
}

// 拿到某点的边缘强度（中心差分，够用且只要四次采样）
float edgeAt(vec2 uv, vec2 texel) {
    float lx = luma(texture(InSampler, uv + vec2(texel.x, 0.0)).rgb)
             - luma(texture(InSampler, uv - vec2(texel.x, 0.0)).rgb);
    float ly = luma(texture(InSampler, uv + vec2(0.0, texel.y)).rgb)
             - luma(texture(InSampler, uv - vec2(0.0, texel.y)).rgb);
    return length(vec2(lx, ly));
}

// 简易 HSV -> RGB，只用来给描边上渐变色
vec3 hue(float h) {
    vec3 k = fract(vec3(h) + vec3(0.0, 2.0 / 3.0, 1.0 / 3.0));
    return clamp(abs(k * 6.0 - 3.0) - 1.0, 0.0, 1.0);
}

void main() {
    vec2 texel = 1.0 / max(OutSize, vec2(1.0));
    vec3 src = texture(InSampler, texCoord).rgb;

    float e = smoothstep(Threshold, Threshold * 3.0, edgeAt(texCoord, texel) * EdgeGain);

    // 辉光：绕一圈再取几次边缘，把线"吹胖"
    float bloom = 0.0;
    for (int i = 0; i < 8; i++) {
        float a = float(i) * 0.7854;
        bloom += edgeAt(texCoord + vec2(cos(a), sin(a)) * Glow, texel);
    }
    bloom = smoothstep(Threshold, Threshold * 4.0, bloom / 8.0 * EdgeGain);

    vec3 tint = mix(NeonColor, hue(texCoord.x * 0.6 + texCoord.y * 0.3 + GTTime * 0.05), HueShift);

    vec3 col = src * (1.0 - Darkness);
    col += tint * (e * 1.6 + bloom * 0.8);
    fragColor = vec4(col, 1.0);
}
