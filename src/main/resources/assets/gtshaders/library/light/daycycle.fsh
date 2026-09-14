// 日夜轮转 / Day Cycle
// 把一整天压进几十秒：晨曦的冷蓝 → 正午的中性 → 黄昏的暖橙 → 夜晚的深蓝，
// 亮度、色温、对比度和暗角一起走。用来给延时素材直接铺一层时间感。
//
// 四个色标之间用两段 mix 插值，所以过渡永远是连续的——分段判断会在交界处跳色。
//
// [en_us]
// A whole day squeezed into a few dozen seconds.
// Cool dawn blue → neutral noon → warm dusk orange → deep night blue, with brightness, color temperature,
// contrast and vignette all moving together. Lay it over time-lapse footage for an instant sense of time.
//
// The four color stops are blended with two-stage mix interpolation, so transitions are always continuous.
// Hard piecewise switching would make the color jump at the boundaries.
//
// @param name=Period type=float min=2 max=300 default=40 zh_cn=一整天用时(秒) en_us=Cycle Length
// @param name=Manual type=float min=-1 max=1 default=0 zh_cn=手动时刻 en_us=Manual Time desc_zh_cn=非 0 时接管周期：-1..1 对应从午夜绕一圈回到午夜 desc_en_us=Overrides the cycle when non-zero; -1..1 spans a full day
// @param name=Offset type=float min=0 max=1 default=0 zh_cn=起始时刻 en_us=Start Phase

// @group 色标 / Color Keys
// @param name=DawnColor type=color3 default=#8FB0FF zh_cn=晨曦 en_us=Dawn
// @param name=NoonColor type=color3 default=#FFFFFF zh_cn=正午 en_us=Noon
// @param name=DuskColor type=color3 default=#FFA45C zh_cn=黄昏 en_us=Dusk
// @param name=NightColor type=color3 default=#22345E zh_cn=夜晚 en_us=Night

// @group 曝光 / Exposure
// @param name=NightDim type=float min=0 max=1 default=0.62 zh_cn=夜间压暗 en_us=Night Dim
// @param name=NightDesat type=float min=0 max=1 default=0.5 zh_cn=夜间失色 en_us=Night Desaturate
// @param name=Contrast type=float min=0.5 max=2 default=1.08 zh_cn=对比度 en_us=Contrast
// @param name=Vignette type=float min=0 max=1 default=0.3 zh_cn=夜间暗角 en_us=Night Vignette

void main() {
    float t = abs(Manual) > 0.001
        ? fract(Manual * 0.5 + 0.5)
        : fract(GTTime / max(Period, 1.0) + Offset);

    // 0=午夜 0.25=清晨 0.5=正午 0.75=黄昏
    // 两段 mix：先在相邻两个色标间插值，再决定用哪一段。交界处天然连续
    vec3 tint;
    float sun;   // 太阳高度 0..1
    if (t < 0.25) {
        float k = t / 0.25;
        tint = mix(NightColor, DawnColor, k);
        sun = k * 0.5;
    } else if (t < 0.5) {
        float k = (t - 0.25) / 0.25;
        tint = mix(DawnColor, NoonColor, k);
        sun = 0.5 + k * 0.5;
    } else if (t < 0.75) {
        float k = (t - 0.5) / 0.25;
        tint = mix(NoonColor, DuskColor, k);
        sun = 1.0 - k * 0.5;
    } else {
        float k = (t - 0.75) / 0.25;
        tint = mix(DuskColor, NightColor, k);
        sun = 0.5 - k * 0.5;
    }

    vec3 col = texture(InSampler, texCoord).rgb;
    float night = 1.0 - sun;

    col *= tint;
    col *= 1.0 - NightDim * night;
    float g = dot(col, vec3(0.2126, 0.7152, 0.0722));
    col = mix(col, vec3(g), NightDesat * night);
    col = (col - 0.5) * Contrast + 0.5;

    vec2 asp = vec2(OutSize.x / max(OutSize.y, 1.0), 1.0);
    float r = length((texCoord - 0.5) * asp) / max(length(asp) * 0.5, 1e-4);
    col *= 1.0 - Vignette * night * smoothstep(0.4, 1.2, r);

    fragColor = vec4(max(col, vec3(0.0)), 1.0);
}
