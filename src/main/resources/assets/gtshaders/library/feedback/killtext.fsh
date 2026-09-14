// 击杀文字 / Kill Text
// 击杀后在准星下方弹出点阵字 "KILL"，比纯图标更直白。字形用 5×7 点阵硬画，
// 不需要任何贴图，后处理里也能稳定显示。
//
// 用法与 killconfirm 相同：配一条 ON_DEATH 锚点绑定后打开 UseAnchor。
//
// [en_us]
// Dot-matrix "KILL" text below the crosshair on a kill.
// More direct than an icon alone. The glyphs are hand-drawn on a 5x7 dot grid, so no texture is needed and it
// displays reliably in post-processing.
//
// Usage is the same as killconfirm: add an ON_DEATH anchor binding, then turn on UseAnchor.
//
// @param name=UseAnchor type=bool default=0 zh_cn=跟随锚点事件 en_us=Use Anchor Event
// @param name=AnchorSlot type=anchor min=0 max=7 default=0 zh_cn=锚点槽位 en_us=Anchor Slot
// @param name=Interval type=float min=0.5 max=30 default=3.0 zh_cn=自动预览间隔(秒) en_us=Auto Preview Interval
// @param name=Position type=vec2 min=0 max=1 default=0.5,0.62 zh_cn=文字位置 en_us=Text Position
// @param name=Duration type=float min=0.2 max=4 default=0.9 zh_cn=动画时长(秒) en_us=Duration
// @param name=Scale type=float min=0.02 max=0.3 default=0.09 zh_cn=字号 en_us=Scale
// @param name=Thickness type=float min=0.05 max=1 default=0.75 zh_cn=点阵圆润度 en_us=Dot Roundness
// @param name=Color type=color3 default=#FFFFFF zh_cn=文字色 en_us=Text Color
// @param name=Gain type=float min=0 max=5 default=2.2 zh_cn=亮度 en_us=Gain

// K I L L 的 5×7 点阵，每行一个 5bit 掩码（bit0 在左）
const int FONT[28] = int[28](
    20, 20, 20, 24, 20, 20, 20, // K
    28,  8,  8,  8,  8,  8, 28, // I
    16, 16, 16, 16, 16, 16, 31, // L
    16, 16, 16, 16, 16, 16, 31  // L
);

float dotMask(vec2 p, float s, float w) {
    // p 以文字中心为原点、单位为「格宽」；整串宽 20 格、高 7 格
    vec2 cell = p / s;
    int col = int(floor(cell.x));
    int row = int(floor(cell.y));
    if (col < 0 || col >= 20 || row < 0 || row >= 7) {
        return 0.0;
    }
    int letter = col / 5;
    int cc = col - letter * 5;
    int idx = letter * 7 + row;
    int mask = FONT[idx];
    if (((mask >> cc) & 1) == 0) {
        return 0.0;
    }
    vec2 f = fract(cell) - 0.5;
    // 点阵的每个点画成小圆角方块；w 越大越接近实心方块
    return smoothstep(w, 0.0, max(abs(f.x), abs(f.y)));
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

    // 文字从下方轻微上浮 + 淡入淡出
    float appear = smoothstep(0.0, 0.18, life);
    float disappear = pow(1.0 - life, 1.6);
    float rise = (1.0 - appear) * 0.08;
    float alpha = appear * disappear * alive;
    float s = Scale * max(asp.x, 1.0);
    vec2 p = ((texCoord - center) * asp + vec2(0.0, rise)) / max(s, 1e-4);
    // 点阵坐标原点在左下，文字整体以中心对齐：左移 10 格、下移 3.5 格
    p.x += 10.0;
    p.y += 3.5;

    float mask = dotMask(p, 1.0, Thickness);
    col += Color * clamp(mask, 0.0, 1.0) * Gain * alpha;
    fragColor = vec4(col, 1.0);
}
