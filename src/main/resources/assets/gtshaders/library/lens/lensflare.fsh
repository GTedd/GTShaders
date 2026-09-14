// 镜头光晕 / Lens Flare
// 鬼影（ghost）是光在镜片组之间来回反射形成的，所以它们必然沿着
// 「光源 → 画面中心」这条线对称分布在中心另一侧——这是判断光晕真假的关键。
// 随便撒几个光圈就穿帮了。
//
// [en_us]
// Lens flare with ghosts mirrored across the center.
// Ghosts are formed by light bouncing back and forth between lens elements, so they must lie along the
// "light source → screen center" line, mirrored on the far side of the center. That is the key to telling a
// real flare from a fake one. Scatter a few random rings and the illusion falls apart.
//
// @param name=Source type=vec2 min=0 max=1 default=0.72,0.78 zh_cn=光源位置 en_us=Light Source
// @param name=Ghosts type=int min=0 max=8 default=5 zh_cn=鬼影数量 en_us=Ghost Count
// @param name=Dispersal type=float min=0 max=1 default=0.32 zh_cn=鬼影间距 en_us=Ghost Spacing
// @param name=FlareColor type=color3 default=#FFD9A0 zh_cn=光晕色 en_us=Flare Color
// @param name=Halo type=float min=0 max=1 default=0.4 zh_cn=光环 en_us=Halo
// @param name=Streak type=float min=0 max=1 default=0.5 zh_cn=横向星芒 en_us=Anamorphic Streak
// @param name=Intensity type=float min=0 max=2 default=0.8 zh_cn=强度 en_us=Intensity

void main() {
    vec2 asp = vec2(OutSize.x / max(OutSize.y, 1.0), 1.0);
    vec3 col = texture(InSampler, texCoord).rgb;

    vec2 toCenter = vec2(0.5) - Source;
    vec2 d = (texCoord - Source) * asp;
    float distToSource = length(d);

    // 主光斑
    float core = exp(-distToSource * distToSource * 90.0);
    float glow = exp(-distToSource * 6.0) * 0.35;

    // 鬼影：沿光源→中心的连线等距排布，越过中心继续往另一侧延伸
    float ghosts = 0.0;
    for (int i = 1; i <= 8; i++) {
        if (i > Ghosts) {
            break;
        }
        vec2 p = Source + toCenter * (float(i) * Dispersal);
        float gd = length((texCoord - p) * asp);
        // 每个鬼影是个软光圈：中间略暗、边缘一圈亮，这是光阑成像的样子
        float radius = 0.035 + 0.02 * float(i);
        float ring = smoothstep(radius, radius * 0.55, gd) * smoothstep(radius * 0.25, radius * 0.6, gd);
        ghosts += ring / float(i);
    }

    // 光环：以画面中心为圆心的一圈彩虹边
    float haloR = length((texCoord - 0.5) * asp);
    float halo = smoothstep(0.06, 0.0, abs(haloR - 0.34)) * Halo;

    // 变形宽银幕镜头的横向星芒
    float streak = exp(-abs(d.y) * 260.0) * exp(-abs(d.x) * 2.5) * Streak;

    vec3 flare = FlareColor * (core * 3.0 + glow + ghosts * 0.6 + streak * 1.5)
               + vec3(0.4, 0.7, 1.0) * halo * 0.5;
    fragColor = vec4(col + flare * Intensity, 1.0);
}
