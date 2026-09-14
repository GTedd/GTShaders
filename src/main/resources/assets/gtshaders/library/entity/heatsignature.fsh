// 热成像标记 / Heat Signature
// 把发光实体渲染成热成像里的高温体：中心白热、外圈橙红、边缘一圈冷色的溢出光。
// 隔着墙也看得见——轮廓缓冲本来就不做深度测试，这正是"透视标记"该有的样子。
//
// 用途是给玩家标出目标：Boss、队友、任务 NPC。比原版那圈纯色描边信息量大得多，
// 因为亮度可以再携带一个维度（这里用的是"离剪影中心有多远"）。
//
// [en_us]
// Renders glowing entities as hot bodies in thermal vision.
// White-hot at the center, orange-red further out, and a ring of cool-colored spill light at the edge.
// It is visible through walls: the outline buffer never does depth testing anyway, which is exactly how a
// "see-through marker" should behave.
//
// It is meant for marking targets for players: bosses, teammates, quest NPCs. It carries far more information
// than the vanilla solid-color outline, because brightness can encode one more dimension (here, "how far from
// the center of the silhouette").

// @param name=Palette type=int min=0 max=2 default=0 zh_cn=配色 en_us=Palette desc_zh_cn=0=热成像（黑体辐射）；1=夜视绿；2=用实体自己的队伍颜色 desc_en_us=0 = thermal, 1 = night-vision green, 2 = the entity team colour
// @param name=Intensity type=float min=0 max=4 default=1.6 zh_cn=强度 en_us=Intensity
// @param name=EdgeBoost type=float min=0 max=4 default=1.4 zh_cn=边缘增益 en_us=Edge Boost
// @param name=Pulse type=float min=0 max=6 default=1.2 zh_cn=呼吸速度 en_us=Pulse Speed
// @param name=Opacity type=float min=0 max=1 default=0.85 zh_cn=不透明度 en_us=Opacity

// 黑体辐射近似：0 冷 → 1 白热。分段叠加而不是查表，省掉一张纹理
vec3 thermal(float t) {
    t = clamp(t, 0.0, 1.0);
    vec3 c = vec3(0.0);
    c += vec3(0.0, 0.0, 0.6) * smoothstep(0.0, 0.25, t) * (1.0 - smoothstep(0.25, 0.5, t));
    c += vec3(0.9, 0.15, 0.0) * smoothstep(0.2, 0.55, t);
    c += vec3(1.0, 0.75, 0.0) * smoothstep(0.5, 0.8, t);
    c += vec3(1.0, 1.0, 1.0) * smoothstep(0.8, 1.0, t);
    return c;
}

void main() {
    float inside = gtMask();
    float edge = gtMaskEdge();
    if (inside <= 0.001 && edge <= 0.001) {
        // 剪影之外必须显式归零：轮廓层的输出会被 alpha 混合到整个屏幕上
        fragColor = vec4(0.0);
        return;
    }

    // gtMaskInset 越靠近剪影中心越大，拿它当"温度"就得到了由内向外的梯度
    float core = gtMaskInset(4.0);
    float heat = clamp(core * 0.9 + edge * EdgeBoost, 0.0, 1.0);
    // 呼吸：让标记在静止画面里也是活的，扫视时更容易被注意到
    heat *= 0.85 + 0.15 * sin(GTTime * Pulse * 3.14159);

    vec3 col;
    if (Palette == 1) {
        col = vec3(0.15, 1.0, 0.35) * heat;
    } else if (Palette == 2) {
        vec3 team = gtMaskColor();
        // 没设队伍时轮廓色接近黑，那样整个标记会消失，兜一个白底
        if (dot(team, vec3(1.0)) < 0.05) {
            team = vec3(1.0);
        }
        col = team * heat;
    } else {
        col = thermal(heat);
    }

    float alpha = clamp(max(inside * Opacity, edge), 0.0, 1.0);
    fragColor = vec4(col * Intensity, alpha);
}
