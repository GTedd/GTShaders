// 油画 / Oil Painting
// Kuwahara 滤波：把邻域切成四个象限，选方差最小的那块的均值。
// 它的妙处是「保边平滑」——普通模糊会把轮廓一起糊掉，而这个算法在边缘处
// 总会挑到没跨过边界的那一侧，于是色块之间界限反而更硬，正是油画的笔触感。
//
// [en_us]
// An oil painting look made with a Kuwahara filter.
// The neighborhood is split into four quadrants, and the mean of the quadrant with the lowest variance is used.
// Its beauty is edge-preserving smoothing: an ordinary blur smears the outlines away too, but at an edge this
// algorithm always picks the side that doesn't cross the boundary. So the borders between patches of color
// actually get harder, which is exactly the feel of oil paint brushstrokes.
//
// @param name=Radius type=int min=1 max=6 default=3 zh_cn=笔触半径 en_us=Brush Radius
// @param name=Saturate type=float min=0 max=2 default=1.2 zh_cn=饱和度 en_us=Saturation
// @param name=Contrast type=float min=0.5 max=2 default=1.1 zh_cn=对比度 en_us=Contrast

void main() {
    vec2 texel = 1.0 / max(OutSize, vec2(1.0));
    int radius = int(clamp(float(Radius), 1.0, 6.0));

    vec3 bestMean = texture(InSampler, texCoord).rgb;
    float bestVar = 1e9;

    // 四个象限：(-1,-1) (1,-1) (-1,1) (1,1)
    for (int q = 0; q < 4; q++) {
        vec2 dir = vec2(q == 0 || q == 2 ? -1.0 : 1.0, q < 2 ? -1.0 : 1.0);
        vec3 sum = vec3(0.0);
        vec3 sumSq = vec3(0.0);
        float n = 0.0;
        for (int y = 0; y <= 6; y++) {
            if (y > radius) {
                break;
            }
            for (int x = 0; x <= 6; x++) {
                if (x > radius) {
                    break;
                }
                vec3 c = texture(InSampler,
                        texCoord + vec2(float(x), float(y)) * dir * texel).rgb;
                sum += c;
                sumSq += c * c;
                n += 1.0;
            }
        }
        vec3 mean = sum / n;
        // 方差 = E[x^2] - E[x]^2，三个通道加起来当作这一块的"杂乱度"
        vec3 var = max(sumSq / n - mean * mean, vec3(0.0));
        float score = var.r + var.g + var.b;
        if (score < bestVar) {
            bestVar = score;
            bestMean = mean;
        }
    }

    vec3 col = bestMean;
    float luma = dot(col, vec3(0.2126, 0.7152, 0.0722));
    col = mix(vec3(luma), col, Saturate);
    col = clamp((col - 0.5) * Contrast + 0.5, 0.0, 1.0);
    fragColor = vec4(col, 1.0);
}
