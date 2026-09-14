// 失明 / Blindness
// 视野从四周往里合拢。要点是「边缘不只是变黑，还失焦」——纯压黑像关灯，
// 加上向外递增的模糊才像视觉本身在退化。
//
// [en_us]
// Your field of view closes in from all sides.
// The key is that the edges don't just go dark, they also lose focus. Pure darkening looks like the lights going
// out; adding blur that grows toward the edges is what makes it feel like your eyesight itself is failing.
//
// @param name=Closing type=float min=0 max=1 default=0.6 zh_cn=合拢程度 en_us=Closing
// @param name=Softness type=float min=0.05 max=1 default=0.45 zh_cn=边缘柔和 en_us=Softness
// @param name=Blur type=float min=0 max=0.02 default=0.006 zh_cn=边缘失焦 en_us=Edge Blur
// @param name=Breathe type=float min=0 max=1 default=0.25 zh_cn=呼吸起伏 en_us=Breathe

void main() {
    vec2 asp = vec2(OutSize.x / max(OutSize.y, 1.0), 1.0);
    float r = length((texCoord - 0.5) * asp) / max(length(asp * 0.5), 1e-4);

    // 视野半径随呼吸微微收放，静止不动的黑圈很容易被看成 UI 而不是状态
    float radius = mix(1.25, 0.02, Closing) + sin(GTTime * 1.6) * 0.03 * Breathe;
    float dark = smoothstep(radius, radius + Softness, r);

    // 越靠外采样点铺得越开，八个方向平均一下就是廉价但够用的失焦
    vec3 col = texture(InSampler, texCoord).rgb;
    float spread = Blur * dark;
    if (spread > 0.0) {
        vec3 acc = vec3(0.0);
        for (int i = 0; i < 8; i++) {
            float a = float(i) * 0.7854;
            acc += texture(InSampler, texCoord + vec2(cos(a), sin(a)) * spread).rgb;
        }
        col = mix(col, acc / 8.0, clamp(dark, 0.0, 1.0));
    }

    fragColor = vec4(col * (1.0 - clamp(dark, 0.0, 1.0)), 1.0);
}
