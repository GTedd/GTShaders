// 手持晃动 / Handheld Shake
// 给固定机位加一层「有人端着摄像机」的呼吸感：位移、微旋、缓慢推拉三样一起动。
// 三个频率刻意不成整数倍，合起来才不会周期性重复——重复是假手持最容易露馅的地方。
//
// 位移量按纵向 UV 给，横向再除以宽高比，否则宽屏上左右晃得比上下明显得多。
//
// [en_us]
// Handheld camera sway for a fixed shot.
// Gives a fixed camera the breathing feel of "someone is holding it": offset, slight rotation and a slow push-pull
// zoom all move together. The three frequencies are deliberately not integer multiples, so the combination never
// repeats periodically. Repetition is what gives fake handheld away most easily.
//
// The offset is given in vertical UV units and divided by the aspect ratio horizontally; otherwise, on a wide
// screen, side-to-side shake looks much stronger than up-and-down.
//
// @param name=Amount type=float min=0 max=3 default=1 zh_cn=总强度 en_us=Amount
// @param name=Speed type=float min=0.05 max=6 default=1 zh_cn=速度 en_us=Speed

// @group 分量 / Components
// @param name=Sway type=float min=0 max=0.06 default=0.012 zh_cn=位移幅度 en_us=Sway
// @param name=Tilt type=float min=0 max=0.15 default=0.02 zh_cn=旋转幅度(弧度) en_us=Tilt
// @param name=Breathe type=float min=0 max=0.15 default=0.02 zh_cn=推拉幅度 en_us=Breathe

// @group 细节 / Detail
// @param name=Micro type=float min=0 max=1 default=0.4 zh_cn=高频抖 en_us=Micro Jitter desc_zh_cn=叠一层更快更小的抖动，模拟手指和呼吸；关掉会显得像云台 desc_en_us=A faster, smaller layer of jitter; turn it off for a gimbal look
// @param name=EdgeZoom type=float min=1 max=1.2 default=1.03 zh_cn=预留边距 en_us=Edge Padding desc_zh_cn=先放大一点点，晃出画面时才不会露出黑边 desc_en_us=Slight pre-zoom so shaking never exposes the border

float wave(float t, float freq, float phase) {
    return sin(t * freq + phase);
}

void main() {
    float t = GTTime * Speed;
    vec2 asp = vec2(OutSize.x / max(OutSize.y, 1.0), 1.0);

    // 三个互不成整数倍的频率叠起来，周期长到看不出来
    vec2 sway = vec2(
        wave(t, 1.13, 0.0) * 0.6 + wave(t, 2.37, 1.7) * 0.3 + wave(t, 4.91, 3.1) * 0.1,
        wave(t, 0.97, 2.2) * 0.6 + wave(t, 2.71, 0.4) * 0.3 + wave(t, 5.43, 1.3) * 0.1);

    if (Micro > 0.001) {
        sway += vec2(wave(t, 13.7, 0.9), wave(t, 11.3, 2.6)) * 0.18 * Micro;
    }
    sway *= Sway * Amount;
    sway.x /= max(asp.x, 1e-3);   // 幅度统一按纵向算，宽屏才不会左右晃得更凶

    float tilt = (wave(t, 0.83, 1.1) * 0.7 + wave(t, 1.97, 2.8) * 0.3) * Tilt * Amount;
    float zoom = EdgeZoom + wave(t, 0.61, 0.5) * Breathe * Amount;

    vec2 uv = texCoord - 0.5;
    uv.x *= asp.x;
    float c = cos(tilt);
    float s = sin(tilt);
    uv = mat2(c, -s, s, c) * uv;
    uv.x /= asp.x;
    uv = uv / max(zoom, 1e-3) + 0.5 + sway;

    fragColor = vec4(texture(InSampler, clamp(uv, vec2(0.0), vec2(1.0))).rgb, 1.0);
}
