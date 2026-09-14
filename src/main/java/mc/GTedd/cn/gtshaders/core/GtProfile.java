package mc.GTedd.cn.gtshaders.core;

import java.util.List;

/**
 * 目标 Minecraft 版本 profile。
 *
 * <p>本工程<b>只支持 26.3 及以后</b>。26.2 那一套（{@code #moj_import}、裸 in/out、
 * 旧的 {@code Globals} 顺序、没有 {@code /posteffect}）已经整体移除，
 * 需要它的话去 {@code 26.2} 分支。
 *
 * <p>枚举只剩一个常量，但结构保留着：GLSL 方言、原版 uniform 块布局、资源包格式号这些东西
 * 每个大版本都可能再动一次，届时加一个常量、把差异填进这里即可，
 * 模型层与 UI 层不需要感知。
 *
 * <h2>26.2 → 26.3 都变了什么（留档）</h2>
 *
 * <p>下面每一条都<b>逐字核对过官方 client.jar 里的实际资产</b>（26.3-pre-2 与 rc-2 的
 * {@code assets/minecraft/shaders/}），不是照抄更新日志——日志只说了 include 和 location，
 * 没提 uniform 块被重排，而后者才是最致命的那一条。
 *
 * <table border="1">
 *   <caption>实测差异</caption>
 *   <tr><th>项</th><th>26.2</th><th>26.3</th></tr>
 *   <tr><td>{@code #version}</td><td>330</td><td><b>仍是 330</b>，不是 450</td></tr>
 *   <tr><td>扩展</td><td>无</td>
 *       <td>{@code #extension GL_ARB_separate_shader_objects : require}（全部着色器都带）</td></tr>
 *   <tr><td>include</td><td>{@code #moj_import}</td><td>{@code #include}（改由 ShaderC 处理）</td></tr>
 *   <tr><td>in / out</td><td>裸声明</td>
 *       <td>必须 {@code layout(location = N)}，顶点输出与片段输入<b>只按 location 匹配</b>，名字被忽略</td></tr>
 *   <tr><td>顶点序号</td><td>{@code gl_VertexID}</td><td>{@code gl_VertexIndex}</td></tr>
 *   <tr><td>{@code Globals} 块</td><td>见 git 历史</td>
 *       <td>成员被<b>重排</b>以填掉 vec3 后的 padding，块从 56 字节压到 48（snapshot-6 起）</td></tr>
 *   <tr><td>post effect JSON</td><td colspan="2">格式完全一致，一字未改</td></tr>
 *   <tr><td>触发方式</td><td>无原版手段，只能劫持原版 id</td>
 *       <td>{@code /posteffect} 指令与常驻 {@code minecraft:end_of_frame}</td></tr>
 * </table>
 */
public enum GtProfile {
    /** 26.3。当前唯一支持的目标。 */
    MC_26_3("26.3");

    /**
     * 原版 {@code Globals} 块。
     *
     * <p>顺序<b>就是</b> std140 内存布局，少一个、换一次位置，后面全部错位——
     * 而错位不会报编译错误，只会让 {@code GameTime} 之类读出垃圾值，极难排查。
     *
     * <p>26.3 把两个 float 塞进了 {@code ivec3} / {@code vec3} 后面本来浪费掉的 4 字节里，
     * 所以顺序和 26.2 不同，不是简单加字段。这次重排发生在 <b>snapshot-6</b>
     * （不是 snapshot-5，那一版只改了 include 与 location 的写法）。
     */
    private static final List<String> GLOBALS = List.of(
            "ivec3 CameraBlockPos",
            "float GlintAlpha",
            "vec3 CameraOffset",
            "float GameTime",
            "vec2 ScreenSize",
            "int MenuBlurRadius",
            "int UseRgss");

    private final String display;

    GtProfile(String display) {
        this.display = display;
    }

    public String display() {
        return display;
    }

    /**
     * GLSL 版本号。
     *
     * <p>是 330——这一条特意留成方法而不是直接写死：26.3 引入 {@code layout(location)}
     * 时很容易想当然地以为要升到 450，实际上 Mojang 选择了保留 330 再 require
     * {@code GL_ARB_separate_shader_objects}。写在这里是为了让下一个人不必再猜一遍。
     */
    public String glslVersion() {
        return "330";
    }

    /** 原版 {@code Globals} 块的成员，顺序即内存布局。 */
    public List<String> globalsMembers() {
        return GLOBALS;
    }

    /**
     * 资源包 {@code min_format}。
     *
     * <p>取 <b>97</b>，也就是 26.3 预发布与候选版的资源包格式号（pre-1 到 rc-2 都是 97.1）。
     *
     * <p>不取更低的值是有原因的：着色器的新写法（{@code #include}、{@code layout(location)}）
     * 从 93.0 起生效，但 {@code Globals} 的成员重排要到 <b>94.0</b> 才发生。
     * 声明成 93 会让包在 93 那一版上加载成功却把 {@code GameTime} 读成 {@code GlintAlpha}——
     * 画面「不太对」但说不上哪里不对，是最难查的一类问题。
     */
    public int minPackFormat() {
        return 97;
    }

    /**
     * 资源包 {@code max_format}。
     *
     * <p>26.3 尚未发正式版，pack_format 还在涨（93 → 97 只隔了几个快照）。
     * 写死当前值会让包在下一个快照上直接从列表里消失，上界放宽让它至少能被看见、被启用。
     */
    public int maxPackFormat() {
        return 200;
    }

    /** i18n key，UI 里显示用。 */
    public String translationKey() {
        return "gtshaders.profile." + name().toLowerCase(java.util.Locale.ROOT);
    }
}
