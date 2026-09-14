// 电影调色 / Cinematic Grade
// 好莱坞的橙青（teal & orange）配色：把暗部推向青蓝、亮部推向橙黄。
// 之所以有效，是因为人的肤色本来就落在橙色一侧，把环境推向它的补色，
// 人物自然就从背景里"跳"出来了。
//
// [en_us]
// Hollywood teal and orange color grading.
// Shadows are pushed toward teal-blue and highlights toward orange-yellow.
// It works because human skin tones already sit on the orange side, so pushing the surroundings toward the
// complementary color makes people naturally "pop" out of the background.
//
// @param name=Strength type=float min=0 max=1 default=0.6 zh_cn=强度 en_us=Strength
// @param name=ShadowColor type=color3 default=#2A6478 zh_cn=暗部色 en_us=Shadow Tint
// @param name=HighlightColor type=color3 default=#FFC17A zh_cn=亮部色 en_us=Highlight Tint
// @param name=Contrast type=float min=0.5 max=2 default=1.15 zh_cn=对比度 en_us=Contrast
// @param name=Lift type=float min=0 max=0.2 default=0.04 zh_cn=抬黑(胶片感) en_us=Black Lift
// @param name=Saturation type=float min=0 max=2 default=1.05 zh_cn=饱和度 en_us=Saturation

void main() {
    vec3 col = texture(InSampler, texCoord).rgb;

    // 先定对比再上色，顺序反了会把染上去的颜色一起拉爆
    col = clamp((col - 0.5) * Contrast + 0.5, 0.0, 1.0);

    float luma = dot(col, vec3(0.2126, 0.7152, 0.0722));
    // 用平滑权重分离暗部和亮部，硬分会在中间调留下一条可见的分界
    float shadowW = 1.0 - smoothstep(0.0, 0.55, luma);
    float highW = smoothstep(0.45, 1.0, luma);

    vec3 graded = col;
    graded = mix(graded, graded * ShadowColor * 2.0, shadowW * Strength * 0.55);
    graded = mix(graded, graded * HighlightColor * 1.4, highW * Strength * 0.55);

    // 抬黑：胶片的黑不是纯黑，这一点点抬升是"电影感"里最便宜也最有效的一步
    graded = graded * (1.0 - Lift) + Lift;

    float gl = dot(graded, vec3(0.2126, 0.7152, 0.0722));
    graded = mix(vec3(gl), graded, Saturation);

    fragColor = vec4(clamp(graded, 0.0, 1.0), 1.0);
}
