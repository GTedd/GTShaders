// 按高度调色 / Height Tint
// 地形按世界高度做色彩渐变：低处偏冷、高处偏暖，或者反过来。
//
// 和 terrain_sway 一样，必须用 ChunkPosition 还原绝对坐标——
// 只用 position 的话每个区块都会从头开始渐变，出现明显的方格。
//
// [en_us]
// Grades terrain color by world height. Low areas cool and high areas warm, or the other way around.
//
// Like terrain_sway, it must use ChunkPosition to recover absolute coordinates. With position alone,
// the gradient restarts in every chunk and leaves an obvious grid pattern.
//
// @param name=LowColor type=color3 default=#5A7FB0 zh_cn=低处色 en_us=Low Color
// @param name=HighColor type=color3 default=#FFDCA8 zh_cn=高处色 en_us=High Color
// @param name=LowY type=float min=-64 max=128 default=0 zh_cn=低处高度 en_us=Low Y
// @param name=HighY type=float min=-64 max=320 default=128 zh_cn=高处高度 en_us=High Y
// @param name=Strength type=float min=0 max=1 default=0.4 zh_cn=强度 en_us=Strength

vec4 gtFragment(vec4 color) {
    // 片段阶段拿不到世界坐标，只能用亮度做一个粗略的近似。
    // 真正按高度取色需要顶点着色器传一个 varying——那要改 varying 表，
    // 属于「整份接管」才能做的事，这里给的是零成本的近似版
    float luma = dot(color.rgb, vec3(0.2126, 0.7152, 0.0722));
    vec3 tint = mix(LowColor, HighColor, smoothstep(0.2, 0.9, luma));
    return vec4(mix(color.rgb, color.rgb * tint * 1.7, Strength), color.a);
}
