// 击杀冲击 / Kill Burst
// 击杀时的全屏/局部冲击反馈：从准星/设定中心向外炸一圈环，附带放射状速度线，最后淡出。
// 比单纯图标更「爽」，适合作为击杀确认的补充层。
//
// 用法与 hitmarker / killconfirm 相同：配一条 ON_DEATH 锚点绑定后打开 UseAnchor。
// 也可以关掉 UseAnchor 让它在屏幕中心自动循环，先看动画节奏。
//
// [en_us]
// Kill burst: a shockwave ring and speed lines on a kill.
// Full-screen or local impact feedback on a kill: a ring bursts outward from the crosshair or a set center, with
// radial speed lines, then fades out. It feels punchier than an icon alone and works well as an extra layer on top
// of a kill confirm.
//
// Usage is the same as hitmarker / killconfirm: add an ON_DEATH anchor binding, then turn on UseAnchor.
// You can also turn UseAnchor off so it loops at the screen center, to check the animation timing first.
//
// @param name=UseAnchor type=bool default=0 zh_cn=跟随锚点事件 en_us=Use Anchor Event
// @param name=AnchorSlot type=anchor min=0 max=7 default=0 zh_cn=锚点槽位 en_us=Anchor Slot
// @param name=Interval type=float min=0.5 max=30 default=4.0 zh_cn=自动预览间隔(秒) en_us=Auto Preview Interval
// @param name=Center type=vec2 min=0 max=1 default=0.5,0.5 zh_cn=固定中心 en_us=Fixed Center
// @param name=Duration type=float min=0.2 max=4 default=1.2 zh_cn=动画时长(秒) en_us=Duration
// @param name=RingMax type=float min=0.1 max=1.5 default=0.8 zh_cn=冲击环最大半径 en_us=Ring Max Radius
// @param name=RingWidth type=float min=0.01 max=0.3 default=0.08 zh_cn=冲击环宽度 en_us=Ring Width
// @param name=SpeedLines type=float min=0 max=1 default=0.6 zh_cn=速度线强度 en_us=Speed Lines
// @param name=Color type=color3 default=#FF6A3D zh_cn=冲击色 en_us=Burst Color
// @param name=Flash type=float min=0 max=1 default=0.35 zh_cn=中心闪光 en_us=Center Flash
// @param name=Gain type=float min=0 max=5 default=2.0 zh_cn=亮度 en_us=Gain

void main() {
    vec2 asp = vec2(OutSize.x / max(OutSize.y, 1.0), 1.0);
    vec3 col = texture(InSampler, texCoord).rgb;

    vec2 center = Center;
    float life;
    float alive;

    if (UseAnchor > 0.5) {
        int slot = AnchorSlot;
        if (slot < 0) slot = 0;
        if (slot > 7) slot = 7;
        if (gtAnchorValid(slot)) {
            // 屏幕空间冲击；锚点只负责提供触发与生命期，不跟实体位置
            life = clamp(gtAnchorLife(slot), 0.0, 1.0);
            alive = gtAnchorStrength(slot);
        } else {
            life = 0.0;
            alive = 0.0;
        }
    } else {
        float period = max(Interval, Duration + 0.05);
        life = clamp(mod(GTTime, period) / max(Duration, 0.05), 0.0, 1.0);
        alive = 1.0;
    }
    if (alive <= 0.001) {
        fragColor = vec4(col, 1.0);
        return;
    }

    vec2 p = (texCoord - center) * asp;
    float r = length(p);
    float ang = atan(p.y, p.x);

    // 冲击环：从内向外扩散，边缘锐利但整体快速衰减
    float ringR = RingMax * (0.08 + 0.92 * life);
    float ring = smoothstep(RingWidth * 0.5, 0.0, abs(r - ringR));
    float ringAlpha = (1.0 - life) * (0.6 + 0.4 * sin(min(life * 3.14159265, 3.14159265)));

    // 速度线：沿径向的长短不一的短线，只在爆发前中期出现
    float lines = 0.0;
    if (SpeedLines > 0.01) {
        float seg = fract(ang / 6.2831853 * 14.0);
        float lineMask = step(0.62, seg) * step(seg, 0.78);
        float fade = pow(1.0 - life, 2.0);
        float rr = fract(r * 7.0 + life * 6.0);
        lines = lineMask * smoothstep(0.0, 0.08, rr) * smoothstep(0.25, 0.0, rr) * fade * SpeedLines;
    }

    // 中心闪光：开局一下，很快收掉
    float flash = smoothstep(0.15, 0.0, r) * pow(1.0 - life, 2.5) * Flash;

    float intensity = clamp(ring * ringAlpha + lines + flash, 0.0, 1.0);
    col += Color * intensity * Gain * alive;
    fragColor = vec4(col, 1.0);
}
