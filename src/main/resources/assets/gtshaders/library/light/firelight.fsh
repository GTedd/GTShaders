// 火光摇曳 / Firelight Flicker
// 画面被一团看不见的火照着：亮度不规则地跳动、色温偏暖、暗部被抬起来一点、
// 光的中心还在小幅游走。营火、火把、壁炉场景直接铺一层就有味道。
//
// 闪烁曲线是三条不同频率的噪声相加而不是正弦：火焰的亮度是随机过程，
// 正弦跳动会读成"有人在拉电闸"。
//
// [en_us]
// Flickering light from an unseen fire.
// The picture is lit by a fire you can't see: brightness jumps irregularly, the color temperature turns warm,
// shadows are lifted a little, and the center of the light wanders slightly. Lay it over campfire, torch or
// fireplace scenes for instant atmosphere.
//
// The flicker curve sums three noise signals of different frequencies instead of using a sine: flame
// brightness is a random process, and a sine pulse reads as "someone flipping the power switch".
//
// @param name=Intensity type=float min=0 max=2 default=0.8 zh_cn=火光强度 en_us=Intensity
// @param name=Flicker type=float min=0 max=1 default=0.5 zh_cn=闪烁幅度 en_us=Flicker Amount
// @param name=Rate type=float min=0.5 max=12 default=4 zh_cn=闪烁速度 en_us=Flicker Rate

// @group 光源 / Source
// @param name=Center type=vec2 min=0 max=1 default=0.5,0.25 zh_cn=光源位置 en_us=Light Position
// @param name=Reach type=float min=0.1 max=2 default=0.7 zh_cn=照射范围 en_us=Reach
// @param name=Wander type=float min=0 max=0.1 default=0.02 zh_cn=光心游走 en_us=Wander

// @group 色调 / Tone
// @param name=WarmColor type=color3 default=#FFB566 zh_cn=火光色 en_us=Fire Color
// @param name=CoolShadow type=color3 default=#2A3A5C zh_cn=阴影色 en_us=Shadow Color
// @param name=ShadowMix type=float min=0 max=1 default=0.4 zh_cn=阴影偏冷 en_us=Cool Shadows
// @param name=Lift type=float min=0 max=0.2 default=0.03 zh_cn=暗部抬升 en_us=Shadow Lift

float hash11(float p) {
    return fract(sin(p * 91.7) * 43758.5453);
}

float noise11(float x) {
    float i = floor(x);
    float f = fract(x);
    f = f * f * (3.0 - 2.0 * f);
    return mix(hash11(i), hash11(i + 1.0), f);
}

void main() {
    // 三条不同频率的噪声相加：火焰是随机过程，正弦会读成"有人在拉电闸"
    float t = GTTime * Rate;
    float fl = noise11(t) * 0.5 + noise11(t * 2.3 + 7.1) * 0.3 + noise11(t * 5.7 + 3.3) * 0.2;
    float amp = 1.0 + (fl - 0.5) * 2.0 * Flicker;

    vec2 asp = vec2(OutSize.x / max(OutSize.y, 1.0), 1.0);
    // 光心小幅游走：火在动，投出去的光也跟着动
    vec2 c = Center + vec2(noise11(t * 0.7) - 0.5, noise11(t * 0.6 + 11.0) - 0.5) * Wander * 2.0;

    float d = length((texCoord - c) * asp) / max(Reach, 1e-3);
    float fall = exp(-d * d * 1.6);
    float lit = fall * Intensity * amp;

    vec3 src = texture(InSampler, texCoord).rgb;
    // 阴影偏冷、受光偏暖：这一对反差是"有个暖光源"最强的信号
    vec3 col = mix(src * CoolShadow * 1.8, src * WarmColor * 1.4, clamp(lit, 0.0, 1.0));
    col = mix(src, col, clamp(ShadowMix + lit, 0.0, 1.0));
    col += WarmColor * lit * 0.35;
    col += Lift * WarmColor * amp;

    fragColor = vec4(max(col, vec3(0.0)), 1.0);
}
