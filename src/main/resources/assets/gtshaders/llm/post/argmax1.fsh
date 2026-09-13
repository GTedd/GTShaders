#version 330
#extension GL_ARB_separate_shader_objects : require
uniform sampler2D WtSampler;
uniform sampler2D MetaSampler;
uniform sampler2D HSampler;
#include <gtllm:weights.glsl>
layout(location=0) in vec2 texCoord;
layout(location=0) out vec4 fragColor;

// 扫词表求 argmax 的第一半：每个纹素负责一段，只报回<b>下标</b>。
//
// 不报回分数是有意的。词表 50257 行、每行 64 维，扫一遍就是 320 万次采样，
// 是全链最贵的一笔。若像常见写法那样用两行分别存「最大值」和「对应下标」，
// 两行都得把这段重扫一遍——白白多花一倍。这里只扫一遍，
// 第二半再拿这 SLICES 个下标回算分数（才 64×64 次），总量直接减半。
void main() {
    int slot = int(gl_FragCoord.x);
    int per = (VOCAB + SLICES - 1) / SLICES;
    float h[H];
    for (int d = 0; d < H; d++) h[d] = texF(HSampler, ivec2(0, d));
    float best = -1e30;
    int besti = slot * per;
    for (int t = 0; t < per; t++) {
        int v = slot * per + t;
        if (v >= VOCAB) break;
        float s = 0.0;
        for (int d = 0; d < H; d++) s += h[d] * w8(WTE_WOFF + v * H + d);
        s *= metaAt(WTE_SOFF + v);
        if (s > best) { best = s; besti = v; }
    }
    fragColor = packF(float(besti));
}
