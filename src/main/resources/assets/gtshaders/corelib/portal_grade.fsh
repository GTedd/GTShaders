// 传送门调色 / Portal Grade
// 末地传送门是原版唯一内置的程序化效果——多层视差星空。
// 这里不重写它，只在它算完之后调色，于是那套星空的运动全部保留。
//
// [en_us]
// Color-grades the End portal effect. The End portal is vanilla's only built-in procedural effect,
// a multi-layer parallax starfield.
// This doesn't rewrite it. It only grades the color after it is computed, so all of the starfield's
// motion is kept.
//
// @param name=Tint type=color3 default=#8A4BFF zh_cn=主色 en_us=Tint
// @param name=Contrast type=float min=0.5 max=3 default=1.4 zh_cn=对比度 en_us=Contrast
// @param name=Boost type=float min=0 max=3 default=1.2 zh_cn=亮度 en_us=Brightness

vec4 gtFragment(vec4 color) {
    vec3 c = clamp((color.rgb - 0.5) * Contrast + 0.5, 0.0, 1.0);
    return vec4(c * Tint * Boost, color.a);
}
