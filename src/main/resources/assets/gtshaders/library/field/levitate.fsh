// 失重悬浮 / Levitation
// 失重的视觉线索有两个：画面轻微上飘的拖影，以及边缘向上翘的形变。
// 拖影方向固定朝上（而不是径向），大脑才会解读成「东西在往上走」。
//
// [en_us]
// Weightless floating with upward ghosting and curled edges.
// Weightlessness has two visual cues: a faint upward-drifting ghost trail on the picture, and edges that warp
// upward. The trail always points up (not radially), which is what makes the brain read it as "things are
// rising".
//
// @param name=Lift type=float min=0 max=0.1 default=0.03 zh_cn=上飘量 en_us=Lift
// @param name=Steps type=int min=2 max=16 default=9 zh_cn=拖影采样数 en_us=Trail Steps
// @param name=Sway type=float min=0 max=0.05 default=0.012 zh_cn=左右摇曳 en_us=Sway
// @param name=Halo type=color3 default=#CDE6FF zh_cn=辉光色 en_us=Halo Color
// @param name=HaloStrength type=float min=0 max=1 default=0.35 zh_cn=辉光强度 en_us=Halo Strength

void main() {
    // 缓慢的左右摇曳，让悬浮不至于像被钉住
    float sway = sin(GTTime * 0.9) * Sway;

    vec3 acc = vec3(0.0);
    float total = 0.0;
    for (int i = 0; i < 16; i++) {
        if (i >= Steps) {
            break;
        }
        float k = float(i) / float(max(Steps - 1, 1));
        // 往下取样 = 拖影留在下方 = 主体在往上飘
        vec2 uv = texCoord - vec2(sway * k, Lift * k);
        float w = 1.0 - k * 0.8;
        acc += texture(InSampler, uv).rgb * w;
        total += w;
    }
    vec3 col = acc / max(total, 1e-4);

    // 底部更亮的一层辉光：光源在下方托着的暗示
    float glow = smoothstep(0.55, 0.0, texCoord.y) * HaloStrength;
    col += Halo * glow * 0.5;

    fragColor = vec4(col, 1.0);
}
