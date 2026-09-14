// 命中震屏 / Hit Shake
// 每隔一段时间来一次「被打中」：镜头猛地一顿，随后高频衰减抖动，同时闪一下伤害色。
// 冲击的方向每次都不一样，靠的是把冲击序号丢进哈希——固定方向抖两次就腻了。
//
// 衰减用指数而不是线性：真实的撞击能量是指数散掉的，线性衰减看着像在"演"抖动。
//
// [en_us]
// Screen shake on a hit: a jolt, then a decaying rattle.
// Every so often you "get hit": the camera jolts, then shakes at high frequency as it decays, with a quick flash
// of damage color. The impact direction changes every time because the impact index is fed into a hash. Shaking
// in the same direction gets old after two hits.
//
// The decay is exponential rather than linear: real impact energy dissipates exponentially, and a linear decay
// looks like the shake is being "acted".
//
// @param name=Interval type=float min=0.2 max=10 default=1.6 zh_cn=触发间隔(秒) en_us=Interval
// @param name=Decay type=float min=1 max=30 default=9 zh_cn=衰减速度 en_us=Decay
// @param name=Amount type=float min=0 max=3 default=1 zh_cn=总强度 en_us=Amount

// @group 冲击 / Impact
// @param name=Kick type=float min=0 max=0.15 default=0.035 zh_cn=位移幅度 en_us=Kick
// @param name=Rumble type=float min=0 max=60 default=34 zh_cn=抖动频率 en_us=Rumble Rate
// @param name=Roll type=float min=0 max=0.2 default=0.03 zh_cn=翻滚幅度 en_us=Roll

// @group 反馈 / Feedback
// @param name=HitColor type=color3 default=#FF2A2A zh_cn=命中色 en_us=Hit Color
// @param name=Flash type=float min=0 max=1 default=0.35 zh_cn=命中闪光 en_us=Hit Flash
// @param name=EdgeOnly type=bool default=1 zh_cn=闪光只在边缘 en_us=Edge Only
// @param name=Chroma type=float min=0 max=0.03 default=0.006 zh_cn=冲击色差 en_us=Impact Chroma

float hash11(float p) {
    return fract(sin(p * 78.233) * 43758.5453);
}

void main() {
    float period = max(Interval, 0.05);
    float idx = floor(GTTime / period);
    float age = GTTime - idx * period;

    float env = exp(-age * Decay) * Amount;

    // 每次冲击换一个方向，长度也随机——固定方向的震屏两下就露馅
    float ang = hash11(idx * 3.71) * 6.2831853;
    vec2 dir = vec2(cos(ang), sin(ang)) * (0.5 + hash11(idx * 9.13) * 0.5);

    vec2 asp = vec2(OutSize.x / max(OutSize.y, 1.0), 1.0);
    vec2 off = dir * Kick * env * sin(age * Rumble);
    off.x /= max(asp.x, 1e-3);

    float roll = Roll * env * sin(age * Rumble * 0.7 + ang);
    vec2 uv = texCoord - 0.5;
    uv.x *= asp.x;
    float c = cos(roll);
    float s = sin(roll);
    uv = mat2(c, -s, s, c) * uv;
    uv.x /= asp.x;
    uv = uv / 1.03 + 0.5 + off;
    uv = clamp(uv, vec2(0.0), vec2(1.0));

    float split = Chroma * env;
    vec3 col;
    col.r = texture(InSampler, clamp(uv + dir * split, vec2(0.0), vec2(1.0))).r;
    col.g = texture(InSampler, uv).g;
    col.b = texture(InSampler, clamp(uv - dir * split, vec2(0.0), vec2(1.0))).b;

    float mask = 1.0;
    if (EdgeOnly > 0.5) {
        // 只在四周压一圈红：中间被染红会挡住玩家在看的东西，边缘才是通行的做法
        float d = length((texCoord - 0.5) * vec2(1.15, 1.0)) * 2.0;
        mask = smoothstep(0.55, 1.25, d);
    }
    col = mix(col, HitColor, clamp(env * Flash * mask, 0.0, 1.0));

    fragColor = vec4(col, 1.0);
}
