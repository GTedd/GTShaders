#version 330
#extension GL_ARB_separate_shader_objects : require
uniform sampler2D InSampler;
uniform sampler2D PreviousSampler;
#include <gtminimap:common.glsl>
layout(location=0) in vec2 texCoord;
layout(location=0) out vec4 fragColor;
void main() {
    float time=fract(GameTime);
    if(texCoord.x>=0.5) {
        float encoded=floor(time*16777215.0);
        fragColor=vec4(floor(encoded/65536.0),mod(floor(encoded/256.0),256.0),mod(encoded,256.0),255.0)/255.0;
        return;
    }
    vec2 target=vec2(float(field(4,3))/3600.0,float(field(7,3))/1800.0);
    vec4 stamp=texelFetch(PreviousSampler,ivec2(1,0),0);
    if(stamp.a<0.5) {fragColor=packOrientation(target);return;}
    vec3 bytes=floor(stamp.rgb*255.0+0.5);
    float before=dot(bytes,vec3(65536.0,256.0,1.0))/16777215.0;
    float elapsed=fract(time-before+1.0)*1200.0;
    vec2 old=unpackOrientation(texelFetch(PreviousSampler,ivec2(0,0),0));
    float blend=1.0-exp(-min(elapsed,0.25)/0.035);
    if(elapsed>0.25) blend=1.0;
    float delta=fract(target.x-old.x+0.5)-0.5;
    fragColor=packOrientation(vec2(fract(old.x+delta*blend+1.0),mix(old.y,target.y,blend)));
}
