// 运动模糊 / Motion Blur
// 沿一个指定方向拉采样。真正的运动模糊需要速度缓冲（每个像素自己的运动向量），
// 后处理链里没有，所以这里退而求其次：整屏统一方向 + 可选的径向（前进感）分量。
//
// [en_us]
// Directional motion blur, with an optional radial zoom.
// Samples are smeared along a chosen direction. Real motion blur needs a velocity buffer (a motion vector for
// every pixel), which the post-processing chain doesn't have, so this settles for the next best thing: one
// uniform direction for the whole screen, plus an optional radial component (for a sense of moving forward).
//
// @param name=Direction type=vec2 min=-1 max=1 default=1,0 zh_cn=运动方向 en_us=Direction
// @param name=Amount type=float min=0 max=0.1 default=0.02 zh_cn=模糊长度 en_us=Amount
// @param name=Radial type=float min=0 max=1 default=0.3 zh_cn=径向占比 en_us=Radial Blend
// @param name=Steps type=int min=2 max=24 default=12 zh_cn=采样数 en_us=Steps
// @param name=Falloff type=float min=0 max=1 default=0.6 zh_cn=尾部衰减 en_us=Tail Falloff

void main() {
    vec2 asp = vec2(OutSize.x / max(OutSize.y, 1.0), 1.0);

    vec2 linear = normalize(Direction + 1e-5) / asp;
    // 径向：方向从中心指向当前像素，中心处几乎不动，边缘拉得最长
    vec2 radial = (texCoord - 0.5);

    vec2 dir = mix(linear, radial, Radial) * Amount;

    vec3 acc = vec3(0.0);
    float total = 0.0;
    for (int i = 0; i < 24; i++) {
        if (i >= Steps) {
            break;
        }
        float k = float(i) / float(max(Steps - 1, 1));
        // 对称采样：往前往后各一半，物体才不会整体偏移
        vec2 uv = texCoord + dir * (k - 0.5);
        float w = mix(1.0, 1.0 - abs(k - 0.5) * 2.0, Falloff);
        acc += texture(InSampler, uv).rgb * w;
        total += w;
    }
    fragColor = vec4(acc / max(total, 1e-4), 1.0);
}
