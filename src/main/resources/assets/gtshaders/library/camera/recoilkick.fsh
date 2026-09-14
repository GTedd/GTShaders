// 后坐力 / Recoil Kick
// 按射速一发发地顶镜头：每一发画面往上抬一下、横向随机偏一点，然后被"压枪"拉回来。
// 连发时抬升会累积（越打越飘），这一点比单发的抖动更能让人认出这是在开枪。
//
// 枪口焰在画面下缘闪一下——只是一团亮斑，不画枪，所以任何视角下都能用。
//
// [en_us]
// Gun recoil that kicks the camera shot by shot.
// Each shot at the fire rate lifts the view, nudges it sideways at random, then "recoil control" pulls it back
// down. During a burst the climb accumulates (the longer you fire, the more it drifts), and that says "someone is
// shooting" far better than per-shot shake alone.
//
// A muzzle flash blinks at the bottom edge of the screen. It's just a bright blob with no gun drawn, so it works
// from any view.
//
// @param name=FireRate type=float min=1 max=20 default=8 zh_cn=射速(发/秒) en_us=Fire Rate
// @param name=BurstLen type=float min=1 max=60 default=12 zh_cn=连发弹数 en_us=Burst Length
// @param name=BurstGap type=float min=0 max=5 default=1.2 zh_cn=连发间隔(秒) en_us=Burst Gap

// @group 后坐 / Recoil
// @param name=KickUp type=float min=0 max=0.15 default=0.03 zh_cn=上抬量 en_us=Vertical Kick
// @param name=KickSide type=float min=0 max=0.1 default=0.012 zh_cn=横向散布 en_us=Horizontal Spread
// @param name=Climb type=float min=0 max=1 default=0.45 zh_cn=连发累积 en_us=Muzzle Climb
// @param name=Recover type=float min=2 max=40 default=16 zh_cn=回位速度 en_us=Recovery

// @group 枪口 / Muzzle
// @param name=FlashColor type=color3 default=#FFC24A zh_cn=枪口焰色 en_us=Muzzle Color
// @param name=FlashSize type=float min=0 max=0.6 default=0.22 zh_cn=枪口焰大小 en_us=Flash Size
// @param name=FlashGain type=float min=0 max=4 default=1.4 zh_cn=枪口焰亮度 en_us=Flash Gain

float hash11(float p) {
    return fract(sin(p * 33.9871) * 27183.2818);
}

void main() {
    float shotPeriod = 1.0 / max(FireRate, 0.1);
    float burst = max(BurstLen, 1.0);
    float cycle = burst * shotPeriod + BurstGap;
    float inCycle = mod(GTTime, max(cycle, 1e-3));

    float shot = floor(inCycle / shotPeriod);
    bool firing = shot < burst;
    float age = inCycle - shot * shotPeriod;

    float env = firing ? exp(-age * Recover) : 0.0;
    // 连发累积：这一梭子已经打出去几发，就多抬多少
    float climb = firing ? Climb * clamp(shot / burst, 0.0, 1.0) : 0.0;

    vec2 asp = vec2(OutSize.x / max(OutSize.y, 1.0), 1.0);
    float side = (hash11(shot * 7.31 + floor(GTTime / max(cycle, 1e-3)) * 3.7) - 0.5) * 2.0;

    vec2 off = vec2(side * KickSide * env / max(asp.x, 1e-3),
                    -(KickUp * env + KickUp * climb));
    vec2 uv = clamp((texCoord - 0.5) / 1.02 + 0.5 + off, vec2(0.0), vec2(1.0));
    vec3 col = texture(InSampler, uv).rgb;

    // 枪口焰：下缘偏右的一团亮斑，只在开火那几帧出现
    vec2 fp = vec2(0.5 + side * 0.05, 0.06);
    float fd = length((texCoord - fp) * asp) / max(FlashSize, 1e-3);
    float flash = exp(-fd * fd * 3.0) * env * FlashGain;
    col += FlashColor * flash;

    fragColor = vec4(col, 1.0);
}
