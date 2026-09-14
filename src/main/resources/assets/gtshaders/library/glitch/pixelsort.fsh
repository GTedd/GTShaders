// 像素排序 / Pixel Sort
// 真正的像素排序需要在一整行上做排序，片段着色器做不到（每个像素互不相通）。
// 这里用一个能在单像素内完成的近似：在触发区间内，把采样点沿排序方向
// 「吸附」到该区间的起点，于是同一段内的像素趋于相同——视觉上就是被拉成长条。
//
// [en_us]
// Fake pixel sorting that stretches runs into streaks.
// Real pixel sorting needs to sort across a whole row, which a fragment shader can't do (pixels can't see each
// other). This uses an approximation that works within a single pixel: inside a triggered span, the sample point
// is "snapped" along the sort direction to the start of that span, so pixels in the same span tend toward the
// same value. Visually, they get stretched into long streaks.
//
// @param name=Threshold type=float min=0 max=1 default=0.55 zh_cn=触发亮度 en_us=Trigger Luma
// @param name=Adapt type=float min=0 max=1 default=0.8 zh_cn=自动适应亮度 en_us=Auto Adapt desc_zh_cn=按当前画面的平均亮度自动调整触发线。关掉之后昏暗场景里没有一个像素够得着阈值，等于没开 desc_en_us=Rescales the trigger by the frame's average brightness; with it off nothing qualifies in dark scenes
// @param name=Length type=float min=0 max=0.3 default=0.09 zh_cn=拉伸长度 en_us=Sort Length
// @param name=Vertical type=bool default=0 zh_cn=竖直方向 en_us=Vertical
// @param name=Steps type=int min=2 max=24 default=12 zh_cn=搜索步数 en_us=Search Steps
// @param name=Invert type=bool default=0 zh_cn=改为暗部触发 en_us=Trigger On Dark

float lum(vec3 c) {
    return dot(c, vec3(0.2126, 0.7152, 0.0722));
}

// 画面平均亮度：4×4 稀疏采样。绝对触发线在昏暗场景里没有任何像素够得着，
// 效果就完全静默了——玩家看到的是「有时候有、有时候没有」，最难排查的那种。
float sceneLuma() {
    float s = 0.0;
    for (int i = 0; i < 16; i++) {
        vec2 p = (vec2(float(i % 4), float(i / 4)) + 0.5) * 0.25;
        s += lum(texture(InSampler, p).rgb);
    }
    return s / 16.0;
}

void main() {
    vec2 dir = Vertical > 0.5 ? vec2(0.0, 1.0) : vec2(1.0, 0.0);
    vec2 step0 = dir * Length / float(max(Steps, 1));
    // 只算一次：搜索循环里每步都算的话要多跑几百次采样
    float rel = clamp(sceneLuma() * 1.35 + 0.05, 0.03, 0.92);
    float th = mix(Threshold, Invert > 0.5 ? rel * 0.75 : rel, clamp(Adapt, 0.0, 1.0));

    vec3 src = texture(InSampler, texCoord).rgb;

    // 沿反方向回溯，找到当前这段"符合条件的连续区间"的起点
    vec2 anchor = texCoord;
    for (int i = 1; i <= 24; i++) {
        if (i > Steps) {
            break;
        }
        vec2 uv = texCoord - step0 * float(i);
        if (uv.x < 0.0 || uv.y < 0.0 || uv.x > 1.0 || uv.y > 1.0) {
            break;
        }
        float l = lum(texture(InSampler, uv).rgb);
        bool hit = Invert > 0.5 ? l < th : l > th;
        if (!hit) {
            break;
        }
        anchor = uv;
    }

    float here = lum(src);
    // 变量名不能叫 alive：那是 GLSL 的保留字，某些驱动会直接拒编译
    bool sorted = Invert > 0.5 ? here < th : here > th;
    fragColor = vec4(sorted ? texture(InSampler, anchor).rgb : src, 1.0);
}
