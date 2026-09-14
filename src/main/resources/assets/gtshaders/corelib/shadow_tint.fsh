// 彩色实体阴影 / Tinted Shadow
// 实体脚下那圈影子默认是纯黑半透明，染个色就有氛围了。
//
// [en_us]
// Tints the round shadow under entities. By default it is plain translucent black,
// and a bit of color adds atmosphere.
//
// @param name=ShadowColor type=color3 default=#2A1B4A zh_cn=阴影色 en_us=Shadow Color
// @param name=Opacity type=float min=0 max=2 default=1 zh_cn=浓度 en_us=Opacity

vec4 gtFragment(vec4 color) {
    return vec4(ShadowColor, color.a * Opacity);
}
