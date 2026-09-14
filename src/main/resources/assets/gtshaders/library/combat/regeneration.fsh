// 生命恢复 / Regeneration
// 暖金色的光点自下而上升起，配一层随呼吸起伏的柔光。
// 光点用「把屏幕切成格子、每格独立算一个点」的老办法：后处理里没有粒子系统，
// 但这样做视觉上完全等价，而且是常数开销——不管几个光点都只采样一次。
//
// [en_us]
// Warm golden motes drift upward over a soft breathing glow.
// The motes use the old trick of splitting the screen into cells and computing one dot per cell on its own.
// Post-processing has no particle system, but this is visually identical and has constant cost: it samples only
// once no matter how many motes there are.
//
// @param name=Glow type=color3 default=#FFD98A zh_cn=辉光色 en_us=Glow Color
// @param name=Intensity type=float min=0 max=1 default=0.55 zh_cn=强度 en_us=Intensity
// @param name=Motes type=float min=4 max=40 default=16 zh_cn=光点密度 en_us=Mote Density
// @param name=Rise type=float min=0 max=2 default=0.5 zh_cn=上升速度 en_us=Rise Speed
// @param name=Breathe type=float min=0 max=3 default=1.2 zh_cn=呼吸频率 en_us=Breathe Rate

float hash(vec2 p) {
    return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453);
}

void main() {
    vec3 col = texture(InSampler, texCoord).rgb;

    vec2 asp = vec2(OutSize.x / max(OutSize.y, 1.0), 1.0);
    vec2 grid = vec2(Motes) * asp / max(asp.x, 1.0);
    vec2 cell = floor(texCoord * grid);
    vec2 local = fract(texCoord * grid);

    // 每格一个光点：横向位置固定随机，纵向随时间循环上升
    float seed = hash(cell);
    vec2 mote = vec2(0.2 + seed * 0.6, fract(seed * 7.3 + GTTime * Rise * 0.25));
    float d = length((local - mote) * vec2(1.0, 0.6));
    float spark = smoothstep(0.28, 0.0, d) * (0.4 + 0.6 * seed);
    // 靠近格子上下边界时淡出，避免光点被硬切掉
    spark *= smoothstep(1.0, 0.75, mote.y) * smoothstep(0.0, 0.15, mote.y);

    float breathe = 0.5 + 0.5 * sin(GTTime * Breathe);
    vec2 dv = texCoord - 0.5;
    float halo = smoothstep(0.75, 0.15, length(dv) * 1.4) * (0.25 + 0.35 * breathe);

    col += Glow * (spark * 1.2 + halo * 0.35) * Intensity;
    fragColor = vec4(col, 1.0);
}
