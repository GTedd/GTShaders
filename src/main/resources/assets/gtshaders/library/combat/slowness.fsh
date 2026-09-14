// 迟缓 / Slowness
// 拖影 + 失色 + 视野收窄。拖影这里用「沿径向反复采样」近似——后处理链拿不到上一帧，
// 真正的时间维拖尾做不了，但径向拖影在视觉上同样传达「跟不上」。
//
// [en_us]
// Slowness: smeared motion, faded color, narrowed view.
// Smear + desaturation + tunnel vision. The smear is approximated by sampling repeatedly along the radial
// direction. The post-processing chain can't access the previous frame, so a true trail over time isn't possible,
// but a radial smear gets the same "can't keep up" feeling across.
//
// @param name=Smear type=float min=0 max=0.15 default=0.05 zh_cn=拖影长度 en_us=Smear
// @param name=Steps type=int min=2 max=16 default=8 zh_cn=拖影采样数 en_us=Smear Steps
// @param name=Desaturate type=float min=0 max=1 default=0.55 zh_cn=失色 en_us=Desaturate
// @param name=Tint type=color3 default=#8A93B5 zh_cn=色调 en_us=Tint
// @param name=Tunnel type=float min=0 max=2 default=0.8 zh_cn=视野收窄 en_us=Tunnel

void main() {
    vec2 dir = texCoord - 0.5;

    vec3 acc = vec3(0.0);
    float total = 0.0;
    for (int i = 0; i < 16; i++) {
        if (i >= Steps) {
            break;
        }
        float k = float(i) / float(max(Steps - 1, 1));
        float w = 1.0 - k * 0.7;   // 原位置权重最高，主体不至于糊没
        acc += texture(InSampler, texCoord - dir * k * Smear).rgb * w;
        total += w;
    }
    vec3 col = acc / max(total, 1e-4);

    float luma = dot(col, vec3(0.2126, 0.7152, 0.0722));
    col = mix(col, vec3(luma) * Tint * 1.4, Desaturate);

    float r = length(dir) * 1.4;
    col *= clamp(1.0 - r * r * Tunnel, 0.0, 1.0);
    fragColor = vec4(col, 1.0);
}
