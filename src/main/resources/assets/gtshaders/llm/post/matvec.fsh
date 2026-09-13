#version 330
#extension GL_ARB_separate_shader_objects : require
uniform sampler2D WtSampler;
uniform sampler2D MetaSampler;
uniform sampler2D XSampler;
uniform sampler2D RSampler;
layout(std140) uniform LlmPass { vec4 A; vec4 B; };
#include <gtllm:weights.glsl>
layout(location=0) in vec2 texCoord;
layout(location=0) out vec4 fragColor;

// 通用矩阵-向量乘，三种用法共用：out_proj（带残差）、c_fc（带 gelu）、c_proj（带残差）。
// A = (IN, OUT, 权重偏移, scale 偏移)，B = (bias 偏移或 -1, 要不要 gelu, 要不要加残差, -)
//
// scale 是 per-row 的，所以整行累加完再乘一次就够——循环里只做 int8 取值和乘加。
void main() {
    int o = int(gl_FragCoord.y);
    int IN = int(A.x), WOFF = int(A.z), SOFF = int(A.w);
    int BOFF = int(B.x);
    float acc = 0.0;
    for (int i = 0; i < IN; i++) acc += texF(XSampler, ivec2(0, i)) * w8(WOFF + o * IN + i);
    acc *= metaAt(SOFF + o);
    if (BOFF >= 0) acc += metaAt(BOFF + o);
    if (B.y > 0.5) acc = gelu(acc);
    if (B.z > 0.5) acc += texF(RSampler, ivec2(0, o));
    fragColor = packF(acc);
}
