// 天降激光 / Laser Strike
// 一道从天而降砸在锚点上的光柱，落地处炸开一圈冲击环。
//
// 这个效果专门演示<b>世界锚点</b>：光柱的落点是世界里的某个实体或坐标在屏幕上的投影，
// 而不是屏幕上的一个固定位置。配「死亡时」的锚点绑定就是「谁死在哪，激光劈在哪」；
// 配「一直有效 + 准星指向的实体」就是「看谁劈谁」。
//
// 三个让它看起来像"打下来"而不是"贴了根柱子"的细节：
//   1. 光柱必须<b>向上无限延伸</b>但向下在落点截断——从天上来的东西不该穿过地面；
//   2. 落地环要比光柱亮，且扩散速度先快后慢，这是冲击波在空气里的样子；
//   3. 整体要有一段极短的预警（细白线）再爆开，没有预警就只是"突然亮了一下"。
//
// [en_us]
// A laser pillar that crashes down from the sky onto an anchor.
// Where it lands, a shockwave ring bursts out.
//
// This effect exists to demonstrate <b>world anchors</b>: the beam's landing point is the on-screen projection
// of an entity or position in the world, not a fixed spot on the screen. With an "On death" anchor binding it
// becomes "wherever something dies, the laser strikes there"; with "Always + Entity under crosshair" it becomes
// "strike whatever you look at".
//
// Three details that make it look like it "came down" rather than "a pillar got pasted on":
//   1. The beam must <b>extend upward forever</b> but stop at the landing point going down. Something from the
//      sky should not pass through the ground.
//   2. The landing ring must be brighter than the beam and expand fast, then slow down. That is how a shockwave
//      moves through air.
//   3. There must be a very short warning (a thin white line) before the blast. Without it, it's just
//      "a sudden flash".
//
// @param name=AnchorSlot type=anchor min=0 max=7 default=0 zh_cn=锚点槽位 en_us=Anchor Slot desc_zh_cn=用第几个锚点当落点。槽位号在编辑器「锚点」页每条绑定后面标着 desc_en_us=Which anchor slot to strike. The number is shown next to each binding on the Anchors tab
// @param name=Center type=vec2 min=0 max=1 default=0.5,0.6 zh_cn=备用落点 en_us=Fallback Point desc_zh_cn=锚点无效时用的固定屏幕坐标。设成没装 mod 也能看到东西，方便先调外观再接锚点 desc_en_us=Fixed screen position used when the anchor is invalid, so you can tune the look before wiring up an anchor

// @group 外观 / Look
// @param name=BeamColor type=color3 default=#8FE3FF zh_cn=光柱颜色 en_us=Beam Color
// @param name=CoreColor type=color3 default=#FFFFFF zh_cn=芯色 en_us=Core Color
// @param name=BeamWidth type=float min=0.002 max=0.2 default=0.022 zh_cn=光柱宽度 en_us=Beam Width
// @param name=Bloom type=float min=0 max=4 default=1.6 zh_cn=辉光 en_us=Bloom
// @param name=RingWidth type=float min=0.005 max=0.3 default=0.045 zh_cn=冲击环宽度 en_us=Ring Width
// @param name=RingReach type=float min=0.05 max=2 default=0.55 zh_cn=冲击环范围 en_us=Ring Reach

// @group 时序 / Timing
// @param name=Progress type=float min=0 max=1 default=0.35 zh_cn=手动进度 en_us=Manual Progress desc_zh_cn=挂上世界锚点后这个值不再生效，进度改由锚点的生命周期驱动 desc_en_us=Ignored once a world anchor drives it; the anchor lifecycle takes over
// @param name=WarnFrac type=float min=0 max=0.6 default=0.22 zh_cn=预警占比 en_us=Warning Phase

void main() {
    vec2 asp = vec2(OutSize.x / max(OutSize.y, 1.0), 1.0);

    // 落点与进度：有世界锚点就全听它的，没有就退回可手调的固定值。
    // 这个退化路径不是凑数——它让效果在没装 mod、或者还没配锚点的时候<b>照样能预览</b>，
    // 于是「先把外观调好、再接上锚点」成为可能。反过来（锚点无效就整个黑掉）
    // 会让人在完全看不到东西的情况下调参数。
    vec2 hit = Center;
    float t = Progress;
    float gate = 1.0;
    if (gtAnchorValid(AnchorSlot)) {
        hit = gtAnchorUV(AnchorSlot);
        t = gtAnchorLife(AnchorSlot);
        gate = gtAnchorStrength(AnchorSlot) * gtAnchorVisible(AnchorSlot);
    }

    vec2 d = (texCoord - hit) * asp;
    vec3 src = texture(InSampler, texCoord).rgb;
    vec3 col = src;

    // ---- 相位 ----
    // 预警段：一条细白线先亮起来。爆发段：光柱张开再收拢。
    float warn = clamp(t / max(WarnFrac, 1e-4), 0.0, 1.0);
    float burst = clamp((t - WarnFrac) / max(1.0 - WarnFrac, 1e-4), 0.0, 1.0);
    // 张开快、收拢慢：sin 的半波正好，且两端自然归零，不需要额外淡入淡出
    float beamEnv = t < WarnFrac ? warn * 0.12 : sin(burst * 3.14159);

    // ---- 光柱 ----
    // 只算横向距离，纵向不参与 —— 于是它是一条竖直的无限长带子
    float lateral = abs(d.x);
    // 向下在落点处截断：光是从天上打下来的，不该穿到地面以下去
    float below = smoothstep(0.0, 0.04, d.y);
    float shaft = (1.0 - below);

    float halo = exp(-lateral / max(BeamWidth * 2.5, 1e-4)) * Bloom;
    float core = smoothstep(BeamWidth, BeamWidth * 0.25, lateral);
    col += (BeamColor * halo + CoreColor * core * 1.8) * beamEnv * shaft * gate;

    // ---- 落地冲击环 ----
    // 只在爆发段出现。半径先快后慢地扩，用 sqrt 是最省事也最像的一条曲线
    float ringR = sqrt(burst) * RingReach;
    float ring = smoothstep(RingWidth, 0.0, abs(length(d) - ringR));
    // 环随扩散变暗，否则扩到屏幕边缘还一样亮
    ring *= (1.0 - burst) * step(WarnFrac, t);
    col += mix(BeamColor, CoreColor, 0.5) * ring * 2.2 * gate;

    // ---- 落点闪光 ----
    // 爆发瞬间在落点糊一团亮斑，把光柱和环连起来，否则两者看着是分开的两个东西
    float flash = exp(-length(d) / 0.09) * pow(1.0 - burst, 3.0) * step(WarnFrac, t);
    col += CoreColor * flash * 1.5 * gate;

    fragColor = vec4(col, 1.0);
}
