#version 330
#extension GL_ARB_separate_shader_objects : require
uniform sampler2D InSampler;
uniform sampler2D CharSampler;
uniform sampler2D FontSampler;
uniform sampler2D StateSampler;
layout(std140) uniform LlmView { vec4 View; };
#include <gtllm:common.glsl>
layout(location=0) in vec2 texCoord;
layout(location=0) out vec4 fragColor;

// 把字符网格画到屏幕上。字形来自自带的 8×8 点阵图集——
// 后处理拿不到游戏字体，想显示文字就只能自己带一张图。
void main() {
    vec3 src = texture(InSampler, texCoord).rgb;
    vec2 px = gl_FragCoord.xy;
    float scale = max(View.x, 1.0);
    vec2 origin = View.yz;
    vec2 rel = (px - origin) / (scale * float(GLYPH));
    // 行号从上往下数：屏幕原点在左下，但文字要从顶上开始排
    ivec2 cell = ivec2(floor(rel.x), floor(-rel.y));
    if (cell.x < 0 || cell.x >= CMW || cell.y < 0 || cell.y >= CMH) {
        fragColor = vec4(src, 1.0);
        return;
    }
    int c = texI(CharSampler, ivec2(cell.x, cell.y));
    if (c < FFIRST || c > 126) { fragColor = vec4(src, 1.0); return; }
    vec2 inCell = fract(vec2(rel.x, -rel.y));
    int gi = c - FFIRST;
    ivec2 glyphPx = ivec2(inCell * float(GLYPH));
    ivec2 at = ivec2((gi % FCOLS) * GLYPH, (gi / FCOLS) * GLYPH) + glyphPx;
    float a = texelFetch(FontSampler, at, 0).a;
    vec3 ink = vec3(COLR, COLG, COLB);
    // 描一圈暗边，免得亮字落在亮画面上看不清
    fragColor = vec4(mix(mix(src, src * 0.25, 0.55 * step(0.5, a)), ink, a), 1.0);
}
