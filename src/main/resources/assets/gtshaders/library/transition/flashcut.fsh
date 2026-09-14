// 白闪切 / Flash Cut
// 一次过曝把画面冲白再退回来，用来遮住两个镜头的接点——剪辑里最老实好用的一招。
// 冲上去要快得几乎没有过程，退回来要慢一拍，眼睛才认为「刚才有东西炸了一下」。
//
// 冲白不是简单往上加白：先把画面整体提亮（模拟感光元件过曝），再叠一层纯色。
// 只做后者的话高光和暗部会一起变灰，看起来像蒙了张纸而不是被闪到。
//
// [en_us]
// A white flash cut that hides the cut point between shots.
// An overexposure blows the picture out to white and back, the most honest and reliable trick in editing.
// The rise should be so fast it has almost no duration, and the fall should lag a beat behind, so the eye reads
// it as "something just went off".
//
// The white-out is not just adding white: the whole picture is brightened first (simulating sensor
// overexposure), then a solid color is layered on top. Doing only the latter turns highlights and shadows gray
// together, which looks like a sheet of paper laid over the screen rather than being flashed.
//
// @param name=AutoPlay type=bool default=1 zh_cn=随时间自动播放 en_us=Auto Play
// @param name=Duration type=float min=0.1 max=4 default=0.6 zh_cn=转场时长(秒) en_us=Duration
// @param name=Interval type=float min=0.2 max=30 default=2.5 zh_cn=重播间隔(秒) en_us=Replay Interval desc_zh_cn=自动播放时每隔这么久闪一次 desc_en_us=How often it replays while Auto Play is on
// @param name=Progress type=float min=0 max=1 default=0.2 zh_cn=手动进度 en_us=Progress
// @param name=Attack type=float min=0.01 max=0.5 default=0.08 zh_cn=冲上去用时占比 en_us=Attack

// @group 外观 / Look
// @param name=FlashColor type=color3 default=#FFFFFF zh_cn=闪光色 en_us=Flash Color
// @param name=Exposure type=float min=0 max=8 default=3.5 zh_cn=过曝增益 en_us=Exposure Gain
// @param name=Bloom type=float min=0 max=1 default=0.5 zh_cn=高光扩散 en_us=Highlight Spread
// @param name=Shake type=float min=0 max=0.05 default=0.008 zh_cn=伴随抖动 en_us=Shake

// 循环播放。绝不能写成 clamp(GTTime / Duration)：GTTime 从预览开始就一直在涨，
// 玩家真正把效果加进工程时它早已是几百秒，clamp 出来恒等于 1——效果永远停在结束态，
// 表现出来就是「加上去什么都没发生」。取余才能让它一直重播。
float gtLoop(float duration, float interval) {
    return clamp(mod(GTTime, max(interval, duration)) / max(duration, 0.01), 0.0, 1.0);
}

void main() {
    float t = AutoPlay > 0.5 ? gtLoop(Duration, Interval) : Progress;

    // 攻击段线性冲顶，释放段用 pow 拖长尾巴
    float a = clamp(Attack, 0.01, 0.99);
    float k = t < a ? t / a : pow(1.0 - (t - a) / max(1.0 - a, 1e-3), 2.2);
    k = clamp(k, 0.0, 1.0);

    // 抖动跟着强度走：闪得最亮那一瞬间抖得最凶
    vec2 jit = vec2(sin(GTTime * 61.0), cos(GTTime * 47.0)) * Shake * k;
    vec2 uv = clamp(texCoord + jit, vec2(0.0), vec2(1.0));

    vec3 src = texture(InSampler, uv).rgb;

    // 高光扩散：朝中心做几次收缩采样，只留亮部，得到一团光雾
    vec3 spread = vec3(0.0);
    for (int i = 1; i <= 5; i++) {
        float s = float(i) / 5.0;
        spread += texture(InSampler, mix(uv, vec2(0.5), s * 0.10 * Bloom)).rgb;
    }
    spread = max(spread / 5.0 - 0.5, vec3(0.0)) * 2.0;

    vec3 col = src * (1.0 + Exposure * k) + spread * k * 1.5;
    col = mix(col, FlashColor, k * k * 0.9);
    fragColor = vec4(col, 1.0);
}
