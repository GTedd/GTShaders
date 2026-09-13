#version 330
#extension GL_ARB_separate_shader_objects : require
uniform sampler2D StateSampler;
#include <gtllm:common.glsl>
layout(std140) uniform LlmCtl { vec4 Ctl; vec4 P[PROMPT_VEC4]; };
layout(location=0) in vec2 texCoord;
layout(location=0) out vec4 fragColor;

// 推进状态机。一帧处理一个位置：先把 pos 处的 token 喂进去，算出下一个，pos++。
//
// prompt 不是「一次性灌进去」的——KV cache 里必须有它每一个 token 的 k/v，
// 所以前 PLEN 帧是预热：照样跑完整条链，只是把算出来的 token 丢掉。
// pos 走到 PLEN 之后才开始真的采纳输出。
void main() {
    int slot = int(gl_FragCoord.x);
    float epochNow = Ctl.x;
    int   plen = int(Ctl.y + 0.5);
    int   ngen = int(Ctl.z + 0.5);

    float epochOld = texF(StateSampler, ivec2(ST_EPOCH, 0));
    int   posOld   = texI(StateSampler, ivec2(ST_POS, 0));
    int   doneOld  = texI(StateSampler, ivec2(ST_DONE, 0));

    // epoch 变了就是换了 prompt（或第一次启动）：整个状态机复位
    bool fresh = epochOld != epochNow;
    int pos  = fresh ? 1 : (doneOld == 1 ? posOld : posOld + 1);
    int done = fresh ? 0 : (doneOld == 1 ? 1 : (pos - plen >= ngen || pos >= MAXSEQ ? 1 : 0));

    float v = 0.0;
    if (slot == ST_EPOCH) v = epochNow;
    else if (slot == ST_POS)  v = float(pos);
    else if (slot == ST_DONE) v = float(done);
    else if (slot == ST_PLEN) v = float(plen);
    else if (slot == ST_NGEN) v = float(ngen);
    else if (slot == 5)       v = fresh ? 1.0 : 0.0;   // 本帧是不是复位帧，KV cache 要看它
    fragColor = packF(v);
}
