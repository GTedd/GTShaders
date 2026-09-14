// 声呐扫描 / Sonar Ping
// 一圈扫描环掠过时，把画面里的轮廓「点亮」一瞬间，其余时间画面几乎全黑。
// 这是最直白的一个「非全屏覆盖」示例：整块屏幕都参与了运算，但真正被改动的
// 只有扫描环经过的那一薄圈——效果的位置由锚点决定，而不是铺满视野。
//
// 勾上 UseProbe 后，环心来自屏幕保留像素里编码的世界坐标，
// 于是扫描源可以是世界里的某个方块或实体（编码方式同 JNNGL/VanillaDI）。
//
// [en_us]
// A sonar ring that briefly lights up outlines as it sweeps.
// As a scan ring passes, it "lights up" the outlines in the picture for an instant, and the rest of the time the
// picture is almost black. This is the most straightforward example of an effect that doesn't cover the whole
// screen: the entire screen takes part in the computation, but only the thin band the ring passes over actually
// changes. Where the effect happens is decided by the anchor, instead of filling the view.
//
// With UseProbe checked, the ring center comes from a world position encoded in reserved screen pixels,
// so the scan source can be a block or entity in the world (encoded the same way as JNNGL/VanillaDI).
//
// @param name=Center type=vec2 min=0 max=1 default=0.5,0.5 zh_cn=扫描原点 en_us=Origin
// @param name=AnchorMode type=int min=0 max=2 default=0 zh_cn=锚点来源 en_us=Anchor Source desc_zh_cn=0=用上面的固定坐标；1=世界锚点，跟着实体或事件走（需要装本 mod）；2=VanillaDI 数据探针，纯资源包也能用但要自行搭建编码端 desc_en_us=0 = fixed position above; 1 = world anchor, follows entities and events (needs this mod); 2 = VanillaDI data probe, works in a plain resource pack but you must build the encoder side
// @param name=Period type=float min=0.5 max=8 default=3 zh_cn=扫描周期(秒) en_us=Period
// @param name=RingColor type=color3 default=#5FFFB0 zh_cn=扫描色 en_us=Ring Color
// @param name=RingWidth type=float min=0.01 max=0.3 default=0.06 zh_cn=环宽 en_us=Ring Width
// @param name=Darkness type=float min=0 max=1 default=0.85 zh_cn=背景压暗 en_us=Background Dim
// @param name=EdgeGain type=float min=0 max=4 default=2 zh_cn=轮廓增益 en_us=Edge Gain
// @param name=Afterglow type=float min=0 max=1 default=0.4 zh_cn=余辉 en_us=Afterglow
// @param name=AnchorSlot type=anchor min=0 max=7 default=0 zh_cn=锚点绑定 en_us=Anchor Binding desc_zh_cn=读哪一条锚点绑定。选的是绑定本身而不是槽位号，之后增删、排序绑定都不会指错 desc_en_us=Which anchor binding to read. Stores the binding itself, not a slot number, so it survives reordering

float luma(vec3 c) {
    return dot(c, vec3(0.2126, 0.7152, 0.0722));
}

void main() {
    vec2 asp = vec2(OutSize.x / max(OutSize.y, 1.0), 1.0);

    vec2 center = Center;
    float gain = EdgeGain;
    if (AnchorMode == 1) {
        if (gtAnchorValid(AnchorSlot)) {
            center = gtAnchorUV(AnchorSlot);
            gain = EdgeGain * gtAnchorStrength(AnchorSlot);
        } else {
            // 扫描源没了就把增益归零，环还在但什么都照不出来——
            // 这比让环凭空消失更符合「声呐没有信号」的观感
            gain = 0.0;
        }
    } else if (AnchorMode == 2) {
        vec4 probe = gtProbe(0);
        if (gtProbeValid(probe)) {
            center = probe.xy;
            gain = EdgeGain * probe.w;
        }
    }

    vec3 src = texture(InSampler, texCoord).rgb;

    // 轮廓：中心差分的梯度模长。声呐只关心「有没有东西」，不关心它什么颜色
    vec2 texel = 1.0 / max(OutSize, vec2(1.0));
    float lx = luma(texture(InSampler, texCoord + vec2(texel.x, 0.0)).rgb)
             - luma(texture(InSampler, texCoord - vec2(texel.x, 0.0)).rgb);
    float ly = luma(texture(InSampler, texCoord + vec2(0.0, texel.y)).rgb)
             - luma(texture(InSampler, texCoord - vec2(0.0, texel.y)).rgb);
    float edge = clamp(length(vec2(lx, ly)) * gain * 6.0, 0.0, 1.0);

    float r = length((texCoord - center) * asp) / max(length(asp), 1e-4) * 2.0;
    float t = fract(GTTime / max(Period, 0.01));

    // 环本身
    float ring = smoothstep(RingWidth, 0.0, abs(r - t));
    // 余辉：环扫过之后轮廓还亮一小会儿，衰减到下一次扫描前刚好熄灭
    float trail = r < t ? exp(-(t - r) * 8.0) * Afterglow : 0.0;

    vec3 col = src * (1.0 - Darkness);
    col += RingColor * (ring * (0.35 + edge * 2.0) + edge * trail * 1.6);
    fragColor = vec4(col, 1.0);
}
