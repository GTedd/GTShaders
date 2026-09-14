// 治疗涟漪 / Heal Pulse
// 一圈圈柔和的绿光从中心往外荡开，经过之处画面被提亮、恢复饱和度，边缘有细小的光点上浮。
// 和伤害反馈刻意做成相反的语言：伤害是压暗+锐利+高频，治疗是提亮+柔和+低频。
//
// [en_us]
// Soft green healing rings rippling out from the center.
// Rings of soft green light ripple outward from the center. Where they pass, the picture brightens and
// regains saturation, and tiny motes of light float up at the edges. It deliberately speaks the opposite
// visual language of damage feedback: damage is darker + sharp + high-frequency, healing is brighter + soft +
// low-frequency.
//
// @param name=Period type=float min=0.3 max=8 default=1.8 zh_cn=涟漪间隔(秒) en_us=Ripple Period
// @param name=Rings type=float min=1 max=5 default=2 zh_cn=同时存在的圈数 en_us=Concurrent Rings
// @param name=Center type=vec2 min=0 max=1 default=0.5,0.5 zh_cn=中心 en_us=Center

// @group 外观 / Look
// @param name=HealColor type=color3 default=#6BFFAE zh_cn=治疗色 en_us=Heal Color
// @param name=RingWidth type=float min=0.01 max=0.5 default=0.12 zh_cn=圈宽 en_us=Ring Width
// @param name=Gain type=float min=0 max=3 default=1.1 zh_cn=亮度 en_us=Gain
// @param name=Restore type=float min=0 max=1 default=0.5 zh_cn=恢复饱和 en_us=Restore Saturation
// @param name=Motes type=float min=0 max=1 default=0.45 zh_cn=上浮光点 en_us=Rising Motes

// @group 底噪 / Ambient
// @param name=Ambient type=float min=0 max=1 default=0.25 zh_cn=常驻绿晕 en_us=Ambient Glow
// @param name=Breathe type=float min=0 max=4 default=1.2 zh_cn=呼吸频率 en_us=Breathe Rate

float hash(vec2 p) {
    return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453);
}

void main() {
    vec2 asp = vec2(OutSize.x / max(OutSize.y, 1.0), 1.0);
    // 半径归一化到「画面正中=0，四角=1」。除数必须是<b>半</b>对角线：
    // asp=(w/h,1)，角落处 |(texCoord-0.5)*asp| 只有 length(asp) 的一半，
    // 拿整条对角线去除的话 r 最大只到 0.5——所有按半径推进的动画都只走完一半就没了，
    // 按半径衰减的效果也只发挥出一半强度。
    float r = length((texCoord - Center) * asp) / max(length(asp) * 0.5, 1e-4);
    vec3 src = texture(InSampler, texCoord).rgb;

    float period = max(Period, 0.1);
    float ring = 0.0;
    for (int i = 0; i < 5; i++) {
        if (float(i) >= Rings) {
            break;
        }
        // 每一圈错开一个身位出发，于是画面上同时有好几道在往外走
        float t = fract(GTTime / period - float(i) / max(Rings, 1.0));
        float front = t * 1.15;
        float band = smoothstep(RingWidth, 0.0, abs(r - front));
        ring += band * (1.0 - t);   // 越往外越淡
    }

    float breathe = 0.5 + 0.5 * sin(GTTime * Breathe);
    vec3 col = src;

    // 恢复饱和：把颜色往远离灰度的方向推，而不是简单加绿
    float g = dot(src, vec3(0.2126, 0.7152, 0.0722));
    col = mix(col, g + (src - g) * 1.6, clamp(ring, 0.0, 1.0) * Restore);

    col += HealColor * ring * Gain;
    col += HealColor * Ambient * (0.4 + breathe * 0.6) * 0.25;

    if (Motes > 0.001) {
        vec2 mp = vec2(texCoord.x * 60.0, texCoord.y * 40.0 + GTTime * 0.9);
        vec2 mi = floor(mp);
        float mote = step(0.978, hash(mi)) * smoothstep(0.5, 0.1, length(fract(mp) - 0.5));
        col += HealColor * mote * Motes * 2.0;
    }

    fragColor = vec4(col, 1.0);
}
