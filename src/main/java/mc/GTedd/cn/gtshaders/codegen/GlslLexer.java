package mc.GTedd.cn.gtshaders.codegen;

import java.util.ArrayList;
import java.util.List;

/**
 * 够用就好的 GLSL 词法切分：只分清「代码、注释、预处理行」三类，外加标识符 / 数字 / 符号。
 *
 * <h2>为什么需要它</h2>
 *
 * <p>调试插桩、异常检查、补全和括号匹配都要回答同一个问题：「这个位置是不是代码」。
 * 用正则按行扫做不到——块注释能跨行，{@code //} 后面的 {@code pow(} 不是调用，
 * {@code #define} 行里插一句语句会把宏整个改坏。SHADERed 的括号匹配就是因为不跳过注释而配错的。
 *
 * <p>刻意不做语法分析：词法这一层足以支撑上面那些用途，而 GLSL 的完整文法要处理宏展开，
 * 那是编译器的事。
 */
public final class GlslLexer {

    public enum Kind {
        IDENT, NUMBER, PUNCT, LINE_COMMENT, BLOCK_COMMENT, PREPROCESSOR
    }

    /**
     * @param start 在源码里的起始偏移
     * @param end   结束偏移（不含）
     * @param line  起始行号，1 起
     */
    public record Token(Kind kind, String text, int start, int end, int line) {
        public boolean isCode() {
            return kind == Kind.IDENT || kind == Kind.NUMBER || kind == Kind.PUNCT;
        }

        public boolean is(String s) {
            return text.equals(s);
        }

        /** 这个 token 的最后一个字符落在哪一行。块注释和带续行的预处理行会跨行。 */
        public int endLine() {
            int n = line;
            for (int i = 0; i < text.length(); i++) {
                if (text.charAt(i) == '\n') {
                    n++;
                }
            }
            return n;
        }
    }

    /** 两个字符的运算符。{@code <<=} 这类三字符的在 GLSL 里罕见，拆成两段对本类的用途没有影响。 */
    private static final String[] TWO_CHAR = {
            "++", "--", "<=", ">=", "==", "!=", "&&", "||", "^^", "+=", "-=", "*=", "/=",
            "%=", "&=", "|=", "^=", "<<", ">>"};

    private GlslLexer() {
    }

    public static List<Token> tokenize(String src) {
        List<Token> out = new ArrayList<>();
        if (src == null) {
            return out;
        }
        int n = src.length();
        int i = 0;
        int line = 1;
        // 预处理指令只在「这一行到目前为止只有空白」时成立
        boolean lineStart = true;
        while (i < n) {
            char c = src.charAt(i);
            if (c == '\n') {
                line++;
                lineStart = true;
                i++;
                continue;
            }
            if (c == ' ' || c == '\t' || c == '\r' || c == '\f') {
                i++;
                continue;
            }
            int start = i;
            int startLine = line;
            if (c == '#' && lineStart) {
                // 一直吃到行尾；反斜杠续行把下一行也算进来
                while (i < n) {
                    char d = src.charAt(i);
                    if (d == '\n') {
                        if (i > start && src.charAt(i - 1) == '\\') {
                            line++;
                            i++;
                            continue;
                        }
                        break;
                    }
                    i++;
                }
                out.add(new Token(Kind.PREPROCESSOR, src.substring(start, i), start, i, startLine));
                continue;
            }
            lineStart = false;
            if (c == '/' && i + 1 < n && src.charAt(i + 1) == '/') {
                while (i < n && src.charAt(i) != '\n') {
                    i++;
                }
                out.add(new Token(Kind.LINE_COMMENT, src.substring(start, i), start, i, startLine));
                continue;
            }
            if (c == '/' && i + 1 < n && src.charAt(i + 1) == '*') {
                i += 2;
                while (i < n && !(src.charAt(i) == '*' && i + 1 < n && src.charAt(i + 1) == '/')) {
                    if (src.charAt(i) == '\n') {
                        line++;
                    }
                    i++;
                }
                // 没闭合的块注释一直算到文件末尾，和编译器的理解一致
                i = Math.min(n, i + 2);
                out.add(new Token(Kind.BLOCK_COMMENT, src.substring(start, i), start, i, startLine));
                continue;
            }
            if (Character.isLetter(c) || c == '_') {
                while (i < n && (Character.isLetterOrDigit(src.charAt(i)) || src.charAt(i) == '_')) {
                    i++;
                }
                out.add(new Token(Kind.IDENT, src.substring(start, i), start, i, startLine));
                continue;
            }
            if (Character.isDigit(c) || (c == '.' && i + 1 < n && Character.isDigit(src.charAt(i + 1)))) {
                while (i < n) {
                    char d = src.charAt(i);
                    boolean exponentSign = (d == '+' || d == '-')
                            && (src.charAt(i - 1) == 'e' || src.charAt(i - 1) == 'E');
                    if (Character.isLetterOrDigit(d) || d == '.' || exponentSign) {
                        i++;
                    } else {
                        break;
                    }
                }
                out.add(new Token(Kind.NUMBER, src.substring(start, i), start, i, startLine));
                continue;
            }
            String two = i + 1 < n ? src.substring(i, i + 2) : "";
            int len = 1;
            for (String op : TWO_CHAR) {
                if (op.equals(two)) {
                    len = 2;
                    break;
                }
            }
            i += len;
            out.add(new Token(Kind.PUNCT, src.substring(start, i), start, i, startLine));
        }
        return out;
    }

    /** 只留代码 token：注释和预处理行都去掉。 */
    public static List<Token> code(String src) {
        List<Token> out = new ArrayList<>();
        for (Token t : tokenize(src)) {
            if (t.isCode()) {
                out.add(t);
            }
        }
        return out;
    }

    /**
     * 偏移 {@code offset} 是否落在注释或预处理行里。
     *
     * <p>落在两个 token 之间的空白算代码——在那里插入或补全都是合法的。
     */
    public static boolean isInCommentOrPreprocessor(List<Token> tokens, int offset) {
        for (Token t : tokens) {
            if (t.start() >= offset) {
                // 行注释的末尾（换行符之前）也仍在注释里：在那里敲字依旧是写注释
                return false;
            }
            if (!t.isCode() && offset <= t.end()) {
                if (t.kind() == Kind.BLOCK_COMMENT && offset == t.end() && t.text().endsWith("*/")) {
                    return false;
                }
                return true;
            }
        }
        return false;
    }
}
