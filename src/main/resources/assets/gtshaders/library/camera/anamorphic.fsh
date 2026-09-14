// 变形宽银幕 / Anamorphic
// 电影感的三件套一次给齐：上下黑边、高光拉出的横向蓝色光条、轻微的横向压缩。
// 光条不是静止的——它随时间轻微呼吸、并跟着画面高光移动，静止的光条一看就是贴图。
//
// 只取亮度超过阈值的部分去拉条：整幅画面横向模糊得到的是"糊"，不是"变形镜头"。
//
// [en_us]
// Anamorphic film look: letterbox bars and blue lens streaks.
// All three cinematic staples at once: letterbox bars, horizontal blue streaks pulled out of the highlights, and a
// slight horizontal squeeze. The streaks aren't static: they breathe gently over time and follow the highlights in
// the picture, because a static streak is instantly recognizable as a texture.
//
// Only the parts brighter than a threshold get stretched into streaks. Blurring the whole frame horizontally just
// gives you "blurry", not "anamorphic lens".
//
// @param name=BarSize type=float min=0 max=0.3 default=0.12 zh_cn=黑边高度 en_us=Letterbox
// @param name=Squeeze type=float min=0.8 max=1.2 default=1.03 zh_cn=横向挤压 en_us=Horizontal Squeeze

// @group 光条 / Flare Streak
// @param name=Threshold type=float min=0 max=1 default=0.68 zh_cn=起条亮度 en_us=Threshold
// @param name=StreakLen type=float min=0 max=0.4 default=0.13 zh_cn=光条长度 en_us=Streak Length
// @param name=StreakColor type=color3 default=#5AA8FF zh_cn=光条色 en_us=Streak Color
// @param name=StreakGain type=float min=0 max=4 default=1.5 zh_cn=光条亮度 en_us=Streak Gain
// @param name=Breathe type=float min=0 max=1 default=0.35 zh_cn=呼吸起伏 en_us=Breathe

// @group 色调 / Tone
// @param name=Teal type=float min=0 max=1 default=0.3 zh_cn=暗部偏青 en_us=Shadow Teal
// @param name=Vignette type=float min=0 max=1 default=0.35 zh_cn=暗角 en_us=Vignette

void main() {
    vec2 uv = texCoord;
    uv.x = (uv.x - 0.5) / max(Squeeze, 0.5) + 0.5;
    uv = clamp(uv, vec2(0.0), vec2(1.0));

    vec3 col = texture(InSampler, uv).rgb;

    // 横向取样，只留超过阈值的高光部分——这样拉出来的是"光条"而不是"横向模糊"
    float breathe = 1.0 + sin(GTTime * 0.9) * Breathe * 0.3;
    vec3 streak = vec3(0.0);
    float total = 0.0;
    for (int i = -8; i <= 8; i++) {
        float s = float(i) / 8.0;
        float w = 1.0 - abs(s);
        vec2 p = clamp(uv + vec2(s * StreakLen * breathe, 0.0), vec2(0.0), vec2(1.0));
        vec3 c = texture(InSampler, p).rgb;
        float l = dot(c, vec3(0.2126, 0.7152, 0.0722));
        streak += max(c * smoothstep(Threshold, min(Threshold + 0.25, 1.0), l), vec3(0.0)) * w;
        total += w;
    }
    streak /= max(total, 1e-4);
    col += streak * StreakColor * StreakGain;

    // 暗部偏青、高光留暖：最省事的一档电影感调色
    float g = dot(col, vec3(0.2126, 0.7152, 0.0722));
    col = mix(col, col * vec3(0.82, 1.02, 1.12), Teal * (1.0 - smoothstep(0.2, 0.7, g)));

    float r = length((texCoord - 0.5) * vec2(1.1, 1.0)) * 1.7;
    col *= 1.0 - Vignette * smoothstep(0.5, 1.35, r);

    // 黑边最后压，免得被上面的加法冲淡
    float bar = step(texCoord.y, BarSize) + step(1.0 - BarSize, texCoord.y);
    col *= 1.0 - clamp(bar, 0.0, 1.0);

    fragColor = vec4(col, 1.0);
}
