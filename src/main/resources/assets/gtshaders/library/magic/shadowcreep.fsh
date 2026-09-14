// 暗影侵蚀 / Shadow Creep
// 黑色的触须从画面四周伸进来，缠住画面并把颜色抽干，触须尖端泛着一点紫。
// 触须的形状靠「按角度取噪声」得到：同一条射线上的扰动一致，于是暗影长成放射状的条，
// 而不是一堆各自为政的斑点。
//
// [en_us]
// Black shadow tendrils creeping in from the edges.
// Black tendrils reach in from the edges of the screen, wrapping around the picture and draining its color,
// with a hint of purple at their tips. The tendril shape comes from "sampling noise by angle": the
// disturbance is the same along any given ray, so the shadow grows as radial streaks instead of a mess of
// unrelated blotches.
//
// @param name=Amount type=float min=0 max=1.2 default=0.5 zh_cn=侵蚀程度 en_us=Creep Amount
// @param name=AutoGrow type=bool default=1 zh_cn=随时间蔓延 en_us=Auto Grow
// @param name=Period type=float min=1 max=30 default=8 zh_cn=呼吸周期(秒) en_us=Breathe Period
// @param name=Tendrils type=float min=2 max=40 default=11 zh_cn=触须数 en_us=Tendril Count
// @param name=Writhe type=float min=0 max=3 default=1 zh_cn=扭动幅度 en_us=Writhe

// @group 外观 / Look
// @param name=ShadowColor type=color3 default=#05030A zh_cn=暗影色 en_us=Shadow Color
// @param name=TipColor type=color3 default=#7A34C8 zh_cn=尖端色 en_us=Tip Color
// @param name=Drain type=float min=0 max=1 default=0.7 zh_cn=抽色程度 en_us=Color Drain
// @param name=TipGlow type=float min=0 max=3 default=0.9 zh_cn=尖端亮度 en_us=Tip Glow

float hash(vec2 p) {
    return fract(sin(dot(p, vec2(211.7, 97.3))) * 43758.5453);
}

float noise(vec2 p) {
    vec2 i = floor(p);
    vec2 f = fract(p);
    f = f * f * (3.0 - 2.0 * f);
    return mix(mix(hash(i), hash(i + vec2(1.0, 0.0)), f.x),
               mix(hash(i + vec2(0.0, 1.0)), hash(i + vec2(1.0, 1.0)), f.x), f.y);
}

void main() {
    vec2 asp = vec2(OutSize.x / max(OutSize.y, 1.0), 1.0);
    vec2 d = (texCoord - 0.5) * asp;
    float r = length(d) / max(length(asp) * 0.5, 1e-4);
    float ang = atan(d.y, d.x) / 6.2831853 + 0.5;

    float grow = AutoGrow > 0.5
        ? Amount * (0.55 + 0.45 * sin(GTTime * 6.2831853 / max(Period, 0.5)))
        : Amount;

    // 每条触须沿角度分布，各自伸缩；噪声让它们不断扭动
    float a = ang * Tendrils;
    float wig = noise(vec2(a, GTTime * 0.35)) - 0.5;
    float reach = grow * (0.75 + 0.5 * noise(vec2(floor(a) * 3.1, GTTime * 0.25)));
    float lane = abs(fract(a) - 0.5) * 2.0;
    // 越靠触须中线越粗；扭动量随伸出长度增加，尖端摆得最厉害
    float body = pow(1.0 - lane, 2.5);
    float front = 1.0 - reach + wig * Writhe * 0.12 * reach;

    float mask = clamp((r - front) * 3.5, 0.0, 1.0) * mix(0.35, 1.0, body);
    float tip = smoothstep(0.16, 0.0, abs(r - front)) * body;

    vec3 src = texture(InSampler, texCoord).rgb;
    float g = dot(src, vec3(0.2126, 0.7152, 0.0722));
    vec3 col = mix(src, vec3(g) * 0.6, Drain * mask);
    col = mix(col, ShadowColor, clamp(mask, 0.0, 1.0));
    col += TipColor * tip * TipGlow * clamp(grow, 0.0, 1.0);

    fragColor = vec4(col, 1.0);
}
