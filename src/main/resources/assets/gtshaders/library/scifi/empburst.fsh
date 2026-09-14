// EMP 脉冲 / EMP Burst
// 一圈电磁冲击从中心炸开，环内的画面被"打坏"：掉色、错行、反相，环过去之后慢慢恢复。
// 破坏程度按到环心的距离衰减，所以恢复是从内往外自然发生的，不需要单独写一段恢复动画。
//
// [en_us]
// An EMP shock ring that corrupts the picture as it spreads.
// A ring of electromagnetic shock bursts out from the center, and the picture inside the ring gets "broken":
// drained color, torn lines and inversion, slowly recovering after the ring has passed.
// Damage falls off with distance behind the ring, so recovery naturally happens from the inside out, with no
// separate recovery animation needed.
//
// @param name=Center type=vec2 min=0 max=1 default=0.5,0.5 zh_cn=爆点 en_us=Epicenter
// @param name=Period type=float min=0.5 max=20 default=4 zh_cn=触发周期(秒) en_us=Period
// @param name=WaveSpeed type=float min=0.2 max=5 default=1.1 zh_cn=扩散速度 en_us=Wave Speed
// @param name=Recover type=float min=0.5 max=8 default=2.2 zh_cn=恢复速度 en_us=Recovery

// @group 冲击环 / Shock Ring
// @param name=RingColor type=color3 default=#8FD8FF zh_cn=环色 en_us=Ring Color
// @param name=RingWidth type=float min=0.01 max=0.4 default=0.07 zh_cn=环宽 en_us=Ring Width
// @param name=RingGain type=float min=0 max=6 default=2.6 zh_cn=环亮度 en_us=Ring Gain
// @param name=Push type=float min=0 max=0.15 default=0.03 zh_cn=冲击位移 en_us=Displacement

// @group 破坏 / Corruption
// @param name=Tear type=float min=0 max=0.2 default=0.05 zh_cn=行错位 en_us=Line Tear
// @param name=Invert type=float min=0 max=1 default=0.5 zh_cn=反相程度 en_us=Inversion
// @param name=Drain type=float min=0 max=1 default=0.7 zh_cn=掉色 en_us=Color Drain

float hash11(float p) {
    return fract(sin(p * 88.11) * 41917.317);
}

void main() {
    vec2 asp = vec2(OutSize.x / max(OutSize.y, 1.0), 1.0);
    float period = max(Period, 0.2);
    float age = mod(GTTime, period);

    // 半径归一化到「画面正中=0，四角=1」。除数必须是<b>半</b>对角线：
    // asp=(w/h,1)，角落处 |(texCoord-0.5)*asp| 只有 length(asp) 的一半，
    // 拿整条对角线去除的话 r 最大只到 0.5——所有按半径推进的动画都只走完一半就没了，
    // 按半径衰减的效果也只发挥出一半强度。
    float r = length((texCoord - Center) * asp) / max(length(asp) * 0.5, 1e-4);
    float front = age * WaveSpeed;

    // 环本身
    float ring = smoothstep(RingWidth, 0.0, abs(r - front));
    // 环内的破坏：越靠近环越严重，且随时间恢复
    float inside = step(r, front);
    float damage = inside * exp(-(front - r) * Recover) ;

    vec2 dir = normalize((texCoord - Center) * asp + 1e-6);
    vec2 uv = texCoord + dir * Push * ring;
    // 行错位：每一行按行号随机横移
    float row = floor(texCoord.y * 120.0);
    uv.x += (hash11(row + floor(GTTime * 24.0)) - 0.5) * Tear * damage;
    uv = clamp(uv, vec2(0.0), vec2(1.0));

    vec3 col = texture(InSampler, uv).rgb;
    float g = dot(col, vec3(0.2126, 0.7152, 0.0722));
    col = mix(col, vec3(g), Drain * damage);
    col = mix(col, 1.0 - col, Invert * damage * step(0.5, hash11(row * 2.7 + floor(GTTime * 12.0))));

    col += RingColor * ring * RingGain;
    fragColor = vec4(col, 1.0);
}
