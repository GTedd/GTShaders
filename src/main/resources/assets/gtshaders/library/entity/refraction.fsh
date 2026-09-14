// 折射隐身 / Refractive Cloak
// 把发光实体变成一块会扭曲背景的"活玻璃"：实体本体不见了，
// 只剩下它形状范围内被折弯的画面，外加一圈随时间流动的边缘高光。
//
// 这个效果只有实体轮廓层做得出来，因为它要同时拿到两样东西：
//   gtMask()    —— 哪块像素属于这个实体（决定扭曲的范围）
//   gtSceneAt() —— 那块像素背后的画面（扭曲的素材）
// /posteffect 挂的链只拿得到后者（allowedTargets 里只有 minecraft:main），
// 所以做不出"只扭曲某一只怪"这件事。
//
// [en_us]
// Turns a glowing entity into refractive living glass.
// It warps the background: the entity itself disappears, leaving only the picture bent within its shape, plus a
// rim highlight that flows over time.
//
// Only the entity outline layer can do this, because it needs two things at once:
//   gtMask()    - which pixels belong to this entity (sets the extent of the distortion)
//   gtSceneAt() - the picture behind those pixels (the material being distorted)
// A chain attached via /posteffect only gets the latter (allowedTargets contains only minecraft:main),
// so it can't do "distort just this one mob".

// @param name=Strength type=float min=0 max=0.08 default=0.018 zh_cn=折射强度 en_us=Refraction
// @param name=Scale type=float min=2 max=60 default=14 zh_cn=波纹密度 en_us=Ripple Scale
// @param name=Speed type=float min=0 max=6 default=1.4 zh_cn=波纹速度 en_us=Ripple Speed
// @param name=Chroma type=float min=0 max=1 default=0.45 zh_cn=色散 en_us=Chromatic
// @param name=RimColor type=color3 default=#BFEFFF zh_cn=边缘色 en_us=Rim Color
// @param name=RimGlow type=float min=0 max=4 default=1.1 zh_cn=边缘亮度 en_us=Rim Glow

void main() {
    float inside = gtMask();
    float edge = gtMaskEdge();
    if (inside <= 0.001 && edge <= 0.001) {
        fragColor = vec4(0.0);
        return;
    }

    // 折射偏移：两个不同频率的正弦叠出来的伪波纹，比单一频率有机得多
    vec2 off = vec2(
        sin(texCoord.y * Scale + GTTime * Speed)
            + 0.5 * sin(texCoord.y * Scale * 2.3 - GTTime * Speed * 1.7),
        cos(texCoord.x * Scale - GTTime * Speed * 0.8)
            + 0.5 * cos(texCoord.x * Scale * 1.9 + GTTime * Speed)
    ) * Strength;

    // 边缘处折射最强：真实玻璃的边缘掠射角大，弯折也最厉害
    off *= 0.5 + edge * 1.5;

    // 三通道错开采样造色散
    float c = Chroma * 0.4;
    vec3 col;
    col.r = gtSceneAt(texCoord + off * (1.0 + c)).r;
    col.g = gtSceneAt(texCoord + off).g;
    col.b = gtSceneAt(texCoord + off * (1.0 - c)).b;

    col += RimColor * edge * RimGlow;

    // alpha=1 表示这块像素完全由我们说了算——实体本体因此被彻底替换成折射后的背景，
    // 也就"隐身"了。这是轮廓层做减法的标准写法：不是降低自己的 alpha，
    // 而是自己把背景算好再以 alpha=1 盖上去
    fragColor = vec4(col, clamp(max(inside, edge), 0.0, 1.0));
}
