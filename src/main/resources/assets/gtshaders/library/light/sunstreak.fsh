// 星芒 / Sun Streak
// 高光被拉成几条对称的长芒，像光圈叶片造成的衍射星芒。芒的角度缓慢转动，
// 亮度随时间轻微呼吸——真实镜头的星芒会随相机的微动而闪。
//
// 芒数由「叶片数」决定：偶数叶片给出的芒数等于叶片数，奇数给出两倍。这里直接给芒数，
// 省得再解释一遍光圈结构。
//
// [en_us]
// Highlights stretched into symmetric star streaks.
// Highlights are drawn out into several long symmetric spikes, like the diffraction starburst caused by
// aperture blades. The streaks slowly rotate and their brightness gently breathes over time, since the
// starburst of a real lens twinkles as the camera moves slightly.
//
// The spike count depends on the blade count: an even number of blades gives as many spikes as blades, an odd
// number gives twice as many. Here you set the spike count directly, so there is no need to explain aperture
// structure all over again.
//
// @param name=Spikes type=float min=2 max=12 default=4 zh_cn=芒数 en_us=Spike Count
// @param name=Length type=float min=0 max=0.5 default=0.16 zh_cn=芒长 en_us=Spike Length
// @param name=Angle type=float min=0 max=3.1416 default=0 zh_cn=起始角(弧度) en_us=Start Angle
// @param name=Rotate type=float min=-1 max=1 default=0.05 zh_cn=旋转速度 en_us=Rotate Speed

// @group 取样 / Source
// @param name=Threshold type=float min=0 max=1 default=0.72 zh_cn=起芒亮度 en_us=Threshold
// @param name=Adapt type=float min=0 max=1 default=0.8 zh_cn=自动适应亮度 en_us=Auto Adapt desc_zh_cn=按当前画面的平均亮度自动调整起芒线。关掉之后一进洞、一低头，星芒就完全消失了 desc_en_us=Rescales the threshold by the frame's average brightness; with it off the streaks vanish in dark scenes
// @param name=Falloff type=float min=0.5 max=6 default=2 zh_cn=沿芒衰减 en_us=Falloff

// @group 外观 / Look
// @param name=StreakColor type=color3 default=#FFF6DC zh_cn=芒色 en_us=Streak Color
// @param name=Gain type=float min=0 max=4 default=1.4 zh_cn=亮度 en_us=Gain
// @param name=Chroma type=float min=0 max=1 default=0.35 zh_cn=芒尖偏色 en_us=Tip Chroma
// @param name=Twinkle type=float min=0 max=1 default=0.3 zh_cn=闪烁 en_us=Twinkle

// 画面平均亮度：4×4 稀疏采样。绝对阈值默认场景是白天，进洞之后没有一个像素够得着，
// 星芒彻底消失——玩家看到的是「换个视角就没了」。后处理拿不到 mip，只能这样估。
float sceneLuma() {
    float s = 0.0;
    for (int i = 0; i < 16; i++) {
        vec2 p = (vec2(float(i % 4), float(i / 4)) + 0.5) * 0.25;
        s += dot(texture(InSampler, p).rgb, vec3(0.2126, 0.7152, 0.0722));
    }
    return s / 16.0;
}

void main() {
    vec2 asp = vec2(OutSize.x / max(OutSize.y, 1.0), 1.0);
    vec3 src = texture(InSampler, texCoord).rgb;

    // 只算一次：放进双重循环里要多跑上千次采样
    float rel = clamp(sceneLuma() * 1.35 + 0.05, 0.03, 0.92);
    float th = mix(Threshold, rel, clamp(Adapt, 0.0, 1.0));

    float n = max(floor(Spikes), 2.0);
    float base = Angle + GTTime * Rotate;
    float twinkle = 1.0 + sin(GTTime * 3.1) * Twinkle * 0.4 + sin(GTTime * 7.7 + 2.1) * Twinkle * 0.2;

    vec3 acc = vec3(0.0);
    for (int s = 0; s < 12; s++) {
        if (float(s) >= n) {
            break;
        }
        float a = base + 6.2831853 * float(s) / n;
        vec2 dir = vec2(cos(a), sin(a)) / vec2(max(asp.x, 1e-3), 1.0);
        for (int i = 1; i <= 8; i++) {
            float k = float(i) / 8.0;
            vec2 uv = clamp(texCoord + dir * k * Length, vec2(0.0), vec2(1.0));
            vec3 c = texture(InSampler, uv).rgb;
            float l = dot(c, vec3(0.2126, 0.7152, 0.0722));
            float w = pow(1.0 - k, Falloff);
            // 芒尖偏色：远端往冷里走，近端保持本色。真实衍射就是有色散的
            vec3 tint = mix(vec3(1.0), vec3(0.7, 0.85, 1.25), k * Chroma);
            acc += c * smoothstep(th, min(th + 0.25, 1.0), l) * w * tint;
        }
    }
    acc /= (n * 8.0) * 0.35;

    fragColor = vec4(src + acc * StreakColor * Gain * twinkle, 1.0);
}
