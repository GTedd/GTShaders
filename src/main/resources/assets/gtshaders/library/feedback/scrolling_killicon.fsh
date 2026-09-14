// 滚动击杀图标 / Scrolling Kill Icon
// 大号图标从 5 倍缩放快速落定，停留约三秒后淡出，按击杀类型切换贴图：
//   - 位置：屏幕下方偏上
//   - 展示：停留 3.25s，淡入 0.3s，淡出 0.1s
//   - 缩放：5 → 1，0.3s 内三次缓出
//   - 图标：默认全部用内置的像素骷髅占位；把 PNG 拖进编辑器替换第一张，
//     其余几张在参数面板的贴图行点「选择」切换到已拖入的 PNG
// 时序参考 gd656killicon（MIT）的默认预设。
//
// [en_us]
// Kill icon that zooms in and holds for a few seconds.
// A large icon settles quickly from 5x scale, stays about three seconds, then fades out; its texture follows the kill type:
//   - Position: low on the screen but raised a bit
//   - Display: hold 3.25s, fade in 0.3s, fade out 0.1s
//   - Scale: 5 → 1, cubic ease-out within 0.3s
//   - Icons: all default to the built-in pixel skull; drop a PNG into the editor to replace the first one,
//     and use "Pick" on the other texture rows to switch to PNGs you dropped in
// Timing adapted from the default preset of gd656killicon (MIT).

// @texture name=DefaultIcon path=gtshaders:gui/killicon width=64 height=64 bilinear=1 zh_cn=普通击杀 en_us=Default
// @texture name=HeadshotIcon path=gtshaders:gui/killicon width=64 height=64 bilinear=1 zh_cn=爆头 en_us=Headshot
// @texture name=ExplosionIcon path=gtshaders:gui/killicon width=64 height=64 bilinear=1 zh_cn=爆炸 en_us=Explosion
// @texture name=CritIcon path=gtshaders:gui/killicon width=64 height=64 bilinear=1 zh_cn=暴击 en_us=Crit
// @texture name=AssistIcon path=gtshaders:gui/killicon width=64 height=64 bilinear=1 zh_cn=助攻 en_us=Assist
// @texture name=DestroyVehicleIcon path=gtshaders:gui/killicon width=64 height=64 bilinear=1 zh_cn=载具摧毁 en_us=Destroy Vehicle
//
// @param name=UseAnchor type=bool default=0 zh_cn=跟随锚点事件 en_us=Use Anchor Event
// @param name=AnchorSlot type=anchor min=0 max=7 default=0 zh_cn=锚点槽位 en_us=Anchor Slot
// @param name=PreviewInterval type=float min=0.5 max=30 default=3.5 zh_cn=自动预览间隔(秒) en_us=Auto Preview Interval
// @param name=KillType type=int min=0 max=5 default=0 zh_cn=击杀类型 en_us=Kill Type desc_zh_cn=0普通 1爆头 2爆炸 3暴击 4助攻 5载具摧毁 desc_en_us=0 Normal, 1 Headshot, 2 Explosion, 3 Crit, 4 Assist, 5 Destroy Vehicle
// @param name=Position type=vec2 min=0 max=1 default=0.5,0.35 zh_cn=图标位置 en_us=Icon Position
// @param name=Size type=float min=0.02 max=0.3 default=0.085 zh_cn=图标尺寸 en_us=Icon Size
// @param name=Scale type=float min=0.1 max=2 default=0.35 zh_cn=整体缩放 en_us=Scale
// @param name=StartScale type=float min=0.5 max=10 default=5.0 zh_cn=入场起始缩放 en_us=Start Scale
// @param name=AnimationDuration type=float min=0.05 max=2 default=0.3 zh_cn=淡入/缩放时长(秒) en_us=Animation Duration
// @param name=DisplayDuration type=float min=0.2 max=10 default=3.25 zh_cn=停留时长(秒) en_us=Display Duration
// @param name=FadeOutDuration type=float min=0.02 max=2 default=0.1 zh_cn=淡出时长(秒) en_us=Fade Out Duration
// @param name=StretchX type=float min=0.2 max=3 default=1.0 zh_cn=水平拉伸 en_us=Stretch Horizontal
// @param name=StretchY type=float min=0.2 max=3 default=1.0 zh_cn=垂直拉伸 en_us=Stretch Vertical
// @param name=FlipX type=bool default=0 zh_cn=水平翻转 en_us=Flip Horizontal
// @param name=FlipY type=bool default=0 zh_cn=垂直翻转 en_us=Flip Vertical
// @param name=Zoom type=float min=0.2 max=4 default=1.0 zh_cn=图片缩放 en_us=Image Zoom
// @param name=Offset type=vec2 min=-1 max=1 default=0,0 zh_cn=图片偏移 en_us=Image Offset
// @param name=Rotate type=float min=-180 max=180 default=0 zh_cn=旋转角度 en_us=Rotation
// @param name=Opacity type=float min=0 max=1 default=1 zh_cn=不透明度 en_us=Opacity
// @param name=Color type=color3 default=#FFFFFF zh_cn=染色 en_us=Tint
// @param name=Gain type=float min=0 max=4 default=1.6 zh_cn=亮度 en_us=Gain

vec4 pickIcon(vec2 uv) {
    if (KillType < 0.5) return texture(DefaultIcon, uv);
    if (KillType < 1.5) return texture(HeadshotIcon, uv);
    if (KillType < 2.5) return texture(ExplosionIcon, uv);
    if (KillType < 3.5) return texture(CritIcon, uv);
    if (KillType < 4.5) return texture(AssistIcon, uv);
    return texture(DestroyVehicleIcon, uv);
}

void main() {
    vec2 asp = vec2(OutSize.x / max(OutSize.y, 1.0), 1.0);
    vec3 col = texture(InSampler, texCoord).rgb;

    float life;
    float alive;
    if (UseAnchor > 0.5) {
        int slot = AnchorSlot;
        if (slot < 0) slot = 0;
        if (slot > 7) slot = 7;
        if (gtAnchorValid(slot)) {
            life = clamp(gtAnchorLife(slot), 0.0, 1.0);
            alive = gtAnchorStrength(slot);
        } else {
            life = 0.0;
            alive = 0.0;
        }
    } else {
        float period = max(PreviewInterval, DisplayDuration + FadeOutDuration + 0.05);
        life = clamp(mod(GTTime, period) / max(DisplayDuration + FadeOutDuration, 0.05), 0.0, 1.0);
        alive = 1.0;
    }
    if (alive <= 0.001) {
        fragColor = vec4(col, 1.0);
        return;
    }

    // 原版时序：把 life 拆成 展示段 + 淡出段
    float total = max(DisplayDuration + FadeOutDuration, 0.05);
    float elapsed = life * total;
    float animDuration = max(AnimationDuration, 0.001);
    float fadeOut = max(FadeOutDuration, 0.001);

    // 淡入 + 缩放：0.3s 三次缓出
    float fadeInProgress = clamp(elapsed / animDuration, 0.0, 1.0);
    float easedIn = 1.0 - pow(1.0 - fadeInProgress, 3.0);
    float baseAlpha = clamp(easedIn, 0.0, 1.0);

    float alpha;
    if (elapsed <= DisplayDuration) {
        alpha = baseAlpha;
    } else {
        float fadeProgress = clamp((elapsed - DisplayDuration) / fadeOut, 0.0, 1.0);
        alpha = baseAlpha * (1.0 - fadeProgress);
    }
    alpha *= alive;

    float progress = fadeInProgress;
    float easedScale = 1.0 - pow(1.0 - progress, 3.0);
    float scale = mix(StartScale, 1.0, easedScale) * Scale;

    // 原版 64 基准下的最终尺寸；Size 为屏幕 UV 尺寸，再乘整体缩放
    vec2 iconSize = vec2(Size * scale * asp.x * StretchX, Size * scale * StretchY);
    vec2 uv = (texCoord - Position) / iconSize + 0.5;
    // 图片编辑：缩放、偏移、旋转、翻转
    uv = (uv - 0.5) / max(Zoom, 0.001) + 0.5;
    uv += Offset * 0.1;
    if (abs(Rotate) > 0.01) {
        vec2 rp = uv - 0.5;
        float rad = radians(Rotate);
        float c = cos(rad);
        float s2 = sin(rad);
        rp = mat2(c, -s2, s2, c) * rp;
        uv = rp + 0.5;
    }
    if (FlipX > 0.5) uv.x = 1.0 - uv.x;
    if (FlipY > 0.5) uv.y = 1.0 - uv.y;
    if (uv.x < 0.0 || uv.x > 1.0 || uv.y < 0.0 || uv.y > 1.0) {
        fragColor = vec4(col, 1.0);
        return;
    }

    vec4 icon = pickIcon(uv);
    col = mix(col, icon.rgb * Color, clamp(icon.a * Gain * alpha * Opacity, 0.0, 1.0));
    fragColor = vec4(col, 1.0);
}
