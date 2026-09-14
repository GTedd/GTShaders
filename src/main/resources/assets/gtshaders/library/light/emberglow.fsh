// 余烬飞舞 / Ember Glow
// 一片橙红的火星从下往上飘，边飘边晃、边飘边暗，飘到顶就熄灭。
// 每一粒的速度、横向摆幅、寿命都不同，靠的是把格子编号丢进哈希——
// 整齐上升的粒子一眼就能看出是程序生成的。
//
// [en_us]
// Orange-red embers drifting up the screen.
// A swarm of orange-red sparks floats from bottom to top, swaying and dimming as it rises, and burns out at
// the top. Every spark has its own speed, sideways sway and lifetime, done by feeding the cell index into a
// hash. Particles that rise in lockstep are instantly recognizable as procedurally generated.
//
// @param name=Density type=float min=10 max=200 default=52 zh_cn=火星密度 en_us=Ember Density
// @param name=Speed type=float min=0 max=3 default=0.35 zh_cn=上升速度 en_us=Rise Speed
// @param name=Sway type=float min=0 max=2 default=0.7 zh_cn=横向摆动 en_us=Sway
// @param name=Size type=float min=0.05 max=1 default=0.3 zh_cn=火星大小 en_us=Ember Size

// @group 外观 / Look
// @param name=HotColor type=color3 default=#FFD07A zh_cn=新生色 en_us=Hot Color
// @param name=CoolColor type=color3 default=#C43A08 zh_cn=将熄色 en_us=Cooling Color
// @param name=Gain type=float min=0 max=5 default=2 zh_cn=亮度 en_us=Gain
// @param name=Trail type=float min=0 max=1 default=0.4 zh_cn=拖尾 en_us=Trail

// @group 环境 / Ambient
// @param name=WarmAir type=float min=0 max=1 default=0.25 zh_cn=空气泛暖 en_us=Warm Air
// @param name=BottomGlow type=float min=0 max=1 default=0.35 zh_cn=底部辉光 en_us=Bottom Glow

float hash21(vec2 p) {
    return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453);
}

void main() {
    vec2 asp = vec2(OutSize.x / max(OutSize.y, 1.0), 1.0);
    vec3 col = texture(InSampler, texCoord).rgb;

    // 网格铺满整个画面，每格里最多一粒火星
    vec2 grid = vec2(Density, Density * 0.6);
    vec2 gp = texCoord * grid;
    vec2 gi = floor(gp);

    float acc = 0.0;
    vec3 tint = vec3(0.0);
    // 查自己和上下相邻两格：火星会飘出自己那一格，只查本格会看到它们被硬切掉
    for (int dy = -1; dy <= 1; dy++) {
        vec2 cellId = gi + vec2(0.0, float(dy));
        float seed = hash21(cellId);
        if (seed < 0.72) {
            continue;   // 大部分格子是空的，火星才稀疏
        }
        float speed = mix(0.5, 1.6, hash21(cellId + 3.1));
        float life = fract(seed * 7.3 + GTTime * Speed * speed);
        float swayAmp = (hash21(cellId + 9.7) - 0.5) * 2.0 * Sway;

        // 粒子在本格内的位置：竖向由寿命决定，横向随时间摆
        vec2 pos = cellId + vec2(0.5 + swayAmp * 0.4 * sin(GTTime * 1.7 * speed + seed * 20.0),
                                 life * 3.0 - 1.0);
        vec2 diff = (gp - pos) / grid * asp;
        float d = length(diff * vec2(1.0, 1.0 / max(1.0 + Trail * 3.0, 1.0)));

        float rad = Size * 0.012 * (1.0 - life * 0.5);
        float glow = exp(-d * d / max(rad * rad, 1e-8));
        float fade = (1.0 - life) * smoothstep(0.0, 0.15, life);
        acc += glow * fade;
        tint += mix(HotColor, CoolColor, life) * glow * fade;
    }

    col += tint * Gain;
    col += HotColor * WarmAir * 0.12;
    col += HotColor * BottomGlow * exp(-texCoord.y * 4.0) * 0.4;

    fragColor = vec4(col, 1.0);
}
