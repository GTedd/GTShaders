// 领域场 / Domain Field
// 一个有边界的空间：域内换一套配色、域外原样，边界上是流动的能量壁。
// 「域内域外用两套调色」比单纯加个圈重要得多——观众要一眼看出自己在里面还是外面。
//
// 同样支持从探针取锚点，让领域跟着世界里的实体走而不是钉在屏幕中央。
//
// [en_us]
// A bounded field that recolors everything inside it.
// Inside the field the scene gets a different palette, outside it stays as is, and the boundary is a flowing
// energy wall. Using two palettes for inside and outside matters far more than just drawing a ring: viewers must
// see at a glance whether they are inside or outside.
//
// It also supports taking the anchor from a probe, so the field follows an entity in the world instead of being
// pinned to the center of the screen.
//
// @param name=Center type=vec2 min=0 max=1 default=0.5,0.5 zh_cn=领域中心 en_us=Center
// @param name=AnchorMode type=int min=0 max=2 default=0 zh_cn=锚点来源 en_us=Anchor Source desc_zh_cn=0=用上面的固定坐标；1=世界锚点，跟着实体或事件走（需要装本 mod）；2=VanillaDI 数据探针，纯资源包也能用但要自行搭建编码端 desc_en_us=0 = fixed position above; 1 = world anchor, follows entities and events (needs this mod); 2 = VanillaDI data probe, works in a plain resource pack but you must build the encoder side
// @param name=Radius type=float min=0.05 max=1.2 default=0.42 zh_cn=领域半径 en_us=Radius
// @param name=WallColor type=color3 default=#B06BFF zh_cn=壁色 en_us=Wall Color
// @param name=InnerTint type=color3 default=#2A1240 zh_cn=域内色调 en_us=Inner Tint
// @param name=InnerMix type=float min=0 max=1 default=0.45 zh_cn=域内染色 en_us=Inner Blend
// @param name=WallWidth type=float min=0.005 max=0.2 default=0.035 zh_cn=壁厚 en_us=Wall Width
// @param name=Flow type=float min=0 max=6 default=1.8 zh_cn=能量流速 en_us=Flow Speed
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
    vec2 asp = vec2(OutSize.x / max(OutSize.y, 1.0), 1.0);

    vec2 center = Center;
    float radius = Radius;
    // 领域是持续性的，所以最适合配「一直有效」的锚点绑定：领域跟着实体走。
    // 锚点无效时把半径收成 0 让领域整个消失，而不是退回屏幕中央
    float gate = 1.0;
    if (AnchorMode == 1) {
        if (gtAnchorValid(AnchorSlot)) {
            center = gtAnchorUV(AnchorSlot);
            radius = max(Radius * 0.25, gtAnchorRadius(AnchorSlot) * 2.0);
            gate = gtAnchorStrength(AnchorSlot);
        } else {
            gate = 0.0;
        }
    } else if (AnchorMode == 2) {
        vec4 probe = gtProbe(0);
        if (gtProbeValid(probe)) {
            center = probe.xy;
            radius = Radius * probe.z * 2.0;
        }
    }

    vec2 d = (texCoord - center) * asp;
    float r = length(d);
    float ang = atan(d.y, d.x);

    // 边界不是正圆：叠一层沿角向流动的噪声，才像"场"而不像贴了个圆形贴纸。
    // 角度<b>必须先映射到单位圆上</b>再采噪声：直接把 atan 的值当输入的话，
    // θ 在负 x 轴上从 +π 跳到 -π，两侧取到毫不相干的噪声值——
    // 边界会在中心正左方裂开一道缝。噪声不是周期函数，绕一圈回不到原处
    float wobble = (noise(vec2(cos(ang), sin(ang)) * 2.0 + GTTime * Flow * 0.3) - 0.5)
                   * radius * 0.08;
    float edge = radius + wobble;

    vec3 src = texture(InSampler, texCoord).rgb;
    float luma = dot(src, vec3(0.2126, 0.7152, 0.0722));

    // 域内：压暗 + 染色 + 提高对比，跟域外拉开层次
    vec3 inner = mix(src, InnerTint + vec3(luma) * 0.6, InnerMix);
    inner = clamp((inner - 0.5) * 1.15 + 0.5, 0.0, 1.0);

    float inside = smoothstep(edge, edge - WallWidth, r) * gate;
    vec3 col = mix(src, inner, inside);

    // 能量壁：以边界为中心的一条窄带，带上有沿角向跑的亮纹
    float wall = smoothstep(WallWidth, 0.0, abs(r - edge)) * gate;
    float streak = 0.55 + 0.45 * sin(ang * 8.0 + GTTime * Flow);
    col += WallColor * wall * (0.8 + 0.8 * streak);

    fragColor = vec4(col, 1.0);
}
