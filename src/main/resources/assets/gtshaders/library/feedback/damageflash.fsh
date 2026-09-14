// 受击闪红 / Damage Flash
// 受伤时在屏幕边缘压一圈红色，并带一点径向暗角，让「挨打」有明确的画面反馈。
// 这是 FPS/TPS 通用的受伤方向提示的简化版：先整体红一下，再用边缘衰减收掉。
//
// 用法：配一条 ON_HURT 锚点绑定，来源选「玩家自己」(SELF) 并打开 UseAnchor。
// 没配锚点时自动循环预览。
//
// [en_us]
// Red flash around the screen edges when you take damage.
// A ring of red presses in from the edges with a slight radial vignette, so getting hit has clear visual feedback.
// It's a simplified take on the damage direction indicator common in FPS/TPS games: the whole screen flashes red
// first, then an edge falloff fades it out.
//
// Usage: add an ON_HURT anchor binding, set the source to "Yourself" (SELF), and turn on UseAnchor.
// With no anchor configured, it loops as a preview.
//
// @param name=UseAnchor type=bool default=0 zh_cn=跟随锚点事件 en_us=Use Anchor Event
// @param name=AnchorSlot type=anchor min=0 max=7 default=0 zh_cn=锚点槽位 en_us=Anchor Slot
// @param name=Interval type=float min=0.5 max=30 default=2.0 zh_cn=自动预览间隔(秒) en_us=Auto Preview Interval
// @param name=Duration type=float min=0.1 max=3 default=0.6 zh_cn=动画时长(秒) en_us=Duration
// @param name=Amount type=float min=0 max=1 default=0.55 zh_cn=边缘红强度 en_us=Edge Red
// @param name=InnerFalloff type=float min=0.5 max=3 default=1.6 zh_cn=向内衰减 en_us=Inner Falloff
// @param name=Color type=color3 default=#FF2020 zh_cn=受伤色 en_us=Damage Color
// @param name=Darken type=float min=0 max=1 default=0.35 zh_cn=外围压暗 en_us=Darken
// @param name=Gain type=float min=0 max=4 default=1.6 zh_cn=亮度 en_us=Gain

void main() {
    vec2 asp = vec2(OutSize.x / max(OutSize.y, 1.0), 1.0);
    vec3 col = texture(InSampler, texCoord).rgb;

    float life;
    float alive;

    if (UseAnchor > 0.5) {
        int slot = AnchorSlot;
        if (slot < 0) slot = 0;
        if (slot > 7) slot = 7;
        if (gtAnchorValid(slot)) {
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

    // 用等比空间的距离：宽屏上边缘红圈不会拉成椭圆
    vec2 p = (texCoord - 0.5) * asp;
    float d = length(p);
    // 边缘权重：越靠边越红；InnerFalloff 控制往中心衰减多快
    float edge = pow(smoothstep(0.22, 0.85, d), max(InnerFalloff, 0.5));
    float envelope = pow(1.0 - life, 1.8);

    col = mix(col, Color, clamp(edge * Amount * envelope * alive, 0.0, 1.0) * Gain);
    col *= 1.0 - Darken * edge * envelope * alive * 0.5;

    fragColor = vec4(col, 1.0);
}
