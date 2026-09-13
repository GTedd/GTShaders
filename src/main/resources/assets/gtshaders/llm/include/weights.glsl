#ifndef GTLLM_WEIGHTS
#define GTLLM_WEIGHTS
#include <gtllm:common.glsl>
// 依赖 WtSampler / MetaSampler，所以单独一份：注意力和缓存合并那几个通道没有这两张图，
// 把它们塞进 common 会让那些着色器因为「引用了未声明的采样器」直接编译不过。
// ---- 权重取用 ----
//
// weights.png 是 RGBA8，一个纹素装 4 个 int8 权重（打包时存的是 v+128）。
// 一次 texelFetch 取回四个，索引的低两位选通道——这就是 8bit 量化省下来的四倍面积。
float w8(int i) {
    int t = i >> 2;
    vec4 c = texelFetch(WtSampler, ivec2(t % ATLASW, t / ATLASW), 0);
    int lane = i & 3;
    float b = lane == 0 ? c.r : lane == 1 ? c.g : lane == 2 ? c.b : c.a;
    // 先四舍五入再减偏移：UNORM8 转 float 只保证一个 ULP，截断会整整差 1
    return floor(b * 255.0 + 0.5) - 128.0;
}

float metaAt(int i) {
    return unpackF(texelFetch(MetaSampler, ivec2(i % ATLASW, i / ATLASW), 0));
}
#endif
