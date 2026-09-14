// 阵营分流 / Team Split
// 一份效果，按实体自己的队伍颜色分成几种表现。这是逐实体数据通道的教学样本。
//
// 轮廓缓冲里每个实体的剪影带着自己的 RGB，来源是原版 core/rendertype_outline：
//     fragColor = vec4(ColorModulator.rgb * vertexColor.rgb, ColorModulator.a);
// 那个颜色就是实体的队伍颜色 / 发光色。于是 RGB 成了一条<b>免费的逐实体寻址通道</b>：
// 服务端用记分板决定谁是什么颜色，着色器这边认颜色分流。
// 比把数据编码进像素（VanillaDI 那套）干净一个数量级，因为它是原版本来就在传的东西。
//
// 服务端配置示例：
//   /team add red_side
//   /team modify red_side color red
//   /team join red_side @e[type=zombie]
//   /effect give @e[type=zombie] minecraft:glowing 999999 0 true
//
// 注意：用编辑器的「让准星实体发光」调试开关时，拿到的是默认白色（本地强制发光没有队伍），
// 所以要预览分流效果必须在真服务器上配队伍。
//
// [en_us]
// One effect with a different look per entity team color.
// This is the teaching sample for the per-entity data channel.
//
// In the outline buffer, each entity's silhouette carries its own RGB, which comes from vanilla
// core/rendertype_outline:
//     fragColor = vec4(ColorModulator.rgb * vertexColor.rgb, ColorModulator.a);
// That color is the entity's team color / glow color. So RGB becomes a <b>free per-entity addressing channel</b>:
// the server decides who gets which color via the scoreboard, and the shader branches by color.
// That is an order of magnitude cleaner than encoding data into pixels (the VanillaDI approach), because it is
// something vanilla already sends anyway.
//
// Server setup example:
//   /team add red_side
//   /team modify red_side color red
//   /team join red_side @e[type=zombie]
//   /effect give @e[type=zombie] minecraft:glowing 999999 0 true
//
// Note: with the editor's "Glow entity under crosshair" debug toggle you get the default white (locally forced
// glowing has no team), so previewing the split requires setting up teams on a real server.

// @param name=Tolerance type=float min=0.05 max=0.6 default=0.3 zh_cn=颜色容差 en_us=Colour Tolerance desc_zh_cn=判断轮廓色属于哪一档时允许的偏差。太小会因为剪影边缘的抗锯齿而认不出来 desc_en_us=How far a colour may drift and still match. Too small and anti-aliased edges stop matching

// @group 红队 / Red
// @param name=RedGlow type=float min=0 max=6 default=3.0 zh_cn=红队边缘 en_us=Red Edge
// @group 绿队 / Green
// @param name=GreenGlow type=float min=0 max=6 default=2.0 zh_cn=绿队边缘 en_us=Green Edge
// @param name=GreenFill type=float min=0 max=1 default=0.3 zh_cn=绿队填充 en_us=Green Fill
// @group 蓝队 / Blue
// @param name=BlueGlow type=float min=0 max=6 default=2.4 zh_cn=蓝队边缘 en_us=Blue Edge
// @param name=BlueWave type=float min=0 max=8 default=3.0 zh_cn=蓝队波动 en_us=Blue Wave
// @group 其它 / Other
// @param name=OtherGlow type=float min=0 max=6 default=1.2 zh_cn=其它实体边缘 en_us=Fallback Edge

void main() {
    float inside = gtMask();
    float edge = gtMaskEdge();
    if (inside <= 0.001 && edge <= 0.001) {
        fragColor = vec4(0.0);
        return;
    }

    vec3 col;
    float alpha;

    if (gtMaskIs(vec3(1.0, 0.0, 0.0), Tolerance)) {
        // 红队：只有一圈硬边，最省事也最醒目，适合敌对目标
        col = vec3(1.0, 0.2, 0.15) * edge * RedGlow;
        alpha = edge;
    } else if (gtMaskIs(vec3(0.0, 1.0, 0.0), Tolerance)) {
        // 绿队：边缘 + 一层柔和的内部填充，适合队友，长时间看也不刺眼
        col = vec3(0.3, 1.0, 0.45) * (edge * GreenGlow + inside * GreenFill);
        alpha = max(edge, inside * GreenFill);
    } else if (gtMaskIs(vec3(0.0, 0.0, 1.0), Tolerance)) {
        // 蓝队：边缘随时间脉动，适合"正在充能/正在读条"这类要传达进度感的对象
        float wave = 0.5 + 0.5 * sin(GTTime * BlueWave + texCoord.y * 20.0);
        col = vec3(0.35, 0.6, 1.0) * edge * BlueGlow * (0.5 + wave);
        alpha = edge;
    } else {
        // 认不出队伍：退回原版那种朴素描边，用实体自己的颜色
        vec3 team = gtMaskColor();
        if (dot(team, vec3(1.0)) < 0.05) {
            team = vec3(1.0);
        }
        col = team * edge * OtherGlow;
        alpha = edge;
    }

    fragColor = vec4(col, clamp(alpha, 0.0, 1.0));
}
