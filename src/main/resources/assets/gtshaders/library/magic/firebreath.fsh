// 烈焰吐息 / Fire Breath
// 火从画面下缘（或指定方向）舔上来，越往上越碎、越冷，最后化成余烬散掉。
// 火焰用两层不同速度的噪声相减得到——单层噪声只会得到一团抖动的云，减出来的才有舌状的边。
//
// 火焰前方还有一层热扭曲：把画面本身按火的强度做位移，比单纯叠一层橙色可信得多。
//
// [en_us]
// Flames licking up from the bottom edge of the screen.
// Fire licks up from the bottom edge (or a chosen direction), getting more broken and cooler as it rises,
// until it scatters into embers. The flames come from subtracting two noise layers moving at different
// speeds: a single noise layer only gives a jittering cloud, while the difference has tongue-shaped edges.
//
// In front of the flames there is also a layer of heat distortion: the picture itself is displaced by the
// fire's intensity, which is far more convincing than just overlaying orange.
//
// @param name=Height type=float min=0 max=1.5 default=0.55 zh_cn=火焰高度 en_us=Flame Height
// @param name=Speed type=float min=0 max=6 default=1.8 zh_cn=上窜速度 en_us=Rise Speed
// @param name=Scale type=float min=1 max=30 default=6 zh_cn=火焰密度 en_us=Flame Scale
// @param name=FromTop type=bool default=0 zh_cn=改为自上而下 en_us=From Top

// @group 颜色 / Color
// @param name=CoreColor type=color3 default=#FFF0A0 zh_cn=焰心色 en_us=Core Color
// @param name=MidColor type=color3 default=#FF7A18 zh_cn=中段色 en_us=Mid Color
// @param name=TipColor type=color3 default=#8C1400 zh_cn=焰尖色 en_us=Tip Color
// @param name=Gain type=float min=0 max=4 default=1.8 zh_cn=整体亮度 en_us=Gain

// @group 扭曲 / Heat
// @param name=Distort type=float min=0 max=0.1 default=0.02 zh_cn=热扭曲 en_us=Heat Distortion
// @param name=Ember type=float min=0 max=1 default=0.5 zh_cn=余烬 en_us=Embers

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
        p *= 2.11;
        a *= 0.5;
    }
    return v;
}

void main() {
    vec2 p = texCoord;
    if (FromTop > 0.5) {
        p.y = 1.0 - p.y;
    }

    float rise = GTTime * Speed;
    // 两层速度不同的噪声相减：单层只有云，减出来才有舌状的火焰边缘
    float n1 = fbm(vec2(p.x * Scale, p.y * Scale * 0.6 - rise));
    float n2 = fbm(vec2(p.x * Scale * 1.9 + 4.7, p.y * Scale * 1.1 - rise * 1.7));
    float flame = n1 * 1.25 - n2 * 0.55;

    // 高度衰减：越往上火越难维持
    float h = clamp(1.0 - p.y / max(Height, 1e-3), 0.0, 1.0);
    float intensity = clamp(flame * h * 2.0 - 0.25, 0.0, 1.0);

    // 热扭曲：按火的强度横向推画面，纵向推得少一点（热气是往上走的）
    vec2 uv = texCoord + vec2((n1 - 0.5) * 2.0, (n2 - 0.5)) * Distort * intensity;
    vec3 col = texture(InSampler, clamp(uv, vec2(0.0), vec2(1.0))).rgb;

    // 三段配色：焰心最亮最白，往外过渡到暗红
    vec3 fire = mix(TipColor, MidColor, smoothstep(0.05, 0.5, intensity));
    fire = mix(fire, CoreColor, smoothstep(0.55, 0.95, intensity));
    col += fire * intensity * Gain;

    // 余烬：稀疏的亮点往上飘，寿命随机
    if (Ember > 0.001) {
        vec2 ep = vec2(p.x * 40.0, p.y * 22.0 - rise * 0.8);
        vec2 ei = floor(ep);
        float e = hash(ei);
        float spark = step(0.985, e) * smoothstep(0.5, 0.0, length(fract(ep) - 0.5));
        col += MidColor * spark * Ember * 2.0 * h;
    }

    fragColor = vec4(col, 1.0);
}
