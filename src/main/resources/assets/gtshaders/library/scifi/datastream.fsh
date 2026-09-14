// 数据流 / Data Stream
// 一列列数据块从上往下淌，落在画面亮部时会"贴合"到画面上——像系统正在扫描并读取眼前的东西。
// 与代码雨的区别在这里：代码雨只管自己下落，数据流的亮度受画面本身调制，
// 所以它看起来是在读<b>这个场景</b>，而不是随便叠了一层动画。
//
// [en_us]
// Columns of data blocks flowing down over the scene.
// Where they land on bright parts of the picture they "stick" to it, as if a system were scanning and reading
// whatever is in front of you.
// This is the difference from Code Rain: code rain just falls on its own, while the data stream's brightness is
// modulated by the picture itself, so it looks like it is reading <b>this scene</b> rather than being some
// animation layered on top.
//
// @param name=Columns type=float min=10 max=300 default=90 zh_cn=列数 en_us=Columns
// @param name=Speed type=float min=0 max=8 default=1.6 zh_cn=下落速度 en_us=Fall Speed
// @param name=BlockLen type=float min=0.02 max=0.6 default=0.16 zh_cn=数据块长度 en_us=Block Length
// @param name=Density type=float min=0 max=1 default=0.55 zh_cn=列占用率 en_us=Column Density

// @group 贴合 / Scene Coupling
// @param name=Couple type=float min=0 max=1 default=0.6 zh_cn=贴合画面亮部 en_us=Couple to Scene desc_zh_cn=让数据只在画面亮的地方显现，看起来像在读取眼前的物体 desc_en_us=Makes the stream appear only over bright areas, as if reading the scene
// @param name=Dim type=float min=0 max=1 default=0.4 zh_cn=底图压暗 en_us=Scene Dim

// @group 外观 / Look
// @param name=StreamColor type=color3 default=#39FF9E zh_cn=数据色 en_us=Stream Color
// @param name=HeadColor type=color3 default=#DFFFF0 zh_cn=头部色 en_us=Head Color
// @param name=Gain type=float min=0 max=4 default=1.5 zh_cn=亮度 en_us=Gain
// @param name=Segments type=float min=2 max=40 default=14 zh_cn=块内分段 en_us=Segments

float hash11(float p) {
    return fract(sin(p * 45.233) * 37193.4137);
}

void main() {
    vec2 asp = vec2(OutSize.x / max(OutSize.y, 1.0), 1.0);
    vec3 src = texture(InSampler, texCoord).rgb;

    float cx = texCoord.x * Columns;
    float id = floor(cx);
    float fx = fract(cx);

    float live = step(1.0 - Density, hash11(id * 3.13));
    float speed = mix(0.6, 1.6, hash11(id * 7.91));
    float head = fract(hash11(id * 2.37) + GTTime * Speed * speed * 0.25);

    // 距离头部多远：头部最亮，往上拖一条渐暗的尾巴
    float dist = head - (1.0 - texCoord.y);
    float inBlock = dist > 0.0 && dist < BlockLen ? 1.0 : 0.0;
    float tail = inBlock * (1.0 - dist / max(BlockLen, 1e-4));

    // 块内切成小段，看起来才像一串字符而不是一条光带
    float seg = step(0.35, hash11(id * 11.7 + floor((1.0 - texCoord.y) * Segments / max(BlockLen, 1e-3))));
    float body = tail * seg * live;
    float headMask = smoothstep(0.02, 0.0, abs(dist)) * live;

    // 列内两侧留缝
    body *= smoothstep(0.0, 0.15, fx) * smoothstep(1.0, 0.85, fx);

    float g = dot(src, vec3(0.2126, 0.7152, 0.0722));
    float couple = mix(1.0, smoothstep(0.12, 0.6, g), Couple);

    vec3 col = src * (1.0 - Dim);
    col += StreamColor * body * couple * Gain;
    col += HeadColor * headMask * couple * Gain * 1.4;

    fragColor = vec4(col, 1.0);
}
