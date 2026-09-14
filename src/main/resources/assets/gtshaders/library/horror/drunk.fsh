// 醉酒 / Drunk
// 视野缓慢摇摆、双影分离、边缘拖成弧形，整体偏暖并且对不上焦。
// 双影的分离方向跟着摇摆走，不是固定的左右——固定方向只会读成"3D 眼镜没戴好"。
//
// [en_us]
// Swaying, doubled, out-of-focus drunk vision.
// The view slowly sways, splits into double vision and smears into arcs at the edges, with a warm cast and
// focus that never quite lands. The double images separate along the direction of the sway rather than a
// fixed left-right. A fixed direction would only read as "3D glasses not worn properly".
//
// @param name=Level type=float min=0 max=1 default=0.6 zh_cn=醉意 en_us=Drunkenness
// @param name=Rate type=float min=0.05 max=2 default=0.35 zh_cn=摇摆频率 en_us=Sway Rate

// @group 摇摆 / Sway
// @param name=SwayAmount type=float min=0 max=0.12 default=0.03 zh_cn=摇摆幅度 en_us=Sway Amount
// @param name=Roll type=float min=0 max=0.3 default=0.06 zh_cn=倾斜幅度 en_us=Roll
// @param name=Barrel type=float min=0 max=0.6 default=0.18 zh_cn=边缘弧形 en_us=Barrel

// @group 双影 / Double Vision
// @param name=Separation type=float min=0 max=0.08 default=0.022 zh_cn=双影距离 en_us=Separation
// @param name=GhostMix type=float min=0 max=1 default=0.5 zh_cn=双影强度 en_us=Ghost Strength

// @group 色调 / Tone
// @param name=Warm type=color3 default=#FFD2A8 zh_cn=暖色 en_us=Warm Tint
// @param name=Blur type=float min=0 max=0.02 default=0.005 zh_cn=失焦 en_us=Defocus
// @param name=Vignette type=float min=0 max=1 default=0.45 zh_cn=暗角 en_us=Vignette

void main() {
    float lvl = clamp(Level, 0.0, 1.0);
    float t = GTTime * Rate;
    vec2 asp = vec2(OutSize.x / max(OutSize.y, 1.0), 1.0);

    // 两个不成比例的频率叠出来的摇摆，不会周期性回到原点
    vec2 sway = vec2(sin(t * 6.2831853) * 0.7 + sin(t * 2.6 + 1.3) * 0.3,
                     cos(t * 5.1 + 0.7) * 0.6 + cos(t * 1.9) * 0.4) * SwayAmount * lvl;
    float roll = (sin(t * 4.3 + 2.1) * 0.6 + sin(t * 1.7) * 0.4) * Roll * lvl;

    vec2 p = texCoord - 0.5;
    p.x *= asp.x;
    float c = cos(roll);
    float s = sin(roll);
    p = mat2(c, -s, s, c) * p;
    // 桶形：边缘弧起来，走廊看着像在弯
    float r2 = dot(p, p);
    p *= 1.0 + Barrel * lvl * r2;
    p.x /= asp.x;
    vec2 base = clamp(p / 1.08 + 0.5 + sway, vec2(0.0), vec2(1.0));

    // 双影方向跟着摇摆走
    vec2 dir = normalize(sway + vec2(1e-4, 1e-4));
    vec2 off = dir * Separation * lvl;

    float b = Blur * lvl;
    vec3 a1 = texture(InSampler, clamp(base + off, vec2(0.0), vec2(1.0))).rgb;
    vec3 a2 = texture(InSampler, clamp(base - off, vec2(0.0), vec2(1.0))).rgb;
    vec3 col = mix(texture(InSampler, base).rgb, (a1 + a2) * 0.5, GhostMix * lvl);
    col = mix(col, texture(InSampler, clamp(base + vec2(b, b), vec2(0.0), vec2(1.0))).rgb * 0.5
                 + texture(InSampler, clamp(base - vec2(b, b), vec2(0.0), vec2(1.0))).rgb * 0.5,
              0.45 * lvl);

    col = mix(col, col * Warm, 0.5 * lvl);
    float r = length((texCoord - 0.5) * asp) / max(length(asp) * 0.5, 1e-4);
    col *= 1.0 - Vignette * lvl * smoothstep(0.4, 1.2, r);

    fragColor = vec4(col, 1.0);
}
