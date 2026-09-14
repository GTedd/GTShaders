// 传送门 / Portal
// 极坐标里的漩涡：把角度按半径扭一下，再让纹理沿半径往里流。
// 门内的画面做了反相 + 换色，「通向别处」这件事必须靠色彩说明白，
// 只做扭曲会被看成水面。
//
// [en_us]
// A swirling portal that shows a recolored world inside.
// A vortex in polar coordinates: twist the angle by the radius, then make the texture flow inward along the
// radius. The picture inside the portal is inverted and recolored, because "it leads somewhere else" has to be
// told through color. With distortion alone it would read as a water surface.
//
// @param name=Center type=vec2 min=0 max=1 default=0.5,0.5 zh_cn=门心 en_us=Center
// @param name=Radius type=float min=0.05 max=1 default=0.3 zh_cn=门半径 en_us=Radius
// @param name=Twist type=float min=0 max=12 default=4 zh_cn=扭曲量 en_us=Twist
// @param name=Suction type=float min=0 max=2 default=0.7 zh_cn=内吸速度 en_us=Suction
// @param name=PortalColor type=color3 default=#9A4BFF zh_cn=门色 en_us=Portal Color
// @param name=RimGlow type=float min=0 max=2 default=1 zh_cn=门框辉光 en_us=Rim Glow

void main() {
    vec2 asp = vec2(OutSize.x / max(OutSize.y, 1.0), 1.0);
    vec2 d = (texCoord - Center) * asp;
    float r = length(d) / max(Radius, 1e-4);
    float ang = atan(d.y, d.x);

    vec3 src = texture(InSampler, texCoord).rgb;
    if (r > 1.15) {
        fragColor = vec4(src, 1.0);
        return;
    }

    // 越靠近门心扭得越狠，同时整体随时间旋转
    float twist = Twist * (1.0 - clamp(r, 0.0, 1.0)) + GTTime * Suction;
    float cs = cos(twist);
    float sn = sin(twist);
    vec2 rot = vec2(d.x * cs - d.y * sn, d.x * sn + d.y * cs);
    // 半径也压缩一点，画面就有了被往里吸的观感
    vec2 uv = Center + rot / asp * (1.0 - 0.25 * (1.0 - clamp(r, 0.0, 1.0)));

    vec3 inner = texture(InSampler, uv).rgb;
    float luma = dot(inner, vec3(0.2126, 0.7152, 0.0722));
    inner = mix(1.0 - inner, PortalColor * (0.3 + luma), 0.55);

    float inside = smoothstep(1.0, 0.9, r);
    vec3 col = mix(src, inner, inside);

    // 门框：半径 1 附近的一圈亮环，带角向的能量纹
    float rim = smoothstep(0.14, 0.0, abs(r - 1.0));
    col += PortalColor * rim * RimGlow * (0.7 + 0.5 * sin(ang * 10.0 - GTTime * 3.0));
    fragColor = vec4(col, 1.0);
}
