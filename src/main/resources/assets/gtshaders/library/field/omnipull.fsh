// 全向引力 / Omni Pull
// 一个「转过身去也还在」的引力源。和「黑洞」的区别只有一条，但那一条决定了能不能拿它做武器：
// 黑洞画的是屏幕上那个点，目标一转出视野就什么都没有了；这个效果在目标看不见时
// 改用**方向**继续作用——奇点在你左后方，整个画面就朝左边被拽过去。
//
// 三种处境用的是三套不同的表现，靠 gtAnchorState 分开：
//   · 屏内     —— 径向吸入，和黑洞一样，以奇点为中心把像素往回拉
//   · 屏外(前方) —— 投影坐标仍然是准的（只是中心在画面外），继续用径向，边缘自然被拉向那一侧
//   · 背后     —— 投影坐标已经没有意义了，改成整幅画面朝 gtAnchorScreenDir 平移拖拽
//
// 关键的一点：**不要乘 gtAnchorVisible**。那个值在「屏幕外」和「背后」都是 0，
// 乘上去效果就会在转身的瞬间整个消失——那正是这个效果要解决的问题。
// 该乘的是 gtAnchorStrength（只受缓动与生命周期影响）和 gtAnchorFront（前后的平滑过渡）。
//
// 用法：锚点页新增一条绑定，来源选「实体类型」或「指定实体」、触发选「一直有效」或「死亡时」，
// 再把这一层的「锚点绑定」参数指向它。做手雷类武器时配「死亡时 + 钉在事件坐标」。
//
// [en_us]
// A gravity source that keeps pulling after you turn away.
// It differs from "Black Hole" in just one way, but that one way decides whether it can be used as a weapon:
// the black hole draws that point on the screen, so once the target leaves the view there is nothing left. When
// the target can't be seen, this effect keeps acting through **direction** instead: if the singularity is
// behind you to the left, the whole picture gets dragged to the left.
//
// Three situations get three different treatments, told apart by gtAnchorState:
//   · On screen             - radial pull, like the black hole, dragging pixels back toward the singularity
//   · Off screen (in front) - the projected position is still accurate (the center is just outside the frame),
//                             so keep the radial pull and the edges naturally get dragged toward that side
//   · Behind                - the projected position no longer means anything, so the whole picture is shifted
//                             and dragged toward gtAnchorScreenDir instead
//
// The key point: **do not multiply by gtAnchorVisible**. It is 0 both "off screen" and "behind",
// so multiplying by it makes the effect vanish the moment you turn around, which is exactly the problem this
// effect solves. Multiply by gtAnchorStrength (affected only by easing and lifetime) and gtAnchorFront (the
// smooth front/back transition) instead.
//
// Usage: on the Anchors tab add a binding with Source "Entity type" or "Specific entity" and Trigger "Always"
// or "On death", then point this layer's "Anchor Binding" parameter at it. For grenade-style weapons, use
// "On death + Stick to event position".
//
// @param name=AnchorSlot type=anchor min=0 max=7 default=0 zh_cn=锚点绑定 en_us=Anchor Binding desc_zh_cn=读哪一条锚点绑定。没装 mod 时全部无效，效果自动退回屏幕中心 desc_en_us=Which anchor binding to read. Without the mod every slot is invalid and the effect falls back to screen centre
// @param name=Strength type=float min=0 max=0.3 default=0.08 zh_cn=引力强度 en_us=Pull Strength
// @param name=Falloff type=float min=0.2 max=6 default=2 zh_cn=距离衰减指数 en_us=Falloff Power desc_zh_cn=屏内径向拉扯按到奇点距离的这个次方衰减。2 接近真实引力 desc_en_us=Radial pull falls off with this power of the distance. 2 approximates real gravity
// @param name=BehindGain type=float min=0 max=2 default=1 zh_cn=背后强度 en_us=Behind Gain desc_zh_cn=目标在身后时整屏拖拽的倍率。调 0 就退回「看不见就没有」的老行为 desc_en_us=Multiplier for the whole-screen drag when the target is behind you. Set 0 to fall back to "invisible means nothing"
// @param name=WorldFalloff type=float min=0 max=64 default=24 zh_cn=世界衰减距离(格) en_us=World Falloff desc_zh_cn=超过这个距离引力衰减到零。0 表示不按距离衰减 desc_en_us=Pull fades to zero beyond this many blocks. 0 disables distance falloff
// @param name=Chroma type=float min=0 max=1 default=0.35 zh_cn=色散 en_us=Chromatic
// @param name=Darken type=float min=0 max=1 default=0.5 zh_cn=向心压暗 en_us=Darken Toward

void main() {
    vec2 asp = vec2(OutSize.x / max(OutSize.y, 1.0), 1.0);
    vec3 col = texture(InSampler, texCoord).rgb;

    if (!gtAnchorValid(AnchorSlot)) {
        // 没装 mod、或这条绑定没解算出目标：什么都不做，别硬凑一个屏幕中心的假奇点
        fragColor = vec4(col, 1.0);
        return;
    }

    // 强度只跟缓动曲线和距离走，与看不看得见无关 —— 这是「转身还在」的前提
    float amp = Strength * gtAnchorStrength(AnchorSlot);
    if (WorldFalloff > 0.0) {
        amp *= 1.0 - smoothstep(0.0, WorldFalloff, gtAnchorDistance(AnchorSlot));
    }
    if (amp <= 0.0) {
        fragColor = vec4(col, 1.0);
        return;
    }

    vec2 offset;
    float weight;
    if (gtAnchorBehind(AnchorSlot)) {
        // 背后：投影坐标是个占位值，只有方向可信。整幅画面朝那个方向平移拖拽。
        // 乘 (1 - gtAnchorFront) 让它从「侧后方」到「正后方」是连续加强的，不会在
        // 越过视野边界的那一帧突然跳一下
        float behind = 1.0 - gtAnchorFront(AnchorSlot);
        offset = gtAnchorScreenDir(AnchorSlot) * amp * BehindGain * behind;
        weight = behind * BehindGain;
    } else {
        // 屏内与屏外(前方)共用径向：屏外时中心虽然不在画面里，但坐标是准的，
        // 靠近那一侧的像素照样被正确地拉过去
        vec2 d = gtAnchorDelta(AnchorSlot);
        float r = max(length(d), 1e-4);
        float pull = amp / pow(r + 0.15, Falloff);
        // 拉扯量不能超过到中心的距离，否则近处会翻面糊成噪点（黑洞那条注释里的第 1 点）
        pull = min(pull, r * 0.9);
        offset = -normalize(d) * pull;
        weight = pull / max(amp, 1e-4);
    }

    vec2 uv = texCoord + offset / asp;
    if (Chroma > 0.0) {
        // 色散沿拖拽方向拆开，三通道各偏一点，边缘就有了引力透镜的味道
        vec2 s = offset / asp * Chroma * 0.35;
        col = vec3(
            texture(InSampler, uv + s).r,
            texture(InSampler, uv).g,
            texture(InSampler, uv - s).b);
    } else {
        col = texture(InSampler, uv).rgb;
    }

    col *= 1.0 - clamp(weight, 0.0, 1.0) * Darken;
    fragColor = vec4(col, 1.0);
}
