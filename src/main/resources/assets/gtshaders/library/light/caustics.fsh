// 水面焦散 / Caustics
// 水波把阳光聚成一张不断游动的网投在场景上。焦散纹用的是经典做法：
// 若干条不同方向的正弦波叠加，取它们的<b>绝对值的倒数</b>——波峰相遇处形成极亮的细线，
// 那正是光线被会聚的地方。
//
// 只加不减：焦散是额外投上去的光，压暗会让画面看起来像蒙了层脏东西。
//
// [en_us]
// Water waves focus sunlight into a moving net on the scene.
// The pattern uses the classic technique: sum several sine waves running in different directions and take
// the <b>reciprocal of their absolute value</b>. Where the crests meet, very bright thin lines form, which is
// exactly where the light converges.
//
// It only adds, never subtracts: caustics are extra light cast on top, and darkening would make the picture
// look like it is covered in a film of grime.
//
// @param name=Scale type=float min=1 max=40 default=9 zh_cn=网纹密度 en_us=Pattern Scale
// @param name=Speed type=float min=0 max=3 default=0.45 zh_cn=游动速度 en_us=Flow Speed
// @param name=Layers type=float min=2 max=8 default=5 zh_cn=波的层数 en_us=Wave Layers
// @param name=Sharpness type=float min=1 max=12 default=5 zh_cn=亮线锐度 en_us=Line Sharpness

// @group 投射 / Projection
// @param name=Perspective type=float min=0 max=1 default=0.5 zh_cn=近大远小 en_us=Perspective desc_zh_cn=让画面下方的网纹更大更慢，模拟投在地面上而不是贴在屏幕上 desc_en_us=Makes the pattern larger and slower toward the bottom, as if projected onto a floor
// @param name=SceneMask type=float min=0 max=1 default=0.5 zh_cn=只投在亮处 en_us=Follow Lit Areas

// @group 外观 / Look
// @param name=CausticColor type=color3 default=#CFF3FF zh_cn=焦散色 en_us=Caustic Color
// @param name=Gain type=float min=0 max=4 default=1.2 zh_cn=亮度 en_us=Gain
// @param name=Chroma type=float min=0 max=1 default=0.3 zh_cn=色散 en_us=Dispersion

float caustic(vec2 p, float t, float layers, float sharp) {
    float v = 0.0;
    for (int i = 0; i < 8; i++) {
        if (float(i) >= layers) {
            break;
        }
        float a = float(i) * 2.399963;   // 黄金角：方向分布最均匀
        vec2 dir = vec2(cos(a), sin(a));
        v += sin(dot(p, dir) + t * (0.7 + float(i) * 0.13));
    }
    v /= max(layers, 1.0);
    // 取绝对值的倒数：波峰相遇的那条细线会被推到很亮，这就是焦散
    return pow(clamp(1.0 - abs(v) * 1.6, 0.0, 1.0), sharp);
}

void main() {
    vec2 asp = vec2(OutSize.x / max(OutSize.y, 1.0), 1.0);
    vec3 src = texture(InSampler, texCoord).rgb;

    // 近大远小：越靠下代表越近，网纹放大、流速变慢
    float persp = mix(1.0, 0.45 + texCoord.y * 1.1, Perspective);
    vec2 p = texCoord * asp * Scale / max(persp, 0.2);
    float t = GTTime * Speed / max(persp, 0.2);

    float layers = max(floor(Layers), 2.0);
    float c = caustic(p, t, layers, Sharpness);

    // 色散：三个通道用略微不同的相位，亮线边缘就有了彩边
    vec3 caus = vec3(c);
    if (Chroma > 0.001) {
        caus.r = caustic(p * (1.0 + 0.012 * Chroma), t, layers, Sharpness);
        caus.b = caustic(p * (1.0 - 0.012 * Chroma), t + 0.3, layers, Sharpness);
    }

    float g = dot(src, vec3(0.2126, 0.7152, 0.0722));
    float mask = mix(1.0, smoothstep(0.1, 0.55, g), SceneMask);

    fragColor = vec4(src + caus * CausticColor * Gain * mask, 1.0);
}
