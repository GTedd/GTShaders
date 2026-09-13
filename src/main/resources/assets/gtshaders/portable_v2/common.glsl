#ifndef GTMINIMAP_COMMON
#define GTMINIMAP_COMMON
#include <minecraft:globals.glsl>
// InSampler is the shared main target, AFTER all sparse nibble writers.
int nibble(int slot) { return int(round(texelFetch(InSampler, ivec2(slot, 0), 0).r * 15.0)); }
uint field(int start, int count) {
    uint result = 0u;
    for (int i = 0; i < count; i++) result |= uint(nibble(start + i)) << uint(i * 4);
    return result;
}
int absoluteCoord(int start) { return int(field(start, 8)) - 30000000; }
vec2 rotate2(vec2 p, float angle) { float c = cos(angle), s = sin(angle); return vec2(c*p.x-s*p.y,s*p.x+c*p.y); }
float lineDistance(vec2 p, vec2 a, vec2 b) {
    vec2 d = b-a;
    return length(p-a-d*clamp(dot(p-a,d)/max(dot(d,d),0.001),0.0,1.0));
}
float arrowDistance(vec2 p, vec2 dir, float size) {
    vec2 tip = dir * size, side = vec2(-dir.y,dir.x);
    return min(lineDistance(p,-dir*size*0.45,tip), min(lineDistance(p,tip,dir*size*0.15+side*size*0.45),lineDistance(p,tip,dir*size*0.15-side*size*0.45)));
}
uint hash2(ivec2 p, uint seed) {
    uint v = uint(p.x)*1597334677u ^ uint(p.y)*3812015801u ^ (seed+11u)*1103515245u;
    v ^= v >> 16u; v *= 2246822519u; v ^= v >> 13u; v *= 3266489917u; return v ^ (v >> 16u);
}
float random2(ivec2 p, uint seed) { return float(hash2(p,seed) & 65535u) / 65535.0; }
float noise2(ivec2 p, int scale, uint seed) {
    ivec2 rem = (p % scale + scale) % scale;
    ivec2 base = (p-rem)/scale;
    vec2 f = vec2(rem)/float(scale); f = f*f*(3.0-2.0*f);
    return mix(mix(random2(base,seed),random2(base+ivec2(1,0),seed),f.x),
               mix(random2(base+ivec2(0,1),seed),random2(base+ivec2(1,1),seed),f.x),f.y);
}
int terrainHeight(ivec2 p, uint seed) { return int(floor(18.0+noise2(p,48,seed)*32.0+noise2(p,12,seed+101u)*9.0)); }
vec3 terrainColor(int height) {
    if(height<32) return vec3(0.12,0.39,0.68);
    if(height<35) return vec3(0.82,0.76,0.48);
    if(height>52) return vec3(0.56,0.61,0.58);
    return mix(vec3(0.20,0.40,0.23),vec3(0.38,0.59,0.27),clamp(float(height-35)/17.0,0.0,1.0));
}
vec2 unpackOrientation(vec4 encodedColor) {
    vec4 bytes = floor(encodedColor*255.0+0.5);
    return vec2(bytes.x*256.0+bytes.y,bytes.z*256.0+bytes.w)/65535.0;
}
vec4 packOrientation(vec2 value) {
    vec2 v=floor(clamp(value,0.0,1.0)*65535.0+0.5);
    return vec4(floor(v.x/256.0),mod(v.x,256.0),floor(v.y/256.0),mod(v.y,256.0))/255.0;
}
#endif
