// 黑客帝国矩阵 / Matrix
// 世界被抽成一层黑底绿线的数据空间。
// 天空是自上而下的黑到墨绿，正中挂一枚带扫描条纹的数据核心；地面按世界坐标铺绿色网格，
// 只沿 Z 轴流动；边缘补一圈轮廓光，再往外推一道展开边界。
//
// 网格钉在世界坐标上，靠 gtWorldPos 一族内置量。导出成纯资源包后没有相机数据，
// 它会退回跟着视角转。
//
// [en_us]
// The world turned into green-on-black Matrix data space.
// The world is stripped down to a data space of green lines on black. The sky fades from black at the top to deep green, with a scan-striped data core hanging in the
// middle. The ground gets a green grid laid out in world coordinates that flows only along the Z axis. Edges get
// a rim of outline light, and a reveal boundary then sweeps outward.
//
// The grid is pinned to world coordinates using the gtWorldPos family of built-ins. When exported as a plain
// resource pack there is no camera data, so it falls back to turning with the view.
//
// @group 网格 / Grid
// @param name=GridSpeed type=float min=0 max=10 default=2 zh_cn=网格流速 en_us=Grid Speed
// @param name=MatrixColor type=color3 default=#00FF33 zh_cn=矩阵绿 en_us=Matrix Green
// @param name=FogDensity type=float min=0 max=0.05 default=0.01 zh_cn=远处淡出 en_us=Distance Fade

// @group 数据核心 / Core
// @param name=CoreSpeed type=float min=0 max=5 default=1 zh_cn=核心速度 en_us=Core Speed

// @group 展开 / Reveal
// @param name=Reveal type=bool default=0 zh_cn=播放展开 en_us=Play Reveal desc_zh_cn=关掉就是展开完成后的稳定态 desc_en_us=When off, shows the steady state after the reveal has finished
// @param name=RevealSpeed type=float min=0.1 max=5 default=1 zh_cn=展开速度 en_us=Reveal Speed
// @param name=RevealCycle type=float min=1 max=20 default=6 zh_cn=循环周期 en_us=Reveal Cycle

// @group 作用范围 / Scope
// @param name=SkyOn type=bool default=1 zh_cn=天空着色 en_us=Shade Sky
// @param name=GroundOn type=bool default=1 zh_cn=地面着色 en_us=Shade Ground

// 见 synthwave：世界坐标在远离原点处会被 float32 吃掉精度，
// 相机的整数块坐标先按 1024 折回来再用（1024 是网格周期 2 的整数倍，接缝对得上）
vec3 gridAnchor() {
    ivec3 wrapped = CameraBlockPos - (CameraBlockPos / 1024) * 1024;
    return vec3(wrapped) + CameraOffset;
}

float gridLines(vec3 p, vec3 n, float lineWidth, float scale) {
    vec3 coord = p * scale;
    vec3 deriv = fwidth(coord);
    vec3 pattern = abs(fract(coord - 0.5) - 0.5);
    vec3 dist = pattern / (max(deriv, 0.001) * lineWidth);
    vec3 lines = 1.0 - min(dist, 1.0);
    vec3 w = abs(n);
    return clamp(lines.x * (1.0 - w.x) + lines.y * (1.0 - w.y) + lines.z * (1.0 - w.z), 0.0, 1.0);
}

// 数据核心：一团脉动的光斑，横着切一层高频扫描线，让它看起来像在刷新而不是在发光
float dataCore(vec2 uv, float time) {
    float core = 1.0 - smoothstep(0.0, 0.3, length(uv));
    float pulse = sin(time * 5.0) * 0.1 + 0.9;
    float lines = sin(uv.y * 100.0 - time * 20.0) * 0.5 + 0.5;
    return core * pulse * (0.8 + 0.2 * lines);
}

void main() {
    vec3 src = texture(InSampler, texCoord).rgb;
    bool sky = gtIsSky(texCoord);
    if ((sky && SkyOn < 0.5) || (!sky && GroundOn < 0.5)) {
        fragColor = vec4(src, 1.0);
        return;
    }

    vec3 darkGreen = MatrixColor * 0.1;
    // 地面分支和后面的展开都要用距离，算一次存着——每多问一次 gtDistance
    // 就是一次深度采样，而这是全屏跑的
    float dist = 0.0;
    vec3 matrixColor;
    if (sky) {
        // 核心的方向是固定的，它是这个世界的「太阳」，不该随玩家转
        vec3 coreDir = normalize(vec3(-1.0, 0.45, 0.0));
        vec3 viewDir = gtWorldDir(texCoord);
        float shape = 0.0;
        if (dot(viewDir, coreDir) > 0.0) {
            vec3 right = normalize(cross(coreDir, vec3(0.0, 1.0, 0.0)));
            vec3 up = cross(right, coreDir);
            vec2 coreUV = vec2(dot(viewDir, right), dot(viewDir, up)) * 1.5;
            shape = dataCore(coreUV, GTTime * CoreSpeed);
        }
        matrixColor = mix(vec3(0.0), darkGreen, 1.0 - texCoord.y) + MatrixColor * shape;
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
        // 只认正交面。斜面上的网格会拉成一片没法看的条纹
        if (max(max(abs(worldNormal.x), abs(worldNormal.y)), abs(worldNormal.z)) > 0.9) {
            pos.z += GTTime * GridSpeed;
            grid = gridLines(pos, worldNormal, 2.0, 0.5);
        }

        float rim = pow(1.0 - max(ndotv, 0.0), 3.0);
        float fade = clamp(exp(-dist * FogDensity), 0.0, 1.0);
        vec3 lit = darkGreen * 0.5 + MatrixColor * rim * 0.5 + MatrixColor * grid;
        matrixColor = mix(vec3(0.0), lit, fade);
    }

    // 展开：半径是时间的五次方，前一秒几乎没动、之后一下子扫出去。
    // 关掉时给一个大到覆盖任何视距的常量
    float radius = 1.0e6;
    if (Reveal > 0.5) {
        radius = pow(mod(GTTime * RevealSpeed, RevealCycle), 5.0);
    }
    // 天空没有距离可比，改用视线仰角当尺度
    float metric = sky ? gtViewDir(texCoord).y : dist;
    float threshold = sky ? mix(-0.8, 1.1, smoothstep(0.0, 1500.0, radius)) : radius;
    float softness = sky ? 0.1 : 8.0;

    float inside = 1.0 - smoothstep(threshold, threshold + softness, metric);
    float edge = smoothstep(threshold, threshold + softness, metric)
               * (1.0 - smoothstep(threshold, threshold + (sky ? 0.1 : 10.0), metric));

    fragColor = vec4(mix(src, matrixColor, inside) + MatrixColor * edge * 2.0, 1.0);
}
