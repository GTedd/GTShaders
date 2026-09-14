// 鱼眼 / Fisheye
// 等距投影：出射角与像高成正比（r' = f * θ），这是绝大多数鱼眼镜头的设计。
// 和「简单地把 uv 按 r^2 推出去」不同，等距投影在边缘的压缩是有物理依据的，
// 直线弯曲的方式也更像真镜头。
//
// [en_us]
// Fisheye lens using equidistant projection.
// Equidistant projection means image height is proportional to the ray angle (r' = f * θ), which is how most
// fisheye lenses are designed. Unlike "simply pushing uv outward by r^2", the compression at the edges of an
// equidistant projection is physically grounded, and straight lines also bend more like they do in a real
// lens.
//
// @param name=FOV type=float min=30 max=180 default=140 zh_cn=视场角(度) en_us=Field of View
// @param name=Zoom type=float min=0.3 max=2 default=1 zh_cn=缩放 en_us=Zoom
// @param name=EdgeColor type=color3 default=#000000 zh_cn=画面外颜色 en_us=Outside Color
// @param name=Chroma type=float min=0 max=0.03 default=0.004 zh_cn=边缘色散 en_us=Edge Chromatic

void main() {
    vec2 asp = vec2(OutSize.x / max(OutSize.y, 1.0), 1.0);
    vec2 p = (texCoord - 0.5) * 2.0 * asp / max(asp.y, 1.0);
    float r = length(p);

    float half_fov = radians(clamp(FOV, 1.0, 179.0)) * 0.5;
    // 像高 r 对应的入射角，再投回平面：tan(θ) 就是等效的针孔坐标
    float theta = r * half_fov / max(Zoom, 1e-3);
    if (theta >= 1.5533) {           // 接近 89 度，tan 会爆掉
        fragColor = vec4(EdgeColor, 1.0);
        return;
    }
    float k = r > 1e-5 ? tan(theta) / tan(half_fov) / r : 1.0;

    vec2 uv = 0.5 + p * k * max(asp.y, 1.0) / asp * 0.5;
    if (uv.x < 0.0 || uv.x > 1.0 || uv.y < 0.0 || uv.y > 1.0) {
        fragColor = vec4(EdgeColor, 1.0);
        return;
    }

    // 边缘色散：越靠外三通道分得越开，这是广角镜头的典型缺陷
    vec2 dir = (uv - 0.5) * Chroma * r;
    vec3 col;
    col.r = texture(InSampler, uv + dir).r;
    col.g = texture(InSampler, uv).g;
    col.b = texture(InSampler, uv - dir).b;
    fragColor = vec4(col, 1.0);
}
