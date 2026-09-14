// 血雾 / Blood Mist
// 一层暗红的雾在画面上翻涌，边缘更浓，偶尔有几道血痕顺着屏幕往下淌。
// 雾用两层不同速度的 fbm 叠加，往相反方向漂——同向漂的两层只会得到一层更粗的噪声。
//
// [en_us]
// A churning dark red mist with dripping blood streaks.
// A dark red mist churns over the picture, thicker at the edges, and now and then a few streaks of blood run
// down the screen. The mist stacks two fbm layers moving at different speeds and drifting in opposite
// directions. Two layers drifting the same way would only give one coarser noise.
//
// @param name=Density type=float min=0 max=1.5 default=0.55 zh_cn=雾浓度 en_us=Density
// @param name=Speed type=float min=0 max=2 default=0.25 zh_cn=翻涌速度 en_us=Churn Speed
// @param name=Scale type=float min=0.5 max=12 default=2.6 zh_cn=雾尺度 en_us=Fog Scale
// @param name=EdgeBias type=float min=0 max=1 default=0.6 zh_cn=边缘更浓 en_us=Edge Bias

// @group 血痕 / Runs
// @param name=Runs type=float min=0 max=1 default=0.45 zh_cn=血痕数量 en_us=Run Amount
// @param name=RunSpeed type=float min=0 max=2 default=0.25 zh_cn=下淌速度 en_us=Run Speed
// @param name=RunWidth type=float min=0.002 max=0.05 default=0.01 zh_cn=血痕宽度 en_us=Run Width

// @group 颜色 / Color
// @param name=MistColor type=color3 default=#8E0F18 zh_cn=雾色 en_us=Mist Color
// @param name=DeepColor type=color3 default=#2A0308 zh_cn=浓处色 en_us=Deep Color
// @param name=Desaturate type=float min=0 max=1 default=0.5 zh_cn=底图褪色 en_us=Scene Desaturate

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
        p *= 2.07;
        a *= 0.5;
    }
    return v;
}

void main() {
    vec2 asp = vec2(OutSize.x / max(OutSize.y, 1.0), 1.0);
    vec2 p = texCoord * asp * Scale;
    float t = GTTime * Speed;

    // 两层反向漂移：同向的话只会叠出一层更粗的噪声，看不出"翻涌"
    float a = fbm(p + vec2(t, t * 0.4));
    float b = fbm(p * 1.7 - vec2(t * 0.7, t * 1.1) + 13.3);
    float fog = clamp((a * 0.65 + b * 0.35) * 1.6 - 0.25, 0.0, 1.0);

    float r = length((texCoord - 0.5) * asp) / max(length(asp) * 0.5, 1e-4);
    fog *= mix(1.0, smoothstep(0.15, 1.1, r), EdgeBias);
    fog *= Density;

    vec3 src = texture(InSampler, texCoord).rgb;
    float g = dot(src, vec3(0.2126, 0.7152, 0.0722));
    vec3 col = mix(src, vec3(g), Desaturate * clamp(fog, 0.0, 1.0));

    vec3 mist = mix(MistColor, DeepColor, clamp(fog * 0.8, 0.0, 1.0));
    col = mix(col, col * 0.35 + mist, clamp(fog, 0.0, 1.0));

    // 血痕：竖向的细条，各自以不同速度往下走
    if (Runs > 0.001) {
        float lane = texCoord.x * 90.0;
        float id = floor(lane);
        float lf = abs(fract(lane) - 0.5) * 2.0;
        float live = step(1.0 - Runs * 0.4, hash(vec2(id, 3.0)));
        float head = fract(hash(vec2(id, 7.0)) + GTTime * RunSpeed * mix(0.4, 1.2, hash(vec2(id, 11.0))));
        float y = 1.0 - texCoord.y;
        float trail = y < head && y > head - 0.5 ? (1.0 - (head - y) / 0.5) : 0.0;
        float run = live * trail * smoothstep(RunWidth * 90.0, 0.0, lf);
        col = mix(col, DeepColor, clamp(run, 0.0, 1.0) * 0.85);
    }

    fragColor = vec4(col, 1.0);
}
