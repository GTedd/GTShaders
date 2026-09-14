// 暖色火光 / Warm Torchlight
//
// 光照贴图是一张 16×16 的查找表：横轴是方块光（火把、萤石），纵轴是天空光。
// 世界里每个像素的明暗都来查这张表，所以改它等于<b>一次性改掉全世界的光照氛围</b>——
// 而且完全不碰几何，性能零成本。这是投入产出比最高的一个核心着色器。
//
// 原版的火光已经偏暖，这里把它推得更远：低光照时更橙，高光照时收回来，
// 否则整个世界会泛橙，反而失去对比。
//
// [en_us]
// Makes torchlight warmer by editing the lightmap. The lightmap is a 16×16 lookup table: the horizontal axis
// is block light (torches, glowstone), the vertical axis is skylight.
// Every pixel in the world looks up its brightness in this table, so changing it <b>changes the lighting mood
// of the whole world in one go</b>, without touching geometry and at zero performance cost. No other core
// shader gives you more for less.
//
// Vanilla torchlight is already warm; this pushes it further. It is more orange in low light and pulled back
// in bright light. Otherwise the whole world gets an orange cast and loses contrast.
//
// @param name=Warmth type=color3 default=#FFB86B zh_cn=火光色 en_us=Torch Tint
// @param name=Strength type=float min=0 max=1 default=0.55 zh_cn=强度 en_us=Strength
// @param name=Contrast type=float min=0.5 max=2 default=1.15 zh_cn=对比度 en_us=Contrast

vec4 gtFragment(vec4 color) {
    // texCoord.x 是方块光强度，正好可以当作「离火源有多近」
    float blockLight = texCoord.x;

    // 只染暖色到方块光那一侧：天空光该保持中性，否则白天也会发橙
    vec3 tinted = color.rgb * mix(vec3(1.0), Warmth, blockLight * Strength);

    // 绕 0.5 中灰拉对比，避免整体变亮或变暗
    tinted = clamp((tinted - 0.5) * Contrast + 0.5, 0.0, 1.0);
    return vec4(tinted, color.a);
}
