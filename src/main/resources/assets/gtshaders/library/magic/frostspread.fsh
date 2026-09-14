// 冰霜蔓延 / Frost Spread
// 冰晶从画面四周往中间长，覆盖处折射、褪色、发蓝，边界是一圈几乎白色的结晶前沿。
// 晶体用 Voronoi 而不是普通噪声：冰的特征是<b>多边形的边</b>，柏林噪声只能给出圆滚滚的云。
//
// 折射按晶格中心的方向偏移采样，所以每一格里的画面都被独立地推开一点，像隔着冰看东西。
//
// [en_us]
// Ice crystals creeping in from the screen edges.
// Ice grows from the edges toward the center. Covered areas refract, lose color and turn blue, and the
// boundary is a nearly white crystalline front. The crystals use Voronoi instead of ordinary noise: ice is
// defined by <b>polygonal edges</b>, and Perlin noise can only give puffy round clouds.
//
// Refraction offsets the sample along the direction of each cell's center, so the picture inside every cell
// is pushed aside a little on its own, like looking through ice.
//
// @param name=Coverage type=float min=0 max=1.2 default=0.55 zh_cn=蔓延程度 en_us=Coverage
// @param name=AutoGrow type=bool default=1 zh_cn=随时间生长 en_us=Auto Grow
// @param name=GrowTime type=float min=0.5 max=20 default=6 zh_cn=长满用时(秒) en_us=Grow Time
// @param name=Hold type=float min=0 max=20 default=2 zh_cn=两端停留(秒) en_us=Hold desc_zh_cn=长满和化尽之后各停多久再往回走。不往回走的话结冰一次就永远是那副样子 desc_en_us=How long it rests frozen and thawed before reversing
// @param name=Scale type=float min=3 max=60 default=16 zh_cn=晶体密度 en_us=Crystal Scale

// @group 外观 / Look
// @param name=IceColor type=color3 default=#BFE6FF zh_cn=冰色 en_us=Ice Color
// @param name=EdgeColor type=color3 default=#FFFFFF zh_cn=结晶边色 en_us=Crystal Edge
// @param name=Refract type=float min=0 max=0.05 default=0.012 zh_cn=折射量 en_us=Refraction
// @param name=Desaturate type=float min=0 max=1 default=0.6 zh_cn=褪色 en_us=Desaturate
// @param name=Shimmer type=float min=0 max=1 default=0.4 zh_cn=闪烁 en_us=Shimmer

float hash(vec2 p) {
    return fract(sin(dot(p, vec2(269.5, 183.3))) * 43758.5453);
}

vec2 hash2(vec2 p) {
    return vec2(hash(p), hash(p + 17.7));
}

// 返回 x=到最近晶核的距离，y=到第二近的距离。两者之差就是晶体的边
vec2 voronoi(vec2 p) {
    vec2 g = floor(p);
    vec2 f = fract(p);
    float d1 = 8.0;
    float d2 = 8.0;
    for (int y = -1; y <= 1; y++) {
        for (int x = -1; x <= 1; x++) {
            vec2 o = vec2(float(x), float(y));
            vec2 c = o + hash2(g + o) - f;
            float d = dot(c, c);
            if (d < d1) {
                d2 = d1;
                d1 = d;
            } else if (d < d2) {
                d2 = d;
            }
        }
    }
    return vec2(sqrt(d1), sqrt(d2));
}

// 结冰 → 停一下 → 化开 → 停一下，循环。
// 直接用 clamp(GTTime / GrowTime) 的话，GTTime 涨过去之后恒等于 1，冰就再也不动了。
float gtPingPong(float duration, float hold) {
    float d = max(duration, 0.01);
    float h = max(hold, 0.0);
    float age = mod(GTTime, (d + h) * 2.0);
    if (age < d) {
        return age / d;
    }
    if (age < d + h) {
        return 1.0;
    }
    if (age < d * 2.0 + h) {
        return 1.0 - (age - d - h) / d;
    }
    return 0.0;
}

void main() {
    float grow = AutoGrow > 0.5
        ? gtPingPong(GrowTime, Hold) * Coverage
        : Coverage;

    vec2 asp = vec2(OutSize.x / max(OutSize.y, 1.0), 1.0);
    // 从四周往里：中心距离越大越先结冰
    float r = length((texCoord - 0.5) * asp) / max(length(asp) * 0.5, 1e-4);

    vec2 v = voronoi(texCoord * Scale * vec2(asp.x, 1.0));
    float edge = clamp((v.y - v.x) * 2.2, 0.0, 1.0);   // 靠近晶界时接近 0

    // 晶体自身的不规则度让蔓延前沿不是一条圆弧
    float front = grow * 1.6 - 0.25;
    float mask = clamp((r + (1.0 - v.x) * 0.35 - (1.0 - front)) * 3.0, 0.0, 1.0);

    // 折射：按到晶核的方向推开采样点
    vec2 dir = normalize(vec2(dFdx(v.x), dFdy(v.x)) + 1e-6);
    vec2 uv = clamp(texCoord + dir * Refract * mask, vec2(0.0), vec2(1.0));
    vec3 col = texture(InSampler, uv).rgb;

    float g = dot(col, vec3(0.2126, 0.7152, 0.0722));
    vec3 iced = mix(col, vec3(g), Desaturate) * IceColor;
    // 晶界高光 + 随时间的闪烁，冰才不是一块死的蓝色玻璃
    float sparkle = (0.5 + 0.5 * sin(GTTime * 3.0 + v.x * 40.0)) * Shimmer;
    iced += EdgeColor * (1.0 - edge) * (0.35 + sparkle * 0.5);

    fragColor = vec4(mix(col, iced, mask), 1.0);
}
