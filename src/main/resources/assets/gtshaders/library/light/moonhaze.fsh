// 月晕 / Moon Haze
// 夜色里的一轮月：月盘周围一圈柔和的晕，晕外还有一道更淡的彩虹环（真实的月晕来自冰晶衍射），
// 整幅画面被压成冷蓝的低对比度夜景。
//
// 云在缓慢地从月前飘过，晕的强度随之起伏——静止的晕会显得像贴上去的贴纸。
//
// [en_us]
// A haloed moon over a cold blue night scene.
// A soft halo surrounds the moon's disc, with a fainter rainbow ring outside it (real moon halos come from
// diffraction by ice crystals), and the whole picture is pressed into a cold blue, low-contrast night scene.
//
// Clouds drift slowly across the moon and the halo strength rises and falls with them. A static halo would
// look like a sticker pasted on.
//
// @param name=MoonPos type=vec2 min=0 max=1 default=0.72,0.78 zh_cn=月亮位置 en_us=Moon Position
// @param name=MoonSize type=float min=0.005 max=0.15 default=0.028 zh_cn=月盘大小 en_us=Moon Size
// @param name=HaloSize type=float min=0.05 max=0.8 default=0.26 zh_cn=晕半径 en_us=Halo Radius

// @group 外观 / Look
// @param name=MoonColor type=color3 default=#F2F6FF zh_cn=月色 en_us=Moon Color
// @param name=HaloColor type=color3 default=#9FC0FF zh_cn=晕色 en_us=Halo Color
// @param name=Gain type=float min=0 max=4 default=1.4 zh_cn=亮度 en_us=Gain
// @param name=Rainbow type=float min=0 max=1 default=0.35 zh_cn=彩虹环 en_us=Iridescent Ring

// @group 夜景 / Night Grade
// @param name=NightTint type=color3 default=#5C79C8 zh_cn=夜色 en_us=Night Tint
// @param name=Dim type=float min=0 max=1 default=0.45 zh_cn=压暗 en_us=Dim
// @param name=Desaturate type=float min=0 max=1 default=0.55 zh_cn=失色 en_us=Desaturate

// @group 云 / Clouds
// @param name=CloudSpeed type=float min=0 max=1 default=0.06 zh_cn=云速 en_us=Cloud Speed
// @param name=CloudAmount type=float min=0 max=1 default=0.5 zh_cn=云的遮挡 en_us=Cloud Occlusion

float hash(vec2 p) {
    return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453);
}

float noise(vec2 p) {
    vec2 i = floor(p);
    vec2 f = fract(p);
    f = f * f * (3.0 - 2.0 * f);
    return mix(mix(hash(i), hash(i + vec2(1.0, 0.0)), f.x),
               mix(hash(i + vec2(0.0, 1.0)), hash(i + vec2(1.0, 1.0)), f.x), f.y);
}

float fbm(vec2 p) {
    float v = 0.0;
    float a = 0.5;
    for (int i = 0; i < 4; i++) {
        v += noise(p) * a;
        p *= 2.05;
        a *= 0.5;
    }
    return v;
}

void main() {
    vec2 asp = vec2(OutSize.x / max(OutSize.y, 1.0), 1.0);
    vec3 col = texture(InSampler, texCoord).rgb;

    // 夜景基调
    float g = dot(col, vec3(0.2126, 0.7152, 0.0722));
    col = mix(col, vec3(g), Desaturate) * (1.0 - Dim);
    col *= NightTint * 1.6;

    float d = length((texCoord - MoonPos) * asp);

    // 云：缓慢飘过，遮挡月与晕
    float cloud = fbm(texCoord * asp * 2.4 + vec2(GTTime * CloudSpeed, GTTime * CloudSpeed * 0.3));
    float occl = 1.0 - clamp((cloud - 0.42) * 2.2, 0.0, 1.0) * CloudAmount;

    // 月盘 + 边缘柔光
    float disc = smoothstep(MoonSize, MoonSize * 0.75, d);
    col += MoonColor * disc * Gain * occl;

    // 晕：内浓外淡的一团
    float halo = exp(-pow(d / max(HaloSize, 1e-3), 1.6) * 2.4);
    col += HaloColor * halo * Gain * 0.35 * occl;

    // 彩虹环：晕的外沿，红在外蓝在内
    if (Rainbow > 0.001) {
        float ringPos = d / max(HaloSize, 1e-3);
        float ring = smoothstep(0.35, 0.0, abs(ringPos - 0.82));
        vec3 iri = vec3(smoothstep(0.75, 0.95, ringPos),
                        smoothstep(0.65, 0.85, ringPos) * 0.8,
                        smoothstep(0.55, 0.78, ringPos) * 0.6);
        col += iri * ring * Rainbow * 0.5 * occl;
    }

    fragColor = vec4(col, 1.0);
}
