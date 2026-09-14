// 载体闪电 / Emitter Lightning
// 在<b>相邻两个槽位</b>之间画一道电弧：0-1、1-2、2-3……配几个载体就有几段。
// 想要一条从天到地的闪电，就配一条 maxSlots 大于 1 的绑定；想要分叉，
// 再加一条绑定接在后面——槽位是按绑定顺序连续分配的，段自然就接上了。
//
// 画法是屏幕空间线段 SDF + 沿法线的噪声扰动，和原作 lightning.fsh 一路。
// 原作把节点位置在世界空间里先扰动一次再投影，这里省掉了：投影后扰动看不出区别，
// 而世界空间扰动要额外一次矩阵乘法，乘以八个槽位是白花的。
//
// 扰动方向取<b>线段自身的法线</b>而不是 dFdx/dFdy 求梯度——梯度在线段两端会炸，
// 表现是电弧端点上镶一圈乱码。
//
// [en_us]
// Lightning arcs chained between adjacent emitter slots.
// Draws an arc between <b>each pair of adjacent slots</b>: 0-1, 1-2, 2-3... as many segments as emitters you set
// up. For one bolt from sky to ground, add a binding with maxSlots greater than 1. For a branch, add another
// binding after it: slots are assigned consecutively in binding order, so the segments join up on their own.
//
// It is drawn as a screen-space line segment SDF plus noise displacement along the normal, the same approach as
// lightning.fsh in the original. The original first displaces node positions in world space and then projects
// them; that step is dropped here. Displacing after projection looks no different, while displacing in world
// space costs an extra matrix multiply, which across eight slots is wasted work.
//
// The displacement direction uses <b>the segment's own normal</b> rather than a dFdx/dFdy gradient. The gradient
// blows up at both ends of the segment, which shows up as a ring of garbage pixels around the arc endpoints.
//
// @param name=Color type=color3 default=#AFCBFF zh_cn=电弧色 en_us=Arc Color
// @param name=CoreColor type=color3 default=#FFFFFF zh_cn=芯色 en_us=Core Color
// @param name=Intensity type=float min=0 max=6 default=2.4 zh_cn=亮度 en_us=Intensity
// @param name=Width type=float min=0.0005 max=0.02 default=0.003 zh_cn=线宽 en_us=Arc Width desc_zh_cn=单位是纵向 UV。0.003 在 1080p 上约 3 像素 desc_en_us=In vertical UV; 0.003 is about 3px at 1080p
// @param name=Jitter type=float min=0 max=0.12 default=0.035 zh_cn=抖动幅度 en_us=Jitter
// @param name=Detail type=float min=1 max=16 default=7 zh_cn=抖动细碎度 en_us=Jitter Detail
// @param name=Speed type=float min=0 max=30 default=12 zh_cn=闪变速度 en_us=Flicker Speed
// @param name=Glow type=float min=0 max=1 default=0.5 zh_cn=辉光 en_us=Glow

float gtfHash(vec2 p) {
    return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453);
}

float gtfNoise(float x) {
    float i = floor(x);
    float f = fract(x);
    f = f * f * (3.0 - 2.0 * f);
    return mix(gtfHash(vec2(i, 3.7)), gtfHash(vec2(i + 1.0, 3.7)), f) * 2.0 - 1.0;
}

// 两段不同频率叠起来：单频噪声画出来是一条规律的正弦波，一眼假。
// 高频那层幅度小，负责毛刺；低频那层负责整条弧的大走向
float gtfWiggle(float t, float seed) {
    float a = gtfNoise(t * Detail + seed + GTTime * Speed);
    float b = gtfNoise(t * Detail * 2.7 + seed * 1.7 - GTTime * Speed * 0.6);
    return a * 0.7 + b * 0.3;
}

void main() {
    vec3 src = texture(InSampler, texCoord).rgb;
    vec2 asp = vec2(OutSize.x / max(OutSize.y, 1.0), 1.0);
    vec2 p = texCoord * asp;
    vec3 arc = vec3(0.0);

    for (int i = 0; i + 1 < GT_ANCHOR_SLOTS; i++) {
        // 两端都得在。一端空着的段不画——这也是「让某一段断开」的办法：
        // 把那个槽位对应的绑定停用即可
        if (!gtAnchorValid(i) || !gtAnchorValid(i + 1)) {
            continue;
        }
        vec2 a = gtAnchorUV(i) * asp;
        vec2 b = gtAnchorUV(i + 1) * asp;
        vec2 ba = b - a;
        float lenSq = dot(ba, ba);
        if (lenSq < 1e-8) {
            continue;
        }

        // 线段参数 t：0 在 a 端、1 在 b 端
        float t = clamp(dot(p - a, ba) / lenSq, 0.0, 1.0);
        vec2 nrm = normalize(vec2(-ba.y, ba.x));

        // 两端不抖：电弧是接在两个节点上的，端点飘起来会看出接缝。
        // sin(πt) 在两端正好是 0，中间是 1
        float taper = sin(t * 3.14159265);
        float seed = float(i) * 11.3 + gtEmitterSpin(i);
        vec2 q = p + nrm * gtfWiggle(t, seed) * Jitter * taper;

        // 扰动后重新求一次到线段的距离，才是抖动后那条弧的距离
        float t2 = clamp(dot(q - a, ba) / lenSq, 0.0, 1.0);
        float d = length(q - a - ba * t2);

        float k = min(gtAnchorStrength(i), gtAnchorStrength(i + 1));
        k *= min(gtAnchorVisible(i), gtAnchorVisible(i + 1));
        k *= 1.0 + gtEmitterCustom1(i);

        // 芯 + 辉光两层：只有芯的话电弧看着像一根塑料棍，
        // 只有辉光又没有那道过曝的白线
        float core = 1.0 - smoothstep(0.0, Width, d);
        float halo = exp(-d / max(Width * 6.0, 1e-4)) * Glow;
        arc += (CoreColor * core + Color * halo) * k;
    }

    fragColor = vec4(src + arc * Intensity, 1.0);
}
