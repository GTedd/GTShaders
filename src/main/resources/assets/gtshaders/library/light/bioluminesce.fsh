// 生物荧光 / Bioluminescence
// 幽蓝的光点在画面上缓慢明灭、成群漂移，暗部被染上一层深青，像深海或发光菌林。
// 明灭的节奏靠每一点各自的相位：整齐一起亮会读成"闪烁的灯串"，各自为政才像活的。
//
// 只在<b>暗部</b>发亮——发光生物在亮处本来就看不见，这一条让效果自动融进场景，
// 而不是浮在画面表面。
//
// [en_us]
// Blue specks of light that pulse and drift in the dark.
// Faint blue dots slowly fade in and out and drift in swarms across the picture, and the shadows take on a
// deep teal tint, like the deep sea or a forest of glowing fungi. Each dot pulses on its own phase: if they
// all lit up together it would read as "a string of blinking lights"; only dots that keep their own rhythm
// look alive.
//
// It glows only in <b>dark areas</b>. Glowing creatures can't be seen in bright light anyway, and this rule
// lets the effect blend into the scene instead of floating on the surface of the picture.
//
// @param name=Density type=float min=10 max=200 default=64 zh_cn=光点密度 en_us=Point Density
// @param name=Drift type=float min=0 max=1 default=0.12 zh_cn=漂移速度 en_us=Drift Speed
// @param name=BlinkRate type=float min=0 max=3 default=0.5 zh_cn=明灭速度 en_us=Blink Rate
// @param name=Size type=float min=0.05 max=1 default=0.35 zh_cn=光点大小 en_us=Point Size

// @group 外观 / Look
// @param name=GlowColor type=color3 default=#48F0D0 zh_cn=荧光色 en_us=Glow Color
// @param name=DeepColor type=color3 default=#062634 zh_cn=深处色 en_us=Deep Tint
// @param name=Gain type=float min=0 max=5 default=2.2 zh_cn=亮度 en_us=Gain
// @param name=DarkOnly type=float min=0 max=1 default=0.7 zh_cn=只在暗处发光 en_us=Dark Areas Only

// @group 环境 / Ambient
// @param name=Dim type=float min=0 max=1 default=0.4 zh_cn=场景压暗 en_us=Scene Dim
// @param name=Desaturate type=float min=0 max=1 default=0.5 zh_cn=场景失色 en_us=Scene Desaturate

float hash21(vec2 p) {
    return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453);
}

void main() {
    vec2 asp = vec2(OutSize.x / max(OutSize.y, 1.0), 1.0);
    vec3 src = texture(InSampler, texCoord).rgb;

    float g = dot(src, vec3(0.2126, 0.7152, 0.0722));
    vec3 col = mix(src, vec3(g), Desaturate) * (1.0 - Dim);
    col = mix(col, col * 0.4 + DeepColor, 0.55);

    vec2 grid = vec2(Density, Density * OutSize.y / max(OutSize.x, 1.0));
    // 整片缓慢漂移，像水流在带着它们走
    vec2 flow = vec2(sin(GTTime * Drift * 0.7) * 0.3, GTTime * Drift * 0.15);
    vec2 gp = texCoord * grid + flow * grid;

    float acc = 0.0;
    for (int dy = -1; dy <= 1; dy++) {
        for (int dx = -1; dx <= 1; dx++) {
            vec2 cellId = floor(gp) + vec2(float(dx), float(dy));
            float seed = hash21(cellId);
            if (seed < 0.8) {
                continue;
            }
            // 每一点自己的相位与频率：整齐一起亮会读成灯串
            float phase = hash21(cellId + 5.3) * 6.2831853;
            float rate = mix(0.4, 1.8, hash21(cellId + 11.7));
            float blink = pow(max(sin(GTTime * BlinkRate * rate + phase), 0.0), 3.0);

            vec2 jitter = vec2(hash21(cellId + 2.1), hash21(cellId + 7.9));
            vec2 pos = cellId + 0.2 + jitter * 0.6;
            vec2 diff = (gp - pos) / grid * asp;
            float rad = Size * 0.01 * (0.6 + blink * 0.6);
            acc += exp(-dot(diff, diff) / max(rad * rad, 1e-8)) * blink;
        }
    }

    // 只在暗处发光：亮处本来就看不见发光生物
    float mask = mix(1.0, 1.0 - smoothstep(0.15, 0.6, g), DarkOnly);
    col += GlowColor * acc * Gain * mask;

    fragColor = vec4(col, 1.0);
}
