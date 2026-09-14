// 扫描解码 / Scan Decode
// 一条解码进度线从上往下扫：线之上是已解码的正常画面，线之下还是"未解码"的
// 低分辨率+噪点状态。做加载动画或黑客入侵演出很好用。
//
// [en_us]
// A decode progress line sweeping from top to bottom.
// Above the line is the normal, decoded picture. Below it the image is still in its "undecoded" low-resolution,
// noisy state. Great for loading animations or hacking sequences.
//
// @param name=Progress type=float min=0 max=1 default=0.45 zh_cn=解码进度 en_us=Progress
// @param name=AutoPlay type=bool default=1 zh_cn=循环播放 en_us=Auto Play
// @param name=Period type=float min=0.5 max=10 default=3 zh_cn=周期(秒) en_us=Period
// @param name=LineColor type=color3 default=#5FFFC4 zh_cn=扫描线色 en_us=Scan Line Color
// @param name=RawBlock type=float min=1 max=40 default=12 zh_cn=未解码块大小 en_us=Raw Block Size
// @param name=RawNoise type=float min=0 max=1 default=0.4 zh_cn=未解码噪点 en_us=Raw Noise
// @param name=LineWidth type=float min=0.001 max=0.05 default=0.006 zh_cn=扫描线宽 en_us=Line Width

float hash(vec2 p) {
    return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453);
}

void main() {
    float t = AutoPlay > 0.5 ? fract(GTTime / max(Period, 0.01)) : Progress;
    // texCoord.y 向上为正，扫描线要从上往下走，所以用 1-y 当"从顶部起的进度"
    float y = 1.0 - texCoord.y;

    vec3 decoded = texture(InSampler, texCoord).rgb;

    // 未解码：马赛克 + 噪点 + 去色
    vec2 size = max(OutSize, vec2(1.0));
    vec2 uv = (floor(gl_FragCoord.xy / max(RawBlock, 1.0)) + 0.5) * max(RawBlock, 1.0) / size;
    vec3 raw = texture(InSampler, uv).rgb;
    float luma = dot(raw, vec3(0.2126, 0.7152, 0.0722));
    raw = vec3(luma) * vec3(0.55, 0.65, 0.7);
    raw += (hash(floor(gl_FragCoord.xy / max(RawBlock, 1.0)) + floor(GTTime * 12.0)) - 0.5) * RawNoise;

    vec3 col = y < t ? decoded : raw;

    // 扫描线本身，外加线下方一小段的辉光
    float line = smoothstep(LineWidth, 0.0, abs(y - t));
    float ahead = smoothstep(0.06, 0.0, y - t) * step(t, y);
    col += LineColor * (line * 2.0 + ahead * 0.35);

    fragColor = vec4(col, 1.0);
}
