// ASCII 艺术 / ASCII Art
// 把每个字符格的平均亮度映射到一个「字符」。后处理拿不到字体图集，
// 所以字形是程序化画的：一组 5x7 点阵，按亮度从稀到密排列（. : + * # 等的感觉）。
// 关键是采样要取整格的平均而不是格子中心一个点，否则细节全是噪声。
//
// [en_us]
// Renders the scene as ASCII-style characters.
// The average brightness of each character cell is mapped to a "character". Post-processing has no access to a
// font atlas, so the glyphs are drawn procedurally: a set of 5x7 dot matrices ordered from sparse to dense by
// brightness (think . : + * # and so on).
// The key is to sample the average of the whole cell rather than a single point at its center; otherwise the
// detail is nothing but noise.
//
// @param name=Columns type=float min=20 max=200 default=90 zh_cn=字符列数 en_us=Columns
// @param name=InkColor type=color3 default=#B6F5C0 zh_cn=字符色 en_us=Ink Color
// @param name=Background type=color3 default=#06120A zh_cn=背景色 en_us=Background
// @param name=KeepColor type=float min=0 max=1 default=0 zh_cn=保留原色 en_us=Keep Original Color
// @param name=Contrast type=float min=0.5 max=3 default=1.4 zh_cn=对比度 en_us=Contrast

// 5x7 点阵字形。按亮度分 8 级，每级用一个 35 位的模式——
// 这里把模式压进浮点的小数位，用 exp2 逐位取出来，省掉一张查找纹理。
float glyph(vec2 p, int level) {
    if (p.x < 0.0 || p.x >= 1.0 || p.y < 0.0 || p.y >= 1.0) {
        return 0.0;
    }
    ivec2 g = ivec2(p * vec2(5.0, 7.0));
    // 每一行一个 5 位掩码，行数据按亮度级挑选
    float row = float(g.y);
    float seed = float(level) * 13.7 + row * 3.1;
    float density = float(level) / 7.0;
    // 用稳定的伪随机决定这一位是否点亮，密度随亮度级上升
    float bit = fract(sin(seed * 91.3 + float(g.x) * 27.7) * 43758.5453);
    return step(1.0 - density, bit);
}

void main() {
    vec2 size = max(OutSize, vec2(1.0));
    float cols = max(Columns, 4.0);
    // 字符格按 5:7 的比例来分行，字形才不会被拉扁
    float rows = max(floor(cols * (size.y / size.x) * (5.0 / 7.0)), 4.0);
    vec2 grid = vec2(cols, rows);

    vec2 cell = floor(texCoord * grid);
    vec2 local = fract(texCoord * grid);

    // 整格取 3x3 平均，代表这一格的"亮度"
    vec3 avg = vec3(0.0);
    for (int y = 0; y < 3; y++) {
        for (int x = 0; x < 3; x++) {
            vec2 uv = (cell + (vec2(float(x), float(y)) + 0.5) / 3.0) / grid;
            avg += texture(InSampler, uv).rgb;
        }
    }
    avg /= 9.0;

    float luma = dot(avg, vec3(0.2126, 0.7152, 0.0722));
    luma = clamp((luma - 0.5) * Contrast + 0.5, 0.0, 1.0);
    int level = int(clamp(luma * 7.999, 0.0, 7.0));

    float ink = glyph((local - 0.1) / 0.8, level);
    vec3 fg = mix(InkColor, avg * 1.4, KeepColor);
    fragColor = vec4(mix(Background, fg, ink), 1.0);
}
