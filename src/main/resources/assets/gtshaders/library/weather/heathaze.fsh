// 热浪扭曲 / Heat Haze
// 热空气折射。两个容易做错的地方：
//   1. 扭曲必须自下而上增强再衰减——热气是从地面升起来的；
//   2. 位移要随时间往上滚动，不然就是静止的哈哈镜。
//
// [en_us]
// Shimmering distortion from hot air rising off the ground.
// Hot air refracts light, and two things are easy to get wrong:
//   1. Going from bottom to top, the distortion must first grow and then fade, because heat rises from the ground.
//   2. The offset must scroll upward over time, otherwise it is just a static funhouse mirror.
//
// @param name=Strength type=float min=0 max=0.03 default=0.008 zh_cn=扭曲强度 en_us=Strength
// @param name=Scale type=float min=2 max=40 default=14 zh_cn=气流尺度 en_us=Cell Scale
// @param name=Rise type=float min=0 max=4 default=1.2 zh_cn=上升速度 en_us=Rise Speed
// @param name=GroundBias type=float min=0 max=1 default=0.6 zh_cn=贴地程度 en_us=Ground Bias
// @param name=Shimmer type=float min=0 max=1 default=0.25 zh_cn=亮度闪烁 en_us=Shimmer

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
    float t = GTTime * Rise;

    // 贴地权重：下方最强，往上快速减弱
    float ground = mix(1.0, smoothstep(0.75, 0.0, texCoord.y), GroundBias);

    float nx = noise(texCoord * Scale + vec2(0.0, -t));
    float ny = noise(texCoord * Scale * 1.4 + vec2(19.0, -t * 1.3));
    vec2 offset = (vec2(nx, ny) - 0.5) * Strength * ground;
    // 热气主要造成竖直方向的抖动，横向弱一些
    offset.x *= 0.6;

    vec3 col = texture(InSampler, texCoord + offset).rgb;
    col *= 1.0 + (nx - 0.5) * Shimmer * ground;
    fragColor = vec4(col, 1.0);
}
