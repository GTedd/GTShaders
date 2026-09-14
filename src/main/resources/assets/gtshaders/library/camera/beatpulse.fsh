// 节拍脉冲 / Beat Pulse
// 按 BPM 打拍子：每一拍画面猛地涨一下再收回去，同时从边缘冲进来一圈彩色环。
// 卡点视频的底子——把 BPM 填成音乐的实际速度，剩下的它自己对齐。
//
// 重拍（每小节第一拍）会做得更狠一点，不然一串等强度的脉冲听起来像节拍器而不是音乐。
//
// [en_us]
// Screen pulse on every beat, driven by BPM.
// On every beat the picture swells sharply and snaps back, while a colored ring rushes in from the edges. It's the
// backbone of beat-synced edits: set BPM to the music's actual tempo and it lines up the rest on its own.
//
// Downbeats (the first beat of each bar) hit harder; otherwise a run of equally strong pulses sounds like a
// metronome instead of music.
//
// @param name=Bpm type=float min=40 max=240 default=120 zh_cn=BPM en_us=BPM
// @param name=Offset type=float min=-1 max=1 default=0 zh_cn=相位偏移(秒) en_us=Phase Offset desc_zh_cn=整体前后挪，用来把第一拍对齐到音频的起点 desc_en_us=Nudge the whole pattern to line up with the first beat of your audio
// @param name=BeatsPerBar type=float min=1 max=8 default=4 zh_cn=每小节拍数 en_us=Beats / Bar
// @param name=Accent type=float min=0 max=2 default=0.8 zh_cn=重拍加成 en_us=Downbeat Accent

// @group 脉冲 / Pulse
// @param name=Punch type=float min=0 max=0.3 default=0.06 zh_cn=缩放冲量 en_us=Zoom Punch
// @param name=Decay type=float min=1 max=30 default=8 zh_cn=衰减速度 en_us=Decay
// @param name=Brighten type=float min=0 max=2 default=0.5 zh_cn=提亮 en_us=Brighten

// @group 光环 / Ring
// @param name=RingColor type=color3 default=#FF3DAE zh_cn=光环色 en_us=Ring Color
// @param name=RingGain type=float min=0 max=3 default=1.2 zh_cn=光环强度 en_us=Ring Gain
// @param name=RingWidth type=float min=0.01 max=0.5 default=0.12 zh_cn=光环宽度 en_us=Ring Width

void main() {
    float period = 60.0 / max(Bpm, 1.0);
    float pos = (GTTime + Offset) / period;
    float beat = floor(pos);
    float age = (pos - beat) * period;

    // 重拍：小节的第一拍额外加成
    float bar = max(BeatsPerBar, 1.0);
    float isDown = mod(beat, bar) < 0.5 ? 1.0 : 0.0;
    float gain = 1.0 + isDown * Accent;

    float env = exp(-age * Decay) * gain;

    vec2 asp = vec2(OutSize.x / max(OutSize.y, 1.0), 1.0);
    float zoom = 1.0 + Punch * env;
    vec2 uv = clamp((texCoord - 0.5) / zoom + 0.5, vec2(0.0), vec2(1.0));
    vec3 col = texture(InSampler, uv).rgb;

    // 光环从边缘往里收，一拍走完刚好到中心
    // 半径归一化到「画面正中=0，四角=1」。除数必须是<b>半</b>对角线：
    // asp=(w/h,1)，角落处 |(texCoord-0.5)*asp| 只有 length(asp) 的一半，
    // 拿整条对角线去除的话 r 最大只到 0.5——所有按半径推进的动画都只走完一半就没了，
    // 按半径衰减的效果也只发挥出一半强度。
    float r = length((texCoord - 0.5) * asp) / max(length(asp) * 0.5, 1e-4);
    float front = 1.0 - clamp(age / period, 0.0, 1.0);
    float ring = smoothstep(RingWidth, 0.0, abs(r - front)) * gain;

    col *= 1.0 + Brighten * env * 0.6;
    col += RingColor * ring * RingGain * 0.5;

    fragColor = vec4(col, 1.0);
}
