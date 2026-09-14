// 实体果冻抖动 / Entity Jelly Wobble
//
// 顶点钩子的入门示例：按世界坐标和时间做一个正弦位移，整个生物就会像果冻一样晃。
//
// 两个容易做错的地方：
//   1. 相位必须<b>跟着位置走</b>，不能只跟时间走——否则整个模型会整体平移，
//      看起来是"生物在滑动"而不是"生物在抖"。
//   2. 幅度要很小（0.02 量级）。大了会让模型穿墙，而且非常晕。
//
// [en_us]
// A starter vertex hook that makes mobs wobble like jelly. It applies a sine offset based on world position
// and time, so the whole mob jiggles.
//
// Two easy mistakes:
//   1. The phase must <b>follow position</b>, not just time. Otherwise the whole model shifts as one piece,
//      and it looks like the mob is sliding rather than wobbling.
//   2. Keep the amplitude tiny (around 0.02). Larger values push the model through walls and get very dizzying.
//
// @param name=Amount type=float min=0 max=0.1 default=0.02 zh_cn=抖动幅度 en_us=Amount
// @param name=Speed type=float min=0 max=10 default=4 zh_cn=抖动速度 en_us=Speed
// @param name=Scale type=float min=0.5 max=20 default=6 zh_cn=波长密度 en_us=Wave Scale

vec3 gtVertex(vec3 position) {
    // GameTime 是 0..1 的归一化天时间，×24000 换回 tick 才有合适的量纲
    float t = GameTime * 24000.0 * Speed * 0.05;

    // 三个轴各用不同的相位，位移方向才不会全挤在一条线上
    float wx = sin(position.y * Scale + t);
    float wz = cos(position.y * Scale + t * 1.3);
    float wy = sin((position.x + position.z) * Scale * 0.5 + t * 0.7);

    return position + vec3(wx, wy * 0.4, wz) * Amount;
}
