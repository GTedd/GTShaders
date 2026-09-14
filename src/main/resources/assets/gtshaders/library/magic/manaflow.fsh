// 魔力潮汐 / Mana Flow
// 一层缓慢流动的能量在画面上淌过，亮处顺着流向拉丝，暗处几乎不受影响。
// 流场用域扭曲的 fbm 算：噪声推着噪声走，得到的纹路会自己打旋，比平移一张噪声图耐看得多。
//
// 只影响亮部是刻意的：全屏均匀叠一层会把画面糊成一片，附在高光上才像"能量顺着物体流"。
//
// [en_us]
// Slow-flowing magical energy streaming over highlights.
// A layer of slowly flowing energy washes over the picture: bright areas are drawn into streaks along the
// flow while dark areas are barely touched. The flow field is computed with domain-warped fbm: noise pushes
// noise around, so the pattern swirls on its own and holds up far better than a scrolling noise texture.
//
// Affecting only the highlights is deliberate: a uniform full-screen layer would smear the picture into
// mush, while clinging to highlights makes it look like "energy flowing along objects".
//
// @param name=Speed type=float min=0 max=3 default=0.35 zh_cn=流动速度 en_us=Flow Speed
// @param name=Scale type=float min=1 max=20 default=4 zh_cn=纹理尺度 en_us=Scale
// @param name=Warp type=float min=0 max=4 default=1.6 zh_cn=打旋强度 en_us=Curl
// @param name=Threshold type=float min=0 max=1 default=0.35 zh_cn=起效亮度 en_us=Luma Threshold

// @group 颜色 / Color
// @param name=ColorA type=color3 default=#4A7BFF zh_cn=能量色 A en_us=Mana A
// @param name=ColorB type=color3 default=#B14AFF zh_cn=能量色 B en_us=Mana B
// @param name=Gain type=float min=0 max=4 default=1.3 zh_cn=亮度 en_us=Gain
// @param name=Filament type=float min=0 max=1 default=0.6 zh_cn=丝状锐度 en_us=Filament

// @group 位移 / Displace
// @param name=Drag type=float min=0 max=0.05 default=0.008 zh_cn=画面拖拽 en_us=Image Drag

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
    for (int i = 0; i < 5; i++) {
        v += noise(p) * a;
        p *= 2.09;
        a *= 0.5;
    }
    return v;
}

void main() {
    vec2 asp = vec2(OutSize.x / max(OutSize.y, 1.0), 1.0);
    vec2 p = texCoord * asp * Scale;
    float t = GTTime * Speed;

    vec2 q = vec2(fbm(p + vec2(0.0, t)), fbm(p + vec2(3.4, -t * 0.8)));
    vec2 rr = vec2(fbm(p + q * Warp + vec2(1.7, 9.2) + t * 0.3),
                   fbm(p + q * Warp + vec2(8.3, 2.8) - t * 0.2));
    float f = fbm(p + rr * Warp);

    // 丝状：把中段值压细成一条条亮带
    float fil = pow(clamp(1.0 - abs(f - 0.5) * 2.4, 0.0, 1.0), mix(1.0, 6.0, Filament));

    vec2 uv = clamp(texCoord + (rr - 0.5) * Drag, vec2(0.0), vec2(1.0));
    vec3 src = texture(InSampler, uv).rgb;
    float g = dot(src, vec3(0.2126, 0.7152, 0.0722));

    // 只在亮部起效：全屏均匀叠会把画面糊掉
    float mask = smoothstep(Threshold, min(Threshold + 0.35, 1.0), g);
    vec3 mana = mix(ColorA, ColorB, clamp(f * 1.4, 0.0, 1.0));

    fragColor = vec4(src + mana * fil * mask * Gain, 1.0);
}
