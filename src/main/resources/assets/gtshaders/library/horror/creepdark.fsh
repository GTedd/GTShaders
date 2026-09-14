// 黑暗逼近 / Creeping Dark
// 黑暗一点点从四周收紧，可视范围越来越小，边界不规则地蠕动，偶尔还会突然收一下再退回去。
// 「突然收一下」是全部恐怖感的来源：匀速收紧只会让人适应，不规则的突进才让人紧张。
//
// [en_us]
// Darkness slowly closing in from the edges.
// Darkness tightens bit by bit from all sides and the visible area keeps shrinking. The boundary writhes
// irregularly and now and then suddenly lunges inward before pulling back. That "sudden lunge" is where all
// the dread comes from: a steady squeeze just lets you get used to it, but irregular lunges keep you tense.
//
// @param name=Closeness type=float min=0 max=1 default=0.45 zh_cn=逼近程度 en_us=Closeness
// @param name=AutoClose type=bool default=1 zh_cn=随时间逼近 en_us=Auto Close
// @param name=CloseTime type=float min=2 max=120 default=25 zh_cn=收紧到底用时(秒) en_us=Close Duration
// @param name=Hold type=float min=0 max=30 default=3 zh_cn=两端停留(秒) en_us=Hold desc_zh_cn=收到底和退回去之后各停多久。不往回退的话黑暗合拢一次就永远合着了 desc_en_us=How long it rests at each end; without reversing the darkness would stay closed forever

// @group 蠕动 / Writhe
// @param name=Wobble type=float min=0 max=1 default=0.4 zh_cn=边界蠕动 en_us=Boundary Wobble
// @param name=WobbleRate type=float min=0 max=3 default=0.6 zh_cn=蠕动速度 en_us=Wobble Rate
// @param name=Lunge type=float min=0 max=1 default=0.5 zh_cn=突进强度 en_us=Lunge
// @param name=LungeRate type=float min=0.2 max=6 default=1.7 zh_cn=突进频率 en_us=Lunge Rate

// @group 外观 / Look
// @param name=DarkColor type=color3 default=#000000 zh_cn=黑暗色 en_us=Dark Color
// @param name=Softness type=float min=0.02 max=1 default=0.3 zh_cn=边界柔和度 en_us=Softness
// @param name=Drain type=float min=0 max=1 default=0.5 zh_cn=边缘褪色 en_us=Edge Drain

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

// 收紧 → 停一下 → 退回 → 停一下，循环。
// clamp(GTTime / CloseTime) 跑过一遍就恒等于 1，黑暗会永远合在那儿不再变化。
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
    float close = AutoClose > 0.5
        ? gtPingPong(CloseTime, Hold) * max(Closeness, 0.001)
        : Closeness;

    // 突进：不规则的短促收缩。用两个不同频率的相乘，得到的间隔就不整齐了
    float l1 = max(sin(GTTime * LungeRate), 0.0);
    float l2 = max(sin(GTTime * LungeRate * 0.37 + 1.7), 0.0);
    float lunge = pow(l1 * l2, 6.0) * Lunge * 0.35;

    vec2 asp = vec2(OutSize.x / max(OutSize.y, 1.0), 1.0);
    vec2 d = (texCoord - 0.5) * asp;
    float r = length(d) / max(length(asp) * 0.5, 1e-4);
    float ang = atan(d.y, d.x);

    // 边界蠕动：沿角度取噪声，边界因此是一圈不规则的波浪
    float wob = (noise(vec2(cos(ang), sin(ang)) * 2.5 + GTTime * WobbleRate) - 0.5) * Wobble * 0.35;

    float radius = (1.0 - close - lunge) + wob;
    float soft = max(Softness, 0.02);
    float visible = smoothstep(radius + soft, radius - soft, r);

    vec3 src = texture(InSampler, texCoord).rgb;
    float g = dot(src, vec3(0.2126, 0.7152, 0.0722));
    vec3 col = mix(src, vec3(g) * 0.7, (1.0 - visible) * Drain);
    col = mix(DarkColor, col, clamp(visible, 0.0, 1.0));

    fragColor = vec4(col, 1.0);
}
