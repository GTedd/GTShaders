#version 330
#extension GL_ARB_separate_shader_objects : require
layout(std140) uniform Signal { int Slot; int Value; };
layout(location=0) out vec4 fragColor;
void main() {
    if(ivec2(gl_FragCoord.xy)!=ivec2(Slot,0)) discard;
    fragColor=vec4(float(Value)/15.0,0.0,0.0,1.0);
}
