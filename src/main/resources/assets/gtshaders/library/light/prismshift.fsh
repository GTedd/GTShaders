// 棱镜色散 / Prism Shift
// 画面被拆成多重彩色副本沿同一条线错开，边缘因此镀上一道彩虹，像隔着棱镜看。
// 错开量随半径增加：中心几乎不拆，越往外拆得越开——这是真实镜头色差的分布，
// 而不是整幅画面平移一遍。
//
// 用 7 个采样点在光谱上均匀取样，而不是简单的 RGB 三路：三路会出现明显的红/蓝重影，
// 多路取样出来的才是连续的彩虹。
//
// [en_us]
// Rainbow-fringed edges, like looking through a prism.
// The picture is split into several colored copies offset along the same line, which coats edges in a rainbow.
// The offset grows with radius: the center barely splits and the outer areas split further apart. That is how
// real lens chromatic aberration is distributed, rather than shifting the whole picture by the same amount.
//
// It takes 7 samples spread evenly across the spectrum instead of three plain RGB taps: three taps show
// obvious red/blue ghosting, and only many samples give a continuous rainbow.
//
// @param name=Amount type=float min=0 max=0.08 default=0.014 zh_cn=色散量 en_us=Dispersion
// @param name=Center type=vec2 min=0 max=1 default=0.5,0.5 zh_cn=中心 en_us=Center
// @param name=Falloff type=float min=0.5 max=4 default=1.8 zh_cn=径向衰减 en_us=Radial Falloff
// @param name=Tangential type=float min=0 max=1 default=0 zh_cn=改为切向 en_us=Tangential desc_zh_cn=改成沿圆周方向拆分，得到旋转式的色散 desc_en_us=Split along the tangent instead, for a swirl-style dispersion

// @group 动态 / Motion
// @param name=Breathe type=float min=0 max=1 default=0.3 zh_cn=呼吸起伏 en_us=Breathe
// @param name=Rate type=float min=0.05 max=3 default=0.5 zh_cn=起伏频率 en_us=Breathe Rate

// @group 外观 / Look
// @param name=Saturate type=float min=0 max=3 default=1.4 zh_cn=彩边饱和 en_us=Fringe Saturation
// @param name=Vignette type=float min=0 max=1 default=0.2 zh_cn=暗角 en_us=Vignette

// 把 0..1 映射成一段彩虹权重。三通道的和大致守恒，所以整体亮度不会漂
vec3 spectrum(float x) {
    return clamp(vec3(1.5 - abs(4.0 * x - 3.0),
                      1.5 - abs(4.0 * x - 2.0),
                      1.5 - abs(4.0 * x - 1.0)), 0.0, 1.0);
}

void main() {
    vec2 asp = vec2(OutSize.x / max(OutSize.y, 1.0), 1.0);
    vec2 d = (texCoord - Center) * asp;
    // 半径归一化到「画面正中=0，四角=1」。除数必须是<b>半</b>对角线：
    // asp=(w/h,1)，角落处 |(texCoord-0.5)*asp| 只有 length(asp) 的一半，
    // 拿整条对角线去除的话 r 最大只到 0.5——所有按半径推进的动画都只走完一半就没了，
    // 按半径衰减的效果也只发挥出一半强度。
    float r = length(d) / max(length(asp) * 0.5, 1e-4);

    vec2 dir = normalize(d + 1e-6);
    if (Tangential > 0.5) {
        dir = vec2(-dir.y, dir.x);
    }
    dir /= vec2(max(asp.x, 1e-3), 1.0);

    float breathe = 1.0 + sin(GTTime * Rate * 6.2831853) * Breathe * 0.5;
    float reach = Amount * pow(clamp(r, 0.0, 1.0), Falloff) * breathe;

    vec3 col = vec3(0.0);
    vec3 wsum = vec3(0.0);
    for (int i = 0; i < 7; i++) {
        float x = float(i) / 6.0;
        vec3 w = spectrum(x);
        vec2 uv = clamp(texCoord + dir * (x - 0.5) * 2.0 * reach, vec2(0.0), vec2(1.0));
        col += texture(InSampler, uv).rgb * w;
        wsum += w;
    }
    col /= max(wsum, vec3(1e-4));

    float g = dot(col, vec3(0.2126, 0.7152, 0.0722));
    col = g + (col - g) * Saturate;
    col *= 1.0 - Vignette * smoothstep(0.45, 1.15, r);

    fragColor = vec4(col, 1.0);
}
