#version 330
#extension GL_ARB_separate_shader_objects : require
layout(location=0) out vec4 fragColor;
void main() {
    if(gl_FragCoord.x>=120.0 || gl_FragCoord.y>=1.0) discard;
    fragColor=vec4(0.0,0.0,0.0,1.0);
}
