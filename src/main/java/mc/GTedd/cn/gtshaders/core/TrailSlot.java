package mc.GTedd.cn.gtshaders.core;

/**
 * 一帧武器轨迹在<b>这一帧</b>投影到屏幕上的样子——刀刃的两个端点各落在哪儿。
 *
 * <p>这是「让后处理看见刀在哪」的那份数据，和 {@link AnchorSlot} 是同一条路子的两个用途：
 * 锚点回答「那个东西在屏幕上的哪一点」，轨迹回答「那把刀在过去这零点几秒里扫过了哪一片」。
 * 一个点撑不起刀光——刀光的形状<b>就是</b>刀刃线段随时间扫出来的那条带子。
 *
 * <h2>为什么不走 BladeFlash 那套像素编码</h2>
 *
 * <p><a href="https://github.com/YangMao-Minister/BladeFlash">BladeFlash</a> 是纯资源包，
 * 拿不到 uniform，只能让 {@code core/item} 把刀刃顶点画进屏幕底行的像素里，再让一条
 * {@code persistent} 的后处理链每帧把历史整体右移、顺带按相机位移补偿世界坐标，
 * 最后在片元里手写一遍光栅化。三件事——写像素、攒历史、补偿相机——全是为了绕开
 * 「资源包不能写 uniform」这一条限制。
 *
 * <p>本工程是 mod，这三件事一件都不用做：
 * <ul>
 *   <li><b>写像素</b> → {@code PreviewRuntime.uploadUniforms} 本来就每帧在写 uniform，
 *       多写几个 vec4 是同一条路径上的事，精度还从 24 位定点回到 float32；</li>
 *   <li><b>攒历史</b> → Java 侧一个环形缓冲，不占屏幕像素，也不受「多层链里只有第一层
 *       读得到原始数据」的限制；</li>
 *   <li><b>补偿相机</b> → 历史直接存<b>绝对世界坐标</b>（{@code TrailRuntime.Sample}），
 *       人走动、转视角时轨迹天然留在原地。BladeFlash 那段 {@code TURE_WORLDSPACE} 补偿
 *       是因为它存的是相机相对坐标，不补就会跟着人飘。</li>
 * </ul>
 *
 * <p><b>代价和 {@code gtAnchor} 完全一样</b>：导出成纯资源包后没有人写这个 uniform，
 * 所有槽位 {@code valid == 0}，效果自动退回「什么都不画」。要在没装 mod 的客户端上
 * 拿到刀刃轨迹，仍然只能走 BladeFlash 那条编码端的路。
 *
 * <h2>字节布局</h2>
 *
 * <p>一个槽位占两个 vec4，与 {@code GlslCodegen} 生成的 std140 数组逐项对应：
 * <pre>
 *   GTTrailA[i] = (rootU, rootV, tipU, tipV)
 *   GTTrailB[i] = (age, depth, valid, speed)
 * </pre>
 *
 * <p>下标 0 永远是<b>最新</b>的那一帧。倒过来存（0 最老）的话，缓冲没攒满时最新的样本
 * 会落在一个随帧数变化的下标上，着色器里就没法写死「从刀根那头开始扫」。
 *
 * @param rootU 刀根（靠手那端）的屏幕横向 UV，0..1，与 {@code texCoord} 同一坐标系
 * @param rootV 刀根的屏幕纵向 UV
 * @param tipU  刀尖的屏幕横向 UV
 * @param tipV  刀尖的屏幕纵向 UV
 * @param age   这一帧有多老，单位<b>秒</b>。{@code 0} 是当前帧。
 *              给秒数而不是给归一化的 0..1，是因为「拖尾留多久」该由效果的参数说了算：
 *              着色器里写 {@code age / TrailDuration} 就得到自己那条曲线的进度，
 *              而 Java 侧不必知道有几个效果、各自想要多长。
 *              <b>按时间而不是按帧序号计</b>——按帧序号的话 144Hz 下的拖尾会比 30Hz 短五倍
 * @param depth 刀刃中点的归一化深度 0..1，0 在近平面。想让刀光被前景遮住时用它
 * @param valid 有效位，{@code 0} 表示这个槽位是空的。着色器靠它判断，所以空槽必须严格写 0
 * @param speed 相对上一帧的屏幕位移长度，单位是<b>纵向 UV</b>。挥得越快条带越宽/越亮就靠它，
 *              而这正是「随便晃一下不该出刀光、真挥一刀才出」的判据
 */
public record TrailSlot(float rootU, float rootV, float tipU, float tipV,
                        float age, float depth, float valid, float speed) {

    /** 一个空槽位。{@code valid == 0} 是「无效」的唯一判据，着色器里 {@code gtTrailValid} 认的就是它。 */
    public static final TrailSlot EMPTY = new TrailSlot(0f, 0f, 0f, 0f, 0f, 1f, 0f, 0f);

    /** 每个槽位占用的 vec4 数量。改这个值要同步改 GlslCodegen 的注入和 PreviewRuntime 的写入。 */
    public static final int VEC4_PER_SLOT = 2;

    /**
     * 轨迹长度上限（帧数）。
     *
     * <p>24 是拿两头卡出来的：下限是「一次挥砍要有足够多的段，条带边缘才不会看出折线」——
     * 原版挥砍动画约 0.3 秒，60Hz 下正好十几帧，再少就开始有棱角了；上限是 uniform 预算——
     * 24 槽 = 49 个 vec4 = 784 字节，和锚点的 272 字节加起来仍然远在任何实现的 UBO 上限之内。
     *
     * <p>高帧率下这 24 帧覆盖的<b>时间</b>更短，所以 {@code TrailRuntime} 是按时间窗口
     * 抽样填进来的，不是每个渲染帧都塞一个——否则 240Hz 下拖尾只有 0.1 秒，短得看不见。
     */
    public static final int SLOTS = 24;

    public boolean isEmpty() {
        return valid <= 0f;
    }
}
