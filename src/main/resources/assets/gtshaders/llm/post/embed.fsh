#version 330
#extension GL_ARB_separate_shader_objects : require
uniform sampler2D WtSampler;
uniform sampler2D MetaSampler;
uniform sampler2D SeqSampler;
uniform sampler2D StateSampler;
#include <gtllm:weights.glsl>
layout(location=0) in vec2 texCoord;
layout(location=0) out vec4 fragColor;
// token embedding + 位置编码。两个都是量化过的矩阵，各自按行取 scale
void main() {
    int d = int(gl_FragCoord.y);
    int p = clamp(texI(StateSampler, ivec2(ST_POS, 0)) - 1, 0, MAXSEQ - 1);
    int tok = clamp(texI(SeqSampler, ivec2(p, 0)), 0, VOCAB - 1);
    float e = w8(WTE_WOFF + tok * H + d) * metaAt(WTE_SOFF + tok);
    float q = w8(WPE_WOFF + p * H + d) * metaAt(WPE_SOFF + p);
    fragColor = packF(e + q);
}
