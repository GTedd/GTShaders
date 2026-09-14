// 召唤法阵 / Summon Circle
// 屏幕空间里画一个透视压扁的法阵：两圈反向旋转的环、一圈刻度、一个内接多边形，
// 整体在地面高度上缓缓浮动并向上打出一层辉光。
//
// 阵是<b>叠加</b>上去的，不遮挡画面——它要读起来像"地上亮起了符文"，而不是"贴了张图"。
// 打开锚点后可以钉在世界里的某个位置上（需要装本 mod）。
//
// [en_us]
// A glowing magic circle on the ground, in perspective.
// Draws a perspective-flattened magic circle in screen space: two counter-rotating rings, a ring of tick marks
// and an inscribed polygon. The whole thing gently bobs at ground level and casts a layer of glow upward.
//
// The circle is <b>added</b> on top and never hides the picture. It should read as "runes lighting up on the
// ground", not "an image pasted on". With the anchor enabled it can be pinned to a location in the world
// (requires this mod installed).
//
// @param name=Center type=vec2 min=0 max=1 default=0.5,0.35 zh_cn=阵心 en_us=Circle Center
// @param name=UseAnchor type=bool default=0 zh_cn=跟随世界锚点 en_us=Follow Anchor
// @param name=Radius type=float min=0.05 max=1 default=0.3 zh_cn=半径 en_us=Radius
// @param name=Flatten type=float min=1 max=8 default=3.2 zh_cn=透视压扁 en_us=Perspective Squash

// @group 结构 / Structure
// @param name=Spin type=float min=-4 max=4 default=0.6 zh_cn=旋转速度 en_us=Spin Speed
// @param name=Ticks type=float min=4 max=96 default=36 zh_cn=刻度数 en_us=Tick Count
// @param name=Sides type=float min=3 max=12 default=5 zh_cn=内接多边形边数 en_us=Polygon Sides
// @param name=LineWidth type=float min=0.001 max=0.05 default=0.008 zh_cn=线宽 en_us=Line Width

// @group 外观 / Look
// @param name=RuneColor type=color3 default=#FFB44A zh_cn=符文色 en_us=Rune Color
// @param name=Gain type=float min=0 max=6 default=2.2 zh_cn=亮度 en_us=Gain
// @param name=Pulse type=float min=0 max=4 default=1.4 zh_cn=脉动频率 en_us=Pulse Rate
// @param name=Uplight type=float min=0 max=1 default=0.4 zh_cn=向上辉光 en_us=Up Light
// @param name=AnchorSlot type=anchor min=0 max=7 default=0 zh_cn=锚点绑定 en_us=Anchor Binding desc_zh_cn=读哪一条锚点绑定。选的是绑定本身而不是槽位号，之后增删、排序绑定都不会指错 desc_en_us=Which anchor binding to read. Stores the binding itself, not a slot number, so it survives reordering

float ring(float r, float target, float w) {
    return smoothstep(w, 0.0, abs(r - target));
}

void main() {
    vec2 asp = vec2(OutSize.x / max(OutSize.y, 1.0), 1.0);
    vec3 col = texture(InSampler, texCoord).rgb;

    vec2 c = Center;
    float strength = 1.0;
    if (UseAnchor > 0.5 && gtAnchorValid(AnchorSlot)) {
        c = gtAnchorUV(AnchorSlot);
        strength = gtAnchorStrength(AnchorSlot) * gtAnchorVisible(AnchorSlot);
    }

    // 压扁 y 轴 = 把正圆看成躺在地上的椭圆
    vec2 d = (texCoord - c) * asp;
    d.y *= Flatten;
    float r = length(d) / max(Radius, 1e-3);
    float ang = atan(d.y, d.x);

    float w = LineWidth / max(Radius, 1e-3) * 4.0;
    float pulse = 0.75 + 0.25 * sin(GTTime * Pulse);

    float lines = 0.0;
    lines += ring(r, 1.0, w);
    lines += ring(r, 0.86, w * 0.7);
    lines += ring(r, 0.42, w * 0.7);

    // 刻度：外环上的一圈短线，跟着转
    float ta = ang + GTTime * Spin;
    float tick = abs(fract(ta / 6.2831853 * Ticks) - 0.5) * 2.0;
    lines += smoothstep(0.55, 1.0, tick) * ring(r, 0.93, w * 3.0);

    // 内接多边形：把极坐标折到 1/n 扇区里，到边的距离就是一条直线
    float n = max(floor(Sides), 3.0);
    float pa = ang - GTTime * Spin * 0.6;
    float sector = 6.2831853 / n;
    float folded = cos(floor(0.5 + pa / sector) * sector - pa) * r;
    lines += smoothstep(w * 1.2, 0.0, abs(folded - 0.72 * cos(sector * 0.5)));

    lines *= smoothstep(1.25, 1.0, r);   // 阵外不画东西

    col += RuneColor * clamp(lines, 0.0, 1.0) * Gain * pulse * strength;
    // 向上的辉光：阵内一层柔光，越靠中心越亮
    col += RuneColor * smoothstep(1.1, 0.0, r) * Uplight * pulse * 0.4 * strength;

    fragColor = vec4(col, 1.0);
}
