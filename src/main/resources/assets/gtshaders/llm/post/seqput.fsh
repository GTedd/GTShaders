#version 330
#extension GL_ARB_separate_shader_objects : require
uniform sampler2D SeqSampler;
uniform sampler2D StateSampler;
uniform sampler2D TokSampler;
#include <gtllm:common.glsl>
layout(std140) uniform LlmCtl { vec4 Ctl; vec4 P[PROMPT_VEC4]; };
layout(location=0) in vec2 texCoord;
layout(location=0) out vec4 fragColor;

// token 序列。复位帧整条写成 prompt，之后每帧只在末尾追加上一帧算出来的那个。
//
// 读的是<b>旧</b>状态：本帧的 ingest 还没跑，pos 还停在上一帧处理的位置，
// 而上一帧算出的 tok 正好该落在 pos 这一格。顺序反了就会错开一位。
void main() {
    int x = int(gl_FragCoord.x);
    float epochNow = Ctl.x;
    int   plen = int(Ctl.y + 0.5);
    float epochOld = texF(StateSampler, ivec2(ST_EPOCH, 0));
    int   posOld   = texI(StateSampler, ivec2(ST_POS, 0));
    int   doneOld  = texI(StateSampler, ivec2(ST_DONE, 0));

    if (epochOld != epochNow) {
        // vec4 数组每格装 4 个 token id，超出 prompt 长度的位置留 0
        float v = 0.0;
        if (x < plen && x < MAXSEQ) {
            vec4 q = P[x >> 2];
            int lane = x & 3;
            v = lane == 0 ? q.x : lane == 1 ? q.y : lane == 2 ? q.z : q.w;
        }
        fragColor = packF(v);
        return;
    }
    // 还在预热 prompt，或者已经生成够了：序列不动
    if (doneOld == 1 || posOld < plen || x != posOld) {
        fragColor = texelFetch(SeqSampler, ivec2(x, 0), 0);
        return;
    }
    fragColor = packF(float(texI(TokSampler, ivec2(0, 0))));
}
