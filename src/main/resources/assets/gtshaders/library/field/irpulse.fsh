// 红外探测 / IR Pulse
// 探测手雷：在世界里某个坐标炸开，一圈波向外扩散，扫过的地方把**结构**显影出来，
// 目标被标出来并留一段余辉。波过之后画面慢慢冷却回去。
//
// 和「声呐扫描」的两处关键区别，也是它像「探测」而不像「涟漪」的原因：
//
//   1. **波是三维的**。屏幕上的圆只是横截面——远处的东西会更晚被扫到，
//      所以波看起来是绕过近处物体、往深处铺开的球面，而不是贴在屏幕上的平面圆。
//   2. **轮廓来自深度而不是颜色**。声呐用亮度边缘，于是草方块的贴图花纹也会被点亮；
//      这里用 gtDepthEdge，只有真正的几何轮廓才显影——那才是「探测到的结构」。
//
// 深度的用法必须说清楚：后处理拿不到世界相机的 ProjMat，**没法把深度还原成米**
// （见 gtDepth 的注释）。所以这里不是拿爆点深度和像素深度相减——那两个值根本不在
// 同一标度上（一个是 NDC 映射，一个是深度缓冲原始值）——而是把深度当成**到达延迟**：
// 越远的像素被波扫到得越晚。这只要求深度单调，不要求两者对齐。
// 万一实测方向反了（画面上波从远处往近处收），把「深度延迟」调成负数即可。
//
// 用法：锚点页新增一条绑定，来源选「视线落点」或「指定实体」、触发选「仅手动」或「死亡时」、
// 锚定方式选「钉在事件坐标」，把下面的「爆点绑定」指向它。局内按 B 打一发。
// 想让它标出敌人，再加一条「实体类型 = zombie · 一直有效」的绑定，打开「标记目标」。
//
// [en_us]
// A scanning grenade pulse that reveals structure and targets.
// It bursts at a point in the world and a wave spreads outward. Wherever it sweeps, **structure** is revealed,
// and targets are marked with a lingering afterglow. After the wave passes, the picture slowly cools back down.
//
// Two key differences from "Sonar Ping", which are also why this reads as "detection" rather than "a ripple":
//
//   1. **The wave is 3D**. The circle on screen is only a cross-section: distant things get swept later,
//      so the wave looks like a sphere spreading into depth around nearby objects, not a flat circle stuck
//      to the screen.
//   2. **Outlines come from depth, not color**. Sonar uses brightness edges, so even the texture pattern on grass
//      blocks lights up. This uses gtDepthEdge, so only real geometric outlines show up. That is the
//      "detected structure".
//
// How depth is used needs spelling out: post-processing has no access to the world camera's ProjMat, so it
// **cannot convert depth back to meters** (see the gtDepth comments). So this does not subtract the burst depth
// from the pixel depth, since those two values are not even on the same scale (one is an NDC mapping, the other
// a raw depth-buffer value). Instead, depth is treated as an **arrival delay**: the farther a pixel, the later
// the wave reaches it. This only requires depth to be monotonic, not the two values to line up.
// If testing shows the direction reversed (the wave closes in from far to near on screen), just set
// "Depth Delay" to a negative value.
//
// Usage: on the Anchors tab add a binding with Source "Look-at point" or "Specific entity", Trigger
// "Manual only" or "On death", and Anchoring "Stick to event position", then point "Burst Anchor" below at it.
// Press B in game to fire one. To mark enemies too, add another binding "Entity type = zombie · Always" and
// turn on "Mark Targets".
//
// @param name=AnchorSlot type=anchor min=0 max=7 default=0 zh_cn=爆点绑定 en_us=Burst Anchor desc_zh_cn=波从哪条锚点扩散。没装 mod 时退回屏幕中心 desc_en_us=Which anchor the wave expands from. Falls back to screen centre without the mod
// @param name=AutoPlay type=bool default=1 zh_cn=循环播放 en_us=Auto Play desc_zh_cn=编辑器里预览用。真正配到手雷上时关掉它，改吃锚点的生命进度 desc_en_us=For previewing in the editor. Turn it off once wired to a real grenade so it follows the anchor lifetime
// @param name=Period type=float min=0.5 max=10 default=3 zh_cn=循环周期(秒) en_us=Period
// @param name=MaxRadius type=float min=0.2 max=3 default=1.4 zh_cn=最大半径 en_us=Max Radius desc_zh_cn=波扩散到多大就结束，单位是屏幕高度 desc_en_us=How far the wave travels, in screen heights
// @param name=DepthDelay type=float min=-1 max=1 default=0.35 zh_cn=深度延迟 en_us=Depth Delay desc_zh_cn=远处被扫到的延迟。0 就退化成贴在屏幕上的平面圆；反了就调负数 desc_en_us=How much later distant pixels get hit. 0 degrades to a flat screen circle; negate it if the direction looks inverted
// @param name=RingWidth type=float min=0.01 max=0.4 default=0.09 zh_cn=波前宽度 en_us=Ring Width
// @param name=ScanColor type=color3 default=#7CFFE8 zh_cn=波前色 en_us=Scan Color
// @param name=HotColor type=color3 default=#FF9A3C zh_cn=近处热色 en_us=Hot Color
// @param name=ColdColor type=color3 default=#123A5A zh_cn=远处冷色 en_us=Cold Color
// @param name=EdgeGain type=float min=0 max=6 default=2.5 zh_cn=结构增益 en_us=Structure Gain desc_zh_cn=深度轮廓的强度。调 0 就只剩色调没有线条 desc_en_us=Strength of the depth outline. At 0 only the tint remains
// @param name=Afterglow type=float min=0 max=1 default=0.55 zh_cn=余辉 en_us=Afterglow desc_zh_cn=波过之后显影保留多久才冷却回去 desc_en_us=How long the reveal lingers behind the wave before cooling off
// @param name=Dim type=float min=0 max=1 default=0.7 zh_cn=背景压暗 en_us=Background Dim
// @param name=MarkTargets type=bool default=1 zh_cn=标记目标 en_us=Mark Targets desc_zh_cn=把爆点之外的其它锚点标出来。配一条「实体类型 · 一直有效」的绑定就是敌人探测 desc_en_us=Marks every anchor other than the burst point. Add an "entity type / always" binding and it becomes enemy detection
// @param name=MarkColor type=color3 default=#FF4D5E zh_cn=目标色 en_us=Target Color
// @param name=MarkSize type=float min=0.01 max=0.3 default=0.06 zh_cn=目标标记大小 en_us=Marker Size

void main() {
    vec2 asp = vec2(OutSize.x / max(OutSize.y, 1.0), 1.0);
    vec3 scene = texture(InSampler, texCoord).rgb;

    if (!gtAnchorValid(AnchorSlot)) {
        fragColor = vec4(scene, 1.0);
        return;
    }

    // 事件型绑定用生命进度驱动：扔一颗手雷播一次。AutoPlay 只是编辑器里的预览
    float t = AutoPlay > 0.5
            ? fract(GTTime / max(Period, 0.01))
            : gtAnchorLife(AnchorSlot);
    float wave = t * MaxRadius;

    // 波前到达这个像素的「时刻」：屏幕平面距离 + 深度带来的延迟。
    // 深度只用到单调性，不需要和爆点深度在同一标度上——那两个值本来就不是
    float delay = (1.0 - gtDepth(texCoord)) * DepthDelay;
    float arrive = gtAnchorRange(AnchorSlot) + delay;

    // 波前：一薄圈，中心最亮
    float ring = 1.0 - smoothstep(0.0, RingWidth, abs(arrive - wave));
    // 已经被扫过的区域：按离波前多远衰减，Afterglow 决定拖多长
    float passed = arrive < wave
            ? exp(-(wave - arrive) / max(Afterglow * MaxRadius, 1e-3))
            : 0.0;

    // 整个效果按锚点强度淡入淡出。这里不乘 gtAnchorVisible：
    // 爆点转到屏幕外时波仍然该在扩散，只是圆心不在画面里
    float amp = gtAnchorStrength(AnchorSlot);
    ring *= amp;
    passed *= amp;

    // 结构轮廓：只认几何边缘，贴图花纹不参与。这是「探测」和「点亮画面」的分界
    float structure = EdgeGain > 0.0 && !gtIsSky(texCoord)
            ? clamp(gtDepthEdge(texCoord, 1.0) * 60.0 * EdgeGain, 0.0, 1.0)
            : 0.0;

    // 热成像着色：近处偏暖、远处偏冷。gtDepth 在反转深度下近处接近 1
    vec3 thermal = mix(ColdColor, HotColor, gtDepth(texCoord));

    // 背景先冷下来，波扫过的地方才显影
    float reveal = clamp(passed + ring, 0.0, 1.0);
    vec3 col = mix(scene, scene * (1.0 - Dim), amp * (1.0 - reveal * 0.35));
    col = mix(col, thermal, passed * 0.55);
    col += thermal * structure * passed * 1.2;
    col += ScanColor * ring * (0.6 + structure * 1.4);

    // 目标标记：爆点之外的锚点，等波扫到之后才亮——先出现的话就成透视挂了，
    // 而这个效果的前提是「探测到才知道」
    if (MarkTargets > 0.5) {
        for (int i = 0; i < GT_ANCHOR_SLOTS; i++) {
            if (i == AnchorSlot || !gtAnchorValid(i)) {
                continue;
            }
            // 目标到爆点的屏幕距离，用来判断波扫到它没有
            vec2 toBurst = (gtAnchorUV(i) - gtAnchorUV(AnchorSlot)) * asp;
            if (length(toBurst) > wave) {
                continue;
            }
            float d = length((texCoord - gtAnchorUV(i)) * asp);
            float m = 1.0 - smoothstep(MarkSize * 0.55, MarkSize, d);
            // 空心环比实心点更像瞄准标记，也不会把目标本身糊住
            float inner = 1.0 - smoothstep(MarkSize * 0.25, MarkSize * 0.5, d);
            col = mix(col, MarkColor, clamp(m - inner, 0.0, 1.0) * gtAnchorStrength(i) * amp);
        }
    }

    fragColor = vec4(col, 1.0);
}
