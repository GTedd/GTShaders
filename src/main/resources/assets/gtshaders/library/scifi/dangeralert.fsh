// 危险警报 / Danger Alert
// 四周红光一亮一灭，上下压两条斜纹警示带，画面轻微抽动——飞船要炸了的那一套。
// 闪烁不是正弦：真实的警报灯是"亮一下、暗一段"，占空比明显偏小，所以用 pow 把波形压尖。
//
// [en_us]
// Flashing red alarm with hazard stripes and screen shake.
// Red light around the edges flashes on and off, two diagonal hazard stripes press in from top and bottom, and the
// picture twitches slightly: the full "the ship is about to blow" treatment.
// The flashing is not a sine wave. A real alarm light goes "a short flash, then a longer dark gap", with a clearly
// small duty cycle, so pow is used to sharpen the waveform into spikes.
//
// @param name=Rate type=float min=0.2 max=8 default=1.4 zh_cn=闪烁频率 en_us=Alarm Rate
// @param name=Sharpness type=float min=1 max=12 default=4 zh_cn=闪烁尖锐度 en_us=Pulse Sharpness
// @param name=Level type=float min=0 max=1 default=0.7 zh_cn=警戒等级 en_us=Alert Level desc_zh_cn=同时控制亮度、抖动和条纹速度，一个滑条就能从"提示"推到"要炸了" desc_en_us=Drives brightness, shake and stripe speed together

// @group 边缘 / Edge
// @param name=AlertColor type=color3 default=#FF2020 zh_cn=警报色 en_us=Alert Color
// @param name=EdgeWidth type=float min=0.05 max=1 default=0.45 zh_cn=红光范围 en_us=Edge Reach
// @param name=Gain type=float min=0 max=4 default=1.6 zh_cn=亮度 en_us=Gain

// @group 警示带 / Hazard Bars
// @param name=BarHeight type=float min=0 max=0.2 default=0.045 zh_cn=条带高度 en_us=Bar Height
// @param name=BarSpeed type=float min=0 max=3 default=0.6 zh_cn=条带滚动 en_us=Bar Scroll
// @param name=BarColor type=color3 default=#FFC400 zh_cn=条带色 en_us=Bar Color

// @group 抖动 / Shake
// @param name=Shake type=float min=0 max=0.03 default=0.005 zh_cn=抖动幅度 en_us=Shake
// @param name=Desaturate type=float min=0 max=1 default=0.35 zh_cn=画面失色 en_us=Desaturate

void main() {
    // 尖脉冲：亮一下、暗一段，不是平滑的正弦
    float phase = fract(GTTime * Rate);
    float pulse = pow(max(sin(phase * 3.14159265), 0.0), Sharpness);
    float lvl = clamp(Level, 0.0, 1.0);

    vec2 jit = vec2(sin(GTTime * 57.0), cos(GTTime * 43.0)) * Shake * lvl * pulse;
    vec2 uv = clamp(texCoord + jit, vec2(0.0), vec2(1.0));
    vec3 col = texture(InSampler, uv).rgb;

    float g = dot(col, vec3(0.2126, 0.7152, 0.0722));
    col = mix(col, vec3(g), Desaturate * lvl);

    // 边缘红光：离中心越远越亮
    vec2 asp = vec2(OutSize.x / max(OutSize.y, 1.0), 1.0);
    float r = length((texCoord - 0.5) * asp) / max(length(asp) * 0.5, 1e-4);
    float edge = smoothstep(1.0 - EdgeWidth, 1.05, r);
    col += AlertColor * edge * pulse * Gain * lvl;

    // 上下警示带：45 度斜纹
    if (BarHeight > 0.001) {
        float inBar = step(texCoord.y, BarHeight) + step(1.0 - BarHeight, texCoord.y);
        float diag = fract((texCoord.x * asp.x + texCoord.y) * 18.0 - GTTime * BarSpeed);
        float stripe = step(0.5, diag);
        vec3 barCol = mix(vec3(0.05), BarColor, stripe);
        col = mix(col, barCol * (0.5 + 0.5 * pulse), clamp(inBar, 0.0, 1.0) * lvl);
    }

    fragColor = vec4(col, 1.0);
}
