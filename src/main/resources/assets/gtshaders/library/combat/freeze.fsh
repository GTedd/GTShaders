// 冰冻 / Freeze
// 冰霜从画面四边往里结：用 Voronoi（最近特征点距离）画晶格，
// 因为真实霜花本来就是从若干凝结核向外长的，规则网格反而不像。
//
// [en_us]
// Frost creeping in from the screen edges.
// The frost forms inward from all four edges. The ice crystals are drawn with Voronoi (distance to the nearest
// feature point), because real frost grows outward from a handful of nucleation points; a regular grid looks
// wrong.
//
// @param name=Frost type=float min=0 max=1 default=0.65 zh_cn=结霜程度 en_us=Frost
// @param name=IceColor type=color3 default=#BFE8FF zh_cn=冰色 en_us=Ice Color
// @param name=Cells type=float min=4 max=40 default=14 zh_cn=晶格密度 en_us=Cell Density
// @param name=Refract type=float min=0 max=0.02 default=0.006 zh_cn=折射 en_us=Refraction
// @param name=Chill type=float min=0 max=1 default=0.5 zh_cn=整体冷调 en_us=Cool Grade

vec2 hash2(vec2 p) {
    return fract(sin(vec2(dot(p, vec2(127.1, 311.7)), dot(p, vec2(269.5, 183.3)))) * 43758.5453);
}

// 返回到最近特征点的距离，0 表示正落在核心上
float voronoi(vec2 p) {
    vec2 g = floor(p);
    vec2 f = fract(p);
    float best = 8.0;
    for (int y = -1; y <= 1; y++) {
        for (int x = -1; x <= 1; x++) {
            vec2 o = vec2(float(x), float(y));
            vec2 c = o + hash2(g + o) - f;
            best = min(best, dot(c, c));
        }
    }
    return sqrt(best);
}

void main() {
    // 四边到中心的距离场：边缘先结霜，越往中间越晚
    vec2 d = abs(texCoord - 0.5) * 2.0;
    float edge = max(d.x, d.y);
    float front = smoothstep(1.0 - Frost * 1.15, 1.0, edge);

    float v = voronoi(texCoord * Cells);
    float crystal = smoothstep(0.42, 0.02, v) * front;

    // 结霜处顺着晶格方向折射一点，像隔着一层冰看
    vec2 uv = texCoord + vec2(v - 0.3) * Refract * front;
    vec3 col = texture(InSampler, uv).rgb;

    float luma = dot(col, vec3(0.2126, 0.7152, 0.0722));
    col = mix(col, mix(col, IceColor * (0.4 + luma), 0.6), Chill);
    col = mix(col, IceColor, clamp(crystal, 0.0, 1.0) * 0.75);
    col += crystal * 0.12;   // 晶格棱线上的高光
    fragColor = vec4(col, 1.0);
}
