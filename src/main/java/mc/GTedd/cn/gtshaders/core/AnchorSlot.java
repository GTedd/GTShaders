package mc.GTedd.cn.gtshaders.core;

/**
 * 一个已经解算完毕的锚点槽位——世界里的某个东西在<b>这一帧</b>落在屏幕上的什么位置。
 *
 * <p>这是「让后处理看见世界」的那份数据。后处理通道天生只能看到一张颜色贴图，
 * 没有坐标、没有实体、没有深度；{@code AnchorRuntime} 每帧在 Java 侧把实体位置投影成屏幕
 * 坐标，写进 uniform，着色器里 {@code gtAnchorUV(0)} 拿到的就是它。
 *
 * <h2>为什么不走 VanillaDI 那套像素编码</h2>
 *
 * <p>JNNGL/VanillaDI 那套「把数据画进屏幕像素再解码」是
 * <b>纯资源包</b>唯一的出路——资源包在运行时没有任何手段改写 uniform，只能让渲染管线里
 * 别的环节把数据画进颜色缓冲，搭着它穿过管线边界。
 *
 * <p>本工程是 mod，{@code PreviewRuntime.uploadUniforms} 本来就每帧在往 uniform 缓冲写字节，
 * 多写几个 vec4 是同一条路径上的事。于是那套编码的全部代价都不必付：
 * 精度从 24 位定点回到 float32，不占用屏幕底行，多层链里每一层都读得到（像素方案只有第一层
 * 读得到原始数据，后面的层看到的是前一层重写过的画面），而且深度、距离、遮挡这些
 * 编码端根本塞不进去的东西，Java 侧随手就能算。
 *
 * <p><b>代价说清楚</b>：用了 {@code gtAnchor} 的效果导出成纯资源包后，没有人写这个 uniform，
 * 锚点会全部退化成无效（{@code strength == 0}），效果自动回落到屏幕空间形态。
 * 要在没装 mod 的原版客户端上拿到世界锚点，仍然只能走 {@code gtProbe} 那条编码端的路。
 * 两条路并存，互不干扰。
 *
 * <h2>字节布局</h2>
 *
 * <p>一个槽位占三个 vec4，与 {@code GlslCodegen} 生成的 std140 数组逐项对应：
 * <pre>
 *   GTAnchorA[i] = (u, v, depth, strength)
 *   GTAnchorB[i] = (radius, distance, life, visibility)
 *   GTAnchorC[i] = (dirX, dirY, dirZ, state)
 * </pre>
 *
 * <h2>C 那一组是给「看不见的锚点」用的</h2>
 *
 * <p>A、B 描述的是「它落在屏幕的哪里」，一旦目标转到视野外，这套坐标就没意义了——
 * {@code u/v} 要么在 0..1 之外，要么（相机背后）是个推出去的假坐标。
 * 但<b>影响力本身不该跟着消失</b>：黑洞转到身后，引力拖拽照样该把画面往那个方向拽；
 * 探测类的效果更是不管目标在哪都要生效。
 *
 * <p>所以 C 存两样东西：<b>视图空间的单位方向</b>（不受视锥限制，任何角度都成立）
 * 和一个<b>明确的状态码</b>。在此之前这两件事都只能从 {@code visibility == 0} 去猜，
 * 而那个 0 同时代表三种完全不同的处境——被墙挡住、在屏幕外、在身后，
 * 作者没法分别处理。
 *
 * @param u          屏幕横向 UV，0..1。与 {@code texCoord} 同一坐标系（GL 约定，原点在左下）
 * @param v          屏幕纵向 UV，0..1
 * @param depth      归一化深度 0..1，0 在近平面。多个锚点互相遮挡时用它排序
 * @param strength   本槽位的最终强度，<b>已经应用过缓动曲线</b>。
 *                   {@code 0} 表示这个槽位是空的——着色器靠它判断有效性，所以空槽必须严格写 0
 * @param radius     目标的世界半径投影到屏幕后的半径，单位是<b>纵向 UV</b>。
 *                   选纵向是为了对齐库效果里 {@code vec2 asp = vec2(OutSize.x/OutSize.y, 1.0)}
 *                   那个约定——在 {@code (texCoord - center) * asp} 这个等比空间里，
 *                   1.0 正好是一屏高度，radius 可以直接拿去和 {@code length(d)} 比
 * @param distance   锚点到相机的距离，单位方块。用来做距离衰减
 * @param life       生命周期进度 0..1 的<b>原始值</b>，没有应用缓动。
 *                   作者想自己写曲线时用它，想直接用调好的强度就用 {@code strength}
 * @param visibility 可见度 0..1。在视锥外或被方块完全挡住是 0
 * @param dirX       视图空间单位方向的 x（+ 为相机右手边）
 * @param dirY       视图空间单位方向的 y（+ 为相机上方）
 * @param dirZ       视图空间单位方向的 z，<b>+ 为相机正前方</b>。
 *                   GL 的视图空间是看向 −Z，这里刻意取反，好让「正前方 = +1、正后方 = −1」
 *                   这个更符合直觉的约定直接可用
 * @param state      位置状态，取值见 {@link #STATE_ON_SCREEN} 等四个常量
 */
public record AnchorSlot(float u, float v, float depth, float strength,
                         float radius, float distance, float life, float visibility,
                         float dirX, float dirY, float dirZ, float state) {

    /** 中心在屏幕内，而且没被挡住。 */
    public static final float STATE_ON_SCREEN = 0f;
    /** 中心在屏幕内，但从相机到它的射线被方块挡住了。 */
    public static final float STATE_OCCLUDED = 1f;
    /** 在相机<b>前方</b>但中心落到了屏幕外。{@code u/v} 仍然是准的（可能超出 0..1）。 */
    public static final float STATE_OFFSCREEN = 2f;
    /** 在相机<b>背后</b>。此时 {@code u/v} 是推到屏幕外的占位值，只有方向可信。 */
    public static final float STATE_BEHIND = 3f;

    /** 一个空槽位。{@code strength == 0} 是「无效」的唯一判据，着色器里 {@code gtAnchorValid} 认的就是它。 */
    public static final AnchorSlot EMPTY =
            new AnchorSlot(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f);

    /**
     * 每个槽位占用的 vec4 数量。
     *
     * <p>改这个值要<b>同时</b>改四处：本 record 的字段、{@code GlslCodegen} 的 std140 声明、
     * {@code PostEffectJsonBuilder} 铺进 JSON 的条目数、{@code PreviewRuntime} 每帧写入的顺序。
     * 少改一处不会报错，只会让整个 uniform 块错位——那是最难查的一类问题。
     */
    public static final int VEC4_PER_SLOT = 3;


    /**
     * 槽位总数。
     *
     * <p>定长而不是按需变长，是因为 std140 块的布局必须在编译期定死——数组长度变了就要重编译，
     * 而锚点数量是每帧都在变的运行时状态。8 个槽位 = 256 字节，对一个本来就要每帧重写的
     * uniform 块来说可以忽略；真到了视野里有九个僵尸的场合，少画一个黑洞不会有人发现。
     */
    public static final int SLOTS = 8;

    public boolean isEmpty() {
        return strength <= 0f;
    }
}
