// 闪光弹 / Flashbang
// 致盲的三段观感：瞬间全白 → 白色退去露出画面 → 残留的暖色余像与眩光。
// 眼睛的恢复不是线性的，所以用 pow 把前段压得很陡，才像"被晃了一下"而不是"慢慢变亮"。
//
// [en_us]
// Flashbang blindness: white-out, fade-in, warm afterimage.
// Three stages: instant white-out, the white fading away to reveal the picture, then a lingering warm afterimage
// and glare. Eyes don't recover linearly, so pow makes the first part steep. That reads as "blinded for a moment",
// not "slowly brightening".
//
// @param name=AutoPlay type=bool default=1 zh_cn=随时间自动播放 en_us=Auto Play
// @param name=Duration type=float min=0.2 max=10 default=3.5 zh_cn=恢复时长(秒) en_us=Duration
// @param name=Interval type=float min=0.5 max=60 default=8 zh_cn=重播间隔(秒) en_us=Replay Interval desc_zh_cn=自动播放时每隔这么久炸一次。不循环的话第一次播完就永远停在结束态了 desc_en_us=How often it replays; without looping it would freeze after the first play
// @param name=Progress type=float min=0 max=1 default=0.12 zh_cn=手动进度 en_us=Progress
// @param name=Bleach type=float min=0 max=1 default=1 zh_cn=白化强度 en_us=Bleach
// @param name=AfterTint type=color3 default=#FFE9C4 zh_cn=余像色 en_us=Afterimage Tint
// @param name=Glare type=float min=0 max=1 default=0.5 zh_cn=眩光扩散 en_us=Glare

// 循环播放。GTTime 只增不减，写成 clamp(GTTime / Duration) 的话跑过一遍就恒为 1，
// 之后不管怎么调参数画面都不会再动一下。
float gtLoop(float duration, float interval) {
    return clamp(mod(GTTime, max(interval, duration)) / max(duration, 0.01), 0.0, 1.0);
}

void main() {
    float t = AutoPlay > 0.5 ? gtLoop(Duration, Interval) : Progress;
    vec3 src = texture(InSampler, texCoord).rgb;

    // 眩光：朝画面中心做几次缩放采样，把高光糊开成一团光雾
    vec3 glare = vec3(0.0);
    for (int i = 0; i < 6; i++) {
        float k = float(i) / 6.0;
        glare += texture(InSampler, mix(texCoord, vec2(0.5), k * 0.12 * Glare)).rgb;
    }
    glare = max(glare / 6.0 - 0.55, vec3(0.0)) * 2.2;

    float white = pow(1.0 - t, 2.5) * Bleach;   // 白幕，退得极快
    float after = pow(1.0 - t, 1.1) * 0.45;     // 余像，退得慢一拍

    vec3 col = src + glare * (0.4 + white);
    col = mix(col, AfterTint, clamp(after, 0.0, 1.0));
    col = mix(col, vec3(1.0), clamp(white, 0.0, 1.0));
    fragColor = vec4(col, 1.0);
}
