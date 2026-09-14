// 万花筒 / Kaleidoscope
// 把画面折成 N 个镜像扇区并缓慢旋转，得到不停变形的对称图案。
// 扇区折叠只需要两步：把角度对 2π/N 取余，再把后半个扇区镜像回去——剩下的对称是免费的。
//
// [en_us]
// Folds the picture into N rotating mirrored sectors.
// The picture is folded into N mirrored sectors that slowly rotate, producing an ever-changing symmetric
// pattern. Folding takes just two steps: take the angle modulo 2π/N, then mirror the second half of each
// sector back. The rest of the symmetry comes for free.
//
// @param name=Sides type=float min=2 max=24 default=6 zh_cn=扇区数 en_us=Segments
// @param name=Spin type=float min=-3 max=3 default=0.25 zh_cn=旋转速度 en_us=Spin Speed
// @param name=Twist type=float min=0 max=4 default=0.6 zh_cn=径向扭转 en_us=Radial Twist desc_zh_cn=让不同半径处的旋转量不同，图案会像水面一样绞起来 desc_en_us=Rotates different radii by different amounts, twisting the pattern

// @group 采样 / Sampling
// @param name=Zoom type=float min=0.2 max=4 default=1.1 zh_cn=缩放 en_us=Zoom
// @param name=Center type=vec2 min=0 max=1 default=0.5,0.5 zh_cn=中心 en_us=Center
// @param name=Pulse type=float min=0 max=1 default=0.25 zh_cn=缩放脉动 en_us=Zoom Pulse

// @group 混合 / Blend
// @param name=Mix type=float min=0 max=1 default=1 zh_cn=与原画面混合 en_us=Blend
// @param name=EdgeGlow type=float min=0 max=2 default=0.3 zh_cn=镜面接缝 en_us=Seam Glow
// @param name=Saturate type=float min=0 max=2 default=1.2 zh_cn=饱和度 en_us=Saturation

void main() {
    vec2 asp = vec2(OutSize.x / max(OutSize.y, 1.0), 1.0);
    vec2 d = (texCoord - Center) * asp;
    float r = length(d);
    float ang = atan(d.y, d.x);

    float n = max(floor(Sides), 2.0);
    float sector = 6.2831853 / n;

    // 折叠：取余到一个扇区里，再把后半个镜像回去
    float a = ang + GTTime * Spin + r * Twist;
    a = mod(a, sector);
    a = abs(a - sector * 0.5);

    float zoom = Zoom * (1.0 + sin(GTTime * 0.7) * Pulse * 0.3);
    vec2 uv = vec2(cos(a), sin(a)) * r / max(zoom, 1e-3);
    uv.x /= max(asp.x, 1e-3);
    uv += Center;
    // 越界折回来，而不是钳到边缘——钳过去会在四周留下拉长的色带
    uv = abs(fract(uv * 0.5) * 2.0 - 1.0);

    vec3 kal = texture(InSampler, uv).rgb;
    vec3 src = texture(InSampler, texCoord).rgb;
    vec3 col = mix(src, kal, clamp(Mix, 0.0, 1.0));

    // 接缝：折叠边界上加一点亮度，镜面结构才看得出来
    float seam = smoothstep(0.03, 0.0, min(a, sector * 0.5 - a));
    col += seam * EdgeGlow * 0.3;

    float g = dot(col, vec3(0.2126, 0.7152, 0.0722));
    col = g + (col - g) * Saturate;

    fragColor = vec4(col, 1.0);
}
