// 雷达网格 / Radar Grid
// 一张往地平线退去的透视网格铺在画面下半部，网格线随时间往前推，配一条来回扫的水平光带。
// 透视靠一次除法就够了：把屏幕 y 映射成 1/y，等距的线自然就挤向地平线。
//
// [en_us]
// A perspective radar grid receding toward the horizon.
// The grid covers the lower half of the screen, its lines advance over time, and a horizontal light band sweeps
// back and forth.
// One division is all the perspective needs: mapping screen y to 1/y makes evenly spaced lines naturally crowd
// toward the horizon.
//
// @param name=Horizon type=float min=0.2 max=0.9 default=0.55 zh_cn=地平线高度 en_us=Horizon
// @param name=Speed type=float min=0 max=6 default=1.2 zh_cn=推进速度 en_us=Speed
// @param name=CellX type=float min=2 max=60 default=14 zh_cn=纵线密度 en_us=Column Density
// @param name=CellZ type=float min=2 max=60 default=12 zh_cn=横线密度 en_us=Row Density
// @param name=LineWidth type=float min=0.005 max=0.2 default=0.04 zh_cn=线宽 en_us=Line Width

// @group 外观 / Look
// @param name=GridColor type=color3 default=#2FE3FF zh_cn=网格色 en_us=Grid Color
// @param name=Gain type=float min=0 max=4 default=1.4 zh_cn=亮度 en_us=Gain
// @param name=FadeDistance type=float min=0.5 max=8 default=2.4 zh_cn=远处淡出 en_us=Distance Fade
// @param name=Darken type=float min=0 max=1 default=0.35 zh_cn=下半部压暗 en_us=Floor Dim

// @group 扫描 / Sweep
// @param name=SweepPeriod type=float min=0 max=12 default=3 zh_cn=扫描周期(秒) en_us=Sweep Period desc_zh_cn=设为 0 就没有扫描带，只留网格 desc_en_us=Set to 0 for a plain grid with no sweep bar
// @param name=SweepColor type=color3 default=#FFFFFF zh_cn=扫描带色 en_us=Sweep Color

void main() {
    vec2 asp = vec2(OutSize.x / max(OutSize.y, 1.0), 1.0);
    vec3 col = texture(InSampler, texCoord).rgb;

    float below = Horizon - texCoord.y;
    if (below <= 0.0) {
        fragColor = vec4(col, 1.0);
        return;
    }

    // 透视：屏幕上离地平线越近，代表的距离越远。1/below 就是那个"距离"
    float depth = 1.0 / max(below, 1e-3);
    float u = (texCoord.x - 0.5) * asp.x * depth;
    float v = depth + GTTime * Speed;

    // 用 fwidth 归一化线宽，远处的线才不会因为挤在一起而糊成一片实心
    float gx = abs(fract(u * CellX * 0.1) - 0.5) * 2.0;
    float gz = abs(fract(v * CellZ * 0.1) - 0.5) * 2.0;
    float wx = fwidth(u * CellX * 0.1) * 2.0 + LineWidth;
    float wz = fwidth(v * CellZ * 0.1) * 2.0 + LineWidth;
    float grid = max(smoothstep(wx, 0.0, 1.0 - gx), smoothstep(wz, 0.0, 1.0 - gz));

    float fade = exp(-depth / max(FadeDistance, 0.1) * 0.35) * smoothstep(0.0, 0.06, below);

    col *= 1.0 - Darken * smoothstep(0.0, 0.15, below);
    col += GridColor * grid * fade * Gain;

    if (SweepPeriod > 0.01) {
        // 扫描带在"距离"上匀速推进，所以在屏幕上会越来越慢——这正是透视该有的样子
        float sweepDepth = fract(GTTime / SweepPeriod) * 8.0;
        float band = smoothstep(0.6, 0.0, abs(depth - sweepDepth));
        col += SweepColor * band * fade * 0.6;
    }

    fragColor = vec4(col, 1.0);
}
