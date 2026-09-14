// 寂静岭表里 / Silent Hill
// 一个开关切两个世界：
// 表世界是灰白浓雾、轻微冷调、标准噪点；里世界是铁锈红、剧烈噪点、亮度压到四成。
// 雾按真实距离算，所以是「走近才看得清」，不是屏幕四周糊一圈。
//
// [en_us]
// Silent Hill fog, with a switch to the rust-red Otherworld.
// One switch flips between two worlds: the Fog World is thick grey-white fog, a slightly cool tone and
// standard grain; the Otherworld is rust red, heavy grain and brightness cut to 40%. The fog is computed from
// real distance, so things only become clear "as you get closer", rather than a blurry ring around the
// screen edges.
//
// @group 世界 / World
// @param name=Otherworld type=bool default=0 zh_cn=里世界 en_us=Otherworld desc_zh_cn=切成铁锈红的里世界 desc_en_us=Switches to the rust-red Otherworld
// @param name=FogDensity type=float min=0 max=0.1 default=0.025 zh_cn=雾浓度 en_us=Fog Density
// @param name=GrainIntensity type=float min=0 max=0.5 default=0.08 zh_cn=颗粒强度 en_us=Grain

// @group 侵蚀 / Reveal
// @param name=Reveal type=bool default=0 zh_cn=播放侵蚀 en_us=Play Reveal desc_zh_cn=关掉就是侵蚀完成后的稳定态 desc_en_us=When off, shows the settled state after the reveal has finished
// @param name=RevealSpeed type=float min=0.1 max=5 default=1 zh_cn=侵蚀速度 en_us=Reveal Speed
// @param name=RevealCycle type=float min=1 max=20 default=6 zh_cn=循环周期 en_us=Reveal Cycle

// @group 作用范围 / Scope
// @param name=SkyOn type=bool default=1 zh_cn=天空着色 en_us=Shade Sky
// @param name=GroundOn type=bool default=1 zh_cn=地面着色 en_us=Shade Ground

float noise(vec2 p) {
    return fract(sin(dot(p, vec2(12.9898, 78.233))) * 43758.5453);
}

void main() {
    vec3 src = texture(InSampler, texCoord).rgb;
    bool sky = gtIsSky(texCoord);
    if ((sky && SkyOn < 0.5) || (!sky && GroundOn < 0.5)) {
        fragColor = vec4(src, 1.0);
        return;
    }

    vec3 fogColor;
    vec3 tint;
    float grainMult;
    float brightness;
    if (Otherworld < 0.5) {
        fogColor = vec3(0.55, 0.55, 0.58);
        tint = vec3(0.9, 0.9, 0.95);
        grainMult = 1.0;
        brightness = 0.8;
    } else {
        fogColor = vec3(0.15, 0.05, 0.02);
        tint = vec3(0.6, 0.2, 0.1);
        grainMult = 2.5;
        brightness = 0.4;
    }

    // 先去掉大部分彩度再往铁锈色推。保留四成原色是关键——
    // 全灰会像黑白滤镜，而这两个世界要的是「颜色被抽走了但没抽干净」
    vec3 gray = vec3(dot(src, vec3(0.299, 0.587, 0.114)));
    vec3 base = mix(src, gray * tint, 0.6) * brightness;

    // 天空当成一百格外，于是它总是被雾吃满，正好是那种看不到天的压抑感
    float dist = sky ? 100.0 : gtDistance(texCoord);
    float fog = exp(-dist * FogDensity);

    float grain = (noise(texCoord + GTTime * 0.1) - 0.5) * GrainIntensity * grainMult;
    vec3 styled = mix(fogColor, base, fog) + grain;

    // 侵蚀：半径是时间的四次方。关掉时给一个大到覆盖任何视距的常量
    float radius = 1.0e6;
    if (Reveal > 0.5) {
        radius = pow(mod(GTTime * RevealSpeed, RevealCycle), 4.0);
    }
    // 边界叠一层噪声，侵蚀线才不是一个几何圆，而是像在啃
    float ragged = noise(texCoord * 4.0 + GTTime * 0.1) * 3.0;
    float inside = 1.0 - smoothstep(radius, radius + 12.0, dist + ragged);
    float edge = smoothstep(radius, radius + 4.0, dist + ragged)
               * (1.0 - smoothstep(radius + 2.0, radius + 6.0, dist + ragged));
    vec3 edgeColor = Otherworld < 0.5 ? vec3(0.1) : vec3(0.25, 0.0, 0.0);

    vec3 col = mix(src, styled, inside) + edgeColor * edge;
    float vignette = smoothstep(0.9, 0.2, length(texCoord - 0.5));
    fragColor = vec4(col * (vignette + 0.1), 1.0);
}
