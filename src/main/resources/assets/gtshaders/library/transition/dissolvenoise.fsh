// 噪声溶解 / Noise Dissolve
// 画面按一张噪声图逐块消失，溶解面上留一圈灼烧色的亮边，像纸被点着了从中间烧开。
// 噪声本身在缓慢漂移，所以溶解面推进时一直在蠕动，而不是一张固定花纹整体淡出。
//
// 阈值加了一点「从边缘先烧」的偏置，四周比中心先没——不加的话溶解看着是随机噪点闪烁，
// 没有方向感。
//
// [en_us]
// A noise dissolve with a glowing burnt edge.
// The picture disappears patch by patch following a noise map, with a burn-colored glowing rim along the dissolve
// front, like paper that caught fire and is burning open from the middle. The noise itself drifts slowly, so the
// front keeps squirming as it advances instead of one fixed pattern fading out as a whole.
//
// The threshold has a slight "burn from the edges first" bias, so the borders go before the center. Without it
// the dissolve looks like random flickering noise with no sense of direction.
//
// @param name=AutoPlay type=bool default=1 zh_cn=随时间自动播放 en_us=Auto Play
// @param name=Duration type=float min=0.2 max=10 default=2 zh_cn=转场时长(秒) en_us=Duration
// @param name=Hold type=float min=0 max=10 default=0.6 zh_cn=两端停留(秒) en_us=Hold desc_zh_cn=盖满和揭开之后各停多久再往回走 desc_en_us=How long it rests at each end before reversing
// @param name=Progress type=float min=0 max=1 default=0.45 zh_cn=手动进度 en_us=Progress

// @group 噪声 / Noise
// @param name=Scale type=float min=2 max=120 default=26 zh_cn=噪声密度 en_us=Noise Scale
// @param name=Drift type=float min=0 max=3 default=0.5 zh_cn=噪声漂移 en_us=Drift
// @param name=EdgeBias type=float min=0 max=1 default=0.35 zh_cn=由外向内 en_us=Edge First

// @group 外观 / Look
// @param name=BurnColor type=color3 default=#FF8A2B zh_cn=灼烧色 en_us=Burn Color
// @param name=BurnWidth type=float min=0.01 max=0.4 default=0.09 zh_cn=灼烧边宽 en_us=Burn Width
// @param name=BurnGlow type=float min=0 max=6 default=2.6 zh_cn=灼烧亮度 en_us=Burn Glow
// @param name=CoverColor type=color3 default=#000000 zh_cn=烧尽后的颜色 en_us=Burnt Color

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
    vec3 src = texture(InSampler, texCoord).rgb;

    vec2 np = texCoord * Scale + vec2(GTTime * Drift * 0.13, GTTime * Drift * 0.09);
    float n = noise(np) * 0.65 + noise(np * 2.7) * 0.35;

    // 到画面中心的距离越大，阈值越低 = 越早被烧掉
    float d = length(texCoord - vec2(0.5)) * 1.42;
    float threshold = mix(n, n * 0.5 + (1.0 - d) * 0.5, EdgeBias);

    // 进度要能真正走完：留出灼烧边的宽度，否则 t=1 时还剩一圈没烧完
    float tt = t * (1.0 + BurnWidth * 2.0);

    vec3 col;
    if (threshold < tt - BurnWidth) {
        col = CoverColor;
    } else if (threshold < tt) {
        float k = 1.0 - (tt - threshold) / max(BurnWidth, 1e-4);
        col = mix(CoverColor, BurnColor * BurnGlow, 0.35 + 0.65 * k);
    } else {
        // 还没烧到：提前一点点透出暖色，做「即将烧起来」的预热
        float warm = smoothstep(tt + BurnWidth * 2.0, tt, threshold);
        col = src + BurnColor * warm * 0.5;
    }
    fragColor = vec4(col, 1.0);
}
