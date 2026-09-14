// 载体光源 / Emitter Light
// 把锚点当光源，在屏幕空间画出它的辉光与光锥。四种形状由<b>每个载体自己的子类型</b>决定，
// 所以同一层里可以既有球光又有聚光——这正是 @param 做不到、必须挂在载体上的那类东西
// （参数属于层，子类型属于单个载体）。
//
// <b>这是屏幕空间近似，不是延迟光照。</b>参考的 some_of_fx 那版是真的 ReSTIR 重要性采样
// 加时空降噪，要 G-buffer 法线、四个 persistent 缓冲、十几个通道；GTShaders 的后处理是
// main↔swap 单通道乒乓，做不了也不该做。差别说清楚：这里的光<b>不会照亮墙面</b>，
// 它是一团随距离衰减、被朝向拉扁的光雾。要真照明得先给管线加深度和法线输入。
//
// 载体面板上：「子类型」选形状，「朝向」决定光往哪照（球光可以留 NONE），
// 「自定义1」是这一盏的亮度倍率——同一层里让某几盏更亮就靠它。
//
// [en_us]
// Screen-space glow and light cones cast from emitters.
// It treats an anchor as a light source and draws its glow and light cone in screen space. The four shapes are
// chosen by <b>each emitter's own Sub-type</b>, so one layer can hold both sphere lights and spotlights. That is
// exactly the kind of thing a @param can't do and has to live on the emitter (parameters belong to the layer,
// the sub-type belongs to a single emitter).
//
// <b>This is a screen-space approximation, not deferred lighting.</b> The some_of_fx version it references does
// real ReSTIR importance sampling with spatiotemporal denoising, which needs G-buffer normals, four persistent
// buffers and a dozen or more passes. GTShaders post-processing is single-pass main↔swap ping-pong, so it can't
// do that and shouldn't try. To be clear about the difference: this light <b>does not illuminate walls</b>.
// It is a haze of light that fades with distance and is squashed along its facing. Real lighting would first
// need depth and normal inputs added to the pipeline.
//
// In the emitter panel, "Sub-type" picks the shape, "Facing" sets where the light points (sphere lights can
// leave it at NONE), and "Custom 1" is this lamp's brightness multiplier. Use it to make some lamps in a layer
// brighter than the rest.
//
// @param name=Color type=color3 default=#FFE0B0 zh_cn=光色 en_us=Light Color
// @param name=Intensity type=float min=0 max=4 default=1.4 zh_cn=亮度 en_us=Intensity
// @param name=Size type=float min=0.2 max=8 default=2.5 zh_cn=光晕尺寸 en_us=Halo Size desc_zh_cn=在载体的世界半径之上再乘一道。光晕本来就该比光源本体大 desc_en_us=Multiplies the emitter world radius; a halo is meant to exceed its source
// @param name=Falloff type=float min=0 max=4 default=1 zh_cn=距离衰减 en_us=Distance Falloff desc_zh_cn=0 = 多远都一样亮。真实感要 1 附近，舞台灯光效果可以调低 desc_en_us=0 keeps brightness constant with distance
// @param name=ConeAngle type=float min=5 max=90 default=35 zh_cn=光锥张角(度) en_us=Cone Angle desc_zh_cn=只对聚光和柱光生效 desc_en_us=Spot and cylinder only
// @param name=Softness type=float min=0.05 max=1 default=0.45 zh_cn=边缘柔和 en_us=Edge Softness

// 球光：最简单的一种，指数衰减的各向同性光雾
float gtfSphere(vec2 p, float r) {
    return exp(-length(p) / max(r * Softness, 1e-4));
}

// 矩形面光：局部坐标下的圆角矩形 SDF。
// 侧对相机时把横轴压扁——一块正对你是方的板子，侧过去就该是一条缝。
// 不压的话，转到 90 度时它还是个正方形，立刻穿帮
float gtfRect(vec2 p, float r, float facing) {
    // 不能叫 half：那是 GLSL 的保留字，编译器会在这一行报「Reserved word」
    vec2 ext = vec2(r * 1.5 * max(abs(facing), 0.06), r);
    vec2 q = abs(p) - ext;
    float d = length(max(q, vec2(0.0))) + min(max(q.x, q.y), 0.0);
    return exp(-max(d, 0.0) / max(r * Softness, 1e-4));
}

// 聚光：沿朝向的扇形 × 径向衰减
float gtfSpot(vec2 p, vec2 dir, float r, float cosHalf) {
    float len = length(p);
    if (len < 1e-5) {
        return 1.0;
    }
    // 从锥心到锥边平滑过渡。cosHalf 是张角一半的余弦，越大锥越窄
    float c = dot(p / len, dir);
    float cone = smoothstep(cosHalf, mix(cosHalf, 1.0, 0.6), c);
    return cone * exp(-len / max(r * 2.0 * Softness, 1e-4));
}

// 柱光：到朝向轴的垂直距离决定粗细，沿轴方向有个软性的长度上限
float gtfCylinder(vec2 p, vec2 dir, float r) {
    float along = dot(p, dir);
    float perp = length(p - dir * along);
    float body = exp(-perp / max(r * Softness, 1e-4));
    float cap = exp(-max(abs(along) - r * 4.0, 0.0) / max(r, 1e-4));
    return body * cap;
}

void main() {
    vec3 src = texture(InSampler, texCoord).rgb;
    vec3 glow = vec3(0.0);
    float cosHalf = cos(radians(ConeAngle));

    for (int i = 0; i < GT_ANCHOR_SLOTS; i++) {
        if (!gtAnchorValid(i)) {
            continue;
        }
        float r = max(gtAnchorRadius(i), 0.004) * Size;
        vec2 p = gtEmitterLocal(i);
        vec2 dir = gtEmitterDir(i);
        // 朝向几乎平行于视线时屏幕方向退化成 (0,0)。那时锥轴在屏幕上缩成一个点，
        // 扇形判据会把整个光锥判掉——退回球形才是那一刻真正看到的样子
        bool hasDir = dot(dir, dir) > 0.25;

        int kind = gtEmitterType(i);
        float shape;
        if (kind == 0) {
            shape = gtfRect(p, r, gtEmitterFacing(i));
        } else if (kind == 2 && hasDir) {
            shape = gtfSpot(p, dir, r, cosHalf);
        } else if (kind == 3 && hasDir) {
            shape = gtfCylinder(p, dir, r);
        } else {
            shape = gtfSphere(p, r);
        }

        // 平方反比，但分母加 1 而不是直接除：贴到脸上时距离趋近 0，
        // 纯平方反比会给出几万倍的亮度，一帧就把整屏烧成纯白
        float d = gtAnchorDistance(i);
        float atten = 1.0 / (1.0 + Falloff * d * d * 0.02);
        atten *= gtAnchorStrength(i) * gtAnchorVisible(i);

        glow += Color * shape * atten * (1.0 + gtEmitterCustom1(i));
    }

    fragColor = vec4(src + glow * Intensity, 1.0);
}
