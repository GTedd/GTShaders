// 冲击波 / Shockwave
// 一圈往外扩的压缩波。位移必须是「先推后拉」的双向波（用 sin 的一个完整周期），
// 只往一个方向推会变成放大镜，不像爆炸。
//
// [en_us]
// An expanding shockwave ring that ripples the picture.
// A ring-shaped compression wave expanding outward. The displacement must be a two-way "push then pull" wave
// (one full period of sin). Pushing in only one direction turns it into a magnifying glass, and it stops looking
// like an explosion.
//
// @param name=Center type=vec2 min=0 max=1 default=0.5,0.5 zh_cn=爆心 en_us=Center
// @param name=AnchorMode type=int min=0 max=2 default=0 zh_cn=锚点来源 en_us=Anchor Source desc_zh_cn=0=用上面的固定坐标；1=世界锚点，跟着实体或事件走（需要装本 mod）；2=VanillaDI 数据探针，纯资源包也能用但要自行搭建编码端 desc_en_us=0 = fixed position above; 1 = world anchor, follows entities and events (needs this mod); 2 = VanillaDI data probe, works in a plain resource pack but you must build the encoder side
// @param name=AutoPlay type=bool default=1 zh_cn=循环播放 en_us=Auto Play
// @param name=Period type=float min=0.3 max=8 default=2 zh_cn=循环周期(秒) en_us=Period
// @param name=Progress type=float min=0 max=1.5 default=0.4 zh_cn=手动进度 en_us=Progress
// @param name=Amplitude type=float min=0 max=0.1 default=0.03 zh_cn=波幅 en_us=Amplitude
// @param name=Width type=float min=0.01 max=0.4 default=0.09 zh_cn=波宽 en_us=Wave Width
// @param name=Chroma type=float min=0 max=1 default=0.5 zh_cn=波前色散 en_us=Chromatic
// @param name=AnchorSlot type=anchor min=0 max=7 default=0 zh_cn=锚点绑定 en_us=Anchor Binding desc_zh_cn=读哪一条锚点绑定。选的是绑定本身而不是槽位号，之后增删、排序绑定都不会指错 desc_en_us=Which anchor binding to read. Stores the binding itself, not a slot number, so it survives reordering

void main() {
    vec2 asp = vec2(OutSize.x / max(OutSize.y, 1.0), 1.0);

    vec2 center = Center;
    // 冲击波天生是事件型的：配「受伤时」或「死亡时」的锚点绑定，再把 AutoPlay 关掉、
    // 让 Progress 直接吃锚点的生命进度，波就正好跟着那一次事件推出去一圈
    float gate = 1.0;
    if (AnchorMode == 1) {
        if (gtAnchorValid(AnchorSlot)) {
            center = gtAnchorUV(AnchorSlot);
            gate = gtAnchorStrength(AnchorSlot);
        } else {
            gate = 0.0;
        }
    } else if (AnchorMode == 2) {
        vec4 probe = gtProbe(0);
        if (gtProbeValid(probe)) {
            center = probe.xy;
        }
    }

    vec2 d = (texCoord - center) * asp;
    float r = length(d);
    vec2 dir = r > 1e-5 ? d / r : vec2(0.0);

    // 挂了世界锚点时，波的进度直接吃锚点的生命进度——一次事件推出去一圈，不循环。
    // 这正是「受伤/死亡时来一下冲击波」该有的节奏，比自己去对 Period 靠谱得多
    float t = AutoPlay > 0.5 ? fract(GTTime / max(Period, 0.01)) * 1.5 : Progress;
    if (AnchorMode == 1 && gtAnchorValid(AnchorSlot)) {
        t = gtAnchorLife(AnchorSlot) * 1.5;
    }

    // 只在波带内有位移；带内走一个完整正弦周期，于是前沿压缩、后沿拉伸
    float x = (r - t) / max(Width, 1e-4);
    float wave = abs(x) < 1.0 ? sin(x * 3.14159) * (1.0 - abs(x)) : 0.0;
    // 波往外走时会衰减，否则扩到屏幕边缘还一样猛
    wave *= 1.0 - clamp(t / 1.5, 0.0, 1.0);

    vec2 offset = dir / asp * wave * Amplitude * gate;

    // 三通道错开一点点采样，波前就有了彩边
    float c = Chroma * 0.35;
    vec3 col;
    col.r = texture(InSampler, texCoord + offset * (1.0 + c)).r;
    col.g = texture(InSampler, texCoord + offset).g;
    col.b = texture(InSampler, texCoord + offset * (1.0 - c)).b;

    col += abs(wave) * 0.25 * gate;   // 波带自身微微发亮
    fragColor = vec4(col, 1.0);
}
