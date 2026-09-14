// 耳鸣 / Tinnitus
// 爆炸之后那几秒：画面失焦、饱和度掉光、四周发白发闷，还有一层随呼吸起伏的低频晃动。
// 「听不见」在画面上的对应物是<b>失去细节</b>——所以主要手段是模糊和降饱和，而不是加东西。
//
// 恢复曲线可以自动跑一遍，也可以手动停在某个程度上，方便和音频对齐。
//
// [en_us]
// The dazed, ringing seconds right after an explosion.
// The picture loses focus, saturation drains away, the surroundings turn white and muffled, and a
// low-frequency sway rises and falls with your breathing. The visual counterpart of "can't hear" is
// <b>losing detail</b>, so the main tools are blur and desaturation rather than adding things.
//
// The recovery curve can play through automatically, or be held manually at a given level, which makes it
// easy to line up with the audio.
//
// @param name=AutoPlay type=bool default=1 zh_cn=自动恢复 en_us=Auto Recover
// @param name=Duration type=float min=0.5 max=20 default=6 zh_cn=恢复时长(秒) en_us=Duration
// @param name=Interval type=float min=1 max=60 default=10 zh_cn=重播间隔(秒) en_us=Replay Interval desc_zh_cn=自动恢复时每隔这么久重来一次；不循环的话恢复完就永远是正常画面了 desc_en_us=How often it replays; without looping it would sit at "fully recovered" forever
// @param name=Severity type=float min=0 max=1 default=0.7 zh_cn=手动严重度 en_us=Severity

// @group 失焦 / Blur
// @param name=Blur type=float min=0 max=0.03 default=0.008 zh_cn=模糊量 en_us=Blur
// @param name=Doubling type=float min=0 max=0.03 default=0.006 zh_cn=复视 en_us=Double Vision
// @param name=Wobble type=float min=0 max=0.03 default=0.006 zh_cn=低频晃动 en_us=Wobble
// @param name=WobbleRate type=float min=0.2 max=4 default=0.9 zh_cn=晃动频率 en_us=Wobble Rate

// @group 色调 / Tone
// @param name=Desaturate type=float min=0 max=1 default=0.75 zh_cn=降饱和 en_us=Desaturate
// @param name=Whiteout type=float min=0 max=1 default=0.4 zh_cn=发白 en_us=Whiteout
// @param name=Ring type=color3 default=#FFF4E0 zh_cn=耳鸣色 en_us=Ring Tint

// 循环播放。写成 1.0 - GTTime / Duration 的话，GTTime 涨过 Duration 之后恒为 0，
// 效果永远停在「已经恢复」——而玩家把它加进工程时 GTTime 早就几百秒了，
// 于是画面一点变化都没有。
float gtLoop(float duration, float interval) {
    return clamp(mod(GTTime, max(interval, duration)) / max(duration, 0.01), 0.0, 1.0);
}

void main() {
    float k = AutoPlay > 0.5
        ? pow(1.0 - gtLoop(Duration, Interval), 1.4)
        : Severity;

    float wob = sin(GTTime * WobbleRate * 6.2831853) * Wobble * k;
    vec2 base = clamp(texCoord + vec2(wob * 0.6, wob), vec2(0.0), vec2(1.0));

    // 8 向环形采样：便宜、够糊，而且不会像十字采样那样留下方向感
    float b = Blur * k;
    vec3 col = texture(InSampler, base).rgb * 0.28;
    float total = 0.28;
    for (int i = 0; i < 8; i++) {
        float a = float(i) / 8.0 * 6.2831853;
        vec2 o = vec2(cos(a), sin(a)) * b;
        col += texture(InSampler, clamp(base + o, vec2(0.0), vec2(1.0))).rgb * 0.09;
        total += 0.09;
    }
    col /= max(total, 1e-4);

    // 复视：一层横向偏移的半透明副本
    vec3 dbl = texture(InSampler, clamp(base + vec2(Doubling * k, 0.0), vec2(0.0), vec2(1.0))).rgb;
    col = mix(col, max(col, dbl), 0.45 * k);

    float g = dot(col, vec3(0.2126, 0.7152, 0.0722));
    col = mix(col, vec3(g), Desaturate * k);
    col = mix(col, Ring, Whiteout * k * 0.55);

    fragColor = vec4(col, 1.0);
}
