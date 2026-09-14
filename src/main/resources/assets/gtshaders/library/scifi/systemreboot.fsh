// 系统重启 / System Reboot
// 老式显像管断电再上电：画面竖向塌成一条亮线、余辉散尽、再从那条线撑开回来，
// 伴随一段自检式的横向扫描条。整个过程循环播放。
//
// 塌陷和展开用的是同一条曲线的两半，所以两头的速度天然对称——分开写两段永远调不到一致。
//
// [en_us]
// An old CRT losing power and powering back up.
// The picture collapses vertically into a bright line, the afterglow fades away, and the image expands back out
// from that line, accompanied by a self-test style horizontal scan bar. The whole sequence loops.
//
// Collapse and expansion use the two halves of the same curve, so the speed at both ends is naturally symmetric.
// Written as two separate pieces, they could never be tuned to match.
//
// @param name=Period type=float min=1 max=30 default=6 zh_cn=循环周期(秒) en_us=Cycle Period
// @param name=OffTime type=float min=0.05 max=0.9 default=0.35 zh_cn=熄屏时长占比 en_us=Off Duration
// @param name=Collapse type=float min=0.02 max=0.6 default=0.18 zh_cn=塌陷用时占比 en_us=Collapse Time

// @group 显像管 / CRT
// @param name=LineColor type=color3 default=#CFEFFF zh_cn=余辉线色 en_us=Scanline Color
// @param name=LineGain type=float min=0 max=6 default=2.5 zh_cn=余辉亮度 en_us=Line Gain
// @param name=Bloom type=float min=0 max=1 default=0.5 zh_cn=辉光扩散 en_us=Bloom

// @group 自检 / Self Test
// @param name=TestBars type=float min=0 max=1 default=0.5 zh_cn=自检扫描条 en_us=Test Bars
// @param name=BarColor type=color3 default=#39FF9E zh_cn=扫描条色 en_us=Bar Color
// @param name=Noise type=float min=0 max=1 default=0.35 zh_cn=上电噪点 en_us=Power-on Noise

float hash(vec2 p) {
    return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453);
}

void main() {
    float period = max(Period, 0.5);
    float t = fract(GTTime / period);

    float coll = clamp(Collapse, 0.02, 0.45);
    float off = clamp(OffTime, 0.05, 0.9);

    // openness：1=正常，0=完全塌成一条线
    float openness;
    if (t < coll) {
        openness = 1.0 - t / coll;                       // 塌陷
    } else if (t < coll + off) {
        openness = 0.0;                                  // 熄屏
    } else {
        openness = (t - coll - off) / max(1.0 - coll - off, 1e-3);   // 展开
    }
    openness = clamp(openness, 0.0, 1.0);
    // 两头用同一条曲线，塌和开的手感才对称
    float shaped = pow(openness, 0.55);

    vec3 col;
    if (shaped < 0.004) {
        col = vec3(0.0);
    } else {
        vec2 uv = vec2(texCoord.x, (texCoord.y - 0.5) / shaped + 0.5);
        if (uv.y < 0.0 || uv.y > 1.0) {
            col = vec3(0.0);
        } else {
            col = texture(InSampler, uv).rgb * shaped;
            // 上电噪点：刚展开时最明显
            float n = hash(floor(texCoord * OutSize * 0.5) + floor(GTTime * 30.0));
            col += (n - 0.5) * Noise * (1.0 - shaped) * 2.0;
        }
    }

    // 余辉线：塌陷到最细时最亮
    float thin = 1.0 - shaped;
    float d = abs(texCoord.y - 0.5);
    float line = exp(-d * d / max(0.00004 + Bloom * 0.004 * shaped, 1e-6));
    col += LineColor * line * LineGain * thin * smoothstep(0.0, 0.15, thin);

    // 自检扫描条：熄屏段里横着扫过去的一条
    if (TestBars > 0.001 && t > coll && t < coll + off) {
        float k = (t - coll) / max(off, 1e-3);
        float band = smoothstep(0.05, 0.0, abs(texCoord.y - k));
        col += BarColor * band * TestBars * 0.8;
    }

    fragColor = vec4(col, 1.0);
}
