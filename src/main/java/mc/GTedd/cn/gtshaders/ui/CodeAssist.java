package mc.GTedd.cn.gtshaders.ui;

import mc.GTedd.cn.gtshaders.codegen.GlslLexer;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 代码编辑器里那些「只看文本」的判断：光标所在的词、调用签名、括号配对、查找。
 *
 * <p>和 {@link CodeEditor} 分开，是因为这些全都能脱离 Minecraft 做单元测试，而编辑器本身不行。
 * 所有位置都用<b>整份文本里的偏移</b>，行列换算由编辑器自己做。
 */
public final class CodeAssist {

    private CodeAssist() {
    }

    public static boolean isIdentChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }

    /** 光标左边正在敲的那个标识符前缀；光标前不是标识符字符时为空串。 */
    public static String prefixAt(String text, int offset) {
        int start = offset;
        while (start > 0 && isIdentChar(text.charAt(start - 1))) {
            start--;
        }
        String word = text.substring(start, offset);
        // 数字开头的不是标识符（1.0 里的 0 不该触发补全）
        return !word.isEmpty() && Character.isDigit(word.charAt(0)) ? "" : word;
    }

    /** {@code offset} 所在的整个标识符的 [起, 止)；不在标识符上返回 null。 */
    public static int @Nullable [] wordRange(String text, int offset) {
        if (offset < 0 || offset > text.length()) {
            return null;
        }
        int s = offset;
        int e = offset;
        while (s > 0 && isIdentChar(text.charAt(s - 1))) {
            s--;
        }
        while (e < text.length() && isIdentChar(text.charAt(e))) {
            e++;
        }
        if (s == e || Character.isDigit(text.charAt(s))) {
            return null;
        }
        return new int[]{s, e};
    }

    /**
     * 光标所在的函数调用。
     *
     * @param name     函数名
     * @param argIndex 光标在第几个实参上（0 起）
     */
    public record CallContext(String name, int argIndex) {
    }

    /**
     * 往回找光标外层还没闭合的那个 {@code (}，取它前面的标识符当函数名。
     *
     * <p>跳过注释（{@code // f(} 里的括号不算），逗号只在同一层括号里才算分隔实参。
     * 只往回看同一条语句：遇到 {@code ;} {@code {} {@code }} 就停，避免跨函数配到别处去。
     */
    public static @Nullable CallContext callAt(String text, int offset) {
        List<GlslLexer.Token> code = new ArrayList<>();
        for (GlslLexer.Token t : GlslLexer.tokenize(text.substring(0, Math.max(0, Math.min(offset, text.length()))))) {
            if (t.isCode()) {
                code.add(t);
            }
        }
        if (GlslLexer.isInCommentOrPreprocessor(GlslLexer.tokenize(text), offset)) {
            return null;
        }
        int depth = 0;
        int commas = 0;
        for (int i = code.size() - 1; i >= 0; i--) {
            String s = code.get(i).text();
            switch (s) {
                case ")", "]" -> depth++;
                case "[" -> depth--;
                case "(" -> {
                    if (depth == 0) {
                        if (i > 0 && code.get(i - 1).kind() == GlslLexer.Kind.IDENT) {
                            return new CallContext(code.get(i - 1).text(), commas);
                        }
                        return null;
                    }
                    depth--;
                }
                case "," -> {
                    if (depth == 0) {
                        commas++;
                    }
                }
                case ";", "{", "}" -> {
                    return null;
                }
                default -> {
                }
            }
        }
        return null;
    }

    /**
     * 与 {@code offset} 处（或它左边紧挨着的）括号配对的另一半。
     *
     * @return {@code [本侧偏移, 对侧偏移]}；不在括号上或配不上时返回 null
     */
    public static int @Nullable [] matchBracket(String text, int offset) {
        List<GlslLexer.Token> all = GlslLexer.tokenize(text);
        List<GlslLexer.Token> code = new ArrayList<>();
        for (GlslLexer.Token t : all) {
            if (t.isCode()) {
                code.add(t);
            }
        }
        int idx = -1;
        for (int i = 0; i < code.size(); i++) {
            GlslLexer.Token t = code.get(i);
            if (isBracket(t.text()) && (t.start() == offset || t.start() == offset - 1)) {
                idx = i;
                // 光标右边的优先：「|(」时要配的是右边这个
                if (t.start() == offset) {
                    break;
                }
            }
        }
        if (idx < 0) {
            return null;
        }
        String open = code.get(idx).text();
        String match = partner(open);
        boolean forward = open.equals("(") || open.equals("[") || open.equals("{");
        int depth = 0;
        for (int i = idx; forward ? i < code.size() : i >= 0; i += forward ? 1 : -1) {
            String s = code.get(i).text();
            if (s.equals(open)) {
                depth++;
            } else if (s.equals(match)) {
                depth--;
                if (depth == 0) {
                    return new int[]{code.get(idx).start(), code.get(i).start()};
                }
            }
        }
        return null;
    }

    private static boolean isBracket(String s) {
        return s.length() == 1 && "()[]{}".contains(s);
    }

    private static String partner(String s) {
        return switch (s) {
            case "(" -> ")";
            case ")" -> "(";
            case "[" -> "]";
            case "]" -> "[";
            case "{" -> "}";
            default -> "{";
        };
    }

    /** 所有命中的起始偏移，不重叠。空查询没有命中。 */
    public static List<Integer> findAll(String text, String query, boolean caseSensitive) {
        List<Integer> out = new ArrayList<>();
        if (query == null || query.isEmpty()) {
            return out;
        }
        String hay = caseSensitive ? text : text.toLowerCase(Locale.ROOT);
        String needle = caseSensitive ? query : query.toLowerCase(Locale.ROOT);
        int from = 0;
        while (true) {
            int i = hay.indexOf(needle, from);
            if (i < 0) {
                return out;
            }
            out.add(i);
            from = i + needle.length();
        }
    }

    /**
     * 把第 {@code which} 个命中替换掉；{@code which < 0} 表示全部替换。
     *
     * <p>全部替换从后往前换，前面的偏移才不会跟着漂。
     */
    public static String replace(String text, String query, String replacement, boolean caseSensitive, int which) {
        List<Integer> hits = findAll(text, query, caseSensitive);
        if (hits.isEmpty()) {
            return text;
        }
        StringBuilder sb = new StringBuilder(text);
        if (which >= 0) {
            if (which >= hits.size()) {
                return text;
            }
            int at = hits.get(which);
            return sb.replace(at, at + query.length(), replacement).toString();
        }
        for (int i = hits.size() - 1; i >= 0; i--) {
            int at = hits.get(i);
            sb.replace(at, at + query.length(), replacement);
        }
        return sb.toString();
    }
}
