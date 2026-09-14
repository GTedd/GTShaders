// 命中标记 / Hit Marker
// 命中反馈里最通用的一档：在命中位置/准星周围弹出四道短斜线，瞬间展开后淡出。
// 后处理画不了真正的 HUD，但 GTShaders 的锚点系统可以把「刚刚打中」这一离散事件
// 以强度/生命期喂进来；没配锚点时自动循环，方便在编辑器里直接预览。
//
// 用法（事件型）：
//   1. 在「锚点」页加一条绑定，触发器选「受伤时」(ON_HURT)，来源选实体类型并留空=全部生物；
//   2. 把这条绑定放在 0 号槽位（或把下面的 AnchorSlot 改成对应槽位）；
//   3. 打开本效果的 UseAnchor，打中任意生物时准星处就会弹命中标记。
//
// [en_us]
// Classic hit marker: four short diagonal ticks on a hit.
// The most common kind of hit feedback: four short diagonal lines pop out around the hit point or crosshair,
// snap open, then fade. Post-processing can't draw a real HUD, but the GTShaders anchor system can feed in the
// discrete "just hit something" event as an intensity/lifetime. With no anchor configured it loops, so you can
// preview it right in the editor.
//
// Usage (event-driven):
//   1. In the Anchors tab, add a binding: trigger "On hurt" (ON_HURT), source Entity type, empty = all mobs;
//   2. Put this binding in slot 0 (or change AnchorSlot below to the matching slot);
//   3. Turn on UseAnchor for this effect, and a hit marker pops up at the crosshair whenever you hit any mob.
//
// @param name=UseAnchor type=bool default=0 zh_cn=跟随锚点事件 en_us=Use Anchor Event
// @param name=AnchorSlot type=anchor min=0 max=7 default=0 zh_cn=锚点槽位 en_us=Anchor Slot
// @param name=Interval type=float min=0.5 max=30 default=2.5 zh_cn=自动预览间隔(秒) en_us=Auto Preview Interval
// @param name=Position type=vec2 min=0 max=1 default=0.5,0.5 zh_cn=固定位置 en_us=Fixed Position
// @param name=Duration type=float min=0.1 max=3 default=0.45 zh_cn=动画时长(秒) en_us=Duration
// @param name=Size type=float min=0.02 max=0.5 default=0.08 zh_cn=标记大小 en_us=Marker Size
// @param name=Thickness type=float min=0.001 max=0.02 default=0.004 zh_cn=线宽 en_us=Thickness
// @param name=Color type=color3 default=#FFFFFF zh_cn=颜色 en_us=Color
// @param name=Gain type=float min=0 max=5 default=2.2 zh_cn=亮度 en_us=Gain

float lineSegment(vec2 p, vec2 a, vec2 b, float w) {
    vec2 pa = p - a;
    vec2 ba = b - a;
    float h = clamp(dot(pa, ba) / max(dot(ba, ba), 1e-6), 0.0, 1.0);
    return smoothstep(w, 0.0, length(pa - ba * h));
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
    float scale = 1.0 + life * 0.9;
    float s = Size * scale;
    float alpha = pow(1.0 - life, 1.5);

    float marker = 0.0;
    for (int i = 0; i < 4; i++) {
        float ang = 0.78539816 + float(i) * 1.5707963;
        vec2 d = vec2(cos(ang), sin(ang));
        vec2 a = d * (0.35 * s);
        vec2 b = d * (0.80 * s);
        marker += lineSegment(p, a, b, Thickness * scale);
    }
    // 中心一个小点，让标记在远距离小尺寸时仍然可读
    marker += smoothstep(Thickness * 0.7, 0.0, length(p) - s * 0.06);

    col += Color * clamp(marker, 0.0, 1.0) * Gain * alpha * alive;
    fragColor = vec4(col, 1.0);
}
