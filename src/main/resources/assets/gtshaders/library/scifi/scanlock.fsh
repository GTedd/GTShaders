// 扫描建模 / Scan Lock
// 一道扫描面从上往下（或从下往上）掠过，掠过之处画面被替换成边缘线框，
// 扫完之后线框整体保留一小会儿再淡回原画面——「系统正在把眼前的东西建模」。
//
// 线框是画面的梯度：亮度变化剧烈的地方就是物体轮廓。这不需要任何几何信息，
// 后处理层拿得到的只有一张图，而这张图里已经包含了全部轮廓。
//
// [en_us]
// A scan plane that turns the scene into an edge wireframe.
// The scan plane sweeps from top to bottom (or bottom to top), replacing everything it passes with an edge
// wireframe. Once the sweep is done, the whole wireframe stays for a moment and then fades back to the original
// picture: "the system is modeling what's in front of you".
//
// The wireframe is the image gradient: wherever brightness changes sharply is an object outline. No geometry is
// needed. A post-processing layer only gets a single image, and that image already contains every outline.
//
// @param name=Period type=float min=0.5 max=20 default=4 zh_cn=扫描周期(秒) en_us=Scan Period
// @param name=ScanTime type=float min=0.1 max=1 default=0.55 zh_cn=扫描用时占比 en_us=Sweep Duration
// @param name=Upward type=bool default=0 zh_cn=改为自下而上 en_us=Bottom-up
// @param name=Hold type=float min=0 max=1 default=0.5 zh_cn=线框保留 en_us=Wireframe Hold

// @group 线框 / Wireframe
// @param name=EdgeGain type=float min=0 max=8 default=3 zh_cn=边缘增益 en_us=Edge Gain
// @param name=EdgeColor type=color3 default=#5FE0FF zh_cn=线框色 en_us=Wire Color
// @param name=Background type=color3 default=#03060C zh_cn=线框底色 en_us=Wire Backdrop

// @group 扫描面 / Scan Plane
// @param name=PlaneColor type=color3 default=#FFFFFF zh_cn=扫描面色 en_us=Plane Color
// @param name=PlaneWidth type=float min=0.002 max=0.15 default=0.015 zh_cn=扫描面厚度 en_us=Plane Width
// @param name=PlaneGain type=float min=0 max=6 default=2.2 zh_cn=扫描面亮度 en_us=Plane Gain

float luma(vec3 c) {
    return dot(c, vec3(0.2126, 0.7152, 0.0722));
}

void main() {
    vec3 src = texture(InSampler, texCoord).rgb;

    float period = max(Period, 0.5);
    float t = fract(GTTime / period);
    float sweep = clamp(t / max(ScanTime, 0.05), 0.0, 1.0);

    float y = Upward > 0.5 ? 1.0 - texCoord.y : texCoord.y;
    float plane = sweep;

    // 已扫描区：在扫描面之后。保留度随扫完的时间衰减
    float after = step(y, plane);
    float held = t <= ScanTime ? 1.0 : exp(-(t - ScanTime) * mix(12.0, 1.5, Hold));
    float wire = after * held;

    // 梯度即轮廓
    vec2 px = 1.0 / max(OutSize, vec2(1.0));
    float lx = luma(texture(InSampler, texCoord + vec2(px.x, 0.0)).rgb)
             - luma(texture(InSampler, texCoord - vec2(px.x, 0.0)).rgb);
    float ly = luma(texture(InSampler, texCoord + vec2(0.0, px.y)).rgb)
             - luma(texture(InSampler, texCoord - vec2(0.0, px.y)).rgb);
    float edge = clamp(length(vec2(lx, ly)) * EdgeGain * 6.0, 0.0, 1.0);

    vec3 wireCol = Background + EdgeColor * edge;
    vec3 col = mix(src, wireCol, clamp(wire, 0.0, 1.0));

    // 扫描面本身
    float band = smoothstep(PlaneWidth, 0.0, abs(y - plane)) * step(t, ScanTime);
    col += PlaneColor * band * PlaneGain;
    // 扫描面前方一小段预亮，让它看起来有厚度
    col += EdgeColor * smoothstep(PlaneWidth * 6.0, 0.0, abs(y - plane)) * 0.25 * step(t, ScanTime);

    fragColor = vec4(col, 1.0);
}
