// 速度线 / Speed Lines
// 漫画式的放射线从画面外往中心冲，中间留一块干净区域给主体。
// 线在极坐标里画：角度决定是哪一根，半径决定它有多长——这样每根线天然指向圆心，
// 不需要为每根线单独算方向。
//
// [en_us]
// Manga-style speed lines rushing toward the center.
// Radial lines rush in from outside the frame toward the center, leaving a clean area in the middle for the
// subject. The lines are drawn in polar coordinates: the angle picks which line, and the radius sets how long it
// is. That way every line naturally points at the center, with no per-line direction to compute.
//
// @param name=Center type=vec2 min=0 max=1 default=0.5,0.5 zh_cn=汇聚点 en_us=Focus Point
// @param name=Speed type=float min=0 max=8 default=2.2 zh_cn=冲刺速度 en_us=Speed
// @param name=Density type=float min=8 max=400 default=44 zh_cn=线条密度 en_us=Density desc_zh_cn=线太密的话每根都不足一个像素宽，采样一取样就全没了——看起来就是「加了没反应」 desc_en_us=Too many lines makes each thinner than a pixel and they vanish entirely when sampled

// @group 形状 / Shape
// @param name=ClearRadius type=float min=0 max=0.8 default=0.22 zh_cn=中心留白半径 en_us=Clear Radius
// @param name=LineLength type=float min=0.05 max=1 default=0.55 zh_cn=线长 en_us=Line Length
// @param name=Sharpness type=float min=1 max=40 default=4 zh_cn=线条锐度 en_us=Sharpness

// @group 外观 / Look
// @param name=LineColor type=color3 default=#FFFFFF zh_cn=线条色 en_us=Line Color
// @param name=Strength type=float min=0 max=2 default=1.3 zh_cn=不透明度 en_us=Opacity
// @param name=DarkLines type=bool default=0 zh_cn=改成黑线 en_us=Ink Style desc_zh_cn=漫画的速度线通常是黑的；亮色线更像特效 desc_en_us=Manga speed lines are usually black; light lines read as VFX

float hash11(float p) {
    return fract(sin(p * 43.7585) * 39182.3719);
}

void main() {
    vec2 asp = vec2(OutSize.x / max(OutSize.y, 1.0), 1.0);
    vec3 src = texture(InSampler, texCoord).rgb;

    vec2 d = (texCoord - Center) * asp;
    // 半径归一化到「画面正中=0，四角=1」。除数必须是<b>半</b>对角线：
    // asp=(w/h,1)，角落处 |(texCoord-0.5)*asp| 只有 length(asp) 的一半，
    // 拿整条对角线去除的话 r 最大只到 0.5——所有按半径推进的动画都只走完一半就没了，
    // 按半径衰减的效果也只发挥出一半强度。
    float r = length(d) / max(length(asp) * 0.5, 1e-4);
    float ang = atan(d.y, d.x) / 6.2831853 + 0.5;

    // 每根线一个格子：格内位置决定粗细，格号决定它自己的相位和长度
    float cell = ang * Density;
    float id = floor(cell);
    float f = abs(fract(cell) - 0.5) * 2.0;

    float phase = hash11(id * 1.37);
    float len = mix(0.4, 1.0, hash11(id * 5.11)) * LineLength;

    // 线沿半径往里冲：head 是这一根当前的前端，减号让它随时间往圆心走
    float head = fract(phase - GTTime * Speed * mix(0.6, 1.4, hash11(id * 9.7)));
    float along = clamp((r - ClearRadius) / max(1.0 - ClearRadius, 1e-3), 0.0, 1.0);
    // 写成「到前端的距离」而不是两个 smoothstep 相乘：后者的峰值落在尾巴上，
    // 画出来是「尾巴最亮、头部最淡」，正好和"冲过去"的方向感相反
    float behind = along - head;
    float body = behind > 0.0 && behind < len ? 1.0 - behind / len : 0.0;

    float thin = pow(1.0 - f, Sharpness);
    float mask = thin * body * smoothstep(ClearRadius, ClearRadius + 0.12, r) * Strength;

    vec3 col = DarkLines > 0.5
        ? src * (1.0 - clamp(mask, 0.0, 1.0))
        : src + LineColor * mask;
    fragColor = vec4(col, 1.0);
}
