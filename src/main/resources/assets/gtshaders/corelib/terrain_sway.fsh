// 地形随风摇摆 / Terrain Sway
//
// 顶点钩子做位移的经典用途。但地形有个<b>所有人第一次都会踩的坑</b>：
// terrain 的坐标是<b>区块相对</b>的，不是世界坐标。
// 直接拿 position 当相位，每个区块的边界上摇摆相位会对不上，出现一条明显的接缝。
//
// 这里用 ChunkPosition 还原绝对坐标再算相位，接缝就消失了。
//
// 另一件事：原版没有"这个方块是不是树叶"的信息，所以摇摆会作用到<b>所有</b>地形。
// 想只摇树叶，得靠贴图内容自己判断——那是另一个层次的话题。
//
// [en_us]
// Makes terrain sway in the wind. It's a classic use of vertex hook displacement, but terrain has a
// <b>trap everyone falls into the first time</b>: terrain coordinates are <b>chunk-relative</b>, not world
// coordinates. Use position directly as the phase, and the sway won't line up at chunk borders, leaving a
// visible seam.
//
// Here ChunkPosition recovers absolute coordinates before computing the phase, and the seam disappears.
//
// One more thing: vanilla has no "is this block leaves?" information, so the sway applies to <b>all</b>
// terrain. Swaying only leaves means telling them apart by texture content yourself, which is a topic for
// another level.
//
// @param name=Amount type=float min=0 max=0.2 default=0.04 zh_cn=摇摆幅度 en_us=Amount
// @param name=Speed type=float min=0 max=4 default=1 zh_cn=风速 en_us=Wind Speed
// @param name=Scale type=float min=0.05 max=2 default=0.35 zh_cn=波长密度 en_us=Wave Scale
// @param name=HeightBias type=float min=0 max=1 default=0.6 zh_cn=越高摇得越狠 en_us=Height Bias

vec3 gtVertex(vec3 position) {
    // ★ 关键：还原绝对坐标。少了这一步，区块边界上会有可见的接缝
    vec3 world = position + vec3(ChunkPosition);

    float t = GameTime * 24000.0 * Speed * 0.03;

    // 两个不同频率叠加，摇摆才不会看出明显周期
    float phase = world.x * Scale + world.z * Scale * 0.7;
    float sway = sin(phase + t) * 0.6 + sin(phase * 2.3 - t * 1.7) * 0.4;

    // 越高摇得越狠：草和树叶在上面，石头在下面。这是个粗糙但有效的近似
    float weight = mix(1.0, clamp(fract(world.y), 0.0, 1.0), HeightBias);

    return position + vec3(sway, 0.0, sway * 0.6) * Amount * weight;
}
