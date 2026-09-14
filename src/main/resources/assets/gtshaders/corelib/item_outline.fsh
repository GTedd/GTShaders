// 物品描边 / Item Outline
//
// 沿物品贴图的不透明边缘描一圈。这个效果存在的意义一半在观感，一半在<b>示范半径怎么取</b>。
//
// 常见写法是用偏导数求「一个屏幕像素在 UV 空间里有多大」：
//     vec2 dx = dFdx(texCoord0);  float radius = max(length(dx), length(dFdy(texCoord0))) * 5.0;
// 两个问题：
//   1. 偏导数按 2×2 像素块做差分。块跨过三角形边界、面在屏幕上被压成一条线时结果会炸，
//      表现为边缘一圈噪点；
//   2. 更要命的是<b>越界</b>——物品拿远了，这个 radius 会大到跨出精灵，
//      把图集里紧挨着的<b>另一个物品</b>的 alpha 采进来，描边于是随镜头乱闪。
//
// 这里改成以「纹素」为单位（gtTexelSize），宽度恒定、与距离无关；
// 再用 gtSpriteTexelClamped 在精灵边界外直接返回 0，物理上不可能越界。
//
// <b>只能内描边</b>：原版 item 着色器在 ALPHA_CUTOUT 分支里 discard 掉了透明像素，
// 物品轮廓外的片元根本不会跑到这里来，没有东西可以往上画。
//
// [en_us]
// Outlines the opaque edges of the item texture. The effect exists half for the look and half to
// <b>demonstrate how to pick the radius</b>.
//
// The common approach uses derivatives to find how big one screen pixel is in UV space:
//     vec2 dx = dFdx(texCoord0);  float radius = max(length(dx), length(dFdy(texCoord0))) * 5.0;
// Two problems:
//   1. Derivatives are differenced over 2×2 pixel blocks. When a block straddles a triangle edge, or a face
//      is squashed into a line on screen, the result blows up and shows as a ring of noise along the edge;
//   2. Worse, it <b>goes out of bounds</b>: hold the item farther away and this radius grows past the sprite,
//      pulling in the alpha of the <b>neighboring item</b> in the atlas, so the outline flickers with the camera.
//
// Here the unit is the texel instead (gtTexelSize), so the width is constant and independent of distance;
// gtSpriteTexelClamped then returns 0 outside the sprite bounds, making out-of-bounds sampling impossible.
//
// <b>Inner outline only</b>: the vanilla item shader discards transparent pixels in its ALPHA_CUTOUT branch,
// so fragments outside the item's silhouette never reach this code, and there is nothing to draw on.
//
// @param name=OutColor type=color3 default=#7A26FF zh_cn=描边色 en_us=Outline Color
// @param name=Width type=int min=1 max=4 default=1 zh_cn=描边宽度（纹素） en_us=Width (texels)
// @param name=Threshold type=float min=0.01 max=0.9 default=0.5 zh_cn=透明判定阈值 en_us=Alpha Threshold
// @param name=Glow type=float min=0 max=3 default=1.3 zh_cn=描边亮度 en_us=Glow
// @param name=ImgSize type=float min=8 max=128 default=16 zh_cn=贴图边长（像素） en_us=Texture Size (px)

vec4 gtFragment(vec4 color) {
    vec2 img = vec2(ImgSize);
    float w = float(Width);

    // 四邻域的原始纹理 alpha。取 min：四边里只要有一边是空的，这里就是轮廓。
    // 注意读的是 Sampler0 的<b>原始</b> alpha，不是 color.a——后者已经乘过
    // vertexColor 和光照，在暗处会把整个物品都判成边缘
    float n = gtSpriteTexelClamped(vec2(0.0,  w), img).a;
    float s = gtSpriteTexelClamped(vec2(0.0, -w), img).a;
    float e = gtSpriteTexelClamped(vec2( w, 0.0), img).a;
    float t = gtSpriteTexelClamped(vec2(-w, 0.0), img).a;

    float edge = 1.0 - smoothstep(0.0, Threshold, min(min(n, s), min(e, t)));
    // 自己是透明的地方不画。不加这一句，物品外圈那些侥幸没被 discard 的
    // 半透明像素会被涂成实心描边色
    edge *= step(Threshold, color.a);

    return vec4(mix(color.rgb, OutColor * Glow, edge), color.a);
}
