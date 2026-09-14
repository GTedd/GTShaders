// 手持物品呼吸 / Item Breathing
// 顶点钩子：让手里的东西轻微缩放。幅度必须很小（1% 量级），
// 大了会让物品穿过手模型。
//
// 注意 item 这一个文件同时管手持、掉落物和界面图标——
// 想只影响手里那把，要靠投影矩阵判断（GUI 与手持的投影矩阵不同）。
//
// [en_us]
// A vertex hook that makes held items gently breathe. It scales them slightly, and the amplitude must stay
// tiny (around 1%), or the item clips through the hand model.
//
// Note that the single item file covers held items, dropped items and GUI icons alike.
// To affect only the one in your hand, check the projection matrix (GUI and held items use different ones).
//
// @param name=Amount type=float min=0 max=0.1 default=0.02 zh_cn=幅度 en_us=Amount
// @param name=Speed type=float min=0 max=6 default=1.5 zh_cn=速度 en_us=Speed

vec3 gtVertex(vec3 position) {
    float t = GameTime * 24000.0 * Speed * 0.02;
    return position * (1.0 + sin(t) * Amount);
}
