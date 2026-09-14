// 隔行错位 / Interlace Tear
// 隔行扫描的两场在时间上差半帧，画面一动就会在水平边缘露出梳齿。
// 这里把奇偶行分别向相反方向错开来还原那种「梳子」效果，
// 再叠一层轻微的场闪烁——CRT 时代的运动画面就长这样。
//
// [en_us]
// Interlaced fields torn apart into comb artifacts.
// The two fields of interlacing are half a frame apart in time, so as soon as the picture moves, comb teeth show
// up along horizontal edges. This shifts odd and even rows in opposite directions to recreate that "comb" look,
// then adds a light field flicker on top: that is what moving pictures looked like in the CRT era.
//
// @param name=Offset type=float min=0 max=0.05 default=0.006 zh_cn=错位量 en_us=Field Offset
// @param name=Motion type=float min=0 max=1 default=0.6 zh_cn=只在边缘处错位 en_us=Edge Only
// @param name=FieldFlicker type=float min=0 max=1 default=0.25 zh_cn=场闪烁 en_us=Field Flicker
// @param name=LineGap type=float min=1 max=8 default=1 zh_cn=行间距 en_us=Line Spacing
// @param name=Softness type=float min=0 max=1 default=0.2 zh_cn=垂直柔化 en_us=Vertical Soften

float lum(vec3 c) {
    return dot(c, vec3(0.2126, 0.7152, 0.0722));
}

void main() {
    float line = floor(gl_FragCoord.y / max(LineGap, 1.0));
    // 奇偶场：+1 / -1
    float field = mod(line, 2.0) < 1.0 ? 1.0 : -1.0;

    vec2 texel = 1.0 / max(OutSize, vec2(1.0));

    // 只在水平方向有内容变化的地方错位，静止的平坦区域不该出现梳齿
    float gx = abs(lum(texture(InSampler, texCoord + vec2(texel.x, 0.0)).rgb)
                 - lum(texture(InSampler, texCoord - vec2(texel.x, 0.0)).rgb));
    float w = mix(1.0, clamp(gx * 8.0, 0.0, 1.0), Motion);

    vec3 col = texture(InSampler, texCoord + vec2(field * Offset * w, 0.0)).rgb;

    // 垂直柔化：把相邻行混一点回来，缓解硬梳齿
    if (Softness > 0.0) {
        vec3 up = texture(InSampler, texCoord + vec2(0.0, texel.y * LineGap)).rgb;
        vec3 down = texture(InSampler, texCoord - vec2(0.0, texel.y * LineGap)).rgb;
        col = mix(col, (col + up + down) / 3.0, Softness);
    }

    // 场闪烁：两场亮度轮流略有差异，50/60Hz 交流供电的痕迹
    float flicker = 1.0 + field * FieldFlicker * 0.06 * sin(GTTime * 30.0);
    fragColor = vec4(col * flicker, 1.0);
}
