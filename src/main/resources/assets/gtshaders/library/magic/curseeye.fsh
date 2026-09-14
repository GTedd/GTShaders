// 诅咒之眼 / Curse Eye
// 视野被压成一只竖瞳的形状，四周吞进黑暗，瞳孔本身在缓慢收缩、偶尔眨一下。
// 眨眼是整套效果的关键：上下眼睑同时合拢再张开，那零点几秒的全黑比任何暗角都吓人。
//
// [en_us]
// Vision squeezed into a blinking slit-pupil eye.
// The view is squeezed into the shape of a vertical slit pupil, with darkness swallowing everything around
// it. The pupil slowly contracts and blinks now and then. The blink is the key to the whole effect: upper and
// lower eyelids close together and open again, and that fraction of a second of total black is scarier than
// any vignette.
//
// @param name=Openness type=float min=0 max=1 default=0.75 zh_cn=睁眼程度 en_us=Openness
// @param name=BlinkPeriod type=float min=0 max=20 default=5 zh_cn=眨眼间隔(秒) en_us=Blink Interval desc_zh_cn=设为 0 就不眨眼 desc_en_us=Set to 0 to never blink
// @param name=BlinkSpeed type=float min=1 max=20 default=7 zh_cn=眨眼速度 en_us=Blink Speed

// @group 瞳孔 / Pupil
// @param name=PupilWidth type=float min=0.02 max=0.6 default=0.18 zh_cn=瞳宽 en_us=Pupil Width
// @param name=PupilBreathe type=float min=0 max=1 default=0.35 zh_cn=瞳孔收缩 en_us=Pupil Breathe
// @param name=Rate type=float min=0 max=4 default=0.8 zh_cn=收缩频率 en_us=Breathe Rate

// @group 外观 / Look
// @param name=IrisColor type=color3 default=#C81E1E zh_cn=虹膜色 en_us=Iris Color
// @param name=IrisGlow type=float min=0 max=3 default=0.8 zh_cn=虹膜亮度 en_us=Iris Glow
// @param name=Darkness type=float min=0 max=1 default=0.92 zh_cn=四周黑度 en_us=Surround Darkness
// @param name=Warp type=float min=0 max=0.1 default=0.02 zh_cn=边缘牵扯 en_us=Edge Pull

void main() {
    vec2 asp = vec2(OutSize.x / max(OutSize.y, 1.0), 1.0);
    vec2 d = (texCoord - 0.5) * asp;

    // 眨眼：一个短促的合—开脉冲，其余时间完全不影响
    float blink = 0.0;
    if (BlinkPeriod > 0.01) {
        float age = mod(GTTime, BlinkPeriod);
        float k = clamp(1.0 - age * BlinkSpeed, 0.0, 1.0);
        blink = sin(k * 3.14159265);   // 合上再张开
    }
    float open = clamp(Openness * (1.0 - blink), 0.0, 1.0);

    // 竖瞳：横向压得比纵向狠，得到一个立着的橄榄形
    float breathe = 1.0 + sin(GTTime * Rate) * PupilBreathe * 0.5;
    float wide = max(PupilWidth * breathe, 1e-3);
    float tall = max(open * 0.55, 1e-3);
    float e = length(vec2(d.x / wide, d.y / tall));

    // 边缘牵扯：靠近眼睑处画面被往中间拉，像被眼皮压住
    vec2 uv = clamp(texCoord + normalize(d + 1e-6) * -Warp * smoothstep(0.6, 1.4, e), vec2(0.0), vec2(1.0));
    vec3 col = texture(InSampler, uv).rgb;

    float inside = smoothstep(1.15, 0.85, e);
    col *= mix(1.0 - Darkness, 1.0, inside);
    // 虹膜：瞳孔边缘那一圈亮环
    col += IrisColor * smoothstep(0.35, 0.0, abs(e - 1.0)) * IrisGlow * (0.4 + 0.6 * open);

    fragColor = vec4(col, 1.0);
}
