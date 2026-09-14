// 条纹推移 / Stripe Push
// 一排色条从画面两侧交替推进来，把旧画面挤出去。相邻条方向相反，交错着合上。
// 条本身带一点速度差，先到的先停，于是收尾时有一段「咔哒咔哒」补齐的节奏。
//
// [en_us]
// Alternating stripes push in from both sides.
// A row of colored bars slides in alternately from both sides of the picture, pushing the old picture out.
// Adjacent bars move in opposite directions and interlock as they close. The bars have slightly different speeds
// and the first to arrive stops first, so the ending has a "click, click" rhythm as the last gaps fill in.
//
// @param name=AutoPlay type=bool default=1 zh_cn=随时间自动播放 en_us=Auto Play
// @param name=Duration type=float min=0.2 max=10 default=1.2 zh_cn=转场时长(秒) en_us=Duration
// @param name=Hold type=float min=0 max=10 default=0.6 zh_cn=两端停留(秒) en_us=Hold desc_zh_cn=盖满和揭开之后各停多久再往回走 desc_en_us=How long it rests at each end before reversing
// @param name=Progress type=float min=0 max=1 default=0.5 zh_cn=手动进度 en_us=Progress

// @group 形状 / Shape
// @param name=Count type=float min=2 max=48 default=9 zh_cn=条数 en_us=Stripe Count
// @param name=Vertical type=bool default=0 zh_cn=竖向条 en_us=Vertical Stripes
// @param name=Stagger type=float min=0 max=0.8 default=0.3 zh_cn=速度差 en_us=Speed Variance
// @param name=PushOld type=float min=0 max=1 default=0.6 zh_cn=旧画面跟着被推 en_us=Push Old Frame

// @group 外观 / Look
// @param name=ColorA type=color3 default=#12141C zh_cn=条色 A en_us=Stripe A
// @param name=ColorB type=color3 default=#1E2230 zh_cn=条色 B en_us=Stripe B

float hash11(float p) {
    return fract(sin(p * 57.31) * 41233.7891);
}

// 往复播放：盖上 → 停一下 → 揭开 → 停一下，然后重来。
// 这里绝不能写成 clamp(GTTime / Duration)——GTTime 只增不减，跑过一遍之后恒等于 1，
// 画面会永远停在「已经盖满」那一帧。而玩家把效果加进工程时 GTTime 早就几百秒了，
// 于是「加上去只看到一块死板的颜色，怎么调都不动」。
float gtPingPong(float duration, float hold) {
    float d = max(duration, 0.01);
    float h = max(hold, 0.0);
    float age = mod(GTTime, (d + h) * 2.0);
    if (age < d) {
        return age / d;
    }
    if (age < d + h) {
        return 1.0;
    }
    if (age < d * 2.0 + h) {
        return 1.0 - (age - d - h) / d;
    }
    return 0.0;
}

void main() {
    float t = AutoPlay > 0.5 ? gtPingPong(Duration, Hold) : Progress;

    float across = Vertical > 0.5 ? texCoord.x : texCoord.y;
    float along = Vertical > 0.5 ? texCoord.y : texCoord.x;

    float idx = floor(across * Count);
    float dir = mod(idx, 2.0) < 0.5 ? 1.0 : -1.0;   // 奇偶条反向

    // 速度差：每条各自的进度略快或略慢，但都在 t=1 之前抵达
    float speed = 1.0 + Stagger * (hash11(idx * 7.13) - 0.5) * 2.0;
    float tt = clamp(t * speed, 0.0, 1.0);

    // dir=+1 的条从 0 端推进来，dir=-1 的从 1 端
    float front = dir > 0.0 ? tt : 1.0 - tt;
    bool covered = dir > 0.0 ? (along < front) : (along > front);

    // 旧画面被推着走，不是原地被盖住——这一下让转场有了"力"
    vec2 uv = texCoord;
    float push = tt * PushOld * 0.35 * dir;
    if (Vertical > 0.5) {
        uv.y += push;
    } else {
        uv.x += push;
    }
    vec3 src = texture(InSampler, clamp(uv, vec2(0.0), vec2(1.0))).rgb;

    vec3 stripe = mix(ColorA, ColorB, hash11(idx * 3.9));
    fragColor = vec4(covered ? stripe : src, 1.0);
}
