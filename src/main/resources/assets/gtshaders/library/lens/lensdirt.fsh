// 镜头污渍 / Lens Dirt
// 灰尘和油污只有在强光照射时才可见——所以污渍图不能直接叠上去，
// 必须乘以画面高光。这一条做对了，效果才像脏镜头而不是脏屏幕。
//
// [en_us]
// Lens dirt that only shows up where the light is strong.
// Dust and grease only become visible under strong light, so the dirt map can't simply be laid on top: it has
// to be multiplied by the picture's highlights. Get this right and it looks like a dirty lens rather than a
// dirty screen.
//
// @param name=Amount type=float min=0 max=2 default=0.7 zh_cn=污渍强度 en_us=Amount
// @param name=Scale type=float min=2 max=30 default=9 zh_cn=污渍尺度 en_us=Dirt Scale
// @param name=Threshold type=float min=0 max=1 default=0.65 zh_cn=高光阈值 en_us=Highlight Threshold
// @param name=Adapt type=float min=0 max=1 default=0.8 zh_cn=自动适应亮度 en_us=Auto Adapt desc_zh_cn=按当前画面的平均亮度自动调整阈值。关掉之后昏暗场景里污渍完全不显形 desc_en_us=Rescales the threshold by the frame's average brightness; with it off the dirt never shows in dark scenes
// @param name=Streaks type=float min=0 max=1 default=0.4 zh_cn=擦拭痕迹 en_us=Wipe Streaks
// @param name=Tint type=color3 default=#FFF0DC zh_cn=污渍染色 en_us=Dirt Tint

float hash(vec2 p) {
    return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453);
}

float noise(vec2 p) {
    vec2 i = floor(p);
    vec2 f = fract(p);
    f = f * f * (3.0 - 2.0 * f);
    return mix(mix(hash(i), hash(i + vec2(1.0, 0.0)), f.x),
               mix(hash(i + vec2(0.0, 1.0)), hash(i + vec2(1.0, 1.0)), f.x), f.y);
}

// 画面平均亮度：4×4 稀疏采样。写死的绝对阈值默认画面是白天；进洞或低头看地面时
// 整幅画面掉到 0.1 以下，阈值再也够不着，污渍就彻底不显形了——转个视角又回来，
// 看起来完全像随机失灵。后处理拿到的渲染目标不带 mip，只能这样估。
float sceneLuma() {
    float s = 0.0;
    for (int i = 0; i < 16; i++) {
        vec2 p = (vec2(float(i % 4), float(i / 4)) + 0.5) * 0.25;
        s += dot(texture(InSampler, p).rgb, vec3(0.2126, 0.7152, 0.0722));
    }
    return s / 16.0;
}

float adaptiveThreshold(float absolute, float adapt) {
    float rel = clamp(sceneLuma() * 1.35 + 0.05, 0.03, 0.92);
    return mix(absolute, rel, clamp(adapt, 0.0, 1.0));
}

void main() {
    vec3 src = texture(InSampler, texCoord).rgb;

    // 高光提取：先向周围糊一圈，污渍才会在光源"附近"发亮而不是只有光源那一点
    vec3 bright = vec3(0.0);
    for (int i = 0; i < 8; i++) {
        float a = float(i) * 0.7854;
        bright += texture(InSampler, texCoord + vec2(cos(a), sin(a)) * 0.02).rgb;
    }
    bright /= 8.0;
    float th = adaptiveThreshold(Threshold, Adapt);
    float glow = smoothstep(th, min(th + 0.35, 1.0), dot(bright, vec3(0.2126, 0.7152, 0.0722)));

    // 斑点：几层噪声相乘，得到少而集中的团块而不是均匀颗粒
    float blobs = noise(texCoord * Scale) * noise(texCoord * Scale * 2.3 + 7.0);
    blobs = smoothstep(0.18, 0.55, blobs);

    // 擦拭痕迹：沿一个方向拉长的低频噪声
    float wipe = noise(vec2(texCoord.x * Scale * 0.4 + texCoord.y * 2.0, texCoord.y * Scale * 3.0));
    wipe = smoothstep(0.45, 0.75, wipe) * Streaks;

    float dirt = clamp(blobs + wipe, 0.0, 1.0);
    fragColor = vec4(src + Tint * dirt * glow * Amount, 1.0);
}
