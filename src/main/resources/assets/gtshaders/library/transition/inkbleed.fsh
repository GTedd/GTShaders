// 墨迹晕染 / Ink Bleed
// 一团墨从圆心洇开吞掉画面，边界是不规则的絮状，像墨滴在宣纸上。
// 关键在于边界不能是圆：用一层低频噪声去扰动半径，才有「洇」的观感。
//
// 墨的边缘留一圈更深的湿痕（真实的墨在扩散前沿会堆积），亮部先被吃掉、暗部后被吃掉，
// 于是吞没过程里画面像先褪色再消失。
//
// [en_us]
// An ink bleed that spreads out and swallows the picture.
// A blob of ink soaks outward from the center with an irregular, feathery boundary, like an ink drop on rice
// paper. The key is that the boundary must not be a circle: perturbing the radius with low-frequency noise is
// what gives the "soaking in" look.
//
// A darker wet ring is left at the ink's edge (real ink piles up at the spreading front). Bright areas are eaten
// first and dark areas later, so while being swallowed the picture seems to fade before it disappears.
//
// @param name=AutoPlay type=bool default=1 zh_cn=随时间自动播放 en_us=Auto Play
// @param name=Duration type=float min=0.2 max=10 default=2.4 zh_cn=转场时长(秒) en_us=Duration
// @param name=Hold type=float min=0 max=10 default=0.6 zh_cn=两端停留(秒) en_us=Hold desc_zh_cn=盖满和揭开之后各停多久再往回走 desc_en_us=How long it rests at each end before reversing
// @param name=Progress type=float min=0 max=1 default=0.45 zh_cn=手动进度 en_us=Progress

// @group 形状 / Shape
// @param name=Center type=vec2 min=0 max=1 default=0.5,0.55 zh_cn=墨滴落点 en_us=Drop Point
// @param name=Roughness type=float min=0 max=1 default=0.55 zh_cn=边界絮乱度 en_us=Edge Roughness
// @param name=Scale type=float min=1 max=30 default=6.5 zh_cn=絮状密度 en_us=Fringe Scale
// @param name=Creep type=float min=0 max=2 default=0.4 zh_cn=边界蠕动 en_us=Creep

// @group 外观 / Look
// @param name=InkColor type=color3 default=#0B0A14 zh_cn=墨色 en_us=Ink Color
// @param name=WetRim type=float min=0 max=1 default=0.5 zh_cn=湿痕深度 en_us=Wet Rim
// @param name=Bleach type=float min=0 max=1 default=0.6 zh_cn=吞没前褪色 en_us=Pre-bleach

float hash(vec2 p) {
    return fract(sin(dot(p, vec2(269.5, 183.3))) * 43758.5453);
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

// 往复播放：盖上 → 停一下 → 揭开 → 停一下，然后重来。
// 这里绝不能写成 clamp(GTTime / Duration)——GTTime 只增不减，跑过一遍之后恒等于 1，
// 画面会永远停在「已经盖满」那一帧。而玩家把效果加进工程时 GTTime 早就几百秒了，
// 于是「加上去只看到一块死板的颜色，怎么调都不动」。
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
    float t = AutoPlay > 0.5 ? gtPingPong(Duration, Hold) : Progress;
    vec2 asp = vec2(OutSize.x / max(OutSize.y, 1.0), 1.0);
    vec3 src = texture(InSampler, texCoord).rgb;

    vec2 d = (texCoord - Center) * asp;
    float r = length(d);
    // 半对角线：角落处 |(texCoord-0.5)*asp| 只有 length(asp) 的一半，
    // 用整条的话进度走到一半画面就已经盖满，后半程完全是死时间
    float maxR = length(asp) * 0.5;

    // 用角度取噪声：同一条射线上扰动一致，墨才会长成放射状的絮，而不是一堆孤立斑点
    float ang = atan(d.y, d.x);
    vec2 np = vec2(cos(ang), sin(ang)) * Scale + vec2(GTTime * Creep * 0.15);
    float fringe = (fbm(np) - 0.5) * Roughness * 0.45;

    float front = t * maxR * 1.15;
    float dist = r - front - fringe * front;

    float inside = smoothstep(0.02, -0.02, dist);
    float rim = smoothstep(0.10, 0.0, abs(dist)) * WetRim;

    // 吞没之前先褪一点色，让过渡不那么突兀
    float pre = smoothstep(0.22, 0.0, dist) * Bleach;
    float g = dot(src, vec3(0.2126, 0.7152, 0.0722));
    vec3 col = mix(src, vec3(g) * 0.8, pre);

    col = mix(col, InkColor, inside);
    col = mix(col, InkColor * 0.4, rim * (1.0 - inside * 0.5));
    fragColor = vec4(col, 1.0);
}
