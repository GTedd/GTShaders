package mc.GTedd.cn.gtshaders.runtime;

import com.mojang.renderpearl.api.pipeline.ShaderSource;
import com.mojang.renderpearl.api.pipeline.ShaderType;
import com.mojang.renderpearl.backend.api.SpvModule;
import com.mojang.renderpearl.frontend.shaders.GlslCompiler;
import com.mojang.renderpearl.util.ShaderCompileException;
import net.minecraft.client.renderer.ShaderDefines;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 在把源码交给 Minecraft 之前，先自己编译一遍拿到结构化的错误。
 *
 * <p>为什么不直接用原版的编译结果：原版编译失败时只往日志里打一行就返回一个无效管线，
 * 既不抛异常也不把编译器的错误信息交回调用方；而那是私有路径，去 hook 它既脆弱又要
 * 区分 GL / Vulkan 两套后端。
 *
 * <p>自己先编一遍则完全可控：拿到原始日志、解析出行号、把行号换算回作者源码的行号，
 * 而且<b>编译失败时根本不会碰原版的任何状态</b>——画面停在上一个能用的版本上，
 * 不会因为敲错一个分号就黑屏。代价是每次编译多花一次编译时间（毫秒级），很划算。
 *
 * <h2>26.3：必须换成 ShaderC，驱动编译已经不作数了</h2>
 *
 * <p>26.2 的 OpenGL 后端是把 GLSL 原文交给驱动编（{@code glShaderSource} + {@code glCompileShader}），
 * 所以那时用 {@code GL33C} 自己编一遍，和游戏走的是同一个编译器。
 *
 * <p>26.3 起<b>所有后端统一</b>：ShaderC 按 Vulkan GLSL 语义编成 SPIR-V，再由 SPIRV-Cross
 * 反编译成 GLSL 330 交给驱动。于是驱动编译不再等价，而且是<b>两头都不准</b>：
 * <ul>
 *   <li>{@code gl_VertexIndex} 这类 Vulkan 内建在桌面 GL 里不存在，驱动会拒——但游戏里合法；</li>
 *   <li>反过来，桌面 GL 宽容的一些写法 ShaderC 会拒——校验放行了，进游戏才炸。</li>
 * </ul>
 *
 * <p>所以这里改用游戏自己的 {@link GlslCompiler}。它是 {@code renderpearl} 的前端组件，
 * 编译选项（Vulkan 1.2 目标环境、{@code auto_bind_uniforms}、优化级别 0）与游戏逐项一致，
 * 因为用的<b>就是同一个类</b>。
 *
 * <p>顺带的好处：不再需要渲染线程。{@code GlslCompiler} 内部对 shaderc 句柄做了同步池化，
 * 与 GL 上下文无关，所以 AI 那条「编不过就把错误喂回去让它改」的多轮循环不必再占渲染帧。
 */
public final class GlslValidator {

    /**
     * @param line    作者源码中的行号，-1 表示无法定位
     * @param message 驱动给出的错误描述
     * @param error   true 为错误，false 为警告
     */
    public record Issue(int line, String message, boolean error) {
    }

    /**
     * @param ok     是否编译通过
     * @param issues 解析出来的问题列表
     * @param rawLog 驱动原始日志，解析不出来时至少还能给作者看原文
     */
    public record Result(boolean ok, List<Issue> issues, String rawLog) {
        public static Result success() {
            return new Result(true, List.of(), "");
        }

        public String firstErrorMessage() {
            for (Issue i : issues) {
                if (i.error()) {
                    return i.line() > 0
                            ? mc.GTedd.cn.gtshaders.i18n.GtLang.get("gtshaders.status.line", i.line(), i.message())
                            : i.message();
                }
            }
            return rawLog.isBlank()
                    ? mc.GTedd.cn.gtshaders.i18n.GtLang.get("gtshaders.status.no_driver_message")
                    : rawLog;
        }
    }

    // 26.3 起走 ShaderC，报的是这个格式：
    //   gtshaders_preview.fsh:12: error: 'foo' : undeclared identifier
    // 前面可能还挂着一句 Mojang 拼上去的前缀，所以开头用非贪婪匹配吃掉它。
    // 放在最后试：它最宽松，先让下面三条严格的驱动格式有机会命中。
    private static final Pattern SHADERC = Pattern.compile(
            "^.*?:(\\d+):\\s*(error|warning):\\s*(.*)$", Pattern.CASE_INSENSITIVE);
    // 下面三条是驱动直接编 GLSL 时的格式。26.3 上正常路径不会再产生它们，
    // 但 SPIRV-Cross 之后那一段仍然是驱动在编，链接期的问题仍可能以这些形式冒出来。
    // NVIDIA:  0(12) : error C0000: syntax error
    private static final Pattern NVIDIA = Pattern.compile(
            "^\\s*\\d+\\((\\d+)\\)\\s*:\\s*(error|warning)\\b[^:]*:?\\s*(.*)$", Pattern.CASE_INSENSITIVE);
    // AMD / Intel:  ERROR: 0:12: 'foo' : undeclared identifier
    private static final Pattern KHRONOS = Pattern.compile(
            "^\\s*(ERROR|WARNING):\\s*\\d+:(\\d+):\\s*(.*)$", Pattern.CASE_INSENSITIVE);
    // Mesa:  0:12(5): error: ...
    private static final Pattern MESA = Pattern.compile(
            "^\\s*\\d+:(\\d+)\\(\\d+\\)\\s*:\\s*(error|warning)\\s*:\\s*(.*)$", Pattern.CASE_INSENSITIVE);

    private GlslValidator() {
    }

    /** 校验时报给编译器的文件名。它会出现在错误信息的开头，所以要像个文件名。 */
    private static final String VIRTUAL_NAME = "gtshaders_preview.fsh";

    /**
     * 我们生成的后处理着色器<b>不含任何 {@code #include}</b>——{@code Globals} 是内联的，
     * 正是为了让行号完全由我们掌控（展开 include 会插入不确定的行数）。
     *
     * <p>所以这个解析器只负责在真的出现 include 时给一句能看懂的话，而不是让 shaderc
     * 抛一个指向 native 层的错。
     */
    private static final ShaderSource NO_INCLUDES = new ShaderSource() {
        @Override
        public String getShader(Identifier id, ShaderType type) {
            return null;
        }

        @Override
        public ShaderSource.CachedIncludeSource getInclude(Identifier id) {
            return ShaderSource.CachedIncludeSource.createError(
                    mc.GTedd.cn.gtshaders.i18n.GtLang.get("gtshaders.status.include_unsupported", id));
        }

        @Override
        public void close() {
        }
    };

    /**
     * 编译一份片段着色器，只为取回诊断信息，产物立刻丢弃。
     *
     * @param fullSource      完整源码（含生成的头部）
     * @param headerLineCount 头部行数，用于把编译器报的行号换算成作者行号
     */
    public static Result validateFragment(String fullSource, int headerLineCount) {
        // 两个 boolean 是 RENDERPEARL_DEPTH_IS_ZERO_TO_ONE / 顶点绘制参数两个宏的开关。
        // 后处理片段着色器用不到它们，给什么都不影响结果；给 true 是为了和 Vulkan 后端一致，
        // 万一作者真去判断那个宏，看到的也是主流后端的值。
        try (GlslCompiler compiler = new GlslCompiler(true, true);
             SpvModule ignored = compiler.compileToSpv(
                     VIRTUAL_NAME, fullSource, ShaderType.FRAGMENT, ShaderDefines.EMPTY, NO_INCLUDES)) {
            return Result.success();
        } catch (ShaderCompileException e) {
            String log = e.getMessage() == null ? "" : e.getMessage().trim();
            return new Result(false, parse(log, headerLineCount), log);
        } catch (RuntimeException e) {
            // 编译器本身出问题（native 层异常之类）。不能把它当成「着色器写错了」，
            // 否则作者会对着一份没问题的源码找一个不存在的错
            String msg = mc.GTedd.cn.gtshaders.i18n.GtLang.get("gtshaders.status.validator_crashed", String.valueOf(e));
            return new Result(false, List.of(new Issue(-1, msg, true)), msg);
        }
    }

    /** 解析驱动日志。任何一行都对不上格式时，整段日志会作为一条无行号的问题返回。 */
    public static List<Issue> parse(String log, int headerLineCount) {
        List<Issue> issues = new ArrayList<>();
        if (log == null || log.isBlank()) {
            return issues;
        }
        boolean matchedAny = false;
        for (String raw : log.split("\\r?\\n")) {
            if (raw.isBlank()) {
                continue;
            }
            Issue issue = parseLine(raw, headerLineCount);
            if (issue != null) {
                issues.add(issue);
                matchedAny = true;
            }
        }
        if (!matchedAny) {
            issues.add(new Issue(-1, log.trim(), true));
        }
        return issues;
    }

    private static Issue parseLine(String raw, int headerLineCount) {
        Matcher m = NVIDIA.matcher(raw);
        if (m.matches()) {
            return build(m.group(1), m.group(2), m.group(3), headerLineCount);
        }
        m = KHRONOS.matcher(raw);
        if (m.matches()) {
            return build(m.group(2), m.group(1), m.group(3), headerLineCount);
        }
        m = MESA.matcher(raw);
        if (m.matches()) {
            return build(m.group(1), m.group(2), m.group(3), headerLineCount);
        }
        m = SHADERC.matcher(raw);
        if (m.matches()) {
            return build(m.group(1), m.group(2), m.group(3), headerLineCount);
        }
        return null;
    }

    private static Issue build(String lineText, String severity, String message, int headerLineCount) {
        int compilerLine;
        try {
            compilerLine = Integer.parseInt(lineText.trim());
        } catch (NumberFormatException e) {
            compilerLine = -1;
        }
        // 驱动报的是生成后源码的行号，减去头部才是作者看到的行号。
        // 落在头部里说明是我们生成的代码有问题，这时给 -1 而不是一个会误导人的负数行号。
        int authorLine = compilerLine > headerLineCount ? compilerLine - headerLineCount : -1;
        boolean isError = !severity.toLowerCase(java.util.Locale.ROOT).startsWith("warn");
        return new Issue(authorLine, message.trim(), isError);
    }
}
