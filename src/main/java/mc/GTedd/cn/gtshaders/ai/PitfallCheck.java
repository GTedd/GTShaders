package mc.GTedd.cn.gtshaders.ai;

import mc.GTedd.cn.gtshaders.codegen.ParamScanner;
import mc.GTedd.cn.gtshaders.core.ShaderParam;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 编译通过之后的第二道关：查那些「编译得过、跑起来不对」的写法。
 *
 * <h2>为什么必须有这一道</h2>
 *
 * <p>自动修错循环的燃料是编译器报错，而这里查的每一种故障<b>都没有报错</b>——着色器合法、
 * 链接成功、每帧都在跑，只是结果恒等于原图，或者那个滑块拖了不动。
 * 循环于是认为一切顺利，把一个空效果交给玩家，玩家得到的结论是「这个 AI 不好使」。
 *
 * <p>判据分两类：
 * <ul>
 *   <li><b>效果看不见</b>——一次性播放、写死的亮度门限、没给 {@code fragColor} 赋值。
 *       扫的是离线真渲染、量画面前后差异时反复抓到过的那几类故障，
 *       只不过这里改成静态的词法判断：游戏内做画面回读要动 GL 状态，
 *       而这些模式化得足以用正则抓住——它们本来就是模型的惯性写法，不是千奇百怪的 bug。</li>
 *   <li><b>选项不可用</b>——参数声明了却没在代码里用、参数名撞上内置量。
 *       这类尤其恶劣：面板上滑块好好地摆着，拖动毫无反应，
 *       而玩家打开这个编辑器就是为了拖滑块。</li>
 * </ul>
 *
 * <h2>为什么宁可漏报也不误报</h2>
 *
 * <p>误报的代价是白白多跑一轮生成：多花玩家的钱、多等十几秒，
 * 最后还可能把一份本来没问题的着色器改坏。漏报的代价只是回到「没有这道检查」的状态。
 * 所以每条判据都收得很紧，只在几乎肯定踩坑时才出声——
 * 而「紧」的标准由 {@code PitfallCorpusTest} 钉住：拿库里那一百多个人工写的效果去跑，
 * 一条误报都不许有。
 */
public final class PitfallCheck {

    /**
     * 不能用作参数名的标识符。
     *
     * <p><b>这份清单是 prompt 里那份的唯一来源</b>（{@link AiPrompt#system()} 直接把它渲染进去）。
     * 分开写两份的话，加了个内置量却只改了代码，模型就会继续拿它当参数名，
     * 撞出来的重复声明报错还指着模板行——而那份 prompt 看起来一切正常。
     *
     * <p>撞名的后果是编译失败（重复声明，或者宏把参数名替换成了别的东西），
     * 所以自动修错<b>能</b>兜住它。列在这里是为了省掉那一轮：一轮就是十几秒和一笔 token。
     */
    public static final Set<String> RESERVED_NAMES = Set.of(
            // 生成的 uniform 块名
            "Globals", "SamplerInfo", "GTParams",
            // 采样器
            "InSampler", "SceneSampler",
            // SamplerInfo 成员
            "OutSize", "InSize",
            // 原版 Globals 成员，可以读，但不能拿来当参数名
            "CameraBlockPos", "CameraOffset", "ScreenSize", "GlintAlpha", "GameTime",
            "MenuBlurRadius", "UseRgss",
            // 顶点输入 / 片元输出
            "texCoord", "fragColor",
            // mod 自己的 uniform 块变量
            "GTSystem", "GTLayer", "GTViewport", "GTAnchorInfo", "GTAnchorA", "GTAnchorB",
            "GTCameraProj", "GTCameraRight", "GTCameraUp", "GTCameraBack",
            // 便捷别名（都是 #define，撞上会被预处理器替换掉，报错极难懂）
            "GTTime", "GTDeltaTime", "GTFrame", "GTPlaying", "GTStrength",
            "GTLayerIndex", "GTLayerCount", "GTViewportUV", "GT_ANCHOR_SLOTS",
            "iTime", "iTimeDelta", "iResolution", "iChannel0", "iChannel1",
            // 入口与生成的包装函数
            "main", "gtAuthorMain");

    /**
     * @param code 判据编号，界面上按它取翻译
     * @param hint 直接发给模型的中文说明，写清「错在哪、该怎么改」
     */
    public record Warning(String code, String hint) {
    }

    // ---------------------------------------------------------------- 判据

    /**
     * 一次性播放：{@code clamp(GTTime / X, 0.0, 1.0)} 及 {@code min(GTTime / X, 1.0)}。
     *
     * <p>{@code iTime} 是同一个量的 Shadertoy 别名，一起认。
     */
    private static final Pattern ONE_SHOT = Pattern.compile(
            "\\b(?:clamp|min)\\s*\\(\\s*\\(?\\s*(?:GTTime|iTime)\\s*/[^,]*,\\s*[^)]*\\)");

    /** {@code smoothstep(字面量, 字面量, 标识符)} 与 {@code step(字面量, 标识符)}。 */
    private static final Pattern SMOOTHSTEP_LIT = Pattern.compile(
            "\\bsmoothstep\\s*\\(\\s*([0-9]*\\.?[0-9]+)\\s*,\\s*([0-9]*\\.?[0-9]+)\\s*,\\s*([A-Za-z_]\\w*)\\s*\\)");
    private static final Pattern STEP_LIT = Pattern.compile(
            "\\bstep\\s*\\(\\s*([0-9]*\\.?[0-9]+)\\s*,\\s*([A-Za-z_]\\w*)\\s*\\)");

    /** 一眼就是亮度的变量名。 */
    private static final Pattern LUMA_NAME = Pattern.compile(
            "(?i).*(luma|luminance|lum|bright|gray|grey)\\w*");

    /** 用点乘算亮度的两种常见系数：Rec.601 与 Rec.709。 */
    private static final Pattern LUMA_ASSIGN = Pattern.compile(
            "\\b(?:float\\s+)?([A-Za-z_]\\w*)\\s*=\\s*dot\\s*\\([^;]*vec3\\s*\\(\\s*(?:0\\.299|0\\.2126)");

    /**
     * 低边界高到这个值以上才算「写死得太高」。
     *
     * <h3>为什么定得这么松</h3>
     *
     * <p>拿库里一百多个人工效果跑过之后才发现，<b>字面量亮度门限是一种极其常见且多数情况下
     * 正当的写法</b>：aurora、caustics、bioluminesce 这些都用 {@code smoothstep(0.1~0.2, …, luma)}
     * 去挑亮部叠加发光，低边界很低，暗场景里照样有响应；cinematic 和 sprintrush 甚至用
     * {@code smoothstep(0.45~0.6, 1.0, luma)} <b>专门</b>只吃高光去拉光晕和速度线——
     * 那是美术意图，不是故障。
     *
     * <p>静态地区分「整体可见性被门限掐死」和「某个子项只作用于亮部」需要判断门限结果
     * 是否乘在最终输出上，词法层面做不可靠。于是这条判据退成只抓明显过分的情况，
     * 真正的防线放在 prompt 里——教模型别这么写，比事后猜它是不是这么写了有效得多。
     */
    private static final float SAFE_THRESHOLD = 0.7f;

    /** 拿原版 {@code GameTime} 驱动动画：它会回绕，而且编辑器暂停不了它。 */
    private static final Pattern GAME_TIME_ANIM = Pattern.compile(
            "\\b(?:sin|cos|fract|mod|tan)\\s*\\([^;)]*\\bGameTime\\b");

    /** 拿 {@code ScreenSize} 算一个像素有多大——多通道链里中转缓冲会被缩放，得用 {@code OutSize}。 */
    private static final Pattern SCREEN_SIZE_TEXEL = Pattern.compile("/\\s*(?:max\\s*\\(\\s*)?ScreenSize\\b");

    /**
     * 位打包的数据被 {@code texture()} 采样读走。
     *
     * <p>只认「解包函数直接套着一次插值采样」这一种写法。收得这么紧是故意的：
     * 作者先把纹素取进一个变量、再解包也一样会坏，但那种写法要跨语句追变量来源，
     * 而这里宁可漏报也不误报。好在模型和人都倾向于写成一行。
     */
    private static final Pattern PACKED_BILINEAR = Pattern.compile(
            "gtUnpack(?:Float|Uint)\\s*\\(\\s*texture\\s*\\(");

    /** {@code fragColor} 或它的分量出现在赋值号左边。{@code ==} 要排掉。 */
    private static final Pattern FRAG_COLOR_WRITE = Pattern.compile(
            "\\bfragColor\\b\\s*(?:\\.[xyzwrgba]+\\s*)?(?:\\+|-|\\*|/)?=(?!=)");

    private PitfallCheck() {
    }

    public static List<Warning> scan(String source) {
        List<Warning> out = new ArrayList<>();
        if (source == null || source.isBlank()) {
            return out;
        }
        ParamScanner.Result scan = ParamScanner.scan(source);
        // strippedBody 已经把裸 uniform 声明换成了等价的注解行，再去掉注释，
        // 剩下的就是纯代码——两种参数声明方式在这里的行为于是完全一致
        String code = stripComments(scan.strippedBody());

        checkVisibility(code, out);
        checkParams(scan, code, out);
        return out;
    }

    // ---------------------------------------------------------------- 效果看不见

    private static void checkVisibility(String code, List<Warning> out) {
        if (!FRAG_COLOR_WRITE.matcher(code).find()) {
            out.add(new Warning("no_frag_color",
                    "整份代码没有给 fragColor 赋值。片元输出不写就是未定义的，"
                            + "画面上会是原图或者一片垃圾像素，而且编译**不会**报错。"
                            + "在 main 的末尾写 fragColor = vec4(col, 1.0);"));
        }
        if (ONE_SHOT.matcher(code).find()) {
            out.add(new Warning("one_shot",
                    "用了 clamp/min 把 GTTime 收进 [0,1] 做一次性播放。玩家把效果加进工程时 "
                            + "GTTime 已经走到几百秒，这个式子恒等于 1，效果永远停在结束态、看起来就是没生效。"
                            + "改成 fract(GTTime / 周期) 循环播放，"
                            + "或者用 mod 写一个「推进→停顿→退回→停顿」的往返函数。"));
        }
        String luma = findLumaThreshold(code);
        if (luma != null) {
            out.add(new Warning("absolute_threshold",
                    "用写死的常量给亮度做门限（" + luma + "）。Minecraft 的画面亮度随昼夜、天气、"
                            + "洞穴剧烈变化，写死的阈值会让效果在夜里和洞里恒为 0，玩家转个头就以为坏了。"
                            + "把阈值改成 @param 参数并把默认值取低一些。"));
        }
        if (GAME_TIME_ANIM.matcher(code).find()) {
            out.add(new Warning("game_time",
                    "拿原版 GameTime 驱动动画。它会回绕，而且编辑器暂停时它照跑——"
                            + "玩家想定格看某一帧根本停不下来。动画一律用 GTTime。"));
        }
        if (SCREEN_SIZE_TEXEL.matcher(code).find()) {
            out.add(new Warning("screen_size_texel",
                    "用 ScreenSize 算一个像素有多大。ScreenSize 是窗口尺寸，"
                            + "而多通道链里本通道的渲染目标可能被缩放过，两者不等。"
                            + "算像素步长一律用 1.0 / OutSize。"));
        }
        if (PACKED_BILINEAR.matcher(code).find()) {
            out.add(new Warning("packed_bilinear",
                    "拿 texture() 去读位打包的数据。位模式一旦被双线性插值混合，"
                            + "解出来就不是「精度差一点」而是一个毫无关系的数——"
                            + "错一个字节可能就是差一个数量级，而且**不会**报错。"
                            + "读位打包的数据只能用 gtUnpackFloatAt(采样器, ivec2(x, y))，"
                            + "它内部走 texelFetch，正好落在纹素中心、不经过任何过滤。"));
        }
    }

    /** 有没有「拿写死的常量去卡一个亮度变量」。找到就返回那段原文，供提示引用。 */
    private static String findLumaThreshold(String code) {
        Set<String> lumaVars = new HashSet<>();
        Matcher assign = LUMA_ASSIGN.matcher(code);
        while (assign.find()) {
            lumaVars.add(assign.group(1));
        }

        Matcher ss = SMOOTHSTEP_LIT.matcher(code);
        while (ss.find()) {
            if (isLuma(ss.group(3), lumaVars) && tooHigh(ss.group(1))) {
                return ss.group();
            }
        }
        Matcher st = STEP_LIT.matcher(code);
        while (st.find()) {
            if (isLuma(st.group(2), lumaVars) && tooHigh(st.group(1))) {
                return st.group();
            }
        }
        return null;
    }

    private static boolean isLuma(String name, Set<String> known) {
        return known.contains(name) || LUMA_NAME.matcher(name).matches();
    }

    /**
     * <b>只看低边界（edge0）。</b>
     *
     * <p>决定「暗场景会不会被完全掐掉」的只有它：低边界之下结果恒为 0。
     * 上边界高只是说「到多亮才算全饱和」，那完全正常——
     * {@code smoothstep(0.0, 0.55, luma)} 从 0 起步，一个像素都不会被掐掉，
     * 可只要判据看了上边界，它就会被误判成有问题。
     */
    private static boolean tooHigh(String edge0) {
        return parse(edge0) >= SAFE_THRESHOLD;
    }

    // ---------------------------------------------------------------- 选项不可用

    private static void checkParams(ParamScanner.Result scan, String code, List<Warning> out) {
        List<ShaderParam> params = scan.params();

        if (params.size() < 3) {
            out.add(new Warning("too_few_params",
                    "只识别到 " + params.size() + " 个可调参数。这是一个可视化编辑器，"
                            + "玩家打开它就是为了拖滑块，不能调的效果等于一张静态贴图。"
                            + "至少给 3 个 // @param，注意参数直接当变量用、不要另外写 uniform 声明。"));
        }

        // 扫描器自己发现的问题：缺 name、类型不认识、重名。它们都会让那一条参数
        // 悄悄消失或退化成 float 滑块，而玩家只看到「面板上少了一个东西」
        if (!scan.warnings().isEmpty()) {
            out.add(new Warning("bad_annotation",
                    "有 @param 注解没被正确解析：" + String.join("；", scan.warnings())
                            + "。每条注解必须写成一行，形如 "
                            + "// @param name=Intensity type=float min=0 max=2 default=1 zh_cn=强度 en_us=Intensity"));
        }

        Set<String> reserved = new LinkedHashSet<>();
        List<String> unused = new ArrayList<>();
        for (ShaderParam p : params) {
            if (RESERVED_NAMES.contains(p.name())) {
                reserved.add(p.name());
            } else if (!usesIdentifier(code, p.name())) {
                unused.add(p.name());
            }
        }

        if (!reserved.isEmpty()) {
            out.add(new Warning("reserved_name",
                    "参数名撞上了编辑器的内置量：" + String.join("、", reserved)
                            + "。这些名字由模板生成，重名会导致重复声明或被宏替换掉。换个名字。"));
        }
        if (!unused.isEmpty()) {
            out.add(new Warning("unused_param",
                    "这些参数声明了却没在代码里用到：" + String.join("、", unused)
                            + "。它们会在面板上生成滑块，玩家拖动却毫无反应——"
                            + "而这**不会**报任何错。要么在代码里真的用上它们，要么把注解删掉。"
                            + "注意 GLSL 区分大小写，注解里的 name 和代码里的写法必须完全一致。"));
        }
    }

    /** 标识符是否真的在代码里出现过。整词匹配，避免 {@code Amount} 命中 {@code AmountX}。 */
    private static boolean usesIdentifier(String code, String name) {
        return Pattern.compile("\\b" + Pattern.quote(name) + "\\b").matcher(code).find();
    }

    // ---------------------------------------------------------------- 工具

    private static float parse(String s) {
        try {
            return Float.parseFloat(s);
        } catch (NumberFormatException e) {
            return 0f;
        }
    }

    /**
     * 去掉注释再扫。
     *
     * <p>不去的话，示例注释里那句「不要写成 clamp(GTTime / 周期, 0, 1)」会把自己扫成一条警告——
     * 而模型很爱把 prompt 里的反例抄进注释当提醒。库里也有好几个效果在注释里
     * 专门解释「为什么不用那种写法」。
     *
     * <p>用空格替换而不是删掉，长度不变，将来要报位置时偏移仍然对得上。
     */
    static String stripComments(String src) {
        StringBuilder out = new StringBuilder(src.length());
        boolean lineComment = false;
        boolean blockComment = false;
        for (int i = 0; i < src.length(); i++) {
            char c = src.charAt(i);
            char n = i + 1 < src.length() ? src.charAt(i + 1) : '\0';
            if (lineComment) {
                if (c == '\n') {
                    lineComment = false;
                    out.append(c);
                } else {
                    out.append(' ');
                }
                continue;
            }
            if (blockComment) {
                if (c == '*' && n == '/') {
                    blockComment = false;
                    out.append("  ");
                    i++;
                } else {
                    out.append(c == '\n' ? '\n' : ' ');
                }
                continue;
            }
            if (c == '/' && n == '/') {
                lineComment = true;
                out.append("  ");
                i++;
            } else if (c == '/' && n == '*') {
                blockComment = true;
                out.append("  ");
                i++;
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    /** 把警告转成发给模型的行。 */
    public static List<String> hints(List<Warning> warnings) {
        List<String> out = new ArrayList<>(warnings.size());
        for (Warning w : warnings) {
            out.add(w.hint());
        }
        return out;
    }

    /** 界面用的翻译键。 */
    public static String langKey(Warning w) {
        return langKeyFor(w.code());
    }

    /**
     * 同上，但只要编号。
     *
     * <p>警告最终是以 {@code pitfall:<code>} 的形式混在产出说明里跨线程传到界面层的，
     * 那边只剩下编号，没有 Warning 对象了。
     */
    public static String langKeyFor(String code) {
        return "gtshaders.ai.pitfall." + code.toLowerCase(Locale.ROOT);
    }
}
