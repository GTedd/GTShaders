// 彩虹信标 / Rainbow Beacon
// 光柱沿高度做色相渐变并随时间滚动。
//
// [en_us]
// Beacon beam hue shifts with height and scrolls over time.
//
// @param name=Speed type=float min=0 max=4 default=1 zh_cn=滚动速度 en_us=Speed
// @param name=Density type=float min=0.1 max=8 default=1.5 zh_cn=色带密度 en_us=Band Density
// @param name=Strength type=float min=0 max=1 default=0.85 zh_cn=强度 en_us=Strength

vec3 hue(float h) {
    vec3 k = fract(vec3(h) + vec3(0.0, 2.0 / 3.0, 1.0 / 3.0));
    return clamp(abs(k * 6.0 - 3.0) - 1.0, 0.0, 1.0);
}

vec4 gtFragment(vec4 color) {
    // texCoord0.y 沿光柱高度变化，正好当色相轴
    float h = fract(texCoord0.y * Density + GameTime * 24000.0 * Speed * 0.01);
    return vec4(mix(color.rgb, color.rgb * hue(h) * 2.0, Strength), color.a);
}
