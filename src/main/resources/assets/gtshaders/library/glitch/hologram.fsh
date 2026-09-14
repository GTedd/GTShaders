// 全息投影 / Hologram
// 投影影像的四个特征：单色偏青、水平扫描条、缓慢上滚的亮带、以及不时的抖动。
// 还有一条最容易漏：全息是"加法"的——它不遮挡背景，而是叠在上面，
// 所以暗部应该几乎完全透明。
//
// [en_us]
// A flickering cyan hologram projection.
// The four traits of a projected image: cyan-tinted monochrome, horizontal scan bars, a bright band slowly rolling
// upward, and occasional jitter.
// One more is the easiest to miss: a hologram is "additive". It doesn't block the background but is layered on
// top of it, so dark areas should be almost fully transparent.
//
// @param name=HoloColor type=color3 default=#63E8FF zh_cn=全息色 en_us=Hologram Color
// @param name=Lines type=float min=50 max=800 default=300 zh_cn=扫描条密度 en_us=Line Density
// @param name=LineDark type=float min=0 max=1 default=0.5 zh_cn=扫描条深度 en_us=Line Darkness
// @param name=Sweep type=float min=0 max=2 default=0.4 zh_cn=亮带速度 en_us=Sweep Speed
// @param name=Jitter type=float min=0 max=0.05 default=0.008 zh_cn=抖动 en_us=Jitter
// @param name=Transparency type=float min=0 max=1 default=0.7 zh_cn=暗部透明 en_us=Dark Transparency

float hash(vec2 p) {
    return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453);
}

void main() {
    // 抖动：按行整体横移，偶尔来一次大的
    float row = floor(texCoord.y * 200.0);
    float j = (hash(vec2(row, floor(GTTime * 15.0))) - 0.5) * Jitter;
    j *= step(0.82, hash(vec2(row, floor(GTTime * 15.0) + 3.0))) * 2.0 + 0.2;

    vec3 src = texture(InSampler, texCoord + vec2(j, 0.0)).rgb;
    float luma = dot(src, vec3(0.2126, 0.7152, 0.0722));

    // 单色化
    vec3 col = HoloColor * (0.25 + luma * 1.3);

    // 扫描条
    float line = 0.5 + 0.5 * sin(texCoord.y * Lines * 3.14159);
    col *= 1.0 - LineDark * (1.0 - line);

    // 缓慢上滚的亮带
    float sweep = fract(texCoord.y - GTTime * Sweep * 0.2);
    col += HoloColor * smoothstep(0.92, 1.0, sweep) * 0.5;

    // 暗部透明：让背景透出来。这一步让它像投影而不是滤镜
    col = mix(col, src, Transparency * (1.0 - clamp(luma * 1.6, 0.0, 1.0)));
    fragColor = vec4(col, 1.0);
}
