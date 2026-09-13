#version 330
#extension GL_ARB_separate_shader_objects : require

// Deliberately vanilla-only: the exported pack copies this file verbatim.
// Do not introduce GTShaders uniforms, dynamic textures or client callbacks here.
#include <minecraft:globals.glsl>
uniform sampler2D InSampler;
uniform sampler2D AtlasSampler;
layout(std140) uniform AtlasConfig {
    int OriginX;
    int OriginZ;
    vec2 AtlasSize;
    float BlocksPerPixel;
    float Radius;
};
layout(location = 0) in vec2 texCoord;
layout(location = 0) out vec4 fragColor;

void main() {
    fragColor = texture(InSampler, texCoord);
    vec2 screen = vec2(textureSize(InSampler, 0));
    // Vanilla screenquad: texCoord.y=0 is the screen's lower edge.
    vec2 pixel = vec2(texCoord.x, 1.0 - texCoord.y) * screen;
    float size = clamp(screen.y * 0.26, 96.0, 256.0);
    size = min(size, min(screen.x, screen.y) - 40.0);
    vec2 center = vec2(screen.x - 20.0 - size * 0.5, 28.0 + size * 0.5);
    vec2 local = pixel - center;
    float edge = max(abs(local.x), abs(local.y));
    if (edge > size * 0.5 + 4.0) return;
    if (edge > size * 0.5) {
        fragColor = vec4(0.32, 0.65, 0.70, 1.0);
        return;
    }
    // Subtract integers FIRST: casting absolute world coordinates to float loses
    // sub-block movement near the world border. CameraOffset is per render frame.
    vec2 relative = vec2(CameraBlockPos.xz - ivec2(OriginX, OriginZ)) + CameraOffset.xz;
    vec2 uv = (relative + local * (2.0 * Radius / size)) / (AtlasSize * BlocksPerPixel);
    vec3 terrain = vec3(0.094, 0.137, 0.176);
    if (all(greaterThanEqual(uv, vec2(0.0))) && all(lessThan(uv, vec2(1.0))))
        terrain = texture(AtlasSampler, uv).rgb;
    // North indicator on the frame; fixed camera marker means no server yaw traffic.
    bool north = abs(local.x) < 2.0 && local.y < -size * 0.5 + 8.0;
    bool cross = (abs(local.x) < 1.3 && abs(local.y) < 5.0) || (abs(local.y) < 1.3 && abs(local.x) < 5.0);
    fragColor = vec4(cross ? vec3(1.0, 0.88, 0.48) : north ? vec3(0.85, 0.97, 1.0) : terrain, 1.0);
}
