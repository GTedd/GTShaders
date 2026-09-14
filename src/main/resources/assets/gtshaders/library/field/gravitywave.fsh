// 引力波 / Gravitational Wave
// 引力波的两种偏振：+ 偏振在水平/垂直方向交替拉伸，× 偏振在两条对角线上做同样的事。
// 关键性质是「体积守恒」——一个方向拉伸时另一个方向必须压缩，
// 所以这里对 x 乘 (1+h)、对 y 乘 (1-h)，而不是同时放大。
//
// [en_us]
// Gravitational waves squeezing and stretching the screen.
// Two polarizations of a gravitational wave: + polarization alternately stretches along the horizontal and
// vertical axes, and x polarization does the same along the two diagonals.
// The key property is volume conservation: when one direction stretches, the other must compress,
// so x is multiplied by (1+h) and y by (1-h) instead of scaling both up.
//
// @param name=Amplitude type=float min=0 max=0.15 default=0.035 zh_cn=应变幅度 en_us=Strain
// @param name=Frequency type=float min=0.5 max=20 default=6 zh_cn=频率 en_us=Frequency
// @param name=Chirp type=float min=0 max=2 default=0.6 zh_cn=啁啾(频率爬升) en_us=Chirp
// @param name=Cross type=float min=0 max=1 default=0.5 zh_cn=叉偏振占比 en_us=Cross Polarization
// @param name=Glow type=color3 default=#7FB2FF zh_cn=波峰辉光 en_us=Crest Glow

void main() {
    vec2 asp = vec2(OutSize.x / max(OutSize.y, 1.0), 1.0);
    vec2 p = (texCoord - 0.5) * asp;
    float r = length(p);

    // 啁啾：并合前频率一路爬升，这是引力波信号最标志性的特征
    float phase = r * Frequency * 6.2832 - GTTime * (Frequency + Chirp * GTTime * 0.5) * 2.0;
    float h = sin(phase) * Amplitude * exp(-r * 1.2);

    // + 偏振：一个轴拉、另一个轴压
    vec2 plus = vec2(p.x * (1.0 + h), p.y * (1.0 - h));
    // × 偏振：同一件事，只是坐标系转了 45 度。
    // 名字刻意不叫 cross——那会遮蔽 GLSL 的内置 cross()，后面谁想用叉乘就踩坑了。
    vec2 crossPol = vec2(p.x + p.y * h, p.y + p.x * h);

    vec2 q = mix(plus, crossPol, Cross);
    vec2 uv = q / asp + 0.5;

    vec3 col = texture(InSampler, uv).rgb;
    col += Glow * abs(h) * 6.0;   // 应变大的地方微微发光，让不可见的形变可见
    fragColor = vec4(col, 1.0);
}
