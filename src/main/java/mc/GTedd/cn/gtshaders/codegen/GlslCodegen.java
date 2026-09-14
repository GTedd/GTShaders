package mc.GTedd.cn.gtshaders.codegen;

import mc.GTedd.cn.gtshaders.core.AnchorSlot;
import mc.GTedd.cn.gtshaders.core.EmitterSlot;
import mc.GTedd.cn.gtshaders.core.TrailSlot;
import mc.GTedd.cn.gtshaders.core.BlendMode;
import mc.GTedd.cn.gtshaders.core.GtProfile;
import mc.GTedd.cn.gtshaders.core.ShaderParam;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 把「作者源码 + 参数表 + 图层设置」组装成原版能直接编译的 post effect 片段着色器。
 *
 * <p>几个刻意的设计决定：
 *
 * <p><b>1. 不用 {@code #moj_import}，直接内联 Globals 块。</b>
 * import 会被 Minecraft 在编译前做文本展开，插入行数不确定，导致驱动报的错误行号无法映射回
 * 作者看到的源码。内联之后头部行数完全由我们决定，{@link Output#headerLineCount()} 就是精确偏移量。
 *
 * <p><b>2. 参数按 std140 对齐降序排布。</b>
 * 原版能正确处理 vec3 后跟 float 之类的填充，但降序排布让块布局稳定可预测，
 * 出问题时人工核对 JSON 与 GLSL 的对应关系要容易得多。
 *
 * <p><b>3. 时间 = 原版游戏时钟 + 一个偏移量：{@code GTTime = GameTime * 1200.0 + GTSystem.x}。</b>
 * 这一条决定了预览与导出物是否真的一致。资源包里没有 mod 每帧写 uniform，
 * {@code GTSystem} 就是 JSON 里那几个常量（偏移 0），于是时间直接来自原版的
 * {@code Globals.GameTime}（一天 24000 tick 折成 [0,1)，乘 1200 得秒）。
 * 编辑器要播放/暂停/拖时间轴，靠的是每帧把偏移写成「编辑器时钟 − 游戏时钟」——
 * 着色器源码<b>一个字都不用变</b>，同一份 GLSL 在编辑器里和资源包里跑的是同一段代码。
 * 代价是资源包里 {@code GTTime} 每 1200 秒回绕一次（原版时钟的固有性质），
 * 编辑器的「资源包视角」会如实呈现这一点。
 *
 * <p><b>4. 混合是包一层 main 实现的，不要求作者改写代码。</b>
 * 作者的 {@code void main()} 被重命名成 {@code gtAuthorMain()}，我们再生成一个真正的
 * {@code main()} 去调它并把结果与原画面混合。重命名只改同一行的文本、不增删行，
 * 所以错误行号仍然准确。
 */
public final class GlslCodegen {

    /** 生成的用户参数 uniform 块名。加前缀避免与原版块名撞车。 */
    public static final String PARAM_BLOCK = "GTParams";
    /** 主颜色输入的 sampler_name，落到 GLSL 里就是 {@code InSampler}。 */
    public static final String MAIN_SAMPLER_NAME = "In";
    /**
     * 场景深度输入的 sampler_name，落到 GLSL 里就是 {@code InDepthSampler}。
     *
     * <p><b>必须是另一个独立的输入</b>，不能给 {@code In} 加 {@code use_depth_buffer}——
     * 原版 {@code PostPass$TargetInput} 的取值是
     * {@code depthBuffer ? getDepthTextureView() : getColorTextureView()}，
     * 同一个 sampler 二选一。给 {@code In} 加标志会把颜色输入整个换成深度图，画面全黑。
     */
    public static final String DEPTH_SAMPLER_NAME = "InDepth";
    /**
     * 系统参数：x=时间偏移(秒，加在 {@code GameTime * 1200} 上) y=帧间隔 z=帧序号 w=播放中(1/0)。
     *
     * <p>资源包里它是常量 {@link PostEffectJsonBuilder#PACK_SYSTEM}：偏移 0、帧间隔 0、帧序号 0、播放 1。
     * 所以 {@code GTDeltaTime} / {@code GTFrame} / {@code GTPlaying} 只在编辑器里有意义，
     * 导出后是常量——这是纯资源包的固有限制，不是 bug。
     */
    public static final String SYSTEM_UNIFORM = "GTSystem";
    /** 原版 {@code GameTime} 是一天的比例（24000 tick → [0,1)），乘它得到秒。 */
    public static final String GAME_DAY_SECONDS = "1200.0";
    /** 图层参数：x=混合强度 y=本层序号 z=层总数 w=保留。 */
    public static final String LAYER_UNIFORM = "GTLayer";
    /**
     * 取景框：(u0, v0, u1, v1)，GL 纹理坐标系（原点在左下）。
     * 框外的像素直接输出原画面，这样拖动画布上的取景框就能实时看到 before/after 分界。
     */
    public static final String VIEWPORT_UNIFORM = "GTViewport";
    /** 锚点表头：x=有效锚点数 y=槽位总数 z,w=保留。 */
    public static final String ANCHOR_INFO_UNIFORM = "GTAnchorInfo";
    /** 锚点数组 A：(u, v, depth, strength)。 */
    public static final String ANCHOR_A_UNIFORM = "GTAnchorA";
    /** 锚点数组 B：(radius, distance, life, visibility)。 */
    public static final String ANCHOR_B_UNIFORM = "GTAnchorB";
    /**
     * 锚点数组 C：(dirX, dirY, dirZ, state)。
     *
     * <p>视图空间单位方向 + 位置状态码。A、B 描述「落在屏幕哪里」，一旦目标转出视野就失效；
     * C 不受视锥限制，是「黑洞在身后也要有引力」这类效果唯一可用的输入。
     */
    public static final String ANCHOR_C_UNIFORM = "GTAnchorC";
    /** 载体数组 A：(dirU, dirV, facing, roll)。槽位号与锚点逐一对齐。 */
    public static final String EMITTER_A_UNIFORM = "GTEmitterA";
    /** 载体数组 B：(type, custom1, custom2, spin)。 */
    public static final String EMITTER_B_UNIFORM = "GTEmitterB";
    /** 轨迹表头：x=有效样本数 y=槽位总数 z=挥砍进度 w=保留。 */
    public static final String TRAIL_INFO_UNIFORM = "GTTrailInfo";
    /** 轨迹数组 A：(rootU, rootV, tipU, tipV)。 */
    public static final String TRAIL_A_UNIFORM = "GTTrailA";
    /** 轨迹数组 B：(age, depth, valid, speed)。 */
    public static final String TRAIL_B_UNIFORM = "GTTrailB";

    /**
     * 世界相机：投影系数 (tanHalfFovY, aspect, ProjMat[2][2], ProjMat[3][2])。
     *
     * <p>后处理链自己的 {@code Projection} 块是<b>正交屏幕投影</b>，与世界相机毫无关系
     * （见 {@link #DEPTH_HELPERS} 的说明），所以链里那份矩阵没法把设备深度换算成米。
     * 这四个数由 mod 每帧从真实的世界相机抄过来，深度于是终于能变成距离。
     *
     * <p>纯资源包里没有来源，JSON 初值是 0——{@code gtLinearDepth} 一族会退回一台
     * 默认相机（FOV 70、zNear 0.05、宽高比按 {@code OutSize} 现算）。判据用第四个分量：
     * 真实的 {@code ProjMat[3][2]} 就是 zNear，恒大于 0，而第三个分量在
     * 反转无限远投影下本来就是 0，拿它当判据会分不清「没数据」和「真的是 0」。
     */
    public static final String CAMERA_PROJ_UNIFORM = "GTCameraProj";
    /** 世界相机：视图空间 X 轴在世界空间的指向（逆视图矩阵第 0 列），w 保留。 */
    public static final String CAMERA_RIGHT_UNIFORM = "GTCameraRight";
    /** 世界相机：视图空间 Y 轴在世界空间的指向（逆视图矩阵第 1 列），w 保留。 */
    public static final String CAMERA_UP_UNIFORM = "GTCameraUp";
    /**
     * 世界相机：视图空间 Z 轴在世界空间的指向（逆视图矩阵第 2 列），w 保留。
     *
     * <p>叫 Back 而不是 Forward 是因为视图空间的 −Z 才是前方——存的是矩阵的列，
     * 名字跟着数学走，免得有人拿它当视线方向直接用。
     */
    public static final String CAMERA_BACK_UNIFORM = "GTCameraBack";
    /** 相机块占的 vec4 个数：投影系数 + 三根基向量。三处（GLSL、JSON、每帧写入）共用。 */
    public static final int CAMERA_VEC4 = 4;

    /**
     * 场景深度的辅助函数。只在源码出现 {@code gtDepth} 时注入——多一个输入就多一个
     * {@code SamplerInfo} 成员和一次纹理绑定，不用的效果不该付这份钱。
     */
    private static final String DEPTH_HELPERS = """

            // ---- 场景深度（26.3 才真正可用）----
            // snapshot-4 修了 MC-309771 之后，/posteffect 挂的链读 minecraft:main 的深度
            // 拿到的是完整场景深度，而不再是只有手臂。26.2 上这条路走不通。
            //
            // 值是「设备深度」，不是米。26.2-snapshot-1 起 Minecraft 用反转深度：
            // 近处接近 1、远处与天空接近 0。想线性化成实际距离需要世界相机的 ProjMat
            // （原版 deviceToLinearDepth 就是 ProjMat[3][2] / (d + ProjMat[2][2])），
            // 而后处理链的 Projection 块是正交屏幕投影、跟世界相机无关——
            // 所以在这里**拿不到真实距离**，只能做相对比较：描边、遮罩、按远近淡出。
            float gtDepth(vec2 uv) {
                return texture(InDepthSampler, uv).r;
            }

            // 按像素取，不做插值。描边这类要精确对齐像素格的用法该用它
            float gtDepthTexel(ivec2 px) {
                return texelFetch(InDepthSampler, px, 0).r;
            }

            // 天空没有几何，深度停在最远处（反转深度下是 0）。
            // 不排除天空的话，天与地的交界会被当成一条极强的边
            bool gtIsSky(vec2 uv) {
                return gtDepth(uv) <= 1e-6;
            }

            // 四邻域绝对差之和。深度是非线性的：同样的实际距离差，近处给出的数值差大得多，
            // 所以近处的边天然更明显、远处需要把阈值调得很小。要两头兼顾就除以中心深度
            // （gtDepthEdgeRelative），代价是天空附近会放大噪声
            float gtDepthEdge(vec2 uv, float radius) {
                vec2 step = radius / max(InDepthSize, vec2(1.0));
                float c = gtDepth(uv);
                return abs(gtDepth(uv - vec2(step.x, 0.0)) - c)
                     + abs(gtDepth(uv + vec2(step.x, 0.0)) - c)
                     + abs(gtDepth(uv - vec2(0.0, step.y)) - c)
                     + abs(gtDepth(uv + vec2(0.0, step.y)) - c);
            }

            // 相对版：除以中心深度，让远处的边也出得来。天空处 c→0，所以先夹一个下限
            float gtDepthEdgeRelative(vec2 uv, float radius) {
                float c = max(gtDepth(uv), 1e-4);
                return gtDepthEdge(uv, radius) / c;
            }
            """;

    /**
     * 世界相机的辅助函数：把设备深度还原成米、视图坐标与世界坐标。
     *
     * <p>{@link #DEPTH_HELPERS} 到此为止只能做相对比较——「谁离得近」而不是「离多远」。
     * 这一组补上缺的那一半：有了真实相机的投影系数与朝向，屏幕上每个像素都能还原成
     * 视图空间坐标，进而是世界坐标。世界固定的网格、按米计的距离雾、从脚下向外推进的
     * 扩散半径，全都建立在这一步上。
     *
     * <p><b>相机位置不在这一组里</b>：原版 {@code Globals} 本来就带着
     * {@code CameraBlockPos} 与 {@code CameraOffset}，两者相加就是相机的世界坐标，
     * 而且纯资源包里同样是真值。重复传一份只会多一个可能对不上的事实来源。
     *
     * <p>只在源码出现相机函数名时注入，并且会连带把深度输入一起打开——
     * 每一个函数都要先读深度。
     */
    private static final String CAMERA_HELPERS = """

            // ---- 世界相机：深度 → 米 → 视图坐标 → 世界坐标 ----
            // 四个 uniform 由 mod 每帧从真实相机抄来。纯资源包里全是 0，
            // 下面每个 helper 都自带回退，所以效果不会崩，只会退化：
            // 距离与视图坐标仍然正确（默认 FOV 70、zNear 0.05），
            // 但世界坐标会跟着视角一起转——钉不到世界上。
            // 「这一帧有没有真相机数据」只认第四个分量：它是 ProjMat[3][2]，也就是 zNear，
            // 真值恒大于 0。第三个分量在反转无限远投影下本来就是 0，拿它当判据分不清
            // 「没数据」和「真的是 0」；第一个分量则可能因 NDC 上下翻转而为负
            bool gtCameraLive() {
                return GTCameraProj.w > 0.0;
            }
            float gtTanHalfFov() {
                return gtCameraLive() ? GTCameraProj.x : 0.7002075;
            }
            float gtAspect() {
                return gtCameraLive() ? GTCameraProj.y : OutSize.x / max(OutSize.y, 1.0);
            }

            // 设备深度 → 米。就是原版 deviceToLinearDepth：ProjMat[3][2] / (d + ProjMat[2][2])。
            // 反转深度下天空 d→0，商趋向无穷，所以夹一个上限——inf 一旦进了后面的
            // exp/乘法，整片天空会变成 NaN 黑块
            float gtLinearDepth(vec2 uv) {
                float zNear = gtCameraLive() ? GTCameraProj.w : 0.05;
                return min(zNear / max(gtDepth(uv) + GTCameraProj.z, 1e-7), 1e6);
            }

            // 视图空间坐标：x 右、y 上、−z 前，单位是米。
            // 天空处 z 会跑到 1e6 那个上限，需要区分的话先问 gtIsSky
            vec3 gtViewPos(vec2 uv) {
                float z = gtLinearDepth(uv);
                vec2 ndc = uv * 2.0 - 1.0;
                float t = gtTanHalfFov();
                return vec3(ndc.x * t * gtAspect() * z, ndc.y * t * z, -z);
            }

            // 不依赖深度的视线方向（视图空间）。天空没有几何，只能用它
            vec3 gtViewDir(vec2 uv) {
                vec2 ndc = uv * 2.0 - 1.0;
                float t = gtTanHalfFov();
                return normalize(vec3(ndc.x * t * gtAspect(), ndc.y * t, -1.0));
            }

            // 视图空间方向 → 世界空间。三根基向量全 0 时退回单位矩阵，
            // 也就是「相机永远在原点朝 −Z」——纯资源包里就是这个形态
            vec3 gtViewToWorld(vec3 v) {
                vec3 r = GTCameraRight.xyz;
                vec3 u = GTCameraUp.xyz;
                vec3 b = GTCameraBack.xyz;
                if (dot(r, r) + dot(u, u) + dot(b, b) < 1e-6) {
                    return v;
                }
                return r * v.x + u * v.y + b * v.z;
            }

            vec3 gtWorldDir(vec2 uv) {
                return normalize(gtViewToWorld(gtViewDir(uv)));
            }

            // 相机的世界坐标。这一个在纯资源包里也是真值——原版 Globals 就带着它
            vec3 gtCameraPos() {
                return vec3(CameraBlockPos) + CameraOffset;
            }

            vec3 gtWorldPos(vec2 uv) {
                return gtCameraPos() + gtViewToWorld(gtViewPos(uv));
            }

            // 相机到该像素的直线距离（米）
            float gtDistance(vec2 uv) {
                return length(gtViewPos(uv));
            }

            // 由深度重建的视图空间法线：四邻域各取一个视图坐标，用「差得更小」的那一侧做叉乘。
            // 跨过几何边界的那一侧深度会突变，用它算出来的法线是乱转的，所以两边都超阈值时
            // 直接退回朝上——地面占了这类效果里绝大多数像素，猜朝上比猜错方向便宜
            vec3 gtViewNormal(vec2 uv) {
                vec3 c = gtViewPos(uv);
                vec2 px = 1.0 / max(InDepthSize, vec2(1.0));
                vec3 l = c - gtViewPos(uv - vec2(px.x, 0.0));
                vec3 r = gtViewPos(uv + vec2(px.x, 0.0)) - c;
                vec3 d = c - gtViewPos(uv - vec2(0.0, px.y));
                vec3 u = gtViewPos(uv + vec2(0.0, px.y)) - c;
                float scale = pow(max(abs(c.z), 0.1), 2.0) * 0.003;
                if (abs(l.z) > scale && abs(r.z) > scale
                        && abs(d.z) > scale && abs(u.z) > scale) {
                    return vec3(0.0, 1.0, 0.0);
                }
                vec3 h = abs(l.z) < abs(r.z) ? l : r;
                vec3 v = abs(d.z) < abs(u.z) ? d : u;
                vec3 n = cross(h, v);
                return dot(n, n) < 1e-12 ? vec3(0.0, 1.0, 0.0) : normalize(n);
            }

            vec3 gtWorldNormal(vec2 uv) {
                return normalize(gtViewToWorld(gtViewNormal(uv)));
            }
            """;


    /**
     * 数据探针的解码端，改编自 JNNGL/VanillaDI（MIT，许可证全文见 THIRD_PARTY_NOTICES.md）。
     *
     * <p>后处理通道只能看到一张颜色贴图，<b>拿不到任何世界信息</b>——没有坐标、没有实体、
     * 没有深度。VanillaDI 绕开这一点的办法是：让渲染管线里<b>别的东西</b>把数据画进屏幕，
     * 后处理再把那几个像素读回来解码。数据于是搭着颜色缓冲这趟车穿过了管线边界。
     *
     * <p>本工程沿用同一思路，并且<b>直接采用 VanillaDI 实测在用的那套编解码</b>——
     * 逐位兼容，不是另起炉灶：
     * <pre>
     *   一个像素 = 一个有符号 24 位定点数
     *   r + g*256 + b*65536，符号位借用 b 的最高位
     *   gtDecodeFloat     值 / 40000    量程 ±209，够放矩阵元素与方向向量
     *   gtDecodeFloat1024 值 / 1024     量程 ±8191，够放世界坐标
     * </pre>
     *
     * <p>为什么不自己设计一套：VanillaDI 的这套在一个真正发布的资源包里跑过，
     * 而且沿用它意味着<b>编码端可以直接抄它的核心着色器</b>，不必从零写一遍——
     * 对只想用这个功能的人来说，这是省掉的最大一块工作量。
     *
     * <p>像素布局同样对齐 VanillaDI 的数据条：第 0..31 个像素是投影矩阵与视图矩阵，
     * 32..34 是相机位置，35 是数量，36 起是逐个光源/锚点的数据。
     * 本工程的锚点槽位就落在 36 之后，每个槽位 4 个像素（x, y, 半径, 强度）。
     *
     * <p><b>刻意不提供「按像素解出整个矩阵」的 helper</b>：那要 32 次 texelFetch，
     * VanillaDI 把它放在后处理的<b>顶点</b>着色器里（一帧只算 4 次），
     * 而本工程的通道固定用原版 {@code core/screenquad} 当顶点着色器，改不了。
     * 逐像素解矩阵在 1080p 下是六千多万次采样，那不是能用的东西。
     * 需要的人可以自己用 {@code gtDecodeFloat} 拼。
     *
     * <p>探针像素本身不会露出来——这一层的 {@code main()} 会重写每一个像素，包括底行那几个。
     *
     * <p>只有源码里真的出现 {@code gtProbe} 时才注入，其余着色器一行都不多。
     */

    private static final String PROBE_HELPERS = """

            // ---- 数据探针：从屏幕保留像素里取回世界侧写入的锚点 ----
            // 编码格式与解码算法改编自 JNNGL/VanillaDI（MIT），与它的编码端逐位兼容
            int gtDecodeInt(vec3 c) {
                // 先四舍五入再取整：纹素是 8 位定点，k/255*255 在 float32 下可能落到 k-0.0001，
                // 直接 int() 截断就会整整差 1，而这一位在 24 位里权重高达 65536
                c = floor(c * 255.0 + 0.5);
                int s = c.b >= 128.0 ? -1 : 1;
                return s * (int(c.r) + int(c.g) * 256 + (int(c.b) - 64 + s * 64) * 65536);
            }
            float gtDecodeFloat(vec3 c) {
                return float(gtDecodeInt(c)) / 40000.0;
            }
            float gtDecodeFloat1024(vec3 c) {
                return float(gtDecodeInt(c)) / 1024.0;
            }
            vec3 gtProbeTexel(int px) {
                return texture(InSampler, (vec2(float(px), 0.0) + 0.5) / max(InSize, vec2(1.0))).rgb;
            }
            // 数据条表头：第 35 个像素是锚点/光源数量
            int gtProbeCount() {
                return gtDecodeInt(gtProbeTexel(35));
            }
            // 锚点槽位接在表头之后，每个占 4 个像素
            #define GT_PROBE_BASE 36
            // 返回 (x, y, 半径, 强度)；强度为 0 表示这个槽位没写过数据
            vec4 gtProbe(int slot) {
                int base = GT_PROBE_BASE + slot * 4;
                return vec4(gtDecodeFloat(gtProbeTexel(base)),
                            gtDecodeFloat(gtProbeTexel(base + 1)),
                            gtDecodeFloat(gtProbeTexel(base + 2)),
                            gtDecodeFloat(gtProbeTexel(base + 3)));
            }
            bool gtProbeValid(vec4 probe) {
                return probe.w > 0.0;
            }
            """;

    /**
     * 无损位打包：把一个 float32 的<b>位模式</b>拆进一个 RGBA8 纹素的四个字节。
     *
     * <p>解决的是 {@link #PROBE_HELPERS} 解决不了的那半个问题。缓冲是 8 位定点，
     * 直接把一个浮点写进颜色通道，读回来只剩 256 级——{@code gtDecodeFloat} 用三个通道
     * 拼出 24 位定点已经是这条路的尽头，而且要先选好量程（±209 还是 ±8191），选错就溢出。
     * 拆位模式没有量程可选：<b>写进去什么读出来就是什么</b>，NaN 和 inf 也一样能过。
     *
     * <p>字节布局是小端：R 放最低字节，A 放最高字节。这和 {@code xpncvr/llm-postshader} 的
     * {@code encf}/{@code decf} 是同一种格式，两边打出来的数据可以互相读；实现是这里自己写的。
     *
     * <h2>三个前提，破一个就全毁</h2>
     * <ol>
     *   <li><b>不能插值</b>。位模式经过 bilinear 混合就是一个毫无意义的新数——不是误差大，
     *       是彻底的垃圾。所以取值只能走 {@code gtUnpackFloatAt}（内部是 {@code texelFetch}），
     *       层的「双线性过滤」开关也必须关着。</li>
     *   <li><b>不能被后续通道当颜色改一下</b>。打包过的纹素长得像噪点，任何调色、模糊、
     *       混合都会把它算坏。约定放在画面边角、并由读它的那一层重写掉。</li>
     *   <li><b>alpha 也是数据的一部分</b>。高 8 位落在 alpha 上，所以这条链上不能有
     *       alpha 混合。原版 post pass 不开 blend，{@code minecraft:post/blit} 也是直读直写，
     *       当前的乒乓链是安全的——但自己另加通道时要记住这一条。</li>
     * </ol>
     *
     * <p><b>最实在的用法不是层间传值，而是配 {@code @texture} 当数据表</b>：
     * 把任意浮点数组按上面的字节布局写成 PNG（每个 float32 占一个 RGBA 纹素），
     * 再用 {@code gtUnpackFloatAt(Tbl, px)} 精确取回。色调映射曲线、大气散射表、
     * 采样核——凡是「算不动但可以预先算好」的东西都能这么塞进资源包，而且是 float32 精度。
     *
     * <p>只有源码里真的出现 {@code gtPack} 或 {@code gtUnpack} 时才注入。
     */
    private static final String BITPACK_HELPERS = """

            // ---- 无损位打包：一个 RGBA8 纹素装一个 float32 的位模式 ----
            // 小端：R 是最低字节，A 是最高字节
            vec4 gtPackUint(uint v) {
                uvec4 bytes = (uvec4(v) >> uvec4(0u, 8u, 16u, 24u)) & uvec4(255u);
                return vec4(bytes) / 255.0;
            }
            uint gtUnpackUint(vec4 c) {
                // 先四舍五入再取整。UNORM8 转 float 是硬件做的，规范只保证误差在一个 ULP 内，
                // 不保证逐位等于 k/255；驱动还常把除法优化成乘 1/255，而 1/255 不可精确表示。
                // 采回来只要偏一点点，截断就整整差 1——这一位若落在指数域，就是差一个数量级
                uvec4 bytes = uvec4(floor(clamp(c, 0.0, 1.0) * 255.0 + 0.5));
                return bytes.r + (bytes.g << 8u) + (bytes.b << 16u) + (bytes.a << 24u);
            }
            vec4 gtPackFloat(float v) {
                return gtPackUint(floatBitsToUint(v));
            }
            float gtUnpackFloat(vec4 c) {
                return uintBitsToFloat(gtUnpackUint(c));
            }
            // 精确取一个纹素。位打包的数据一插值就毁，所以只能这么读，不能用 texture()
            float gtUnpackFloatAt(sampler2D s, ivec2 px) {
                return gtUnpackFloat(texelFetch(s, px, 0));
            }
            uint gtUnpackUintAt(sampler2D s, ivec2 px) {
                return gtUnpackUint(texelFetch(s, px, 0));
            }
            """;

    /**
     * 世界锚点：由 mod 每帧直接写进 uniform 的实体/坐标屏幕投影。
     *
     * <p>和 {@link #PROBE_HELPERS} 解决的是同一个问题——<b>后处理看不见世界</b>——
     * 但走的是完全不同的一条路，因为前提不同：
     *
     * <table border="1">
     *   <caption>两条锚点路径</caption>
     *   <tr><th></th><th>{@code gtProbe}（VanillaDI 式）</th><th>{@code gtAnchor}（本 mod 式）</th></tr>
     *   <tr><td>数据怎么进来</td><td>核心着色器把它画进屏幕像素，这里再解码</td><td>Java 侧每帧写 uniform</td></tr>
     *   <tr><td>要不要装 mod</td><td>不要，纯资源包即可</td><td><b>要</b></td></tr>
     *   <tr><td>要不要数据包+核心着色器</td><td><b>要</b></td><td>不要</td></tr>
     *   <tr><td>精度</td><td>24 位定点</td><td>float32</td></tr>
     *   <tr><td>多层链</td><td>只有第一层读得到</td><td>每层都读得到</td></tr>
     *   <tr><td>深度/距离/遮挡</td><td>编码端塞了才有</td><td>都有</td></tr>
     * </table>
     *
     * <p><b>导出成纯资源包后 {@code gtAnchor} 会失效</b>：没有 mod 就没有人写这个 uniform，
     * 所有槽位的 {@code strength} 都是 0，{@code gtAnchorValid} 一律返回 false。
     * 这是有意的降级——效果会自动退回屏幕空间形态，而不是画出一堆垃圾。
     * 要在没装 mod 的原版客户端上拿到世界锚点，用 {@code gtProbe}。
     *
     * <p>只有源码里真的出现 {@code gtAnchor} 时才注入，其余着色器一行都不多，
     * uniform 块里也不会平白多出 17 个 vec4。
     */
    private static final String ANCHOR_HELPERS = """

            // ---- 世界锚点：由 GTShaders 每帧从实体位置投影而来 ----
            // 没装 mod 时（例如导出成纯资源包后）全部槽位无效，效果自动退回屏幕空间形态
            int gtAnchorCount() {
                return int(GTAnchorInfo.x + 0.5);
            }
            // 越界返回空槽而不是钳到边界：钳过去会让 gtAnchor(99) 静悄悄拿到最后一个锚点的数据，
            // 那种错比直接什么都不画难查得多
            bool gtAnchorValid(int i) {
                return i >= 0 && i < GT_ANCHOR_SLOTS && GTAnchorA[i].w > 0.0;
            }
            vec2 gtAnchorUV(int i) {
                return gtAnchorValid(i) ? GTAnchorA[i].xy : vec2(-1.0);
            }
            float gtAnchorDepth(int i) {
                return gtAnchorValid(i) ? GTAnchorA[i].z : 1.0;
            }
            // 已经应用过缓动曲线的强度；0 表示这个槽位是空的
            float gtAnchorStrength(int i) {
                return gtAnchorValid(i) ? GTAnchorA[i].w : 0.0;
            }
            // 目标世界半径投影出来的屏幕半径，单位是纵向 UV——和 gtAnchorRange 同一个空间，可以直接比
            float gtAnchorRadius(int i) {
                return gtAnchorValid(i) ? GTAnchorB[i].x : 0.0;
            }
            float gtAnchorDistance(int i) {
                return gtAnchorValid(i) ? GTAnchorB[i].y : 0.0;
            }
            // 生命周期进度 0..1 的原始值，没加缓动。想自己写曲线的用它
            float gtAnchorLife(int i) {
                return gtAnchorValid(i) ? GTAnchorB[i].z : 0.0;
            }
            // 可见度：在视野外或被方块挡住是 0。位置类的效果都该乘上它
            float gtAnchorVisible(int i) {
                return gtAnchorValid(i) ? GTAnchorB[i].w : 0.0;
            }
            // 当前像素到锚点的偏移，已做等比校正——宽屏上圆形效果才不会被拉成椭圆
            vec2 gtAnchorDelta(int i) {
                vec2 asp = vec2(OutSize.x / max(OutSize.y, 1.0), 1.0);
                return (texCoord - gtAnchorUV(i)) * asp;
            }
            float gtAnchorRange(int i) {
                return length(gtAnchorDelta(i));
            }

            // ---- 看不见的锚点：转出视野之后仍然可用的那部分 ----
            // 上面那些全都建立在「它落在屏幕的某个位置」之上，目标一转到视野外就没意义了。
            // 但影响力本身不该跟着消失——黑洞转到身后，引力照样该把画面往那边拽。
            // 下面这组不受视锥限制，任何角度都成立。
            //
            // 注意 gtAnchorVisible 在「屏幕外」和「背后」都是 0，所以做这类效果时
            // <b>不要</b>拿它当总开关乘上去，那正是效果一转身就消失的原因；
            // 该乘的是 gtAnchorStrength，它只受缓动曲线和距离影响，与看不看得见无关。

            // 视图空间单位方向：x 朝相机右手边，y 朝上，z <b>正前方为 +1、正后方为 −1</b>
            vec3 gtAnchorDir(int i) {
                return gtAnchorValid(i) ? GTAnchorC[i].xyz : vec3(0.0, 0.0, 1.0);
            }

            // 位置状态：0 屏内可见 · 1 屏内被挡 · 2 屏外（相机前方）· 3 相机背后
            int gtAnchorState(int i) {
                return gtAnchorValid(i) ? int(GTAnchorC[i].w + 0.5) : 0;
            }

            bool gtAnchorBehind(int i) {
                return gtAnchorState(i) == 3;
            }

            // 中心是否落在屏幕内（被墙挡住也算在内——位置仍然是准的）
            bool gtAnchorOnScreen(int i) {
                return gtAnchorState(i) <= 1;
            }

            bool gtAnchorOccluded(int i) {
                return gtAnchorState(i) == 1;
            }

            // 从屏幕中心指向锚点的单位方向，等比空间。屏内屏外都用真实投影；
            // 背后时退回视图空间的横向分量——那时「它在哪一边」仍然成立，
            // 而 gtAnchorUV 只是个推出去的占位值，拿它算方向会得到错的结果
            vec2 gtAnchorScreenDir(int i) {
                if (gtAnchorBehind(i)) {
                    vec2 d = GTAnchorC[i].xy;
                    float l = length(d);
                    return l > 1e-5 ? d / l : vec2(0.0, -1.0);
                }
                vec2 asp = vec2(OutSize.x / max(OutSize.y, 1.0), 1.0);
                vec2 d = (gtAnchorUV(i) - vec2(0.5)) * asp;
                float l = length(d);
                return l > 1e-5 ? d / l : vec2(0.0, 0.0);
            }

            // 正前方 1 → 正后方 0 的连续量。想让效果「绕到背后就减弱但不消失」时，
            // 拿它当系数比用 gtAnchorVisible 那种硬 0/1 自然得多
            float gtAnchorFront(int i) {
                return gtAnchorDir(i).z * 0.5 + 0.5;
            }

            // 形状与 gtProbe 对齐：(u, v, 半径, 强度)。两条路径的效果代码于是能长得几乎一样
            vec4 gtAnchor(int i) {
                return gtAnchorValid(i)
                    ? vec4(GTAnchorA[i].xy, GTAnchorB[i].x, GTAnchorA[i].w)
                    : vec4(0.0);
            }
            """;

    /**
     * 载体扩展：锚点的朝向、子类型与自定义值。
     *
     * <p>{@link #ANCHOR_HELPERS} 回答「那个东西在屏幕上的哪一点」，这一组回答
     * 「它朝着哪、是哪一类」。分成两组而不是并进锚点，是因为绝大多数效果是圆对称的，
     * 并进去等于让它们每个都白付 16 个 vec4。
     *
     * <p>思路来自 <a href="https://github.com/YangMao-Minister/some_of_fx">some_of_fx</a>
     * 的 {@code item_display} 载体，但只搬了朝向、类型和两个自定义值——
     * 它那套编码里的强度和调色板在 GTShaders 里由 {@code @param} 承担，
     * 理由见 {@code EmitterSlot} 的类注释。
     *
     * <p>注入 {@code gtEmitter} 会<b>连带注入 {@code gtAnchor}</b>：
     * {@code gtEmitterLocal} 要用 {@code gtAnchorDelta} 求出相对载体中心的偏移，
     * 没有位置的朝向没有意义。
     */
    private static final String EMITTER_HELPERS = """

            // ---- 载体扩展：锚点的朝向与逐载体参数 ----
            // 槽位号与 gtAnchor 完全一致：gtEmitterDir(2) 说的就是 gtAnchor(2) 那个载体
            // 朝向的屏幕方向，已等比校正。前向量几乎平行于视线时是 (0,0)——
            // 那时它在屏幕上本来就没有方向，改看 gtEmitterFacing
            vec2 gtEmitterDir(int i) {
                return gtAnchorValid(i) ? GTEmitterA[i].xy : vec2(0.0);
            }
            // 前向量与「载体→相机」的点积。1=正对着你，0=完全侧对，-1=背对
            float gtEmitterFacing(int i) {
                return gtAnchorValid(i) ? GTEmitterA[i].z : 0.0;
            }
            // 上向量投影到屏幕的角度（弧度）。矩形要摆正、法阵要跟着转，都用它
            float gtEmitterRoll(int i) {
                return gtAnchorValid(i) ? GTEmitterA[i].w : 0.0;
            }
            // 子类型 0..GT_EMITTER_TYPES-1。同一层里区分「这盏是球光那盏是聚光」
            int gtEmitterType(int i) {
                return gtAnchorValid(i) ? int(GTEmitterB[i].x + 0.5) : 0;
            }
            float gtEmitterCustom1(int i) {
                return gtAnchorValid(i) ? GTEmitterB[i].y : 0.0;
            }
            float gtEmitterCustom2(int i) {
                return gtAnchorValid(i) ? GTEmitterB[i].z : 0.0;
            }
            // 自转相位（弧度），由绑定上的转速乘编辑器时间推进。可暂停可回拨
            float gtEmitterSpin(int i) {
                return gtAnchorValid(i) ? GTEmitterB[i].w : 0.0;
            }
            // 载体自己的屏幕坐标系：把等比空间的向量转进去，x 沿朝向、y 沿上方向
            mat2 gtEmitterBasis(int i) {
                float a = gtEmitterRoll(i);
                float c = cos(a);
                float s = sin(a);
                return mat2(c, -s, s, c);
            }
            // 当前像素在载体局部坐标系里的位置。矩形面光、法阵这类要在
            // 「载体自己的正方向」上求 SDF 的，都从这里起手
            vec2 gtEmitterLocal(int i) {
                return gtEmitterBasis(i) * gtAnchorDelta(i);
            }
            // 侧对时把局部横轴压扁——一个正对相机是圆的东西，侧过去就该是椭圆。
            // 这是"用二维画三维"最省的一招：不重建世界坐标也能有立体感
            vec2 gtEmitterFlat(int i) {
                vec2 p = gtEmitterLocal(i);
                float k = max(abs(gtEmitterFacing(i)), 0.05);
                return vec2(p.x / k, p.y);
            }
            """;

    /**
     * 武器轨迹：由 mod 每帧从第一人称主手的刀刃投影而来的一条屏幕条带。
     *
     * <p>和 {@link #ANCHOR_HELPERS} 是同一条路（Java 侧写 uniform），区别只在于
     * 锚点回答「那个东西在屏幕上的哪一点」，轨迹回答「那把刀在过去零点几秒里扫过了哪一片」。
     * 一个点撑不起刀光——刀光的形状<b>就是</b>刀刃线段随时间扫出来的那条带子。
     * 数据怎么来的见 {@code TrailRuntime}，为什么不走像素编码见 {@code TrailSlot}。
     *
     * <h2>为什么在片元里做三角形判定</h2>
     *
     * <p>这条带子没有对应的几何体可画——后处理只有一个铺满屏幕的四边形，
     * 顶点着色器还是原版的 {@code core/screenquad}，我们连一个顶点都加不进去。
     * 所以只能反过来：每个像素自己去问「我落在哪一段带子里」。
     * 这正是 BladeFlash 在 {@code post/tail.fsh} 里做的事，只是它要在<b>世界空间</b>做
     * （近平面裁剪、透视校正插值一样都不能少），而我们的端点在 Java 侧就已经投影完了，
     * 剩下的是纯二维问题：点在三角形内 + 重心坐标插值。
     *
     * <p>代价是每像素要遍历 {@value TrailSlot#SLOTS} 段。所以 {@code gtTrailAt} 里那两句
     * 包围盒剔除不是可选的优化，是这个效果能不能用的分界线。
     *
     * <p>只有源码里真的出现 {@code gtTrail} 时才注入。
     */
    private static final String TRAIL_HELPERS = """

            // ---- 武器轨迹：由 GTShaders 每帧从第一人称主手的刀刃投影而来 ----
            // 没装 mod 时（例如导出成纯资源包后）全部槽位无效，gtTrailAt 恒返回未命中
            int gtTrailCount() {
                return int(GTTrailInfo.x + 0.5);
            }
            // 挥砍动画进度 0..1。想让刀光只在真挥刀时出现就乘上它
            float gtTrailSwing() {
                return GTTrailInfo.z;
            }
            // 槽位 0 永远是最新的那一帧，越往后越老
            bool gtTrailValid(int i) {
                return i >= 0 && i < GT_TRAIL_SLOTS && GTTrailB[i].z > 0.0;
            }
            // 刀根（靠手那端）的屏幕 UV
            vec2 gtTrailRoot(int i) {
                return GTTrailA[i].xy;
            }
            vec2 gtTrailTip(int i) {
                return GTTrailA[i].zw;
            }
            // 这一帧有多老，单位秒。0 是当前帧
            float gtTrailAge(int i) {
                return GTTrailB[i].x;
            }
            float gtTrailDepth(int i) {
                return GTTrailB[i].y;
            }
            // 相对上一帧的屏幕位移，单位纵向 UV。挥得越快越大
            float gtTrailSpeed(int i) {
                return GTTrailB[i].w;
            }

            // 点 p 在三角形 abc 里的重心坐标；三个分量都非负 = 在三角形内。
            // 屏幕 UV 不是等比的，但重心坐标在仿射变换下不变，所以这里不必做宽高比校正
            vec3 gtTrailBary(vec2 p, vec2 a, vec2 b, vec2 c) {
                vec2 v0 = b - a;
                vec2 v1 = c - a;
                vec2 v2 = p - a;
                float d = v0.x * v1.y - v1.x * v0.y;
                if (abs(d) < 1e-9) {
                    return vec3(-1.0);
                }
                float v = (v2.x * v1.y - v1.x * v2.y) / d;
                float w = (v0.x * v2.y - v2.x * v0.y) / d;
                return vec3(1.0 - v - w, v, w);
            }

            // 屏幕上任意一点 p 落在拖尾带的哪里：
            //   x = 命中（0/1）
            //   y = 沿刀刃的位置，0 是刀根、1 是刀尖
            //   z = 该处的年龄（秒）。条带的「宽度」方向就是时间方向，所以它同时是横向坐标
            //   w = 该处的挥动速度，用来让挥得快的那一段更亮
            // duration 之外的段不参与，遍历也就在那里停下——短拖尾比长拖尾便宜。
            // 收一个坐标而不是直接用 texCoord，是为了让外发光那类效果能往旁边探几次；
            // 探不了的话「条带外面的光晕」就只能靠假的径向渐变糊出来
            vec4 gtTrailAtUV(vec2 p, float duration) {
                for (int i = 0; i + 1 < GT_TRAIL_SLOTS; i++) {
                    if (!gtTrailValid(i) || !gtTrailValid(i + 1)) {
                        break;
                    }
                    if (gtTrailAge(i) > duration) {
                        break;
                    }
                    vec2 p00 = gtTrailRoot(i);
                    vec2 p10 = gtTrailTip(i);
                    vec2 p11 = gtTrailTip(i + 1);
                    vec2 p01 = gtTrailRoot(i + 1);

                    // 包围盒剔除。全屏绝大多数像素落在所有段之外，这一步让它们每段只付
                    // 四次 min/max 而不是两次三角形求解——不剔除的话 1080p 下能吃掉几毫秒
                    vec2 lo = min(min(p00, p10), min(p11, p01));
                    vec2 hi = max(max(p00, p10), max(p11, p01));
                    if (any(lessThan(p, lo)) || any(greaterThan(p, hi))) {
                        continue;
                    }

                    float ageA = gtTrailAge(i);
                    float ageB = gtTrailAge(i + 1);
                    float spdA = gtTrailSpeed(i);
                    float spdB = gtTrailSpeed(i + 1);

                    // 四边形拆成两个三角形。属性 (along, seg) 在四个角上分别是
                    // p00=(0,0) p10=(1,0) p11=(1,1) p01=(0,1)
                    vec3 b1 = gtTrailBary(p, p00, p10, p11);
                    if (all(greaterThanEqual(b1, vec3(0.0)))) {
                        float seg = b1.z;
                        return vec4(1.0, b1.y + b1.z, mix(ageA, ageB, seg), mix(spdA, spdB, seg));
                    }
                    vec3 b2 = gtTrailBary(p, p00, p11, p01);
                    if (all(greaterThanEqual(b2, vec3(0.0)))) {
                        float seg = b2.y + b2.z;
                        return vec4(1.0, b2.y, mix(ageA, ageB, seg), mix(spdA, spdB, seg));
                    }
                }
                return vec4(0.0);
            }

            // 当前像素版，绝大多数时候用这个
            vec4 gtTrailAt(float duration) {
                return gtTrailAtUV(texCoord, duration);
            }

            // 一行版：直接给出这个像素上的拖尾强度，沿时间线性衰减。
            // 想自己写衰减曲线、按 along 分刀根刀尖的，用 gtTrailAt
            float gtTrail(float duration) {
                vec4 t = gtTrailAt(duration);
                return t.x * (1.0 - clamp(t.z / max(duration, 1e-4), 0.0, 1.0));
            }
            """;

    /**
     * 实体轮廓层的 helper。
     *
     * <h2>这条路径为什么存在</h2>
     *
     * <p>后处理天生只看得到一张画好的图，分不出哪块像素属于哪个实体。原版其实<b>已经</b>
     * 把这份信息算出来了——发光实体会被 {@code core/rendertype_outline} 画进一张单独的
     * {@code minecraft:entity_outline} 缓冲：
     *
     * <pre>
     *   fragColor = vec4(ColorModulator.rgb * vertexColor.rgb, ColorModulator.a);
     *   // 纹理 alpha 为 0 的地方 discard，所以剪影是模型的真实形状，不是包围盒
     * </pre>
     *
     * <p>于是那张缓冲里：<b>A = 剪影覆盖</b>（1 在实体内、0 在外），
     * <b>RGB = 该实体的轮廓颜色</b>（队伍颜色 / 发光色）。后者是白送的<b>逐实体数据通道</b>——
     * 服务端用记分板队伍就能给不同实体涂不同颜色，着色器按颜色分流，
     * 一条链里红队做溶解、蓝队做护盾。
     *
     * <h2>为什么必须是 {@code entity_outline} 这条链，不能是 /posteffect</h2>
     *
     * <p>{@code GameRenderer} 加载后处理请求列表里那些链时传的是
     * {@code LevelTargetBundle.MAIN_TARGETS}，而那个集合<b>只有 {@code minecraft:main}</b>。
     * 所以 {@code /posteffect} 挂上去的效果声明 {@code entity_outline} 会被 {@code PostChain.load} 拒掉。
     *
     * <p>而 {@code LevelRenderer} 加载 {@code minecraft:entity_outline} 时传的是
     * {@code OUTLINE_TARGETS = {main, entity_outline}}——两张图都拿得到。这条链读的正是
     * {@code assets/minecraft/post_effect/entity_outline.json}，<b>资源包可以整个覆盖</b>。
     *
     * <h2>输出是怎么回到画面上的</h2>
     *
     * <p>写进 {@code entity_outline} 的东西，最后由 {@code LevelRenderer.doEntityOutline()}
     * 做 {@code blitAndBlendToTexture}——<b>alpha 混合</b>到主画面上。所以 alpha 就是「这块像素有多大程度
     * 由你说了算」：0 完全透出原画面，1 完全替换。因为同时读得到 {@code SceneSampler}，
     * 扭曲、透镜、色差这类要改动原画面的效果也做得出来（采样偏移后的 Scene，再以 alpha=1 写回）。
     */
    private static final String OUTLINE_HELPERS = """

            // ---- 实体轮廓：逐实体的剪影遮罩与颜色 ----
            // InSampler = minecraft:entity_outline（A=剪影覆盖，RGB=该实体的队伍/发光色）
            // SceneSampler = minecraft:main（渲染好的世界画面）
            // 输出的 alpha 决定这块像素有多大程度替换原画面——引擎做的是 alpha 混合
            float gtMask() {
                return texture(InSampler, texCoord).a;
            }
            float gtMaskAt(vec2 uv) {
                return texture(InSampler, uv).a;
            }
            // 该实体的轮廓颜色。服务端用记分板队伍颜色涂它，于是这是逐实体的「效果 id」通道
            vec3 gtMaskColor() {
                return texture(InSampler, texCoord).rgb;
            }
            vec4 gtOutline() {
                return texture(InSampler, texCoord);
            }
            vec3 gtScene() {
                return texture(SceneSampler, texCoord).rgb;
            }
            vec3 gtSceneAt(vec2 uv) {
                return texture(SceneSampler, uv).rgb;
            }
            // 剪影边缘强度：四邻域的 alpha 差分。原版的描边就是这么来的（post/entity_sobel）
            float gtMaskEdge() {
                vec2 px = 1.0 / max(InSize, vec2(1.0));
                float c = gtMask();
                float d = abs(c - gtMaskAt(texCoord - vec2(px.x, 0.0)))
                        + abs(c - gtMaskAt(texCoord + vec2(px.x, 0.0)))
                        + abs(c - gtMaskAt(texCoord - vec2(0.0, px.y)))
                        + abs(c - gtMaskAt(texCoord + vec2(0.0, px.y)));
                return clamp(d, 0.0, 1.0);
            }
            // 到剪影边缘的近似距离（向内为正），做内发光、溶解推进这类沿边缘走的效果用它
            float gtMaskInset(float radius) {
                vec2 px = 1.0 / max(InSize, vec2(1.0)) * max(radius, 1.0);
                float s = gtMaskAt(texCoord - vec2(px.x, 0.0)) + gtMaskAt(texCoord + vec2(px.x, 0.0))
                        + gtMaskAt(texCoord - vec2(0.0, px.y)) + gtMaskAt(texCoord + vec2(0.0, px.y));
                return gtMask() * (s * 0.25);
            }
            // 颜色分流：判断这个实体的轮廓色是不是某个目标色（用于按队伍颜色区分效果）
            bool gtMaskIs(vec3 want, float tolerance) {
                vec3 c = gtMaskColor();
                return gtMask() > 0.5 && all(lessThan(abs(c - want), vec3(tolerance)));
            }
            """;

    /**
     * 生成产物里的三条分界线。
     *
     * <p>它们不只是注释，还是<b>可逆性的凭据</b>：{@link #extractAuthorBody} 靠这三行把作者源码
     * 从完整着色器里原样切回来。于是导出的 {@code .fsh} 同时是「Minecraft 能加载的成品」和
     * 「编辑器能拖回来继续改的文档」，作者不必维护源码版和发布版两份东西。
     *
     * <p>所以这几行的文本一旦改动，旧产物就再也拖不回来了——要改就得同时留一条兼容分支。
     */
    public static final String HEADER_BEGIN = "// ==== 以下由 GTShaders 自动生成，改动会在下次生成时被覆盖 ====";
    public static final String HEADER_END = "// ==== 自动生成部分结束 ====";
    public static final String BLEND_BEGIN = "// ==== GTShaders 图层混合（由图层设置生成） ====";

    /** 作者的入口函数被重命名成的名字。 */
    private static final String AUTHOR_MAIN = "gtAuthorMain";
    /** 只匹配同一行内的 {@code void main()}，保证重命名不改变行数。 */
    private static final Pattern AUTHOR_MAIN_PATTERN =
            Pattern.compile("\\bvoid[ \\t]+main[ \\t]*\\([ \\t]*\\)");
    /** {@code // @texture name=IconSampler path=... width=64 height=64} —— 额外贴图输入。 */
    private static final Pattern TEXTURE_ANNOTATION =
            Pattern.compile("^\\s*//\\s*@texture\\s+(.*)$", Pattern.CASE_INSENSITIVE);
    /** 与 {@link ParamScanner} 相同的 key=value 拆法，这里只服务于 @texture。 */
    private static final Pattern KV = Pattern.compile(
            "([A-Za-z_][A-Za-z0-9_]*)\\s*=\\s*(?:\"([^\"]*)\"|((?:(?!\\s+[A-Za-z_][A-Za-z0-9_]*\\s*=).)*))");

    /**
     * @param source          完整可编译的片段着色器源码
     * @param headerLineCount 生成头部占用的行数；驱动报的行号减去它就是作者源码的行号
     * @param orderedParams   实际写进 uniform 块的参数，顺序必须与 JSON 中一致
     * @param wrapped         是否成功套上了混合外壳；false 表示没找到作者的 main
     * @param usesAnchors     是否注入了世界锚点。uniform 块的字节布局因此不同，
     *                        JSON 生成与每帧写入都必须问它，三处对不上就会整块错位
     * @param outline         是否是实体轮廓层。它比后处理层多一个输入采样器，
     *                        JSON 里的 inputs 与 SamplerInfo 的 vec2 数量都因此不同
     * @param usesTrails      是否注入了武器轨迹。和 {@code usesAnchors} 一样改变 uniform 块的
     *                        字节布局，同样是 GLSL 声明、JSON 生成、每帧写入三处必须一致
     * @param usesEmitters    是否注入了载体扩展。为真时 {@code usesAnchors} <b>必然</b>也为真——
     *                        载体扩展是挂在锚点槽位上的，没有位置的朝向没有意义
     * @param usesDepth       是否要了场景深度。为真时 JSON 多一个指向 {@code minecraft:main} 的
     *                        {@code use_depth_buffer} 输入，{@code SamplerInfo} 也多一个 vec2——
     *                        两处必须同时改，只改一处整个块就错位
     * @param usesCamera      是否注入了世界相机。为真时 {@code usesDepth} <b>必然</b>也为真——
     *                        每一个相机 helper 都要先读深度。它同样改变 uniform 块的字节布局，
     *                        GLSL 声明、JSON 生成、每帧写入三处必须一致
     */
    public record Output(String source, int headerLineCount, List<ShaderParam> orderedParams,
                         boolean wrapped, boolean usesAnchors, boolean outline,
                         List<ShaderTexture> textures, boolean usesTrails, boolean usesEmitters,
                         boolean usesDepth, boolean usesCamera) {
    }

    private GlslCodegen() {
    }

    /**
     * 按 std140 对齐降序排布参数：4 分量对齐的在前，2 分量的居中，标量在后。
     * 同一对齐级别内保持作者声明顺序，这样调整源码里的顺序仍然能反映到面板上。
     */
    public static List<ShaderParam> orderParams(List<ShaderParam> params) {
        List<ShaderParam> ordered = new ArrayList<>(params);
        ordered.sort(Comparator.comparingInt((ShaderParam p) -> -p.type().std140AlignmentUnits()));
        return ordered;
    }

    public static Output generate(GtProfile profile, String authorBody,
                                  List<ShaderParam> params, BlendMode blend) {
        return build(profile, authorBody, params, blend, false, null);
    }

    /**
     * 调试插桩往生成器里挂的钩子（见 {@link DebugInstrument}）。只有编辑器的调试视图会用，
     * 导出物里永远没有它。
     *
     * @param header   追加在生成头部末尾的代码：插桩用的全局变量与辅助函数
     * @param mainTail 作者 main 跑完之后执行的代码，<b>取代</b>正常的混合那一句。
     *                 可以读 {@code gtBase}（原画面）、{@code gtInside}（是否在取景框内）与
     *                 {@code fragColor}（作者写出的结果），也可以调 {@code gtBlend}
     * @param params   插桩要的隐藏参数，排进 uniform 块的方式与作者参数完全相同，
     *                 所以每帧照常从这些对象上读值——调试视图靠改它们切换输出
     */
    public record Hook(String header, String mainTail, List<ShaderParam> params) {
    }

    /** 带调试钩子的版本。钩子为 null 时与 {@link #generate} 逐字节相同。 */
    public static Output generate(GtProfile profile, String authorBody,
                                  List<ShaderParam> params, BlendMode blend,
                                  @org.jspecify.annotations.Nullable Hook hook) {
        return build(profile, authorBody, params, blend, false, hook);
    }

    /**
     * 生成<b>实体轮廓层</b>的着色器：覆盖原版 {@code minecraft:entity_outline} 那条链。
     *
     * <p>和后处理层的三处差别，见 {@link #OUTLINE_HELPERS}：多一个 {@code SceneSampler} 输入、
     * {@code SamplerInfo} 多一个 {@code vec2}、输出的 alpha 有语义（引擎按它做混合）。
     *
     * <p><b>没有混合模式</b>：合成方式由引擎的 {@code blitAndBlendToTexture} 定死了，
     * 是 alpha 混合。再给一个「正片叠底/滤色」的选项只会让人以为它有用。
     * 图层强度仍然生效——它乘在 alpha 上，也就是整体淡出。
     */
    public static Output generateOutline(GtProfile profile, String authorBody,
                                         List<ShaderParam> params) {
        return build(profile, authorBody, params, BlendMode.NORMAL, true, null);
    }

    private static Output build(GtProfile profile, String authorBody,
                                List<ShaderParam> params, BlendMode blend, boolean outline,
                                @org.jspecify.annotations.Nullable Hook hook) {
        List<ShaderParam> all = new ArrayList<>(params);
        if (hook != null && !outline) {
            all.addAll(hook.params());
        }
        List<ShaderParam> ordered = orderParams(all);
        boolean fxEmitters = usesEmitter(authorBody);
        // 载体扩展蕴含锚点：gtEmitterLocal 是在 gtAnchorDelta 之上算的
        boolean anchors = usesAnchor(authorBody) || fxEmitters;
        boolean trails = usesTrail(authorBody);
        // 轮廓层暂不支持深度：它已经有遮罩 + 场景两个输入，再插一个会打乱
        // SamplerInfo 的顺序，而逐实体描边本来就有比深度更准的信息
        boolean camera = !outline && usesCamera(authorBody);
        // 相机蕴含深度：gtViewPos 建立在 gtLinearDepth 之上，而后者要读深度图
        boolean depth = !outline && (usesDepth(authorBody) || camera);
        List<ShaderTexture> textures = parseTextures(authorBody);
        StringBuilder sb = new StringBuilder(3072);

        sb.append("#version ").append(profile.glslVersion()).append('\n');
        // layout(location) 用在 in/out 上，在 GLSL 330 里要靠这个扩展才合法。
        // 26.3 的每一个原版 shader 都带着它——注意 Mojang 并没有升到 #version 450。
        sb.append("#extension GL_ARB_separate_shader_objects : require\n");
        sb.append("\n");
        sb.append(HEADER_BEGIN).append('\n');

        // Globals：内联原版的真实布局（来源 assets/minecraft/shaders/include/globals.glsl）。
        // 用不上的成员也必须原样保留——std140 块的成员顺序和数量决定内存布局，删一个就全错位。
        // 成员顺序就是 std140 内存布局，逐字对齐原版；由 profile 提供，下个大版本改了只动那一处。
        sb.append("layout(std140) uniform Globals {\n");
        for (String member : profile.globalsMembers()) {
            sb.append("    ").append(member).append(";\n");
        }
        sb.append("};\n");
        sb.append("\n");

        sb.append("uniform sampler2D InSampler;\n");
        if (depth) {
            // 独立于 InSampler 的第二个输入，两者指向同一个 target 但取的纹理不同，
            // 见 DEPTH_SAMPLER_NAME 的注释
            sb.append("uniform sampler2D ").append(DEPTH_SAMPLER_NAME).append("Sampler;\n");
        }
        if (outline) {
            // 轮廓层比后处理多一个输入：InSampler 是逐实体的剪影遮罩，
            // SceneSampler 才是渲染好的世界画面。能同时拿到这两张图，是这条路径
            // 比「/posteffect 挂的链」强的全部原因——后者只拿得到 minecraft:main。
            sb.append("uniform sampler2D SceneSampler;\n");
        }
        // 额外贴图输入：由 @texture 注解声明，JSON 里会生成 TextureInput。
        // 目前先支持普通后处理层；轮廓层如果以后要加，再扩展 SamplerInfo 顺序。
        for (ShaderTexture tex : textures) {
            if (!outline) {
                // 原版 PostPass 绑定贴图时会把 sampler_name 拼上 "Sampler" 再找 uniform，
                // 例如 sampler_name=Icon → 绑定 IconSampler。这里必须用同名，否则贴图不会绑定，
                // 采样器会回退到主画面纹理（症状就是“图标区域映射主界面”）。
                sb.append("uniform sampler2D ").append(tex.samplerName()).append("Sampler;\n");
            }
        }
        sb.append("\n");
        sb.append("layout(std140) uniform SamplerInfo {\n");
        sb.append("    vec2 OutSize;\n");
        sb.append("    vec2 InSize;\n");
        if (depth) {
            // 顺序必须与 PostEffectJsonBuilder 里 inputs 的声明顺序一致：In 之后、贴图之前
            sb.append("    vec2 ").append(DEPTH_SAMPLER_NAME).append("Size;\n");
        }
        for (ShaderTexture tex : textures) {
            if (!outline) {
                sb.append("    vec2 ").append(tex.samplerName()).append("SamplerSize;\n");
            }
        }
        if (outline) {
            // 顺序不是随便排的：原版 PostPass 先写一个 OutSize，再<b>按 JSON 里 inputs 的声明顺序</b>
            // 每个输入写一个 vec2（PostPass 里那句 `for (InputTexture i : inputTextures) putVec2(...)`）。
            // 所以这里第三个 vec2 必须对应 JSON 里第二个 input，即 Scene。
            sb.append("    vec2 SceneSize;\n");
        }
        sb.append("};\n");
        sb.append("\n");

        sb.append("layout(std140) uniform ").append(PARAM_BLOCK).append(" {\n");
        // 三个系统 vec4 永远排在最前：对齐最大，放头部不会给后面制造空洞。
        sb.append("    vec4 ").append(SYSTEM_UNIFORM).append(";\n");
        sb.append("    vec4 ").append(LAYER_UNIFORM).append(";\n");
        sb.append("    vec4 ").append(VIEWPORT_UNIFORM).append(";\n");
        if (anchors) {
            // 锚点排在系统量之后、用户参数之前。位置是固定的，因为 Java 侧每帧按同一顺序写字节。
            //
            // 这里写成数组、而 post effect JSON 里铺成 17 个平铺的 vec4，两者是<b>同一份字节</b>：
            // std140 下 vec4 数组的跨距就是 16 字节，和连续排列的独立 vec4 完全一致。
            // 之所以能这么做，是因为原版 PostPass 只按 JSON 里的 UniformValue 列表算 std140 尺寸、
            // 再把字节灌进一个 UBO，<b>它从不反射 GLSL 块的成员</b>——GLSL 侧只要块名和总尺寸对得上。
            // 于是 GLSL 这边可以用最好写的形式（数组 + 动态下标），JSON 那边用它唯一支持的形式。
            sb.append("    vec4 ").append(ANCHOR_INFO_UNIFORM).append(";\n");
            sb.append("    vec4 ").append(ANCHOR_A_UNIFORM)
                    .append('[').append(AnchorSlot.SLOTS).append("];\n");
            sb.append("    vec4 ").append(ANCHOR_B_UNIFORM)
                    .append('[').append(AnchorSlot.SLOTS).append("];\n");
            sb.append("    vec4 ").append(ANCHOR_C_UNIFORM)
                    .append('[').append(AnchorSlot.SLOTS).append("];\n");
        }

        if (fxEmitters) {
            // 紧跟在锚点后面：两者槽位一一对应，摆在一起时人工核对 JSON 与 GLSL 最省事
            sb.append("    vec4 ").append(EMITTER_A_UNIFORM)
                    .append('[').append(AnchorSlot.SLOTS).append("];\n");
            sb.append("    vec4 ").append(EMITTER_B_UNIFORM)
                    .append('[').append(AnchorSlot.SLOTS).append("];\n");
        }
        if (trails) {
            // 排在锚点之后、用户参数之前。位置固定，理由和锚点那段一样：
            // Java 侧每帧按同一顺序写字节，谁在前谁在后决定的是内存偏移而不只是可读性
            sb.append("    vec4 ").append(TRAIL_INFO_UNIFORM).append(";\n");
            sb.append("    vec4 ").append(TRAIL_A_UNIFORM)
                    .append('[').append(TrailSlot.SLOTS).append("];\n");
            sb.append("    vec4 ").append(TRAIL_B_UNIFORM)
                    .append('[').append(TrailSlot.SLOTS).append("];\n");
        }
        if (camera) {
            // 排在全部系统量之后、用户参数之前。四个独立 vec4 而不是一个 mat4：
            // std140 里矩阵的对齐与跨距是另一套规则，而 post effect JSON 只认 vec4，
            // 铺成四个 vec4 两边的字节天然一致
            sb.append("    vec4 ").append(CAMERA_PROJ_UNIFORM).append(";\n");
            sb.append("    vec4 ").append(CAMERA_RIGHT_UNIFORM).append(";\n");
            sb.append("    vec4 ").append(CAMERA_UP_UNIFORM).append(";\n");
            sb.append("    vec4 ").append(CAMERA_BACK_UNIFORM).append(";\n");
        }
        for (ShaderParam p : ordered) {
            sb.append("    ").append(p.type().glslType()).append(' ').append(p.name()).append(";\n");
        }
        sb.append("};\n");
        sb.append("\n");

        // 顶点输出与片段输入只按 location 匹配，名字被忽略，必须显式写。
        // location 0 不是随便选的：原版 core/screenquad.vsh 的唯一输出就写在 location 0，
        // 我们这一层是接在它后面的，编号对不上就拿不到 UV。
        sb.append("layout(location = 0) in vec2 texCoord;\n");
        sb.append("layout(location = 0) out vec4 fragColor;\n");
        sb.append("\n");

        // 给作者的便捷别名。GTTime = 原版游戏时钟（秒）+ 偏移：资源包里偏移恒为 0，
        // 编辑器每帧把偏移写成「编辑器时钟 − 游戏时钟」来实现暂停与回拨——两边共用同一份源码。
        // 另外铺一层 Shadertoy 风格别名，让从 Shadertoy 抄来的代码少改几处。
        sb.append("#define GTTime (GameTime * ").append(GAME_DAY_SECONDS)
                .append(" + ").append(SYSTEM_UNIFORM).append(".x)\n");
        sb.append("#define GTDeltaTime (").append(SYSTEM_UNIFORM).append(".y)\n");
        sb.append("#define GTFrame (").append(SYSTEM_UNIFORM).append(".z)\n");
        sb.append("#define GTPlaying (").append(SYSTEM_UNIFORM).append(".w)\n");
        sb.append("#define GTStrength (").append(LAYER_UNIFORM).append(".x)\n");
        sb.append("#define GTLayerIndex (").append(LAYER_UNIFORM).append(".y)\n");
        sb.append("#define GTLayerCount (").append(LAYER_UNIFORM).append(".z)\n");
        // 取景框内的归一化坐标：作者想让效果随框走（而不是随屏幕走）时用它
        sb.append("#define GTViewportUV ((texCoord - ").append(VIEWPORT_UNIFORM)
                .append(".xy) / max(").append(VIEWPORT_UNIFORM).append(".zw - ")
                .append(VIEWPORT_UNIFORM).append(".xy, vec2(1e-5)))\n");
        sb.append("#define iTime GTTime\n");
        sb.append("#define iTimeDelta (").append(SYSTEM_UNIFORM).append(".y)\n");
        sb.append("#define iResolution vec3(OutSize, 1.0)\n");
        if (outline) {
            // 轮廓层里「那张图」是世界画面而不是遮罩，所以 Shadertoy 别名指向 Scene，
            // 遮罩另给一个 iChannel1——从 Shadertoy 抄来的代码通常是拿 iChannel0 当画面用的
            sb.append("#define iChannel0 SceneSampler\n");
            sb.append("#define iChannel1 InSampler\n");
            sb.append(OUTLINE_HELPERS);
        } else {
            sb.append("#define iChannel0 InSampler\n");
        }
        // 作者源码里继续写 @texture 的 sampler 短名（如 DefaultIcon），
        // 这里映射到实际 uniform（DefaultIconSampler）。
        for (ShaderTexture tex : textures) {
            if (!outline) {
                sb.append("#define ").append(tex.samplerName()).append(' ')
                        .append(tex.samplerName()).append("Sampler\n");
            }
        }
        if (anchors) {
            sb.append("#define GT_ANCHOR_SLOTS ").append(AnchorSlot.SLOTS).append('\n');
            sb.append(ANCHOR_HELPERS);
        }
        if (fxEmitters) {
            sb.append("#define GT_EMITTER_TYPES ").append(EmitterSlot.MAX_TYPES).append('\n');
            sb.append(EMITTER_HELPERS);
        }
        if (trails) {
            sb.append("#define GT_TRAIL_SLOTS ").append(TrailSlot.SLOTS).append('\n');
            sb.append(TRAIL_HELPERS);
        }
        if (depth) {
            sb.append(DEPTH_HELPERS);
        }
        if (camera) {
            sb.append(CAMERA_HELPERS);
        }
        if (usesProbe(authorBody)) {
            sb.append(PROBE_HELPERS);
        }
        if (usesBitpack(authorBody)) {
            sb.append(BITPACK_HELPERS);
        }
        appendDrivers(sb, ordered);
        if (hook != null && !outline) {
            sb.append(hook.header());
            if (!hook.header().endsWith("\n")) {
                sb.append('\n');
            }
        }
        sb.append(HEADER_END).append('\n');

        int headerLines = countLines(sb);

        // 重命名作者的 main。只替换同一行内的写法，所以行数不变，错误行号依然准确。
        Matcher m = AUTHOR_MAIN_PATTERN.matcher(authorBody);
        boolean wrapped = m.find();
        String body = wrapped
                ? new StringBuilder(authorBody).replace(m.start(), m.end(), "void " + AUTHOR_MAIN + "()").toString()
                : authorBody;
        sb.append(body);

        if (wrapped && outline) {
            if (!body.endsWith("\n")) {
                sb.append('\n');
            }
            sb.append('\n').append(BLEND_BEGIN).append('\n');
            sb.append("void main() {\n");
            // 预置成原版轮廓数据：作者若在某些分支里没写 fragColor，
            // 画面上留下的就是原版那份轮廓，而不是未初始化的随机内容
            sb.append("    fragColor = texture(InSampler, texCoord);\n");
            sb.append("    if (texCoord.x < ").append(VIEWPORT_UNIFORM).append(".x")
                    .append(" || texCoord.x > ").append(VIEWPORT_UNIFORM).append(".z")
                    .append(" || texCoord.y < ").append(VIEWPORT_UNIFORM).append(".y")
                    .append(" || texCoord.y > ").append(VIEWPORT_UNIFORM).append(".w) {\n");
            sb.append("        return;\n");
            sb.append("    }\n");
            sb.append("    ").append(AUTHOR_MAIN).append("();\n");
            // 强度乘在 alpha 上。引擎做的是 alpha 混合，压 alpha 就是整体淡出——
            // 这里刻意不碰 rgb：把 rgb 也乘一遍会让效果在淡出时先变黑再消失
            sb.append("    fragColor.a = clamp(fragColor.a * GTStrength, 0.0, 1.0);\n");
            sb.append("}\n");
        } else if (wrapped) {
            // 尾部的混合外壳。放在作者代码之后，所以不影响作者行号。
            if (!body.endsWith("\n")) {
                sb.append('\n');
            }
            sb.append('\n').append(BLEND_BEGIN).append('\n');
            sb.append("vec3 gtBlend(vec3 base, vec3 result) {\n");
            sb.append("    vec3 mixed = ").append(blend.expression()).append(";\n");
            sb.append("    return mix(base, clamp(mixed, 0.0, 1.0), GTStrength);\n");
            sb.append("}\n");
            sb.append("\n");
            if (hook != null) {
                // 调试视图：取景框外也要走到尾部——那里要输出「这个像素没执行到」，
                // 而不是原画面；否则框外的读数看起来就像探针坏了
                sb.append("void main() {\n");
                sb.append("    vec4 gtBase = texture(InSampler, texCoord);\n");
                sb.append("    fragColor = gtBase;\n");
                sb.append("    bool gtInside = !(texCoord.x < ").append(VIEWPORT_UNIFORM).append(".x")
                        .append(" || texCoord.x > ").append(VIEWPORT_UNIFORM).append(".z")
                        .append(" || texCoord.y < ").append(VIEWPORT_UNIFORM).append(".y")
                        .append(" || texCoord.y > ").append(VIEWPORT_UNIFORM).append(".w);\n");
                sb.append("    if (gtInside) {\n");
                sb.append("        ").append(AUTHOR_MAIN).append("();\n");
                sb.append("    }\n");
                sb.append(hook.mainTail());
                if (!hook.mainTail().endsWith("\n")) {
                    sb.append('\n');
                }
                sb.append("}\n");
                return new Output(sb.toString(), headerLines, ordered, wrapped, anchors, outline,
                        textures, trails, fxEmitters, depth, camera);
            }
            sb.append("void main() {\n");
            sb.append("    vec4 gtBase = texture(InSampler, texCoord);\n");
            // 先把 fragColor 预置成原画面：作者若在某些分支里没写 fragColor，
            // 画面会保持原样，而不是显示成未初始化的随机内容。
            sb.append("    fragColor = gtBase;\n");
            // 取景框之外原样输出。这让画布上的框成为真正的「效果作用区域」，
            // 拖动它就能得到一条实时的 before/after 分界线。
            sb.append("    if (texCoord.x < ").append(VIEWPORT_UNIFORM).append(".x")
                    .append(" || texCoord.x > ").append(VIEWPORT_UNIFORM).append(".z")
                    .append(" || texCoord.y < ").append(VIEWPORT_UNIFORM).append(".y")
                    .append(" || texCoord.y > ").append(VIEWPORT_UNIFORM).append(".w) {\n");
            sb.append("        return;\n");
            sb.append("    }\n");
            sb.append("    ").append(AUTHOR_MAIN).append("();\n");
            sb.append("    fragColor = vec4(gtBlend(gtBase.rgb, fragColor.rgb), 1.0);\n");
            sb.append("}\n");
        }

        return new Output(sb.toString(), headerLines, ordered, wrapped, anchors, outline,
                textures, trails, fxEmitters, depth, camera);

    }

    /** 解析作者源码里的 {@code @texture} 注解，生成额外的贴图输入。 */
    public static List<ShaderTexture> parseTextures(String authorBody) {
        List<ShaderTexture> out = new ArrayList<>();
        if (authorBody == null) {
            return out;
        }
        String[] lines = authorBody.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        for (String line : lines) {
            Matcher m = TEXTURE_ANNOTATION.matcher(line);
            if (!m.matches()) {
                continue;
            }
            Map<String, String> kv = new java.util.LinkedHashMap<>();
            Matcher km = KV.matcher(m.group(1));
            while (km.find()) {
                String value = km.group(2) != null ? km.group(2) : km.group(3);
                if (value != null) {
                    kv.put(km.group(1).toLowerCase(java.util.Locale.ROOT), value.trim());
                }
            }
            String name = kv.get("name");
            String location = kv.getOrDefault("path", kv.get("location"));
            if (name == null || location == null) {
                continue;
            }
            int width = parseInt(kv.get("width"), 1);
            int height = parseInt(kv.get("height"), 1);
            boolean bilinear = "1".equals(kv.get("bilinear")) || "true".equalsIgnoreCase(kv.get("bilinear"));
            String display = kv.getOrDefault("zh_cn", kv.getOrDefault("en_us", name));
            out.add(new ShaderTexture(name, location, width, height, bilinear, display));
        }
        return out;
    }

    private static int parseInt(String raw, int fallback) {
        if (raw == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /**
     * 从一份<b>由本工程生成的</b>完整着色器里切回作者源码。
     *
     * <p>这是 {@link #generate} 的逆运算，靠 {@link #HEADER_END} 与 {@link #BLEND_BEGIN}
     * 两条分界线定位——不做任何语法分析，所以作者写了什么都不会影响切割的准确性。
     *
     * <p>切出来的东西是<b>逐字节相同</b>的：{@code @param} 注解留在正文里，参数面板于是能
     * 完整恢复；唯一的改写是把 {@code gtAuthorMain} 改回 {@code main}。
     *
     * @return 作者源码；不是本工程生成的产物时返回 null
     */
    public static @org.jspecify.annotations.Nullable String extractAuthorBody(String generated) {
        if (generated == null) {
            return null;
        }
        int headerEnd = generated.indexOf(HEADER_END);
        if (headerEnd < 0) {
            return null;
        }
        int bodyStart = generated.indexOf('\n', headerEnd);
        if (bodyStart < 0) {
            return null;
        }
        bodyStart++;

        // 混合外壳是我们加的，切掉；没套上外壳（作者没写 main）时正文一直到文件末尾
        int bodyEnd = generated.indexOf(BLEND_BEGIN, bodyStart);
        String body = bodyEnd < 0 ? generated.substring(bodyStart) : generated.substring(bodyStart, bodyEnd);

        // 生成时把作者的 main 改名成了 gtAuthorMain，这里改回去
        body = body.replace("void " + AUTHOR_MAIN + "()", "void main()");
        return body.stripTrailing() + "\n";
    }

    /**
     * 源码是否用到了数据探针。
     *
     * <p>纯文本判断，故意不做词法分析：误判的代价只是多几行用不上的函数（驱动会直接优化掉），
     * 而漏判会让作者收到一句"未定义的 gtProbe"，那才是真的挡路。
     */
    private static boolean usesProbe(String authorBody) {
        return authorBody != null && authorBody.contains("gtProbe");
    }

    /**
     * 源码是否用到了位打包。
     *
     * <p>判据要同时认 {@code gtPack} 和 {@code gtUnpack}：解包端（读数据表）比打包端常见得多，
     * 只认前者的话，一个只写 {@code gtUnpackFloatAt} 的效果会收到「未定义」。
     */
    private static boolean usesBitpack(String authorBody) {
        return authorBody != null
                && (authorBody.contains("gtPack") || authorBody.contains("gtUnpack"));
    }

    /**
     * 源码是否要了场景深度。
     *
     * <p>判据同样是纯文本。代价比锚点那些低（一个采样器 + 一个 vec2），但<b>不能默认全开</b>：
     * 多一个输入就多一次纹理绑定，而且 {@code SamplerInfo} 的成员数跟着变，
     * 一旦与 JSON 那边对不上，整个块错位——那是不报错、只让画面莫名其妙的一类问题。
     */
    private static boolean usesDepth(String authorBody) {
        return authorBody != null && authorBody.contains("gtDepth");
    }

    /**
     * 相机 helper 的名字。
     *
     * <p>判据只能逐个认名字：这一组没有共同前缀。硬套一个 {@code gtCamXxx} 前缀会把
     * {@code gtWorldPos} 这种一眼就懂的名字弄难读，而这些函数正是给人手写着色器用的。
     *
     * <p>{@code gtCameraPos} 单独出现也算数——它虽然只读原版 {@code Globals}，
     * 但声明落在同一段 helper 里，漏判就是一句「未定义的 gtCameraPos」。
     */
    private static final String[] CAMERA_TOKENS = {
            "gtLinearDepth", "gtViewPos", "gtViewDir", "gtViewToWorld", "gtViewNormal",
            "gtWorldPos", "gtWorldDir", "gtWorldNormal", "gtCameraPos", "gtCameraLive",
            "gtDistance", "gtTanHalfFov", "gtAspect"};

    /**
     * 源码是否用到了世界相机。
     *
     * <p>纯文本判断，理由同 {@link #usesProbe}。代价是 4 个 vec4（一帧 64 字节）外加
     * 深度输入那一份——比锚点便宜得多，但仍然不默认全开：多一个输入就多一次纹理绑定，
     * 而且 {@code SamplerInfo} 的成员数跟着变，与 JSON 对不上就整块错位。
     */
    private static boolean usesCamera(String authorBody) {
        if (authorBody == null) {
            return false;
        }
        for (String token : CAMERA_TOKENS) {
            if (authorBody.contains(token)) {
                return true;
            }
        }
        return false;
    }


    /**
     * 源码是否用到了世界锚点。
     *
     * <p>和 {@link #usesProbe} 一样是纯文本判断。这里的取舍还更明显一点：多判一次只是白搭
     * 17 个 vec4 的 uniform 空间（一帧 272 字节），少判一次作者就会撞上「未定义的 gtAnchorUV」。
     */
    private static boolean usesAnchor(String authorBody) {
        return authorBody != null && authorBody.contains("gtAnchor");
    }

    /**
     * 源码是否用到了武器轨迹。
     *
     * <p>判据同样是纯文本，但代价比锚点大一档：多判一次要白搭 49 个 vec4（一帧 784 字节），
     * 而且会让 {@code TrailRuntime} 每帧真的去采样、重采样、投影。所以别把
     * {@code gtTrail} 写进注释里当说明——那会让整条采集链为一行注释跑起来。
     */
    private static boolean usesTrail(String authorBody) {
        return authorBody != null && authorBody.contains("gtTrail");
    }

    /**
     * 源码是否用到了载体扩展。
     *
     * <p>命中时会连带把锚点也注入进来，所以代价是 {@code 17 + 16 = 33} 个 vec4。
     * 判据同样是纯文本——写 {@code gtEmitter} 进注释一样会触发。
     */
    private static boolean usesEmitter(String authorBody) {
        return authorBody != null && authorBody.contains("gtEmitter");
    }


    /**
     * 全部按需注入的 helper 源码，给编辑器的补全与悬停说明用。
     *
     * <p>说明文字直接取自每个函数上方的注释（见 {@code GlslSymbols}），不另写一份：
     * helper 改了、注释跟着改，编辑器里的说明就不会过时。
     */
    public static List<String> helperSources() {
        return List.of(ANCHOR_HELPERS, EMITTER_HELPERS, TRAIL_HELPERS, DEPTH_HELPERS, CAMERA_HELPERS,
                PROBE_HELPERS, BITPACK_HELPERS, OUTLINE_HELPERS, DRIVER_HELPERS);
    }

    /**
     * 参数驱动器的求值函数。公式与 {@link mc.GTedd.cn.gtshaders.core.ParamDriver#evaluate} 逐项一致，
     * 波形编号就是 {@code ParamDriver.Wave} 的 ordinal。
     */
    private static final String DRIVER_HELPERS = """

            // ---- 参数驱动器：带 drive= 注解的参数由时间函数算出 ----
            // 时间源是 GTTime：编辑器里跟时间轴走，资源包里是原版时钟（1200 秒回绕）
            float gtDrive(int wave, float from, float to, float period, float phase) {
                float t = fract(GTTime / max(period, 0.001) + phase);
                float w;
                if (wave == 1) {
                    w = 1.0 - abs(t * 2.0 - 1.0);
                } else if (wave == 2) {
                    w = step(0.5, t);
                } else if (wave == 3) {
                    w = t;
                } else {
                    w = 0.5 - 0.5 * cos(t * 6.28318530718);
                }
                return mix(from, to, w);
            }
            """;

    /**
     * 给带驱动器的参数各写一行 {@code #define Name gtDrive(...)}。
     *
     * <p>uniform 块里的成员照旧保留：块布局、JSON 条目、每帧写入三处都不必知道驱动器的存在，
     * 那个值只是不再被读到。宏写在块声明<b>之后</b>，所以块里那一行不会被替换。
     * 常数直接烤进源码，于是纯资源包里驱动器照样在动——这正是它不能做成「mod 每帧写 uniform」的原因。
     */
    private static void appendDrivers(StringBuilder sb, List<ShaderParam> params) {
        boolean any = false;
        for (ShaderParam p : params) {
            if (p.driver() == null || p.type() != mc.GTedd.cn.gtshaders.core.ParamType.FLOAT) {
                continue;
            }
            if (!any) {
                sb.append(DRIVER_HELPERS);
                any = true;
            }
            mc.GTedd.cn.gtshaders.core.ParamDriver d = p.driver();
            sb.append("#define ").append(p.name()).append(" gtDrive(").append(d.wave().index())
                    .append(", ").append(glslFloat(d.from()))
                    .append(", ").append(glslFloat(d.to()))
                    .append(", ").append(glslFloat(d.period()))
                    .append(", ").append(glslFloat(d.phase())).append(")\n");
        }
    }

    /** GLSL 的 float 字面量必须带小数点：{@code 2} 是 int，传给 float 形参在 330 里会报错。 */
    static String glslFloat(float v) {
        if (!Float.isFinite(v)) {
            return "0.0";
        }
        String s = Float.toString(v);
        if (s.contains("E")) {
            s = new java.math.BigDecimal(s).toPlainString();
        }
        return s.contains(".") ? s : s + ".0";
    }

    /** 把驱动报的行号换算回作者源码的行号。越界时返回 -1，调用方据此决定是否显示行号。 */
    public static int toAuthorLine(int compilerLine, int headerLineCount) {
        int line = compilerLine - headerLineCount;
        return line >= 1 ? line : -1;
    }

    private static int countLines(CharSequence cs) {
        int n = 0;
        for (int i = 0; i < cs.length(); i++) {
            if (cs.charAt(i) == '\n') {
                n++;
            }
        }
        return n;
    }

    /**
     * 新建工程时的起手源码：一个<b>原样输出</b>的空壳，外加把可用内置量列出来的注释。
     *
     * <p>刻意不预置任何视觉效果。默认就带效果意味着玩家一打开编辑器画面就变了，
     * 而他并没有要求过；空壳则保证「默认无效果」，同时给了一个能立刻开始敲的落点。
     * 想要现成的效果，走菜单里的示例模板。
     */
    public static String defaultAuthorBody() {
        return """
                // 在这里编写你的后处理效果，写完在图层上勾选「启用」才会生效。
                // Write your post-processing effect here, then tick "Enabled" on the layer.
                //
                // 内置量 / built-ins:
                //   InSampler  texCoord  gl_FragCoord  OutSize  InSize
                //   GTTime  GTDeltaTime  GTStrength  GTViewportUV
                //   iTime  iResolution  iChannel0   (Shadertoy 风格别名)
                //
                // 声明一个可调参数 / declare a tweakable parameter:
                //   // @param name=Amount type=float min=0 max=1 default=0.5 zh_cn=强度 en_us=Amount

                void main() {
                    fragColor = texture(InSampler, texCoord);
                }
                """;
    }

    /**
     * 新增<b>实体轮廓层</b>时的起手源码。
     *
     * <p>和后处理层的空壳不一样，这里给的是一个<b>能立刻看到东西</b>的最小实现——
     * 原样透出原版轮廓等于「什么都没发生」，而轮廓层要先让一只实体发光才看得见，
     * 排查「到底是没发光还是效果没写对」的成本比后处理高得多。所以起手就给一层可见的染色。
     */
    public static String defaultOutlineBody() {
        return """
                // 实体轮廓层：覆盖原版 minecraft:entity_outline 那条链。
                // 只对<b>发光的实体</b>生效（服务端 /effect give ... glowing，
                // 或用编辑器的「让准星实体发光」调试开关）。
                //
                // 内置量 / built-ins:
                //   gtMask()        剪影覆盖，1 在实体内、0 在外
                //   gtMaskColor()   该实体的轮廓色（队伍颜色）——逐实体的「效果 id」通道
                //   gtMaskEdge()    剪影边缘强度
                //   gtScene()       渲染好的世界画面，可以拿它做扭曲/透镜
                //   输出的 alpha 决定这块像素有多大程度替换原画面

                // @param name=Tint type=color3 default=#66E0FF zh_cn=染色 en_us=Tint
                // @param name=Glow type=float min=0 max=3 default=1.2 zh_cn=边缘发光 en_us=Edge Glow

                void main() {
                    float inside = gtMask();
                    float edge = gtMaskEdge();
                    vec3 col = Tint * (0.35 + Glow * edge);
                    // alpha：实体内部淡淡一层，边缘实打实
                    fragColor = vec4(col, max(inside * 0.25, edge));
                }
                """;
    }

    /** 新增效果层时的起手源码：同样是原样输出的空壳，只是更短。 */
    public static String newLayerBody() {
        return """
                // 新的效果层，默认原样输出。写完勾选「启用」才会进入渲染链。
                // A new layer. It passes the image through until you write something here.

                void main() {
                    fragColor = texture(InSampler, texCoord);
                }
                """;
    }
}
