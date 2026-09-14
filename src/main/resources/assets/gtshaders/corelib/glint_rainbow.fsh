// 彩虹附魔光 / Rainbow Glint
// 附魔物品上那层流动的紫色改成彩虹。改动范围很小、风险很低，适合练手。
//
// [en_us]
// Turns the flowing purple enchantment glint into a rainbow. The change is small and low-risk,
// which makes it a good one to practice on.
//
// @param name=Speed type=float min=0 max=4 default=1 zh_cn=流动速度 en_us=Speed
// @param name=Density type=float min=0.5 max=10 default=3 zh_cn=色带密度 en_us=Band Density
// @param name=Boost type=float min=0.5 max=3 default=1.3 zh_cn=亮度增益 en_us=Boost

vec3 hue(float h) {
    vec3 k = fract(vec3(h) + vec3(0.0, 2.0 / 3.0, 1.0 / 3.0));
    return clamp(abs(k * 6.0 - 3.0) - 1.0, 0.0, 1.0);
}

vec4 gtFragment(vec4 color) {
    float h = fract((texCoord0.x + texCoord0.y) * Density
                    + GameTime * 24000.0 * Speed * 0.02);
    return vec4(color.rgb * hue(h) * Boost, color.a);
}
