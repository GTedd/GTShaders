// 精神污染 / Insanity
// 画面在「正常」和「不正常」之间来回滑：色相乱转、局部反相、边缘长出蠕动的噪点，
// 偶尔整帧突然错位一下。强度按一条慢速噪声起伏，所以永远不知道下一秒会不会更糟。
//
// 关键在于<b>不能一直很糟</b>：有安稳的间隙作对照，糟的那几秒才成立。
//
// [en_us]
// The picture slips between normal and deranged.
// It slides back and forth between "normal" and "not normal": hues spin wildly, patches invert, writhing
// noise grows at the edges, and now and then the whole frame suddenly jolts out of place. The strength
// follows a slow noise curve, so you never know whether the next second will be worse.
//
// The key is that <b>it can't stay bad all the time</b>: calm gaps provide contrast, and only then do the bad
// seconds work.
//
// @param name=Level type=float min=0 max=1 default=0.5 zh_cn=污染程度 en_us=Corruption
// @param name=Volatility type=float min=0 max=1 default=0.6 zh_cn=起伏剧烈度 en_us=Volatility
// @param name=Rate type=float min=0.05 max=2 default=0.3 zh_cn=起伏速度 en_us=Drift Rate

// @group 色彩 / Color
// @param name=HueChaos type=float min=0 max=3 default=1.2 zh_cn=色相混乱 en_us=Hue Chaos
// @param name=InvertPatches type=float min=0 max=1 default=0.4 zh_cn=局部反相 en_us=Inverted Patches
// @param name=PatchScale type=float min=1 max=20 default=4 zh_cn=斑块尺度 en_us=Patch Scale

// @group 噪点 / Noise
// @param name=Crawl type=float min=0 max=1 default=0.45 zh_cn=边缘蠕动噪点 en_us=Crawling Grain
// @param name=Jolt type=float min=0 max=0.2 default=0.05 zh_cn=突然错位 en_us=Jolt

float hash(vec2 p) {
    return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453);
}

float noise(vec2 p) {
    vec2 i = floor(p);
    vec2 f = fract(p);
    f = f * f * (3.0 - 2.0 * f);
    return mix(mix(hash(i), hash(i + vec2(1.0, 0.0)), f.x),
               mix(hash(i + vec2(0.0, 1.0)), hash(i + vec2(1.0, 1.0)), f.x), f.y);
}

vec3 hueShift(vec3 c, float a) {
    const vec3 k = vec3(0.57735);
    float cs = cos(a);
    return c * cs + cross(k, c) * sin(a) + k * dot(k, c) * (1.0 - cs);
}

void main() {
    // 慢速噪声当作"病情"曲线：有起有落，不是一条平线
    float wave = noise(vec2(GTTime * Rate, 0.0)) * 0.6 + noise(vec2(GTTime * Rate * 2.7, 5.1)) * 0.4;
    float amt = clamp(Level * mix(1.0, wave * 2.0, Volatility), 0.0, 1.5);

    // 突然错位：短促、稀疏，只在病情重的时候发生
    float joltGate = step(0.93, noise(vec2(GTTime * 6.0, 3.3))) * amt;
    vec2 uv = texCoord + vec2((hash(vec2(floor(GTTime * 14.0), 0.0)) - 0.5) * Jolt * joltGate, 0.0);
    uv = clamp(uv, vec2(0.0), vec2(1.0));

    vec3 col = texture(InSampler, uv).rgb;

    col = hueShift(col, sin(GTTime * 0.9 + texCoord.y * 3.0) * HueChaos * amt);

    // 局部反相：低频噪声划出几块区域，块内颜色翻过来
    float patch = noise(texCoord * PatchScale + GTTime * 0.15);
    float inv = smoothstep(0.62, 0.72, patch) * InvertPatches * amt;
    col = mix(col, 1.0 - col, clamp(inv, 0.0, 1.0));

    // 边缘蠕动噪点：只长在四周，中心保持干净，视线才有落点
    vec2 asp = vec2(OutSize.x / max(OutSize.y, 1.0), 1.0);
    float r = length((texCoord - 0.5) * asp) / max(length(asp) * 0.5, 1e-4);
    float grain = noise(texCoord * 220.0 + GTTime * 9.0) - 0.5;
    col += grain * Crawl * amt * smoothstep(0.35, 1.1, r) * 1.2;

    fragColor = vec4(col, 1.0);
}
