// 甩镜 / Whip Pan
// 镜头猛地横甩过去，中途整片糊成条纹，然后急停。剪辑里用来接两个完全无关的画面。
// 甩的速度曲线是「加速—到顶—急停」，糊的强度就是这条曲线本身，两者必须共用同一个值，
// 否则会出现"已经停了还在糊"的怪异感。
//
// [en_us]
// Whip pan: a fast sideways swing that blurs into streaks.
// The camera whips sideways, the whole frame smears into streaks midway, then it stops dead. Editors use it to cut
// between two completely unrelated shots. The speed curve is "accelerate → peak → hard stop", and the blur
// strength is that very curve. Both must share one value, or you get the odd "already stopped but still blurry"
// look.
//
// @param name=AutoPlay type=bool default=1 zh_cn=随时间自动播放 en_us=Auto Play
// @param name=Interval type=float min=0.3 max=12 default=3 zh_cn=触发间隔(秒) en_us=Interval
// @param name=Duration type=float min=0.05 max=2 default=0.35 zh_cn=甩镜时长(秒) en_us=Whip Duration
// @param name=Progress type=float min=0 max=1 default=0.5 zh_cn=手动进度 en_us=Progress

// @group 甩动 / Whip
// @param name=Distance type=float min=0 max=1.5 default=0.55 zh_cn=甩动距离 en_us=Distance
// @param name=Blur type=float min=0 max=1 default=0.8 zh_cn=拖糊强度 en_us=Smear
// @param name=Vertical type=bool default=0 zh_cn=改成上下甩 en_us=Vertical Whip

// @group 外观 / Look
// @param name=Darken type=float min=0 max=1 default=0.35 zh_cn=甩动压暗 en_us=Darken
// @param name=Chroma type=float min=0 max=0.04 default=0.01 zh_cn=拖尾色散 en_us=Trail Chroma

void main() {
    float t;
    if (AutoPlay > 0.5) {
        float period = max(Interval, 0.1);
        float age = mod(GTTime, period);
        t = clamp(age / max(Duration, 0.01), 0.0, 1.0);
    } else {
        t = Progress;
    }

    // 加速—到顶—急停：正弦的前半周期，尾部再乘一段收敛
    float vel = sin(clamp(t, 0.0, 1.0) * 3.14159265);
    vel = pow(vel, 0.7);

    vec2 axis = Vertical > 0.5 ? vec2(0.0, 1.0) : vec2(1.0, 0.0);
    vec2 uv = texCoord + axis * (t - 0.5) * Distance * vel;

    float smear = vel * Blur * 0.09;
    vec3 col = vec3(0.0);
    float total = 0.0;
    for (int i = 0; i < 10; i++) {
        float s = float(i) / 9.0 - 0.5;
        float w = 1.0 - abs(s) * 1.2;
        vec2 p = clamp(uv + axis * s * smear, vec2(0.0), vec2(1.0));
        float ca = Chroma * vel;
        col.r += texture(InSampler, clamp(p + axis * ca, vec2(0.0), vec2(1.0))).r * w;
        col.g += texture(InSampler, p).g * w;
        col.b += texture(InSampler, clamp(p - axis * ca, vec2(0.0), vec2(1.0))).b * w;
        total += w;
    }
    col /= max(total, 1e-4);
    col *= 1.0 - Darken * vel;

    fragColor = vec4(col, 1.0);
}
