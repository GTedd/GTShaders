// VHS 录像带 / VHS Tape
// 磁带的典型损伤：色度信号带宽远低于亮度（所以颜色会横向糊开并偏移）、
// 磁头切换处的横向撕裂、以及缓慢上滚的画面。
// 「色度比亮度糊」是 VHS 最标志性的一点，只加噪点是仿不出来的。
//
// [en_us]
// Worn VHS tape with color bleed, tearing and rolling.
// The typical damage of tape: chroma bandwidth far below luma (so colors smear and shift sideways), horizontal
// tearing where the heads switch, and a picture that slowly rolls upward.
// "Chroma blurrier than luma" is the most iconic trait of VHS, and adding noise alone can't fake it.
//
// @param name=Wear type=float min=0 max=1 default=0.5 zh_cn=磨损程度 en_us=Tape Wear
// @param name=ChromaBleed type=float min=0 max=0.05 default=0.012 zh_cn=色度糊开 en_us=Chroma Bleed
// @param name=Tearing type=float min=0 max=1 default=0.4 zh_cn=撕裂 en_us=Head Switching
// @param name=Roll type=float min=0 max=1 default=0.15 zh_cn=上滚 en_us=Vertical Roll
// @param name=Noise type=float min=0 max=1 default=0.25 zh_cn=雪花 en_us=Snow Noise
// @param name=Saturation type=float min=0 max=2 default=1.25 zh_cn=饱和度 en_us=Saturation

float hash(vec2 p) {
    return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453);
}

void main() {
    vec2 uv = texCoord;

    // 缓慢上滚
    uv.y = fract(uv.y + GTTime * Roll * 0.05);

    // 撕裂：少数几条扫描线整行横移，位置随时间跳变
    float lineId = floor(uv.y * 240.0);
    float tearSeed = hash(vec2(lineId, floor(GTTime * 8.0)));
    float tear = step(1.0 - Tearing * 0.12, tearSeed) * (tearSeed - 0.5) * 0.08 * Tearing;
    uv.x += tear;

    // 亮度取原位，色度往右糊——正是 VHS 把色度带宽砍到 1/4 的后果
    float bleed = ChromaBleed * (0.5 + Wear);
    vec3 sharp = texture(InSampler, uv).rgb;
    vec3 soft = (texture(InSampler, uv + vec2(bleed, 0.0)).rgb
               + texture(InSampler, uv + vec2(bleed * 2.0, 0.0)).rgb
               + texture(InSampler, uv + vec2(bleed * 3.0, 0.0)).rgb) / 3.0;

    float luma = dot(sharp, vec3(0.2126, 0.7152, 0.0722));
    float softLuma = dot(soft, vec3(0.2126, 0.7152, 0.0722));
    // 重新合成：亮度用锐的，色度差用糊的
    vec3 col = vec3(luma) + (soft - vec3(softLuma)) * Saturation;

    // 雪花噪点，暗部更明显
    float n = hash(gl_FragCoord.xy + vec2(GTTime * 91.0, GTTime * 57.0));
    col += (n - 0.5) * Noise * Wear * (1.2 - clamp(luma, 0.0, 1.0));

    // 磁头切换：画面底部固定的一条乱码带，这是 VHS 最容易被认出来的细节
    float head = smoothstep(0.055, 0.0, uv.y) * Tearing;
    col = mix(col, vec3(hash(vec2(gl_FragCoord.x, floor(GTTime * 20.0)))), head * 0.8);

    fragColor = vec4(col, 1.0);
}
