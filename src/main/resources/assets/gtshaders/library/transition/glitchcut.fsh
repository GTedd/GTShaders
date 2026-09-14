// 故障切 / Glitch Cut
// 转场那零点几秒里画面被撕成横条、左右错位、通道分离，然后瞬间归位。
// 错位量跟着一条「先猛后收」的包络走——真正的信号故障从不匀速，匀速的看着像动画不像事故。
//
// 剪辑里最常见的用法是把它压到 0.3 秒左右，卡在两个镜头的接点上。
//
// [en_us]
// A glitch cut that tears the picture into shifted strips.
// For a split second the picture is torn into horizontal strips, shifted left and right and split into color
// channels, then it snaps back at once. The offset follows a "hit hard, then settle" envelope: real signal
// glitches are never uniform, and a uniform one looks like animation rather than an accident.
//
// The most common editing use is to squeeze it down to about 0.3 seconds and place it right on the cut point
// between two shots.
//
// @param name=AutoPlay type=bool default=1 zh_cn=随时间自动播放 en_us=Auto Play
// @param name=Duration type=float min=0.1 max=4 default=0.5 zh_cn=转场时长(秒) en_us=Duration
// @param name=Interval type=float min=0.2 max=30 default=2.2 zh_cn=重播间隔(秒) en_us=Replay Interval desc_zh_cn=自动播放时每隔这么久撕一次 desc_en_us=How often it replays while Auto Play is on
// @param name=Progress type=float min=0 max=1 default=0.3 zh_cn=手动进度 en_us=Progress

// @group 撕裂 / Tearing
// @param name=Slices type=float min=2 max=80 default=22 zh_cn=横条数 en_us=Slice Count
// @param name=Displace type=float min=0 max=0.5 default=0.12 zh_cn=最大错位 en_us=Max Displace
// @param name=Jitter type=float min=0 max=40 default=14 zh_cn=抖动频率 en_us=Jitter Rate desc_zh_cn=每秒重掷几次随机数。低了像慢动作，高了像噪点 desc_en_us=How many times per second the randomness is re-rolled

// @group 色彩 / Color
// @param name=RgbSplit type=float min=0 max=0.06 default=0.014 zh_cn=通道分离 en_us=RGB Split
// @param name=Blowout type=float min=0 max=2 default=0.6 zh_cn=过曝闪烁 en_us=Blowout

float hash11(float p) {
    return fract(sin(p * 127.1) * 43758.5453);
}

// 循环播放。clamp(GTTime / Duration) 在 GTTime 涨过 Duration 之后恒等于 1，
// 效果会永远停在结束态；取余才会一直重播。
float gtLoop(float duration, float interval) {
    return clamp(mod(GTTime, max(interval, duration)) / max(duration, 0.01), 0.0, 1.0);
}

void main() {
    float t = AutoPlay > 0.5 ? gtLoop(Duration, Interval) : Progress;

    // 包络：0 起跳到 1，再收回 0。pow 让上升沿比下降沿陡得多
    float env = pow(sin(clamp(t, 0.0, 1.0) * 3.14159265), 0.55);

    // 注意别把局部变量取名 step / sign：那会遮住同名的 GLSL 内建函数，
    // 而报错信息只会说「找不到匹配的重载」，看半天想不到是自己挡的
    float tick = floor(GTTime * Jitter);
    float row = floor(texCoord.y * Slices);

    // 每条各有各的随机偏移，且每次抖动重掷一次
    float dirSign = hash11(row * 13.7 + tick * 3.1) > 0.5 ? 1.0 : -1.0;
    // 三次方让大多数条只微微动，少数几条猛地飞出去——这才是故障的分布
    float off = dirSign * pow(hash11(row * 5.3 + tick * 7.7), 3.0) * Displace * env;

    vec2 uv = texCoord + vec2(off, 0.0);
    float split = RgbSplit * env;
    vec3 col;
    col.r = texture(InSampler, uv + vec2(split, 0.0)).r;
    col.g = texture(InSampler, uv).g;
    col.b = texture(InSampler, uv - vec2(split, 0.0)).b;

    // 少数条整条过曝白掉，这是模拟采样头丢同步
    float flash = step(0.93, hash11(row * 2.9 + tick * 11.3)) * env * Blowout;
    col += flash;

    fragColor = vec4(col, 1.0);
}
