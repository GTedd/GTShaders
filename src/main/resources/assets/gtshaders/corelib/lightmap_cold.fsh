// 冷色月夜 / Cold Moonlight
//
// 和暖色火光相反：把<b>天空光</b>那一侧推向冷蓝，方块光保持暖。
// 于是室内外的色温差被拉开，夜里站在屋檐下会有很明显的"里外之分"。
//
// [en_us]
// Turns <b>skylight</b> cold blue while block light stays warm. It is the opposite of Warm Torchlight.
// That widens the color temperature gap between indoors and outdoors: at night, standing under the eaves
// feels clearly "inside" versus "outside".
//
// @param name=SkyTint type=color3 default=#7FA8FF zh_cn=天空光色 en_us=Sky Tint
// @param name=BlockTint type=color3 default=#FFD9A0 zh_cn=方块光色 en_us=Block Tint
// @param name=Strength type=float min=0 max=1 default=0.6 zh_cn=强度 en_us=Strength

vec4 gtFragment(vec4 color) {
    float blockLight = texCoord.x;
    float skyLight = texCoord.y;

    // 两侧各自染色再按各自的强度混合。谁更亮谁的色偏就更明显，
    // 这正是真实世界里"离哪个光源近就偏哪个色"的行为
    vec3 tint = mix(vec3(1.0), SkyTint, skyLight * Strength);
    tint = mix(tint, BlockTint, blockLight * Strength * 0.8);

    return vec4(color.rgb * tint, color.a);
}
