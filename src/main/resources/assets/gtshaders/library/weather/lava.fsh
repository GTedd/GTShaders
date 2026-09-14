// 岩浆 / Lava
// 视野被熔岩糊住：极浓的橙红、强烈的热扭曲，以及缓慢翻涌的暗色浮渣。
// 浮渣是关键——纯橙色一片会像调色滤镜，有了缓慢流动的暗块才有黏稠感。
//
// [en_us]
// A view smothered in molten lava.
// Thick orange-red, strong heat distortion and slowly churning dark crust.
// The crust is the key: a flat sheet of orange looks like a color filter, and only slowly flowing dark patches
// make it feel viscous.
//
// @param name=Thickness type=float min=0 max=1 default=0.8 zh_cn=浓稠度 en_us=Thickness
// @param name=HotColor type=color3 default=#FF6A00 zh_cn=熔岩色 en_us=Lava Color
// @param name=CrustColor type=color3 default=#2A0B05 zh_cn=浮渣色 en_us=Crust Color
// @param name=Distort type=float min=0 max=0.06 default=0.02 zh_cn=热扭曲 en_us=Heat Distort
// @param name=Flow type=float min=0 max=2 default=0.35 zh_cn=翻涌速度 en_us=Flow Speed

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
    return noise(p) * 0.55 + noise(p * 2.3) * 0.3 + noise(p * 5.1) * 0.15;
}

void main() {
    float t = GTTime * Flow;

    float warp = fbm(texCoord * 5.0 + vec2(0.0, t));
    vec2 uv = texCoord + (vec2(warp, fbm(texCoord * 5.0 + 31.0 - vec2(0.0, t))) - 0.5)
                         * Distort * Thickness;
    vec3 col = texture(InSampler, uv).rgb;

    float luma = dot(col, vec3(0.2126, 0.7152, 0.0722));
    // 先把画面整体推向熔岩色，亮度决定「离热源多近」
    col = mix(col, HotColor * (0.35 + luma * 1.4), Thickness * 0.85);

    // 浮渣：低频噪声取阈值，缓慢漂移。乘一层高频让边界不至于太规整
    float crust = smoothstep(0.52, 0.68, fbm(texCoord * 3.2 + vec2(t * 0.3, -t * 0.5)));
    crust *= smoothstep(0.35, 0.6, fbm(texCoord * 9.0 - t * 0.2));
    col = mix(col, CrustColor, crust * Thickness * 0.8);

    // 裂缝：浮渣边缘漏出的高温
    col += HotColor * smoothstep(0.48, 0.52, crust) * (1.0 - crust) * Thickness * 2.0;
    fragColor = vec4(col, 1.0);
}
