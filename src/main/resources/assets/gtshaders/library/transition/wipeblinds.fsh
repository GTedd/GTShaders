// 百叶窗 / Blinds Wipe
// 画面被切成若干条，每条从两端向中线合拢，像百叶窗关上。
// 条与条之间用黄金比错开开始时间：全部同步合拢会显得很廉价，错开一点才有层次。
//
// [en_us]
// A blinds wipe that closes the picture strip by strip.
// The picture is cut into strips, and each one closes from both ends toward its center line, like blinds shutting.
// Start times are staggered by the golden ratio: closing all strips in sync looks cheap, and a slight offset adds
// layering.
//
// @param name=AutoPlay type=bool default=1 zh_cn=随时间自动播放 en_us=Auto Play
// @param name=Duration type=float min=0.2 max=10 default=1.4 zh_cn=转场时长(秒) en_us=Duration
// @param name=Hold type=float min=0 max=10 default=0.6 zh_cn=两端停留(秒) en_us=Hold desc_zh_cn=盖满和揭开之后各停多久再往回走 desc_en_us=How long it rests at each end before reversing
// @param name=Progress type=float min=0 max=1 default=0.5 zh_cn=手动进度 en_us=Progress

// @group 形状 / Shape
// @param name=Count type=float min=2 max=64 default=12 zh_cn=条数 en_us=Blind Count
// @param name=Vertical type=bool default=0 zh_cn=竖向百叶 en_us=Vertical
// @param name=Stagger type=float min=0 max=0.9 default=0.35 zh_cn=错拍 en_us=Stagger desc_zh_cn=每条晚多久开始。0 是整齐划一，越大越像手拉的百叶 desc_en_us=How much each blind lags behind; 0 is perfectly synced

// @group 外观 / Look
// @param name=CoverColor type=color3 default=#0A0A12 zh_cn=覆盖色 en_us=Cover Color
// @param name=EdgeGlow type=float min=0 max=3 default=0.8 zh_cn=合拢边亮度 en_us=Edge Glow

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

    float axis = Vertical > 0.5 ? texCoord.x : texCoord.y;
    float band = axis * Count;
    float idx = floor(band);
    float f = fract(band);

    // 黄金比的小数部分：先后顺序看着随机，但在条数很多时分布仍然均匀，
    // 不会出现「一半先关另一半后关」那种一眼能看穿的规律
    float delay = Stagger * fract(idx * 0.6180339887);
    float tt = clamp((t - delay) / max(1.0 - Stagger, 1e-3), 0.0, 1.0);

    float d = abs(f - 0.5) * 2.0;   // 条边缘=1，条中线=0
    float cut = 1.0 - tt;           // 还没被合拢的那半宽
    float edge = 1.5 / Count;

    float covered = smoothstep(cut, cut - edge, d);
    float line = smoothstep(edge, 0.0, abs(d - cut)) * tt;

    vec3 col = mix(src, CoverColor, covered);
    col += vec3(1.0) * line * EdgeGlow * 0.5;
    fragColor = vec4(col, 1.0);
}
