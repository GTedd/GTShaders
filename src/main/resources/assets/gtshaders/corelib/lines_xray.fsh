// 碰撞箱筛选 / Hitbox Filter
//
// rendertype_lines 被碰撞箱、命中框、区块边界、结构方框、调试线<b>共用</b>。
// 原版给它们各自定死了顶点色，于是颜色成了唯一能在着色器里区分它们的依据。
//
// 这个思路来自 hi_mv 的「碰撞箱透视」资源包，是「一个文件服务多种对象」时的通用解法：
// 既然管线不告诉你在画什么，就从数据本身找线索。
//
// 注意：这里只做<b>筛选</b>（藏掉某些线），不做透视。
// 透视要改顶点着色器的深度，而 lines 的顶点着色器有两处 gl_Position，
// 自动注入不安全，所以这个示例不做透视。
//
// [en_us]
// Hides selected line types, like hitboxes or chunk borders. rendertype_lines is <b>shared</b> by hitboxes,
// block selection outlines, chunk borders, structure boxes and debug lines.
// Vanilla hard-codes a vertex color for each, so color is the only thing a shader can use to tell them apart.
//
// The idea comes from hi_mv's "hitbox X-ray" resource pack. It is the general fix whenever one file serves
// many kinds of objects: if the pipeline won't tell you what is being drawn, look for clues in the data itself.
//
// Note: this only <b>filters</b> (hides certain lines); it does not do X-ray.
// X-ray requires changing depth in the vertex shader, and the lines vertex shader writes gl_Position in two
// places, so automatic injection isn't safe and this example leaves X-ray out.
//
// @param name=HideBlue type=float min=0 max=1 default=1 zh_cn=藏蓝色线 en_us=Hide Blue
// @param name=HideRed type=float min=0 max=1 default=1 zh_cn=藏红色线 en_us=Hide Red
// @param name=HideGreen type=float min=0 max=1 default=0 zh_cn=藏绿色线 en_us=Hide Green
// @param name=Tint type=color3 default=#FFFFFF zh_cn=保留线的染色 en_us=Kept Line Tint

vec4 gtFragment(vec4 color) {
    vec3 c = color.rgb;
    bool pureBlue = c.b > 0.8 && c.r < 0.2 && c.g < 0.2;
    bool pureRed = c.r > 0.8 && c.g < 0.2 && c.b < 0.2;
    bool pureGreen = c.g > 0.8 && c.r < 0.2 && c.b < 0.2;

    // 用 alpha 归零而不是 discard：在 OIT 的 alpha 阶段两者行为不同，
    // 归零对两个阶段都是一致的"不可见"
    float hidden = max(max(pureBlue ? HideBlue : 0.0, pureRed ? HideRed : 0.0),
                       pureGreen ? HideGreen : 0.0);

    return vec4(c * Tint, color.a * (1.0 - hidden));
}
