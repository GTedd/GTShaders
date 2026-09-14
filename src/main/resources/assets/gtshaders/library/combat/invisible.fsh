// 隐身折射 / Invisibility
// 隐形不是「看不见」，而是「背景被扭了一下」。所以这里不做透明度，
// 只做位移采样：画面整体保持原样，只有起伏处的像素被推开一点。
//
// [en_us]
// Invisibility as a refractive ripple over the scene.
// Invisibility isn't "you can't see it", it's "the background got warped a little". So there is no transparency
// here, only offset sampling: the picture stays as it is, and only the pixels on the ripples get pushed aside a bit.
//
// @param name=Refract type=float min=0 max=0.06 default=0.018 zh_cn=折射强度 en_us=Refraction
// @param name=Scale type=float min=1 max=30 default=7 zh_cn=起伏尺度 en_us=Ripple Scale
// @param name=Speed type=float min=0 max=4 default=0.9 zh_cn=流动速度 en_us=Flow Speed
// @param name=Fringe type=float min=0 max=1 default=0.4 zh_cn=边缘反光 en_us=Edge Sheen
// @param name=SheenColor type=color3 default=#CFE9FF zh_cn=反光色 en_us=Sheen Color

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
    // 取两个错开的噪声当作位移的两个分量，方向才不会全指向同一侧
    float nx = noise(texCoord * Scale + vec2(t, 0.0));
    float ny = noise(texCoord * Scale + vec2(0.0, t) + 41.0);
    vec2 offset = (vec2(nx, ny) - 0.5) * Refract;

    vec3 col = texture(InSampler, texCoord + offset).rgb;

    // 位移量大的地方给一点冷色高光，模拟折射面的掠射反射
    float sheen = clamp(length(offset) / max(Refract, 1e-4), 0.0, 1.0);
    col += SheenColor * pow(sheen, 2.0) * Fringe * 0.6;

    fragColor = vec4(col, 1.0);
}
