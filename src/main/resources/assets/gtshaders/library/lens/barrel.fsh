// 桶形/枕形畸变 / Lens Distortion
// Brown-Conrady 径向畸变模型的前两项：r' = r * (1 + k1*r^2 + k2*r^4)。
// k1 为正是枕形（画面往里收），为负是桶形（往外鼓）——这两个参数
// 就是相机标定里实际会解出来的那两个，填真实镜头的值即可复现它的畸变。
//
// [en_us]
// Barrel and pincushion lens distortion.
// Uses the first two terms of the Brown-Conrady radial distortion model: r' = r * (1 + k1*r^2 + k2*r^4).
// Positive k1 gives pincushion (the picture pulls inward), negative gives barrel (it bulges outward). These
// two parameters are exactly the ones solved for in real camera calibration, so plugging in a real lens's
// values reproduces its distortion.
//
// @param name=K1 type=float min=-0.6 max=0.6 default=-0.22 zh_cn=一阶畸变 en_us=K1
// @param name=K2 type=float min=-0.4 max=0.4 default=0.05 zh_cn=二阶畸变 en_us=K2
// @param name=Scale type=float min=0.5 max=1.5 default=1 zh_cn=缩放补偿 en_us=Scale
// @param name=Chroma type=float min=0 max=0.05 default=0.008 zh_cn=横向色差 en_us=Lateral Chromatic
// @param name=EdgeColor type=color3 default=#000000 zh_cn=画面外颜色 en_us=Outside Color

// 按给定的畸变系数把归一化坐标映回原图
vec2 distort(vec2 p, float k1, float k2) {
    float r2 = dot(p, p);
    return p * (1.0 + k1 * r2 + k2 * r2 * r2);
}

void main() {
    vec2 asp = vec2(OutSize.x / max(OutSize.y, 1.0), 1.0);
    vec2 p = (texCoord - 0.5) * 2.0;
    p.x *= asp.x;                     // 在等比空间里算，否则宽屏上畸变会变成椭圆
    p /= max(Scale, 1e-3);

    // 横向色差：三个通道的畸变系数略有差别，这正是它的物理成因
    vec2 pr = distort(p, K1 * (1.0 + Chroma), K2);
    vec2 pg = distort(p, K1, K2);
    vec2 pb = distort(p, K1 * (1.0 - Chroma), K2);

    vec2 ur = vec2(pr.x / asp.x, pr.y) * 0.5 + 0.5;
    vec2 ug = vec2(pg.x / asp.x, pg.y) * 0.5 + 0.5;
    vec2 ub = vec2(pb.x / asp.x, pb.y) * 0.5 + 0.5;

    if (ug.x < 0.0 || ug.x > 1.0 || ug.y < 0.0 || ug.y > 1.0) {
        fragColor = vec4(EdgeColor, 1.0);
        return;
    }

    fragColor = vec4(texture(InSampler, clamp(ur, 0.0, 1.0)).r,
                     texture(InSampler, ug).g,
                     texture(InSampler, clamp(ub, 0.0, 1.0)).b, 1.0);
}
