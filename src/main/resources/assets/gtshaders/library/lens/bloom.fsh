// 泛光 / Bloom
// 单通道近似：先按阈值挑出高光，再用多环采样把它糊开叠回去。
// 原版的做法是拆成横竖两个通道（N*N 降到 N+N），这里为了「一个文件即一个效果」
// 用了环形采样，代价是采样次数多一些，好处是不需要中转缓冲。
// 想要更高质量，把本效果复制成两层、方向各取横竖即可。
//
// [en_us]
// Single-pass bloom that spreads bright highlights.
// A single-pass approximation: pick out highlights by threshold, then blur them with multi-ring sampling and
// add them back. Vanilla splits this into separate horizontal and vertical passes (N*N down to N+N), but to
// keep "one file, one effect" this uses ring sampling instead. The cost is somewhat more samples, the benefit
// is no intermediate buffer. For higher quality, duplicate this effect into two layers, one horizontal and
// one vertical.
//
// @param name=Threshold type=float min=0 max=1 default=0.62 zh_cn=高光阈值 en_us=Threshold
// @param name=Adapt type=float min=0 max=1 default=0.8 zh_cn=自动适应亮度 en_us=Auto Adapt desc_zh_cn=按当前画面的平均亮度自动调整阈值。关掉之后一进洞、一低头看地面，效果就完全消失了 desc_en_us=Rescales the threshold by the frame's average brightness; with it off the effect vanishes in dark scenes
// @param name=Knee type=float min=0 max=0.5 default=0.15 zh_cn=阈值软过渡 en_us=Soft Knee
// @param name=Radius type=float min=0 max=0.05 default=0.014 zh_cn=扩散半径 en_us=Radius
// @param name=Intensity type=float min=0 max=3 default=0.9 zh_cn=强度 en_us=Intensity
// @param name=Rings type=int min=1 max=4 default=3 zh_cn=采样环数 en_us=Rings
// @param name=Tint type=color3 default=#FFFFFF zh_cn=泛光染色 en_us=Bloom Tint

// 画面平均亮度：4×4 稀疏采样。
//
// 为什么需要它：写死的绝对阈值默认画面是明亮的白天。真在游戏里用起来，玩家一低头看地面、
// 一进洞，整幅画面的亮度就掉到 0.1 以下，谁都够不着阈值——效果一点动静都没有，
// 看起来像随机失灵；转个视角天空进画面了又好了，更让人以为是 bug。
//
// 全屏平均本该靠 mipmap，但后处理拿到的渲染目标不带 mip，取不了。
// 十六个点足够把「洞里」和「正午」分开，代价只有十六次采样，而且每帧只算一次。
float sceneLuma() {
    float s = 0.0;
    for (int i = 0; i < 16; i++) {
        vec2 p = (vec2(float(i % 4), float(i / 4)) + 0.5) * 0.25;
        s += dot(texture(InSampler, p).rgb, vec3(0.2126, 0.7152, 0.0722));
    }
    return s / 16.0;
}

/** 把绝对阈值换成「比画面平均亮多少」。adapt=0 用作者写死的值，=1 完全自动。 */
float adaptiveThreshold(float absolute, float adapt) {
    float rel = clamp(sceneLuma() * 1.35 + 0.05, 0.03, 0.92);
    return mix(absolute, rel, clamp(adapt, 0.0, 1.0));
}

// 软阈值：阈值附近平滑过渡，硬切会让高光边界抖动得很明显
vec3 highlight(vec3 c, float th) {
    float l = dot(c, vec3(0.2126, 0.7152, 0.0722));
    float w = smoothstep(th - Knee, th + Knee, l);
    return c * w;
}

void main() {
    vec3 src = texture(InSampler, texCoord).rgb;
    vec2 asp = vec2(OutSize.x / max(OutSize.y, 1.0), 1.0);
    // 只算一次：放进 highlight() 里的话，环形采样每取一个点就要多跑十六次采样
    float th = adaptiveThreshold(Threshold, Adapt);

    vec3 acc = highlight(src, th);
    float total = 1.0;
    for (int ring = 1; ring <= 4; ring++) {
        if (ring > Rings) {
            break;
        }
        float rr = float(ring) / float(max(Rings, 1));
        // 高斯权重：越外圈贡献越小
        float w = exp(-rr * rr * 2.0);
        int count = ring * 8;
        for (int i = 0; i < 32; i++) {
            if (i >= count) {
                break;
            }
            float a = 6.28319 * float(i) / float(count);
            vec2 uv = texCoord + vec2(cos(a), sin(a)) * rr * Radius / asp;
            acc += highlight(texture(InSampler, uv).rgb, th) * w;
            total += w;
        }
    }

    fragColor = vec4(src + acc / total * Tint * Intensity * 4.0, 1.0);
}
