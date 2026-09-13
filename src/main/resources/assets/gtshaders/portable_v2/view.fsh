#version 330
#extension GL_ARB_separate_shader_objects : require
uniform sampler2D InSampler;
uniform sampler2D OrientationSampler;
uniform sampler2D RaysSampler;
uniform sampler2D FontSampler;
uniform sampler2D AtlasSampler;
uniform sampler2D IconsSampler;
// ICON_CONSTANTS
#include <gtminimap:common.glsl>
layout(std140) uniform AtlasConfig { int OriginX; int OriginZ; float Step; int HasAtlas; };
layout(location=0) in vec2 texCoord;
layout(location=0) out vec4 fragColor;
float glyph(vec2 p,int index) {
    if(any(lessThan(p,vec2(0.0)))||any(greaterThanEqual(p,vec2(16.0)))) return 0.0;
    vec2 pixel=vec2((index%16)*16,(index/16)*16)+floor(p)+0.5;
    return texture(FontSampler,pixel/vec2(textureSize(FontSampler,0))).a;
}
// GENERATED_LABELS
float number(vec2 p,int value) {
    if(p.x<0.0||p.y<0.0||p.y>=16.0) return 0.0;
    bool neg=value<0; int n=abs(value), digits=1, pow10=1;
    for(int i=0;i<8;i++) {if(n>=pow10*10){pow10*=10;digits++;}}
    int place=int(p.x)/9;
    if(neg&&place==0) return glyph(p,10);
    if(neg) {place--;p.x-=9.0;}
    if(place<0||place>=digits) return 0.0;
    for(int i=0;i<9;i++){if(i<place)pow10/=10;}
    int digit=(n/pow10)%10;
    return glyph(vec2(mod(p.x,9.0),p.y),digit);
}
vec4 pigIcon(vec2 p) {
    if(any(lessThan(p,vec2(0)))||any(greaterThanEqual(p,vec2(16)))) return vec4(0);
    vec2 atlasPixel = vec2((PIG_ICON_SLOT%16)*16,(PIG_ICON_SLOT/16)*16)+floor(p)+.5;
    return texture(IconsSampler,atlasPixel/vec2(textureSize(IconsSampler,0)));
}
void main() {
    ivec2 resolution=textureSize(InSampler,0);
    vec2 pixel=vec2(texCoord.x,1.0-texCoord.y)*vec2(resolution);
    // Cover the private transport strip by extending its neighbouring scene row.
    vec2 sceneUV=texCoord;
    if(gl_FragCoord.y<1.0&&gl_FragCoord.x<120.0) sceneUV.y=1.5/float(resolution.y);
    fragColor=texture(InSampler,sceneUV);
    if(nibble(0)!=13) return;
    int flags=nibble(1);
    bool rays=(flags&2)!=0, map=(flags&1)!=0;
    if(rays) fragColor=texture(RaysSampler,texCoord);
    if(!map) return;
    float side=clamp(float(resolution.y)*0.28,128.0,288.0);
    side=min(side,float(resolution.y)-160.0);
    if(side<64.0) return;
    vec2 corner=vec2(float(resolution.x)-side-24.0,52.0);
    vec2 local=pixel-corner;
    if(local.x< -8.0||local.x>=side+8.0||local.y< -30.0||local.y>=side+120.0) return;
    vec3 background=vec3(0.035,0.065,0.10);
    vec3 ink=vec3(0.81,0.92,0.95);
    vec3 color=background;
    vec2 angles=unpackOrientation(texelFetch(OrientationSampler,ivec2(0,0),0));
    float yaw=angles.x*6.2831853;
    float rotation=(flags&4)!=0?3.14159265-yaw:0.0;
    float radius=32.0*exp2(float(nibble(2)));
    vec2 wp=vec2(absoluteCoord(14)-CameraBlockPos.x,absoluteCoord(22)-CameraBlockPos.z)-CameraOffset.xz;
    bool hasWaypoint=nibble(30)==1;
    if(local.y>=0.0&&local.y<side&&local.x>=0.0&&local.x<side) {
        vec2 delta=rotate2((local-vec2(side*0.5))*2.0*radius/side,-rotation);
        vec2 precise=CameraOffset.xz+delta;
        ivec2 world=CameraBlockPos.xz+ivec2(floor(precise));
        uint seed=field(10,4);
        int height=terrainHeight(world,seed);
        color=terrainColor(height);
        color*=clamp(0.94+float(height-terrainHeight(world+ivec2(-1,-1),seed))*0.06,0.68,1.15);
#ifdef CLASSIC_MAP
        color=vec3(0.094,0.137,0.176);
#endif
        if((flags&8)!=0&&HasAtlas==1) {
            vec2 relative=vec2(CameraBlockPos.xz-ivec2(OriginX,OriginZ))+precise;
            vec2 uv=relative/(vec2(textureSize(AtlasSampler,0))*Step);
            color=all(greaterThanEqual(uv,vec2(0.0)))&&all(lessThan(uv,vec2(1.0)))?texture(AtlasSampler,uv).rgb:vec3(0.094,0.137,0.176);
        }
        vec2 anchor=vec2(absoluteCoord(32)-CameraBlockPos.x,absoluteCoord(40)-CameraBlockPos.z)-CameraOffset.xz;
        for(int i=0;i<12;i++) {
            if(i>=nibble(3)) break;
            vec2 pig=anchor+(vec2(float(field(48+i*6,3)),float(field(51+i*6,3)))-2048.0)/8.0;
            vec2 p=local-vec2(side*0.5)-rotate2(pig,rotation)*side/(2.0*radius);
            vec4 face=pigIcon(p+vec2(8));
            color=mix(color,face.rgb,face.a);
        }
        if(hasWaypoint) {
            vec2 location=rotate2(wp,rotation)*side/(2.0*radius);
            float limit=side*0.5-10.0;
            bool outside=max(abs(location.x),abs(location.y))>limit;
            vec2 pin=outside?location*limit/max(abs(location.x),abs(location.y)):location;
            vec2 p=local-vec2(side*0.5)-pin;
            if(outside?arrowDistance(p,normalize(location),7.0)<1.5:abs(length(p)-5.0)<1.5) color=vec3(1.0,0.81,0.24);
        }
        vec2 playerDirection=rotate2(vec2(-sin(yaw),cos(yaw)),rotation);
        if(arrowDistance(local-vec2(side*0.5),playerDirection,9.0)<1.6) color=vec3(1.0,0.93,0.68);
        float n=word(local-vec2(side*0.5-8.0,3.0),10);
        // Cardinal labels are fixed only in north-up mode.
        if((flags&4)==0) color=mix(color,ink,n);
        if(local.x<1.5||local.x>side-1.5||local.y<1.5||local.y>side-1.5) color=vec3(0.27,0.67,0.68);
    } else {
        float textAlpha=0.0;
        if(local.y<0.0) textAlpha=word(local-vec2(0.0,-25.0),rays?1:0);
#ifdef CLASSIC_MAP
        if(local.y<0.0) {
            textAlpha=word(local-vec2(0,-25),(flags&8)!=0?19:18);
            textAlpha=max(textAlpha,number(local-vec2(side-50,-25),int(radius)));
            textAlpha=max(textAlpha,word(local-vec2(side-16,-25),9));
        }
#endif
        float y=local.y-side-6.0;
        textAlpha=max(textAlpha,word(vec2(local.x,y),2));
        textAlpha=max(textAlpha,number(vec2(local.x-54.0,y),CameraBlockPos.x));
        textAlpha=max(textAlpha,word(vec2(local.x,y-18.0),3));
        textAlpha=max(textAlpha,number(vec2(local.x-38.0,y-18.0),CameraBlockPos.y));
        textAlpha=max(textAlpha,word(vec2(local.x-86.0,y-18.0),5));
        textAlpha=max(textAlpha,number(vec2(local.x-122.0,y-18.0),int(round(angles.x*360.0))%360));
        textAlpha=max(textAlpha,word(vec2(local.x,y-36.0),4));
        textAlpha=max(textAlpha,number(vec2(local.x-54.0,y-36.0),CameraBlockPos.z));
#ifndef CLASSIC_MAP
        textAlpha=max(textAlpha,word(vec2(local.x,y-54.0),6));
        textAlpha=max(textAlpha,number(vec2(local.x-38.0,y-54.0),int(field(10,4))));
#endif
        textAlpha=max(textAlpha,word(vec2(local.x-92.0,y-54.0),7));
        textAlpha=max(textAlpha,number(vec2(local.x-112.0,y-54.0),nibble(3)));
        if(hasWaypoint) {
            textAlpha=max(textAlpha,word(vec2(local.x,y-76.0),8));
            textAlpha=max(textAlpha,number(vec2(local.x-38.0,y-76.0),int(length(wp))));
            int distance=int(length(wp));
            float digits=1.0+floor(log2(float(max(1,distance)))/log2(10.0));
            textAlpha=max(textAlpha,word(vec2(local.x-42.0-digits*9.0,y-76.0),9));
        } else textAlpha=max(textAlpha,word(vec2(local.x,y-76.0),14));
#ifdef CLASSIC_MAP
        textAlpha=max(textAlpha,word(vec2(local.x,y-94.0),(flags&8)!=0?17:18));
#else
        textAlpha=max(textAlpha,word(vec2(local.x,y-94.0),(flags&8)!=0?17:16));
#endif
        color=mix(color,ink,textAlpha);
    }
    fragColor=vec4(color,1.0);
}
