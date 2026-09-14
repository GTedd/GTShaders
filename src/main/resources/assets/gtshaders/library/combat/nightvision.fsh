// 夜视仪 / Night Vision
// 真正的像增强管有三个特征：单色磷光屏、暗部被拉爆的增益、以及增益带出来的噪点。
// 少了噪点就只是「绿滤镜」——噪声必须随亮度反向增强，暗处才会更脏。
//
// [en_us]
// Night vision goggles with phosphor glow and gain noise.
// A real image intensifier tube has three traits: a monochrome phosphor screen, gain that blows out the shadows,
// and the noise that gain brings with it. Without the noise it's just a green filter. The noise has to grow as
// brightness drops, so the dark areas get dirtier.
//
// @param name=Gain type=float min=1 max=8 default=3.2 zh_cn=增益 en_us=Gain
// @param name=Phosphor type=color3 default=#7CFF9E zh_cn=磷光色 en_us=Phosphor
// @param name=Noise type=float min=0 max=1 default=0.35 zh_cn=噪点 en_us=Noise
// @param name=Scanline type=float min=0 max=1 default=0.2 zh_cn=扫描线 en_us=Scanline
// @param name=Vignette type=float min=0 max=3 default=1.6 zh_cn=镜筒暗角 en_us=Tube Vignette

float hash(vec2 p) {
    return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453);
}

void main() {
    vec3 src = texture(InSampler, texCoord).rgb;
    float luma = dot(src, vec3(0.2126, 0.7152, 0.0722));

    // 先开方再乘增益：线性放大会把本来就亮的地方直接烧掉
    float amp = clamp(pow(luma, 0.6) * Gain, 0.0, 1.6);

    // 噪声随时间跳变，且暗处更明显——这正是增益噪声的物理来源
    float n = hash(gl_FragCoord.xy + vec2(GTTime * 60.0, GTTime * 37.0));
    amp += (n - 0.5) * Noise * (1.2 - clamp(luma, 0.0, 1.0));

    float line = 0.5 + 0.5 * sin(gl_FragCoord.y * 1.6 + GTTime * 3.0);
    amp *= 1.0 - Scanline * (1.0 - line);

    vec3 col = Phosphor * clamp(amp, 0.0, 1.4);

    vec2 asp = vec2(OutSize.x / max(OutSize.y, 1.0), 1.0);
    float r = length((texCoord - 0.5) * asp) / max(length(asp * 0.5), 1e-4);
    col *= clamp(1.0 - r * r * Vignette, 0.0, 1.0);

    fragColor = vec4(col, 1.0);
}
