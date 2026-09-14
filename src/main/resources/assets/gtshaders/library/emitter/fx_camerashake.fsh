// 载体屏幕震动 / Emitter Camera Shake
// 整张画面按 fbm 噪声偏移。强度由离得最近的那个载体决定——爆炸在脸上震得狠，
// 在远处只是抖一下，这个关系不用手调，载体的距离本来就带着。
//
// 和原作 camera_shake.fsh 一样用<b>分形布朗运动</b>而不是单纯的 sin 或随机数：
// 单频抖动是规律的左右晃，一眼看出是程序做的；纯随机每帧跳变，抖成雪花。
// fbm 把几个倍频叠起来，才有那种"低频大晃 + 高频小颤"的手持感。
//
// 抖动<b>只改采样坐标，不改画面内容</b>。所以它可以叠在任何一层上面，
// 也可以单独作为一层放在链的最后——原作正是把它放在整条链的末尾。
//
// 载体面板上：「自定义1」是这一个载体的震动倍率，「自定义2」是频率倍率。
// 想要"两个爆炸叠加震得更狠"，配两个载体即可，这里取的是它们的强度和。
//
// [en_us]
// Screen shake whose strength comes from nearby emitters.
// The whole picture is offset by fbm noise. Strength is set by the nearest emitter: an explosion in your face
// shakes hard, one far away only gives a small jolt. This needs no manual tuning, since the emitter already
// carries its distance.
//
// Like camera_shake.fsh in the original, it uses <b>fractional Brownian motion</b> rather than a plain sin or
// random numbers. A single-frequency shake is a regular side-to-side sway that is obviously procedural; pure
// randomness jumps every frame and turns into jittery static. fbm stacks several octaves, which gives that
// handheld feel of "big low-frequency sway + small high-frequency tremor".
//
// The shake <b>only changes the sample coordinates, not the picture content</b>. So it can sit on top of any
// layer, or go alone as a layer at the end of the chain. The original puts it at the very end of its chain.
//
// In the emitter panel, "Custom 1" is this emitter's shake multiplier and "Custom 2" its frequency multiplier.
// For "two explosions stacking into a harder shake", just set up two emitters: the strengths are summed here.
//
// @param name=Strength type=float min=0 max=0.06 default=0.012 zh_cn=震动幅度 en_us=Shake Strength desc_zh_cn=单位是纵向 UV。0.012 在 1080p 上约 13 像素 desc_en_us=In vertical UV; 0.012 is about 13px at 1080p
// @param name=Frequency type=float min=0.5 max=40 default=14 zh_cn=震动频率 en_us=Frequency
// @param name=Octaves type=int min=1 max=6 default=4 zh_cn=噪声层数 en_us=Octaves desc_zh_cn=层数越多越毛糙。4 层是手持感与开销的平衡点 desc_en_us=More octaves means rougher motion; 4 balances feel and cost
// @param name=Roll type=float min=0 max=1 default=0.35 zh_cn=旋转分量 en_us=Rotational desc_zh_cn=除了平移还绕画面中心扭一点。纯平移的震动像在推显示器 desc_en_us=Adds a twist around screen center; pure translation feels like shoving the monitor
// @param name=FalloffDist type=float min=1 max=64 default=16 zh_cn=衰减距离(格) en_us=Falloff Distance desc_zh_cn=超过这个距离的载体基本不再震动 desc_en_us=Emitters beyond this barely shake anything
// @param name=Manual type=float min=0 max=1 default=0 zh_cn=手动强度 en_us=Manual Amount desc_zh_cn=没有任何载体时也强制震动，用来调参数 desc_en_us=Forces shake with no emitters present, for tuning

float gtfHash(float x) {
    return fract(sin(x * 127.1) * 43758.5453);
}

float gtfNoise(float x) {
    float i = floor(x);
    float f = fract(x);
    // 五次平滑：三次的 smoothstep 在格点上二阶导不连续，叠了几层之后
    // 那些折点会攒成肉眼可见的规律颤动
    f = f * f * f * (f * (f * 6.0 - 15.0) + 10.0);
    return mix(gtfHash(i), gtfHash(i + 1.0), f) * 2.0 - 1.0;
}

float gtfFbm(float x, float freq) {
    float v = 0.0;
    float amp = 1.0;
    float norm = 0.0;
    for (int i = 0; i < 6; i++) {
        if (i >= Octaves) {
            break;
        }
        v += gtfNoise(x * freq) * amp;
        norm += amp;
        amp *= 0.5;
        freq *= 2.0;
    }
    return v / max(norm, 1e-4);
}

void main() {
    // 把所有载体的贡献加起来。取和而不是取最大：两发炸弹一起响，就该比一发更晃
    float amount = Manual;
    float freqMul = 1.0;
    for (int i = 0; i < GT_ANCHOR_SLOTS; i++) {
        if (!gtAnchorValid(i)) {
            continue;
        }
        // 距离衰减用平滑的反比而不是线性截断：线性截断会让载体在边界上
        // "啪"地一下停止震动，而爆炸的余波是渐渐消失的
        float d = gtAnchorDistance(i);
        float atten = 1.0 / (1.0 + (d / max(FalloffDist, 1e-4)) * (d / max(FalloffDist, 1e-4)));
        amount += gtAnchorStrength(i) * atten * (1.0 + gtEmitterCustom1(i));
        freqMul = max(freqMul, 1.0 + gtEmitterCustom2(i));
    }
    amount = clamp(amount, 0.0, 4.0);

    if (amount <= 0.0001) {
        fragColor = texture(InSampler, texCoord);
        return;
    }

    float freq = Frequency * freqMul;
    // 两个轴用不同的时间偏移，否则 x 和 y 同相位，画面只会沿一条对角线来回滑
    float sx = gtfFbm(GTTime + 0.0, freq);
    float sy = gtfFbm(GTTime + 31.7, freq);
    float sr = gtfFbm(GTTime + 71.3, freq * 0.6);

    vec2 asp = vec2(OutSize.x / max(OutSize.y, 1.0), 1.0);
    vec2 uv = texCoord;

    // 旋转分量：绕画面中心扭。在等比空间里转，宽屏上才不会转成剪切变形
    if (Roll > 0.0001) {
        vec2 c = (uv - 0.5) * asp;
        float a = sr * Roll * Strength * 8.0 * amount;
        float cs = cos(a);
        float sn = sin(a);
        c = mat2(cs, -sn, sn, cs) * c;
        uv = c / asp + 0.5;
    }

    uv += vec2(sx, sy) * Strength * amount / asp;
    fragColor = texture(InSampler, uv);
}
