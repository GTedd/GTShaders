#version 330
#extension GL_ARB_separate_shader_objects : require
uniform sampler2D WtSampler;
uniform sampler2D MetaSampler;
uniform sampler2D HSampler;
uniform sampler2D A1Sampler;
#include <gtllm:weights.glsl>
layout(location=0) in vec2 texCoord;
layout(location=0) out vec4 fragColor;
// 第二半：把 SLICES 个候选的分数重算一遍再比。回算只有 SLICES×H 次采样，比多扫一遍词表便宜得多
void main() {
    float h[H];
    for (int d = 0; d < H; d++) h[d] = texF(HSampler, ivec2(0, d));
    float best = -1e30;
    int besti = 0;
    for (int k = 0; k < SLICES; k++) {
        int v = clamp(texI(A1Sampler, ivec2(k, 0)), 0, VOCAB - 1);
        float s = 0.0;
        for (int d = 0; d < H; d++) s += h[d] * w8(WTE_WOFF + v * H + d);
        s *= metaAt(WTE_SOFF + v);
        if (s > best) { best = s; besti = v; }
    }
    fragColor = packF(float(besti));
}
