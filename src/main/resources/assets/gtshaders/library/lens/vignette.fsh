// 暗角 / Vignette
// 除了常见的「越远越暗」，这里还实现了真实镜头的 cos^4 定律：
// 照度随视场角的四次方衰减。它比手调的 smoothstep 衰减得更缓、更自然，
// 想要风格化的重暗角时再切回 smoothstep 模式。
//
// [en_us]
// Vignette with a physically based cos^4 falloff.
// Besides the usual "darker the farther out", this also implements the cos^4 law of real lenses: illuminance
// falls off with the fourth power of the cosine of the field angle. It falls off more gently and naturally
// than a hand-tuned smoothstep. Switch back to smoothstep mode when you want a heavy, stylized vignette.
//
// @param name=Amount type=float min=0 max=1 default=0.55 zh_cn=强度 en_us=Amount
// @param name=Radius type=float min=0 max=1.5 default=0.75 zh_cn=起始半径 en_us=Radius
// @param name=Softness type=float min=0.01 max=1.5 default=0.6 zh_cn=柔和度 en_us=Softness
// @param name=Physical type=bool default=1 zh_cn=用 cos^4 定律 en_us=Cos^4 Falloff
// @param name=Roundness type=float min=0 max=1 default=0.7 zh_cn=圆度(0=贴合画幅) en_us=Roundness
// @param name=TintShadow type=color3 default=#0A0C14 zh_cn=暗角染色 en_us=Shadow Tint

void main() {
    vec3 col = texture(InSampler, texCoord).rgb;

    vec2 asp = vec2(OutSize.x / max(OutSize.y, 1.0), 1.0);
    // Roundness=1 是正圆（按等比空间算），=0 则贴合画幅比例
    vec2 d = (texCoord - 0.5) * mix(vec2(1.0), asp / max(asp.y, 1.0), Roundness);
    float r = length(d) * 2.0;

    float falloff;
    if (Physical > 0.5) {
        // cos^4 定律：把归一化像高换算成视场角
        float theta = atan(r * 0.9);
        float c = cos(theta);
        falloff = c * c * c * c;
        falloff = mix(1.0, falloff, Amount);
    } else {
        falloff = 1.0 - smoothstep(Radius, Radius + Softness, r) * Amount;
    }

    col = mix(TintShadow, col, clamp(falloff, 0.0, 1.0));
    fragColor = vec4(col, 1.0);
}
