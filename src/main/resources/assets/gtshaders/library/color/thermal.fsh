// 热成像 / Thermal Vision
// 把亮度映射到伪彩色查找表。这里的调色板是手写的五段渐变
// （黑 → 紫 → 红 → 橙 → 白），对应常见的 ironbow 配色。
// 另外加了一点"边缘更热"的处理，让轮廓在热图里仍然可辨。
//
// [en_us]
// Thermal vision: brightness mapped to false color.
// Brightness is looked up in a false-color table. The palette here is a hand-written five-stop gradient
// (black → purple → red → orange → white), matching the common ironbow palette.
// There is also a touch of "edges run hotter", so outlines stay recognizable in the heat map.
//
// @param name=Palette type=int min=0 max=2 default=0 zh_cn=配色(0铁红 1彩虹 2白热) en_us=Palette
// @param name=Contrast type=float min=0.5 max=3 default=1.4 zh_cn=对比度 en_us=Contrast
// @param name=Bias type=float min=-0.5 max=0.5 default=0 zh_cn=温度偏移 en_us=Temperature Bias
// @param name=EdgeHeat type=float min=0 max=1 default=0.25 zh_cn=轮廓增温 en_us=Edge Heat
// @param name=Noise type=float min=0 max=0.2 default=0.03 zh_cn=传感器噪点 en_us=Sensor Noise

float hash(vec2 p) {
    return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453);
}

float lum(vec3 c) {
    return dot(c, vec3(0.2126, 0.7152, 0.0722));
}

// 五段线性插值实现的 ironbow 调色板
vec3 ironbow(float t) {
    vec3 c0 = vec3(0.0, 0.0, 0.0);
    vec3 c1 = vec3(0.22, 0.0, 0.42);
    vec3 c2 = vec3(0.78, 0.06, 0.22);
    vec3 c3 = vec3(1.0, 0.62, 0.0);
    vec3 c4 = vec3(1.0, 1.0, 0.88);
    t = clamp(t, 0.0, 1.0) * 4.0;
    if (t < 1.0) {
        return mix(c0, c1, t);
    }
    if (t < 2.0) {
        return mix(c1, c2, t - 1.0);
    }
    if (t < 3.0) {
        return mix(c2, c3, t - 2.0);
    }
    return mix(c3, c4, t - 3.0);
}

vec3 rainbow(float t) {
    vec3 k = fract(vec3(0.66 - clamp(t, 0.0, 1.0) * 0.66) + vec3(0.0, 2.0 / 3.0, 1.0 / 3.0));
    return clamp(abs(k * 6.0 - 3.0) - 1.0, 0.0, 1.0);
}

void main() {
    vec2 texel = 1.0 / max(OutSize, vec2(1.0));
    float t = lum(texture(InSampler, texCoord).rgb);

    // 轮廓增温：让物体边界在伪彩色下依然读得出形状
    float lx = lum(texture(InSampler, texCoord + vec2(texel.x, 0.0)).rgb)
             - lum(texture(InSampler, texCoord - vec2(texel.x, 0.0)).rgb);
    float ly = lum(texture(InSampler, texCoord + vec2(0.0, texel.y)).rgb)
             - lum(texture(InSampler, texCoord - vec2(0.0, texel.y)).rgb);
    t += length(vec2(lx, ly)) * EdgeHeat * 2.0;

    t = clamp((t - 0.5) * Contrast + 0.5 + Bias, 0.0, 1.0);
    t += (hash(gl_FragCoord.xy + GTTime * 53.0) - 0.5) * Noise;

    vec3 col;
    if (Palette == 1) {
        col = rainbow(t);
    } else if (Palette == 2) {
        col = vec3(clamp(t, 0.0, 1.0));
    } else {
        col = ironbow(t);
    }
    fragColor = vec4(col, 1.0);
}
