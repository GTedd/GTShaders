// 心跳 / Heartbeat
// 一次心跳是两下：咚—哒，第二下比第一下轻、隔得很近。画面跟着这个节奏鼓一下、暗一下，
// 边缘渗出血色。写成单次脉冲的话，得到的是「呼吸」不是「心跳」——差别全在那第二下。
//
// 心率越高，压暗越重、红色越浓：一个滑条同时表达「跳得快」和「情况不妙」。
//
// [en_us]
// A pounding heartbeat with red seeping in at the edges.
// A heartbeat is two beats, lub-dub, with the second one softer and very close behind the first. The picture
// swells and darkens to that rhythm while blood red seeps in from the edges. Written as a single pulse you
// get "breathing", not "a heartbeat". The whole difference is in that second beat.
//
// The higher the heart rate, the heavier the darkening and the deeper the red, so one slider conveys both
// "beating fast" and "things are going badly".
//
// @param name=Bpm type=float min=30 max=200 default=76 zh_cn=心率(BPM) en_us=Heart Rate
// @param name=SecondBeat type=float min=0 max=1 default=0.55 zh_cn=第二下强度 en_us=Second Beat
// @param name=BeatGap type=float min=0.05 max=0.5 default=0.16 zh_cn=两下间隔占比 en_us=Beat Gap

// @group 画面 / Image
// @param name=Punch type=float min=0 max=0.15 default=0.025 zh_cn=鼓动幅度 en_us=Pulse Zoom
// @param name=Dim type=float min=0 max=1 default=0.3 zh_cn=跳动压暗 en_us=Beat Dim
// @param name=Blur type=float min=0 max=0.02 default=0.004 zh_cn=跳动虚焦 en_us=Beat Blur

// @group 血色 / Blood
// @param name=BloodColor type=color3 default=#B01020 zh_cn=血色 en_us=Blood Color
// @param name=Vignette type=float min=0 max=1 default=0.6 zh_cn=边缘血色 en_us=Edge Blood
// @param name=Stress type=float min=0 max=1 default=0.5 zh_cn=紧张程度 en_us=Stress desc_zh_cn=同时加重压暗、血色和虚焦，一个滑条从「有点累」推到「快不行了」 desc_en_us=Pushes dim, blood and blur together, from mild to critical

float beatEnvelope(float phase) {
    // 咚：主脉冲，冲得快落得快
    float a = exp(-phase * 26.0);
    // 哒：延后一点、更轻的第二下
    // 第二下要"晚一点、轻一点"：这两条同时成立，才听得出是心跳而不是呼吸
    float b = exp(-max(phase - BeatGap, 0.0) * 30.0) * SecondBeat;
    return max(a, b);
}

void main() {
    float period = 60.0 / max(Bpm, 1.0);
    float phase = fract(GTTime / period) * period;
    float env = beatEnvelope(phase);

    float stress = clamp(Stress, 0.0, 1.0);
    float amt = env * (0.5 + stress);

    vec2 asp = vec2(OutSize.x / max(OutSize.y, 1.0), 1.0);
    float zoom = 1.0 + Punch * amt;
    vec2 base = (texCoord - 0.5) / zoom + 0.5;

    // 跳动瞬间轻微失焦：四点采样够了，心跳的虚焦不需要精细
    float b = Blur * amt;
    vec3 col = texture(InSampler, clamp(base, vec2(0.0), vec2(1.0))).rgb * 0.4;
    col += texture(InSampler, clamp(base + vec2(b, 0.0), vec2(0.0), vec2(1.0))).rgb * 0.15;
    col += texture(InSampler, clamp(base - vec2(b, 0.0), vec2(0.0), vec2(1.0))).rgb * 0.15;
    col += texture(InSampler, clamp(base + vec2(0.0, b), vec2(0.0), vec2(1.0))).rgb * 0.15;
    col += texture(InSampler, clamp(base - vec2(0.0, b), vec2(0.0), vec2(1.0))).rgb * 0.15;

    float r = length((texCoord - 0.5) * asp) / max(length(asp) * 0.5, 1e-4);
    float edge = smoothstep(0.35, 1.15, r);

    col *= 1.0 - Dim * amt * (0.35 + edge * 0.65);
    col = mix(col, col * BloodColor * 2.0, edge * Vignette * (0.25 + amt * 0.75) * (0.4 + stress * 0.6));

    fragColor = vec4(col, 1.0);
}
