#ifndef GTLLM_DETOK
#define GTLLM_DETOK
#include <gtllm:common.glsl>
// token id -> 字符。detok.png 也是一个纹素装四个字节，取法同权重但不减偏移。
// 0 表示这个 token 的字符到此为止
int detokChar(int tok, int j) {
    int i = tok * DETOK_W + j;
    int t = i >> 2;
    vec4 c = texelFetch(DetokSampler, ivec2(t % ATLASW, t / ATLASW), 0);
    int lane = i & 3;
    float b = lane == 0 ? c.r : lane == 1 ? c.g : lane == 2 ? c.b : c.a;
    return int(floor(b * 255.0 + 0.5));
}
#endif
