// 中毒 / Poison
// 病态绿的关键不是"加绿"，而是把红色抽掉：中毒观感来自补色缺失，
// 直接叠绿只会让画面像戴了副墨镜。再配一层缓慢的视野扭动表现眩晕。
//
// [en_us]
// Poison: sickly green tint with a slow, woozy sway.
// The trick to a sickly green isn't adding green but draining red: the poisoned look comes from the missing
// complementary color, and simply overlaying green just looks like tinted sunglasses. A slow warping of the view
// on top conveys the dizziness.
//
// @param name=Tint type=color3 default=#6BBF3A zh_cn=毒素色 en_us=Toxin Color
// @param name=RedDrain type=float min=0 max=1 default=0.55 zh_cn=抽离红色 en_us=Drain Red
// @param name=Pulse type=float min=0 max=3 default=1.1 zh_cn=脉动速度 en_us=Pulse Speed
// @param name=Swim type=float min=0 max=0.02 default=0.004 zh_cn=眩晕扭动 en_us=Swim
// @param name=Vignette type=float min=0 max=2 default=0.9 zh_cn=暗角 en_us=Vignette

void main() {
    // 两个不同频率的正弦叠加，避免扭动看出明显周期
    vec2 uv = texCoord;
    uv.x += sin(texCoord.y * 11.0 + GTTime * 1.3) * Swim;
    uv.y += cos(texCoord.x * 9.0 - GTTime * 1.7) * Swim;

    vec3 src = texture(InSampler, uv).rgb;
    float luma = dot(src, vec3(0.2126, 0.7152, 0.0722));

    float pulse = 0.5 + 0.5 * sin(GTTime * Pulse * 3.14159);
    vec3 col = src;
    col.r = mix(col.r, luma * 0.35, RedDrain * (0.7 + 0.3 * pulse));
    col = mix(col, luma * Tint * 1.6, 0.35 + 0.25 * pulse);

    vec2 d = texCoord - 0.5;
    col *= clamp(1.0 - dot(d, d) * Vignette, 0.0, 1.0);
    fragColor = vec4(col, 1.0);
}
