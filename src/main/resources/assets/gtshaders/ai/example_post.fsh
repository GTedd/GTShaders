// 扫描线 / Scanlines
// CRT 显示器的横向暗纹：按屏幕纵向位置周期性压暗，再叠一条缓慢向下爬的亮带。
// 染色只作用在暗部——亮部一起染的话整幅画面会糊成一片绿，看着像滤镜而不像显示器。
//
// [en_us]
// The horizontal dark lines of a CRT monitor. The screen is darkened periodically by vertical position,
// then a bright band slowly crawls downward on top.
// The tint only affects the dark parts. Tinting the bright parts too would wash the whole picture green,
// which looks like a filter rather than a monitor.
//
// @param name=Density type=float min=50 max=800 default=280 zh_cn=扫描线密度 en_us=Line Density
// @param name=Depth type=float min=0 max=1 default=0.35 zh_cn=暗纹深度 en_us=Depth
// @param name=Tint type=color3 default=#7FFFD4 zh_cn=荧光色 en_us=Phosphor Tint

// @group 滚动亮带 / Rolling Band
// @param name=BandSpeed type=float min=0 max=2 default=0.25 zh_cn=滚动速度 en_us=Speed
// @param name=BandWidth type=float min=0.01 max=0.5 default=0.12 zh_cn=亮带宽度 en_us=Width
// @param name=BandGain type=float min=0 max=1 default=0.18 zh_cn=亮带强度 en_us=Gain

void main() {
    vec3 col = texture(InSampler, texCoord).rgb;

    // 暗纹。密度越高线越细，用正弦而不是阶跃是为了让缩放时不产生硬摩尔纹
    float line = 0.5 + 0.5 * sin(texCoord.y * Density * 3.14159265);
    col *= 1.0 - Depth * line;

    // 荧光染色只吃暗部：亮度越高越保留原色
    float luma = dot(col, vec3(0.299, 0.587, 0.114));
    col = mix(col * Tint, col, luma);

    // 亮带用 fract 循环。写成 clamp(GTTime / 周期, 0, 1) 的话，
    // 效果加进工程时 GTTime 已经几百秒，亮带永远停在画面外
    float band = fract(texCoord.y - GTTime * BandSpeed);
    col += BandGain * (1.0 - smoothstep(0.0, max(BandWidth, 1e-5), band)) * Tint;

    fragColor = vec4(col, 1.0);
}
