// 星星闪烁 / Twinkling Stars
// 原版星星是恒定亮度的白点。按屏幕位置给每颗星一个稳定但不同的相位，
// 整片星空就不会同步呼吸——同步闪是所有"星星闪烁"效果最容易露馅的地方。
//
// [en_us]
// Makes vanilla's constant-brightness white stars twinkle. Each star gets a stable but distinct phase from
// its screen position, so the sky doesn't pulse in unison. Blinking in unison is where every
// "twinkling stars" effect most easily gives itself away.
//
// @param name=Twinkle type=float min=0 max=1 default=0.6 zh_cn=闪烁强度 en_us=Twinkle
// @param name=Speed type=float min=0 max=6 default=2 zh_cn=闪烁速度 en_us=Speed
// @param name=StarColor type=color3 default=#DCE8FF zh_cn=星色 en_us=Star Color

float hash(vec2 p) {
    return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453);
}

vec4 gtFragment(vec4 color) {
    // 用屏幕像素块当"这是哪颗星"的标识：同一颗星在一帧里的像素落在同一块内
    vec2 cell = floor(gl_FragCoord.xy / 3.0);
    float phase = hash(cell) * 6.2831853;
    float t = GameTime * 24000.0 * Speed * 0.02;

    float pulse = 0.5 + 0.5 * sin(t + phase);
    float gain = mix(1.0, 0.3 + 1.4 * pulse, Twinkle);
    return vec4(color.rgb * StarColor * gain, color.a);
}
