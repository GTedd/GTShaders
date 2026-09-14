// 抽帧顿挫 / Stutter
// 把时间量化成一格一格的，运动因此变成一跳一跳的——定格动画和低帧率录像的那种顿挫感。
// 量化的不是画面而是<b>时间</b>：所有跟着 GTTime 走的位移、缩放、抖动一起被钉在格点上，
// 于是整条画面在一格之内完全静止，换格的瞬间整体跳一下。
//
// 换格瞬间叠一层残影，模拟快门没跟上——纯粹的跳格会显得过于干净。
//
// [en_us]
// Choppy stop-motion stutter from quantized time.
// Time is quantized into discrete steps, so motion jumps from step to step, like the choppiness of stop-motion or
// low-frame-rate footage. What gets quantized isn't the image but <b>time</b>: every offset, zoom and shake that
// follows GTTime is pinned to the grid together, so the whole picture freezes within a step and jumps all at once
// when the step changes.
//
// An afterimage is layered in at each step change to mimic a shutter that can't keep up. Pure step jumps look
// too clean.
//
// @param name=StepRate type=float min=1 max=30 default=8 zh_cn=每秒格数 en_us=Steps / Second
// @param name=Jitter type=float min=0 max=0.08 default=0.014 zh_cn=每格位移 en_us=Per-step Offset
// @param name=Rotate type=float min=0 max=0.1 default=0.012 zh_cn=每格旋转 en_us=Per-step Rotation
// @param name=Zoom type=float min=0 max=0.12 default=0.02 zh_cn=每格缩放 en_us=Per-step Zoom

// @group 换格 / Step Change
// @param name=GhostAmount type=float min=0 max=1 default=0.4 zh_cn=换格残影 en_us=Step Ghost
// @param name=GhostFade type=float min=2 max=40 default=14 zh_cn=残影消退 en_us=Ghost Fade
// @param name=Flicker type=float min=0 max=0.6 default=0.12 zh_cn=亮度闪动 en_us=Flicker

float hash11(float p) {
    return fract(sin(p * 61.7419) * 33917.7321);
}

vec2 poseAt(float frame, vec2 uv, float aspx) {
    float jx = (hash11(frame * 1.7) - 0.5) * 2.0;
    float jy = (hash11(frame * 4.3) - 0.5) * 2.0;
    float rot = (hash11(frame * 8.9) - 0.5) * 2.0 * Rotate;
    float zm = 1.03 + (hash11(frame * 2.1) - 0.5) * 2.0 * Zoom;

    vec2 p = uv - 0.5;
    p.x *= aspx;
    float c = cos(rot);
    float s = sin(rot);
    p = mat2(c, -s, s, c) * p;
    p.x /= aspx;
    return p / max(zm, 1e-3) + 0.5 + vec2(jx / max(aspx, 1e-3), jy) * Jitter;
}

void main() {
    float rate = max(StepRate, 1.0);
    float frame = floor(GTTime * rate);
    float age = GTTime * rate - frame;   // 0..1，本格已经走了多少

    float aspx = OutSize.x / max(OutSize.y, 1.0);
    vec3 col = texture(InSampler, clamp(poseAt(frame, texCoord, aspx), vec2(0.0), vec2(1.0))).rgb;

    // 换格瞬间把上一格叠进来一点，跳变才不至于生硬
    float ghost = exp(-age * GhostFade) * GhostAmount;
    if (ghost > 0.002) {
        vec3 prev = texture(InSampler, clamp(poseAt(frame - 1.0, texCoord, aspx), vec2(0.0), vec2(1.0))).rgb;
        col = mix(col, max(col, prev), ghost);
    }

    col *= 1.0 + (hash11(frame * 12.3) - 0.5) * 2.0 * Flicker;
    fragColor = vec4(col, 1.0);
}
