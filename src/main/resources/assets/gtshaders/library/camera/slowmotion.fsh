// 慢动作拖影 / Slow-Mo Trail
// 子弹时间的观感：沿一个缓慢摆动的方向把画面抹开，暗部拖得比亮部长，整体降饱和偏冷。
//
// 说明白一点：后处理拿不到上一帧，所以这不是真的帧混合，而是<b>沿方向的多次采样</b>
// 在空间上伪造时间上的残留。好处是零成本、任何场景都能用；代价是静止画面也会有拖影，
// 想要"只有动的东西拖"就得配 dof / motionblur 那条真运动向量的路子。
//
// [en_us]
// Bullet-time smear along a slowly swinging direction.
// Shadows trail longer than highlights, and the whole image is desaturated and shifted cooler.
//
// To be clear: post-processing can't access the previous frame, so this isn't real frame blending. It uses
// <b>multiple samples along a direction</b> to fake temporal persistence in space. The upside is zero cost and it
// works in any scene; the downside is that even a still image gets trails. If you want "only moving things trail",
// you need the real motion-vector route of dof / motionblur.
//
// @param name=Amount type=float min=0 max=1 default=0.6 zh_cn=拖影长度 en_us=Trail Length
// @param name=Angle type=float min=0 max=6.2832 default=0 zh_cn=拖影方向(弧度) en_us=Direction
// @param name=Swing type=float min=0 max=3 default=0.6 zh_cn=方向摆动 en_us=Direction Swing
// @param name=Speed type=float min=0 max=4 default=0.5 zh_cn=摆动速度 en_us=Swing Speed

// @group 权重 / Weighting
// @param name=ShadowBias type=float min=0 max=1 default=0.55 zh_cn=暗部拖更长 en_us=Shadow Bias
// @param name=Ghost type=float min=0 max=1 default=0.35 zh_cn=残影强度 en_us=Ghosting

// @group 色调 / Tone
// @param name=CoolTint type=color3 default=#9FC8FF zh_cn=冷色调 en_us=Cool Tint
// @param name=TintAmount type=float min=0 max=1 default=0.3 zh_cn=偏色量 en_us=Tint Amount
// @param name=Desaturate type=float min=0 max=1 default=0.35 zh_cn=降饱和 en_us=Desaturate

void main() {
    float ang = Angle + sin(GTTime * Speed) * Swing;
    vec2 asp = vec2(OutSize.x / max(OutSize.y, 1.0), 1.0);
    vec2 dir = vec2(cos(ang), sin(ang)) / vec2(max(asp.x, 1e-3), 1.0);

    vec3 base = texture(InSampler, texCoord).rgb;
    float luma = dot(base, vec3(0.2126, 0.7152, 0.0722));
    // 暗部拖得更长：亮部拖长了会糊成一片白，暗部拖长反而像残影
    float reach = Amount * mix(1.0, 1.0 - luma, ShadowBias) * 0.12;

    vec3 col = vec3(0.0);
    float total = 0.0;
    for (int i = 0; i < 9; i++) {
        float s = float(i) / 8.0;
        float w = pow(1.0 - s, 1.6);
        vec2 uv = clamp(texCoord - dir * s * reach, vec2(0.0), vec2(1.0));
        col += texture(InSampler, uv).rgb * w;
        total += w;
    }
    col /= max(total, 1e-4);
    col = mix(base, col, clamp(0.4 + Ghost, 0.0, 1.0));

    float g = dot(col, vec3(0.2126, 0.7152, 0.0722));
    col = mix(col, vec3(g), Desaturate);
    col = mix(col, col * CoolTint, TintAmount);

    fragColor = vec4(col, 1.0);
}
