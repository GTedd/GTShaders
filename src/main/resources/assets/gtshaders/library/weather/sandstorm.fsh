// 沙尘暴 / Sandstorm
// 横向高速流动的沙幕 + 能见度衰减。沙粒必须是「横向拉长」的：
// 各向同性的噪点看起来是电视雪花，沿风向拉伸之后才是被风吹的沙。
//
// [en_us]
// A fast sideways wall of blowing sand that cuts visibility.
// A curtain of sand streams sideways at high speed while visibility drops. The grains must be stretched
// horizontally: isotropic noise looks like TV static, and only after stretching it along the wind direction does
// it read as wind-blown sand.
//
// @param name=Density type=float min=0 max=1 default=0.6 zh_cn=沙尘浓度 en_us=Density
// @param name=SandColor type=color3 default=#C9A063 zh_cn=沙色 en_us=Sand Color
// @param name=WindSpeed type=float min=0 max=8 default=3 zh_cn=风速 en_us=Wind Speed
// @param name=Stretch type=float min=1 max=30 default=10 zh_cn=横向拉伸 en_us=Streak Stretch
// @param name=Gust type=float min=0 max=1 default=0.4 zh_cn=阵风起伏 en_us=Gust
// @param name=Visibility type=float min=0 max=1 default=0.45 zh_cn=能见度损失 en_us=Visibility Loss

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

void main() {
    float t = GTTime * WindSpeed;
    // 阵风：整体强度随时间起伏，恒定的沙幕会显得像静态贴图
    float gust = 1.0 + Gust * sin(GTTime * 0.7) * sin(GTTime * 0.23);

    // 三层不同速度的沙幕，近的快、远的慢，叠出景深
    float sand = 0.0;
    sand += noise(vec2(texCoord.x * Stretch - t, texCoord.y * Stretch * 6.0)) * 0.5;
    sand += noise(vec2(texCoord.x * Stretch * 2.0 - t * 1.6, texCoord.y * Stretch * 11.0)) * 0.3;
    sand += noise(vec2(texCoord.x * Stretch * 0.5 - t * 0.6, texCoord.y * Stretch * 3.0)) * 0.2;
    sand *= gust;

    vec3 col = texture(InSampler, texCoord).rgb;

    // 能见度：远景先消失。没有深度可用，就拿"离画面中心的距离"当粗略的远近代理
    float haze = Visibility * Density * (0.55 + 0.45 * length(texCoord - 0.5) * 1.4);
    col = mix(col, SandColor * (0.55 + 0.45 * sand), clamp(haze, 0.0, 1.0));

    // 掠过的沙粒本身
    col += SandColor * smoothstep(0.62, 0.9, sand) * Density * 0.5;
    fragColor = vec4(col, 1.0);
}
