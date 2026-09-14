// 结界 / Rune Barrier
// 符文环：内外两圈同心线 + 一圈按格子随机点亮的符号 + 缓慢反向旋转。
// 内外环反向转是重点，同向转会被看成一整块贴图在转，少了「机关」的感觉。
//
// [en_us]
// A counter-rotating rune ring with randomly lit glyphs.
// Two concentric lines (inner and outer), a band of symbols lit at random cell by cell, and a slow
// counter-rotation. The counter-rotation is the point: if both rings turn the same way, it reads as one
// texture spinning and loses the feel of a "mechanism".
//
// @param name=RuneColor type=color3 default=#FFC96B zh_cn=符文色 en_us=Rune Color
// @param name=Radius type=float min=0.1 max=1 default=0.38 zh_cn=环半径 en_us=Ring Radius
// @param name=Runes type=float min=6 max=48 default=20 zh_cn=符文数量 en_us=Rune Count
// @param name=Spin type=float min=0 max=3 default=0.5 zh_cn=旋转速度 en_us=Spin Speed
// @param name=LineWidth type=float min=0.002 max=0.05 default=0.008 zh_cn=线宽 en_us=Line Width
// @param name=Dim type=float min=0 max=1 default=0.35 zh_cn=环内压暗 en_us=Inner Dim

float hash(vec2 p) {
    return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453);
}

void main() {
    vec2 asp = vec2(OutSize.x / max(OutSize.y, 1.0), 1.0);
    vec2 d = (texCoord - 0.5) * asp;
    float r = length(d);
    float ang = atan(d.y, d.x);

    vec3 col = texture(InSampler, texCoord).rgb;
    col *= 1.0 - Dim * smoothstep(Radius * 1.12, Radius * 0.9, r);

    // 两圈细线
    float ring = smoothstep(LineWidth, 0.0, abs(r - Radius))
               + smoothstep(LineWidth, 0.0, abs(r - Radius * 0.82)) * 0.7;

    // 符文带：把 [0,2π) 切成 Runes 份，每份里画一个由随机位模式组成的小方块
    float slot = floor((ang + 3.14159) / 6.28319 * Runes);
    float local = fract((ang + 3.14159) / 6.28319 * Runes);
    float band = smoothstep(0.055, 0.02, abs(r - Radius * 0.91));
    // 每个符文有自己的闪烁相位，整圈才不会一起亮一起灭
    float blink = 0.45 + 0.55 * sin(GTTime * 2.0 + hash(vec2(slot, 3.0)) * 6.28);
    float glyph = step(0.35, hash(vec2(slot, floor(local * 3.0)))) * step(0.18, local) * step(local, 0.82);

    // 内外环反向转：符文带跟着 ang 偏移，视觉上就是两层各转各的
    float spin = GTTime * Spin;
    float outer = smoothstep(LineWidth * 1.5, 0.0,
                             abs(r - Radius * 1.04) ) * (0.5 + 0.5 * sin(ang * 24.0 + spin * 2.0));
    float inner = band * glyph * blink * (0.5 + 0.5 * sin(ang * 8.0 - spin * 3.0));

    col += RuneColor * (ring * 1.2 + outer * 0.8 + inner * 1.6);
    fragColor = vec4(col, 1.0);
}
