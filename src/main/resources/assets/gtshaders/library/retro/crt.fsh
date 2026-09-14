// CRT 显像管 / CRT Monitor
// 老显示器的四个特征缺一不可：屏幕曲率、荫罩条（RGB 三色竖条）、
// 扫描线、以及边角的暗晕。只做扫描线的话看起来像加了个横条滤镜。
//
// [en_us]
// An old CRT monitor with curvature, mask and scanlines.
// None of the four traits of an old monitor can be left out: screen curvature, the shadow mask (vertical RGB
// stripes), scanlines, and the dark falloff in the corners. With scanlines alone it just looks like a horizontal
// stripe filter was added.
//
// @param name=Curve type=float min=0 max=0.4 default=0.12 zh_cn=屏幕曲率 en_us=Curvature
// @param name=Mask type=float min=0 max=1 default=0.45 zh_cn=荫罩强度 en_us=Aperture Mask
// @param name=Scanline type=float min=0 max=1 default=0.35 zh_cn=扫描线 en_us=Scanline
// @param name=Bleed type=float min=0 max=0.01 default=0.0025 zh_cn=色渗 en_us=Color Bleed
// @param name=Flicker type=float min=0 max=1 default=0.15 zh_cn=闪烁 en_us=Flicker
// @param name=Vignette type=float min=0 max=2 default=0.8 zh_cn=暗角 en_us=Vignette

void main() {
    // 桶形畸变：把 uv 往外推，量与到中心距离的平方成正比
    vec2 c = texCoord - 0.5;
    float r2 = dot(c, c);
    vec2 uv = 0.5 + c * (1.0 + r2 * Curve * 2.0);

    // 弯出屏幕的部分是真的黑边，不是把边缘像素拉长
    if (uv.x < 0.0 || uv.x > 1.0 || uv.y < 0.0 || uv.y > 1.0) {
        fragColor = vec4(0.0, 0.0, 0.0, 1.0);
        return;
    }

    // 色渗：电子束在相邻荧光点上溢出，表现为水平方向的轻微色散
    vec3 col;
    col.r = texture(InSampler, uv + vec2(Bleed, 0.0)).r;
    col.g = texture(InSampler, uv).g;
    col.b = texture(InSampler, uv - vec2(Bleed, 0.0)).b;

    // 荫罩：每三个物理像素一组，依次只让 R/G/B 通过
    float sub = mod(gl_FragCoord.x, 3.0);
    vec3 maskRgb = vec3(sub < 1.0 ? 1.0 : 0.35,
                        (sub >= 1.0 && sub < 2.0) ? 1.0 : 0.35,
                        sub >= 2.0 ? 1.0 : 0.35);
    col *= mix(vec3(1.0), maskRgb, Mask);

    // 扫描线：隔行压暗
    float line = 0.5 + 0.5 * cos(gl_FragCoord.y * 3.14159);
    col *= 1.0 - Scanline * line;

    // 电网频率带来的整屏亮度抖动
    col *= 1.0 + Flicker * 0.06 * sin(GTTime * 50.0);

    col *= clamp(1.0 - r2 * Vignette * 2.2, 0.0, 1.0);
    // 荫罩会吃掉大量亮度，补回来一点，否则整屏发灰
    col *= 1.0 + Mask * 0.8;
    fragColor = vec4(col, 1.0);
}
