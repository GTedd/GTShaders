package mc.GTedd.cn.gtshaders.codegen;

/**
 * 粘贴进代码编辑器的文本清洗。
 *
 * <p>从网页、聊天软件、文档里复制来的着色器代码常常夹带 GLSL 不认识的字符，
 * 而它们在编辑器里<b>和正常字符长得几乎一样</b>：全角分号 {@code U+FF1B}、
 * 不换行空格 {@code U+00A0}、零宽空格、BOM、智能引号。驱动只会报一句
 * "unexpected token"，作者盯着那一行怎么看都是对的——这类问题的排查成本极高，
 * 所以在入口处直接换掉，比事后诊断划算得多。
 *
 * <p><b>只清洗代码部分，{@code //} 之后的注释原样保留。</b>
 * 中文注释里的「，。：」是作者想要的排版，不该被顺手改成半角；
 * 而真正会让编译失败的全角符号几乎只出现在代码里。
 * 不可见字符（BOM、零宽、NBSP）则整行都换——它们在任何位置都只会添乱。
 *
 * <p>源码里一律用 {@code \\uXXXX} 转义写这些字符：把不可见字符直接敲进源文件，
 * 下一个人看到的就是一个空白的 case 分支。
 */
public final class GlslSanitizer {

    /**
     * @param text     清洗后的文本
     * @param replaced 被替换或删除的字符数；大于 0 时值得提示作者一声
     */
    public record Result(String text, int replaced) {
    }

    /** 一个制表符展开成几个空格。与编辑器里按 Tab 插入的宽度保持一致。 */
    private static final String TAB = "    ";

    private GlslSanitizer() {
    }

    public static Result sanitize(String input) {
        if (input == null || input.isEmpty()) {
            return new Result("", 0);
        }
        // 换行统一成 \n。CRLF 混进来时行数会算错，编译错误的行号也就跟着错
        int carriageReturns = 0;
        for (int i = 0; i < input.length(); i++) {
            if (input.charAt(i) == '\r') {
                carriageReturns++;
            }
        }
        String normalized = input.replace("\r\n", "\n").replace('\r', '\n');

        String[] lines = normalized.split("\n", -1);
        StringBuilder out = new StringBuilder(normalized.length());
        int replaced = carriageReturns;
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) {
                out.append('\n');
            }
            replaced += appendLine(out, lines[i]);
        }
        return new Result(out.toString(), replaced);
    }

    private static int appendLine(StringBuilder out, String line) {
        int commentAt = line.indexOf("//");
        int changed = 0;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            String mapped = map(c, commentAt >= 0 && i >= commentAt);
            if (mapped == null) {
                out.append(c);
            } else {
                out.append(mapped);
                changed++;
            }
        }
        return changed;
    }

    /** @return 替换后的文本；null 表示这个字符本来就没问题 */
    private static String map(char c, boolean inComment) {
        // ---- 不可见字符：注释里也一样有害，无条件处理 ----
        switch (c) {
            // BOM / 零宽空格 / 零宽非连接符 / 零宽连接符 / word joiner
            case '\uFEFF', '\u200B', '\u200C', '\u200D', '\u2060' -> {
                return "";
            }
            // 不换行空格 / 表意空格（中文输入法下按空格常打出后者）
            case '\u00A0', '\u3000' -> {
                return " ";
            }
            case '\t' -> {
                return TAB;
            }
            default -> {
            }
        }
        if (Character.isISOControl(c)) {
            return "";
        }
        if (inComment) {
            return null;
        }

        // ---- 全角与「智能」标点：只在代码部分换 ----
        if (c >= '\uFF01' && c <= '\uFF5E') {
            // 全角 ASCII 区整体偏移 0xFEE0，一条式子就覆盖了全角的 ！（）；：，'" 等全部符号
            return String.valueOf((char) (c - 0xFEE0));
        }
        return switch (c) {
            case '\u201C', '\u201D', '\u301D', '\u301E' -> "\"";  // 弯引号
            case '\u2018', '\u2019' -> "'";                       // 弯单引号
            case '\u2014', '\u2013', '\u2212' -> "-";             // 破折号 / 连接号 / 减号
            case '\u3002' -> ".";                                 // 句号
            case '\u3001' -> ",";                                 // 顿号
            case '\u2026' -> "...";                               // 省略号
            case '\u3010' -> "[";                                 // 左方头括号
            case '\u3011' -> "]";                                 // 右方头括号
            default -> null;
        };
    }

    /** 单个字符的清洗，供输入法直接打出全角符号时用。 */
    public static String sanitizeChar(int codepoint) {
        if (codepoint > Character.MAX_VALUE) {
            return Character.toString(codepoint);
        }
        String mapped = map((char) codepoint, false);
        return mapped == null ? Character.toString(codepoint) : mapped;
    }
}
