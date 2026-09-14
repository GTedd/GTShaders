// 像素化 / Pixel Art
// 马赛克 + 调色板量化。只做马赛克得到的是「低分辨率照片」，
// 必须同时砍掉色深，才会变成「像素画」——两件事缺一不可。
//
// [en_us]
// Pixel art: mosaic blocks plus palette quantization.
// Mosaic alone only gives you a "low-resolution photo". The color depth has to be cut at the same time to get
// "pixel art". Neither step works without the other.
//
// @param name=PixelSize type=float min=1 max=48 default=6 zh_cn=像素块大小 en_us=Pixel Size
// @param name=Levels type=float min=2 max=32 default=6 zh_cn=每通道色阶 en_us=Color Levels
// @param name=Saturate type=float min=0 max=2 default=1.25 zh_cn=饱和度 en_us=Saturation
// @param name=Outline type=float min=0 max=1 default=0.25 zh_cn=块间描边 en_us=Cell Outline

void main() {
    vec2 size = max(OutSize, vec2(1.0));
    float px = max(PixelSize, 1.0);

    // 采样点对齐到块中心：取块左上角会让整幅画偏半块
    vec2 block = floor(gl_FragCoord.xy / px);
    vec2 uv = (block * px + px * 0.5) / size;
    vec3 col = texture(InSampler, uv).rgb;

    // 先提饱和再量化：量化会压掉色彩差异，顺序反了就白提了
    float luma = dot(col, vec3(0.2126, 0.7152, 0.0722));
    col = clamp(mix(vec3(luma), col, Saturate), 0.0, 1.0);

    float steps = max(Levels - 1.0, 1.0);
    col = floor(col * steps + 0.5) / steps;

    // 块与块之间压一条暗边，颗粒感更明确
    vec2 local = fract(gl_FragCoord.xy / px);
    float edge = min(min(local.x, 1.0 - local.x), min(local.y, 1.0 - local.y));
    col *= 1.0 - Outline * smoothstep(0.12, 0.0, edge);

    fragColor = vec4(col, 1.0);
}
