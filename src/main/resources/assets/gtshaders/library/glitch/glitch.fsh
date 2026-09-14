// 数字故障 / Digital Glitch
// 故障感的关键是「稀疏 + 突然」：大部分时间画面完全正常，偶尔猛地错一下。
// 持续抖动只会像信号差。这里用一个按时间片跳变的随机门控制整体强度，
// 门关着的时候一行代码都不生效。
//
// [en_us]
// Sparse, sudden digital glitches.
// The key to a glitch look is "sparse + sudden": most of the time the picture is completely normal, then every
// so often it jolts out of place. Constant jitter just looks like a bad signal. Here a random gate that flips per
// time slice controls the overall strength, and while the gate is closed not a single line of it takes effect.
//
// @param name=Intensity type=float min=0 max=1 default=0.5 zh_cn=强度 en_us=Intensity
// @param name=Rate type=float min=1 max=30 default=8 zh_cn=触发频率 en_us=Trigger Rate
// @param name=Blocks type=float min=4 max=80 default=24 zh_cn=错位块数 en_us=Block Count
// @param name=Shift type=float min=0 max=0.2 default=0.05 zh_cn=错位距离 en_us=Shift Distance
// @param name=Chroma type=float min=0 max=0.05 default=0.012 zh_cn=通道分离 en_us=Channel Split
// @param name=Noise type=float min=0 max=1 default=0.3 zh_cn=数字噪点 en_us=Digital Noise

float hash(vec2 p) {
    return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453);
}

void main() {
    float slot = floor(GTTime * Rate);
    // 大部分时间片什么都不做，故障才显得突然
    float gate = step(0.72, hash(vec2(slot, 1.0))) * Intensity;

    vec2 uv = texCoord;

    // 横向条块错位：把画面切成 Blocks 条，随机挑几条整体平移
    float band = floor(texCoord.y * Blocks);
    float bandSeed = hash(vec2(band, slot));
    float displaced = step(0.7, bandSeed) * gate;
    uv.x += (bandSeed - 0.5) * Shift * displaced * 2.0;

    // 通道分离
    float split = Chroma * gate * (0.5 + hash(vec2(slot, 5.0)));
    vec3 col;
    col.r = texture(InSampler, uv + vec2(split, 0.0)).r;
    col.g = texture(InSampler, uv).g;
    col.b = texture(InSampler, uv - vec2(split, 0.0)).b;

    // 数字噪点：整块整块地出现，不是逐像素的白噪声
    vec2 nblock = floor(gl_FragCoord.xy / 6.0);
    float n = hash(nblock + slot * 13.0);
    col = mix(col, vec3(n), step(0.93, n) * gate * Noise);

    // 偶尔整屏亮度跳一下
    col *= 1.0 + gate * (hash(vec2(slot, 9.0)) - 0.5) * 0.4;
    fragColor = vec4(col, 1.0);
}
