// 曲速跃迁 / Warp Drive
// 星辰被拉成从中心射出的长条，越靠外拉得越长，中间那圈越来越亮——准备跃迁的经典画面。
// 星星画在极坐标里：角度决定是哪一颗，半径决定它跑到哪儿了，于是所有星星天然沿射线运动。
//
// [en_us]
// Stars stretching into streaks for a jump to warp.
// Stars are pulled into long streaks shooting out from the center, longer the farther out they are, while the
// ring in the middle keeps getting brighter: the classic shot of a ship about to jump.
// The stars are drawn in polar coordinates: the angle picks which star it is and the radius says how far it has
// traveled, so every star naturally moves along a ray.
//
// @param name=Speed type=float min=0 max=6 default=1.2 zh_cn=跃迁速度 en_us=Warp Speed
// @param name=StarDensity type=float min=20 max=500 default=180 zh_cn=星辰密度 en_us=Star Density
// @param name=Streak type=float min=0.02 max=1 default=0.35 zh_cn=拉丝长度 en_us=Streak Length
// @param name=Center type=vec2 min=0 max=1 default=0.5,0.5 zh_cn=跃迁点 en_us=Warp Point

// @group 外观 / Look
// @param name=StarColor type=color3 default=#DCEBFF zh_cn=星辰色 en_us=Star Color
// @param name=CoreColor type=color3 default=#5AA0FF zh_cn=核心色 en_us=Core Color
// @param name=Gain type=float min=0 max=5 default=1.8 zh_cn=亮度 en_us=Gain
// @param name=CoreGlow type=float min=0 max=2 default=0.6 zh_cn=核心辉光 en_us=Core Glow

// @group 画面 / Scene
// @param name=SceneStretch type=float min=0 max=1 default=0.5 zh_cn=画面径向拉伸 en_us=Scene Stretch
// @param name=SceneDim type=float min=0 max=1 default=0.4 zh_cn=画面压暗 en_us=Scene Dim

float hash11(float p) {
    return fract(sin(p * 61.7) * 33917.31);
}

void main() {
    vec2 asp = vec2(OutSize.x / max(OutSize.y, 1.0), 1.0);
    vec2 d = (texCoord - Center) * asp;
    // 半径归一化到「画面正中=0，四角=1」。除数必须是<b>半</b>对角线：
    // asp=(w/h,1)，角落处 |(texCoord-0.5)*asp| 只有 length(asp) 的一半，
    // 拿整条对角线去除的话 r 最大只到 0.5——所有按半径推进的动画都只走完一半就没了，
    // 按半径衰减的效果也只发挥出一半强度。
    float r = length(d) / max(length(asp) * 0.5, 1e-4);
    float ang = atan(d.y, d.x) / 6.2831853 + 0.5;

    // 画面本身也被往外拉，星星才不像浮在一张静止的图上
    vec2 outward = normalize(d + 1e-6) / max(asp.x, 1e-3);
    vec3 col = vec3(0.0);
    float total = 0.0;
    for (int i = 0; i < 5; i++) {
        float s = float(i) / 4.0;
        float w = 1.0 - s * 0.6;
        vec2 uv = clamp(texCoord + outward * s * SceneStretch * r * 0.08, vec2(0.0), vec2(1.0));
        col += texture(InSampler, uv).rgb * w;
        total += w;
    }
    col = col / max(total, 1e-4) * (1.0 - SceneDim);

    // 星辰：角度切成 N 条通道，每条上跑一颗
    float lane = ang * StarDensity;
    float id = floor(lane);
    float lf = abs(fract(lane) - 0.5) * 2.0;

    float seed = hash11(id * 1.73);
    float pos = fract(seed + GTTime * Speed * mix(0.6, 1.5, hash11(id * 4.19)));
    float len = Streak * mix(0.4, 1.2, hash11(id * 8.31));

    // 沿半径的一条亮带：头在 pos（外端），往圆心方向拖 len 长
    // 用「到头部的距离」直接算，亮度才是头亮尾淡——星辰是往外飞的
    float behind = pos - r;
    float body = behind > 0.0 && behind < len ? 1.0 - behind / len : 0.0;
    // 越靠外越粗越亮
    float thin = pow(1.0 - lf, mix(30.0, 6.0, r));
    float star = body * thin * smoothstep(0.02, 0.3, r);

    col += StarColor * star * Gain;
    col += CoreColor * exp(-r * 6.0) * CoreGlow;

    fragColor = vec4(col, 1.0);
}
