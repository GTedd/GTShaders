// 力场泡 / Force Field
// 球形护罩。核心是菲涅耳：正对视线的地方几乎全透，掠射角上才亮起来——
// 这是所有「玻璃/能量罩」看起来是球而不是圆片的唯一原因。
//
// [en_us]
// A spherical force-field shield with a Fresnel rim.
// The core is the Fresnel term: where the surface faces the viewer it is almost fully transparent, and it only
// lights up at grazing angles. That is the one and only reason any "glass/energy shield" reads as a sphere
// rather than a flat disc.
//
// @param name=Center type=vec2 min=0 max=1 default=0.5,0.5 zh_cn=球心 en_us=Center
// @param name=Radius type=float min=0.05 max=1.2 default=0.45 zh_cn=半径 en_us=Radius
// @param name=FieldColor type=color3 default=#5FE0C8 zh_cn=力场色 en_us=Field Color
// @param name=Fresnel type=float min=0.5 max=8 default=3 zh_cn=菲涅耳指数 en_us=Fresnel Power
// @param name=Refract type=float min=0 max=0.08 default=0.02 zh_cn=折射 en_us=Refraction
// @param name=Ripple type=float min=0 max=1 default=0.4 zh_cn=受击涟漪 en_us=Impact Ripple
// @param name=Speed type=float min=0 max=6 default=2 zh_cn=涟漪速度 en_us=Ripple Speed

void main() {
    vec2 asp = vec2(OutSize.x / max(OutSize.y, 1.0), 1.0);
    vec2 d = (texCoord - Center) * asp;
    float r = length(d) / max(Radius, 1e-4);

    if (r > 1.0) {
        fragColor = texture(InSampler, texCoord);
        return;
    }

    // 把平面距离还原成球面高度：这一步让后面的菲涅耳有真正的三维含义
    float h = sqrt(max(1.0 - r * r, 0.0));
    // 视线方向近似为 +Z，与球面法线的夹角就由 h 决定；h 小＝掠射＝亮
    float fresnel = pow(1.0 - h, Fresnel);

    // 球面法线的横向分量决定折射方向，边缘弯得最狠
    vec2 uv = texCoord + d / max(length(d), 1e-5) * (1.0 - h) * Refract;
    vec3 col = texture(InSampler, uv).rgb;

    // 受击涟漪：从球心往外扩的同心波
    float wave = sin(r * 18.0 - GTTime * Speed * 3.0);
    float ripple = max(wave, 0.0) * Ripple * (0.3 + 0.7 * r);

    col += FieldColor * (fresnel * 1.4 + ripple * 0.5);
    col = mix(col, col * FieldColor * 1.3, 0.18);   // 罩内轻微偏色
    fragColor = vec4(col, 1.0);
}
