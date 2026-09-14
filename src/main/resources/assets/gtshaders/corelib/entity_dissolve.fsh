// 实体溶解 / Entity Dissolve
//
// 按噪声阈值把像素一点点丢掉，边缘留一圈发光的"燃烧边"。
// 这是所有溶解/传送/消散特效的通用做法：
// <b>先决定哪些像素消失，再给"正要消失"的那一圈上色</b>。两步分开做，边缘才干净。
//
// [en_us]
// Entities dissolve away, leaving a glowing "burning edge". Pixels are dropped bit by bit against a noise
// threshold. This is the standard approach for every dissolve / teleport / fade-away effect:
// <b>first decide which pixels disappear, then color the ring that is about to disappear</b>.
// Keeping the two steps separate is what makes the edge clean.
//
// @param name=Progress type=float min=0 max=1 default=0.35 zh_cn=溶解进度 en_us=Progress
// @param name=EdgeColor type=color3 default=#FF9A3C zh_cn=燃烧边颜色 en_us=Edge Color
// @param name=EdgeWidth type=float min=0.01 max=0.3 default=0.08 zh_cn=边缘宽度 en_us=Edge Width
// @param name=Scale type=float min=2 max=60 default=18 zh_cn=噪声密度 en_us=Noise Scale

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

vec4 gtFragment(vec4 color) {
    float n = noise(texCoord0 * Scale);

    // 噪声值低于进度的像素消失。用 alpha 归零而不是 discard：
    // discard 在 OIT 的 alpha 阶段行为不一样，会让排序出问题
    float alive = step(Progress, n);

    // 正在消失的那一圈：噪声值刚好在阈值附近
    float edge = smoothstep(Progress + EdgeWidth, Progress, n) * alive;

    vec3 rgb = color.rgb + EdgeColor * edge * 2.0;
    return vec4(rgb, color.a * alive);
}
