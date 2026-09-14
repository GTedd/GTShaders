// 鬼影闪现 / Ghost Flash
// 每隔一阵，画面里突然浮出一张扭曲的负片重影，只停留几帧就消失，同时伴一次画面撕裂。
// 出现的间隔是随机的：等间隔的惊吓两次之后就变成节拍器，只有猜不到才吓得到人。
//
// 重影本身是画面自己的负片经过强扭曲得到的——没有贴图，所以在任何场景里都不会显得"贴上去"。
//
// [en_us]
// A distorted negative ghost that flashes up at random.
// Every so often a distorted negative ghost image suddenly surfaces in the picture, lingers for only a few
// frames and vanishes, along with a screen tear. The gaps between appearances are random: evenly spaced
// scares turn into a metronome after two rounds, and only what you can't predict can frighten you.
//
// The ghost itself is the picture's own negative, heavily distorted. There is no texture, so it never looks
// "pasted on" in any scene.
//
// @param name=MeanGap type=float min=0.5 max=30 default=6 zh_cn=平均间隔(秒) en_us=Mean Gap
// @param name=Randomness type=float min=0 max=1 default=0.7 zh_cn=间隔随机度 en_us=Gap Randomness
// @param name=HoldTime type=float min=0.02 max=1 default=0.12 zh_cn=停留时长(秒) en_us=Hold Time

// @group 鬼影 / Apparition
// @param name=Warp type=float min=0 max=0.4 default=0.12 zh_cn=扭曲量 en_us=Warp
// @param name=Scale type=float min=0.5 max=6 default=1.6 zh_cn=扭曲尺度 en_us=Warp Scale
// @param name=GhostColor type=color3 default=#CFE8FF zh_cn=鬼影色 en_us=Ghost Tint
// @param name=Opacity type=float min=0 max=1 default=0.75 zh_cn=不透明度 en_us=Opacity

// @group 伴随 / Accompaniment
// @param name=Tear type=float min=0 max=0.2 default=0.06 zh_cn=画面撕裂 en_us=Screen Tear
// @param name=Darken type=float min=0 max=1 default=0.45 zh_cn=瞬间压暗 en_us=Darken

float hash11(float p) {
    return fract(sin(p * 39.317) * 45183.7391);
}

void main() {
    // 用「事件序号」而不是固定周期：每次的间隔由上一个序号随机决定
    float slot = floor(GTTime / max(MeanGap, 0.2));
    float jitter = (hash11(slot * 7.13) - 0.5) * 2.0 * Randomness;
    float fireAt = slot * MeanGap + MeanGap * 0.5 * (1.0 + jitter);
    float age = GTTime - fireAt;

    float k = age >= 0.0 && age < HoldTime
        ? sin(clamp(age / max(HoldTime, 1e-3), 0.0, 1.0) * 3.14159265)
        : 0.0;
    // 闪现期间再叠一层高频闪断，鬼影因此是"卡帧"出现的
    k *= step(0.35, fract(GTTime * 37.0));

    vec2 uv = texCoord;
    if (Tear > 0.0 && k > 0.001) {
        float row = floor(texCoord.y * 60.0);
        uv.x += (hash11(row + slot * 3.7) - 0.5) * Tear * k;
    }
    uv = clamp(uv, vec2(0.0), vec2(1.0));
    vec3 col = texture(InSampler, uv).rgb;

    if (k > 0.001) {
        // 鬼影：画面自己被强扭曲后的负片
        vec2 g = texCoord - 0.5;
        float a = length(g) * Scale * 6.2831853;
        vec2 gv = clamp(texCoord + vec2(sin(a + slot), cos(a * 1.3 - slot)) * Warp, vec2(0.0), vec2(1.0));
        vec3 ghost = (1.0 - texture(InSampler, gv).rgb) * GhostColor;
        col = mix(col, ghost, k * Opacity);
        col *= 1.0 - Darken * k;
    }

    fragColor = vec4(col, 1.0);
}
