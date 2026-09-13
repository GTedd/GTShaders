#version 330
#extension GL_ARB_separate_shader_objects : require
uniform sampler2D WtSampler;
uniform sampler2D MetaSampler;
uniform sampler2D XSampler;
uniform sampler2D PrevSampler;
layout(std140) uniform LlmPass { vec4 A; vec4 B; };
#include <gtllm:weights.glsl>
layout(location=0) in vec2 texCoord;
layout(location=0) out vec4 fragColor;

// q、k、v 三个投影合成一个通道。
//
// 目标是 1×(3·H·L)：每层占 3H 行，依次是 q、k、v。本层的 3H 行现算，其余 (L-1)·3H 行
// 从上一层的结果原样拷过来。拷贝看着浪费，但采样次数和拆成三个通道<b>完全相同</b>
// （算的行数一样多），却省下两次全屏 draw——一层省 2 个，八层就是 16 个。
//
// k、v 之所以要按层堆在一起，是为了让 KV cache 的合并<b>一帧只做一次</b>：
// 最后一层跑完时这张图里已经有全部八层的 k/v 了。
void main() {
    int y = int(gl_FragCoord.y);
    int layer = int(A.x);
    int base = layer * 3 * H;
    if (y < base || y >= base + 3 * H) {
        fragColor = texelFetch(PrevSampler, ivec2(0, y), 0);
        return;
    }
    int local = y - base;
    int which = local / H;          // 0=q 1=k 2=v
    int o = local % H;
    int woff = which == 0 ? int(A.y) : which == 1 ? int(A.z) : int(A.w);
    int soff = which == 0 ? int(B.x) : which == 1 ? int(B.y) : int(B.z);
    float acc = 0.0;
    for (int i = 0; i < H; i++) acc += texF(XSampler, ivec2(0, i)) * w8(woff + o * H + i);
    fragColor = packF(acc * metaAt(soff + o));
}
