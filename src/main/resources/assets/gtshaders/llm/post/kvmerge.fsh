#version 330
#extension GL_ARB_separate_shader_objects : require
uniform sampler2D CacheSampler;
uniform sampler2D QkvSampler;
uniform sampler2D StateSampler;
#include <gtllm:common.glsl>
layout(location=0) in vec2 texCoord;
layout(location=0) out vec4 fragColor;

// 把本帧算出的 k/v 写进 cache 的第 p 列，其余原样保留。
//
// 只改一列却要整张重画，是后处理的硬约束：同一通道不能既读又写同一目标，
// 也没有 scissor 可以只画一小块。所以 cache 开多大，每帧就要搬多少——
// 这里 MAXSEQ 取 256 而不是 wpe 支持的 2048，正是为了压住这笔搬运。
void main() {
    int x = int(gl_FragCoord.x), y = int(gl_FragCoord.y);
    if (texI(StateSampler, ivec2(5, 0)) == 1) { fragColor = packF(0.0); return; }  // 复位帧清空
    if (texI(StateSampler, ivec2(ST_DONE, 0)) == 1) {
        fragColor = texelFetch(CacheSampler, ivec2(x, y), 0);
        return;
    }
    int p = clamp(texI(StateSampler, ivec2(ST_POS, 0)) - 1, 0, MAXSEQ - 1);
    if (x != p) { fragColor = texelFetch(CacheSampler, ivec2(x, y), 0); return; }
    // cache 的行：前 H·L 行是 k，后 H·L 行是 v；qkv 图里每层占 3H 行
    bool isV = y >= H * NL;
    int r = isV ? y - H * NL : y;
    int layer = r / H, o = r % H;
    int src = layer * 3 * H + (isV ? 2 * H : H) + o;
    fragColor = texelFetch(QkvSampler, ivec2(0, src), 0);
}
