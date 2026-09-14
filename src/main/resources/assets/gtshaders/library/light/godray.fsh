// 上帝之光 / God Rays
// 从光源位置往外放射的体积光。做法是「径向模糊亮部」：沿着朝向光源的射线反复采样并衰减，
// 亮的地方会被拖成一条长长的光柱，暗的地方拖不出东西——这正是丁达尔效应的样子。
//
// 光源可以固定在画面某处，也可以跟着世界锚点走（需要装本 mod）。
// 光柱强度还随时间轻微起伏，模拟云或树叶的遮挡在变。
//
// [en_us]
// Volumetric light rays radiating from a light source.
// The technique is "radial blur of the bright areas": sample repeatedly along the ray toward the light source
// with falloff, so bright spots get dragged into long shafts of light while dark spots produce nothing. That
// is exactly what the Tyndall effect looks like.
//
// The light source can be fixed somewhere on screen or follow a world anchor (requires this mod installed).
// The shaft intensity also rises and falls slightly over time, simulating shifting cover from clouds or leaves.
//
// @param name=SunPos type=vec2 min=-0.5 max=1.5 default=0.5,0.85 zh_cn=光源位置 en_us=Light Position
// @param name=UseAnchor type=bool default=0 zh_cn=跟随世界锚点 en_us=Follow Anchor

// @group 光束 / Rays
// @param name=Density type=float min=0 max=1.5 default=0.75 zh_cn=射线长度 en_us=Ray Length
// @param name=Decay type=float min=0.8 max=1 default=0.96 zh_cn=沿程衰减 en_us=Decay
// @param name=Weight type=float min=0 max=2 default=0.6 zh_cn=射线权重 en_us=Weight
// @param name=Threshold type=float min=0 max=1 default=0.55 zh_cn=起效亮度 en_us=Luma Threshold

// @group 外观 / Look
// @param name=RayColor type=color3 default=#FFE7B8 zh_cn=光色 en_us=Ray Color
// @param name=Gain type=float min=0 max=4 default=1.2 zh_cn=亮度 en_us=Gain
// @param name=Flicker type=float min=0 max=1 default=0.25 zh_cn=遮挡起伏 en_us=Occlusion Flicker
// @param name=Halo type=float min=0 max=2 default=0.5 zh_cn=光源辉光 en_us=Source Halo
// @param name=AnchorSlot type=anchor min=0 max=7 default=0 zh_cn=锚点绑定 en_us=Anchor Binding desc_zh_cn=读哪一条锚点绑定。选的是绑定本身而不是槽位号，之后增删、排序绑定都不会指错 desc_en_us=Which anchor binding to read. Stores the binding itself, not a slot number, so it survives reordering

void main() {
    vec2 asp = vec2(OutSize.x / max(OutSize.y, 1.0), 1.0);
    vec3 src = texture(InSampler, texCoord).rgb;

    vec2 sun = SunPos;
    float strength = 1.0;
    if (UseAnchor > 0.5 && gtAnchorValid(AnchorSlot)) {
        sun = gtAnchorUV(AnchorSlot);
        strength = gtAnchorStrength(AnchorSlot);
    }

    // 每一步朝光源靠拢固定的比例，所以步长在屏幕上是等距的
    const int STEPS = 24;
    vec2 delta = (texCoord - sun) * (Density / float(STEPS));
    vec2 uv = texCoord;
    float illum = 1.0;
    vec3 acc = vec3(0.0);

    for (int i = 0; i < STEPS; i++) {
        uv -= delta;
        vec3 c = texture(InSampler, clamp(uv, vec2(0.0), vec2(1.0))).rgb;
        float l = dot(c, vec3(0.2126, 0.7152, 0.0722));
        // 只有超过阈值的亮部才参与：否则整幅画面都会被往光源方向拖，糊成一团
        acc += c * smoothstep(Threshold, min(Threshold + 0.3, 1.0), l) * illum;
        illum *= Decay;
    }
    acc /= float(STEPS);

    // 遮挡起伏：模拟云或树叶在动，光柱因此不是一条恒定的亮度
    float flick = 1.0 + sin(GTTime * 0.8) * 0.6 * Flicker + sin(GTTime * 2.3 + 1.7) * 0.4 * Flicker;

    vec3 col = src + acc * RayColor * Weight * Gain * flick * strength;

    // 光源本体的辉光
    float d = length((texCoord - sun) * asp);
    col += RayColor * exp(-d * d * 12.0) * Halo * flick * strength;

    fragColor = vec4(col, 1.0);
}
