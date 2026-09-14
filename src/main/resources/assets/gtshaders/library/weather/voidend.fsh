// 末地虚空 / The Void
// 紫黑基调 + 缓慢旋转的星点 + 边缘往里吞的暗。
// 星点用极坐标网格生成，于是它们天然绕着视野中心转，像整个空间在缓慢自转。
//
// [en_us]
// The End's void: purple-black, with slowly spinning stars.
// A purple-black base, slowly rotating star specks, and darkness creeping in from the edges.
// The stars are generated on a polar grid, so they naturally circle the center of the view, as if the whole space
// were slowly rotating.
//
// @param name=VoidColor type=color3 default=#2A1140 zh_cn=虚空色 en_us=Void Color
// @param name=StarColor type=color3 default=#C9A6FF zh_cn=星点色 en_us=Star Color
// @param name=Depth type=float min=0 max=1 default=0.6 zh_cn=侵蚀程度 en_us=Depth
// @param name=Stars type=float min=0 max=1 default=0.5 zh_cn=星点密度 en_us=Star Density
// @param name=Spin type=float min=0 max=1 default=0.15 zh_cn=自转速度 en_us=Spin Speed

float hash(vec2 p) {
    return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453);
}

void main() {
    vec3 src = texture(InSampler, texCoord).rgb;
    float luma = dot(src, vec3(0.2126, 0.7152, 0.0722));

    vec2 asp = vec2(OutSize.x / max(OutSize.y, 1.0), 1.0);
    vec2 d = (texCoord - 0.5) * asp;
    float r = length(d);
    float ang = atan(d.y, d.x) + GTTime * Spin;

    // 极坐标网格：环向 40 格、径向 20 格，格子中心放一个星点
    vec2 polar = vec2(ang * 6.366, r * 20.0);
    vec2 cell = floor(polar);
    vec2 local = fract(polar) - 0.5;
    float seed = hash(cell);
    float star = smoothstep(0.18, 0.0, length(local)) * step(1.0 - Stars * 0.5, seed);
    // 每颗星有自己的闪烁相位
    star *= 0.45 + 0.55 * sin(GTTime * 2.5 + seed * 6.28);

    // 虚空从边缘往里吞：暗部先被吃掉，亮部（如玩家手上的东西）留得久一点
    float erode = smoothstep(0.25, 1.0, r * 1.3) * Depth + Depth * 0.35;
    vec3 col = mix(src, VoidColor * (0.35 + luma * 0.8), clamp(erode, 0.0, 1.0));
    col *= 1.0 - Depth * 0.3;
    col += StarColor * star * (0.4 + erode);

    fragColor = vec4(col, 1.0);
}
