#version 330
#extension GL_ARB_separate_shader_objects : require
uniform sampler2D DetokSampler;
uniform sampler2D LayoutSampler;
uniform sampler2D SeqSampler;
uniform sampler2D StateSampler;
#include <gtllm:detok.glsl>
layout(location=0) in vec2 texCoord;
layout(location=0) out vec4 fragColor;

// 每个 token 在字符网格里的起始列，是一张前缀和。
//
// 这张表存在的唯一理由是让排版变成 O(1)。否则 charmap 的每个纹素都得从头遍历整个
// token 序列才能知道自己该显示哪个字，4096 个纹素乘 256 个 token 就是上千万次采样。
// 有了前缀和，每帧只需要算<b>新增的那一格</b>。
void main() {
    int x = int(gl_FragCoord.x);
    if (texI(StateSampler, ivec2(5, 0)) == 1) { fragColor = packF(0.0); return; }
    int pos = texI(StateSampler, ivec2(ST_POS, 0));
    if (x != pos || pos <= 0 || pos >= MAXSEQ) {
        fragColor = texelFetch(LayoutSampler, ivec2(x, 0), 0);
        return;
    }
    int prev = pos - 1;
    int start = texI(LayoutSampler, ivec2(prev, 0));
    int tok = clamp(texI(SeqSampler, ivec2(prev, 0)), 0, VOCAB - 1);
    int n = 0;
    for (int j = 0; j < DETOK_W; j++) {
        if (detokChar(tok, j) == 0) break;
        n++;
    }
    fragColor = packF(float(start + n));
}
