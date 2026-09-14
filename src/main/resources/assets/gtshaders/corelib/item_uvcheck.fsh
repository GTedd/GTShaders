// 精灵坐标自检 / Sprite UV Check
//
// 不是拿来看的效果，是拿来<b>确认坐标对不对</b>的量具。写任何依赖面内坐标的
// 核心着色器之前先挂上它，能省掉「代码没错但效果不出现」的一整晚。
//
// texCoord0 是<b>图集</b>坐标：item / terrain / block / particle / crumbling / text
// 的 Sampler0 都是一张上千像素的图集，一个 16 像素的精灵在里面只跨 0.0156。
// 而 entity / glint / beacon_beam 绑的是独立纹理，texCoord0 恰好就是 0..1。
// 同一行代码在两边一个对一个错，肉眼分不出来——所以要有这把尺子。
//
// [en_us]
// A gauge for <b>verifying coordinates</b>, not an effect to look at. Attach it before writing any core shader
// that relies on face-local coordinates, and save yourself a whole night of "the code is right but the effect
// never shows up".
//
// texCoord0 is an <b>atlas</b> coordinate: for item / terrain / block / particle / crumbling / text,
// Sampler0 is an atlas over a thousand pixels wide, where a 16-pixel sprite spans only 0.0156.
// entity / glint / beacon_beam, on the other hand, bind standalone textures, so texCoord0 happens to be 0..1.
// The same line of code is right on one side and wrong on the other, and you can't tell by eye.
// That's why you need this ruler.
//
// @param name=Mode type=int min=0 max=2 default=0 zh_cn=模式 en_us=Mode desc_zh_cn=0=面内 UV；1=图集坐标对照；2=纹素棋盘 desc_en_us=0 = sprite UV, 1 = atlas-coord comparison, 2 = texel checker
// @param name=ImgSize type=float min=8 max=128 default=16 zh_cn=贴图边长（像素） en_us=Texture Size (px)

vec4 gtFragment(vec4 color) {
    vec2 uv = gtSpriteUV(vec2(ImgSize));

    if (Mode == 0) {
        // 面内 UV：红=横向、绿=纵向。每个面都该是一整块<b>完整</b>的红绿渐变。
        //   渐变中间冒出接缝  → ImgSize 填小了，一个精灵被切成了好几份
        //   渐变只走到一半就没了 → ImgSize 填大了
        return vec4(uv.x, uv.y, 0.0, color.a);
    }

    if (Mode == 1) {
        // 左右对照，一眼看出这篇文档在讲什么：
        // 左半用 texCoord0（图集坐标）画条纹——几乎是一片纯色，条纹根本没出现；
        // 右半用 gtSpriteUV（面内坐标）画同样的条纹——清清楚楚。
        float src = uv.x < 0.5 ? texCoord0.y : uv.y;
        return vec4(vec3(step(0.5, fract(src * 16.0))), color.a);
    }

    // 纹素棋盘：ImgSize 填对时，一个面正好是 ImgSize×ImgSize 个格子，
    // 且每个格子边界与贴图的像素边界重合。数一下就知道贴图到底多大
    vec2 cell = floor(uv * ImgSize);
    float chk = mod(cell.x + cell.y, 2.0);
    return vec4(mix(color.rgb * 0.3, color.rgb * 1.8, chk), color.a);
}
