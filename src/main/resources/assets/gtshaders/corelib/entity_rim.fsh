// 实体轮廓光 / Entity Rim Light
//
// 让生物边缘泛起一圈光。做法是判断"这个面有多背对镜头"——
// 越接近侧面（掠射角）越亮，这就是菲涅耳的直觉版本。
//
// 注意 entity 是<b>一个文件管所有实体</b>，启用后每个生物都会有轮廓光。
// 想只影响某一类，得靠贴图内容自己判断。
//
// [en_us]
// Adds a glowing rim around mobs. The trick is to measure how far a face is turned away from the camera:
// the closer to edge-on (grazing angle), the brighter. That is the intuitive version of Fresnel.
//
// Note that entity is <b>one file for every entity</b>, so once enabled every mob gets a rim light.
// To affect only one kind, you have to tell them apart by texture content yourself.
//
// @param name=RimColor type=color3 default=#66E0FF zh_cn=轮廓色 en_us=Rim Color
// @param name=Power type=float min=0.5 max=8 default=3 zh_cn=收束度 en_us=Falloff Power
// @param name=Strength type=float min=0 max=2 default=0.8 zh_cn=强度 en_us=Strength

vec4 gtFragment(vec4 color) {
    // 传进来的 color 已经乘过原版的方向光照：正对光源的面亮、背对的暗。
    // 拿它的亮度当"这个面朝向如何"的廉价替身——真正的法线在片段阶段拿不到。
    //
    // ★ 刻意<b>不</b>直接读 vertexColor：那个 varying 在 PER_FACE_LIGHTING 变体下
    //   根本不存在（换成了 vertexPerFaceColorFront/Back），读它会让那个变体编译失败。
    //   钩子参数是唯一在所有变体里都存在的东西。
    float facing = dot(color.rgb, vec3(0.2126, 0.7152, 0.0722));

    // 越暗（越背光）越亮，于是光集中在轮廓上
    float rim = pow(clamp(1.0 - facing, 0.0, 1.0), Power);

    return vec4(color.rgb + RimColor * rim * Strength, color.a);
}
