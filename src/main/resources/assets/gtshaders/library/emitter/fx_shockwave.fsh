// 载体冲击波 / Emitter Shockwave
// 从载体炸出去的一圈波：屏幕扭曲 + 白色描边 + 全屏白闪。半径由生命周期推进，
// 所以配事件型触发器（「死亡时」「受伤时」）挂上去就自己会炸，不用管时间。
//
// 原作 shock_wave.fsh 是<b>真的在世界空间里做射线-球求交</b>，拿深度重建每个片元的
// 世界坐标再和球心比距离——于是它能被地形正确遮挡，球面也有真实的透视变形。
// GTShaders 的后处理链拿不到深度缓冲（PostEffectJsonBuilder 只生成 main↔swap 乒乓），
// 所以这里退成屏幕空间的圆环：<b>不会被墙挡住</b>，正对着看没区别，
// 贴着地面看少了那点球面压扁。换来的是它能在任何一层里直接用。
//
// 半径拿 gtAnchorRadius 当基准——那是载体的世界半径投影出来的屏幕半径，
// 所以走远了波纹自然变小，而不是永远占屏幕上同样大的一圈。
//
// [en_us]
// A shockwave ring bursting out from an emitter.
// A wave blasting out from the emitter: screen distortion + white outline + full-screen white flash. The radius
// is driven by the lifetime, so attach it with an event trigger ("On death", "On hurt") and it bursts on its
// own, with no timing to manage.
//
// The original shock_wave.fsh does <b>real ray-sphere intersection in world space</b>: it reconstructs each
// fragment's world position from depth and compares its distance to the sphere center. That lets terrain
// occlude it correctly and gives the sphere real perspective distortion. The GTShaders post-processing chain
// has no access to the depth buffer (PostEffectJsonBuilder only generates main↔swap ping-pong), so this falls
// back to a screen-space ring: it <b>is not blocked by walls</b>. Seen head-on there is no difference; seen
// along the ground it lacks that slight flattening of the sphere. In exchange, it works directly in any layer.
//
// The radius is based on gtAnchorRadius, the emitter's world radius projected to a screen radius, so the ripple
// naturally shrinks as you move away instead of always taking up the same size ring on screen.
//
// @param name=Color type=color3 default=#FFFFFF zh_cn=波纹色 en_us=Ring Color
// @param name=Expand type=float min=0.5 max=20 default=6 zh_cn=扩张倍率 en_us=Expansion desc_zh_cn=生命周期走完时半径是载体半径的几倍 desc_en_us=Final radius as a multiple of the emitter radius
// @param name=Thickness type=float min=0.002 max=0.3 default=0.05 zh_cn=波纹厚度 en_us=Ring Thickness
// @param name=Distort type=float min=0 max=1 default=0.5 zh_cn=空间扭曲 en_us=Distortion
// @param name=Rim type=float min=0 max=4 default=1.5 zh_cn=描边亮度 en_us=Rim Brightness
// @param name=Flash type=float min=0 max=1 default=0.35 zh_cn=全屏白闪 en_us=Screen Flash desc_zh_cn=炸开瞬间压过整个画面的那一下。只在生命周期最前段出现 desc_en_us=The blown-out frame at detonation; only in the first slice of the lifetime
// @param name=Ease type=float min=0.2 max=2 default=0.55 zh_cn=扩张曲线 en_us=Expansion Curve desc_zh_cn=小于 1 是先快后慢（真实爆炸的样子），1 是匀速 desc_en_us=Below 1 starts fast then slows, like a real blast

void main() {
    vec2 asp = vec2(OutSize.x / max(OutSize.y, 1.0), 1.0);
    vec2 offset = vec2(0.0);
    vec3 add = vec3(0.0);
    float flash = 0.0;

    for (int i = 0; i < GT_ANCHOR_SLOTS; i++) {
        if (!gtAnchorValid(i)) {
            continue;
        }
        float life = clamp(gtAnchorLife(i), 0.0, 1.0);
        // 先快后慢。真实爆炸的波前是被空气拖住的，匀速扩张一眼就假
        float grow = pow(life, Ease);
        float radius = max(gtAnchorRadius(i), 0.004) * (1.0 + grow * Expand);
        float thick = max(Thickness * (1.0 - life * 0.6), 1e-4);

        vec2 d = gtAnchorDelta(i);
        float len = length(d);
        // 到波前的有符号距离。abs 之后就是「离这圈有多远」
        float band = abs(len - radius);
        float ring = 1.0 - smoothstep(0.0, thick, band);

        float k = gtAnchorStrength(i) * (1.0 + gtEmitterCustom1(i));

        // 扭曲：沿径向把画面推开，波前处最狠。方向用 d/len 而不是载体朝向——
        // 冲击波是各向同性的，它没有"正面"
        if (len > 1e-5) {
            offset += (d / len) * ring * Distort * thick * k / asp;
        }
        add += Color * ring * Rim * k;

        // 白闪只在最前面那一小段。用 1-life 的高次幂把它压到几帧之内，
        // 拖长了就不是"炸了一下"而是"开了个白色滤镜"
        flash += pow(max(1.0 - life * 4.0, 0.0), 2.0) * Flash * k;
    }

    vec3 src = texture(InSampler, texCoord + offset).rgb;
    vec3 col = src + add;
    col = mix(col, vec3(1.0), clamp(flash, 0.0, 1.0));
    fragColor = vec4(col, 1.0);
}
