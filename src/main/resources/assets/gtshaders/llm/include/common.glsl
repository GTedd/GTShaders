#ifndef GTLLM_COMMON
#define GTLLM_COMMON
#include <gtllm:dims.glsl>

// ---- float32 位打包 ----
//
// 与工程里的 gtPackFloat / gtUnpackFloat 逐位兼容（见 docs/reference/位打包与数据通道.md）。
// scale、bias、layernorm 参数、token id、光标位置全走这条路：它们要么量程不定，
// 要么差一点点就全错，8 位定点扛不住。
float unpackF(vec4 c) {
    uvec4 b = uvec4(c * 255.0 + 0.5);
    return uintBitsToFloat(b.x | (b.y << 8) | (b.z << 16) | (b.w << 24));
}
vec4 packF(float v) {
    uint u = floatBitsToUint(v);
    return vec4(float(u & 0xffu), float((u >> 8) & 0xffu),
                float((u >> 16) & 0xffu), float((u >> 24) & 0xffu)) / 255.0;
}
float texF(sampler2D s, ivec2 p) { return unpackF(texelFetch(s, p, 0)); }
int   texI(sampler2D s, ivec2 p) { return int(texF(s, p) + 0.5); }

// ---- 状态槽位 ----
#define ST_EPOCH 0
#define ST_POS   1
#define ST_DONE  2
#define ST_PLEN  3
#define ST_NGEN  4

// gelu_new：GPT-Neo 用的就是这个 tanh 近似，不是精确 erf 版
float gelu(float x) {
    return 0.5 * x * (1.0 + tanh(0.7978845608028654 * (x + 0.044715 * x * x * x)));
}
#endif
