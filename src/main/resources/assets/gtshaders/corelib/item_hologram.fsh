// 物品全息 / Item Hologram
// 单色 + 扫描线 + 上滚亮带。和后处理版全息的区别：这一版只作用在物品上，
// 而且会被正确遮挡——后处理那版是糊在整张画面上的。
//
// <b>这个效果曾经是坏的</b>，坏在一个所有人都会踩的地方：把 texCoord0 当成了面内坐标。
// item 的 Sampler0 绑的是<b>方块图集</b>，一张上千像素的大图；一个 16 像素的物品在里面
// 只跨 16/1024 = 0.0156。于是 sin(texCoord0.y * 140 * π) 在整个物品上几乎是同一个值——
// 扫描线不是变淡了，是压根没画出来。
// gtSpriteUV 把图集坐标还原成这个精灵内部的 0..1。
//
// [en_us]
// Monochrome, scanlines and an upward-scrolling bright band. Unlike the post-processing hologram, this one
// only affects items and is properly occluded. The post-processing version is smeared over the whole screen.
//
// <b>This effect used to be broken</b>, by a trap everyone falls into: treating texCoord0 as face-local
// coordinates. item's Sampler0 is bound to the <b>block atlas</b>, one big texture over a thousand pixels wide,
// and a 16-pixel item spans only 16/1024 = 0.0156 of it. So sin(texCoord0.y * 140 * π) is nearly constant
// across the whole item. The scanlines weren't faint; they were never drawn at all.
// gtSpriteUV turns atlas coordinates back into 0..1 within the sprite.
//
// @param name=HoloColor type=color3 default=#63E8FF zh_cn=全息色 en_us=Hologram Color
// @param name=Lines type=float min=2 max=64 default=16 zh_cn=扫描条数 en_us=Line Count
// @param name=LineDark type=float min=0 max=1 default=0.45 zh_cn=扫描条深度 en_us=Line Darkness
// @param name=Sweep type=float min=0 max=3 default=0.6 zh_cn=亮带速度 en_us=Sweep Speed
// @param name=ImgSize type=float min=8 max=128 default=16 zh_cn=贴图边长（像素） en_us=Texture Size (px)

vec4 gtFragment(vec4 color) {
    // 面内 0..1 坐标。物品贴图几乎都是 16×16，ImgSize 填错只会让条纹相位偏移，不会崩
    vec2 uv = gtSpriteUV(vec2(ImgSize));

    float luma = dot(color.rgb, vec3(0.2126, 0.7152, 0.0722));
    vec3 holo = HoloColor * (0.3 + luma * 1.4);

    // Lines 现在是<b>条数</b>而不是过去那个没有量纲的密度值。
    // 16 条正好一条线压一个纹素——再密就开始摩尔纹了
    float line = 0.5 + 0.5 * sin(uv.y * Lines * 6.28318);
    holo *= 1.0 - LineDark * (1.0 - line);

    float sweep = fract(uv.y - GameTime * 24000.0 * Sweep * 0.01);
    holo += HoloColor * smoothstep(0.9, 1.0, sweep) * 0.6;

    return vec4(holo, color.a);
}
