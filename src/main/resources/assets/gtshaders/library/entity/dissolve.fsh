// 实体溶解 / Entity Dissolve
// 让发光实体从边缘开始烧成灰烬：溶解面是一条向内推进的噪声阈值线，
// 线上是灼烧色的亮边，线内还没烧到，线外已经消失。
//
// 这个效果演示了轮廓层<b>真正的价值</b>：它同时拿得到剪影（哪块像素属于这个实体）
// 和主画面（那块像素原本长什么样），所以可以做"把原来的实体擦掉"这种事——
// 在剪影内部输出 alpha=1 的替换色，就等于把那块画面盖住了。
//
// 进度可以手调，也可以接世界锚点（gtAnchorLife）让它跟着一次死亡事件推进。
//
// [en_us]
// Burns a glowing entity away to ash, starting from its edges.
// The dissolve front is a noise threshold line that moves inward: on the line is a bright burning edge, inside
// it nothing has burned yet, and outside it everything is already gone.
//
// This effect shows the <b>real value</b> of the outline layer: it gets both the silhouette (which pixels belong
// to this entity) and the main picture (what those pixels originally looked like), so it can do things like
// "erase the original entity". Outputting a replacement color with alpha=1 inside the silhouette simply covers
// that part of the picture.
//
// Progress can be set by hand, or hooked to a world anchor (gtAnchorLife) so it advances with a death event.

// @param name=Progress type=float min=0 max=1 default=0.45 zh_cn=溶解进度 en_us=Progress
// @param name=UseAnchorLife type=bool default=0 zh_cn=用锚点生命周期 en_us=Drive by Anchor desc_zh_cn=开启后进度改由下面选的那条锚点绑定的生命周期驱动，配「死亡时」的锚点绑定就是「死了才开始溶解」 desc_en_us=Drive the progress from the selected anchor binding's lifecycle instead

// @group 外观 / Look
// @param name=BurnColor type=color3 default=#FF7A1A zh_cn=灼烧色 en_us=Burn Color
// @param name=EdgeWidth type=float min=0.01 max=0.5 default=0.14 zh_cn=灼烧边宽 en_us=Burn Width
// @param name=Glow type=float min=0 max=6 default=3.0 zh_cn=灼烧亮度 en_us=Burn Glow
// @param name=NoiseScale type=float min=4 max=120 default=42 zh_cn=噪声密度 en_us=Noise Scale
// @param name=Drift type=float min=0 max=4 default=0.8 zh_cn=噪声漂移 en_us=Noise Drift
// @param name=AnchorSlot type=anchor min=0 max=7 default=0 zh_cn=锚点绑定 en_us=Anchor Binding desc_zh_cn=读哪一条锚点绑定。选的是绑定本身而不是槽位号，之后增删、排序绑定都不会指错 desc_en_us=Which anchor binding to read. Stores the binding itself, not a slot number, so it survives reordering

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

void main() {
    float inside = gtMask();
    if (inside <= 0.001) {
        fragColor = vec4(0.0);
        return;
    }

    float t = Progress;
    if (UseAnchorLife > 0.5 && gtAnchorValid(AnchorSlot)) {
        t = gtAnchorLife(AnchorSlot);
    }

    // 噪声场决定每块像素在什么进度上被烧掉。加一点随时间的漂移，
    // 让溶解面在推进过程中一直在蠕动，而不是一张固定的花纹整体淡出
    vec2 np = texCoord * NoiseScale + vec2(GTTime * Drift * 0.1, GTTime * Drift * 0.07);
    float n = noise(np) * 0.7 + noise(np * 2.3) * 0.3;

    // 边缘先烧：把剪影边缘的像素在噪声上加权，溶解就从轮廓往里推进
    float edgeBias = 1.0 - gtMaskInset(3.0);
    float threshold = n * 0.75 + edgeBias * 0.25;

    if (threshold < t - EdgeWidth) {
        // 已经烧没了：alpha=1 且输出原画面，等于把这块实体像素<b>擦掉</b>——
        // 露出的是它背后的世界。这一步只有轮廓层做得到，因为它读得到 SceneSampler
        fragColor = vec4(gtScene(), 1.0);
        return;
    }
    if (threshold < t) {
        // 正在烧：灼烧色的亮边。越靠近溶解面越亮
        float k = 1.0 - (t - threshold) / max(EdgeWidth, 1e-4);
        fragColor = vec4(BurnColor * Glow * (0.4 + 0.6 * k), 1.0);
        return;
    }

    // 还没烧到：让原实体原样显示，只在边缘留一点预热的暗红
    float warm = smoothstep(t + EdgeWidth * 2.0, t, threshold);
    fragColor = vec4(BurnColor * warm * 0.6, warm * 0.5);
}
