#version 330
#extension GL_ARB_separate_shader_objects : require
#include <minecraft:globals.glsl>
void main() {
    vec2 uv=vec2((gl_VertexIndex<<1)&2,gl_VertexIndex&2);
    gl_Position=vec4(uv*vec2(120.0,1.0)/ScreenSize*2.0-1.0,0.0,1.0);
}
