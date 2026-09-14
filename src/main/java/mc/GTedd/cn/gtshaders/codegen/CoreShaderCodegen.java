package mc.GTedd.cn.gtshaders.codegen;

import mc.GTedd.cn.gtshaders.i18n.GtLang;
import mc.GTedd.cn.gtshaders.core.GtProfile;
import mc.GTedd.cn.gtshaders.core.ShaderKind;
import mc.GTedd.cn.gtshaders.core.ShaderParam;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 把作者写的一小段「钩子」注入到原版核心着色器里。
 *
 * <h2>为什么是注入而不是整份重写</h2>
 *
 * <p>核心着色器和后处理不一样：后处理只有一张输入图，我们可以整份生成；
 * 核心着色器要正确处理光照、雾、overlay、OIT 两阶段、十几个 {@code #define} 变体，
 * 整份重写就等于把原版逻辑抄一遍，而且每个版本更新都得重抄一遍。那是维护不住的。
 *
 * <p>所以这里反过来：<b>拿原版源码当模板，只在两个点上做手脚</b>。
 * 好处是所有版本差异、OIT 分支、变体开关全都自动继承，作者只写效果本身：
 *
 * <pre>
 *   // 顶点钩子：改变顶点位置（做位移、摇摆、动画）
 *   vec3 gtVertex(vec3 position) { ... return position; }
 *
 *   // 片段钩子：改变最终颜色
 *   vec4 gtFragment(vec4 color) { ... return color; }
 * </pre>
 *
 * <h2>两个注入点靠什么定位</h2>
 *
 * <p>不是行号（那会随版本漂移），是原版源码里稳定存在的<b>文本模式</b>：
 * <ul>
 *   <li>顶点：{@code gl_Position = ... vec4(Position, 1.0)}，把里面的位置表达式包一层。
 *       少数着色器写的是 {@code vec4(pos, 1.0)}（先算了个局部变量），两种都认。</li>
 *   <li>片段：{@code fragColor = X;}，把右边整个包一层。
 *       实测每个核心着色器都<b>恰好只有一处</b>对 fragColor 的赋值。</li>
 * </ul>
 *
 * <p>找不到锚点时<b>直接抛异常</b>，不生成"看起来正常但其实没接上"的着色器——
 * 那种失败最难查：编译通过、游戏正常、效果没有。
 *
 * <h2>参数只能是 const</h2>
 *
 * <p>资源包<b>不能给核心着色器新增 uniform 块</b>——块必须由 Java 侧的渲染管线声明
 * （见 <a href="https://mojira.dev/MC-307387">MC-307387</a>）。
 * 所以参数编译成 {@code const}，改参数要重新生成、重新编译。
 * 这是核心着色器相对后处理的真实代价，不是没做完。
 */
public final class CoreShaderCodegen {

    public static final String VERTEX_HOOK = "gtVertex";
    public static final String FRAGMENT_HOOK = "gtFragment";

    private static final String INJECT_BEGIN = "// ==== GTShaders 注入开始 ====";
    private static final String INJECT_END = "// ==== GTShaders 注入结束 ====";

    /** {@code gl_Position = ...} 那一整条语句。 */
    private static final Pattern GL_POSITION_STMT =
            Pattern.compile("gl_Position\\s*=[^;]*;", Pattern.DOTALL);
    /** 语句里的位置表达式：{@code vec4(Position, 1.0)} 或 {@code vec4(pos, 1.0)}。 */
    private static final Pattern POSITION_EXPR =
            Pattern.compile("vec4\\s*\\(\\s*(Position|pos)\\s*,\\s*1\\.0\\s*\\)");
    /** {@code fragColor = X;} */
    private static final Pattern FRAG_ASSIGN =
            Pattern.compile("fragColor\\s*=\\s*([^;]+);", Pattern.DOTALL);
    /** 插入点：唯一的 {@code void main()}。 */
    private static final Pattern MAIN_DECL = Pattern.compile("\\bvoid\\s+main\\s*\\(");

    /**
     * @param source          完整可编译的源码
     * @param injectedAtLine  注入块的起始行（1 起）；驱动报的行号减去它就是作者源码的行号
     * @param hooked          是否真的接上了钩子
     */
    public record Output(String source, int injectedAtLine, boolean hooked) {
    }

    private CoreShaderCodegen() {
    }

    /**
     * 生成一个阶段的完整源码。
     *
     * @param kind     着色器种类
     * @param stage    {@code vsh} 或 {@code fsh}
     * @param template <b>原版</b>该阶段的源码，运行时从游戏里取
     * @param author   作者写的钩子源码
     * @param params   可调参数，会编译成 const
     */
    /**
     * 精准采样面上纹理的 helper。
     *
     * <h2>它解决的问题</h2>
     *
     * <p>{@code texCoord0} 是<b>图集</b>坐标，不是「这个面内部的 0..1 坐标」。
     * 对 item / terrain / block / particle / crumbling / text 这几种，Sampler0 绑的是一张
     * 上千像素的图集，一个 16×16 的精灵在里面只占 {@code 16/1024 = 0.015625} 那么一小段。
     * 直接拿 {@code texCoord0.y * 140} 当扫描线相位，算出来是 {@code sin(2.2)}——
     * 整个精灵上几乎是一个常数，效果<b>不是变弱，是根本不出现</b>。
     * 而 entity / glint / beacon_beam 这些绑的是独立纹理，{@code texCoord0} 恰好就是 0..1，
     * 同一行代码在那边又是对的。这个差别不写出来没人分得清。
     *
     * <h2>为什么不用偏导数</h2>
     *
     * <p>常见做法是拿 {@code dFdx(texCoord0) / dFdx(uv)} 反解出「精灵有多大」。
     * 但偏导数是按 2×2 像素块做差分的：块跨过三角形边界、面在屏幕上退化成一条线、
     * 分母趋近 0——都会让结果炸掉，表现为一片噪点。
     *
     * <p>轩宇1725《着色器实践篇 - 精准采样纹理》给的办法是<b>把未知量换掉</b>：
     * 精灵的像素尺寸 {@code imgSize} 本来就是已知的（物品方块几乎都是 16），
     * 图集尺寸 {@code textureSize(Sampler0, 0)} 也能直接问出来，于是
     *
     * <pre>
     *   texCoord0   = spriteOrigin + normalizedUV * imgSize / atlasSize
     *   sampleCoord = texCoord0 + (imgCoord - normalizedUV) * imgSize / atlasSize
     * </pre>
     *
     * <p>一次乘加，没有除法也没有差分，数值上完全稳定。
     *
     * <h2>只有源码里出现 gt 前缀时才注入</h2>
     *
     * <p>和 {@code gtProbe} / {@code gtAnchor} 一条规矩：没用到就一行都不多。
     * 另外要求模板里确实有 {@code Sampler0} 和 {@code texCoord0}——
     * lightmap / sky / lines 这些没有纹理的种类不注入，作者会收到
     * 「未定义的 gtSpriteUV」，那句话指向的是他自己写的那一行，比让生成的代码报错好查。
     */
    private static final String ATLAS_HELPERS = """

            // ---- 精准采样面上纹理（gtSprite*）----
            // texCoord0 是图集坐标，不是面内 0..1 坐标。核心关系式：
            //   texCoord0 = spriteOrigin + normalizedUV * imgSize / atlasSize
            vec2 gtAtlasSize() {
                return vec2(textureSize(Sampler0, 0));
            }
            // 一个纹素在图集 UV 空间里有多大。描边、模糊的半径拿它算，比 dFdx 稳
            vec2 gtTexelSize() {
                return 1.0 / gtAtlasSize();
            }
            // 精灵在图集 UV 空间里占多大，也就是关系式里的 k
            vec2 gtSpriteScale(vec2 imgSize) {
                return imgSize * gtTexelSize();
            }
            // 精灵左上角在图集里的坐标，关系式里的 b。
            // 调试时把它当颜色输出：同一个精灵上应该是纯色，出现渐变就说明
            // normalizedUV 的环绕方向和 texCoord0 反了（见 item_uvcheck）
            vec2 gtSpriteOrigin(vec2 normalizedUV, vec2 imgSize) {
                return texCoord0 - normalizedUV * gtSpriteScale(imgSize);
            }
            // ★ 核心公式：拿面内归一化坐标去采样这个面自己的贴图
            vec2 gtSpriteCoord(vec2 imgCoord, vec2 normalizedUV, vec2 imgSize) {
                return texCoord0 + (imgCoord - normalizedUV) * gtSpriteScale(imgSize);
            }
            vec4 gtSpriteSample(vec2 imgCoord, vec2 normalizedUV, vec2 imgSize) {
                return texture(Sampler0, gtSpriteCoord(imgCoord, normalizedUV, imgSize));
            }
            // 手里没有 normalizedUV 时的反解。前提是精灵在图集里按 imgSize 对齐——
            // 拼接器按尺寸降序放置且尺寸都取 2 的幂，同尺寸的一批贴图满足这一点。
            // imgSize 填错的后果是图案相位偏移，不是崩溃，改回来即可
            vec2 gtSpriteUV(vec2 imgSize) {
                return fract(texCoord0 / gtSpriteScale(imgSize));
            }
            // 物品和方块的贴图绝大多数是 16×16
            vec2 gtSpriteUV() {
                return gtSpriteUV(vec2(16.0));
            }
            // 以纹素为单位偏移采样。描边写 gtSpriteTexel(vec2(1.0, 0.0)) 这种，
            // 宽度就恒等于「一个纹素」，不随镜头远近变化
            vec4 gtSpriteTexel(vec2 texelOffset) {
                return texture(Sampler0, texCoord0 + texelOffset * gtTexelSize());
            }
            // 同上，但越出精灵边界时返回全 0，而不是采到图集里的邻居。
            // 描边必须用这个：图集里紧挨着的是另一个物品，采过去边缘会闪别人的形状
            vec4 gtSpriteTexelClamped(vec2 texelOffset, vec2 imgSize) {
                vec2 uv = gtSpriteUV(imgSize) + texelOffset / imgSize;
                if (any(lessThan(uv, vec2(0.0))) || any(greaterThanEqual(uv, vec2(1.0)))) {
                    return vec4(0.0);
                }
                return texture(Sampler0, texCoord0 + texelOffset * gtTexelSize());
            }
            """;

    /** 源码里用到了 {@link #ATLAS_HELPERS} 里的任何一个函数。 */
    private static boolean usesAtlas(String author) {
        if (author == null) {
            return false;
        }
        String src = stripComments(author);
        return src.contains("gtSprite") || src.contains("gtTexelSize") || src.contains("gtAtlasSize");
    }

    /** 这个种类到底有没有图集可采。没有就别注入，让报错落在作者写的那一行上。 */
    private static boolean templateSupportsAtlas(String template) {
        return template.contains("Sampler0") && template.contains("texCoord0");
    }

    /**
     * 顶点/实例序号的别名。
     *
     * <p>26.3 把 {@code gl_VertexID} 换成了 Vulkan 风格的 {@code gl_VertexIndex}，没有留兼容宏——
     * 着色器现在由 ShaderC 按 Vulkan GLSL 语义编译，那边只认后者。
     *
     * <p>别名保留着，尽管现在只有一个取值：作者源码里写 {@code GT_VERTEX_INDEX}，
     * 下一个版本 Mojang 再改名时只要动这一行，效果库里上百份源码一个字都不用改。
     */
    private static String compatDefines() {
        return "#define GT_VERTEX_INDEX gl_VertexIndex\n"
                + "#define GT_INSTANCE_INDEX gl_InstanceIndex\n";
    }

    public static Output generate(ShaderKind.Entry kind, GtProfile profile, String stage,
                                  String template, String author, List<ShaderParam> params) {
        boolean vertex = "vsh".equals(stage);

        // 作者自己写了 main，说明他要的是<b>整份接管</b>而不是往原版里插钩子。
        // 这条路是拖入第三方核心着色器时唯一可行的：那种文件没有钩子函数，
        // 只有一份完整的原版改写，硬要注入反而会把它毁掉。
        if (isRawOverride(author)) {
            return rawOverride(profile, vertex, author, params);
        }

        boolean wantHook = vertex ? kind.vertexHook() : kind.fragmentHook();
        String hookName = vertex ? VERTEX_HOOK : FRAGMENT_HOOK;

        // 用了 GameTime / ScreenSize 之类就自动把 Globals 块引进来，
        // 否则作者会收到一句莫名其妙的"未定义的变量 GameTime"
        String spliced = usesGlobals(author) ? ensureGlobals(template, profile) : template;
        boolean hooked = false;
        if (wantHook && author != null && author.contains(hookName)) {
            // 从 spliced 接着改，不是从 template——否则上面补进去的 include 会被丢掉
            spliced = vertex ? spliceVertex(spliced) : spliceFragment(spliced);
            hooked = true;
        }

        int mainAt = indexOfMain(spliced);
        StringBuilder sb = new StringBuilder(spliced.length() + 1024);
        sb.append(spliced, 0, mainAt);

        // OIT 的 alpha 阶段没有 fragColor，也没有大部分 varying。
        // 原版把那些声明包在 #ifndef OIT_ALPHA_ONLY 里，我们注入的代码引用了它们，
        // 所以必须跟着一起包——不然 alpha 变体编译不过。
        boolean guard = !vertex && spliced.contains("OIT_ALPHA_ONLY");

        sb.append('\n').append(INJECT_BEGIN).append('\n');
        if (guard) {
            sb.append("#ifndef OIT_ALPHA_ONLY\n");
        }
        if (vertex) {
            sb.append(compatDefines());
        }
        // 图集 helper 排在参数常量之前：injectedAtLine 是在这一整段之后才数的，
        // 多注入的行数会被一起算进去，作者看到的报错行号仍然对得上
        if (!vertex && usesAtlas(author) && templateSupportsAtlas(spliced)) {
            sb.append(ATLAS_HELPERS);
        }
        for (ShaderParam p : params) {
            sb.append(constLine(p)).append('\n');
        }
        if (!params.isEmpty()) {
            sb.append('\n');
        }
        int injectedAtLine = countLines(sb) + 1;
        // 只留本阶段用得上的那个钩子。顺手删掉另一个不是为了省几行——
        // 作者的 gtVertex 里很可能用了 Normal、UV1 这些<b>只有顶点阶段才有</b>的属性，
        // 原样塞进片段着色器就是一句"未定义的变量"，而报错指向的是作者没写错的那一行。
        String body = author == null ? "" : dropOtherHook(author, vertex);
        sb.append(body);
        if (!body.isEmpty() && !body.endsWith("\n")) {
            sb.append('\n');
        }
        if (guard) {
            sb.append("#endif\n");
        }
        sb.append(INJECT_END).append('\n');
        sb.append(spliced.substring(mainAt));

        return new Output(sb.toString(), injectedAtLine, hooked);
    }

    /**
     * 删掉不属于本阶段的那个钩子函数，行数用空行补齐。
     *
     * <p>补空行是为了让报错行号仍然对得上作者在编辑器里看到的源码——
     * 删几行就少几行的话，错误标红会整体上移，比不报错还难查。
     */
    public static String dropOtherHook(String author, boolean vertexStage) {
        return removeFunction(author, vertexStage ? FRAGMENT_HOOK : VERTEX_HOOK);
    }

    /** 按花括号配对删掉一个函数定义，保留同样多的空行。 */
    private static String removeFunction(String source, String name) {
        Pattern decl = Pattern.compile("^[ \\t]*\\w+[ \\t]+" + Pattern.quote(name) + "\\s*\\([^)]*\\)\\s*\\{",
                Pattern.MULTILINE);
        Matcher m = decl.matcher(source);
        if (!m.find()) {
            return source;
        }
        int depth = 0;
        int i = m.end() - 1;
        for (; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    i++;
                    break;
                }
            }
        }
        String removed = source.substring(m.start(), i);
        return source.substring(0, m.start())
                + "\n".repeat(Math.max(0, (int) removed.chars().filter(c -> c == '\n').count()))
                + source.substring(i);
    }

    /**
     * 这份源码是不是一份「整份接管」的完整着色器。
     *
     * <p>判据是有没有自己的 {@code main()}——钩子模式下作者只写 {@code gtVertex} /
     * {@code gtFragment}，绝不会写 main。这条判据同时也是给用户的规则：
     * <b>写了 main 就是你全权负责</b>，GTShaders 不再往里插任何东西。
     */
    public static boolean isRawOverride(String author) {
        return author != null && MAIN_DECL.matcher(stripComments(author)).find();
    }

    /** 整份接管：原样输出，只在 main 之前补上参数常量。 */
    private static Output rawOverride(GtProfile profile, boolean vertex,
                                      String author, List<ShaderParam> params) {
        if (params.isEmpty()) {
            return new Output(author, 1, false);
        }
        int mainAt = indexOfMain(author);
        StringBuilder sb = new StringBuilder(author.length() + 256);
        sb.append(author, 0, mainAt);
        if (vertex) {
            sb.append(compatDefines());
        }
        for (ShaderParam p : params) {
            sb.append(constLine(p)).append('\n');
        }
        sb.append(author.substring(mainAt));
        return new Output(sb.toString(), 1, false);
    }

    private static String stripComments(String source) {
        String s = source.replaceAll("(?s)/\\*.*?\\*/", "");
        return s.replaceAll("//[^\n]*", "");
    }

    /**
     * {@code Globals} 块里的成员。
     *
     * <p>它是最容易踩的坑：想做随时间变化的效果就要 {@code GameTime}，
     * 想按屏幕位置取值就要 {@code ScreenSize}——这是新手最先想用的两个东西。
     * 但<b>多数核心着色器并不引 globals.glsl</b>（26.3 的 entity.fsh 只在
     * {@code #ifdef GLINT} 时才引），直接写就是一句"未定义的变量"。
     * 所以这里检测到用了就自动补上 include。
     */
    private static final String[] GLOBALS_MEMBERS = {
            "GameTime", "ScreenSize", "CameraBlockPos", "CameraOffset",
            "GlintAlpha", "MenuBlurRadius", "UseRgss",
    };

    private static boolean usesGlobals(String author) {
        if (author == null) {
            return false;
        }
        String src = stripComments(author);
        for (String member : GLOBALS_MEMBERS) {
            if (Pattern.compile("\\b" + member + "\\b").matcher(src).find()) {
                return true;
            }
        }
        return false;
    }

    /**
     * 在模板里补一条引入 globals 的指令。
     *
     * <p>插在最后一条已有的 include 之后——那里一定在所有声明之前，
     * 而且和原版自己的写法排在一起，产物读起来仍然像一份正常的着色器。
     *
     * <p><b>要注意的一点</b>：能编译不等于运行时一定拿得到值。
     * uniform 块要由 Java 侧的渲染管线声明，资源包加不了
     * （<a href="https://mojira.dev/MC-307387">MC-307387</a>）。
     * OpenGL 后端通常宽容，Vulkan 后端可能报"找不到 uniform"——
     * 这一条只能在真机上验。
     */
    /** 被 {@code #ifdef} 包着的 globals 引入——26.3 的 item/entity 就是这么写的。 */
    private static final Pattern GUARDED_GLOBALS = Pattern.compile(
            "^[ \\t]*#if(?:def|ndef)\\s+\\w+[ \\t]*\\r?\\n"
                    + "([ \\t]*#(?:include|moj_import)\\s*<[^>]*globals\\.glsl>[ \\t]*\\r?\\n)"
                    + "[ \\t]*#endif[ \\t]*$",
            Pattern.MULTILINE);

    private static String ensureGlobals(String template, GtProfile profile) {
        // ★ 不能只用 contains("globals.glsl") 判断：26.3 的 item.fsh / entity.fsh 里
        //   那一行被 #ifdef GLINT 包着，非 GLINT 的变体里 Globals 根本不存在。
        //   被这个字符串骗过去的话，作者会在大多数变体上收到"未定义的 GameTime"。
        Matcher guarded = GUARDED_GLOBALS.matcher(template);
        if (guarded.find()) {
            // 把条件解开成无条件引入。不能再插一条新的——两条会让 GLINT 变体里
            // Globals 块被声明两次，那是编译错误
            return template.substring(0, guarded.start())
                    + guarded.group(1).stripTrailing()
                    + template.substring(guarded.end());
        }
        if (Pattern.compile("^[ \\t]*#(?:include|moj_import)\\s*<[^>]*globals\\.glsl>",
                Pattern.MULTILINE).matcher(template).find()) {
            return template;
        }
        String directive = "#include <minecraft:globals.glsl>";

        Matcher m = Pattern.compile("^[ \\t]*#(?:moj_import|include)\\s*<[^>]+>[ \\t]*$",
                Pattern.MULTILINE).matcher(template);
        int at = -1;
        while (m.find()) {
            at = m.end();
        }
        if (at < 0) {
            // 一条 include 都没有：退而求其次，插在 #version（和可能的 #extension）之后
            Matcher head = Pattern.compile("^[ \\t]*#(?:version|extension)\\b.*$",
                    Pattern.MULTILINE).matcher(template);
            while (head.find()) {
                at = head.end();
            }
        }
        if (at < 0) {
            return template;
        }
        return template.substring(0, at) + "\n" + directive + template.substring(at);
    }

    private static int indexOfMain(String source) {
        Matcher m = MAIN_DECL.matcher(source);
        if (!m.find()) {
            throw new IllegalStateException(GtLang.get("gtshaders.core.error.no_main"));
        }
        // 回退到该行行首，注入块才不会把 main 的声明劈成两半
        int at = m.start();
        int lineStart = source.lastIndexOf('\n', at);
        return lineStart < 0 ? at : lineStart + 1;
    }

    private static String spliceVertex(String template) {
        Matcher stmt = GL_POSITION_STMT.matcher(template);
        while (stmt.find()) {
            Matcher expr = POSITION_EXPR.matcher(stmt.group());
            if (expr.find()) {
                String replaced = stmt.group().substring(0, expr.start())
                        + "vec4(" + VERTEX_HOOK + "(" + expr.group(1) + "), 1.0)"
                        + stmt.group().substring(expr.end());
                return template.substring(0, stmt.start()) + replaced + template.substring(stmt.end());
            }
        }
        throw new IllegalStateException(GtLang.get("gtshaders.core.error.no_position"));
    }

    private static String spliceFragment(String template) {
        Matcher m = FRAG_ASSIGN.matcher(template);
        if (!m.find()) {
            throw new IllegalStateException(GtLang.get("gtshaders.core.error.no_frag_color"));
        }
        String rhs = m.group(1).trim();
        return template.substring(0, m.start())
                + "fragColor = " + FRAGMENT_HOOK + "(" + rhs + ");"
                + template.substring(m.end());
    }

    /** 把参数写成一行 {@code const}。核心着色器加不了 uniform 块，只能走这条路。 */
    static String constLine(ShaderParam p) {
        StringBuilder sb = new StringBuilder("const ")
                .append(p.type().glslType()).append(' ').append(p.name()).append(" = ");
        int n = p.type().components();
        if (n == 1) {
            sb.append(p.type().isInteger()
                    ? Integer.toString(Math.round(p.get(0)))
                    : glslFloat(p.get(0)));
        } else {
            sb.append(p.type().glslType()).append('(');
            for (int i = 0; i < n; i++) {
                if (i > 0) {
                    sb.append(", ");
                }
                sb.append(glslFloat(p.get(i)));
            }
            sb.append(')');
        }
        return sb.append(';').toString();
    }

    /** GLSL 的 float 字面量必须带小数点，否则 {@code 1} 会被当成 int 而类型不匹配。 */
    private static String glslFloat(float v) {
        String s = Float.toString(v);
        return s.contains(".") || s.contains("e") || s.contains("E") ? s : s + ".0";
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

    /** 把驱动报的行号换算回作者源码的行号；越界时返回 -1。 */
    public static int toAuthorLine(int compilerLine, int injectedAtLine) {
        int line = compilerLine - injectedAtLine + 1;
        return line >= 1 ? line : -1;
    }

    /** 新建一个核心着色器效果时的起手源码。 */
    public static String defaultBody(ShaderKind.Entry kind) {
        StringBuilder sb = new StringBuilder();
        sb.append("// ").append(kind.displayName()).append('\n');
        if (!kind.displayNote().isBlank()) {
            sb.append("// ").append(kind.displayNote()).append('\n');
        }
        sb.append("//\n");
        // 标题和种类说明已经是界面语言了，下面这几行提示跟着走，别在一份起手源码里混两种语言
        sb.append("// ").append(GtLang.get("gtshaders.core.template.hooks_only")).append('\n');
        sb.append("// ").append(GtLang.get("gtshaders.core.template.params"))
                .append(" // @param name=Amount type=float min=0 max=1 default=0.5\n");
        sb.append("// ").append(GtLang.get("gtshaders.core.template.const_params")).append('\n');
        sb.append('\n');
        if (kind.vertexHook() && kind.hasVertexStage()) {
            sb.append("// ").append(GtLang.get("gtshaders.core.template.vertex_hook")).append('\n');
            sb.append("vec3 ").append(VERTEX_HOOK).append("(vec3 position) {\n");
            sb.append("    return position;\n");
            sb.append("}\n\n");
        }
        if (kind.fragmentHook()) {
            sb.append("// ").append(GtLang.get("gtshaders.core.template.fragment_hook")).append('\n');
            sb.append("vec4 ").append(FRAGMENT_HOOK).append("(vec4 color) {\n");
            sb.append("    return color;\n");
            sb.append("}\n");
        }
        return sb.toString();
    }
}
