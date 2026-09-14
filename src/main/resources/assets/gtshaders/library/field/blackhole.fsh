// 黑洞 / Black Hole
// 引力透镜的近似：把采样点沿「指向奇点」的方向往回拉，拉的量与距离平方成反比。
// 三个必须的细节，缺一个就不像黑洞：
//   1. 弯折量要 clamp 在到中心的距离以内，否则近处会翻面，糊成一团噪点；
//   2. 事件视界内必须是纯黑，不是"很暗"——有一丝底色就露馅了；
//   3. 吸积盘要比视界亮得多，光都是被甩到边上的。
//
// 这个效果演示了「非全屏覆盖」的两条路：奇点位置可以不是固定坐标，而是世界里某个东西
// 在屏幕上的投影。两条路的取舍见 AnchorMode 的说明。
//
// 最典型的用法：在编辑器「锚点」页新增一条绑定，来源选「实体类型」、触发选「死亡时」、
// 锚定方式选「钉在事件坐标」，然后把 AnchorMode 设成 1 —— 于是谁死在哪，黑洞就在哪坍缩。
//
// [en_us]
// A black hole that bends the picture around a singularity.
// It approximates gravitational lensing: sample points are pulled back toward the singularity, by an amount
// inversely proportional to the squared distance.
// Three details are required, and missing any one of them breaks the look:
//   1. Clamp the bend to the distance from the center, or nearby pixels flip over into a smear of noise.
//   2. Inside the event horizon must be pure black, not "very dark". Any trace of the background gives it away.
//   3. The accretion disk must be far brighter than the horizon, because all the light is flung to the edge.
//
// This effect shows the two routes to an effect that doesn't cover the whole screen: the singularity does not
// have to be a fixed coordinate, it can be the on-screen projection of something in the world. See AnchorMode
// for the trade-offs between the two routes.
//
// Typical use: on the editor's "Anchors" tab, add a binding with Source "Entity type", Trigger "On death" and
// Anchoring "Stick to event position", then set AnchorMode to 1. Now wherever something dies, a black hole
// collapses on that spot.
//
// @param name=Center type=vec2 min=0 max=1 default=0.5,0.5 zh_cn=奇点位置 en_us=Center
// @param name=AnchorMode type=int min=0 max=2 default=0 zh_cn=锚点来源 en_us=Anchor Source desc_zh_cn=0=用上面的固定坐标；1=世界锚点，跟着实体或事件走（需要装本 mod）；2=VanillaDI 数据探针，纯资源包也能用但要自行搭建编码端 desc_en_us=0 = fixed position above; 1 = world anchor, follows entities and events (needs this mod); 2 = VanillaDI data probe, works in a plain resource pack but you must build the encoder side
// @param name=Radius type=float min=0.01 max=0.5 default=0.07 zh_cn=视界半径 en_us=Horizon Radius
// @param name=Mass type=float min=0 max=0.2 default=0.045 zh_cn=引力强度 en_us=Mass
// @param name=Spin type=float min=-4 max=4 default=1.2 zh_cn=拖曳自旋 en_us=Frame Drag
// @param name=DiskColor type=color3 default=#FFB347 zh_cn=吸积盘色 en_us=Disk Color
// @param name=DiskWidth type=float min=0.01 max=0.5 default=0.09 zh_cn=吸积盘宽度 en_us=Disk Width
// @param name=AnchorSlot type=anchor min=0 max=7 default=0 zh_cn=锚点绑定 en_us=Anchor Binding desc_zh_cn=读哪一条锚点绑定。选的是绑定本身而不是槽位号，之后增删、排序绑定都不会指错 desc_en_us=Which anchor binding to read. Stores the binding itself, not a slot number, so it survives reordering

void main() {
    vec2 asp = vec2(OutSize.x / max(OutSize.y, 1.0), 1.0);

    vec2 center = Center;
    float radius = Radius;
    float mass = Mass;
    // 整个效果的总闸。锚点无效时它是 0，于是什么都不画——而不是退回屏幕中央：
    // 事件型锚点大部分时间是灭的，退回中央会让屏幕正中莫名其妙常驻一个黑洞。
    //
    // 用乘法闸而不是把 radius 归零，是因为下面那两句 smoothstep 的两个边界都由 radius 算出来，
    // radius=0 会让它们退化成 smoothstep(0,0,r)——边界相等在 GLSL 里是未定义的（要除以零），
    // 实测在部分驱动上整屏变黑。半径必须始终保持正数。
    float gate = 1.0;
    if (AnchorMode == 1) {
        // 世界锚点：mod 每帧把实体位置投影进 uniform。半径直接用投影出来的屏幕半径，
        // 于是走远了黑洞会自然变小——这一点探针那条路做不到，它没有距离信息
        if (gtAnchorValid(AnchorSlot)) {
            center = gtAnchorUV(AnchorSlot);
            radius = max(Radius * 0.25, gtAnchorRadius(AnchorSlot));
            // 强度带着缓动曲线，可见度在目标被墙挡住时归零。两个都要乘：
            // 只乘强度的话，黑洞会隔着墙照样吸
            gate = gtAnchorStrength(AnchorSlot) * gtAnchorVisible(AnchorSlot);
            mass = Mass * gate;
        } else {
            gate = 0.0;
            mass = 0.0;
        }
    } else if (AnchorMode == 2) {
        vec4 probe = gtProbe(0);
        if (gtProbeValid(probe)) {
            center = probe.xy;
            radius = Radius * probe.z * 2.0;
            mass = Mass * probe.w;
        }
    }

    // 在等比空间里算距离，否则宽屏上黑洞会被拉成椭圆
    vec2 d = (texCoord - center) * asp;
    float r = length(d);
    vec2 dir = r > 1e-5 ? d / r : vec2(0.0);

    // 弯折量与 r^2 成反比，再夹到 r 之内——越过 r 就意味着采样点跨到了中心另一侧
    float bend = min(mass / max(r * r, 1e-4) * 0.02, r * 0.98);

    // 参考系拖曳：越靠近转得越快，吸积盘的螺旋感全靠它
    float ang = Spin * 0.06 / max(r, 0.02);
    float cs = cos(ang);
    float sn = sin(ang);
    vec2 rotated = vec2(dir.x * cs - dir.y * sn, dir.x * sn + dir.y * cs);

    vec2 uv = texCoord - rotated * bend / asp;
    vec3 col = texture(InSampler, uv).rgb;

    // 吸积盘：视界外一圈，亮度随时间沿角向流动
    float disk = smoothstep(radius + DiskWidth, radius, r) * smoothstep(radius * 0.95, radius, r);
    float swirl = 0.6 + 0.4 * sin(atan(d.y, d.x) * 3.0 + GTTime * Spin * 2.0 - r * 30.0);
    col += DiskColor * disk * swirl * 2.2 * gate;

    // 事件视界：纯黑，边缘只留极窄的一圈过渡免得出现硬锯齿。
    // 用 mix 把整块过渡按 gate 淡出，gate=0 时这一句等于乘 1.0，画面原样通过
    col *= mix(1.0, smoothstep(radius * 0.92, radius, r), gate);

    fragColor = vec4(col, 1.0);
}
