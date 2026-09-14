// 冲刺加速 / Sprint Rush
// 跑起来的那种观感：视野往外撑、四周拉出径向拖影、边缘压暗、颜色略微失血。
// 强度跟着一条起伏曲线走，模拟脚步的节奏，不是一条平的直线。
//
// 中心区域刻意几乎不动：玩家的准星在那儿，把中间也糊掉就没法玩了。
//
// [en_us]
// Sprinting rush: wider view, radial blur, dark edges.
// The feeling of running: the view pushes outward, radial trails stretch out around it, the edges darken and the
// color drains a little. The intensity follows an undulating curve that mimics the rhythm of footsteps, not a
// flat line.
//
// The center is deliberately left almost still: the crosshair is there, and blurring the middle too would make
// the game unplayable.
//
// @param name=Intensity type=float min=0 max=2 default=1 zh_cn=冲刺强度 en_us=Intensity
// @param name=AutoPace type=bool default=1 zh_cn=按步频起伏 en_us=Auto Pace
// @param name=Pace type=float min=0.5 max=6 default=2.6 zh_cn=步频(每秒) en_us=Step Rate

// @group 视野 / Field of View
// @param name=Widen type=float min=0 max=0.4 default=0.12 zh_cn=视野外扩 en_us=FOV Widen
// @param name=Streak type=float min=0 max=1 default=0.6 zh_cn=径向拖影 en_us=Radial Streak
// @param name=ClearRadius type=float min=0 max=0.7 default=0.22 zh_cn=中心保护半径 en_us=Clear Radius

// @group 色调 / Tone
// @param name=Vignette type=float min=0 max=1 default=0.5 zh_cn=边缘压暗 en_us=Vignette
// @param name=Desaturate type=float min=0 max=1 default=0.25 zh_cn=失血感 en_us=Desaturate
// @param name=Warm type=color3 default=#FFD9B0 zh_cn=高光偏色 en_us=Highlight Tint

void main() {
    float pace = AutoPace > 0.5
        ? 0.75 + 0.25 * sin(GTTime * Pace * 6.2831853)
        : 1.0;
    float amt = Intensity * pace;

    vec2 asp = vec2(OutSize.x / max(OutSize.y, 1.0), 1.0);
    vec2 d = (texCoord - 0.5) * asp;
    // 半径归一化到「画面正中=0，四角=1」。除数必须是<b>半</b>对角线：
    // asp=(w/h,1)，角落处 |(texCoord-0.5)*asp| 只有 length(asp) 的一半，
    // 拿整条对角线去除的话 r 最大只到 0.5——所有按半径推进的动画都只走完一半就没了，
    // 按半径衰减的效果也只发挥出一半强度。
    float r = length(d) / max(length(asp) * 0.5, 1e-4);
    // 中心保护：准星附近权重压到 0，玩家才看得清自己在打什么
    float w = smoothstep(ClearRadius, 1.0, r);

    vec2 base = (texCoord - 0.5) / (1.0 + Widen * amt * w) + 0.5;
    vec2 outward = normalize(d + 1e-6) / max(asp.x, 1e-3);

    vec3 col = vec3(0.0);
    float total = 0.0;
    for (int i = 0; i < 7; i++) {
        float s = float(i) / 6.0;
        float weight = 1.0 - s * 0.7;
        vec2 uv = clamp(base + outward * s * Streak * amt * w * 0.09, vec2(0.0), vec2(1.0));
        col += texture(InSampler, uv).rgb * weight;
        total += weight;
    }
    col /= max(total, 1e-4);

    float g = dot(col, vec3(0.2126, 0.7152, 0.0722));
    col = mix(col, vec3(g), Desaturate * amt * w);
    // 高光偏暖，模拟高强度运动时的视觉发热
    col = mix(col, col * Warm, smoothstep(0.6, 1.0, g) * amt * 0.5);
    col *= 1.0 - Vignette * amt * smoothstep(0.35, 1.15, r);

    fragColor = vec4(col, 1.0);
}
