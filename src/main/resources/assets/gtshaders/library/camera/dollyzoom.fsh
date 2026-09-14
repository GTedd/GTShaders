// 滑动变焦 / Dolly Zoom
// 希区柯克那一招：中心保持不动，四周却在往里塌或者往外撑，空间被拉长又压扁。
// 真实的滑动变焦靠机位与焦距反向运动，后处理拿不到景深，就用「随半径变化的缩放」去逼近——
// 中心缩放系数固定为 1，越往外偏离越大，观感上就成立了。
//
// [en_us]
// Dolly zoom: the center holds still while the edges warp.
// The Hitchcock trick: the center stays put while the surroundings collapse inward or push outward, so space
// stretches and squashes. A real dolly zoom moves the camera and the focal length in opposite directions.
// Post-processing has no depth, so it is approximated with a scale that varies with radius: the scale factor is
// fixed at 1 at the center and deviates more the farther out you go, and that is enough to sell it.
//
// @param name=AutoPlay type=bool default=1 zh_cn=随时间来回 en_us=Auto Play
// @param name=Period type=float min=0.5 max=20 default=6 zh_cn=来回周期(秒) en_us=Period
// @param name=Manual type=float min=-1 max=1 default=0.4 zh_cn=手动位置 en_us=Manual

// @group 形变 / Warp
// @param name=Center type=vec2 min=0 max=1 default=0.5,0.5 zh_cn=不动点 en_us=Anchor Point
// @param name=Amount type=float min=0 max=1.5 default=0.55 zh_cn=形变量 en_us=Amount
// @param name=Falloff type=float min=0.5 max=4 default=2 zh_cn=衰减指数 en_us=Falloff desc_zh_cn=越大则形变越集中在画面边缘，中心区域越稳 desc_en_us=Higher keeps the centre steadier and pushes the warp outward

// @group 外观 / Look
// @param name=Chroma type=float min=0 max=0.03 default=0.005 zh_cn=边缘色散 en_us=Edge Chroma
// @param name=EdgeBlur type=float min=0 max=1 default=0.35 zh_cn=边缘拖影 en_us=Edge Smear

void main() {
    float k = AutoPlay > 0.5 ? sin(GTTime * 6.2831853 / max(Period, 0.1)) : Manual;
    k *= Amount;

    vec2 asp = vec2(OutSize.x / max(OutSize.y, 1.0), 1.0);
    vec2 d = (texCoord - Center) * asp;
    // 半径归一化到「画面正中=0，四角=1」。除数必须是<b>半</b>对角线：
    // asp=(w/h,1)，角落处 |(texCoord-0.5)*asp| 只有 length(asp) 的一半，
    // 拿整条对角线去除的话 r 最大只到 0.5——所有按半径推进的动画都只走完一半就没了，
    // 按半径衰减的效果也只发挥出一半强度。
    float r = length(d) / max(length(asp) * 0.5, 1e-4);

    // 中心系数恒为 1，随半径按幂次张开——这就是"中心不动、四周在动"
    float scale = 1.0 + k * pow(clamp(r, 0.0, 1.0), Falloff);

    vec2 base = (texCoord - Center) * scale + Center;
    vec2 outward = normalize(d + 1e-6) / max(asp.x, 1e-3);

    vec3 col = vec3(0.0);
    float total = 0.0;
    for (int i = 0; i < 5; i++) {
        float s = (float(i) / 4.0 - 0.5) * EdgeBlur * abs(k) * r * 0.08;
        float w = 1.0 - abs(float(i) / 4.0 - 0.5);
        vec2 uv = clamp(base + outward * s, vec2(0.0), vec2(1.0));
        float ca = Chroma * r * sign(k);
        col.r += texture(InSampler, clamp(uv + outward * ca, vec2(0.0), vec2(1.0))).r * w;
        col.g += texture(InSampler, uv).g * w;
        col.b += texture(InSampler, clamp(uv - outward * ca, vec2(0.0), vec2(1.0))).b * w;
        total += w;
    }
    fragColor = vec4(col / max(total, 1e-4), 1.0);
}
