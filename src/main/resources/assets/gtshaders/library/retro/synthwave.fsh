// 合成器浪潮 / Synthwave
// 赛博富士风格：
// 天空换成紫红渐变加一轮带扫描条纹的落日，地面按世界坐标铺一层会呼吸的洋红网格，
// 再从脚下推一道青色的展开边缘出去。
//
// 网格钉在世界坐标上，不是屏幕上——走动时它跟着地面走，转视角时不跟着转。
// 这一条靠 gtWorldPos 一族内置量，导出成纯资源包后没有相机数据，网格会退回跟着视角转。
//
// [en_us]
// A synthwave sky with a striped sunset over a magenta grid.
// A "Cyber Fuji" look: the sky becomes a purple-red gradient with a scan-striped setting sun, the ground gets a breathing magenta grid
// laid out in world coordinates, and a cyan reveal edge then sweeps outward from your feet.
//
// The grid is pinned to world coordinates, not to the screen: it moves with the ground as you walk and does not
// turn when you turn your view. This relies on the gtWorldPos family of built-ins. When exported as a plain
// resource pack there is no camera data, so the grid falls back to turning with the view.
//
// @group 网格 / Grid
// @param name=GridSpeed type=float min=0 max=10 default=0.3 zh_cn=网格流速 en_us=Grid Speed
// @param name=PulseSpeed type=float min=0 max=10 default=2 zh_cn=呼吸速度 en_us=Pulse Speed

// @group 落日 / Sun
// @param name=SunAzimuth type=float min=0 max=360 default=180 zh_cn=方位角 en_us=Azimuth
// @param name=SunElevation type=float min=5 max=80 default=5 zh_cn=高度角 en_us=Elevation
// @param name=SunSize type=float min=0.1 max=0.6 default=0.33 zh_cn=太阳大小 en_us=Sun Size
// @param name=SunSpeed type=float min=0 max=2 default=0.1 zh_cn=条纹速度 en_us=Stripe Speed
// @param name=SunBrightness type=float min=0 max=3 default=1 zh_cn=太阳亮度 en_us=Sun Brightness

// @group 展开 / Reveal
// @param name=Reveal type=bool default=0 zh_cn=播放展开 en_us=Play Reveal desc_zh_cn=关掉就是展开完成后的稳定态 desc_en_us=When off, shows the steady state after the reveal has finished
// @param name=RevealSpeed type=float min=0.1 max=5 default=1 zh_cn=展开速度 en_us=Reveal Speed
// @param name=RevealCycle type=float min=1 max=20 default=6 zh_cn=循环周期 en_us=Reveal Cycle

// @group 作用范围 / Scope
// @param name=SkyOn type=bool default=1 zh_cn=天空着色 en_us=Shade Sky
// @param name=GroundOn type=bool default=1 zh_cn=地面着色 en_us=Shade Ground

// 世界坐标直接拿来铺网格，在离原点几百万格的地方会被 float32 吃掉精度、糊成一片。
// 相机的整数块坐标先按 1024 折回来（1024 是网格周期 2 的整数倍，接缝对得上），
// 剩下的小数部分本来就是高精度的，于是网格在哪儿都稳。
vec3 gridAnchor() {
    ivec3 wrapped = CameraBlockPos - (CameraBlockPos / 1024) * 1024;
    return vec3(wrapped) + CameraOffset;
}

// 三个轴各算一遍线条，再按法线把「贴着这个面」的那一轴权重压掉——
// 否则地板上会额外浮出一层竖直方向的线
float gridLines(vec3 p, vec3 n, float lineWidth, float scale) {
    vec3 coord = p * scale;
    vec3 deriv = fwidth(coord);
    vec3 pattern = abs(fract(coord - 0.5) - 0.5);
    vec3 dist = pattern / (max(deriv, 0.001) * lineWidth);
    vec3 lines = 1.0 - min(dist, 1.0);
    vec3 w = abs(n);
    return clamp(lines.x * (1.0 - w.x) + lines.y * (1.0 - w.y) + lines.z * (1.0 - w.z), 0.0, 1.0);
}

// 落日：一枚圆盘 + 一圈光晕，横向条纹越往下越密，是这个风格的招牌
float synthSun(vec2 uv, float time) {
    float radius = length(uv);
    float disc = 1.0 - smoothstep(SunSize * 0.94, SunSize, radius);
    float bloom = 1.0 - smoothstep(SunSize, SunSize * 1.8, radius);
    float wave = sin((uv.y + time * SunSpeed) * 60.0) + clamp(uv.y * 12.0, -1.0, 1.0);
    float stripes = smoothstep(-0.15, 0.15, wave);
    return disc * mix(0.35, 1.0, stripes) + bloom * 0.45;
}

void main() {
    vec3 src = texture(InSampler, texCoord).rgb;
    bool sky = gtIsSky(texCoord);
    if ((sky && SkyOn < 0.5) || (!sky && GroundOn < 0.5)) {
        fragColor = vec4(src, 1.0);
        return;
    }

    // 地面分支和后面的展开都要用距离，算一次存着——每多问一次 gtDistance
    // 就是一次深度采样，而这是全屏跑的
    float dist = 0.0;
    vec3 synth;
    if (sky) {
        float az = radians(SunAzimuth);
        float el = radians(SunElevation);
        float horizontal = cos(el);
        vec3 sunDir = normalize(vec3(cos(az) * horizontal, sin(el), sin(az) * horizontal));

        vec3 viewDir = gtWorldDir(texCoord);
        float sunShape = 0.0;
        vec2 sunUV = vec2(0.0);
        // 背对太阳时整块天空都不用算——dot 为负就是在身后
        if (dot(viewDir, sunDir) > 0.0) {
            vec3 sunRight = normalize(cross(sunDir, vec3(0.0, 1.0, 0.0)));
            vec3 sunUp = cross(sunRight, sunDir);
            sunUV = vec2(dot(viewDir, sunRight), dot(viewDir, sunUp));
            sunShape = synthSun(sunUV, GTTime);
        }
        vec3 sunColor = mix(vec3(1.0, 0.0, 0.5), vec3(1.0, 0.8, 0.0),
                            smoothstep(-0.3, 0.3, sunUV.y));
        synth = mix(vec3(0.4, 0.0, 0.4), vec3(0.05, 0.0, 0.1), texCoord.y)
              + sunColor * sunShape * SunBrightness;
    } else {
        vec3 viewPos = gtViewPos(texCoord);
        dist = length(viewPos);
        // 法线重建本身要采五次深度，所以只调一次 gtViewNormal，
        // 世界法线由它转过去，而不是再调一次 gtWorldNormal
        vec3 normal = gtViewNormal(texCoord);
        float ndotv = dot(normal, normalize(-viewPos));
        vec3 worldNormal = normalize(gtViewToWorld(normal));
        vec3 pos = gridAnchor() + gtViewToWorld(viewPos);

        float grid = 0.0;
        // 只有正对着某个轴的平面才铺网格。斜面上的网格会拉成一片没法看的条纹，
        // 而 Minecraft 的世界里几乎所有面都是正交的，挡掉斜面几乎不损失什么
        float axis = max(max(abs(worldNormal.x), abs(worldNormal.y)), abs(worldNormal.z));
        if (smoothstep(0.9, 0.98, axis) > 0.01) {
            vec3 gpos = pos;
            gpos.x -= GTTime * GridSpeed;
            gpos.yz += GTTime * GridSpeed;
            grid = gridLines(gpos, worldNormal, 3.0, 0.5);
        }
        grid *= sin(GTTime * PulseSpeed) * 0.4 + 0.6;

        vec3 lit = vec3(0.08, 0.05, 0.15)
                 + vec3(0.0, 1.0, 1.0) * pow(1.0 - max(ndotv, 0.0), 3.0)
                 + vec3(1.0, 0.0, 0.8) * grid * 2.0;
        synth = mix(vec3(0.1, 0.0, 0.2), lit, clamp(exp(-dist * 0.015), 0.0, 1.0));
    }

    // 展开：半径是时间的五次方，所以前一秒几乎没动、之后一下子扫出去，
    // 正是想要的那种「世界被换掉」的观感。关掉时给一个大到覆盖任何视距的常量
    float radius = 1.0e6;
    if (Reveal > 0.5) {
        radius = pow(mod(GTTime * RevealSpeed, RevealCycle), 5.0);
    }
    // 天空没有距离可比，改用视线的仰角当尺度：展开推进时从地平线往上吃
    float metric = sky ? gtViewDir(texCoord).y : dist;
    float threshold = sky ? mix(-0.8, 1.1, smoothstep(0.0, 1500.0, radius)) : radius;
    float softness = sky ? 0.15 : 10.0;

    float inside = 1.0 - smoothstep(threshold, threshold + softness, metric);
    float edge = smoothstep(threshold, threshold + softness, metric)
               * (1.0 - smoothstep(threshold + (sky ? 0.02 : 1.0),
                                   threshold + (sky ? 0.2 : 12.0), metric));

    fragColor = vec4(mix(src, synth, inside) + vec3(0.5, 1.0, 1.2) * edge * 3.0, 1.0);
}
