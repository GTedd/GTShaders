// 刀光拖尾 / Blade Flash
// 手里那把刀在过去零点几秒扫过的那片区域，按时间衰减成一条带子。
// 形状不是画上去的——它<b>就是</b>刀刃线段的运动轨迹本身，所以挥得快条带就宽、
// 挥得慢自然收窄，转身带出来的弧线也是真的弧线。不需要任何"挥砍检测"，
// 也不会出现"动画早放完了、光还挂在那儿"。
//
// 数据由 mod 每帧从第一人称主手的模型矩阵直接算出（gtTrail 系列）。思路来自
// YangMao-Minister/BladeFlash，但那是纯资源包，只能把刀刃顶点编码进屏幕底行像素、
// 再靠一条 persistent 链逐帧移位攒历史、还要按相机位移反向补偿；这里走 uniform，
// 那三样连同它们的精度损失一起不存在。<b>没装 mod 时（例如导出成纯资源包）不显示。</b>
//
// [en_us]
// Blade trail traced from your held weapon's real sweep.
// It is the area the blade in your hand swept over in the last fraction of a second, fading over time into a band.
// The shape isn't drawn: it <b>is</b> the motion path of the blade segment itself, so a fast swing makes a wide
// band, a slow one narrows naturally, and the arc from turning around is a real arc. No "swing detection" is
// needed, and you never get "the animation ended long ago but the glow is still hanging there".
//
// The mod computes the data every frame straight from the first-person main-hand model matrix (the gtTrail*
// uniforms). The idea comes from YangMao-Minister/BladeFlash, but that is a pure resource pack: it has to encode
// the blade vertices into the bottom row of screen pixels, shift them frame by frame through a persistent chain
// to build up history, and compensate for camera movement in reverse. Here it all goes through uniforms, so those
// three steps and their precision loss simply don't exist.
// <b>Nothing shows without the mod (for example when exported as a plain resource pack).</b>
//
// @param name=Duration type=float min=0.05 max=0.5 default=0.22 zh_cn=拖尾时长(秒) en_us=Trail Duration desc_zh_cn=留多久。超过 0.35 秒就开始像残影而不是刀光了 desc_en_us=How long the trail lingers; past 0.35s it reads as a ghost rather than a slash
// @param name=CoreColor type=color3 default=#FFFFFF zh_cn=内核色 en_us=Core Color
// @param name=EdgeColor type=color3 default=#4FC3FF zh_cn=外缘色 en_us=Edge Color
// @param name=Intensity type=float min=0 max=4 default=1.8 zh_cn=亮度 en_us=Intensity
// @param name=Fade type=float min=0.5 max=4 default=1.8 zh_cn=衰减陡度 en_us=Fade Curve desc_zh_cn=越大尾巴收得越急。1 是线性 desc_en_us=Higher means the tail dies faster; 1 is linear
// @param name=TipBias type=float min=0 max=1 default=0.6 zh_cn=刀尖偏重 en_us=Tip Bias desc_zh_cn=刀尖扫得比刀根快，光也该更亮。0 是整条一样亮 desc_en_us=The tip sweeps faster than the hilt so it should glow brighter; 0 is uniform
// @param name=SpeedGate type=float min=0 max=0.06 default=0.012 zh_cn=起光速度 en_us=Speed Gate desc_zh_cn=慢过这个速度就不出光。走路晃动大约 0.002，真挥一刀在 0.05 以上 desc_en_us=Nothing shows below this sweep speed; idle bob is about 0.002
// @param name=Distort type=float min=0 max=1 default=0.35 zh_cn=空间扭曲 en_us=Distortion
// @param name=Glow type=float min=0 max=1 default=0.4 zh_cn=外发光 en_us=Glow

// 一次命中的强度。抽成函数是因为外发光要在旁边的坐标上再算两次，
// 三处的曲线必须完全一样，否则光晕会和本体错开一圈
float gtfBand(vec4 t) {
    float age01 = clamp(t.z / max(Duration, 1e-4), 0.0, 1.0);
    // 起光门限用<b>速度</b>而不是挥砍动画：抡起来砸方块、被击退甩出去的那一下都算数，
    // 而拿 gtTrailSwing() 当判据的话它们全都不出光
    float gate = smoothstep(SpeedGate * 0.4, max(SpeedGate, 1e-4), t.w);
    float body = t.x * gate * pow(1.0 - age01, Fade);
    return body * mix(1.0, t.y, TipBias);
}

void main() {
    vec4 t = gtTrailAt(Duration);
    float band = gtfBand(t);

    // 扭曲与外发光的方向取<b>最新那一帧刀身</b>的法线，整条带子共用一个方向。
    // 用 dFdx(age) 求梯度更"正确"，但条带边界上 age 会从有值突变成 0，
    // 导数在那一圈直接炸开——表现是刀光边上镶了一圈乱码。
    // 先换到等比空间求法线再换回来，宽屏上偏移方向才是真的垂直于刀身
    vec2 asp = vec2(OutSize.x / max(OutSize.y, 1.0), 1.0);
    vec2 blade = (gtTrailTip(0) - gtTrailRoot(0)) * asp;
    vec2 nrm = length(blade) > 1e-5
        ? normalize(vec2(-blade.y, blade.x)) / asp
        : vec2(0.0, 1.0);

    // 外发光：沿法线往两边各探一次。Glow 是 uniform，为 0 时整支线程一起跳过这段，
    // 也就不会为一个关掉的选项多跑两遍 24 段遍历
    float halo = 0.0;
    if (Glow > 0.001) {
        float w = Glow * 0.02;
        halo = max(gtfBand(gtTrailAtUV(texCoord + nrm * w, Duration)),
                   gtfBand(gtTrailAtUV(texCoord - nrm * w, Duration))) * 0.55;
    }

    // 高温空气那种推开感。偏移量按纵向 UV 算，换分辨率时扭曲宽度不会跟着变
    float push = (band + halo) * Distort * 0.02;
    vec3 src = texture(InSampler, texCoord + nrm * push).rgb;

    // 内核到外缘：age 就是条带的横向坐标，band 已经把它揉进去了，
    // 所以直接拿 band 当渐变轴，色带天然贴合条带的形状
    vec3 tint = mix(EdgeColor, CoreColor, pow(band, 2.2));
    vec3 col = src + tint * (band + halo * 0.6) * Intensity;

    fragColor = vec4(col, 1.0);
}
