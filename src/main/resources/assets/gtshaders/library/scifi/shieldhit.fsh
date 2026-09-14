// 护盾击中 / Shield Impact
// 一层六边形能量护盾，被打中的位置炸开一圈亮环，附近的格子跟着亮起来再慢慢熄灭。
// 六边形不是装饰：它是"这是一层人造屏障"最短的视觉表达，圆形或方格都读不出这个意思。
//
// [en_us]
// A hexagonal energy shield flaring where it gets hit.
// A bright ring bursts from the impact point, and nearby cells light up with it before slowly dimming.
// The hexagons are not decoration: they are the shortest visual way to say "this is an artificial barrier".
// Circles or square grids don't read that way.
//
// @param name=HitPoint type=vec2 min=0 max=1 default=0.5,0.5 zh_cn=受击点 en_us=Hit Point
// @param name=Interval type=float min=0.2 max=10 default=2 zh_cn=受击间隔(秒) en_us=Hit Interval
// @param name=Decay type=float min=0.5 max=12 default=3 zh_cn=衰减速度 en_us=Decay
// @param name=RandomHit type=bool default=1 zh_cn=每次随机位置 en_us=Random Position

// @group 护盾 / Shield
// @param name=HexScale type=float min=4 max=60 default=18 zh_cn=六边形密度 en_us=Hex Scale
// @param name=EdgeWidth type=float min=0.01 max=0.4 default=0.09 zh_cn=格边宽度 en_us=Cell Edge
// @param name=Ambient type=float min=0 max=1 default=0.12 zh_cn=常驻可见度 en_us=Idle Visibility

// @group 冲击 / Impact
// @param name=ShieldColor type=color3 default=#46B4FF zh_cn=护盾色 en_us=Shield Color
// @param name=FlareColor type=color3 default=#DDF2FF zh_cn=击中色 en_us=Flare Color
// @param name=Gain type=float min=0 max=6 default=2.4 zh_cn=亮度 en_us=Gain
// @param name=RippleSpeed type=float min=0.1 max=4 default=1.1 zh_cn=波纹速度 en_us=Ripple Speed
// @param name=Refract type=float min=0 max=0.05 default=0.01 zh_cn=击中折射 en_us=Impact Refraction

float hash11(float p) {
    return fract(sin(p * 51.317) * 29173.1913);
}

// 六边形网格：返回 xy=格内相对坐标，zw=格心
vec4 hexGrid(vec2 p) {
    vec2 s = vec2(1.0, 1.7320508);
    vec2 a = mod(p, s) - s * 0.5;
    vec2 b = mod(p - s * 0.5, s) - s * 0.5;
    vec2 gv = dot(a, a) < dot(b, b) ? a : b;
    return vec4(gv, p - gv);
}

float hexDist(vec2 p) {
    p = abs(p);
    return max(dot(p, normalize(vec2(1.0, 1.7320508))), p.x);
}

void main() {
    vec2 asp = vec2(OutSize.x / max(OutSize.y, 1.0), 1.0);

    float period = max(Interval, 0.1);
    float idx = floor(GTTime / period);
    float age = GTTime - idx * period;
    float env = exp(-age * Decay);

    vec2 hit = HitPoint;
    if (RandomHit > 0.5) {
        hit = vec2(0.2 + hash11(idx * 3.7) * 0.6, 0.2 + hash11(idx * 9.1) * 0.6);
    }

    vec2 p = (texCoord - 0.5) * asp * HexScale;
    vec4 h = hexGrid(p);
    float edge = smoothstep(0.5 - EdgeWidth, 0.5, hexDist(h.xy));

    // 格心到受击点的距离决定这一格什么时候亮
    vec2 cellUv = h.zw / max(HexScale, 1e-3) + 0.5;
    float d = length((cellUv - hit) * asp);
    float front = age * RippleSpeed;
    float wave = smoothstep(0.25, 0.0, abs(d - front)) * env;
    float near = exp(-d * 4.0) * env;

    // 折射：冲击波经过时把画面推开一点
    vec2 dir = normalize((texCoord - hit) * asp + 1e-6);
    vec2 uv = clamp(texCoord + dir * Refract * wave, vec2(0.0), vec2(1.0));
    vec3 col = texture(InSampler, uv).rgb;

    float lit = clamp(Ambient + wave + near * 0.8, 0.0, 2.0);
    col += ShieldColor * edge * lit * Gain * 0.5;
    col += FlareColor * (wave * 0.6 + near * 0.4) * Gain * 0.35;

    fragColor = vec4(col, 1.0);
}
