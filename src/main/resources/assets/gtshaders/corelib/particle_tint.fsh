// 粒子染色 / Particle Tint
// 按粒子自身亮度做双色调映射。配合替换特定粒子贴图，可以只改某一种粒子。
//
// [en_us]
// Maps particles to two tones based on their own brightness. Combined with replacing specific particle
// textures, it lets you change just one kind of particle.
//
// @param name=DarkColor type=color3 default=#3A1F5C zh_cn=暗部色 en_us=Dark
// @param name=BrightColor type=color3 default=#FFD36B zh_cn=亮部色 en_us=Bright
// @param name=Strength type=float min=0 max=1 default=0.7 zh_cn=强度 en_us=Strength

vec4 gtFragment(vec4 color) {
    float luma = dot(color.rgb, vec3(0.2126, 0.7152, 0.0722));
    vec3 graded = mix(DarkColor, BrightColor, luma);
    return vec4(mix(color.rgb, graded, Strength), color.a);
}
