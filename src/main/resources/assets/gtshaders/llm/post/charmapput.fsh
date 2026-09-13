#version 330
#extension GL_ARB_separate_shader_objects : require
uniform sampler2D DetokSampler;
uniform sampler2D CharSampler;
uniform sampler2D LayoutSampler;
uniform sampler2D SeqSampler;
uniform sampler2D StateSampler;
#include <gtllm:detok.glsl>
layout(location=0) in vec2 texCoord;
layout(location=0) out vec4 fragColor;

// 字符网格。每帧只写新 token 落到的那几格，其余保持——这就是前缀和换来的 O(1)。
void main() {
    int col = int(gl_FragCoord.x), row = int(gl_FragCoord.y);
    int idx = row * CMW + col;
    if (texI(StateSampler, ivec2(5, 0)) == 1) { fragColor = packF(0.0); return; }
    int pos = texI(StateSampler, ivec2(ST_POS, 0));
    int prev = pos - 1;
    if (prev < 0 || prev >= MAXSEQ) { fragColor = texelFetch(CharSampler, ivec2(col, row), 0); return; }
    int start = texI(LayoutSampler, ivec2(prev, 0));
    int tok = clamp(texI(SeqSampler, ivec2(prev, 0)), 0, VOCAB - 1);
    int off = idx - start;
    if (off < 0 || off >= DETOK_W) { fragColor = texelFetch(CharSampler, ivec2(col, row), 0); return; }
    int c = detokChar(tok, off);
    if (c == 0) { fragColor = texelFetch(CharSampler, ivec2(col, row), 0); return; }
    fragColor = packF(float(c));
}
