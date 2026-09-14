// 机甲 HUD / Mech HUD
// 驾驶舱视角：四角边框、中央准星、两侧刻度尺、轻微的桶形畸变和玻璃反光条。
// 刻度尺会随时间滚动，准星呼吸式地开合——静止的 HUD 一看就是贴图，动起来才像仪器。
//
// [en_us]
// A mech cockpit HUD with moving scales and crosshair.
// Seen from the cockpit: corner frames, a center crosshair, tick scales on both sides, slight barrel distortion
// and a glass glare streak.
// The scales scroll over time and the crosshair opens and closes as if breathing. A static HUD instantly reads
// as a pasted texture; only once it moves does it feel like an instrument.
//
// @param name=HudColor type=color3 default=#7CFF5A zh_cn=界面色 en_us=HUD Color
// @param name=Gain type=float min=0 max=4 default=1.4 zh_cn=亮度 en_us=Gain
// @param name=LineWidth type=float min=0.0005 max=0.01 default=0.0018 zh_cn=线宽 en_us=Line Width

// @group 框架 / Frame
// @param name=Margin type=float min=0.01 max=0.3 default=0.06 zh_cn=边距 en_us=Margin
// @param name=CornerLen type=float min=0.02 max=0.4 default=0.12 zh_cn=角标长度 en_us=Corner Length
// @param name=TickSpeed type=float min=0 max=3 default=0.35 zh_cn=刻度滚动 en_us=Tick Scroll

// @group 准星 / Reticle
// @param name=Reticle type=float min=0 max=0.3 default=0.05 zh_cn=准星大小 en_us=Reticle Size
// @param name=Breathe type=float min=0 max=2 default=0.8 zh_cn=准星呼吸 en_us=Reticle Breathe

// @group 镜片 / Lens
// @param name=Barrel type=float min=0 max=0.4 default=0.08 zh_cn=桶形畸变 en_us=Barrel
// @param name=GlassStreak type=float min=0 max=1 default=0.3 zh_cn=玻璃反光 en_us=Glass Streak
// @param name=Scanline type=float min=0 max=1 default=0.2 zh_cn=扫描线 en_us=Scanline

float bar(float x, float w) {
    return smoothstep(w, 0.0, abs(x));
}

void main() {
    vec2 asp = vec2(OutSize.x / max(OutSize.y, 1.0), 1.0);

    // 桶形畸变：模拟隔着弧形座舱玻璃看
    vec2 c = texCoord - 0.5;
    float r2 = dot(c * asp, c * asp);
    vec2 uv = clamp(c * (1.0 + Barrel * r2) + 0.5, vec2(0.0), vec2(1.0));
    vec3 col = texture(InSampler, uv).rgb;

    float w = LineWidth * 4.0;
    float m = Margin;
    vec2 q = texCoord;
    float hud = 0.0;

    // 四角：只在角落附近画那两段边
    float nearX = step(q.x, m + CornerLen) + step(1.0 - m - CornerLen, q.x);
    float nearY = step(q.y, m + CornerLen) + step(1.0 - m - CornerLen, q.y);
    hud += (bar(q.x - m, w) + bar(q.x - (1.0 - m), w)) * clamp(nearY, 0.0, 1.0)
         * step(m, q.y) * step(q.y, 1.0 - m);
    hud += (bar(q.y - m, w) + bar(q.y - (1.0 - m), w)) * clamp(nearX, 0.0, 1.0)
         * step(m, q.x) * step(q.x, 1.0 - m);

    // 两侧刻度尺：长短线交替，整体缓慢滚动
    float scroll = GTTime * TickSpeed;
    float tick = fract((q.y + scroll) * 40.0);
    float longTick = step(0.75, fract((q.y + scroll) * 8.0)) * 0.5 + 0.5;
    float ruler = smoothstep(0.12, 0.0, tick) * longTick;
    float rulerBand = bar(abs(q.x - 0.5) - (0.5 - m - 0.02), 0.012 * longTick);
    hud += ruler * rulerBand * step(m + 0.02, q.y) * step(q.y, 1.0 - m - 0.02);

    // 准星：一个会呼吸的十字，中间留空
    float rs = Reticle * (1.0 + sin(GTTime * Breathe) * 0.12);
    vec2 d = (texCoord - 0.5) * asp;
    float dist = length(d);
    float cross = (bar(d.x, w) + bar(d.y, w))
                * step(dist, rs) * step(rs * 0.25, dist);
    hud += cross;
    hud += smoothstep(w * 1.5, 0.0, abs(dist - rs * 0.75));

    col += HudColor * clamp(hud, 0.0, 1.0) * Gain;

    // 玻璃反光：一条斜着缓慢移动的亮带
    if (GlassStreak > 0.001) {
        float s = (texCoord.x * 0.7 + texCoord.y * 0.7) - fract(GTTime * 0.07) * 2.0 + 0.5;
        col += vec3(0.6, 0.8, 1.0) * smoothstep(0.25, 0.0, abs(s)) * GlassStreak * 0.25;
    }
    col *= 1.0 - Scanline * 0.5 * (0.5 + 0.5 * sin(texCoord.y * OutSize.y * 1.6));

    fragColor = vec4(col, 1.0);
}
