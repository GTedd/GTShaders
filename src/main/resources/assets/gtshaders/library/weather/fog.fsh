// 迷雾 / Volumetric Fog
// 体积雾在后处理里没有深度可用，所以拿「亮度」当深度的替身：
// 远处通常更亮（天空、雾散射），暗的多半是近处的实体和方块。
// 这是个粗糙但在 Minecraft 场景里意外好使的启发式。
//
// [en_us]
// Drifting volumetric fog that rolls across the scene.
// Volumetric fog has no depth to work with in post-processing, so brightness stands in for depth: distant things
// are usually brighter (sky, fog scattering), while dark pixels are mostly nearby entities and blocks.
// It is a crude heuristic, but it works surprisingly well in Minecraft scenes.
//
// @param name=FogColor type=color3 default=#B8C6D6 zh_cn=雾色 en_us=Fog Color
// @param name=Density type=float min=0 max=1 default=0.5 zh_cn=浓度 en_us=Density
// @param name=Height type=float min=0 max=1 default=0.45 zh_cn=雾层高度 en_us=Fog Height
// @param name=Rolling type=float min=0 max=1 default=0.35 zh_cn=翻卷 en_us=Rolling
// @param name=Speed type=float min=0 max=2 default=0.25 zh_cn=飘移速度 en_us=Drift Speed

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
    return noise(p) * 0.5 + noise(p * 2.1) * 0.28 + noise(p * 4.7) * 0.14 + noise(p * 9.3) * 0.08;
}

void main() {
    vec3 src = texture(InSampler, texCoord).rgb;
    float luma = dot(src, vec3(0.2126, 0.7152, 0.0722));

    // 亮度当深度：越亮认为越远，雾也就越厚
    float depth = clamp(pow(luma, 0.7), 0.0, 1.0);

    // 高度衰减：texCoord.y 向上为正，所以下方雾更厚
    float height = smoothstep(Height + 0.35, Height - 0.35, texCoord.y);

    float t = GTTime * Speed;
    float roll = fbm(texCoord * 3.0 + vec2(t, t * 0.3)) * Rolling;

    float fog = clamp((depth * 0.75 + height * 0.6 + roll * 0.5) * Density, 0.0, 1.0);
    fragColor = vec4(mix(src, FogColor, fog), 1.0);
}
