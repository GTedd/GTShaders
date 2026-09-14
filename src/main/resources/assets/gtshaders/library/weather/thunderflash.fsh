// 雷暴闪光 / Thunder Flash
// 闪电的照明特征：极短的爆发、常常连闪两三下、且光是有方向的（一侧更亮）。
// 匀亮的整屏白闪最假——真实闪电总在某个方位，画面另一侧应该还是暗的。
//
// [en_us]
// Storm lightning flashes that light one side of the view.
// Lightning has a distinct lighting signature: a very short burst, often two or three flashes in a row, and
// directional light (one side is brighter). An evenly lit full-screen white flash looks the most fake. Real
// lightning is always off in some direction, so the other side of the picture should stay dark.
//
// @param name=Rate type=float min=0.1 max=3 default=0.5 zh_cn=闪电频率 en_us=Strike Rate
// @param name=Brightness type=float min=0 max=2 default=0.9 zh_cn=亮度 en_us=Brightness
// @param name=FlashColor type=color3 default=#DCE6FF zh_cn=闪光色 en_us=Flash Color
// @param name=Direction type=vec2 min=-1 max=1 default=-0.6,0.5 zh_cn=光来向 en_us=Light Direction
// @param name=Gloom type=float min=0 max=1 default=0.4 zh_cn=平时的阴沉 en_us=Base Gloom

float hash(float x) {
    return fract(sin(x * 127.1) * 43758.5453);
}

void main() {
    vec3 col = texture(InSampler, texCoord).rgb;

    // 平时压暗压灰，闪光才有对比
    float luma = dot(col, vec3(0.2126, 0.7152, 0.0722));
    col = mix(col, vec3(luma) * vec3(0.85, 0.88, 1.0), Gloom);
    col *= 1.0 - Gloom * 0.35;

    float slot = floor(GTTime * Rate);
    float phase = fract(GTTime * Rate);
    float seed = hash(slot);

    // 不是每个时段都打雷
    float alive = step(0.55, seed);

    // 一次事件里连闪两下：主闪 + 稍弱的回闪，间隔很短
    float a = exp(-pow((phase - 0.02) * 60.0, 2.0));
    float b = exp(-pow((phase - 0.09) * 45.0, 2.0)) * 0.55;
    float flash = (a + b) * alive * Brightness * (0.6 + 0.4 * hash(slot + 3.0));

    // 方向性：沿 Direction 一侧更亮
    vec2 dir = normalize(Direction + 1e-5);
    float side = 0.5 + 0.5 * dot(normalize(texCoord - 0.5 + 1e-5), dir);
    float grad = mix(0.35, 1.0, side);

    col += FlashColor * flash * grad;
    col = mix(col, FlashColor, clamp(flash * 0.35 * grad, 0.0, 1.0));
    fragColor = vec4(col, 1.0);
}
