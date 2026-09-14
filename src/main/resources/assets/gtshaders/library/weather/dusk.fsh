// 蓝调 / Dusk
// 天空换成上紫下橙的黄昏渐变，
// 场景先去掉一半彩度再压进冷色氛围光，平坦的表面额外提亮一点当作被打湿的反光，
// 远处按真实距离淹进雾色里。
//
// 「哪些面是平的」靠深度的横向梯度判断——同一个平面上相邻像素的深度是线性变化的，
// 而墙角和树叶那里会突变。这是个近似，但在方块世界里意外地准。
//
// [en_us]
// A dusk look with a gradient sky, cool mood light and fog.
// The sky becomes a gradient, purple above and orange below.
// The scene loses half its saturation and is then pushed into a cool ambient light, flat surfaces get a little
// extra brightness as wet reflections, and distant areas sink into the fog color by real distance.
//
// "Which surfaces are flat" is judged from the horizontal gradient of depth: on a single plane, depth changes
// linearly between neighboring pixels, while at wall corners and leaves it jumps. It is an approximation, but
// surprisingly accurate in a block world.
//
// @group 氛围 / Mood
// @param name=MoodIntensity type=float min=0 max=1 default=0.8 zh_cn=氛围强度 en_us=Mood Intensity
// @param name=MoodColor type=color3 default=#96A8DC zh_cn=氛围颜色 en_us=Mood Color
// @param name=Wetness type=float min=0 max=1 default=0.55 zh_cn=地面湿润度 en_us=Wetness

// @group 天空与雾 / Sky & Fog
// @param name=SkyTop type=color3 default=#231652 zh_cn=天空顶部 en_us=Sky Top
// @param name=SkyBottom type=color3 default=#F57052 zh_cn=天空底部 en_us=Sky Bottom
// @param name=FogColor type=color3 default=#524E70 zh_cn=雾颜色 en_us=Fog Color
// @param name=FogDensity type=float min=0 max=0.05 default=0.012 zh_cn=雾浓度 en_us=Fog Density

// @group 作用范围 / Scope
// @param name=SkyOn type=bool default=1 zh_cn=天空着色 en_us=Shade Sky
// @param name=GroundOn type=bool default=1 zh_cn=地面着色 en_us=Shade Ground

void main() {
    vec3 src = texture(InSampler, texCoord).rgb;
    bool sky = gtIsSky(texCoord);
    if ((sky && SkyOn < 0.5) || (!sky && GroundOn < 0.5)) {
        fragColor = vec4(src, 1.0);
        return;
    }

    float gray = dot(src, vec3(0.299, 0.587, 0.114));
    vec3 moody = mix(src, vec3(gray), 0.5) * MoodColor;

    vec3 effect;
    if (sky) {
        effect = mix(SkyBottom, SkyTop, texCoord.y);
    } else {
        vec2 px = 1.0 / max(InDepthSize, vec2(1.0));
        float dl = gtDepth(texCoord - vec2(px.x, 0.0));
        float dr = gtDepth(texCoord + vec2(px.x, 0.0));
        // 除以中心深度：反转深度是非线性的，同样的坡度在近处给出的差值大得多，
        // 不归一化的话只有贴脸的地面会被认成平面
        float slope = abs(dl - dr) / max(gtDepth(texCoord), 1e-4);
        float flatness = 1.0 - smoothstep(0.0, 0.02, slope);

        vec3 ground = mix(moody, moody * 1.3, flatness * Wetness);
        effect = mix(FogColor, ground, clamp(exp(-gtDistance(texCoord) * FogDensity), 0.0, 1.0));
    }

    float vignette = smoothstep(1.0, 0.3, length(texCoord - 0.5));
    fragColor = vec4(mix(src, effect * vignette, MoodIntensity), 1.0);
}
