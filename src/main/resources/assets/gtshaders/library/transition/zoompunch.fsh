// 缩放冲刺切 / Zoom Punch
// 画面猛地朝中心冲一下，同时拉出径向拖影，冲到顶时整块糊掉——短视频里最常见的那种"冲击转场"。
// 冲程曲线是快进慢出：起手那两帧要几乎瞬移，回落可以慢慢来。
//
// 径向模糊的采样点全部落在同一条射线上，所以拖影方向天然指向圆心，不需要额外算方向。
//
// [en_us]
// A zoom punch toward the center with radial motion blur.
// The picture lunges toward the center while radial streaks stretch out, and at the peak it blurs out completely,
// the "impact transition" you see all the time in short videos. The stroke curve is fast in, slow out: the first
// couple of frames should be almost a teleport, and the return can take its time.
//
// The radial blur samples all lie on the same ray, so the streaks naturally point toward the center with no extra
// direction math.
//
// @param name=AutoPlay type=bool default=1 zh_cn=随时间自动播放 en_us=Auto Play
// @param name=Duration type=float min=0.1 max=4 default=0.7 zh_cn=转场时长(秒) en_us=Duration
// @param name=Interval type=float min=0.2 max=30 default=2.4 zh_cn=重播间隔(秒) en_us=Replay Interval desc_zh_cn=自动播放时每隔这么久冲一次 desc_en_us=How often it replays while Auto Play is on
// @param name=Progress type=float min=0 max=1 default=0.35 zh_cn=手动进度 en_us=Progress
// @param name=Attack type=float min=0.02 max=0.6 default=0.18 zh_cn=冲上去用时占比 en_us=Attack

// @group 冲击 / Punch
// @param name=Center type=vec2 min=0 max=1 default=0.5,0.5 zh_cn=冲击中心 en_us=Center
// @param name=ZoomAmount type=float min=0 max=1.5 default=0.45 zh_cn=推进量 en_us=Zoom Amount
// @param name=Streak type=float min=0 max=1 default=0.55 zh_cn=径向拖影 en_us=Radial Streak
// @param name=Outward type=bool default=0 zh_cn=改成向外拉 en_us=Zoom Out

// @group 外观 / Look
// @param name=Chroma type=float min=0 max=0.05 default=0.012 zh_cn=边缘色散 en_us=Edge Chroma
// @param name=Brighten type=float min=0 max=3 default=0.8 zh_cn=冲击提亮 en_us=Impact Gain

// 循环播放。clamp(GTTime / Duration) 跑过一遍就恒等于 1，效果永远停在结束态。
float gtLoop(float duration, float interval) {
    return clamp(mod(GTTime, max(interval, duration)) / max(duration, 0.01), 0.0, 1.0);
}

void main() {
    float t = AutoPlay > 0.5 ? gtLoop(Duration, Interval) : Progress;
    float a = clamp(Attack, 0.02, 0.95);
    float k = t < a ? pow(t / a, 0.45) : pow(1.0 - (t - a) / max(1.0 - a, 1e-3), 1.8);
    k = clamp(k, 0.0, 1.0);

    float dir = Outward > 0.5 ? -1.0 : 1.0;
    float zoom = 1.0 - ZoomAmount * k * dir;

    vec2 base = (texCoord - Center) * zoom + Center;
    vec2 toCenter = Center - base;

    // 径向模糊：沿着「本像素→圆心」这条线取 8 个点。步长跟冲击强度走
    vec3 col = vec3(0.0);
    float total = 0.0;
    for (int i = 0; i < 8; i++) {
        float s = float(i) / 7.0;
        float w = 1.0 - s * 0.6;
        vec2 uv = base + toCenter * s * Streak * k * 0.35 * dir;
        // 色散：越靠外拆得越开，中心几乎不拆——真实镜头就是这样
        float edge = length(texCoord - Center) * Chroma * k * 40.0;
        col.r += texture(InSampler, uv + toCenter * edge * 0.01).r * w;
        col.g += texture(InSampler, uv).g * w;
        col.b += texture(InSampler, uv - toCenter * edge * 0.01).b * w;
        total += w;
    }
    col /= max(total, 1e-4);
    col *= 1.0 + Brighten * k * 0.5;

    fragColor = vec4(col, 1.0);
}
