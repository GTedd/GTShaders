// 雷电充能 / Thunder Charge
// 电弧在画面上爬行，能量一格格攒起来，攒满时炸开一次全屏白闪，然后重新开始。
// 电弧用「噪声的等值线」画：取一层 fbm，把它接近某个值的那条细带高亮出来，
// 天然就是曲折的、分叉的、不重复的——手写折线段永远画不出这个味道。
//
// [en_us]
// Electric arcs charge up, then burst in a white flash.
// Arcs crawl across the screen while energy builds up step by step. When it is full it bursts into a
// full-screen white flash, then starts over. The arcs are drawn as "noise contour lines": take a layer of fbm
// and highlight the thin band where it is close to a certain value. The result is naturally jagged, branching
// and non-repeating, a feel that hand-written polyline segments can never achieve.
//
// @param name=Cycle type=float min=0.5 max=20 default=4 zh_cn=充能周期(秒) en_us=Charge Cycle
// @param name=ArcCount type=float min=1 max=8 default=3 zh_cn=电弧层数 en_us=Arc Layers
// @param name=Scale type=float min=1 max=20 default=5 zh_cn=电弧尺度 en_us=Arc Scale
// @param name=Speed type=float min=0 max=6 default=1.6 zh_cn=爬行速度 en_us=Crawl Speed

// @group 外观 / Look
// @param name=ArcColor type=color3 default=#9FD8FF zh_cn=电弧色 en_us=Arc Color
// @param name=CoreColor type=color3 default=#FFFFFF zh_cn=弧心色 en_us=Core Color
// @param name=Thickness type=float min=0.002 max=0.08 default=0.016 zh_cn=弧宽 en_us=Arc Width
// @param name=Gain type=float min=0 max=6 default=2.4 zh_cn=亮度 en_us=Gain

// @group 放电 / Discharge
// @param name=BurstFlash type=float min=0 max=3 default=1.2 zh_cn=放电闪光 en_us=Burst Flash
// @param name=ChargeGlow type=float min=0 max=1 default=0.4 zh_cn=充能辉光 en_us=Charge Glow

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
    float period = max(Cycle, 0.2);
    float t = fract(GTTime / period);
    // 充能到最后越来越急，放电后瞬间归零
    float charge = pow(t, 2.0);
    float burst = exp(-fract(GTTime / period) * 14.0) * (t < 0.08 ? 1.0 : 0.0);

    vec2 asp = vec2(OutSize.x / max(OutSize.y, 1.0), 1.0);
    vec2 p = (texCoord - 0.5) * asp;
    vec3 src = texture(InSampler, texCoord).rgb;

    float arcs = 0.0;
    for (int i = 0; i < 8; i++) {
        if (float(i) >= ArcCount) {
            break;
        }
        float k = float(i);
        vec2 q = p * Scale + vec2(k * 3.7, -GTTime * Speed * (0.7 + k * 0.2));
        float n = fbm(q);
        // 等值线：把 n 接近 0.5 的那条细带提出来，就是一条曲折的弧
        float line = smoothstep(Thickness, 0.0, abs(n - 0.5));
        // 沿弧长方向的明暗跳动，模拟电流不均匀
        line *= 0.5 + 0.5 * sin(n * 120.0 + GTTime * 22.0 + k);
        arcs = max(arcs, line);
    }
    arcs *= 0.25 + charge * 0.75;

    vec3 col = src;
    col += ArcColor * arcs * Gain;
    col += CoreColor * pow(arcs, 3.0) * Gain * 0.8;
    // 充能辉光：整幅画面轻微泛蓝，越接近放电越明显
    col += ArcColor * charge * ChargeGlow * 0.25;
    col += CoreColor * burst * BurstFlash;

    fragColor = vec4(col, 1.0);
}
