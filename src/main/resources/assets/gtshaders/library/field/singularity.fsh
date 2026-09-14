// 奇点炸弹 / Singularity Bomb
// 参考《some_of_fx》数据包的奇点炸弹，还原「触发的全过程」：
//
//   蓄力期 —— 黑洞在落点长大，周围的光被一点一点吸走（世界变暗，
//             平方级增强，开头一小段不动）；吸积盘越来越亮，
//             偶有闪电劈下；画面向中心拖曳（引力透镜 + 张力环）。
//   爆发期 —— 全屏白闪，一圈冲击波从视界炸开（快环强扭曲 +
//             慢长波持续扩张），画面震动，烟尘扩散，世界变暗解除。
//   消退期 —— 辉光退去，慢波继续向外扩张，世界恢复原样。
//
// 全程由一个锚点事件驱动：按 B 触发当前选中的锚点，「仅手动」触发器的默认时长
// 6 秒——蓄力 ~3.6 秒 + 爆发消退 ~2.4 秒，和原版数据包的节奏一致。
//
// 绑到实体上时记得看一眼锚点面板的「屏幕半径上限」：黑洞的视界大小跟着
// gtAnchorRadius 走，而那个值随距离反比增长——走近到一格以内，不设限的话
// 视界会覆盖整个屏幕，画面全黑。默认 0.35 就是为这一档准备的。
// 想不配锚点直接欣赏完整动画，把 FixedCenter 打开即可循环播放。
//
// [en_us]
// A singularity bomb that charges, detonates and fades.
// Based on the singularity bomb from the "some_of_fx" data pack, recreating "the whole trigger sequence":
//
//   Charge - a black hole grows at the landing point and the surrounding light is drained bit by bit (the
//            world darkens, ramping up quadratically, with a short still moment at the start). The accretion
//            disk grows brighter, lightning strikes now and then, and the picture is dragged toward the center
//            (gravitational lensing + tension ring).
//   Burst  - a full-screen white flash and a shockwave blasting out from the horizon (a fast ring with strong
//            distortion + a slow long wave that keeps expanding). The screen shakes, dust spreads, and the
//            darkening lifts.
//   Fade   - the glow fades, the slow wave keeps expanding outward, and the world returns to normal.
//
// The whole sequence is driven by one anchor event: press B to trigger the selected anchor. The "Manual only"
// trigger lasts 6 seconds by default (charge ~3.6 s + burst and fade ~2.4 s), matching the pacing of the
// original data pack.
//
// When binding it to an entity, check "Screen Radius Cap" in the anchor panel: the horizon size follows
// gtAnchorRadius, which grows as distance shrinks. Within one block, with no cap the horizon covers the
// whole screen and everything goes black. The default of 0.35 is there for exactly that case.
// To watch the full animation without any anchor, turn on FixedCenter and it plays on a loop.
//
// @param name=AnchorSlot type=anchor min=0 max=7 default=0 zh_cn=锚点槽位 en_us=Anchor Slot
// @param name=FixedCenter type=int min=0 max=1 default=0 zh_cn=固定中心演示 en_us=Fixed Center Demo desc_zh_cn=1=忽略锚点，用下面的 Center 固定坐标循环播放完整生命周期（预览效果、导出成无 mod 资源包时用）；0=世界锚点，只在触发时播放，平时画面干净 desc_en_us=1 = ignore anchors and loop the full lifetime at the fixed Center position (for previewing / no-mod export); 0 = world anchor, plays only while triggered, idle otherwise
// @param name=Center type=vec2 min=0 max=1 default=0.5,0.45 zh_cn=固定中心 en_us=Fixed Center desc_zh_cn=固定中心演示模式用的屏幕位置 desc_en_us=Screen position used in fixed-center demo mode
// @param name=ExplodeAt type=float min=0.1 max=0.95 default=0.6 zh_cn=爆发时机 en_us=Explode At desc_zh_cn=生命周期走到多少比例时引爆（白闪 + 冲击波 + 震动）。0.6 即蓄力占 6 成、爆发消退占 4 成，与原版节奏一致 desc_en_us=Fraction of the lifetime at which the bomb detonates (flash + shockwave + shake). 0.6 = 60% buildup, 40% blast & decay, matching the reference datapack
// @param name=Horizon type=float min=0.01 max=0.4 default=0.07 zh_cn=视界半径 en_us=Horizon
// @param name=Mass type=float min=0 max=0.3 default=0.1 zh_cn=引力强度 en_us=Mass
// @param name=Spin type=float min=-6 max=6 default=1.8 zh_cn=拖曳自旋 en_us=Frame Drag
// @param name=DiskColor type=color3 default=#A64DFF zh_cn=吸积盘色 en_us=Disk Color
// @param name=DiskWidth type=float min=0.02 max=0.6 default=0.12 zh_cn=吸积盘宽度 en_us=Disk Width
// @param name=Power type=float min=0 max=3 default=1.2 zh_cn=爆发强度 en_us=Explosion Power desc_zh_cn=白闪与冲击波的峰值强度 desc_en_us=Peak strength of the flash and shockwave
// @param name=Darkness type=float min=0 max=1.5 default=0.65 zh_cn=世界变暗 en_us=World Darkening desc_zh_cn=蓄力期周围的光被黑洞吸走的程度，爆发后逐步恢复 desc_en_us=How much the world darkens during buildup as the black hole drinks light; recovers after detonation
// @param name=Lightning type=float min=0 max=2 default=1 zh_cn=闪电强度 en_us=Lightning desc_zh_cn=蓄力期随机劈下的闪电亮度，0 关掉 desc_en_us=Brightness of random strikes during buildup; 0 disables
// @param name=Shake type=float min=0 max=2 default=0.5 zh_cn=画面震动 en_us=Screen Shake
// @param name=Vignette type=float min=0 max=1.5 default=0.55 zh_cn=压迫暗角 en_us=Vignette

// hash / value noise / fbm：hash 与 value noise 改编自 some_of_fx（Pizuka，MIT），见 THIRD_PARTY_NOTICES.md
uint gthash(uint x) {
    x += (x << 10u);
    x ^= (x >> 6u);
    x += (x << 3u);
    x ^= (x >> 11u);
    x += (x << 15u);
    return x;
}

float gtrand(vec2 p) {
    uint h = gthash(floatBitsToUint(p.x) ^ gthash(floatBitsToUint(p.y)));
    return float(h & 0x00FFFFFFu) / 16777216.0;
}

float gtnoise(vec2 p) {
    vec2 i = floor(p);
    vec2 f = fract(p);
    vec2 u = f * f * f * (f * (f * 6.0 - 15.0) + 10.0);
    float a = gtrand(i);
    float b = gtrand(i + vec2(1.0, 0.0));
    float c = gtrand(i + vec2(0.0, 1.0));
    float d = gtrand(i + vec2(1.0, 1.0));
    return mix(mix(a, b, u.x), mix(c, d, u.x), u.y);
}

float gtfbm(vec2 p) {
    float v = 0.0;
    float amp = 0.5;
    float w = 0.0;
    for (int i = 0; i < 4; i++) {
        v += gtnoise(p) * amp;
        w += amp;
        p = p * 2.03 + vec2(17.3, 9.1);
        amp *= 0.5;
    }
    return v / w;
}

void main() {
    vec2 asp = vec2(OutSize.x / max(OutSize.y, 1.0), 1.0);

    // ---- 锚点解算 ----
    bool anchored = gtAnchorValid(AnchorSlot);
    vec2 center;
    float radius;
    float gate;
    float life;
    if (anchored) {
        center = gtAnchorUV(AnchorSlot);
        radius = max(Horizon * 0.3, gtAnchorRadius(AnchorSlot));
        gate = gtAnchorStrength(AnchorSlot) * gtAnchorVisible(AnchorSlot);
        life = clamp(gtAnchorLife(AnchorSlot), 0.0, 1.0);
    } else if (FixedCenter > 0) {
        // 固定中心演示：忽略锚点，循环播放完整生命周期——不配锚点直接看整段动画、
        // 或导出成没有 mod 的纯资源包时用
        center = Center;
        radius = Horizon;
        gate = 1.0;
        life = fract(GTTime * 0.16);
    } else {
        // 世界锚点模式但当前没有点亮的事件：什么都不画，画面干净。
        // 触发后 strength 从 0 涨起、gtAnchorValid 才成立，事件播完 strength
        // 归零自动静默——效果只存在于触发期间。否则预览一开它就自己循环，
        // 按键触发测试就没意义了
        fragColor = texture(InSampler, texCoord);
        return;
    }
    if (gate < 0.002) {
        fragColor = texture(InSampler, texCoord);
        return;
    }

    // ---- 阶段曲线：蓄力 → 爆发 → 消退 ----
    float ex = clamp(ExplodeAt, 0.1, 0.95);
    float grow = smoothstep(0.0, ex * 0.9, life);    // 坍缩进度 0..1
    // 生命周期末尾：黑洞收缩淡出。EASE_OUT 曲线在尾部保持满强度，
    // 事件到期时 strength 直接归零——不淡出的话，播到最后整幅效果会「啪」地硬切消失，
    // 转视角看它的人会以为是视角切换弄没了
    float fadeOut = 1.0 - smoothstep(0.82, 1.0, life);
    float r = radius * mix(0.12, 1.0, grow) * fadeOut;   // 当前视界半径
    float burst = smoothstep(ex, 1.0, life);         // 爆发进度 0..1
    float sinceBurst = max(life - ex, 0.0);          // 爆发后经过的比例
    // 白闪只在爆发后点亮：0.05 段（约 0.3 秒）内点到峰值，然后指数衰减。
    // 以前是 exp(-sinceBurst*8)*Power 直接乘——sinceBurst 在蓄力期为 0，
    // 公式退化成恒定满强度，整个蓄力期屏幕全白，黑洞的成形过程全被盖住
    float flash = smoothstep(0.0, 0.05, sinceBurst) * exp(-sinceBurst * 7.0) * Power;

    // ---- 等比空间中的极坐标 ----
    vec2 d = (texCoord - center) * asp;
    float rr = length(d);
    vec2 dir = rr > 1e-5 ? d / rr : vec2(0.0);

    // ---- 蓄力期：世界变暗 ----
    // 前一小段不动，然后平方级吸光（对应原版 light pass 的 (t-20)² 曲线），
    // 爆发后逐步恢复
    float darkness = pow(grow, 2.0) * (1.0 - smoothstep(ex, ex + 0.3, life)) * Darkness * gate;
    // 张力环：视界外缘缓慢扩张的暗波，蓄力越久越紧
    float tensionR = r * (1.0 + 0.9 * log2(1.0 + grow * 5.0));
    float tension = smoothstep(0.0, r * 0.4, abs(rr - tensionR))
                    * grow * (1.0 - smoothstep(ex, ex + 0.06, life));
    // 蓄力期随机闪电：每 2 秒一个周期，周期开头抽签，抽中则闪约 0.15 秒
    float lp = fract(GTTime * 0.5);
    float roll = gtnoise(vec2(floor(GTTime * 0.5), 13.7));
    float lightning = smoothstep(0.55, 0.8, roll)
                      * exp(-max(lp - 0.06, 0.0) * 30.0) * grow
                      * (1.0 - smoothstep(ex, ex + 0.04, life));

    // ---- 爆发期：冲击波双环（快环强扭曲 + 慢长波）----
    float sinceEx = sinceBurst / max(1.0 - ex, 1e-4); // 爆发段内部进度 0..1
    float waveFast = r * mix(1.2, 6.0, smoothstep(0.0, 0.45, sinceEx));
    float waveSlow = r * mix(1.6, 11.0, smoothstep(0.0, 1.0, sinceEx));
    float ringF = smoothstep(r * 0.6, 0.0, abs(rr - waveFast)) * sinceBurst * exp(-sinceBurst * 5.0);
    float ringS = smoothstep(r * 2.0, 0.0, abs(rr - waveSlow)) * sinceBurst * exp(-sinceBurst * 2.2);

    // ---- 采样点偏移：引力透镜 + 拖曳自旋 + 张力内勒 + 冲击波外推 + 震动 ----
    float bend = min(Mass / max(rr * rr, 1e-4) * 0.35, rr * 0.95) * grow;
    float ang = Spin * 0.15 / max(rr, 0.03) * grow;
    float cs = cos(ang);
    float sn = sin(ang);
    vec2 rotated = vec2(dir.x * cs - dir.y * sn, dir.x * sn + dir.y * cs);
    // 震动只在爆发后出现，强度跟着白闪一起衰减
    vec2 shakeDir = vec2(gtrand(vec2(floor(GTTime * 40.0), 3.1)),
                         gtrand(vec2(floor(GTTime * 40.0), 7.7)));
    vec2 shakeUV = (shakeDir - 0.5) * 0.03 * Shake * flash
                 + (gtrand(texCoord * 91.0 + floor(GTTime * 50.0)) - 0.5) * 0.006 * Shake * flash;

    vec2 uv = texCoord - rotated * bend / asp - dir * tension * r * 0.35 / asp
            + dir * (ringF * 2.0 + ringS * 1.2) * r * 1.6 / asp + shakeUV;
    vec3 col = texture(InSampler, uv).rgb;

    // ---- 吸积盘：视界外一圈亮环，角向流动出螺旋感，蓄力期越来越亮 ----
    float disk = smoothstep(r + DiskWidth * 3.0, r, rr) * smoothstep(r * 0.93, r, rr);
    float angd = atan(d.y, d.x);
    // 唯一不能照搬原式的地方：角度<b>必须先映射到单位圆上</b>再采噪声。
    // 直接把 atan 的值当噪声输入的话，θ 在负 x 轴上从 +π 跳到 -π，
    // 两侧取到毫不相干的噪声值——画面上就是中心正左方那条缝。
    // 同一行里的 sin(angd * 4.0) 没这个问题：4 是整数，绕一圈正好闭合；
    // 噪声不是周期函数，绕一圈回不到原处。
    //
    // 圆的半径取 2.0 是<b>对着原式配的</b>：原来写 angd * 2.0，绕一圈在噪声里
    // 走过 2·2π ≈ 12.57；圆映射后周长是 2π·2，同样是 12.57。
    // 走过的噪声距离一样，螺旋的疏密就和以前一致——这一处只去缝，不改观感
    vec2 angp = vec2(cos(angd), sin(angd)) * 2.0 + vec2(0.0, GTTime * 0.6);
    float swirl = 0.5 + 0.5 * sin(angd * 4.0 + GTTime * 2.5 * Spin - rr * 36.0
                                  + gtfbm(angp) * 4.0);
    col += DiskColor * disk * swirl * (1.4 + 1.2 * grow) * gate;

    // ---- 光晕：从视界向外弥散，蓄力期最亮，爆发后被白闪盖住 ----
    float glow = exp(-rr / max(r * 2.2, 1e-4));
    col += DiskColor * glow * 0.5 * grow * (1.0 - burst * 0.6) * gate;

    // ---- 爆发烟尘：三团噪声斑从中心向随机方向扩散、淡出 ----
    float smoke = 0.0;
    for (int i = 0; i < 3; i++) {
        vec2 sp = vec2(gtrand(vec2(float(i), 3.7)), gtrand(vec2(float(i), 9.3)));
        sp = sp * 2.0 - 1.0;
        vec2 sp2 = sp * asp * r * (3.0 + sinceBurst * 5.0);
        float sd = length(d - sp2);
        float blotch = 0.4 + 0.6 * gtfbm(sp2 * 3.0 + vec2(GTTime * 0.5, 0.0));
        smoke += exp(-sd * sd * 40.0) * blotch;
    }
    smoke *= smoothstep(0.0, 0.04, sinceBurst) * exp(-sinceBurst * 3.5) * 0.3;
    col = mix(col, col * 0.55 + vec3(0.06, 0.05, 0.04), smoke);

    // ---- 压迫暗角：黑洞外围的光被吞掉，画面沉下去；爆发后恢复 ----
    float pressure = smoothstep(r * 1.4, r * 6.0, rr);
    col *= 1.0 - Vignette * pressure * (1.0 - burst * 0.85) * grow * gate;

    // ---- 事件视界：纯黑，只留极窄的一圈过渡免得出现硬锯齿 ----
    col *= mix(1.0, smoothstep(r * 0.9, r, rr), gate);

    // ---- 世界变暗：蓄力期整体压暗（黑洞把光吸走）----
    col *= 1.0 - darkness * 0.85;

    // ---- 闪电：整屏瞬时提亮 ----
    col = mix(col, vec3(1.0), clamp(lightning * 0.9 * Lightning, 0.0, 1.0));

    // ---- 白闪：爆发瞬间到顶，指数衰减 ----
    col = mix(col, vec3(1.0), clamp(flash * 0.85, 0.0, 1.0));

    // ---- 生命周期末尾整体淡出，接住上面的黑洞收缩 ----
    col *= fadeOut;

    fragColor = vec4(col, 1.0);
}
