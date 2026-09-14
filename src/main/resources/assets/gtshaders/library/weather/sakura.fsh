// 樱花 / Sakura
// 天空换成粉蓝渐变，场景压一层樱花粉，
// 屏幕前再飘一层花瓣，最后从脚下推一圈带白边的绽放波纹出去。
//
// 花瓣是屏幕空间的——它们飘在画面前面，不是长在世界里，转视角时不会跟着走。
// 这正是「镜头前的花瓣」该有的样子。
//
// [en_us]
// Cherry blossoms with drifting petals and a bloom ripple.
// The sky becomes a pink-blue gradient, the scene gets a
// cherry-blossom pink tint, a layer of petals floats in front of the screen, and finally a blooming ripple with
// a white rim spreads outward from under your feet.
//
// The petals live in screen space: they float in front of the picture instead of existing in the world, so they
// do not move when you turn the camera. That is exactly how "petals in front of the lens" should look.
//
// @group 花瓣 / Petals
// @param name=PetalDensity type=float min=0 max=2 default=1 zh_cn=花瓣密度 en_us=Petal Density
// @param name=PetalColor type=color3 default=#FFCCE6 zh_cn=花瓣颜色 en_us=Petal Color

// @group 调色 / Grade
// @param name=PinkIntensity type=float min=0 max=1 default=0.45 zh_cn=粉色强度 en_us=Pink Tint
// @param name=SkyTop type=color3 default=#99CCFF zh_cn=天空顶部 en_us=Sky Top
// @param name=SkyBottom type=color3 default=#FFCCE6 zh_cn=天空底部 en_us=Sky Bottom

// @group 绽放 / Bloom
// @param name=Reveal type=bool default=0 zh_cn=播放绽放 en_us=Play Bloom desc_zh_cn=关掉就是绽放完成后的稳定态 desc_en_us=When off, it shows the steady state after the bloom has finished
// @param name=RevealSpeed type=float min=0.1 max=5 default=1 zh_cn=绽放速度 en_us=Bloom Speed
// @param name=RevealCycle type=float min=1 max=20 default=6 zh_cn=循环周期 en_us=Bloom Cycle

// @group 作用范围 / Scope
// @param name=SkyOn type=bool default=1 zh_cn=天空着色 en_us=Shade Sky
// @param name=GroundOn type=bool default=1 zh_cn=地面着色 en_us=Shade Ground

float hash21(vec2 p) {
    p = fract(p * vec2(123.34, 456.21));
    p += dot(p, p + 45.32);
    return fract(p.x * p.y);
}

// 把屏幕切成格子，每格里放一片花瓣，再看相邻的八格——
// 只看本格的话，花瓣飘出格子边界时会被硬切掉半片
float petals(vec2 uv, float time) {
    uv *= 4.0;
    vec2 id = floor(uv);
    vec2 gv = fract(uv) - 0.5;

    float mask = 0.0;
    for (float y = -1.0; y <= 1.0; y++) {
        for (float x = -1.0; x <= 1.0; x++) {
            vec2 offs = vec2(x, y);
            float n = hash21(id + offs);
            // 向下飘 + 左右晃，相位按格子的哈希打散，否则整屏花瓣会一起摆
            float t = time * 0.5 + n * 6.28;
            vec2 p = offs + vec2(sin(t) * 0.3, cos(t * 0.5) - fract(time * 0.2 + n));
            mask += smoothstep(0.05, 0.02, length(gv - p));
        }
    }
    return mask;
}

void main() {
    vec3 src = texture(InSampler, texCoord).rgb;
    bool sky = gtIsSky(texCoord);
    if ((sky && SkyOn < 0.5) || (!sky && GroundOn < 0.5)) {
        fragColor = vec4(src, 1.0);
        return;
    }

    // 先提一点亮再往粉里推。直接加粉会显脏，先抬亮度才有那种被阳光透过的感觉
    vec3 pinkTint = vec3(1.0, 0.75, 0.85);
    vec3 graded = src * vec3(1.1, 1.05, 1.05);
    graded = mix(graded, graded * pinkTint, PinkIntensity);

    vec3 sakura = sky ? mix(SkyBottom, SkyTop, texCoord.y) : graded;

    // 花瓣按屏幕宽高比拉正，否则宽屏上会被压成椭圆
    vec2 petalUV = texCoord * vec2(OutSize.x / max(OutSize.y, 1.0), 1.0);
    sakura = mix(sakura, PetalColor, clamp(petals(petalUV, GTTime) * PetalDensity, 0.0, 1.0) * 0.8);

    // 绽放：半径是时间的四次方。关掉时给一个大到覆盖任何视距的常量
    float radius = 1.0e6;
    if (Reveal > 0.5) {
        radius = pow(mod(GTTime * RevealSpeed, RevealCycle), 4.0);
    }
    // 天空没有距离，钉在一个中景值上，让它跟着地面一起被吃进来
    float dist = sky ? 50.0 : gtDistance(texCoord);
    // 波纹起伏：让绽放边界不是一个完美的圆，而是有点呼吸感
    float wave = sin(dist * 0.2 - GTTime * 2.0) * 0.5;
    float inside = 1.0 - smoothstep(radius, radius + 15.0, dist + wave);
    float edge = smoothstep(radius, radius + 2.0, dist + wave)
               * (1.0 - smoothstep(radius, radius + 4.0, dist + wave));

    vec3 col = mix(src, sakura, inside) + edge * 0.5;
    // 四周不压暗反而提亮，是这个效果里唯一一处刻意反着来的地方——
    // 樱花的观感来自过曝而不是暗角
    float vignette = smoothstep(1.2, 0.5, length(texCoord - 0.5));
    col = mix(col, col * 1.1, 1.0 - vignette);

    fragColor = vec4(col, 1.0);
}
