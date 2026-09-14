// 凋零 / Wither
// 生命被抽走的画面：先掉饱和，再从四周长进来一层焦黑的"血管"。
// 血管用两层不同尺度的噪声相乘取阈值——单层噪声只会得到均匀的斑点，
// 相乘之后细节集中在少数几条上，才有蔓延的走向感。
//
// [en_us]
// Life draining away: color fades, charred veins creep in.
// Saturation drops first, then a layer of charred black "veins" grows in from the edges. The veins multiply two
// noise layers of different scales and threshold the result. A single noise layer only gives even blotches;
// multiplied, the detail concentrates into a few strands, which gives them a sense of spreading direction.
//
// @param name=Decay type=float min=0 max=1 default=0.6 zh_cn=凋零程度 en_us=Decay
// @param name=VeinColor type=color3 default=#160E14 zh_cn=血管色 en_us=Vein Color
// @param name=VeinScale type=float min=2 max=40 default=12 zh_cn=血管密度 en_us=Vein Scale
// @param name=Creep type=float min=0 max=1 default=0.35 zh_cn=蔓延速度 en_us=Creep Speed

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
    vec3 src = texture(InSampler, texCoord).rgb;
    float luma = dot(src, vec3(0.2126, 0.7152, 0.0722));

    vec3 col = mix(src, vec3(luma) * vec3(0.86, 0.82, 0.88), Decay);
    col *= mix(1.0, 0.55, Decay);

    // 离中心越远越先被侵蚀，加上时间偏移就成了"往里蔓延"
    float edge = length(texCoord - 0.5) * 1.4;
    float front = Decay * 1.5 - (1.0 - edge) + sin(GTTime * Creep) * 0.05;

    float n = noise(texCoord * VeinScale) * noise(texCoord * VeinScale * 2.7 + 13.0);
    float vein = smoothstep(0.22, 0.05, n) * smoothstep(0.0, 0.35, front);

    col = mix(col, VeinColor, clamp(vein, 0.0, 1.0));
    fragColor = vec4(col, 1.0);
}
