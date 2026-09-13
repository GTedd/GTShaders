#version 330
#extension GL_ARB_separate_shader_objects : require
uniform sampler2D InSampler;
layout(location=0) in vec2 texCoord;
layout(location=0) out vec4 fragColor;
// 原样搬运。persistent 目标不能在同一个通道里既读又写，所以每个状态目标都要配一次拷贝
void main() { fragColor = texelFetch(InSampler, ivec2(gl_FragCoord.xy), 0); }
