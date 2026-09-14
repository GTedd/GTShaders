// 击杀确认 / Kill Confirm
// 击杀时在准星附近弹出一个骷髅/击杀标记：快速放大、轻微回弹，再整体淡出。
// 图案用 SDF 拼，纯后处理也能画；锚点事件提供「哪一刻击杀」。
//
// 用法（事件型）：
//   1. 在「锚点」页加一条绑定，触发器选「死亡时」(ON_DEATH)，来源选实体类型并留空=全部生物；
//   2. 把它放在 0 号槽位（或把 AnchorSlot 改成对应槽位）；
//   3. 打开 UseAnchor，击杀任意生物时准星处就会弹骷髅。
//
// [en_us]
// Kill confirm: a skull pops up near the crosshair.
// On a kill, a skull/kill marker pops up near the crosshair: it scales up fast, bounces back slightly, then fades
// out as a whole. The shape is built from SDFs, so pure post-processing can draw it; the anchor event supplies the
// moment of the kill.
//
// Usage (event-driven):
//   1. In the Anchors tab, add a binding: trigger "On death" (ON_DEATH), source Entity type, empty = all mobs;
//   2. Put it in slot 0 (or change AnchorSlot to the matching slot);
//   3. Turn on UseAnchor, and a skull pops up at the crosshair whenever you kill any mob.
//
// @param name=UseAnchor type=bool default=0 zh_cn=跟随锚点事件 en_us=Use Anchor Event
// @param name=AnchorSlot type=anchor min=0 max=7 default=0 zh_cn=锚点槽位 en_us=Anchor Slot
// @param name=Interval type=float min=0.5 max=30 default=3.0 zh_cn=自动预览间隔(秒) en_us=Auto Preview Interval
// @param name=Position type=vec2 min=0 max=1 default=0.5,0.5 zh_cn=固定位置 en_us=Fixed Position
// @param name=Duration type=float min=0.2 max=4 default=1.0 zh_cn=动画时长(秒) en_us=Duration
// @param name=Size type=float min=0.05 max=0.8 default=0.22 zh_cn=图标大小 en_us=Icon Size
// @param name=LineWidth type=float min=0.001 max=0.03 default=0.006 zh_cn=线宽/描边 en_us=Line Width
// @param name=Color type=color3 default=#FFE08A zh_cn=主色 en_us=Color
// @param name=Gain type=float min=0 max=5 default=2.4 zh_cn=亮度 en_us=Gain

float sdCircle(vec2 p, float r) {
    return length(p) - r;
}

float sdBox(vec2 p, vec2 b) {
    vec2 d = abs(p) - b;
    return length(max(d, 0.0)) + min(max(d.x, d.y), 0.0);
}

float lineSegment(vec2 p, vec2 a, vec2 b, float w) {
    vec2 pa = p - a;
    vec2 ba = b - a;
    float h = clamp(dot(pa, ba) / max(dot(ba, ba), 1e-6), 0.0, 1.0);
    return smoothstep(w, 0.0, length(pa - ba * h));
}

// 一个可读的骷髅 SDF：圆颅 + 方颌，再挖掉双眼和鼻洞
float skullSdf(vec2 p) {
    float head = sdCircle(p - vec2(0.0, 0.08), 0.34);
    float jaw = sdBox(p - vec2(0.0, -0.18), vec2(0.20, 0.16));
    float body = min(head, jaw);

    float eyeL = sdCircle(p - vec2(-0.13, 0.15), 0.07);
    float eyeR = sdCircle(p - vec2(0.13, 0.15), 0.07);
    float nose = sdBox(p - vec2(0.0, 0.03), vec2(0.035, 0.04));
    float holes = min(eyeL, min(eyeR, nose));

    // 洞内：body 为负（在骨头上），holes 为负（在洞里），取 max 后洞内变正 → 无填充
    return max(body, -holes);
}

void main() {
    vec2 asp = vec2(OutSize.x / max(OutSize.y, 1.0), 1.0);
    vec3 col = texture(InSampler, texCoord).rgb;

    vec2 center = Position;
    float life;
    float alive;

    if (UseAnchor > 0.5) {
        int slot = AnchorSlot;
        if (slot < 0) slot = 0;
        if (slot > 7) slot = 7;
        if (gtAnchorValid(slot)) {
            // HUD 式反馈固定在准星/设定位置；锚点只负责提供触发与生命期
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

    // 快速放大 + 末尾淡出；life 的 0.3 前是「出现」，之后是「停留/消失」
    float appear = smoothstep(0.0, 0.25, life);
    float disappear = pow(1.0 - life, 1.4);
    float scale = mix(1.7, 1.0, appear);
    float alpha = appear * disappear * alive;
    float s = Size * scale;

    float shape = 0.0;
    float d = skullSdf(p / max(s, 1e-4));
    shape = smoothstep(LineWidth / max(s, 1e-4), 0.0, d);
    // 边缘加一点亮线，让骷髅在亮背景上也分得出来
    float edge = smoothstep(LineWidth * 1.5 / max(s, 1e-4), 0.0, abs(d) - LineWidth * 0.6 / max(s, 1e-4));
    shape = max(shape, edge * 0.6);

    // 底下衬一个淡色圆环，强化「确认」而不是「吓人」
    float ring = smoothstep(LineWidth * 2.0 / max(s, 1e-4), 0.0, abs(length(p / max(s, 1e-4)) - 0.72));
    shape = max(shape, ring * 0.25);

    col += Color * clamp(shape, 0.0, 1.0) * Gain * alpha;
    fragColor = vec4(col, 1.0);
}
