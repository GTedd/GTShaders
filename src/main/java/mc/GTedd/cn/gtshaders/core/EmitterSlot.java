package mc.GTedd.cn.gtshaders.core;

/**
 * 一个锚点的<b>载体扩展</b>：它朝着哪、是哪一类、作者给它挂了什么自定义数值。
 *
 * <h2>为什么锚点还不够</h2>
 *
 * <p>{@link AnchorSlot} 回答的是「那个东西在屏幕上的哪一点」。对黑洞、击杀标记这类
 * 圆对称的效果，一个点就够了。但一整类效果不是圆对称的：
 * 矩形面光要知道面朝哪，聚光要知道锥轴指向哪，闪电要知道两个节点之间怎么连。
 * 这些都需要<b>朝向</b>，而朝向是锚点里没有的东西。
 *
 * <h2>参考的那套做法，以及为什么只搬一半</h2>
 *
 * <p><a href="https://github.com/YangMao-Minister/some_of_fx">some_of_fx</a> 用
 * {@code item_display} 当载体：实体持一个零面积模型的物品，
 * {@code custom_model_data.strings[0]} 选子类型、{@code colors[0]} 打包
 * {@code intensity<<16 | palette<<8 | id} 当顶点 tint，核心着色器认出它再把世界坐标、
 * 切线、副切线、颜色、强度写进屏幕底行的像素。
 *
 * <p>那套编码里<b>只有位置和朝向是真正必须搬过来的</b>。强度、调色板、子类型之所以要
 * 塞进实体，是因为纯资源包没有别的地方能放参数——而 GTShaders 有完整的 {@code @param}
 * 系统，一个效果层的参数就在右边的面板上摆着，拖滑条即时生效。把它们再编码一遍
 * 等于凭空多一层，还失去了实时调节。
 *
 * <p>所以留下来的是：<b>朝向</b>（锚点缺的那样东西）、<b>类型</b>（同一个效果层里
 * 让不同载体画不同形状，这是 {@code @param} 表达不了的）、
 * <b>两个自定义值</b>（同上，让同一层里的每个载体各有各的强弱/相位）。
 *
 * <h2>字节布局</h2>
 *
 * <p>一个槽位占两个 vec4，与 {@code GlslCodegen} 生成的 std140 数组逐项对应：
 * <pre>
 *   GTEmitterA[i] = (dirU, dirV, facing, roll)
 *   GTEmitterB[i] = (type, custom1, custom2, spin)
 * </pre>
 *
 * <p>槽位号与 {@link AnchorSlot} <b>逐一对齐</b>——{@code gtEmitterDir(2)} 说的就是
 * {@code gtAnchorUV(2)} 那个锚点的朝向。两套数据同一套槽位分配，不存在第二种编号。
 *
 * @param dirU    前向量投影到屏幕后的方向，横向分量。已做等比校正，与
 *                {@code gtAnchorDelta} 同一个空间，可以直接点乘
 * @param dirV    前向量的屏幕方向，纵向分量。前向量几乎平行于视线时整个方向退化成
 *                {@code (0,0)}——那时它在屏幕上本来就没有方向可言，靠 {@code facing} 去分辨
 * @param facing  前向量与「从载体指向相机」那个方向的点积，-1..1。
 *                {@code 1} 是正对着你（面光迎面照过来），{@code 0} 是完全侧对，
 *                {@code -1} 是背对。聚光的光锥该画成圆还是画成扁椭圆，就看它
 * @param roll    上向量投影到屏幕后的角度（弧度，{@code atan2} 的值域）。
 *                矩形面光要摆正、旋转的法阵要跟着转，都靠这个
 * @param type    子类型序号 0..7。同一个效果层里让不同载体画不同形状用它——
 *                这是 {@code @param} 做不到的事：参数属于层，而它属于单个载体
 * @param custom1 作者自定的数值之一，语义由效果自己解释
 * @param custom2 作者自定的数值之二
 * @param spin    自转相位（弧度）。由运行时按载体的生命周期推进，
 *                用来让多个同类载体不在同一拍上，避免"一群一模一样的东西同步闪"
 */
public record EmitterSlot(float dirU, float dirV, float facing, float roll,
                          float type, float custom1, float custom2, float spin) {

    /** 空槽位。有效性由配对的 {@link AnchorSlot#strength} 说了算，这里不再重复一份判据。 */
    public static final EmitterSlot EMPTY = new EmitterSlot(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f);

    /** 每个槽位占用的 vec4 数量。改这个值要同步改 GlslCodegen 的注入和 PreviewRuntime 的写入。 */
    public static final int VEC4_PER_SLOT = 2;

    /** 子类型的取值上限（不含）。够放 some_of_fx 那几套形状里最多的一组，也够 GLSL 里用 switch 展开。 */
    public static final int MAX_TYPES = 8;
}
