// 裂纹发光 / Glowing Cracks
// 挖方块时的裂纹改成发光的。裂纹贴图是灰度的，直接拿它当遮罩即可。
//
// [en_us]
// Makes block-breaking cracks glow. The crack texture is grayscale, so it can be used directly as a mask.
//
// @param name=GlowColor type=color3 default=#FF6A2A zh_cn=裂纹色 en_us=Crack Color
// @param name=Pulse type=float min=0 max=6 default=2 zh_cn=脉动速度 en_us=Pulse
// @param name=Strength type=float min=0 max=3 default=1.2 zh_cn=强度 en_us=Strength

vec4 gtFragment(vec4 color) {
    // 裂纹越深的地方原色越暗，取反就是遮罩
    float crack = 1.0 - dot(color.rgb, vec3(0.333));
    float pulse = 0.6 + 0.4 * sin(GameTime * 24000.0 * Pulse * 0.05);
    return vec4(color.rgb + GlowColor * crack * pulse * Strength, color.a);
}
