// 神圣光柱 / Holy Beam
// 一道垂直光柱落下来，柱内有缓慢上浮的尘埃与流动的亮纹，柱底晕开一圈地面辉光。
// 光柱越靠下越宽——真实的体积光是锥形的，画成等宽的一条只会像贴了张条形贴图。
//
// 打开锚点后柱子会跟着世界里的实体或事件走（需要装本 mod）；没有锚点时退回固定横坐标，
// 导出成纯资源包也能用。
//
// [en_us]
// A vertical beam of holy light shining down.
// A vertical pillar of light comes down, with slowly rising dust and flowing bright streaks inside it and a
// ring of glow spreading over the ground at its base. The beam widens toward the bottom: real volumetric
// light is cone-shaped, and a constant-width beam just looks like a strip texture pasted on.
//
// With the anchor enabled, the beam follows an entity or event in the world (requires this mod installed).
// Without an anchor it falls back to a fixed horizontal position, so it also works when exported as a plain
// resource pack.
//
// @param name=Position type=float min=0 max=1 default=0.5 zh_cn=柱子横向位置 en_us=Beam X
// @param name=UseAnchor type=bool default=0 zh_cn=跟随世界锚点 en_us=Follow Anchor desc_zh_cn=开启后柱子落在下面选的那条锚点绑定上；没有 mod 时自动退回上面的固定位置 desc_en_us=Puts the beam on the selected anchor binding; falls back to the fixed position without the mod

// @group 形状 / Shape
// @param name=Width type=float min=0.01 max=0.6 default=0.09 zh_cn=柱宽 en_us=Beam Width
// @param name=Spread type=float min=0 max=3 default=1.2 zh_cn=下端张开 en_us=Cone Spread
// @param name=Softness type=float min=0.1 max=4 default=1.6 zh_cn=边缘柔和度 en_us=Softness

// @group 外观 / Look
// @param name=BeamColor type=color3 default=#FFF3C9 zh_cn=光柱色 en_us=Beam Color
// @param name=Gain type=float min=0 max=5 default=1.6 zh_cn=亮度 en_us=Gain
// @param name=Flow type=float min=0 max=4 default=0.8 zh_cn=纹理流动 en_us=Flow Speed
// @param name=Dust type=float min=0 max=1 default=0.5 zh_cn=尘埃 en_us=Dust Motes
// @param name=GroundGlow type=float min=0 max=1 default=0.5 zh_cn=地面辉光 en_us=Ground Glow
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
    vec3 col = texture(InSampler, texCoord).rgb;

    float cx = Position;
    float strength = 1.0;
    if (UseAnchor > 0.5 && gtAnchorValid(AnchorSlot)) {
        cx = gtAnchorUV(AnchorSlot).x;
        strength = gtAnchorStrength(AnchorSlot) * gtAnchorVisible(AnchorSlot);
    }

    // 锥形：越靠下越宽
    float down = 1.0 - texCoord.y;
    float halfW = Width * (1.0 + down * Spread) * 0.5;
    float dx = abs(texCoord.x - cx) * asp.x;
    float shape = pow(clamp(1.0 - dx / max(halfW * asp.x, 1e-4), 0.0, 1.0), Softness);

    // 柱内的流动亮纹：竖向拉长的噪声，缓慢往下走
    float flow = noise(vec2(texCoord.x * 40.0, texCoord.y * 5.0 - GTTime * Flow));
    float body = shape * (0.65 + 0.35 * flow);
    // 上端稍微淡出，让光柱像从画面外照进来
    body *= smoothstep(1.05, 0.55, texCoord.y) * 0.5 + 0.5;

    col += BeamColor * body * Gain * strength;

    // 尘埃：柱内缓慢上浮的小亮点
    if (Dust > 0.001) {
        vec2 dp = vec2(texCoord.x * 90.0, texCoord.y * 60.0 + GTTime * 1.2);
        vec2 di = floor(dp);
        float mote = step(0.972, hash(di)) * smoothstep(0.5, 0.1, length(fract(dp) - 0.5));
        col += BeamColor * mote * Dust * shape * 2.5 * strength;
    }

    // 柱底辉光：一个压扁的椭圆，模拟光打在地上
    float gx = (texCoord.x - cx) * asp.x;
    float gy = (texCoord.y - 0.06) * 3.2;
    float glow = exp(-(gx * gx / max(halfW * halfW * 4.0, 1e-5) + gy * gy));
    col += BeamColor * glow * GroundGlow * Gain * 0.6 * strength;

    fragColor = vec4(col, 1.0);
}
