#version 330
#extension GL_ARB_separate_shader_objects : require
uniform sampler2D InSampler;
uniform sampler2D OrientationSampler;
#include <gtminimap:common.glsl>
layout(location=0) in vec2 texCoord;
layout(location=0) out vec4 fragColor;
void main() {
    if((nibble(1)&2)==0 || nibble(0)!=13) {fragColor=vec4(0.0);return;}
    uint seed=field(10,4);
    vec2 angles=unpackOrientation(texelFetch(OrientationSampler,ivec2(0,0),0));
    float yaw=angles.x*6.2831853, pitch=(angles.y-0.5)*3.14159265;
    vec3 forward=vec3(-sin(yaw)*cos(pitch),-sin(pitch),cos(yaw)*cos(pitch));
    vec3 right=vec3(-cos(yaw),0.0,-sin(yaw));
    vec3 up=cross(right,forward);
    float fov=float(70+nibble(31)*20)*0.0174532925;
    vec2 uv=texCoord*2.0-1.0;
    vec3 ray=normalize(forward+(right*uv.x*(ScreenSize.x/ScreenSize.y)+up*uv.y)*tan(fov*0.5));
    vec3 origin=CameraOffset;
    ivec3 cell=ivec3(floor(origin));
    ivec3 stepDir=ivec3(sign(ray));
    vec3 inverse=1.0/max(abs(ray),vec3(0.000001));
    vec3 boundary=vec3(cell)+step(vec3(0.0),ray);
    vec3 next=(boundary-origin)*sign(ray)*inverse;
    int lastAxis=1;
    float distance=0.0;
    ivec2 cached=ivec2(2147483647);
    int ground=0;
    for(int i=0;i<192;i++) {
        ivec3 world=CameraBlockPos+cell;
        if(world.xz!=cached) {cached=world.xz;ground=terrainHeight(world.xz,seed);}
        int surface=max(ground,32);
        if(world.y<=surface) {
            vec3 color=terrainColor(ground);
            if(world.y<ground-3) color=vec3(0.36,0.32,0.27);
            float shade=lastAxis==1?1.0:lastAxis==0?0.72:0.56;
            float fog=1.0-exp(-distance*0.008);
            fragColor=vec4(mix(color*shade,vec3(0.57,0.72,0.83),fog),1.0);
            return;
        }
        if(next.x<=next.y&&next.x<=next.z) {distance=next.x;next.x+=inverse.x;cell.x+=stepDir.x;lastAxis=0;}
        else if(next.y<=next.z) {distance=next.y;next.y+=inverse.y;cell.y+=stepDir.y;lastAxis=1;}
        else {distance=next.z;next.z+=inverse.z;cell.z+=stepDir.z;lastAxis=2;}
        if(distance>192.0) break;
    }
    vec3 sky=mix(vec3(0.64,0.78,0.87),vec3(0.16,0.37,0.65),clamp(ray.y,0.0,1.0));
    float sun=pow(max(dot(ray,normalize(vec3(-0.45,0.65,0.3))),0.0),160.0);
    fragColor=vec4(sky+vec3(1.0,0.82,0.48)*sun,1.0);
}
