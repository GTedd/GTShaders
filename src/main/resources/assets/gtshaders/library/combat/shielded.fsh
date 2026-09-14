// 能量护盾 / Energy Shield
// 六边形蜂窝壳。六边形铺砌用「两套错开半格的矩形网格、取更近的那个中心」来求，
// 比拿正方形网格硬凑要正得多，接缝处也不会出现锯齿。
//
// [en_us]
// A hexagonal honeycomb energy shield.
// The hex tiling takes two rectangular grids offset by half a cell and picks the nearer center. That comes out far
// more regular than forcing it from a square grid, and the seams don't get jagged.
//
// @param name=ShieldColor type=color3 default=#4FD8FF zh_cn=护盾色 en_us=Shield Color
// @param name=Density type=float min=4 max=60 default=18 zh_cn=蜂窝密度 en_us=Cell Density
// @param name=Edge type=float min=0.01 max=0.4 default=0.12 zh_cn=网格粗细 en_us=Edge Width
// @param name=Impact type=float min=0 max=1 default=0.35 zh_cn=受击强度 en_us=Impact
// @param name=Ripple type=float min=0 max=6 default=2 zh_cn=能量流速 en_us=Energy Flow
// @param name=Rim type=float min=0 max=2 default=1.1 zh_cn=边缘聚拢 en_us=Rim Focus

// 返回到最近六边形中心的偏移，以及一个稳定的格子编号
vec3 hexInfo(vec2 p) {
    vec2 s = vec2(1.0, 1.7320508);
    vec2 a = mod(p, s) - s * 0.5;
    vec2 b = mod(p - s * 0.5, s) - s * 0.5;
    vec2 g = dot(a, a) < dot(b, b) ? a : b;
    vec2 id = p - g;
    return vec3(g, dot(id, vec2(7.13, 3.71)));
}

void main() {
    vec2 asp = vec2(OutSize.x / max(OutSize.y, 1.0), 1.0);
    vec2 p = (texCoord - 0.5) * asp;
    float r = length(p) / max(length(asp * 0.5), 1e-4);

    vec3 hex = hexInfo(p * Density);
    // 六边形的内切距离：越靠近边线值越大
    float d = max(abs(hex.x) * 1.1547 + abs(hex.y) * 0.6667, abs(hex.y) * 1.3333);
    float grid = smoothstep(0.5 - Edge, 0.5, d);

    // 能量沿半径往外流，每个格子带自己的相位，整片才不会同步闪
    float flow = 0.5 + 0.5 * sin(r * 10.0 - GTTime * Ripple + hex.z);

    // 护盾贴在视野边缘，中间基本透明，否则挡视线
    float rim = smoothstep(0.25, 1.0, r) * Rim;
    float glow = (grid * (0.5 + 0.5 * flow) + Impact * flow * 0.35) * rim;

    vec3 col = texture(InSampler, texCoord).rgb;
    col += ShieldColor * clamp(glow, 0.0, 1.5);
    col = mix(col, ShieldColor * 0.6, clamp(rim * Impact * 0.25, 0.0, 1.0));
    fragColor = vec4(col, 1.0);
}
