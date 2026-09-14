// 文字渐变 / Text Gradient
// 所有文字按屏幕高度做渐变。26.3 的 text 还有 IS_GUI / IS_SEE_THROUGH 开关，
// 可以只影响界面里的字——那需要在源码里写 #ifdef，属于进阶用法。
//
// [en_us]
// Applies a gradient to all text by screen height. In 26.3, text also has IS_GUI / IS_SEE_THROUGH switches
// that let you affect only GUI text. That requires writing #ifdef in the source, which is an advanced use.
//
// @param name=TopColor type=color3 default=#FFE9A0 zh_cn=上端色 en_us=Top Color
// @param name=BottomColor type=color3 default=#FF7AC8 zh_cn=下端色 en_us=Bottom Color
// @param name=Strength type=float min=0 max=1 default=0.7 zh_cn=强度 en_us=Strength

vec4 gtFragment(vec4 color) {
    float t = clamp(gl_FragCoord.y / max(ScreenSize.y, 1.0), 0.0, 1.0);
    vec3 grad = mix(BottomColor, TopColor, t);
    return vec4(mix(color.rgb, color.rgb * grad * 1.8, Strength), color.a);
}
