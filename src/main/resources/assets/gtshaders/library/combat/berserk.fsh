// 狂暴 / Berserk
// 肾上腺素的三件套：心跳般的推拉变焦、红色压边、三通道错位的重影。
// 变焦幅度必须很小（1% 量级），大了会立刻变成晕船。
//
// [en_us]
// Adrenaline rage: heartbeat zoom, red edges, RGB ghosting.
// The zoom pulses like a heartbeat, red presses in from the edges, and the three color channels split into
// offset ghosts. Keep the zoom tiny (around 1%): any bigger and it instantly turns into seasickness.
//
// @param name=Rage type=float min=0 max=1 default=0.7 zh_cn=狂暴程度 en_us=Rage
// @param name=Pulse type=float min=0.5 max=6 default=2.4 zh_cn=脉动频率 en_us=Pulse Rate
// @param name=Zoom type=float min=0 max=0.05 default=0.012 zh_cn=推拉幅度 en_us=Zoom Punch
// @param name=Fringe type=float min=0 max=0.02 default=0.005 zh_cn=重影错位 en_us=Fringe
// @param name=RageColor type=color3 default=#FF2A1C zh_cn=暴怒色 en_us=Rage Color

void main() {
    float beat = pow(0.5 + 0.5 * sin(GTTime * Pulse * 6.2832), 3.0) * Rage;

    vec2 c = texCoord - 0.5;
    vec2 uv = 0.5 + c * (1.0 - beat * Zoom * 4.0);

    // 三通道各自偏一点，偏移量随离心距增大——中心保持锐利，余光才乱
    float f = Fringe * Rage * (0.3 + length(c) * 1.4);
    vec3 col;
    col.r = texture(InSampler, uv + c * f).r;
    col.g = texture(InSampler, uv).g;
    col.b = texture(InSampler, uv - c * f).b;

    // 提高对比 + 压边红，让画面显得绷紧
    col = clamp((col - 0.5) * (1.0 + 0.35 * Rage) + 0.5, 0.0, 1.0);
    float edge = smoothstep(0.28, 0.95, length(c) * 1.5);
    col = mix(col, RageColor, edge * Rage * (0.25 + 0.45 * beat));
    fragColor = vec4(col, 1.0);
}
