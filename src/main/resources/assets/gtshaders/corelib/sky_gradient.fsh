// 自定义天空渐变 / Sky Gradient
//
// 天空是最适合第一次改核心着色器的地方：改坏了也只是天空难看，不影响任何玩法。
//
// 原版的天空色由 ColorModulator 给（它已经算好了时段、生物群系、天气），
// 所以这里不覆盖它，而是<b>在它的基础上做渐变</b>——这样日夜循环仍然正常。
//
// [en_us]
// Adds a custom gradient to the sky. The sky is the best place for your first core shader edit: if you break
// it, the sky just looks bad, and no gameplay is affected.
//
// Vanilla's sky color comes from ColorModulator (which already accounts for time of day, biome and weather),
// so this doesn't override it but <b>builds the gradient on top of it</b>.
// That way the day-night cycle still works.
//
// @param name=TopColor type=color3 default=#2B4B8C zh_cn=天顶色 en_us=Zenith
// @param name=HorizonColor type=color3 default=#FFB07A zh_cn=地平线色 en_us=Horizon
// @param name=Strength type=float min=0 max=1 default=0.5 zh_cn=强度 en_us=Strength
// @param name=Curve type=float min=0.2 max=4 default=1.4 zh_cn=渐变曲线 en_us=Gradient Curve

vec4 gtFragment(vec4 color) {
    // 天空穹顶是绕玩家的一个球，屏幕纵坐标就足以当"看多高"用。
    // 用 gl_FragCoord 而不是某个 varying：天空着色器没有可用的位置 varying
    float height = gl_FragCoord.y / max(ScreenSize.y, 1.0);
    float t = pow(clamp(height, 0.0, 1.0), Curve);

    vec3 gradient = mix(HorizonColor, TopColor, t);
    return vec4(mix(color.rgb, color.rgb * gradient * 2.0, Strength), color.a);
}
