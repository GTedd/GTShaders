// 舞台聚光 / Stage Spotlight
// 一束（或几束）聚光在画面上扫来扫去，光锥内有可见的光轴，锥外压成黑。
// 每束灯有自己的相位和摆幅，所以永远不会整齐地同步——同步的聚光看起来像 UI 而不像灯。
//
// [en_us]
// Stage spotlights sweeping across a darkened screen.
// One (or several) spotlights sweep back and forth across the picture, with a visible beam inside each cone
// and everything outside pushed to black. Each light has its own phase and swing, so they never fall into
// neat sync. Synchronized spotlights look like UI, not like lights.
//
// @param name=Lights type=float min=1 max=4 default=2 zh_cn=灯数 en_us=Light Count
// @param name=Radius type=float min=0.05 max=0.8 default=0.22 zh_cn=光斑半径 en_us=Spot Radius
// @param name=Softness type=float min=0.02 max=1 default=0.35 zh_cn=边缘柔和 en_us=Edge Softness
// @param name=Speed type=float min=0 max=3 default=0.4 zh_cn=扫动速度 en_us=Sweep Speed
// @param name=SweepWidth type=float min=0 max=0.6 default=0.3 zh_cn=扫动幅度 en_us=Sweep Width

// @group 外观 / Look
// @param name=LightColor type=color3 default=#FFF2D0 zh_cn=灯色 en_us=Light Color
// @param name=Gain type=float min=0 max=4 default=1.5 zh_cn=亮度 en_us=Gain
// @param name=Darkness type=float min=0 max=1 default=0.8 zh_cn=锥外压暗 en_us=Outside Darkness
// @param name=Shaft type=float min=0 max=1 default=0.45 zh_cn=光轴可见度 en_us=Visible Shaft
// @param name=Haze type=float min=0 max=1 default=0.3 zh_cn=空气尘埃 en_us=Atmospheric Haze

float hash11(float p) {
    return fract(sin(p * 27.13) * 31917.31);
}

void main() {
    vec2 asp = vec2(OutSize.x / max(OutSize.y, 1.0), 1.0);
    vec3 src = texture(InSampler, texCoord).rgb;

    float n = max(floor(Lights), 1.0);
    float lit = 0.0;
    float shaft = 0.0;

    for (int i = 0; i < 4; i++) {
        if (float(i) >= n) {
            break;
        }
        float k = float(i);
        float phase = hash11(k * 3.7) * 6.2831853;
        float speed = mix(0.6, 1.5, hash11(k * 8.1));
        // 灯挂在画面上方，光斑在下方扫
        vec2 head = vec2(0.5 + (k - (n - 1.0) * 0.5) / max(n, 1.0) * 0.6, 1.05);
        vec2 spot = vec2(0.5 + sin(GTTime * Speed * speed + phase) * SweepWidth, 0.18);

        float d = length((texCoord - spot) * asp);
        lit += smoothstep(Radius, Radius * (1.0 - Softness), d);

        // 光轴：从灯头到光斑那条线段的距离场，越靠上越细
        vec2 a = (head - spot) * asp;
        vec2 p = (texCoord - spot) * asp;
        float h = clamp(dot(p, a) / max(dot(a, a), 1e-5), 0.0, 1.0);
        float dist = length(p - a * h);
        float width = mix(Radius, Radius * 0.25, h);
        shaft += smoothstep(width, 0.0, dist) * (1.0 - h * 0.6);
    }

    lit = clamp(lit, 0.0, 1.0);
    vec3 col = src * mix(1.0 - Darkness, 1.0, lit);
    col += LightColor * lit * Gain * 0.35;
    col += LightColor * clamp(shaft, 0.0, 1.0) * Shaft * Gain * 0.25;

    // 空气尘埃：让光轴里有颗粒感，慢慢往上飘
    if (Haze > 0.001) {
        float dust = fract(sin(dot(floor(texCoord * OutSize * 0.25), vec2(12.9898, 78.233))) * 43758.5453);
        dust = step(0.985, fract(dust + GTTime * 0.05));
        col += LightColor * dust * clamp(shaft, 0.0, 1.0) * Haze;
    }

    fragColor = vec4(col, 1.0);
}
