// 云层染色 / Cloud Tint
// 按云的明暗分别染色：亮面偏暖、暗面偏冷，立刻就有体积感。
// 单色染色只会让云看起来像贴纸。
//
// [en_us]
// Tints clouds separately by light and shade. Warm lit sides and cool shaded sides give instant volume.
// A single flat tint just makes clouds look like stickers.
//
// @param name=LitColor type=color3 default=#FFE8C8 zh_cn=亮面色 en_us=Lit Color
// @param name=ShadeColor type=color3 default=#8FA8C8 zh_cn=暗面色 en_us=Shade Color
// @param name=Strength type=float min=0 max=1 default=0.6 zh_cn=强度 en_us=Strength

vec4 gtFragment(vec4 color) {
    float luma = dot(color.rgb, vec3(0.2126, 0.7152, 0.0722));
    vec3 tint = mix(ShadeColor, LitColor, smoothstep(0.35, 0.85, luma));
    return vec4(mix(color.rgb, color.rgb * tint * 1.6, Strength), color.a);
}
