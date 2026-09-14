// 合成器浪潮·经典 / Synthwave Classic
// 合成器浪潮的硬边版本：紫红渐变的天空里挂一轮被横向扫描线切开的落日，
// 地面和墙面按世界坐标铺一层会呼吸的洋红网格，边缘打一圈青色轮廓光，远处沉进紫色的雾里；
// 开场时一道青色的扫描边缘从脚下推出去，把整个世界换掉。
//
// 和效果库里的「合成器浪潮」不是同一套公式：这一版的太阳固定在西边低空、条纹是硬切的，
// 网格流得更快，几何断开处的网格会被压掉，扫描边缘和世界一起按展开遮罩混合、而不是另外叠在最上面。
//
// 网格钉在世界坐标上，靠 gtWorldPos 一族内置量。导出成纯资源包后没有相机数据，
// 网格和落日会退回跟着视角转。
//
// [en_us]
// A hard-edged synthwave sunset over a breathing magenta grid.
// The sky becomes a purple-red gradient with a setting sun cut by horizontal scan stripes. Floors and walls get a
// breathing magenta grid laid out in world coordinates, edges get a cyan rim light, and the distance sinks into
// purple fog. On start, a cyan scan edge sweeps outward from your feet and replaces the whole world.
//
// This is not the same formula as the library's "Synthwave": here the sun is fixed low in the west with hard-cut
// stripes, the grid flows faster, the grid is suppressed where geometry breaks apart, and the scan edge is blended
// through the reveal mask together with the world instead of being added on top.
//
// The grid is pinned to world coordinates through the gtWorldPos family of built-ins. When exported as a plain
// resource pack there is no camera data, so the grid and the sun fall back to turning with the view.
//
// @group 动画 / Animation
// @param name=GridSpeed type=float min=0 max=10 default=2 zh_cn=网格流速 en_us=Grid Speed
// @param name=PulseSpeed type=float min=0 max=10 default=2 zh_cn=呼吸速度 en_us=Pulse Speed
// @param name=StripeSpeed type=float min=0 max=2 default=0.2 zh_cn=条纹速度 en_us=Stripe Speed

// @group 展开 / Reveal
// @param name=RevealMode type=int min=0 max=2 default=0 zh_cn=展开方式(0一次 1循环 2关闭) en_us=Reveal Mode desc_zh_cn=0=从时间零点播一次，编辑器里按「重置时间」重播，资源包里原版时钟每 20 分钟回绕时重播；1=按循环周期重播；2=关闭，直接显示展开完成后的样子 desc_en_us=0 = play once from time zero (press Reset Time in the editor to replay; in a resource pack it replays whenever the vanilla clock wraps, every 20 minutes); 1 = replay every Reveal Cycle; 2 = off, show the finished state
// @param name=RevealSpeed type=float min=0.1 max=5 default=1 zh_cn=展开速度 en_us=Reveal Speed
// @param name=RevealCycle type=float min=5 max=30 default=8 zh_cn=循环周期(秒) en_us=Reveal Cycle desc_zh_cn=只在循环模式下生效。展开速度为 1 时天空要 4.3 秒才扫完，周期别短于它 desc_en_us=Only used in loop mode. At Reveal Speed 1 the sky takes 4.3 seconds to finish, so keep the cycle longer than that

// 下面有几处写法和直觉上的「照抄」不一样，都是同一个原因：
// 那些写法在 GLSL 规范里属于未定义行为，旧的桌面驱动碰巧给出了想要的结果，
// 而 26.3 先用 ShaderC 编成 SPIR-V，不保证还是那个结果。改写只在未定义的那一点上有差别，
// 其余情况下数值与直写完全相同。

// 世界坐标直接拿来铺网格，在离原点很远的地方会被 float32 吃掉精度。
// 相机的整数块坐标先按 1024 折回来——网格周期是 2 格，1024 是它的整数倍，接缝对得上
vec3 gridAnchor() {
    ivec3 wrapped = CameraBlockPos - (CameraBlockPos / 1024) * 1024;
    return vec3(wrapped) + CameraOffset;
}

// 越出屏幕的邻居给一个正前方 1000 米的点：它和中心的深度差必然超过门限，
// 于是屏幕最外一圈像素的法线一律退回「正对镜头」，而不是去读边缘被夹住的那个深度
vec3 fetchViewPos(vec2 uv) {
    if (uv.x < 0.0 || uv.x > 1.0 || uv.y < 0.0 || uv.y > 1.0) {
        return vec3(0.0, 0.0, -1000.0);
    }
    return gtViewPos(uv);
}

// 由深度重建视图空间法线：四邻域各取一点，横竖各挑深度差更小的那一侧做叉乘。
// 任何一侧的相对深度差超过门限就当作几何边界，直接给「正对镜头」——
// 这和内置的 gtViewNormal 不同（那个要四侧都超才退回，退回的是朝上），效果的轮廓光靠的是这一版
vec3 reconstructNormal(vec3 centerPos, vec2 uv) {
    const float EDGE_THRESHOLD = 0.003;
    vec2 offset = 1.0 / max(InDepthSize, vec2(1.0));
    vec3 l = centerPos - fetchViewPos(uv + vec2(-offset.x, 0.0));
    vec3 r = fetchViewPos(uv + vec2(offset.x, 0.0)) - centerPos;
    vec3 d = centerPos - fetchViewPos(uv + vec2(0.0, -offset.y));
    vec3 u = fetchViewPos(uv + vec2(0.0, offset.y)) - centerPos;

    // 门限随距离的平方放宽：同样一个像素，远处跨过的深度差天然更大。
    // 写成 z * z 而不是 pow(z, 2.0)：视图空间的 z 是负数，底数为负的 pow 是未定义的，
    // 给出 NaN 的驱动上边界判断会整个失效
    float p = centerPos.z * centerPos.z;
    if (abs(l.z / p) > EDGE_THRESHOLD || abs(r.z / p) > EDGE_THRESHOLD
            || abs(d.z / p) > EDGE_THRESHOLD || abs(u.z / p) > EDGE_THRESHOLD) {
        return vec3(0.0, 0.0, 1.0);
    }
    vec3 hVec = abs(l.z) < abs(r.z) ? l : r;
    vec3 vVec = abs(d.z) < abs(u.z) ? d : u;
    vec3 n = cross(hVec, vVec);
    // 两个差向量共线时叉乘为零，normalize 会得到 NaN；按边界处理
    return dot(n, n) > 0.0 ? normalize(n) : vec3(0.0, 0.0, 1.0);
}

// 三个轴各算一遍线条，再按法线把「贴着这个面」的那一轴权重压掉，否则地板上会多浮出一层竖线。
// 导数由调用方在分支外面求好传进来，理由见 main 开头
float grid(vec3 coord, vec3 gridDeriv, vec3 normal, float lineWidth) {
    vec3 gridPattern = abs(fract(coord - 0.5) - 0.5);
    // 平地上垂直于地面的那一轴导数可能恰好是 0，0/0 是 NaN。
    // 下限远小于任何真实的像素跨度，只在除零那一点上起作用
    vec3 lineDist = gridPattern / (max(gridDeriv, vec3(1e-8)) * lineWidth);
    vec3 gridLines = 1.0 - min(lineDist, 1.0);
    vec3 weight = abs(normal);
    float result = gridLines.x * (1.0 - weight.x)
                 + gridLines.y * (1.0 - weight.y)
                 + gridLines.z * (1.0 - weight.z);
    return clamp(result, 0.0, 1.0);
}

// 落日：硬边圆盘乘上横向条纹，再叠一圈柔和的光晕。
// clamp(uv.y * 15) 那一项让条纹越往下越粗，上半轮几乎是实心的
float synthwaveSun(vec2 uv, float time) {
    // smoothstep 的上下边界写反是未定义的，1 - smoothstep(低, 高) 是它的等价写法
    float disc = 1.0 - smoothstep(0.29, 0.3, length(uv));
    float bloom = 1.0 - smoothstep(0.0, 0.5, length(uv));
    float cut = 5.0 * sin((uv.y + time * StripeSpeed) * 60.0);
    cut += clamp(uv.y * 15.0, -6.0, 6.0);
    cut = clamp(cut, 0.0, 1.0);
    return clamp(disc * cut, 0.0, 1.0) + bloom * 0.6;
}

// 展开动画用的时钟。模式 2 给一个早就播完的时刻
float revealClock() {
    if (RevealMode == 2) {
        return 1.0e4;
    }
    if (RevealMode == 1) {
        return mod(GTTime, max(RevealCycle, 0.1));
    }
    return GTTime;
}

void main() {
    vec3 src = texture(InSampler, texCoord).rgb;
    bool sky = gtIsSky(texCoord);
    float t = GTTime;

    // fwidth 要放在所有分支外面求。导数是按 2×2 像素块差分出来的，写在 if 里时，
    // 块里没走进这个分支的邻居像素算出来的是什么，规范不管——
    // 在分支外对每个像素都求一遍，得到的就是想要的那个值。天空像素上这几个量用不到，算了也无妨
    vec3 viewPos = gtViewPos(texCoord);
    vec3 worldPos = gridAnchor() + gtViewToWorld(viewPos);
    // 网格流动
    worldPos.x -= t * GridSpeed;
    worldPos.yz += t * GridSpeed;
    vec3 gridCoord = worldPos * 0.5;
    vec3 gridDeriv = fwidth(gridCoord);
    float depthDelta = fwidth(viewPos.z);

    vec3 synthColor;
    if (sky) {
        vec3 worldViewDir = normalize(gtViewToWorld(gtViewDir(texCoord)));

        // 太阳在世界 −X（西边），仰角约 8.5°
        vec3 sunDir = normalize(vec3(-1.0, 0.15, 0.0));
        float sunShape = 0.0;
        vec2 sunUV = vec2(0.0);
        // 背对太阳的那半边天不用算
        if (dot(worldViewDir, sunDir) > 0.0) {
            vec3 sunRight = normalize(cross(sunDir, vec3(0.0, 1.0, 0.0)));
            vec3 sunUp = cross(sunRight, sunDir);
            sunUV = vec2(dot(worldViewDir, sunRight), dot(worldViewDir, sunUp));
            sunShape = synthwaveSun(sunUV, t);
        }

        vec3 sunColorTop = vec3(1.0, 0.8, 0.0);
        vec3 sunColorBottom = vec3(1.0, 0.0, 0.5);
        vec3 sunColor = mix(sunColorBottom, sunColorTop, smoothstep(-0.3, 0.3, sunUV.y));

        vec3 skyTop = vec3(0.05, 0.0, 0.1);
        vec3 skyBottom = vec3(0.4, 0.0, 0.4);
        synthColor = mix(skyBottom, skyTop, texCoord.y) + sunColor * sunShape;
    } else {
        vec3 normal = reconstructNormal(viewPos, texCoord);
        float NdotV = dot(normal, normalize(-viewPos));
        vec3 worldNormal = normalize(gtViewToWorld(normal));

        vec3 baseColor = vec3(0.08, 0.05, 0.15);

        // 轮廓光。两个单位向量的点积会因为舍入略大于 1，底数就成了一个极小的负数，
        // 所以再夹一次 0，理由同 reconstructNormal 里的 pow
        float rimIntensity = pow(max(1.0 - max(NdotV, 0.0), 0.0), 3.0);
        vec3 rimColor = vec3(0.0, 1.0, 1.0) * rimIntensity;

        // 只有正对某个轴的平面才铺网格，斜面上的网格会拉成一片条纹
        vec3 absN = abs(worldNormal);
        float axisAlignment = max(max(absN.x, absN.y), absN.z);
        float flatSurfaceMask = smoothstep(0.9, 0.98, axisAlignment);

        float gridVal = 0.0;
        if (flatSurfaceMask > 0.01) {
            gridVal = grid(gridCoord, gridDeriv, worldNormal, 3.0);
            // 深度在相邻像素间跳得厉害的地方是几何断开处，那里的网格导数是乱的，压掉
            float edgeDisconnectMask = 1.0 - smoothstep(0.2, 0.8, depthDelta);
            gridVal *= edgeDisconnectMask;
            gridVal *= flatSurfaceMask;
        }
        // 网格呼吸
        gridVal *= sin(t * PulseSpeed) * 0.4 + 0.6;
        vec3 gridColor = vec3(1.0, 0.0, 0.8) * gridVal * 2.0;

        float fogFactor = clamp(exp(-length(viewPos) * 0.015), 0.0, 1.0);
        vec3 fogColor = vec3(0.1, 0.0, 0.2);
        synthColor = mix(fogColor, baseColor + rimColor + gridColor, fogFactor);
    }

    // 展开：半径是时间的五次方，前一秒几乎不动，之后一下子扫出去。
    // 时间夹在 10 以内（半径 10 万米），再往后画面已经没有任何变化，
    // 而半径一旦大到 float32 分不出 R 和 R + 10，smoothstep 的两个边界就重合了——那又是未定义行为
    float timeCycle = revealClock();
    float mainRadius = pow(clamp(timeCycle * RevealSpeed, 0.0, 10.0), 5.0);
    if (timeCycle < 0.1) {
        mainRadius = 0.0;
    }

    float scanMetric;
    float scanThreshold;
    float scanSoftness;
    float scanWidthInner;
    float scanWidthOuter;
    if (sky) {
        // 天空没有距离可比，改用视线在视图空间的仰角：展开推进时从下往上吃掉整片天
        float skyProgress = smoothstep(0.0, 1500.0, mainRadius);
        scanMetric = gtViewDir(texCoord).y;
        scanThreshold = mix(-0.8, 1.1, skyProgress);
        scanSoftness = 0.15;
        scanWidthInner = 0.02;
        scanWidthOuter = scanSoftness + 0.05;
    } else {
        scanMetric = length(viewPos);
        scanThreshold = mainRadius;
        scanSoftness = 10.0;
        scanWidthInner = 1.0;
        scanWidthOuter = scanSoftness + 2.0;
    }

    float reveal = smoothstep(scanThreshold, scanThreshold + scanSoftness, scanMetric);
    float alphaMask = 1.0 - reveal;
    float scanLine = reveal
                   * (1.0 - smoothstep(scanThreshold + scanWidthInner, scanThreshold + scanWidthOuter, scanMetric));
    synthColor += vec3(0.5, 1.0, 1.2) * scanLine * 3.0;

    // 合成照着 srcalpha / 1-srcalpha 混合来做：8 位颜色缓冲会先把输出夹进 [0,1] 再混合，
    // 所以这里也先 clamp 再 mix。顺序反过来的话，过亮的扫描线会在半透明处把原画面冲白
    fragColor = vec4(mix(src, clamp(synthColor, 0.0, 1.0), alphaMask), 1.0);
}
