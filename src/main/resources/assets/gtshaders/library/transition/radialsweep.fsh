// 径向扫 / Radial Sweep
// 一根从圆心射出的指针绕着转一圈，扫过的扇区被覆盖掉，像钟表指针抹掉画面。
// 指针后面拖一段渐隐的余辉，看起来才像在「扫」而不是在「跳」。
//
// [en_us]
// A clock-hand sweep that wipes the picture in a circle.
// A hand extends from the center and turns one full revolution, covering the sector it passes, like a clock hand
// erasing the picture. A fading afterglow trails behind the hand, so it looks like it "sweeps" instead of "jumps".
//
// @param name=AutoPlay type=bool default=1 zh_cn=随时间自动播放 en_us=Auto Play
// @param name=Duration type=float min=0.2 max=10 default=1.8 zh_cn=转场时长(秒) en_us=Duration
// @param name=Hold type=float min=0 max=10 default=0.6 zh_cn=两端停留(秒) en_us=Hold desc_zh_cn=盖满和揭开之后各停多久再往回走 desc_en_us=How long it rests at each end before reversing
// @param name=Progress type=float min=0 max=1 default=0.4 zh_cn=手动进度 en_us=Progress

// @group 形状 / Shape
// @param name=Center type=vec2 min=0 max=1 default=0.5,0.5 zh_cn=圆心 en_us=Center
// @param name=StartAngle type=float min=0 max=6.2832 default=1.5708 zh_cn=起始角(弧度) en_us=Start Angle
// @param name=Clockwise type=bool default=1 zh_cn=顺时针 en_us=Clockwise
// @param name=Feather type=float min=0.001 max=0.5 default=0.04 zh_cn=边缘软度 en_us=Feather

// @group 外观 / Look
// @param name=CoverColor type=color3 default=#05060A zh_cn=覆盖色 en_us=Cover Color
// @param name=SweepColor type=color3 default=#66E0FF zh_cn=指针色 en_us=Sweep Color
// @param name=SweepGlow type=float min=0 max=5 default=2 zh_cn=指针亮度 en_us=Sweep Glow
// @param name=Trail type=float min=0 max=1 default=0.45 zh_cn=余辉长度 en_us=Trail

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
    float ang = atan(d.y, d.x) - StartAngle;
    if (Clockwise > 0.5) {
        ang = -ang;
    }
    // 归一化到 0..1 的一圈。fract 处理负角，省掉一堆分支
    float a = fract(ang / 6.2831853 + 1.0);

    float covered = smoothstep(t + Feather, t - Feather, a);

    // 余辉：指针后方（a 略小于 t）按指数衰减
    float behind = t - a;
    float trail = behind > 0.0 ? exp(-behind / max(Trail * 0.5, 1e-3)) : 0.0;
    float needle = smoothstep(Feather, 0.0, abs(a - t));

    vec3 col = mix(src, CoverColor, covered);
    col += SweepColor * (needle * SweepGlow + trail * 0.35 * SweepGlow);
    fragColor = vec4(col, 1.0);
}
