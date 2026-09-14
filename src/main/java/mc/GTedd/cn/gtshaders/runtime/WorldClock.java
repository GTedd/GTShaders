package mc.GTedd.cn.gtshaders.runtime;

/**
 * 接管原版的世界时钟：把 {@code Globals.GameTime} 改写成编辑器自己的时间轴。
 *
 * <h2>它解决的问题</h2>
 *
 * <p>后处理层的时间走 {@code GTTime = GameTime * 1200 + GTSystem.x}，编辑器每帧写那个偏移，
 * 所以能暂停、能回拨。**核心着色器层没有这条路**——它们直接读 {@code Globals.GameTime}，
 * 那是原版的游戏时钟，按下暂停后处理层定住了、核心层照跑。
 * 同一个编辑器里两条路径对同一个按钮的反应不一样，这本身就是 bug。
 *
 * <p>修法是从源头改：{@code GameRendererMixin} 用 {@code @Redirect} 拦
 * {@code GlobalSettingsUniform.update}，把传进去的 {@code gameTime} 换成编辑器时钟对应的值。
 * 于是**两条路径都跟着编辑器走**，而着色器源码一个字都不用改。
 *
 * <h2>代价：整个画面都会跟着停</h2>
 *
 * <p>{@code Globals} 是全局的，覆盖它等于把原版的水面波动、传送门、附魔光效一起定住。
 * 定格看一帧时这正是想要的，平时则是干扰——所以它是个**独立开关，默认关**，
 * 而不是跟着播放/暂停按钮走。
 *
 * <h2>与资源包视角的关系</h2>
 *
 * <p>互斥，且资源包视角优先。资源包视角要模拟的是「玩家加载纯资源包」，那种情况下时间必然
 * 来自原版时钟；接管了世界时钟就不再是那个场景了。所以 {@link #isEngaged()} 在
 * 资源包视角下恒为 false——开关状态留着，切回来还在。
 *
 * <p>将来若要覆盖 {@code Globals} 的其它成员（相机位置、屏幕尺寸，用来做逐帧可复现对比），
 * 扩展点就在这里与那个 {@code @Redirect}，不必再找别的注入点。
 */
public final class WorldClock {

    /** 一个游戏日 = 24000 tick = 1200 秒，所以 20 tick/秒。 */
    private static final float TICKS_PER_SECOND = 20f;
    private static final long TICKS_PER_DAY = 24000L;

    private static boolean frozen;

    private WorldClock() {
    }

    /** 开关本身的状态，与当前是否真的生效无关。 */
    public static boolean isFrozen() {
        return frozen;
    }

    public static void setFrozen(boolean value) {
        frozen = value;
    }

    public static void toggle() {
        frozen = !frozen;
    }

    /**
     * 这一帧是否真的要接管。资源包视角下一律不接管，理由见类注释。
     */
    public static boolean isEngaged() {
        return frozen && !PreviewRuntime.isPackView();
    }

    /**
     * 编辑器时钟对应的 {@code gameTime}（整 tick 部分）。
     *
     * <p>原版把这两个数按 {@code ((gameTime % 24000) + partialTick) / 24000} 折成
     * {@code Globals.GameTime}，所以只要令 {@code gameTime + partialTick = 秒数 × 20}，
     * 着色器读到的就是编辑器时钟。超过 1200 秒后 {@code % 24000} 自然回绕——
     * 和资源包里的行为一致，不额外处理。
     */
    public static long gameTime() {
        return gameTimeFor(PreviewRuntime.editorTime());
    }

    /** 同上的小数部分。两个值必须成对使用，拆开用会丢掉亚 tick 精度，动画会一格一格跳。 */
    public static float partialTick() {
        return partialTickFor(PreviewRuntime.editorTime());
    }

    /**
     * 纯函数形式，供用例核对换算。
     *
     * <p>值得单独钉住：这里的 20 tick/秒 写错了不会报任何错，只表现为「动画快了或慢了」，
     * 而那种问题肉眼极难判定——尤其是它同时影响后处理层与核心着色器层，
     * 两边一起错的时候看上去反而「一致」。
     */
    public static long gameTimeFor(float seconds) {
        return (long) Math.floor(ticksFor(seconds)) % TICKS_PER_DAY;
    }

    public static float partialTickFor(float seconds) {
        double t = ticksFor(seconds);
        return (float) (t - Math.floor(t));
    }

    private static double ticksFor(float seconds) {
        return Math.max(0f, seconds) * (double) TICKS_PER_SECOND;
    }

}
