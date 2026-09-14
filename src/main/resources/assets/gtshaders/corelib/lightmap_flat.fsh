// 全亮 / Fullbright
//
// 挖矿用。把整张查找表推平，于是无论方块光还是天空光都接近满值——
// 效果等同于夜视，但它是<b>改渲染</b>而不是加药水效果。
//
// 这是学光照贴图最快的入口：只有一行有效逻辑，改完立刻能看出这张表干什么用的。
// 把 Level 调回 0 就是原版。
//
// [en_us]
// Fullbright for mining. It flattens the whole lookup table, so both block light and skylight sit near full.
// The result matches Night Vision, but it <b>changes rendering</b> instead of adding a potion effect.
//
// This is the fastest way into lightmaps: there is only one line of real logic, and once you change it you
// immediately see what the table is for.
// Set Level back to 0 for vanilla.
//
// @param name=Level type=float min=0 max=1 default=0.85 zh_cn=提亮程度 en_us=Brightness
// @param name=KeepTint type=float min=0 max=1 default=0.3 zh_cn=保留原本色调 en_us=Keep Original Tint

vec4 gtFragment(vec4 color) {
    // 直接 mix 到白色会把原版精心调过的色调全抹掉，
    // 留一点原色让画面不至于像贴了张白纸
    //
    // 变量别叫 flat：那是 GLSL 的插值限定符关键字（flat in vec4 x），
    // 拿来当变量名 ShaderC 直接报语法错误
    vec3 evened = mix(vec3(1.0), color.rgb, KeepTint);
    return vec4(mix(color.rgb, evened, Level), color.a);
}
