// 屏幕裂纹 / Screen Crack
// 从一点辐射出去的裂纹。用 Voronoi 的「到最近两个特征点的距离差」画裂缝——
// 这个差值在两个种子点的等距线上为零，正好构成一张天然的裂纹网。
// 再叠一层沿半径的放射状主裂缝，就有了受击点。
//
// [en_us]
// Cracked screen glass radiating from a point of impact.
// The cracks are drawn with the Voronoi "difference between the distances to the two nearest feature points":
// that difference is zero on the line equidistant from two seed points, which forms a natural web of cracks.
// Add a layer of main cracks radiating along the radius on top, and you have a point of impact.
//
// @param name=Impact type=vec2 min=0 max=1 default=0.5,0.55 zh_cn=受击点 en_us=Impact Point
// @param name=Severity type=float min=0 max=1 default=0.6 zh_cn=破碎程度 en_us=Severity
// @param name=Cells type=float min=3 max=30 default=10 zh_cn=碎片密度 en_us=Shard Density
// @param name=Refract type=float min=0 max=0.05 default=0.012 zh_cn=碎片折射 en_us=Shard Refraction
// @param name=Radial type=float min=0 max=1 default=0.6 zh_cn=放射裂缝 en_us=Radial Cracks
// @param name=Highlight type=color3 default=#FFFFFF zh_cn=裂缝高光 en_us=Crack Highlight

vec2 hash2(vec2 p) {
    return fract(sin(vec2(dot(p, vec2(127.1, 311.7)), dot(p, vec2(269.5, 183.3)))) * 43758.5453);
}

// 返回 (最近距离, 次近距离, 最近特征点的编号)
vec3 voronoi2(vec2 p) {
    vec2 g = floor(p);
    vec2 f = fract(p);
    float d1 = 8.0;
    float d2 = 8.0;
    float id = 0.0;
    for (int y = -1; y <= 1; y++) {
        for (int x = -1; x <= 1; x++) {
            vec2 o = vec2(float(x), float(y));
            vec2 c = o + hash2(g + o) - f;
            float d = length(c);
            if (d < d1) {
                d2 = d1;
                d1 = d;
                id = dot(g + o, vec2(7.13, 3.71));
            } else if (d < d2) {
                d2 = d;
            }
        }
    }
    return vec3(d1, d2, id);
}

void main() {
    vec2 asp = vec2(OutSize.x / max(OutSize.y, 1.0), 1.0);
    vec2 d = (texCoord - Impact) * asp;
    float r = length(d);
    float ang = atan(d.y, d.x);

    // 离受击点越远裂纹越少
    float reach = smoothstep(Severity * 1.3, 0.0, r);

    vec3 v = voronoi2(texCoord * asp * Cells);
    // 两个距离之差在等距线上趋近 0，正是裂缝所在
    float web = smoothstep(0.06, 0.0, v.y - v.x) * reach;

    // 放射状主裂缝：把角度切成若干扇区，扇区边界就是裂缝
    float spokes = smoothstep(0.06, 0.0, abs(fract(ang / 6.28319 * 9.0 + 0.5) - 0.5) * 2.0);
    spokes *= Radial * smoothstep(0.02, 0.15, r) * reach;

    float crack = clamp(web + spokes, 0.0, 1.0);

    // 每块碎片整体偏一点，玻璃碎了之后本来就不共面了
    vec2 shard = (hash2(vec2(v.z, v.z * 1.7)) - 0.5) * Refract * reach;
    vec3 col = texture(InSampler, texCoord + shard).rgb;

    // 裂缝本身：暗芯 + 亮边，这是玻璃断面的样子
    col = mix(col, col * 0.25, crack);
    col += Highlight * smoothstep(0.09, 0.05, v.y - v.x) * reach * 0.6;

    // 受击点的粉碎区
    col = mix(col, col * 0.5 + Highlight * 0.35, smoothstep(0.05, 0.0, r) * Severity);
    fragColor = vec4(col, 1.0);
}
