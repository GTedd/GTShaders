// 暴雨 / Heavy Rain
// 打在镜头上的雨：斜着划过的雨丝 + 停留的水珠。
// 水珠必须做折射（采样点被推开）而不是只画个亮点，否则会像屏幕脏了。
//
// [en_us]
// Heavy rain hitting the lens, with streaks and droplets.
// Slanted rain streaks slide across the view while droplets stay put on the lens.
// Droplets must refract (the sample point is pushed aside) instead of just drawing a bright dot, otherwise it
// looks like the screen is dirty.
//
// @param name=Intensity type=float min=0 max=1 default=0.6 zh_cn=雨量 en_us=Intensity
// @param name=Streaks type=float min=10 max=200 default=70 zh_cn=雨丝密度 en_us=Streak Density
// @param name=Slant type=float min=-1 max=1 default=0.25 zh_cn=倾斜 en_us=Slant
// @param name=Speed type=float min=0 max=6 default=2.5 zh_cn=下落速度 en_us=Fall Speed
// @param name=Drops type=float min=4 max=40 default=14 zh_cn=水珠密度 en_us=Droplet Density
// @param name=DropRefract type=float min=0 max=0.06 default=0.02 zh_cn=水珠折射 en_us=Droplet Refraction
// @param name=Gloom type=float min=0 max=1 default=0.3 zh_cn=阴沉 en_us=Gloom

float hash(vec2 p) {
    return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453);
}

void main() {
    // ---- 停留的水珠：格子里放一个圆，圆内把采样点朝圆心外推 ----
    vec2 asp = vec2(OutSize.x / max(OutSize.y, 1.0), 1.0);
    vec2 dgrid = vec2(Drops) * vec2(asp.x, 1.0);
    vec2 dcell = floor(texCoord * dgrid);
    vec2 dlocal = fract(texCoord * dgrid) - 0.5;
    float dseed = hash(dcell);
    // 只有一部分格子有水珠，全都有会像浴室玻璃
    float has = step(0.62, dseed);
    vec2 dcenter = (vec2(hash(dcell + 1.0), hash(dcell + 2.0)) - 0.5) * 0.5;
    float dd = length(dlocal - dcenter);
    float dr = 0.16 + 0.16 * hash(dcell + 3.0);
    float drop = smoothstep(dr, dr * 0.6, dd) * has * Intensity;

    vec2 uv = texCoord + normalize(dlocal - dcenter + 1e-5) * drop * DropRefract;
    vec3 col = texture(InSampler, uv).rgb;

    // ---- 雨丝：把 uv 沿倾斜方向剪切，再按列取随机相位 ----
    vec2 rp = vec2(texCoord.x + texCoord.y * Slant, texCoord.y);
    float column = floor(rp.x * Streaks);
    float phase = hash(vec2(column, 0.0));
    float y = fract(rp.y * 2.0 + GTTime * Speed * (0.7 + phase * 0.6) + phase);
    // 每条雨丝是一段短亮线：用 smoothstep 掐头去尾
    float streak = smoothstep(0.0, 0.08, y) * smoothstep(0.35, 0.1, y);
    streak *= step(0.55, hash(vec2(column, floor(rp.y * 2.0 + GTTime * Speed))));
    col += vec3(0.75, 0.82, 0.95) * streak * Intensity * 0.35;

    // 水珠边缘的高光
    col += vec3(0.8, 0.88, 1.0) * smoothstep(dr * 0.95, dr, dd) * drop * 0.5;

    // 阴沉：整体压暗并偏冷
    float luma = dot(col, vec3(0.2126, 0.7152, 0.0722));
    col = mix(col, vec3(luma) * vec3(0.82, 0.88, 1.0), Gloom);
    col *= 1.0 - Gloom * 0.25;
    fragColor = vec4(col, 1.0);
}
