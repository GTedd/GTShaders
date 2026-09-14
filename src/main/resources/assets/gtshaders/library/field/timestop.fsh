// 时间静止 / Time Stop
// 一圈「时停锋面」从中心扫出去：锋面之外还是正常世界，之内已经被冻成单色。
// 这个效果的说服力全在锋面本身——那条亮线是时间的边界，必须细、亮、有色散。
//
// [en_us]
// A time-stop front that freezes the world into monochrome.
// A "time-stop front" sweeps out from the center: outside the front the world is still normal, inside it
// everything is already frozen into a single color. The whole effect lives or dies on the front itself. That
// bright line is the boundary of time, so it must be thin, bright and show color dispersion.
//
// @param name=Progress type=float min=0 max=1.6 default=0.55 zh_cn=锋面推进 en_us=Front Progress
// @param name=AutoPlay type=bool default=0 zh_cn=随时间自动播放 en_us=Auto Play
// @param name=Duration type=float min=0.2 max=10 default=2.5 zh_cn=推进时长(秒) en_us=Duration
// @param name=FrontColor type=color3 default=#DCEBFF zh_cn=锋面色 en_us=Front Color
// @param name=FrozenTint type=color3 default=#7C93B8 zh_cn=冻结色调 en_us=Frozen Tint
// @param name=Warp type=float min=0 max=0.05 default=0.014 zh_cn=锋面折射 en_us=Front Warp

void main() {
    vec2 asp = vec2(OutSize.x / max(OutSize.y, 1.0), 1.0);
    vec2 d = (texCoord - 0.5) * asp;
    float r = length(d) / max(length(asp * 0.5), 1e-4);

    float t = AutoPlay > 0.5 ? fract(GTTime / max(Duration, 0.01)) * 1.6 : Progress;

    // 锋面附近折射一下：光线跨越时间边界应该是要弯的
    float band = smoothstep(0.09, 0.0, abs(r - t));
    vec2 uv = texCoord + normalize(d + 1e-5) / asp * band * Warp;

    vec3 src = texture(InSampler, uv).rgb;
    float luma = dot(src, vec3(0.2126, 0.7152, 0.0722));

    // 锋面之内：几乎完全去色，只留一点冷调，像老照片
    vec3 frozen = mix(vec3(luma), FrozenTint * (0.35 + luma * 0.9), 0.6);
    float inside = smoothstep(t + 0.01, t - 0.02, r);
    vec3 col = mix(src, frozen, inside);

    // 锋面本身：一条窄亮线，外侧带一点色散
    col += FrontColor * band * 1.6;
    col.r += smoothstep(0.05, 0.0, abs(r - t - 0.012)) * 0.25;
    col.b += smoothstep(0.05, 0.0, abs(r - t + 0.012)) * 0.25;

    fragColor = vec4(col, 1.0);
}
