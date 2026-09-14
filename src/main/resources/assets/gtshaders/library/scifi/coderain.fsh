// 代码雨 / Code Rain
// 竖排字符从上往下淌，头部近乎纯白、尾部渐暗，字符本身还在不停变。
// 字形不用贴图：把格子切成 5×7 的点阵，按哈希决定每个点亮不亮——远看就是一串看不懂的符号，
// 而这正是需要的效果。字形随时间重掷，字才会"跳"。
//
// [en_us]
// Columns of changing glyphs raining down the screen.
// The head of each column is nearly pure white, the tail fades out, and the glyphs themselves keep changing.
// Glyphs need no texture: each cell is cut into a 5x7 dot grid and a hash decides which dots light up. From a
// distance that reads as a string of unreadable symbols, which is exactly the effect we want. The glyphs are
// re-rolled over time, and that is what makes the characters "jump".
//
// @param name=Columns type=float min=20 max=300 default=110 zh_cn=列数 en_us=Columns
// @param name=Speed type=float min=0 max=8 default=1.4 zh_cn=下落速度 en_us=Fall Speed
// @param name=TrailLen type=float min=0.05 max=1 default=0.35 zh_cn=拖尾长度 en_us=Trail Length
// @param name=Density type=float min=0 max=1 default=0.75 zh_cn=列占用率 en_us=Column Density

// @group 字形 / Glyphs
// @param name=Flicker type=float min=0 max=20 default=6 zh_cn=换字频率 en_us=Glyph Flicker
// @param name=Fill type=float min=0.1 max=0.9 default=0.5 zh_cn=笔画密度 en_us=Stroke Density

// @group 外观 / Look
// @param name=RainColor type=color3 default=#28FF6E zh_cn=字符色 en_us=Rain Color
// @param name=HeadColor type=color3 default=#E8FFF0 zh_cn=头部色 en_us=Head Color
// @param name=Gain type=float min=0 max=4 default=1.6 zh_cn=亮度 en_us=Gain
// @param name=SceneDim type=float min=0 max=1 default=0.75 zh_cn=底图压暗 en_us=Scene Dim

float hash21(vec2 p) {
    return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453);
}

void main() {
    vec3 src = texture(InSampler, texCoord).rgb;

    float cols = max(Columns, 4.0);
    float rows = floor(cols * OutSize.y / max(OutSize.x, 1.0) * 1.6);
    vec2 cell = vec2(cols, max(rows, 4.0));

    vec2 gp = texCoord * cell;
    vec2 gi = floor(gp);
    vec2 gf = fract(gp);

    float live = step(1.0 - Density, hash21(vec2(gi.x, 0.0)));
    float speed = mix(0.5, 1.8, hash21(vec2(gi.x, 1.0)));
    float head = fract(hash21(vec2(gi.x, 2.0)) + GTTime * Speed * speed * 0.2);

    float y = 1.0 - texCoord.y;
    float dist = head - y;
    float trail = dist > 0.0 && dist < TrailLen
        ? pow(1.0 - dist / max(TrailLen, 1e-4), 1.6)
        : 0.0;

    // 5×7 点阵：格内再切一次，按哈希决定每个点亮不亮
    vec2 dot5 = floor(gf * vec2(5.0, 7.0));
    float glyphSeed = hash21(gi + floor(GTTime * Flicker) * 0.37);
    float on = step(1.0 - Fill, hash21(dot5 + glyphSeed * 31.7));
    // 点与点之间留缝，才看得出是点阵而不是实心块
    vec2 sub = fract(gf * vec2(5.0, 7.0));
    on *= step(0.15, sub.x) * step(sub.x, 0.85) * step(0.1, sub.y) * step(sub.y, 0.9);

    float body = trail * on * live;
    float headMask = smoothstep(0.03, 0.0, abs(dist)) * on * live;

    vec3 col = src * (1.0 - SceneDim);
    col += RainColor * body * Gain;
    col += HeadColor * headMask * Gain;

    fragColor = vec4(col, 1.0);
}
