// 终结者 T-800 / Terminator HUD
// 终结者 HUD 的着色器层：
// 红色机器视觉滤镜、按深度描出的物体轮廓、CRT 扫描线、动态噪点、暗角，
// 中央那套断环准心与两侧数据条，以及死亡时那段老电视关机动画。
//
// 完整的 HUD 还需要一整套 Java 侧的扫描系统（锁定框、目标数据、音效、罗盘）。
// 那些东西着色器画不了，也不属于效果层——这里只还原画面本身，
// 准心位置与关机进度改成可拖的参数，于是关机动画可以在编辑器里逐帧看。
//
// [en_us]
// Terminator T-800 red machine-vision HUD.
// The shader layer of a Terminator HUD: the red machine-vision filter, depth-based object outlines, CRT scanlines, animated noise, vignette,
// the broken-ring crosshair in the center with data bars on both sides, and the old-TV power-off animation played
// on death.
//
// A full HUD would also need a whole Java-side scanning system (lock-on boxes, target data, sounds, compass).
// None of that can be drawn by a shader, and it doesn't belong in an effect layer either, so only the visuals are
// recreated here. The crosshair position and shutdown progress became draggable parameters, so the power-off
// animation can be scrubbed frame by frame in the editor.
//
// @group 机器视觉 / Machine Vision
// @param name=FilterColor type=color3 default=#FF1C12 zh_cn=滤镜颜色 en_us=Filter Color
// @param name=FilterStrength type=float min=0 max=1 default=0.82 zh_cn=滤镜强度 en_us=Filter Strength
// @param name=Contrast type=float min=0.5 max=2.5 default=1.25 zh_cn=对比度 en_us=Contrast
// @param name=ScanlineStrength type=float min=0 max=0.8 default=0.18 zh_cn=扫描线强度 en_us=Scanlines
// @param name=ScanSpeed type=float min=0 max=5 default=1 zh_cn=扫描速度 en_us=Scan Speed
// @param name=NoiseStrength type=float min=0 max=0.25 default=0.035 zh_cn=噪点强度 en_us=Noise
// @param name=VignetteStrength type=float min=0 max=1 default=0.55 zh_cn=暗角强度 en_us=Vignette

// @group 战术界面 / HUD
// @param name=Reticle type=bool default=1 zh_cn=瞄准界面 en_us=Reticle
// @param name=HudColor type=color3 default=#FFFFFF zh_cn=界面颜色 en_us=HUD Color
// @param name=HudOpacity type=float min=0 max=1.5 default=0.85 zh_cn=界面亮度 en_us=HUD Brightness
// @param name=ReticleSize type=float min=0.1 max=0.45 default=0.22 zh_cn=瞄准框大小 en_us=Reticle Size
// @param name=ReticleCenter type=vec2 default=0.5,0.5 zh_cn=瞄准框位置 en_us=Reticle Center

// @group 关机动画 / Shutdown
// @param name=Shutdown type=float min=0 max=1 default=0 zh_cn=关机进度 en_us=Shutdown desc_zh_cn=0 是正常画面，拖到 1 走完收束成白点的全过程 desc_en_us=0 is the normal picture, dragging to 1 plays the whole collapse down to a white dot

// @group 作用范围 / Scope
// @param name=SkyOn type=bool default=1 zh_cn=天空着色 en_us=Shade Sky
// @param name=GroundOn type=bool default=1 zh_cn=地面着色 en_us=Shade Ground

float hash21(vec2 p) {
    return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453);
}

// 一段带圆头的线段。HUD 上每一根刻度都是拿它拼出来的
float segment(vec2 p, vec2 a, vec2 b, float width) {
    vec2 pa = p - a;
    vec2 ba = b - a;
    float h = clamp(dot(pa, ba) / dot(ba, ba), 0.0, 1.0);
    return 1.0 - smoothstep(width, width * 2.0, length(pa - ba * h));
}

// 深度描边。阈值跟着中心深度走：反转深度下近处是 1、远处趋近 0，
// 远处的深度差天然小得多，用固定阈值的话十几格外就一根边都描不出来
float depthEdge(vec2 uv) {
    vec2 px = 1.0 / max(InDepthSize, vec2(1.0));
    float c = gtDepth(uv);
    float h = abs(gtDepth(uv + vec2(px.x, 0.0)) - gtDepth(uv - vec2(px.x, 0.0)));
    float v = abs(gtDepth(uv + vec2(0.0, px.y)) - gtDepth(uv - vec2(0.0, px.y)));
    float scale = max(0.00002, c * 0.02);
    return smoothstep(scale, scale * 4.0, h + v);
}

// 断环准心：外圈按角度切成四段、内圈一个小环、十字四根、四角括号，再补四个斜向刻度
float reticleShape(vec2 p, float size, float pixel) {
    float angle = atan(p.y, p.x);
    float ringMask = smoothstep(0.12, 0.32, abs(sin(angle * 4.0)));
    float ring = (1.0 - smoothstep(pixel, pixel * 2.5, abs(length(p) - size * 0.48))) * ringMask;
    float inner = 1.0 - smoothstep(pixel, pixel * 2.0, abs(length(p) - size * 0.12));

    float cross4 = 0.0;
    cross4 += segment(p, vec2(-size * 0.72, 0.0), vec2(-size * 0.18, 0.0), pixel);
    cross4 += segment(p, vec2(size * 0.18, 0.0), vec2(size * 0.72, 0.0), pixel);
    cross4 += segment(p, vec2(0.0, -size * 0.72), vec2(0.0, -size * 0.18), pixel);
    cross4 += segment(p, vec2(0.0, size * 0.18), vec2(0.0, size * 0.72), pixel);

    float outer = size;
    float inset = size * 0.68;
    float corners = 0.0;
    corners += segment(p, vec2(-outer, -outer), vec2(-inset, -outer), pixel * 1.4);
    corners += segment(p, vec2(-outer, -outer), vec2(-outer, -inset), pixel * 1.4);
    corners += segment(p, vec2(outer, -outer), vec2(inset, -outer), pixel * 1.4);
    corners += segment(p, vec2(outer, -outer), vec2(outer, -inset), pixel * 1.4);
    corners += segment(p, vec2(-outer, outer), vec2(-inset, outer), pixel * 1.4);
    corners += segment(p, vec2(-outer, outer), vec2(-outer, inset), pixel * 1.4);
    corners += segment(p, vec2(outer, outer), vec2(inset, outer), pixel * 1.4);
    corners += segment(p, vec2(outer, outer), vec2(outer, inset), pixel * 1.4);

    float ticks = 0.0;
    ticks += segment(p, vec2(-size * 0.43, -size * 0.43), vec2(-size * 0.35, -size * 0.35), pixel * 1.2);
    ticks += segment(p, vec2(size * 0.43, -size * 0.43), vec2(size * 0.35, -size * 0.35), pixel * 1.2);
    ticks += segment(p, vec2(-size * 0.43, size * 0.43), vec2(-size * 0.35, size * 0.35), pixel * 1.2);
    ticks += segment(p, vec2(size * 0.43, size * 0.43), vec2(size * 0.35, size * 0.35), pixel * 1.2);

    return clamp(ring + inner + cross4 + corners + ticks, 0.0, 1.0);
}

// 左右两列长短不一的数据条。长度是哈希出来的定值而不是动画——
// 一直跳动的数据条会把注意力从准心上抢走
float dataBars(vec2 p, float pixel) {
    float bars = 0.0;
    for (int i = 0; i < 8; i++) {
        float y = -0.29 + float(i) * 0.035;
        float width = 0.035 + hash21(vec2(float(i), 3.0)) * 0.075;
        bars += segment(p, vec2(-0.54, y), vec2(-0.54 + width, y), pixel * 1.5);
        bars += segment(p, vec2(0.54 - width, -y), vec2(0.54, -y), pixel * 1.5);
    }
    return clamp(bars, 0.0, 1.0);
}

void main() {
    vec3 src = texture(InSampler, texCoord).rgb;
    bool sky = gtIsSky(texCoord);
    // 关机动画一旦开始就接管整个画面，这时作用范围不再适用——
    // 半块屏幕正常、半块在关机会非常怪
    if (((sky && SkyOn < 0.5) || (!sky && GroundOn < 0.5)) && Shutdown <= 0.0) {
        fragColor = vec4(src, 1.0);
        return;
    }

    float luminance = dot(src, vec3(0.299, 0.587, 0.114));
    vec3 vision = FilterColor * (0.08 + luminance * 1.25);
    vision += FilterColor * depthEdge(texCoord) * 0.45;
    vision = clamp((vision - 0.5) * Contrast + 0.5, 0.0, 1.0);
    vec3 color = mix(src, vision, FilterStrength);

    // 扫描线按渲染目标高度的实际像素排，所以缩放窗口时线不会跟着变粗
    float scanPhase = (texCoord.y * OutSize.y + GTTime * ScanSpeed * 45.0) * 3.14159265;
    float scanline = 0.5 + 0.5 * sin(scanPhase);
    color *= 1.0 - (1.0 - scanline) * ScanlineStrength;

    color += FilterColor * (hash21(gl_FragCoord.xy + floor(GTTime * 60.0)) - 0.5) * NoiseStrength;

    float aspect = OutSize.x / max(OutSize.y, 1.0);
    vec2 screenP = (texCoord - 0.5) * vec2(aspect, 1.0);
    color *= 1.0 - smoothstep(0.25, 0.78, length(screenP)) * VignetteStrength;

    if (Reticle > 0.5) {
        vec2 p = (texCoord - ReticleCenter) * vec2(aspect, 1.0);
        float pixel = 1.5 / max(OutSize.y, 1.0);
        float hud = max(reticleShape(p, ReticleSize, pixel), dataBars(p, pixel));
        // 一道自下而上慢慢扫过的光带，是这套 HUD 里唯一持续运动的东西
        float beam = 1.0 - smoothstep(0.0, 0.012, abs(texCoord.y - fract(GTTime * ScanSpeed * 0.12)));
        hud = max(hud, beam * 0.22);
        color = mix(color, max(color, HudColor), clamp(hud * HudOpacity, 0.0, 1.0));
    }

    if (Shutdown > 0.0) {
        float progress = clamp(Shutdown, 0.0, 1.0);
        if (progress < 0.64) {
            // 第一段：画面纵向压成一条带子，内容跟着一起压而不是被裁掉——
            // 老电视关机时看到的就是被挤扁的图像
            float phase = smoothstep(0.0, 1.0, progress / 0.64);
            float halfHeight = mix(0.5, 0.0022, phase);
            float band = 1.0 - step(halfHeight, abs(texCoord.y - 0.5));
            vec2 squeezed = vec2(texCoord.x,
                clamp(0.5 + (texCoord.y - 0.5) / max(halfHeight * 2.0, 0.0005), 0.0, 1.0));
            vec3 inner = texture(InSampler, squeezed).rgb;
            vec3 red = mix(inner,
                FilterColor * (0.15 + dot(inner, vec3(0.299, 0.587, 0.114)) * 1.35), 0.78);
            float rim = 1.0 - smoothstep(0.0, 3.5 / max(OutSize.y, 1.0),
                abs(abs(texCoord.y - 0.5) - halfHeight));
            color = red * band + FilterColor * rim * (0.45 + phase * 0.55);
        } else {
            // 第二段：那条带子从两头往中间收，最后剩一个越缩越小的白点
            float phase = smoothstep(0.0, 1.0, (progress - 0.64) / 0.36);
            float halfWidth = mix(0.5, 0.0, phase);
            float line = (1.0 - smoothstep(1.0, 2.8, abs(texCoord.y - 0.5) * OutSize.y))
                       * (1.0 - step(halfWidth, abs(texCoord.x - 0.5)));
            float dotRadius = mix(4.5, 0.8, phase);
            float spot = 1.0 - smoothstep(dotRadius, dotRadius + 1.5,
                length((texCoord - 0.5) * OutSize));
            vec3 tint = mix(FilterColor, vec3(1.0), smoothstep(0.30, 0.82, phase));
            color = tint * max(line, spot) * (1.0 - smoothstep(0.90, 1.0, phase));
        }
    }

    fragColor = vec4(clamp(color, 0.0, 1.0), 1.0);
}
