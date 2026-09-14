// 界面染色 / GUI Tint
// gui 是最简单的一个核心着色器——只有 Position 和 Color 两个属性。
// 适合用来确认"我到底有没有改对文件"：改了之后物品栏底色立刻会变。
//
// [en_us]
// Tints the GUI, the simplest core shader of all. It has only two attributes, Position and Color.
// Handy for confirming "did I actually edit the right file?": change it and the inventory background
// shifts at once.
//
// @param name=Tint type=color3 default=#8FB4FF zh_cn=染色 en_us=Tint
// @param name=Strength type=float min=0 max=1 default=0.4 zh_cn=强度 en_us=Strength

vec4 gtFragment(vec4 color) {
    return vec4(mix(color.rgb, color.rgb * Tint * 1.5, Strength), color.a);
}
