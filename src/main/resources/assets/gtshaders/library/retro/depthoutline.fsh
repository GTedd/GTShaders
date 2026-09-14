// 深度描边 / Depth Outline
// 用场景深度找几何边缘，给世界画一圈卡通描边。
//
// 这是 26.3 才做得到的效果：snapshot-4 修好 MC-309771 之前，/posteffect 挂的链
// 读 minecraft:main 的深度只能拿到手臂，画出来的线全在屏幕正中那一小块。
//
// 和「铅笔素描」的区别值得说清楚——那个是对**颜色**做 Sobel，所以方块表面的贴图纹理
// 也会被当成边画出来；这个只看**几何**，草方块上的花纹不会出线，只有真正的轮廓才有。
// 两个叠起来用效果也不错。
//
// 深度是非线性的：同样的实际距离差，近处给出的数值差比远处大得多。所以只用绝对差会
// 得到「眼前满是线、远处一条都没有」。「远处补偿」把判据从绝对差连续地推向相对差
// （除以中心点深度），往上调能把远处的边找回来，代价是远景噪声与天际线一起变明显。
//
// [en_us]
// Toon outlines traced from geometric edges in scene depth.
//
// This effect only became possible in 26.3: before snapshot-4 fixed MC-309771, a chain attached with /posteffect
// that read the depth of minecraft:main only got the player's arm, so all the lines ended up in a small patch in
// the middle of the screen.
//
// The difference from Pencil Sketch is worth spelling out. That one runs Sobel on **color**, so the textures on
// block surfaces get drawn as edges too. This one only looks at **geometry**: the pattern on a grass block makes
// no lines, only real outlines do. The two also look good stacked together.
//
// Depth is nonlinear: the same real distance gap gives a much bigger value difference up close than far away. So
// using only the absolute difference gives "lines everywhere right in front of you, not a single one in the
// distance". Far Boost continuously shifts the test from absolute difference toward relative difference (divided
// by the center sample's depth). Turning it up brings distant edges back, at the cost of far-away noise and the
// skyline becoming more visible along with them.
//
// @param name=LineColor type=color3 default=#101014 zh_cn=描边色 en_us=Line Color
// @param name=Threshold type=float min=0.0002 max=0.05 default=0.004 zh_cn=边缘阈值 en_us=Edge Threshold desc_zh_cn=越小线越多。深度差超过它才算一条边 desc_en_us=Lower means more lines. A depth gap must exceed this to count as an edge
// @param name=FarBoost type=float min=0 max=1 default=0.5 zh_cn=远处补偿 en_us=Far Boost desc_zh_cn=0=只按绝对深度差，远处几乎不出线；1=完全按相对差，远近一视同仁但噪声也放大 desc_en_us=0 = absolute depth gap only, far geometry gets no lines; 1 = fully relative, even coverage but noisier
// @param name=Radius type=float min=0.5 max=4 default=1 zh_cn=采样半径(像素) en_us=Sample Radius
// @param name=Softness type=float min=0 max=1 default=0.35 zh_cn=边缘柔和 en_us=Edge Softness
// @param name=Opacity type=float min=0 max=1 default=1 zh_cn=不透明度 en_us=Opacity
// @param name=Darken type=float min=0 max=1 default=0 zh_cn=非边缘压暗 en_us=Darken Fill desc_zh_cn=把没被描边的地方整体压暗，衬出线条 desc_en_us=Dims everything that is not an edge so the lines stand out
// @param name=SkyLines type=bool default=0 zh_cn=天空也描边 en_us=Outline Sky desc_zh_cn=天空没有几何，深度停在最远处；不排除的话天与地的交界会是一条极粗的线 desc_en_us=The sky has no geometry and sits at the far plane; leaving it in draws one very thick line along the horizon
// @param name=ShowDepth type=bool default=0 zh_cn=显示深度图 en_us=Show Depth desc_zh_cn=调参用：直接把深度画出来。反转深度下近处亮、远处暗 desc_en_us=For tuning: draw the raw depth. With reversed depth, near is bright and far is dark

void main() {
    vec3 col = texture(InSampler, texCoord).rgb;

    // 调参时先开这个：阈值该定在哪、远处补偿够不够，看一眼深度图比盲调快得多
    if (ShowDepth > 0.5) {
        fragColor = vec4(vec3(gtDepth(texCoord)), 1.0);
        return;
    }

    if (SkyLines < 0.5 && gtIsSky(texCoord)) {
        fragColor = vec4(col, 1.0);
        return;
    }

    // 分母在 1（绝对差）与中心深度（相对差）之间连续插值。
    // 直接把两种边缘值混起来是不行的——它们量级差好几个数量级，混出来的曲线不单调
    float center = max(gtDepth(texCoord), 1e-4);
    float denom = mix(1.0, center, FarBoost);
    float edge = gtDepthEdge(texCoord, Radius) / denom;

    // 上界要比下界大一点点，否则 Softness=0 时 smoothstep 的两个边界相等，结果未定义
    float hi = Threshold * (1.0 + Softness * 4.0) + 1e-7;
    float line = smoothstep(Threshold, hi, edge);

    col = mix(col, col * (1.0 - Darken * 0.6), 1.0 - line);
    col = mix(col, LineColor, line * Opacity);
    fragColor = vec4(col, 1.0);
}
