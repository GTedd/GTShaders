#version 330
#extension GL_ARB_separate_shader_objects : require
uniform sampler2D WtSampler;
uniform sampler2D MetaSampler;
uniform sampler2D XSampler;
layout(std140) uniform LlmPass { vec4 A; vec4 B; };
#include <gtllm:weights.glsl>
layout(location=0) in vec2 texCoord;
layout(location=0) out vec4 fragColor;
// LayerNorm。gamma/beta 不量化，直接从 meta 取 float32
void main() {
    int o = int(gl_FragCoord.y);
    int GW = int(A.z), GB = int(A.w);
    float mu = 0.0;
    for (int i = 0; i < H; i++) mu += texF(XSampler, ivec2(0, i));
    mu /= float(H);
    float var = 0.0;
    for (int i = 0; i < H; i++) { float t = texF(XSampler, ivec2(0, i)) - mu; var += t * t; }
    var /= float(H);
    float x = texF(XSampler, ivec2(0, o));
    fragColor = packF((x - mu) * inversesqrt(var + EPS) * metaAt(GW + o) + metaAt(GB + o));
}
