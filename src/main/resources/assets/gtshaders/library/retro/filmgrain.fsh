// 胶片颗粒 / Film Grain
// 真实胶片颗粒有两个性质经常被忽略：中间调最明显（暗部和高光的银盐要么没曝光
// 要么全曝光），以及颗粒是彩色的（三层乳剂各自独立）。
// 均匀的灰噪点看起来只是"信号差"，不是胶片。
//
// [en_us]
// Film grain that is colored and strongest in the midtones.
// Real film grain has two properties that are often overlooked: it is most visible in the midtones (in shadows and
// highlights the silver halide is either not exposed at all or fully exposed), and the grain is colored (the three
// emulsion layers are independent).
// Uniform gray noise just looks like "bad signal", not film.
//
// @param name=Amount type=float min=0 max=1 default=0.35 zh_cn=颗粒强度 en_us=Grain Amount
// @param name=Size type=float min=0.5 max=6 default=1.4 zh_cn=颗粒大小 en_us=Grain Size
// @param name=ColorGrain type=float min=0 max=1 default=0.4 zh_cn=彩色颗粒 en_us=Color Grain
// @param name=Halation type=float min=0 max=1 default=0.3 zh_cn=光晕溢出 en_us=Halation
// @param name=Vignette type=float min=0 max=2 default=0.6 zh_cn=暗角 en_us=Vignette

float hash(vec2 p) {
    return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453);
}

void main() {
    vec3 col = texture(InSampler, texCoord).rgb;
    float luma = dot(col, vec3(0.2126, 0.7152, 0.0722));

    // 颗粒在中间调最强：一条以 0.5 为峰的钟形权重
    float response = 1.0 - pow(abs(luma * 2.0 - 1.0), 1.6);

    vec2 gp = floor(gl_FragCoord.xy / max(Size, 0.5)) + floor(GTTime * 24.0) * 137.0;
    vec3 grain = vec3(hash(gp), hash(gp + 17.3), hash(gp + 91.7)) - 0.5;
    // 彩色颗粒是三层独立的，单色颗粒则三通道共用一个值
    grain = mix(vec3(grain.r), grain, ColorGrain);

    col += grain * Amount * response;

    // 光晕：亮部向周围溢出的暖色，胶片上是红层最容易过曝
    if (Halation > 0.0) {
        vec3 bleed = vec3(0.0);
        for (int i = 0; i < 6; i++) {
            float a = float(i) * 1.0472;
            bleed += texture(InSampler, texCoord + vec2(cos(a), sin(a)) * 0.008).rgb;
        }
        bleed = max(bleed / 6.0 - 0.6, vec3(0.0));
        col += bleed * vec3(1.0, 0.45, 0.25) * Halation * 1.5;
    }

    vec2 d = texCoord - 0.5;
    col *= clamp(1.0 - dot(d, d) * Vignette, 0.0, 1.0);
    fragColor = vec4(col, 1.0);
}
