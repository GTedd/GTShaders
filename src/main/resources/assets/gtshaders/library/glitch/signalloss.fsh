// 信号丢失 / Signal Loss
// 模拟信号变弱的全过程：先是雪花变多、画面变暗，然后开始垂直不同步（画面上下乱跑），
// 最后整屏只剩噪声。用一个 Loss 参数串起这三个阶段，拖动它就是一次完整的失联演出。
//
// [en_us]
// An analog signal fading out into pure static.
// Simulates the whole process of a signal getting weaker: first more snow and a darker picture, then the vertical
// hold goes (the picture jumps up and down), and finally the whole screen is nothing but noise. A single Loss
// parameter ties the three stages together, so dragging it plays out a complete signal dropout.
//
// @param name=Loss type=float min=0 max=1 default=0.5 zh_cn=信号丢失度 en_us=Signal Loss
// @param name=SnowScale type=float min=1 max=8 default=2 zh_cn=雪花颗粒 en_us=Snow Size
// @param name=Hold type=float min=0 max=1 default=0.5 zh_cn=垂直不同步 en_us=Vertical Hold
// @param name=Ghost type=float min=0 max=0.1 default=0.03 zh_cn=重影 en_us=Ghosting
// @param name=Desaturate type=float min=0 max=1 default=0.7 zh_cn=去色 en_us=Desaturate

float hash(vec2 p) {
    return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453);
}

void main() {
    // 阶段二：垂直不同步。整幅画面沿 y 循环滚动，滚速随丢失度上升
    float roll = Hold * Loss * Loss;
    float shift = roll * (GTTime * 0.6 + sin(GTTime * 2.3) * 0.4);
    vec2 uv = vec2(texCoord.x, fract(texCoord.y + shift));

    // 重影：信号多径反射，画面右侧出现一个更弱的副本
    vec3 col = texture(InSampler, uv).rgb;
    col += texture(InSampler, uv + vec2(Ghost * Loss, 0.0)).rgb * Loss * 0.45;
    col /= 1.0 + Loss * 0.45;

    float luma = dot(col, vec3(0.2126, 0.7152, 0.0722));
    col = mix(col, vec3(luma), Desaturate * Loss);
    col *= 1.0 - Loss * 0.35;

    // 阶段一 & 三：雪花。比例随 Loss 上升，到 1 时几乎盖满
    vec2 sp = floor(gl_FragCoord.xy / max(SnowScale, 1.0));
    float n = hash(sp + floor(GTTime * 30.0) * 91.7);
    float snowAmount = smoothstep(0.15, 1.0, Loss);
    col = mix(col, vec3(n), snowAmount * step(0.35, hash(sp + floor(GTTime * 30.0))) );

    // 行同步失败带来的横向噪声条
    float band = step(0.97 - Loss * 0.25, hash(vec2(floor(texCoord.y * 120.0), floor(GTTime * 18.0))));
    col = mix(col, vec3(hash(gl_FragCoord.xy + GTTime)), band * Loss * 0.8);

    fragColor = vec4(col, 1.0);
}
