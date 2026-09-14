package mc.GTedd.cn.gtshaders.ai;

import mc.GTedd.cn.gtshaders.GTShaders;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 组装喂给模型的那几段文字。
 *
 * <h2>为什么 prompt 是资源文件而不是字符串常量</h2>
 *
 * <p>这份 prompt 会被反复调整——它是这个功能里唯一一处「改一句话就能显著改变产出质量」
 * 的地方。做成资源文件之后，调它不需要动 Java、不需要重新理解调用链，
 * 而且 diff 出来就是一段自然语言，能看清改了什么。
 *
 * <h2>为什么示例是一份真实的 .fsh</h2>
 *
 * <p>few-shot 示例一旦写死在 prompt 里，就成了没人验证的第二份真理：约定改了它不会跟着改，
 * 而模型会忠实地模仿一份过时的格式，产出一堆「看起来对但扫不出参数」的效果。
 * 把它放成 {@code example_post.fsh}，单元测试就能拿 {@code ParamScanner} 和
 * {@code SourceDoc} 去验它——示例一旦不合约定，测试立刻就红。
 */
public final class AiPrompt {

    private static final String SYSTEM_RES = "/assets/gtshaders/ai/system_post.md";

    /**
     * few-shot 示例，按顺序拼进 prompt。
     *
     * <p>两份刻意覆盖不同的类型组合：{@code example_post} 是「颜色 + 时间」
     * （float / color3 / @group），{@code example_warp} 是「几何扭曲 + 分支 + 循环」
     * （vec2 / bool / int，以及循环上界必须是常量这条）。
     * 只给一份的话，模型倾向于把那一份的形状当成唯一的形状，
     * 要什么效果都先来一层扫描线。
     */
    private static final List<String> EXAMPLE_RES = List.of(
            "/assets/gtshaders/ai/example_post.fsh",
            "/assets/gtshaders/ai/example_warp.fsh");

    /** 规范里那份保留名清单的占位符，由 {@link PitfallCheck#RESERVED_NAMES} 填。 */
    private static final String RESERVED_PLACEHOLDER = "{{RESERVED_NAMES}}";

    /** 读一次就够，之后一直用。prompt 在运行期不会变。 */
    private static String cached;

    private AiPrompt() {
    }

    /**
     * 完整的 system prompt：规范 + 保留名清单 + 两份真实示例。
     *
     * <p>资源读不到时退回一段极简 prompt。这种情况只可能出现在 jar 被人拆改过之后，
     * 但「功能整体不可用」比「产出质量下降」严重得多，不值得为它抛异常。
     */
    public static synchronized String system() {
        if (cached == null) {
            String spec = read(SYSTEM_RES);
            if (spec.isBlank()) {
                GTShaders.LOGGER.warn("AI system prompt 资源缺失，使用内置精简版");
                spec = fallbackSpec();
            }
            spec = spec.replace(RESERVED_PLACEHOLDER, reservedNameList());

            StringBuilder sb = new StringBuilder(spec.length() + 4096);
            sb.append(spec);
            appendExamples(sb);
            cached = sb.toString();
        }
        return cached;
    }

    private static void appendExamples(StringBuilder sb) {
        List<String> loaded = new ArrayList<>(EXAMPLE_RES.size());
        for (String path : EXAMPLE_RES) {
            String s = read(path);
            if (!s.isBlank()) {
                loaded.add(s.strip());
            }
        }
        if (loaded.isEmpty()) {
            return;
        }
        sb.append("\n\n# 两份合格产出的完整样子\n\n");
        sb.append("都是照着上述全部约定写出来的，也都通过了真机编译。看的时候注意：");
        sb.append("头部注释的写法、@group 怎么分、参数名怎么起、每一个参数都在代码里被用到、");
        sb.append("以及第二份里循环为什么写成常量上界加 break。\n");
        for (String example : loaded) {
            sb.append("\n```glsl\n").append(example).append("\n```\n");
        }
    }

    /**
     * 把保留名清单渲染成 prompt 里的一段。
     *
     * <p><b>清单只有一份</b>，在 {@link PitfallCheck#RESERVED_NAMES}。写死在 markdown 里的话，
     * 以后加了个内置量只会改代码，模型就会继续拿它当参数名——而那份 prompt 看起来一切正常。
     */
    private static String reservedNameList() {
        List<String> names = new ArrayList<>(PitfallCheck.RESERVED_NAMES);
        names.sort(String.CASE_INSENSITIVE_ORDER);
        StringBuilder sb = new StringBuilder(names.size() * 18);
        int perLine = 6;
        for (int i = 0; i < names.size(); i++) {
            if (i > 0) {
                sb.append(i % perLine == 0 ? "\n" : " ");
            }
            sb.append('`').append(names.get(i)).append('`');
        }
        return sb.toString();
    }

    /** 首轮请求。玩家那句话原样带上，不做任何改写——改写等于替玩家做决定。 */
    public static String initialRequest(String wish) {
        String w = wish == null ? "" : wish.strip();
        return "玩家想要的效果：\n\n" + w + "\n\n"
                + "请按上述全部约定写出这个后处理着色器。只输出一个 ```glsl 代码块。";
    }

    /**
     * 修错轮。
     *
     * <p>必须把<b>完整源码</b>一起发回去：DeepSeek 的 Responses API 明确不支持
     * {@code store} 和 {@code previous_response_id}（会被静默忽略），
     * 服务端不替我们记住上一轮说过什么。只发错误信息的话，模型是在凭空猜一份它看不见的代码。
     *
     * <p>行号是编辑器换算回作者源码之后的行号，和这里发过去的源码对得上——
     * 这一点很关键，模型才能直接定位到那一行。
     */
    public static String repairRequest(String source, List<String> errors) {
        StringBuilder sb = new StringBuilder(source.length() + 512);
        sb.append("上面这份着色器编译失败了。驱动报的错如下（行号对应下面这份源码）：\n\n");
        for (String e : errors) {
            sb.append("- ").append(e).append('\n');
        }
        sb.append("\n当前源码：\n\n```glsl\n").append(source.strip()).append("\n```\n\n");
        sb.append("请修好它。仍然遵守全部约定，仍然只输出一个 ```glsl 代码块，");
        sb.append("输出**完整**的修正版，不要只给改动的片段，不要解释改了什么。");
        return sb.toString();
    }

    /**
     * 编译通过、但踩了已知陷阱时的追问。
     *
     * <p>这类产出玩家的描述永远是「没反应」，而它编译得过，自动修错循环的常规路径
     * （拿编译器报错回喂）根本不会被触发——不主动查就永远漏过去。
     * 模型只要被明确告知踩了哪一条，通常一轮就能改对。
     */
    public static String pitfallRequest(String source, List<String> hints) {
        StringBuilder sb = new StringBuilder(source.length() + 512);
        sb.append("这份着色器编译通过了，但它踩了会导致「玩家看不见效果」的已知陷阱：\n\n");
        for (String h : hints) {
            sb.append("- ").append(h).append('\n');
        }
        sb.append("\n当前源码：\n\n```glsl\n").append(source.strip()).append("\n```\n\n");
        sb.append("请按上面的指出逐条改掉，仍然只输出一个 ```glsl 代码块，给出完整的修正版。");
        return sb.toString();
    }

    private static String read(String path) {
        try (InputStream in = GTShaders.class.getResourceAsStream(path)) {
            if (in == null) {
                return "";
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            GTShaders.LOGGER.warn("读取 AI prompt 资源失败 {}: {}", path, e.toString());
            return "";
        }
    }

    /** 兜底 prompt。只保留绝对不能少的那几条约定。 */
    private static String fallbackSpec() {
        return """
                你是 Minecraft 后处理着色器（GLSL 330）的作者。只输出一个 ```glsl 代码块。

                不要写 #version、uniform sampler2D InSampler、in vec2 texCoord、out vec4 fragColor
                以及任何 std140 块——编辑器会自动生成，重复声明会编译失败。

                可用：texCoord、fragColor、InSampler、OutSize、GTTime、gl_FragCoord。
                入口函数叫 main，必须给 fragColor 赋值。拿不到深度和法线，只有颜色。

                每个可调量写一行注解，编辑器据此生成滑块，至少 3 个：
                // @param name=Intensity type=float min=0 max=2 default=1 zh_cn=强度 en_us=Intensity
                type 可用 float / int / bool / vec2 / vec3 / vec4 / color3 / color4；
                bool 落地成 float，判断要写 > 0.5；颜色用 color3，default 写 #RRGGBB。
                声明了的参数必须在代码里真的用上，否则滑块拖了没反应而且不报错。

                不要用 clamp(GTTime / 时长, 0, 1) 做一次性播放（加进工程时 GTTime 已经几百秒，
                效果会停在结束态）。不要用写死的高绝对亮度门限（昏暗场景里恒为 0）。
                不要用 GameTime 驱动动画，用 GTTime。算像素步长用 1.0 / OutSize，不用 ScreenSize。
                循环上界必须是编译期常量。
                """;
    }
}
