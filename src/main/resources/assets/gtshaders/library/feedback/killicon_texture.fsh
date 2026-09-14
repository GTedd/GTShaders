// 自定义贴图击杀图标 / Texture Kill Icon
// 直接把一张资源包 PNG 当作后处理采样器输入来画，效果可以做到和原版 HUD 图标几乎一致。
// post effect JSON 支持 TextureInput：声明 location 后原版会把它加载成贴图，
// 着色器里用 texture(IconSampler, uv) 采样即可。
//
// 内置了 64×64 的骷髅图标作为示例：assets/gtshaders/textures/gui/killicon.png
// 想换成自己的图，把 @texture 里的 path 改成你的贴图 id，并同步 width/height。
//
// 用法与 killconfirm 相同：配一条 ON_DEATH 锚点绑定后打开 UseAnchor。
//
// [en_us]
// Kill icon drawn from a custom texture.
// Draws a resource pack PNG directly as a post-processing sampler input, so it can look almost identical to a
// vanilla HUD icon. Post effect JSON supports TextureInput: once you declare a location, vanilla loads it as a
// texture, and the shader just samples it with texture(IconSampler, uv).
//
// A built-in 64x64 skull icon is included as an example: assets/gtshaders/textures/gui/killicon.png
// To use your own image, change the path in @texture to your texture id and update width/height to match.
//
// Usage is the same as killconfirm: add an ON_DEATH anchor binding, then turn on UseAnchor.
//
// @texture name=IconSampler path=gtshaders:gui/killicon width=64 height=64 bilinear=1 zh_cn=击杀图标 en_us=Kill Icon
//
// @param name=UseAnchor type=bool default=0 zh_cn=跟随锚点事件 en_us=Use Anchor Event
// @param name=AnchorSlot type=anchor min=0 max=7 default=0 zh_cn=锚点槽位 en_us=Anchor Slot
// @param name=Interval type=float min=0.5 max=30 default=3.0 zh_cn=自动预览间隔(秒) en_us=Auto Preview Interval
// @param name=Position type=vec2 min=0 max=1 default=0.5,0.45 zh_cn=图标位置 en_us=Icon Position
// @param name=Duration type=float min=0.2 max=4 default=1.0 zh_cn=动画时长(秒) en_us=Duration
// @param name=Size type=float min=0.05 max=0.8 default=0.18 zh_cn=图标大小 en_us=Icon Size
// @param name=StretchX type=float min=0.2 max=3 default=1.0 zh_cn=水平拉伸 en_us=Stretch Horizontal
// @param name=StretchY type=float min=0.2 max=3 default=1.0 zh_cn=垂直拉伸 en_us=Stretch Vertical
// @param name=FlipX type=bool default=0 zh_cn=水平翻转 en_us=Flip Horizontal
// @param name=FlipY type=bool default=0 zh_cn=垂直翻转 en_us=Flip Vertical
// @param name=Zoom type=float min=0.2 max=4 default=1.0 zh_cn=图片缩放 en_us=Image Zoom
// @param name=Offset type=vec2 min=-1 max=1 default=0,0 zh_cn=图片偏移 en_us=Image Offset
// @param name=Rotate type=float min=-180 max=180 default=0 zh_cn=旋转角度 en_us=Rotation
// @param name=Opacity type=float min=0 max=1 default=1 zh_cn=不透明度 en_us=Opacity
// @param name=Color type=color3 default=#FFFFFF zh_cn=染色 en_us=Tint
// @param name=Gain type=float min=0 max=5 default=2.2 zh_cn=亮度 en_us=Gain

void main() {
    vec2 asp = vec2(OutSize.x / max(OutSize.y, 1.0), 1.0);
    vec3 col = texture(InSampler, texCoord).rgb;

    vec2 center = Position;
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
        float period = max(Interval, Duration + 0.05);
        life = clamp(mod(GTTime, period) / max(Duration, 0.05), 0.0, 1.0);
        alive = 1.0;
    }
    if (alive <= 0.001) {
        fragColor = vec4(col, 1.0);
        return;
    }

    float appear = smoothstep(0.0, 0.2, life);
    float disappear = pow(1.0 - life, 1.5);
    float scale = mix(1.8, 1.0, appear);
    float alpha = appear * disappear * alive;

    // 等比方块：宽屏上图标仍是正圆/正方形，不会被拉扁
    vec2 halfSize = vec2(Size * 0.5 * scale * asp.x * StretchX, Size * 0.5 * scale * StretchY);
    vec2 uv = (texCoord - center) / (halfSize * 2.0) + 0.5;
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

    vec4 icon = texture(IconSampler, uv);
    // 用图自带 alpha 做混合，rgb 乘上染色、亮度与不透明度
    col = mix(col, icon.rgb * Color, clamp(icon.a * Gain * alpha * Opacity, 0.0, 1.0));
    fragColor = vec4(col, 1.0);
}
