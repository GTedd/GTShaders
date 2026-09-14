// 能量外壳 / Energy Shell
// 给发光实体套一层贴着剪影的能量壳：边缘最亮，向内衰减成一层薄薄的辉光。
//
// 这是实体轮廓层最基础的形态，也是理解这条路径的最短样本：
//   gtMask()     = 1 在实体内、0 在外（剪影覆盖）
//   gtMaskEdge() = 剪影边缘强度
//   输出 alpha   = 这块像素有多大程度替换原画面（引擎做的是 alpha 混合）
//
// 三个让它像"能量场"而不像"描了个边"的细节：
//   1. 边缘那圈必须比内部亮一个数量级，能量壳的实体感全在这道边上；
//   2. 内部要有沿时间流动的纹路，静止的填充看上去只是块半透明贴纸；
//   3. 颜色默认取实体自己的轮廓色（队伍颜色），这样同一份效果能被服务端染成不同阵营。
//
// [en_us]
// An energy shell that hugs a glowing entity's silhouette.
// The edge is brightest and fades inward into a thin layer of glow.
//
// This is the most basic form of the entity outline layer, and the shortest sample for understanding this path:
//   gtMask()     = 1 inside the entity, 0 outside (silhouette coverage)
//   gtMaskEdge() = silhouette edge strength
//   output alpha = how much this pixel replaces the original picture (the engine does alpha blending)
//
// Three details that make it look like an "energy field" rather than "an outline traced around it":
//   1. The edge must be an order of magnitude brighter than the interior. The shell's sense of substance lives
//      entirely in that edge.
//   2. The interior needs patterns flowing over time. A static fill just looks like a translucent sticker.
//   3. The color defaults to the entity's own outline color (team color), so the server can tint the same
//      effect for different factions.

// @param name=UseTeamColor type=bool default=1 zh_cn=用队伍颜色 en_us=Use Team Color desc_zh_cn=开启时取实体自己的轮廓色（记分板队伍颜色），关闭时用下面那个固定色 desc_en_us=Take the colour from the entity outline (scoreboard team colour) instead of the fixed one below
// @param name=Color type=color3 default=#66E0FF zh_cn=固定色 en_us=Fixed Color
// @param name=EdgeGlow type=float min=0 max=6 default=2.6 zh_cn=边缘强度 en_us=Edge Glow
// @param name=Fill type=float min=0 max=1 default=0.22 zh_cn=内部填充 en_us=Inner Fill
// @param name=Flow type=float min=0 max=6 default=1.6 zh_cn=纹路流速 en_us=Flow Speed
// @param name=Scale type=float min=2 max=80 default=26 zh_cn=纹路密度 en_us=Pattern Scale

void main() {
    float inside = gtMask();
    if (inside <= 0.001 && gtMaskEdge() <= 0.001) {
        // 完全在剪影之外：alpha 给 0，原画面原样透出。
        // 这一步必须显式做——轮廓层的输出会被 alpha 混合到整个屏幕上，
        // 不归零的话没有实体的地方也会蒙上一层
        fragColor = vec4(0.0);
        return;
    }

    vec3 tint = UseTeamColor > 0.5 ? gtMaskColor() : Color;
    // 队伍颜色可能是黑的（没设队伍时轮廓色接近 0），那样整个效果会消失。兜一个底
    if (UseTeamColor > 0.5 && dot(tint, vec3(1.0)) < 0.05) {
        tint = Color;
    }

    float edge = gtMaskEdge();

    // 内部纹路：斜向的行进条纹，只在剪影内部出现
    float stripe = sin((texCoord.x + texCoord.y) * Scale + GTTime * Flow * 3.0) * 0.5 + 0.5;
    float body = inside * Fill * (0.55 + 0.45 * stripe);

    vec3 col = tint * (edge * EdgeGlow + body * 2.0);
    // alpha 取边缘与内部的较大值：边缘实打实，内部只是一层薄雾
    float alpha = max(edge, body);
    fragColor = vec4(col, clamp(alpha, 0.0, 1.0));
}
