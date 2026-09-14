// 极光天幕 / Aurora Sky
//
// 在天空上叠几道竖直光幕。和后处理版的极光比，这一版画在<b>天空几何</b>上，
// 所以它会被地形和建筑正确遮挡——后处理那版是糊在整张画面上的，会盖住山。
// 这就是"该用核心着色器还是后处理"最直观的一个判断依据。
//
// [en_us]
// Layers a few vertical light curtains across the sky. Compared with the post-processing aurora, this one is
// drawn on the <b>sky geometry</b>, so terrain and buildings occlude it correctly. The post-processing version
// is smeared over the whole screen and covers the mountains.
// This is the most intuitive way to decide between a core shader and post-processing.
//
// @param name=LowColor type=color3 default=#3BFF9E zh_cn=低空色 en_us=Low Color
// @param name=HighColor type=color3 default=#B44BFF zh_cn=高空色 en_us=High Color
// @param name=Intensity type=float min=0 max=2 default=0.7 zh_cn=强度 en_us=Intensity
// @param name=Curtains type=float min=1 max=8 default=3 zh_cn=光幕层数 en_us=Curtains
// @param name=Speed type=float min=0 max=3 default=0.5 zh_cn=飘动速度 en_us=Drift Speed
// @param name=NightOnly type=float min=0 max=1 default=0.8 zh_cn=只在夜晚 en_us=Night Only

float hash1(float x) {
    return fract(sin(x * 127.1) * 43758.5453);
}

float noise1(float x) {
    float i = floor(x);
    float f = fract(x);
    f = f * f * (3.0 - 2.0 * f);
    return mix(hash1(i), hash1(i + 1.0), f);
}

vec4 gtFragment(vec4 color) {
    vec2 uv = gl_FragCoord.xy / max(ScreenSize, vec2(1.0));
    float t = GameTime * 24000.0 * Speed * 0.01;

    float aurora = 0.0;
    for (int i = 0; i < 8; i++) {
        if (float(i) >= Curtains) {
            break;
        }
        float fi = float(i);
        // 每层一条竖直亮带，横向位置由一维噪声缓慢游走
        float center = 0.2 + 0.6 * hash1(fi * 3.7) + (noise1(t + fi * 5.0) - 0.5) * 0.3;
        float band = smoothstep(0.06, 0.0, abs(uv.x - center));
        band *= 0.5 + 0.5 * noise1(uv.y * 6.0 + t * 2.0 + fi * 9.0);
        aurora += band;
    }

    // 只在天空上半部分，且底部淡出——极光不接地平线
    aurora *= smoothstep(0.35, 0.8, uv.y);

    // 白天不该有极光：原版的天空色在白天很亮，拿它当"是不是夜里"的判据
    float brightness = dot(color.rgb, vec3(0.2126, 0.7152, 0.0722));
    aurora *= mix(1.0, smoothstep(0.5, 0.1, brightness), NightOnly);

    vec3 tint = mix(LowColor, HighColor, clamp(uv.y, 0.0, 1.0));
    return vec4(color.rgb + tint * clamp(aurora, 0.0, 2.0) * Intensity, color.a);
}
