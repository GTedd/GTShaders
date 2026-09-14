// 幻觉扭曲 / Hallucination
// 画面像浸在水里一样缓慢起伏，颜色沿着扭曲方向拆开，还有一层若隐若现的重影跟不上主画面。
// 三层扭曲的频率与相位都不同，任何时刻都找不到"正在重复"的那一秒——这是致幻感的关键。
//
// [en_us]
// A slow, underwater-like warping hallucination.
// The picture slowly undulates as if submerged in water, colors split apart along the direction of the warp,
// and a faint ghost image lags behind the main picture. The three warp layers all differ in frequency and
// phase, so there is never a single second where it visibly "repeats". That is the key to the
// hallucinatory feel.
//
// @param name=Amount type=float min=0 max=1 default=0.5 zh_cn=扭曲强度 en_us=Warp Amount
// @param name=Speed type=float min=0 max=3 default=0.5 zh_cn=起伏速度 en_us=Speed
// @param name=Scale type=float min=1 max=20 default=4 zh_cn=波纹尺度 en_us=Wave Scale

// @group 重影 / Echo
// @param name=Echo type=float min=0 max=1 default=0.4 zh_cn=重影强度 en_us=Echo Amount
// @param name=EchoLag type=float min=0 max=0.1 default=0.03 zh_cn=重影滞后 en_us=Echo Lag
// @param name=EchoTint type=color3 default=#FF7ACD zh_cn=重影色 en_us=Echo Tint

// @group 色彩 / Color
// @param name=Split type=float min=0 max=0.05 default=0.012 zh_cn=通道分离 en_us=Channel Split
// @param name=HueDrift type=float min=0 max=1 default=0.35 zh_cn=色相漂移 en_us=Hue Drift
// @param name=Saturate type=float min=0 max=2 default=1.3 zh_cn=饱和度 en_us=Saturation

vec3 hueShift(vec3 c, float a) {
    // YIQ 里旋转色相：三行常数就够，比转 HSV 再转回来便宜
    const vec3 k = vec3(0.57735);
    float cosA = cos(a);
    return c * cosA + cross(k, c) * sin(a) + k * dot(k, c) * (1.0 - cosA);
}

vec2 warpAt(vec2 uv, float t) {
    float s = Scale;
    vec2 w = vec2(0.0);
    w.x += sin(uv.y * s * 1.0 + t * 1.13) * 1.0;
    w.y += cos(uv.x * s * 1.3 + t * 0.87) * 1.0;
    w.x += sin(uv.y * s * 2.7 - t * 1.61) * 0.45;
    w.y += cos(uv.x * s * 3.1 + t * 1.31) * 0.45;
    w.x += sin((uv.x + uv.y) * s * 5.3 + t * 2.29) * 0.2;
    w.y += cos((uv.x - uv.y) * s * 4.7 - t * 2.03) * 0.2;
    return w * 0.012 * Amount;
}

void main() {
    float t = GTTime * Speed;
    vec2 w = warpAt(texCoord, t);
    vec2 uv = clamp(texCoord + w, vec2(0.0), vec2(1.0));

    // 通道沿扭曲方向拆开，而不是固定横向——这样拆出来的颜色跟着画面一起流
    vec2 dir = normalize(w + 1e-6);
    vec3 col;
    col.r = texture(InSampler, clamp(uv + dir * Split, vec2(0.0), vec2(1.0))).r;
    col.g = texture(InSampler, uv).g;
    col.b = texture(InSampler, clamp(uv - dir * Split, vec2(0.0), vec2(1.0))).b;

    // 重影：用一个滞后的时间去算扭曲，得到"跟不上"的那一层
    if (Echo > 0.001) {
        vec2 lagUv = clamp(texCoord + warpAt(texCoord, t - EchoLag * 40.0), vec2(0.0), vec2(1.0));
        vec3 echo = texture(InSampler, lagUv).rgb * EchoTint;
        col = mix(col, max(col, echo), Echo * 0.6);
    }

    col = hueShift(col, sin(t * 0.37) * HueDrift * 1.5);
    float g = dot(col, vec3(0.2126, 0.7152, 0.0722));
    col = g + (col - g) * Saturate;

    fragColor = vec4(col, 1.0);
}
