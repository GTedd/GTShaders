// 水下 / Underwater
// 三件事叠在一起：焦散光斑、随深度加重的蓝绿吸收、以及缓慢的折射晃动。
// 水对红光的吸收远快于蓝绿，所以「变蓝」应该做成按通道衰减，而不是往上叠蓝色。
//
// [en_us]
// An underwater look with caustics, absorption and wobble.
// Three things layered together: caustic light patches, blue-green absorption that grows with depth, and a slow
// refractive wobble. Water absorbs red light much faster than blue and green, so "turning blue" should be done as
// per-channel attenuation, not by adding blue on top.
//
// @param name=Depth type=float min=0 max=1 default=0.55 zh_cn=深度 en_us=Depth
// @param name=WaterColor type=color3 default=#2E7FA8 zh_cn=水色 en_us=Water Color
// @param name=Caustics type=float min=0 max=1 default=0.4 zh_cn=焦散强度 en_us=Caustics
// @param name=CausticScale type=float min=2 max=40 default=12 zh_cn=焦散尺度 en_us=Caustic Scale
// @param name=Wobble type=float min=0 max=0.03 default=0.008 zh_cn=晃动 en_us=Wobble
// @param name=Speed type=float min=0 max=3 default=0.7 zh_cn=流速 en_us=Flow Speed

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

void main() {
    float t = GTTime * Speed;

    vec2 uv = texCoord;
    uv.x += sin(texCoord.y * 14.0 + t * 1.7) * Wobble;
    uv.y += cos(texCoord.x * 11.0 - t * 1.3) * Wobble;
    vec3 col = texture(InSampler, uv).rgb;

    // 吸收：红光衰减最快，绿次之，蓝几乎不衰减。深度越大差距越大
    vec3 absorb = exp(-vec3(1.9, 0.7, 0.35) * Depth * 1.6);
    col *= absorb;
    col = mix(col, col * WaterColor * 2.0, Depth * 0.5);

    // 焦散：两层反向流动的脊线噪声相乘，交叉处形成网状亮斑——真实焦散正是这个成因
    float a = 1.0 - abs(noise(texCoord * CausticScale + vec2(t, t * 0.6)) - 0.5) * 2.0;
    float b = 1.0 - abs(noise(texCoord * CausticScale * 1.3 - vec2(t * 0.8, t)) - 0.5) * 2.0;
    float caustic = pow(clamp(a * b, 0.0, 1.0), 6.0);
    col += vec3(0.65, 0.9, 1.0) * caustic * Caustics * 1.6;

    fragColor = vec4(col, 1.0);
}
