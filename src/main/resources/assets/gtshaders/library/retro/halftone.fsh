// 漫画网点 / Halftone
// 印刷网屏：每个网格里画一个圆，圆的半径由该处的亮度决定。
// 网格必须旋转一个角度（传统印刷用 15/45/75 度），
// 正交排列的网点会和像素网格产生摩尔纹，看起来非常脏。
//
// [en_us]
// A printed halftone screen of brightness-sized dots.
// Each grid cell gets a circle whose radius is set by the brightness there.
// The grid has to be rotated by some angle (traditional printing uses 15/45/75 degrees): halftone dots in an
// axis-aligned grid create moire patterns against the pixel grid, which looks really dirty.
//
// @param name=Dots type=float min=20 max=400 default=140 zh_cn=网点密度 en_us=Dot Density
// @param name=Angle type=float min=0 max=90 default=45 zh_cn=网屏角度 en_us=Screen Angle
// @param name=InkColor type=color3 default=#1A1A22 zh_cn=油墨色 en_us=Ink
// @param name=PaperColor type=color3 default=#F6F2E8 zh_cn=纸色 en_us=Paper
// @param name=ColorMode type=bool default=0 zh_cn=彩色网点 en_us=Color Halftone
// @param name=Sharpness type=float min=0.01 max=0.5 default=0.08 zh_cn=网点锐度 en_us=Dot Sharpness

// 在旋转过的网格里，返回该点到最近网点中心的距离
float dotDistance(vec2 uv, float angleDeg, float density) {
    float a = radians(angleDeg);
    mat2 rot = mat2(cos(a), -sin(a), sin(a), cos(a));
    vec2 p = rot * uv * density;
    return length(fract(p) - 0.5) * 2.0;
}

void main() {
    vec2 asp = vec2(OutSize.x / max(OutSize.y, 1.0), 1.0);
    vec2 uv = texCoord * asp;
    vec3 src = texture(InSampler, texCoord).rgb;

    if (ColorMode > 0.5) {
        // 三个通道各用一个角度，重叠处混出中间色——这就是四色印刷的原理
        float dr = dotDistance(uv, Angle + 15.0, Dots);
        float dg = dotDistance(uv, Angle + 45.0, Dots);
        float db = dotDistance(uv, Angle + 75.0, Dots);
        vec3 col;
        col.r = smoothstep(src.r - Sharpness, src.r + Sharpness, dr * 0.72);
        col.g = smoothstep(src.g - Sharpness, src.g + Sharpness, dg * 0.72);
        col.b = smoothstep(src.b - Sharpness, src.b + Sharpness, db * 0.72);
        fragColor = vec4(col, 1.0);
        return;
    }

    float luma = dot(src, vec3(0.2126, 0.7152, 0.0722));
    float d = dotDistance(uv, Angle, Dots);
    // 亮的地方网点小（d 要很小才算在点内），暗的地方网点铺满
    float ink = 1.0 - smoothstep(luma - Sharpness, luma + Sharpness, d * 0.72);
    fragColor = vec4(mix(PaperColor, InkColor, ink), 1.0);
}
