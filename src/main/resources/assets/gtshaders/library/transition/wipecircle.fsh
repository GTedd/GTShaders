// 圆形擦除 / Circle Wipe
// 一个从指定圆心扩散的圆把画面切成「已过场」和「未过场」两半，交界处带一圈发光软边。
// 转场的手感全在这圈边上：硬边像贴纸，太软又看不出是个圆，所以边宽单独给一个参数。
//
// 半径刻意算到画面最远的那个角再多一点，否则进度拉满时四个角落还留着没吃掉的残片。
//
// [en_us]
// A circle wipe expanding from a chosen center.
// The growing circle splits the picture into "transitioned" and "not yet transitioned" parts, with a glowing soft
// edge at the border. The whole feel of the transition is in that edge: a hard edge looks like a sticker, and one
// that is too soft no longer reads as a circle, so edge width gets its own parameter.
//
// The radius deliberately reaches a little past the farthest corner of the picture. Otherwise, at full progress,
// leftover fragments would remain in the corners.
//
// @param name=AutoPlay type=bool default=1 zh_cn=随时间自动播放 en_us=Auto Play
// @param name=Duration type=float min=0.2 max=10 default=1.6 zh_cn=转场时长(秒) en_us=Duration
// @param name=Hold type=float min=0 max=10 default=0.6 zh_cn=两端停留(秒) en_us=Hold desc_zh_cn=盖满和揭开之后各停多久再往回走 desc_en_us=How long it rests at each end before reversing
// @param name=Progress type=float min=0 max=1 default=0.45 zh_cn=手动进度 en_us=Progress desc_zh_cn=关掉自动播放后由它决定停在哪一帧，方便逐帧对齐剪辑点 desc_en_us=Used when Auto Play is off, so you can park on an exact frame

// @group 形状 / Shape
// @param name=Center type=vec2 min=0 max=1 default=0.5,0.5 zh_cn=圆心 en_us=Center
// @param name=Invert type=bool default=0 zh_cn=反向(由外向内) en_us=Invert
// @param name=EdgeWidth type=float min=0.001 max=0.3 default=0.04 zh_cn=边缘软度 en_us=Edge Softness

// @group 外观 / Look
// @param name=CoverColor type=color3 default=#000000 zh_cn=覆盖色 en_us=Cover Color
// @param name=EdgeColor type=color3 default=#FFFFFF zh_cn=边缘光色 en_us=Edge Color
// @param name=EdgeGlow type=float min=0 max=4 default=1.6 zh_cn=边缘亮度 en_us=Edge Glow

// 往复播放：盖上 → 停一下 → 揭开 → 停一下，然后重来。
// 这里绝不能写成 clamp(GTTime / Duration)——GTTime 只增不减，跑过一遍之后恒等于 1，
// 画面会永远停在「已经盖满」那一帧。而玩家把效果加进工程时 GTTime 早就几百秒了，
// 于是「加上去只看到一块死板的颜色，怎么调都不动」。
float gtPingPong(float duration, float hold) {
    float d = max(duration, 0.01);
    float h = max(hold, 0.0);
    float age = mod(GTTime, (d + h) * 2.0);
    if (age < d) {
        return age / d;
    }
    if (age < d + h) {
        return 1.0;
    }
    if (age < d * 2.0 + h) {
        return 1.0 - (age - d - h) / d;
    }
    return 0.0;
}

void main() {
    float t = AutoPlay > 0.5 ? gtPingPong(Duration, Hold) : Progress;
    if (Invert > 0.5) {
        t = 1.0 - t;
    }

    // 等比校正：不做的话宽屏上「圆」会被拉成椭圆
    vec2 asp = vec2(OutSize.x / max(OutSize.y, 1.0), 1.0);
    vec3 src = texture(InSampler, texCoord).rgb;

    // 半对角线：角落处 |(texCoord-0.5)*asp| 只有 length(asp) 的一半，
    // 用整条的话进度走到一半画面就已经盖满，后半程完全是死时间
    float maxR = length(asp) * 0.5;
    float r = length((texCoord - Center) * asp);
    float radius = t * maxR * 1.05;
    float soft = max(EdgeWidth * maxR, 1e-4);

    float covered = smoothstep(radius, radius - soft, r);
    float ring = smoothstep(soft, 0.0, abs(r - radius));

    vec3 col = mix(src, CoverColor, covered);
    col += EdgeColor * ring * EdgeGlow;
    fragColor = vec4(col, 1.0);
}
