// 双色调 / Duotone
// 把画面压成灰度，再用一条两色（或三色）渐变重新上色。
// 加一个可选的中间色会大幅提升质感——纯两色插值在中间调总是发灰发脏。
//
// [en_us]
// Grayscale recolored through a two- or three-color gradient.
// The picture is flattened to grayscale, then recolored with a two-color (or three-color) gradient.
// Adding the optional middle color greatly improves the look: a pure two-color blend always looks gray and muddy
// in the midtones.
//
// @param name=DarkColor type=color3 default=#0B1E3A zh_cn=暗色 en_us=Dark Color
// @param name=MidColor type=color3 default=#B0466E zh_cn=中间色 en_us=Mid Color
// @param name=LightColor type=color3 default=#FFE29A zh_cn=亮色 en_us=Light Color
// @param name=UseMid type=bool default=1 zh_cn=启用中间色 en_us=Use Mid Color
// @param name=Contrast type=float min=0.5 max=3 default=1.3 zh_cn=对比度 en_us=Contrast
// @param name=Strength type=float min=0 max=1 default=1 zh_cn=强度 en_us=Strength

void main() {
    vec3 src = texture(InSampler, texCoord).rgb;
    float luma = dot(src, vec3(0.2126, 0.7152, 0.0722));
    luma = clamp((luma - 0.5) * Contrast + 0.5, 0.0, 1.0);

    vec3 graded;
    if (UseMid > 0.5) {
        // 分两段插值，中间色落在 0.5 处
        graded = luma < 0.5
                ? mix(DarkColor, MidColor, luma * 2.0)
                : mix(MidColor, LightColor, (luma - 0.5) * 2.0);
    } else {
        graded = mix(DarkColor, LightColor, luma);
    }

    fragColor = vec4(mix(src, graded, Strength), 1.0);
}
