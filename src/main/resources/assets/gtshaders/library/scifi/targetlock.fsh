// 目标锁定 / Target Lock
// 四个角括号从画面外飞进来收在目标上，锁定完成后转成一圈慢慢旋转的准星并开始闪烁。
// 「收拢」这个动作是全部信息量所在：直接画一个静止的框，读起来只是 UI；
// 让它从远处收进来，才读得出"系统正在锁定"。
//
// [en_us]
// Corner brackets closing in on a target to lock on.
// Four corner brackets fly in from off-screen and close in on the target. Once locked, they turn into a slowly
// rotating reticle that starts blinking.
// The closing-in motion carries all the information: a static box just reads as UI. Only by pulling it in from
// afar does it read as "the system is locking on".
//
// @param name=Target type=vec2 min=0 max=1 default=0.5,0.5 zh_cn=目标位置 en_us=Target
// @param name=UseAnchor type=bool default=0 zh_cn=跟随世界锚点 en_us=Follow Anchor desc_zh_cn=开启后框住下面选的那条锚点绑定对应的实体；没装 mod 时退回上面的固定坐标 desc_en_us=Brackets the entity of the selected anchor binding; falls back to the fixed position without the mod
// @param name=Cycle type=float min=0.5 max=20 default=3 zh_cn=锁定周期(秒) en_us=Lock Cycle
// @param name=LockTime type=float min=0.1 max=3 default=0.8 zh_cn=收拢用时(秒) en_us=Lock-on Time

// @group 形状 / Shape
// @param name=Size type=float min=0.02 max=0.5 default=0.12 zh_cn=锁定框大小 en_us=Bracket Size
// @param name=ArmLength type=float min=0.1 max=1 default=0.38 zh_cn=角括号臂长 en_us=Arm Length
// @param name=LineWidth type=float min=0.001 max=0.02 default=0.004 zh_cn=线宽 en_us=Line Width
// @param name=Spin type=float min=-3 max=3 default=0.5 zh_cn=准星旋转 en_us=Reticle Spin

// @group 外观 / Look
// @param name=HudColor type=color3 default=#4CFF8F zh_cn=界面色 en_us=HUD Color
// @param name=LockedColor type=color3 default=#FF3B3B zh_cn=锁定后颜色 en_us=Locked Color
// @param name=Gain type=float min=0 max=5 default=2 zh_cn=亮度 en_us=Gain
// @param name=Blink type=float min=0 max=12 default=5 zh_cn=锁定后闪烁频率 en_us=Blink Rate
// @param name=AnchorSlot type=anchor min=0 max=7 default=0 zh_cn=锚点绑定 en_us=Anchor Binding desc_zh_cn=读哪一条锚点绑定。选的是绑定本身而不是槽位号，之后增删、排序绑定都不会指错 desc_en_us=Which anchor binding to read. Stores the binding itself, not a slot number, so it survives reordering

// 参数别叫 half —— 那是 GLSL 的保留字，编译器只会甩一句「语法错误」，不会告诉你为什么
float box(vec2 p, vec2 halfSize, float w) {
    vec2 d = abs(p) - halfSize;
    float outside = length(max(d, 0.0));
    float inside = min(max(d.x, d.y), 0.0);
    return smoothstep(w, 0.0, abs(outside + inside));
}

void main() {
    vec2 asp = vec2(OutSize.x / max(OutSize.y, 1.0), 1.0);
    vec3 col = texture(InSampler, texCoord).rgb;

    vec2 target = Target;
    float strength = 1.0;
    if (UseAnchor > 0.5 && gtAnchorValid(AnchorSlot)) {
        target = gtAnchorUV(AnchorSlot);
        strength = gtAnchorStrength(AnchorSlot) * gtAnchorVisible(AnchorSlot);
    }

    float t = clamp(mod(GTTime, max(Cycle, 0.2)) / max(LockTime, 0.05), 0.0, 1.0);
    // 收拢曲线：先快后慢，最后一点点"咬"上去
    float close = 1.0 - pow(1.0 - t, 3.0);
    bool locked = t >= 1.0;

    vec2 p = (texCoord - target) * asp;
    float s = Size * mix(3.5, 1.0, close);
    float w = LineWidth * 4.0;

    // 四个角括号：整框减去中间那段，剩下的就是四个角
    float frame = box(p, vec2(s), w);
    vec2 a = abs(p) / max(s, 1e-4);
    float corner = step(1.0 - ArmLength, max(a.x, a.y));
    float brackets = frame * corner;

    vec3 c = locked ? LockedColor : HudColor;
    float blink = locked ? (0.55 + 0.45 * sin(GTTime * Blink * 6.2831853)) : 1.0;

    float marks = 0.0;
    if (locked) {
        // 锁定后加一圈旋转准星：内环 + 十字刻度
        float ang = atan(p.y, p.x) + GTTime * Spin;
        float r = length(p) / max(s, 1e-4);
        float ring = smoothstep(w / max(s, 1e-4), 0.0, abs(r - 0.62));
        float tick = step(0.82, abs(fract(ang / 6.2831853 * 8.0) * 2.0 - 1.0));
        marks = ring * (0.35 + tick * 0.65);
        marks += smoothstep(w, 0.0, abs(p.x)) * step(length(p), s * 0.22);
        marks += smoothstep(w, 0.0, abs(p.y)) * step(length(p), s * 0.22);
    }

    col += c * clamp(brackets + marks, 0.0, 1.0) * Gain * blink * strength;
    fragColor = vec4(col, 1.0);
}
