// 催眠螺旋 / Hypno Spiral
// 一条从中心旋出的螺旋不停地往里（或往外）转，画面被它调制得忽明忽暗，越靠中心转得越快。
// 螺旋的方程只有一行：角度加上「半径乘以疏密系数」，再取周期——难的是让它不晕得让人难受，
// 所以中心留了一块几乎不受影响的区域。
//
// [en_us]
// A hypnotic spiral turning endlessly from the center.
// A spiral winding out from the center keeps turning inward (or outward), modulating the picture between
// light and dark, and it turns faster closer to the center. The spiral equation is a single line: the angle
// plus "radius times a density factor", fed through a periodic function. The hard part is keeping it from
// being sickeningly dizzying, so the center keeps an area that is barely affected.
//
// @param name=Center type=vec2 min=0 max=1 default=0.5,0.5 zh_cn=中心 en_us=Center
// @param name=Arms type=float min=1 max=12 default=2 zh_cn=螺旋条数 en_us=Arms
// @param name=Tightness type=float min=1 max=60 default=14 zh_cn=疏密 en_us=Tightness
// @param name=Spin type=float min=-4 max=4 default=1 zh_cn=旋转速度 en_us=Spin Speed

// @group 外观 / Look
// @param name=ColorA type=color3 default=#000000 zh_cn=暗条色 en_us=Dark Band
// @param name=ColorB type=color3 default=#FFFFFF zh_cn=亮条色 en_us=Light Band
// @param name=Opacity type=float min=0 max=1 default=0.55 zh_cn=不透明度 en_us=Opacity
// @param name=Hardness type=float min=0 max=1 default=0.4 zh_cn=边界硬度 en_us=Edge Hardness

// @group 舒适 / Comfort
// @param name=ClearRadius type=float min=0 max=0.6 default=0.12 zh_cn=中心留白 en_us=Clear Center
// @param name=Suck type=float min=0 max=0.1 default=0.02 zh_cn=向心牵引 en_us=Inward Pull

void main() {
    vec2 asp = vec2(OutSize.x / max(OutSize.y, 1.0), 1.0);
    vec2 d = (texCoord - Center) * asp;
    // 半径归一化到「画面正中=0，四角=1」。除数必须是<b>半</b>对角线：
    // asp=(w/h,1)，角落处 |(texCoord-0.5)*asp| 只有 length(asp) 的一半，
    // 拿整条对角线去除的话 r 最大只到 0.5——所有按半径推进的动画都只走完一半就没了，
    // 按半径衰减的效果也只发挥出一半强度。
    float r = length(d) / max(length(asp) * 0.5, 1e-4);
    float ang = atan(d.y, d.x);

    // 向心牵引：画面被轻轻吸向中心，螺旋才像在"卷"东西
    vec2 uv = clamp(texCoord - normalize(d + 1e-6) / max(asp.x, 1e-3) * Suck * (1.0 - r), vec2(0.0), vec2(1.0));
    vec3 src = texture(InSampler, uv).rgb;

    // 螺旋：角度 + 半径×疏密，越往里相位跑得越快
    float phase = ang * max(floor(Arms), 1.0) + log(max(r, 1e-3)) * Tightness + GTTime * Spin;
    float band = sin(phase) * 0.5 + 0.5;
    band = mix(band, smoothstep(0.35, 0.65, band), Hardness);

    float mask = smoothstep(ClearRadius, ClearRadius + 0.12, r) * Opacity;
    vec3 spiral = mix(ColorA, ColorB, band);

    fragColor = vec4(mix(src, spiral, mask), 1.0);
}
