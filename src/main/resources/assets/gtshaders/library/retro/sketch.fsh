// 铅笔素描 / Pencil Sketch
// 两层叠加：Sobel 求出的轮廓线，以及按亮度决定密度的排线（hatching）。
// 排线方向必须随亮度切换（暗处交叉排线），单方向排线看着像划痕不像素描。
//
// [en_us]
// A pencil sketch made of outlines and hatching.
// Two layers stacked: outlines found with Sobel, and hatching whose density follows brightness.
// The hatching direction has to change with brightness (cross-hatching in dark areas). Hatching in a single
// direction looks like scratches, not a sketch.
//
// @param name=PaperColor type=color3 default=#F2EDE1 zh_cn=纸色 en_us=Paper
// @param name=PencilColor type=color3 default=#2B2620 zh_cn=铅笔色 en_us=Pencil
// @param name=EdgeGain type=float min=0 max=6 default=2.4 zh_cn=轮廓强度 en_us=Edge Gain
// @param name=Hatch type=float min=0 max=1 default=0.6 zh_cn=排线强度 en_us=Hatching
// @param name=HatchScale type=float min=100 max=1200 default=420 zh_cn=排线密度 en_us=Hatch Density

float luma(vec3 c) {
    return dot(c, vec3(0.2126, 0.7152, 0.0722));
}

float sampleLuma(vec2 uv) {
    return luma(texture(InSampler, uv).rgb);
}

void main() {
    vec2 texel = 1.0 / max(OutSize, vec2(1.0));

    // Sobel：横竖两个方向的梯度，模长就是边缘强度
    float tl = sampleLuma(texCoord + texel * vec2(-1.0, 1.0));
    float t  = sampleLuma(texCoord + texel * vec2(0.0, 1.0));
    float tr = sampleLuma(texCoord + texel * vec2(1.0, 1.0));
    float l  = sampleLuma(texCoord + texel * vec2(-1.0, 0.0));
    float r  = sampleLuma(texCoord + texel * vec2(1.0, 0.0));
    float bl = sampleLuma(texCoord + texel * vec2(-1.0, -1.0));
    float b  = sampleLuma(texCoord + texel * vec2(0.0, -1.0));
    float br = sampleLuma(texCoord + texel * vec2(1.0, -1.0));

    float gx = (tr + 2.0 * r + br) - (tl + 2.0 * l + bl);
    float gy = (tl + 2.0 * t + tr) - (bl + 2.0 * b + br);
    float edge = clamp(length(vec2(gx, gy)) * EdgeGain, 0.0, 1.0);

    float v = sampleLuma(texCoord);

    // 排线：三层不同角度，按亮度依次加入。越暗层数越多，就是素描的加深方式
    vec2 p = texCoord * HatchScale;
    float h = 0.0;
    if (v < 0.75) {
        h = max(h, smoothstep(0.4, 0.6, abs(sin(p.x + p.y))));
    }
    if (v < 0.5) {
        h = max(h, smoothstep(0.4, 0.6, abs(sin(p.x - p.y))));
    }
    if (v < 0.28) {
        h = max(h, smoothstep(0.45, 0.6, abs(sin(p.x * 1.7))));
    }
    h *= Hatch * (1.0 - v);

    float ink = clamp(edge + h, 0.0, 1.0);
    fragColor = vec4(mix(PaperColor, PencilColor, ink), 1.0);
}
