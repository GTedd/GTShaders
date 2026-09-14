// 分离色调 / Split Toning
// 暗部、中间调、亮部各染一个颜色，权重用平滑曲线分配。
// 这就是 Lightroom 的分离色调面板在做的事，只是这里三段而不是两段。
//
// [en_us]
// Tints shadows, midtones and highlights with separate colors.
// The weights between them are distributed with smooth curves.
// This is what the split toning panel in Lightroom does, except with three ranges instead of two.
//
// @param name=Shadows type=color3 default=#2E4A6B zh_cn=暗部 en_us=Shadows
// @param name=Midtones type=color3 default=#8C8C8C zh_cn=中间调 en_us=Midtones
// @param name=Highlights type=color3 default=#FFE4B5 zh_cn=亮部 en_us=Highlights
// @param name=Balance type=float min=-0.5 max=0.5 default=0 zh_cn=平衡点偏移 en_us=Balance
// @param name=Strength type=float min=0 max=1 default=0.5 zh_cn=强度 en_us=Strength
// @param name=Preserve type=float min=0 max=1 default=0.8 zh_cn=保持亮度 en_us=Preserve Luma

void main() {
    vec3 col = texture(InSampler, texCoord).rgb;
    float luma = dot(col, vec3(0.2126, 0.7152, 0.0722));

    // Balance 把三段的分界整体上下平移，等价于面板上那根平衡滑杆
    float l = clamp(luma - Balance, 0.0, 1.0);

    float ws = 1.0 - smoothstep(0.0, 0.5, l);
    float wh = smoothstep(0.5, 1.0, l);
    float wm = max(1.0 - ws - wh, 0.0);

    vec3 tint = Shadows * ws + Midtones * wm + Highlights * wh;
    // 乘 2 是因为中性灰是 0.5：这样"选中性灰"等于不改变画面
    vec3 graded = col * tint * 2.0;

    // 保持亮度：染色只改色相/饱和，不动明暗，避免调完整体变暗
    float gl = dot(graded, vec3(0.2126, 0.7152, 0.0722));
    graded = mix(graded, graded * (luma / max(gl, 1e-4)), Preserve);

    fragColor = vec4(clamp(mix(col, graded, Strength), 0.0, 1.0), 1.0);
}
