package mc.GTedd.cn.gtshaders.codegen;

import java.util.List;

/**
 * 示例模板。
 *
 * <p>这些以前是「新建工程时的默认源码」，现在退到菜单里按需插入——默认工程必须是无效果的，
 * 但示例本身很有价值：它们是参数注解怎么写、内置量怎么用的现成范本，
 * 比一段文档更容易看懂。
 *
 * <p>每个模板都：只用一个 {@code void main()}、参数带中英双语标签、
 * 不依赖除内置量以外的任何东西，插进任意一层都能直接编译。
 */
public final class Samples {

    /**
     * @param nameKey 名称的 i18n key
     * @param source  作者源码
     */
    public record Sample(String nameKey, String source) {
    }

    private Samples() {
    }

    public static List<Sample> all() {
        return List.of(
                new Sample("gtshaders.sample.tint", TINT),
                new Sample("gtshaders.sample.grayscale", GRAYSCALE),
                new Sample("gtshaders.sample.dither", DITHER),
                new Sample("gtshaders.sample.wave", WAVE),
                new Sample("gtshaders.sample.scanline", SCANLINE),
                new Sample("gtshaders.sample.edge", EDGE),
                new Sample("gtshaders.sample.radial", RADIAL),
                new Sample("gtshaders.sample.tonemap", TONEMAP),
                new Sample("gtshaders.sample.coderain", CODERAIN));
    }

    private static final String TINT = """
            // 染色与暗角 / Tint & Vignette
            // 最短的一个能看见效果的着色器。取出这个像素的颜色、乘上一个色调、再按到中心的距离压暗。
            // 从这里能看清后处理的全部套路：读 InSampler、算一算、写 fragColor。
            //
            // [en_us]
            // The shortest shader with a visible result. It takes the pixel color, multiplies it by a tint
            // and darkens it by the distance to the center.
            // It shows the whole post-processing routine: read InSampler, do some math, write fragColor.

            // @param name=Strength type=float min=0 max=1 default=0.6 zh_cn=效果强度 en_us=Strength
            // @param name=TintColor type=color3 default=#99CCFF zh_cn=染色 en_us=Tint
            // @param name=Vignette type=float min=0 max=2 default=0.8 zh_cn=暗角 en_us=Vignette

            void main() {
                vec4 src = texture(InSampler, texCoord);

                // 染色
                vec3 col = mix(src.rgb, src.rgb * TintColor, Strength);

                // 暗角：离画面中心越远压得越暗
                vec2 d = texCoord - 0.5;
                float vig = 1.0 - dot(d, d) * Vignette;
                col *= clamp(vig, 0.0, 1.0);

                fragColor = vec4(col, 1.0);
            }
            """;

    private static final String GRAYSCALE = """
            // 黑白与饱和度 / Grayscale & Saturation
            // 把彩色压成灰度，关键是三个通道的权重不能取平均。人眼对绿最敏感、对蓝最迟钝，
            // 平均法算出来的灰度会让红色物体显得过亮。
            //
            // [en_us]
            // Turns color into grayscale. The key is not to average the three channels: the eye is most
            // sensitive to green and least to blue, so a plain average makes red objects look too bright.

            // @param name=Amount type=float min=0 max=1 default=1.0 zh_cn=强度 en_us=Amount
            // @param name=Saturation type=float min=0 max=2 default=0.0 zh_cn=饱和度 en_us=Saturation

            void main() {
                vec4 src = texture(InSampler, texCoord);

                // Rec.709 亮度权重，比简单取平均更符合人眼观感
                float luma = dot(src.rgb, vec3(0.2126, 0.7152, 0.0722));
                vec3 col = mix(vec3(luma), src.rgb, Saturation);
                col = mix(src.rgb, col, Amount);

                fragColor = vec4(col, 1.0);
            }
            """;

    private static final String WAVE = """
            // 水波扭曲 / Wave Distortion
            // 采样位置本身可以动：横向按纵坐标做正弦偏移，画面就像隔着水面看。
            // 这是"改采样坐标"这一类效果的最小范本，热浪、抖动、传送门都是它的变体。
            //
            // [en_us]
            // Wavy distortion, like looking through water. The sample position itself moves: a sine offset
            // along x driven by y.
            // This is the smallest example of "move the sample coordinate"; heat haze, shaking and portals
            // are all variations of it.

            // @param name=Amplitude type=float min=0 max=0.02 default=0.004 zh_cn=波幅 en_us=Amplitude
            // @param name=Frequency type=float min=1 max=80 default=40 zh_cn=波长密度 en_us=Frequency
            // @param name=Speed type=float min=0 max=6 default=1.5 zh_cn=速度 en_us=Speed

            void main() {
                // GTTime 由编辑器自己驱动，可以随时暂停/回拨，不受游戏时间影响
                float wave = sin(texCoord.y * Frequency + GTTime * Speed) * Amplitude;
                fragColor = texture(InSampler, texCoord + vec2(wave, 0.0));
            }
            """;

    private static final String SCANLINE = """
            // CRT 扫描线 / CRT Scanlines
            // 老显示器的两个特征：一行行的扫描线，以及三个颜色通道各偏一点造成的色边。
            // 只做扫描线的话像加了个横条滤镜，加上色散才有那股旧感。
            //
            // [en_us]
            // An old CRT look. Two traits of old displays: visible scanlines, and color fringes from the
            // three color channels being slightly offset.
            // Scanlines alone look like a striped filter; the color dispersion is what makes it feel old.

            // @param name=LineDarkness type=float min=0 max=1 default=0.35 zh_cn=扫描线深度 en_us=Line Darkness
            // @param name=LineCount type=float min=50 max=600 default=280 zh_cn=扫描线数量 en_us=Line Count
            // @param name=Aberration type=float min=0 max=0.01 default=0.0015 zh_cn=色散 en_us=Aberration

            void main() {
                vec2 uv = texCoord;

                // 横向色散：RGB 三通道各偏一点，模拟老显示器的色边
                float r = texture(InSampler, uv + vec2(Aberration, 0.0)).r;
                float g = texture(InSampler, uv).g;
                float b = texture(InSampler, uv - vec2(Aberration, 0.0)).b;
                vec3 col = vec3(r, g, b);

                // 扫描线
                float line = 0.5 + 0.5 * sin(uv.y * LineCount * 3.14159);
                col *= 1.0 - LineDarkness * (1.0 - line);

                fragColor = vec4(col, 1.0);
            }
            """;

    private static final String EDGE = """
            // 边缘描边 / Edge Outline
            // 采样自己和上下左右四个邻居，亮度差得越多说明这里越像一条边。
            // 这是第一个需要"看邻居"的例子，OutSize 就是为了把一个像素换算成 UV 上的距离。
            //
            // [en_us]
            // Outlines edges by brightness difference. It samples the pixel and its four neighbors; the more
            // their brightness differs, the more likely this is an edge.
            // This is the first example that looks at neighbors; OutSize converts one pixel into a UV distance.

            // @param name=Thickness type=float min=0.5 max=4 default=1.0 zh_cn=描边粗细 en_us=Thickness
            // @param name=Threshold type=float min=0 max=1 default=0.12 zh_cn=阈值 en_us=Threshold
            // @param name=LineColor type=color3 default=#101418 zh_cn=描边颜色 en_us=Line Color

            void main() {
                // OutSize 是当前渲染目标的像素尺寸，用它换算出一个像素有多大
                vec2 texel = Thickness / OutSize;
                vec4 src = texture(InSampler, texCoord);

                float c = dot(src.rgb, vec3(0.2126, 0.7152, 0.0722));
                float l = dot(texture(InSampler, texCoord - vec2(texel.x, 0.0)).rgb, vec3(0.2126, 0.7152, 0.0722));
                float r = dot(texture(InSampler, texCoord + vec2(texel.x, 0.0)).rgb, vec3(0.2126, 0.7152, 0.0722));
                float u = dot(texture(InSampler, texCoord - vec2(0.0, texel.y)).rgb, vec3(0.2126, 0.7152, 0.0722));
                float d = dot(texture(InSampler, texCoord + vec2(0.0, texel.y)).rgb, vec3(0.2126, 0.7152, 0.0722));

                float edge = abs(l - r) + abs(u - d) + abs(c - (l + r + u + d) * 0.25);
                float k = smoothstep(Threshold, Threshold * 2.0, edge);

                fragColor = vec4(mix(src.rgb, LineColor, k), 1.0);
            }
            """;

    private static final String DITHER = """
            // 有序抖动 / Ordered Dither
            // 有序抖动（Bayer 4x4）：只用很少的几级颜色去逼近连续灰度。
            // 早期显示器做不到 256 阶灰度，就靠一张固定的阈值矩阵在空间上"骗"人眼；
            // 今天它主要被当成风格化手段用。延伸阅读：轩宇1725《Minecraft 着色器教程》https://cr-109.docs.repeater.red/datapack-index/index/附录7.html
            //
            // [en_us]
            // Ordered (Bayer 4x4) dithering. It fakes continuous gray with only a few color levels.
            // Early displays could not show 256 shades of gray, so a fixed threshold matrix "fooled" the eye
            // spatially; today it is mostly a stylistic choice. Further reading: Xuanyu1725's Minecraft shader tutorial.
            //
            // @param name=Levels type=float min=2 max=16 default=2 zh_cn=色阶数 en_us=Levels
            // @param name=PixelSize type=float min=1 max=8 default=2 zh_cn=抖动颗粒 en_us=Dither Pixel
            // @param name=Monochrome type=bool default=1 zh_cn=先转灰度 en_us=Grayscale First
            // @param name=Tint type=color3 default=#FFFFFF zh_cn=着色 en_us=Tint

            // GLSL 的 mat4 构造函数按「列主序」填参数，所以下面每一行文本其实是矩阵的一列。
            // 这样写之后 BAYER[x][y] 恰好取到坐标 (x, y) 处的阈值，索引顺序和直觉一致。
            const mat4 BAYER = mat4(
                 0.0, 12.0,  3.0, 15.0,
                 8.0,  4.0, 11.0,  7.0,
                 2.0, 14.0,  1.0, 13.0,
                10.0,  6.0,  9.0,  5.0) / 16.0;

            // 阈值取自屏幕像素坐标而不是 texCoord：抖动图案必须钉死在像素网格上，
            // 跟着画面内容浮动的话会糊成一片噪点。
            float ditherThreshold() {
                ivec2 c = ivec2(mod(floor(gl_FragCoord.xy / max(PixelSize, 1.0)), 4.0));
                return BAYER[c.x][c.y];
            }

            float quantize(float v, float t) {
                float steps = max(Levels - 1.0, 1.0);
                return clamp(floor(v * steps + t) / steps, 0.0, 1.0);
            }

            void main() {
                vec3 src = texture(InSampler, texCoord).rgb;
                float t = ditherThreshold();

                vec3 col;
                if (Monochrome > 0.5) {
                    // Rec.709 亮度权重：人眼对绿色最敏感，直接取平均会让绿色物体显得偏暗
                    float luma = dot(src, vec3(0.2126, 0.7152, 0.0722));
                    col = vec3(quantize(luma, t));
                } else {
                    col = vec3(quantize(src.r, t), quantize(src.g, t), quantize(src.b, t));
                }

                fragColor = vec4(col * Tint, 1.0);
            }
            """;

    private static final String RADIAL = """
            // 径向模糊 / Radial Blur
            // 径向模糊：沿着「当前像素 → 中心」这条线多点采样再平均，得到冲刺/聚焦感。
            // 后处理链每层只有一个输入采样器，多次 texture() 就是唯一的模糊手段——
            // 原版 blur 之所以拆成横竖两个通道，正是为了把 N*N 次采样降成 N+N 次。
            //
            // [en_us]
            // Blurs along the line from each pixel to the center. Averaging several samples along that line
            // gives a sense of dashing or focus.
            // Each layer of a post chain has only one input sampler, so repeated texture() calls are the only
            // way to blur. That is why the vanilla blur is split into horizontal and vertical passes: it turns
            // N*N samples into N+N.
            //
            // @param name=Amount type=float min=0 max=0.3 default=0.08 zh_cn=模糊强度 en_us=Amount
            // @param name=Center type=vec2 min=0 max=1 default=0.5,0.5 zh_cn=中心 en_us=Center
            // @param name=Steps type=int min=2 max=24 default=10 zh_cn=采样次数 en_us=Steps
            // @param name=Protect type=float min=0 max=1 default=0.25 zh_cn=中心保护半径 en_us=Center Protect

            void main() {
                vec2 dir = texCoord - Center;

                // 离中心越远糊得越狠；Protect 半径内几乎不动，视觉上就成了"聚焦"
                float w = smoothstep(Protect, 1.0, length(dir) * 1.4);
                float scale = Amount * w;

                vec3 acc = vec3(0.0);
                float total = 0.0;
                // 循环上限写成常量，驱动才能展开；真正的次数由 Steps 控制
                for (int i = 0; i < 24; i++) {
                    if (i >= Steps) {
                        break;
                    }
                    float k = float(i) / float(max(Steps - 1, 1));
                    // k=0 就是原位置，越往后越靠近中心
                    vec3 s = texture(InSampler, texCoord - dir * (k * scale)).rgb;
                    float weight = 1.0 - k * 0.5;   // 原位置权重最高，主体不至于被糊没
                    acc += s * weight;
                    total += weight;
                }

                fragColor = vec4(acc / max(total, 1e-4), 1.0);
            }
            """;

    private static final String TONEMAP = """
            // 色调映射 / Tone Mapping
            // 曝光 / 色调映射 / Gamma —— 任何一套「算出来的光照」最后都要走这三步才能上屏。
            // 直接 clamp 会让高光成片死白，S 形曲线则把它们平滑地压回 0..1。
            // 延伸阅读：轩宇1725《Minecraft 着色器教程》的 PBR 一章
            //
            // [en_us]
            // Exposure, tone mapping and gamma for computed lighting. Any lighting you compute has to go
            // through these three steps before it reaches the screen.
            // A plain clamp blows highlights out to flat white; an S-curve compresses them smoothly back into 0..1.
            // Further reading: the PBR chapter of Xuanyu1725's Minecraft shader tutorial.
            //
            // @param name=Exposure type=float min=0 max=4 default=1.0 zh_cn=曝光 en_us=Exposure
            // @param name=UseAces type=bool default=1 zh_cn=用 ACES 曲线 en_us=ACES Curve
            // @param name=Contrast type=float min=0.5 max=2 default=1.0 zh_cn=对比度 en_us=Contrast
            // @param name=Gamma type=float min=0.5 max=2.5 default=1.0 zh_cn=Gamma en_us=Gamma
            // @param name=Lift type=color3 default=#000000 zh_cn=暗部提亮 en_us=Lift

            // Narkowicz 的 ACES 拟合：一条便宜的 S 形曲线，电影感主要来自它的肩部
            vec3 acesFilm(vec3 x) {
                const float a = 2.51, b = 0.03, c = 2.43, d = 0.59, e = 0.14;
                return clamp((x * (a * x + b)) / (x * (c * x + d) + e), 0.0, 1.0);
            }

            void main() {
                vec3 col = texture(InSampler, texCoord).rgb * Exposure;

                // 另一条是 Reinhard：x/(x+1)，更柔和但对比也更平
                col = UseAces > 0.5 ? acesFilm(col) : col / (col + vec3(1.0));

                // 绕 0.5 中灰拉对比，避免整体变亮或变暗
                col = clamp((col - 0.5) * Contrast + 0.5, 0.0, 1.0);
                // Lift 只抬暗部：越亮的地方加得越少
                col = clamp(col + Lift * (1.0 - col), 0.0, 1.0);
                col = pow(col, vec3(1.0 / max(Gamma, 0.01)));

                fragColor = vec4(col, 1.0);
            }
            """;

    private static final String CODERAIN = """
            // 代码雨 / Code Rain
            // 一列列字符从上往下掉，尾部渐暗。
            // 教程里的原版跑在核心着色器里，字符是从原版 ascii 字体图集上采样的；
            // 后处理链每层只有一个输入采样器，拿不到字体图集，所以这里把字形换成程序化点阵，
            // 下落节奏仍沿用教程那套「每列若干条雨滴、各自速度」的做法。
            // 延伸阅读：轩宇1725《Minecraft 着色器教程》的代码雨一章
            //
            // [en_us]
            // Columns of characters falling down with fading tails.
            // The tutorial version runs in a core shader and samples glyphs from the vanilla ASCII font atlas.
            // A post chain layer has only one input sampler and cannot reach the font atlas, so the glyphs here
            // are procedural dot matrices. The falling rhythm still follows the tutorial: several drops per
            // column, each with its own speed. Further reading: the code rain chapter of Xuanyu1725's Minecraft shader tutorial.
            //
            // @param name=Grid type=vec2 min=16 max=200 default=100,45 zh_cn=列数与行数 en_us=Grid
            // @param name=RainColor type=color3 default=#33FF66 zh_cn=雨的颜色 en_us=Rain Color
            // @param name=Speed type=float min=0 max=4 default=1.0 zh_cn=速度 en_us=Speed
            // @param name=Fallers type=float min=1 max=8 default=3 zh_cn=每列雨滴数 en_us=Fallers
            // @param name=Tail type=float min=2 max=40 default=14 zh_cn=拖尾长度 en_us=Tail
            // @param name=Darken type=float min=0 max=1 default=0.55 zh_cn=压暗原画面 en_us=Darken

            float hash(vec2 p) {
                return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453);
            }

            // 5x7 点阵字形：每一行取一个 hash，把它的二进制小数位当作这一行的 5 个亮点。
            // 换字符只要换 tick，不需要任何纹理。
            float glyph(vec2 p, vec2 cell, float tick) {
                vec2 gp = (p - 0.1) / 0.8;              // 留出 20% 间隙，字符之间才分得开
                if (gp.x < 0.0 || gp.x >= 1.0 || gp.y < 0.0 || gp.y >= 1.0) {
                    return 0.0;
                }
                vec2 g = floor(gp * vec2(5.0, 7.0));
                float h = hash(cell + vec2(g.y * 7.13, tick));
                return step(0.5, fract(h * exp2(g.x + 1.0)));
            }

            void main() {
                vec3 src = texture(InSampler, texCoord).rgb;

                vec2 grid = max(floor(Grid), vec2(1.0));
                vec2 uv = texCoord * grid;
                vec2 cell = floor(uv);
                vec2 pix = fract(uv);

                // GTTime 由编辑器驱动，可暂停、可回拨——用 GameTime 就没法定格看某一帧
                float t = GTTime * Speed;
                float tail = max(Tail, 1.0);

                // texCoord.y 向上为正，所以「从顶部往下数第几行」要拿 grid.y 减
                float rowFromTop = grid.y - cell.y;

                float brightness = 0.0;
                for (int i = 0; i < 8; i++) {
                    if (float(i) >= Fallers) {
                        break;
                    }
                    // 每条雨滴一个稳定但看着随机的速度与起始相位
                    float sp = 0.3 + hash(vec2(cell.x, float(i) + 0.5)) * 0.7;
                    float head = mod(t * sp * 18.0 + hash(vec2(cell.x, float(i))) * grid.y,
                                     grid.y + tail);
                    float d = head - rowFromTop;         // 离雨滴头部还有多少行
                    if (d >= 0.0 && d < tail) {
                        brightness = max(brightness, 1.0 - d / tail);
                    }
                }

                float tick = floor(t * 6.0 + hash(cell) * 10.0);
                float c = glyph(pix, cell, tick);

                vec3 col = src * (1.0 - Darken);
                col += RainColor * c * brightness;

                fragColor = vec4(col, 1.0);
            }
            """;
}
