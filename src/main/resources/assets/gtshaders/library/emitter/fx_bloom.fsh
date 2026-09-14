// 载体闪光 / Emitter Bloom
// 挂在载体上的闪光。三种形状：圆形辉光、放射状喷溅、十字星芒，
// 由每个载体的「子类型」各自决定。
//
// 和 fx_light 的分工：光源是「一个持续在照的东西」，闪光是「炸出来的一下」。
// 所以这一版吃生命周期（gtAnchorLife），配事件型触发器用——绑「死亡时」或
// 「受伤时」，效果自己按曲线起落，不用写任何时间控制。
//
// 放射状那一型借鉴 some_of_fx 里 splashBloom 的思路：让每个方向上的半径都不一样，
// 看着像溅出来的一团而不是一个规规矩矩的圆。那边采一张噪声贴图，
// 这里改成程序化 hash——省一个 @texture 声明，这种低频噪声用不着贴图那点细节。
// 角度要先映射到单位圆上再采噪声，否则 atan 在 ±π 处的跳变会让闪光正左方劈出一条缝。
//
// [en_us]
// A burst of bloom attached to an emitter.
// Three shapes: round glow, radial splash and cross-shaped star glint, chosen per emitter by its "Sub-type".
//
// Division of labor with fx_light: a light is "something that keeps shining", a flash is "a single burst".
// So this one runs on lifetime (gtAnchorLife) and is meant for event triggers. Bind it to "On death" or
// "On hurt" and the effect rises and falls along its own curve, with no timing code to write.
//
// The radial type borrows the idea of splashBloom from some_of_fx: give every direction a different radius,
// so it looks like a splattered blob rather than a neat circle. That one samples a noise texture;
// here it is a procedural hash instead. That saves an @texture declaration, and low-frequency noise like this
// doesn't need a texture's detail. Map the angle onto the unit circle before sampling the noise, otherwise the
// jump of atan at ±π splits a seam straight to the left of the flash.
//
// @param name=Color type=color3 default=#B8E4FF zh_cn=闪光色 en_us=Bloom Color
// @param name=Intensity type=float min=0 max=6 default=2.2 zh_cn=亮度 en_us=Intensity
// @param name=Size type=float min=0.2 max=10 default=3 zh_cn=尺寸 en_us=Size
// @param name=Splash type=float min=0 max=1 default=0.6 zh_cn=喷溅不规则度 en_us=Splash Irregularity desc_zh_cn=只对放射状生效。0 是规规矩矩的圆，1 是炸开的一团 desc_en_us=Splash type only; 0 is a clean circle
// @param name=Spikes type=float min=2 max=12 default=4 zh_cn=星芒数 en_us=Spike Count desc_zh_cn=只对十字星芒生效，取整数才对称 desc_en_us=Cross type only; whole numbers stay symmetric
// @param name=Speed type=float min=0 max=4 default=1 zh_cn=翻腾速度 en_us=Churn Speed
// @param name=UseLife type=bool default=1 zh_cn=跟随生命周期 en_us=Follow Lifetime desc_zh_cn=关掉就一直亮着，调形状时方便 desc_en_us=Turn off to keep it lit while tweaking the shape

float gtfHash(vec2 p) {
    return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453);
}

// 二维值噪声。放射状那一型<b>必须</b>用二维的：极角 atan 在负 x 轴上从 +π 跳到 -π，
// 拿一维噪声直接吃角度的话，跳变两侧取到毫不相干的两个值，
// 闪光正左方会劈出一条缝。把角度映射到单位圆上再采就没这问题——
// cos/sin 在 ±π 处都落在同一点，绕一圈天然闭合
float gtfNoise2(vec2 p) {
    vec2 i = floor(p);
    vec2 f = fract(p);
    f = f * f * (3.0 - 2.0 * f);
    float a = gtfHash(i);
    float b = gtfHash(i + vec2(1.0, 0.0));
    float c = gtfHash(i + vec2(0.0, 1.0));
    float d = gtfHash(i + vec2(1.0, 1.0));
    return mix(mix(a, b, f.x), mix(c, d, f.x), f.y);
}

float gtfCircle(vec2 p, float r) {
    // exp 而不是 smoothstep：闪光的核心要过曝、边缘要拖很长的尾，
    // smoothstep 两端都是平的，画出来像一个贴纸
    return exp(-length(p) / max(r, 1e-4));
}

float gtfSplash(vec2 p, float r, float seed) {
    float len = length(p);
    float theta = atan(p.y, p.x);
    // 沿角度取噪声，再加一个随时间前进的偏移，让这团东西自己在翻腾。
    // 圆的半径 1.9 决定绕一圈走过多少噪声距离，也就是喷溅分成几个瓣——
    // 放大就碎成毛刺了
    vec2 ring = vec2(cos(theta), sin(theta)) * 1.9;
    float n = gtfNoise2(ring + vec2(seed, GTTime * Speed * 0.7));
    float radius = r * (1.0 + Splash * (n * 2.0 - 1.0) * 0.8);
    return exp(-len / max(radius, 1e-4));
}

float gtfCross(vec2 p, float r, float roll) {
    float len = length(p);
    float theta = atan(p.y, p.x) - roll;
    // 星芒：角度上的周期性尖峰。pow 把峰压窄，次数越高芒越细
    float spike = pow(abs(cos(theta * Spikes * 0.5)), 12.0);
    float core = exp(-len / max(r * 0.35, 1e-4));
    return core + spike * exp(-len / max(r * 1.6, 1e-4));
}

void main() {
    vec3 src = texture(InSampler, texCoord).rgb;
    vec3 glow = vec3(0.0);

    for (int i = 0; i < GT_ANCHOR_SLOTS; i++) {
        if (!gtAnchorValid(i)) {
            continue;
        }
        float r = max(gtAnchorRadius(i), 0.004) * Size;
        vec2 p = gtEmitterLocal(i);

        int kind = gtEmitterType(i);
        float shape;
        if (kind == 1) {
            // 每个载体给一个不同的噪声种子，否则视野里几个闪光会一模一样地同步翻腾。
            // 种子取自转相位——它本来就是运行时为「别让同类载体同拍」准备的
            shape = gtfSplash(p, r, gtEmitterSpin(i) + float(i) * 7.31);
        } else if (kind == 2) {
            shape = gtfCross(p, r, gtEmitterRoll(i));
        } else {
            shape = gtfCircle(p, r);
        }

        float k = gtAnchorStrength(i) * gtAnchorVisible(i);
        if (UseLife > 0.5) {
            // strength 已经带过缓动曲线了，这里再乘一道原始 life 的反向衰减，
            // 让尾巴收得比曲线本身更干净一点——闪光拖着不走比它太短更难看
            k *= 1.0 - gtAnchorLife(i) * 0.65;
        }
        glow += Color * shape * k * (1.0 + gtEmitterCustom1(i));
    }

    fragColor = vec4(src + glow * Intensity, 1.0);
}
