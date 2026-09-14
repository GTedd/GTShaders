// 电弧 / Lightning Arc
// 弧光用「脊线噪声」画：把噪声取绝对值再反过来，零点附近就形成一条条细亮线，
// 天然带分叉，比手工画折线自然得多。整屏闪光和弧线分开控制，才能做出爆闪的节奏。
//
// [en_us]
// Electric arcs with a separately controlled screen flash.
// The arcs are drawn with "ridge noise": take the absolute value of the noise and invert it, and thin bright
// lines form around the zeros. They branch on their own and look far more natural than hand-drawn zigzags.
// The full-screen flash and the arcs are controlled separately, which is what makes a strobing burst rhythm
// possible.
//
// @param name=BoltColor type=color3 default=#BFD8FF zh_cn=电弧色 en_us=Bolt Color
// @param name=Density type=float min=1 max=20 default=6 zh_cn=弧线密度 en_us=Bolt Density
// @param name=Sharpness type=float min=1 max=60 default=22 zh_cn=弧线锐度 en_us=Sharpness
// @param name=Speed type=float min=0 max=10 default=3 zh_cn=窜动速度 en_us=Flicker Speed
// @param name=Flash type=float min=0 max=1 default=0.35 zh_cn=整屏爆闪 en_us=Screen Flash
// @param name=Branches type=int min=1 max=4 default=3 zh_cn=弧线层数 en_us=Bolt Layers

float hash(vec2 p) {
    return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453);
}

float noise(vec2 p) {
    vec2 i = floor(p);
    vec2 f = fract(p);
    f = f * f * (3.0 - 2.0 * f);
    return mix(mix(hash(i), hash(i + vec2(1.0, 0.0)), f.x),
               mix(hash(i + vec2(0.0, 1.0)), hash(i + vec2(1.0, 1.0)), f.x), f.y);
}

// 脊线噪声：|n - 0.5| 在 0 附近形成谷，取倒数放大就是一条亮线
float ridge(vec2 p) {
    return 1.0 - abs(noise(p) - 0.5) * 2.0;
}

void main() {
    vec3 col = texture(InSampler, texCoord).rgb;
    vec2 asp = vec2(OutSize.x / max(OutSize.y, 1.0), 1.0);
    vec2 p = (texCoord - 0.5) * asp;

    float t = GTTime * Speed;
    float bolt = 0.0;
    for (int i = 0; i < 4; i++) {
        if (i >= Branches) {
            break;
        }
        // 每层换一个尺度和相位，叠起来才有主干 + 分叉的层次
        float s = Density * (1.0 + float(i) * 0.9);
        float r = ridge(p * s + vec2(float(i) * 17.0, floor(t) * 3.0 + t));
        bolt += pow(clamp(r, 0.0, 1.0), Sharpness) / (1.0 + float(i));
    }

    // 放电是断续的：用一个跳变的随机门控制通断，连续发光会变成霓虹灯
    float gate = step(0.55, hash(vec2(floor(t * 3.0), 7.0)));
    bolt *= gate;

    col += BoltColor * bolt * 2.5;
    col += BoltColor * Flash * gate * (0.15 + 0.35 * hash(vec2(floor(t * 3.0), 19.0)));
    fragColor = vec4(col, 1.0);
}
