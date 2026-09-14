// 数据损坏 / Datamosh
// 视频编码坏掉时的样子：宏块被错误的运动向量拖走，颜色残留、位置错位。
// 用「按块取一个随机方向，整块一起偏移」来模拟，因为真实的宏块错位
// 正是以 8x8 / 16x16 为单位整体发生的。
//
// [en_us]
// Broken video compression dragging macroblocks around.
// This is what broken video encoding looks like: macroblocks get dragged away by wrong motion vectors, leaving
// color behind and ending up out of place.
// It is simulated by "pick a random direction per block and shift the whole block", because real macroblock
// errors happen in whole 8x8 / 16x16 units.
//
// @param name=BlockSize type=float min=4 max=64 default=16 zh_cn=宏块大小 en_us=Macroblock Size
// @param name=Displace type=float min=0 max=0.2 default=0.05 zh_cn=拖拽距离 en_us=Displacement
// @param name=Corruption type=float min=0 max=1 default=0.4 zh_cn=损坏比例 en_us=Corruption
// @param name=Drift type=float min=0 max=2 default=0.3 zh_cn=漂移速度 en_us=Drift Speed
// @param name=ColorRot type=float min=0 max=1 default=0.35 zh_cn=颜色错乱 en_us=Color Rotation

float hash(vec2 p) {
    return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453);
}

void main() {
    vec2 size = max(OutSize, vec2(1.0));
    vec2 block = floor(gl_FragCoord.xy / max(BlockSize, 1.0));

    // 每个宏块一个稳定的随机运动向量，随时间缓慢换向
    float t = floor(GTTime * Drift * 4.0);
    float seed = hash(block + t * 7.3);
    float broken = step(1.0 - Corruption, seed);

    float ang = hash(block + t * 3.1 + 17.0) * 6.28319;
    vec2 mv = vec2(cos(ang), sin(ang)) * Displace * broken * (0.4 + seed * 0.6);

    vec3 col = texture(InSampler, texCoord + mv).rgb;

    // 颜色错乱：损坏块的通道被轮换，这是解码器把色度分量对错位置的结果
    if (broken > 0.5 && hash(block + 31.0) < ColorRot) {
        col = hash(block + 47.0) < 0.5 ? col.gbr : col.brg;
    }

    // 块边缘留一条更亮/更暗的痕迹，像压缩残影
    vec2 local = fract(gl_FragCoord.xy / max(BlockSize, 1.0));
    float edge = min(min(local.x, 1.0 - local.x), min(local.y, 1.0 - local.y));
    col *= 1.0 + broken * smoothstep(0.08, 0.0, edge) * 0.25;

    fragColor = vec4(col, 1.0);
}
