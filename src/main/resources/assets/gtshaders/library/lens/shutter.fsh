// 快门 / Shutter
// 电影摄影机的旋转快门：一条黑边扫过画幅。可以当转场用，
// 也可以调成很窄的一条做"胶片跳帧"。
//
// [en_us]
// A rotary film shutter sweeping a black bar across.
// The rotary shutter of a film camera: a black band sweeps across the frame. Use it as a transition, or tune
// it to a very narrow band for a "film frame skip" look.
//
// @param name=Style type=int min=0 max=2 default=0 zh_cn=样式(0横 1圆 2百叶) en_us=Style
// @param name=Progress type=float min=0 max=1 default=0.35 zh_cn=进度 en_us=Progress
// @param name=AutoPlay type=bool default=0 zh_cn=循环播放 en_us=Auto Play
// @param name=Period type=float min=0.2 max=8 default=2 zh_cn=周期(秒) en_us=Period
// @param name=Softness type=float min=0 max=0.3 default=0.03 zh_cn=边缘柔和 en_us=Edge Softness
// @param name=Blades type=float min=2 max=32 default=8 zh_cn=百叶数量 en_us=Blade Count
// @param name=ShutterColor type=color3 default=#000000 zh_cn=遮挡色 en_us=Shutter Color

void main() {
    vec3 col = texture(InSampler, texCoord).rgb;
    float t = AutoPlay > 0.5 ? fract(GTTime / max(Period, 0.01)) : Progress;

    float cover;
    if (Style == 1) {
        // 圆形收缩
        vec2 asp = vec2(OutSize.x / max(OutSize.y, 1.0), 1.0);
        float r = length((texCoord - 0.5) * asp) / max(length(asp * 0.5), 1e-4);
        cover = smoothstep(1.0 - t + Softness, 1.0 - t - Softness, r);
        cover = 1.0 - cover;
    } else if (Style == 2) {
        // 百叶：每条叶片各自从中线往两边张开
        float band = fract(texCoord.y * Blades);
        cover = 1.0 - smoothstep(t - Softness, t + Softness, abs(band - 0.5) * 2.0);
    } else {
        // 横扫：一条边从上往下推
        cover = smoothstep(t - Softness, t + Softness, 1.0 - texCoord.y);
    }

    fragColor = vec4(mix(col, ShutterColor, clamp(cover, 0.0, 1.0)), 1.0);
}
