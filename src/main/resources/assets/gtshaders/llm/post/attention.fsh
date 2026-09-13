#version 330
#extension GL_ARB_separate_shader_objects : require
uniform sampler2D QkvSampler;
uniform sampler2D KvSampler;
uniform sampler2D StateSampler;
layout(std140) uniform LlmPass { vec4 A; vec4 B; };
#include <gtllm:common.glsl>
layout(location=0) in vec2 texCoord;
layout(location=0) out vec4 fragColor;

// 单 token 的因果注意力，历史 k/v 全部来自 KV cache。
//
// 两处容易写错的地方：
// 1. **GPT-Neo 不做 1/sqrt(d) 缩放**。这是它和 GPT-2 的差别之一，多除一次结果全歪。
// 2. 当前位置 p 的 k/v <b>还没进 cache</b>——合并在本帧更靠后的通道里做。
//    所以 j==p 时要从 qkv 图里取，只有 j<p 才读 cache。
void main() {
    int f = int(gl_FragCoord.y);
    int head = f / HD, d = f % HD, hbase = head * HD;
    int layer = int(A.x), win = int(A.y);
    int p = clamp(texI(StateSampler, ivec2(ST_POS, 0)) - 1, 0, MAXSEQ - 1);
    int j0 = win > 0 ? max(0, p - win + 1) : 0;

    int qbase = layer * 3 * H + hbase;          // q 段
    int kbase = layer * 3 * H + H + hbase;      // k 段
    int vbase = layer * 3 * H + 2 * H + hbase;  // v 段
    int cacheK = layer * H + hbase;             // cache 的 k 区
    int cacheV = H * NL + layer * H + hbase;    // cache 的 v 区

    // 先求最大值再做 softmax：logits 会到几十，直接 exp 会溢出成 inf
    float mx = -1e30;
    for (int j = j0; j <= p; j++) {
        float s = 0.0;
        for (int dd = 0; dd < HD; dd++) {
            float kv = (j == p) ? texF(QkvSampler, ivec2(0, kbase + dd))
                                : texF(KvSampler, ivec2(j, cacheK + dd));
            s += texF(QkvSampler, ivec2(0, qbase + dd)) * kv;
        }
        mx = max(mx, s);
    }
    float den = 0.0, num = 0.0;
    for (int j = j0; j <= p; j++) {
        float s = 0.0;
        for (int dd = 0; dd < HD; dd++) {
            float kv = (j == p) ? texF(QkvSampler, ivec2(0, kbase + dd))
                                : texF(KvSampler, ivec2(j, cacheK + dd));
            s += texF(QkvSampler, ivec2(0, qbase + dd)) * kv;
        }
        float e = exp(s - mx);
        den += e;
        num += e * ((j == p) ? texF(QkvSampler, ivec2(0, vbase + d))
                             : texF(KvSampler, ivec2(j, cacheV + d)));
    }
    fragColor = packF(num / max(den, 1e-20));
}
