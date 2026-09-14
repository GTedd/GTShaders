package mc.GTedd.cn.gtshaders.library;

import mc.GTedd.cn.gtshaders.i18n.GtLang;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 从着色器源码头部的注释里读出「这是什么效果」。
 *
 * <h2>为什么解析注释而不是在索引里再写一份描述</h2>
 *
 * <p>描述写进 {@code index.json} 就等于同一件事说了两遍，而作者改效果时改的是源码顶上那段
 * 注释——索引里那份必然慢慢过时，最后变成骗人的。注释本来就是写给人看的，直接拿来用，
 * 库里七十多个效果一行额外维护成本都没有。
 *
 * <p>这也让<b>拖进来的第三方源码</b>白捡一份说明：只要它头上有注释，详情面板就不是空的。
 *
 * <h2>约定的格式</h2>
 *
 * <pre>
 * // 闪光弹 / Flashbang                       ← 标题行，中文 / English
 * // 致盲的三段观感：瞬间全白 → 白色退去…      ← 说明段（中文）
 * // 眼睛的恢复不是线性的，所以用 pow…
 * //                                          ← 空注释行分段
 * // [en_us]                                  ← 语言段标记，之后是英文说明
 * // A flashbang in three stages: …
 * //
 * // @param name=Bleach …                     ← 到此为止
 * </pre>
 *
 * <p>英文说明放在同一段注释里而不是另开文件，理由和上面一样：翻译离原文越远越容易过时。
 * 标记之前的说明视为中文；没有任何标记的文件（第三方源码大多如此）所有语言共用那一份。
 *
 * <p>解析刻意宽容：没有标题行、没有说明、整个文件不带注释，都只是少显示一点东西，
 * 不会抛异常。库里的文件都守这个格式，外面的文件不守也不该炸。
 *
 * @param lang 说明取的是哪门语言的那段，决定 {@link #localTitle()} 取标题的哪一半
 */
public record SourceDoc(String title, String summary, List<String> detail, String lang) {

    public static final SourceDoc EMPTY = new SourceDoc("", "", List.of());

    /** 标题行超过这个长度就当它其实是说明的第一句——真正的标题不会这么长。 */
    private static final int TITLE_MAX = 40;

    /** 出现句子标点就说明这是一句话而不是一个名字。比长度更靠得住：名字不带逗号。 */
    private static final String SENTENCE_MARKS = "，。；：,;";

    /** 语言段标记：{@code [en_us]}。整行只有它才算，免得说明里正好写了个方括号被误判。 */
    private static final Pattern SECTION = Pattern.compile("^\\[([a-z]{2,3}(?:_[a-z]{2,4})?)]$");

    /** 已经按界面语言挑好了说明的文档，比如由种类注解拼出来的那种。 */
    public SourceDoc(String title, String summary, List<String> detail) {
        this(title, summary, detail, GtLang.contentLang());
    }

    public boolean isEmpty() {
        return title.isEmpty() && summary.isEmpty() && detail.isEmpty();
    }

    /**
     * 当前语言的标题。库里的标题写成 {@code 闪光弹 / Flashbang}：中文取斜杠前那半，
     * 其余语言取后半。没有斜杠就原样返回。
     */
    public String localTitle() {
        int i = title.indexOf(" / ");
        if (i <= 0) {
            return title;
        }
        return GtLang.isChinese(lang) ? title.substring(0, i).trim() : title.substring(i + 3).trim();
    }

    /** 按界面语言解析。 */
    public static SourceDoc parse(String source) {
        return parse(source, GtLang.contentLang());
    }

    public static SourceDoc parse(String source, String lang) {
        if (source == null || source.isBlank()) {
            return EMPTY;
        }
        List<String> block = headComment(source);
        if (block.isEmpty()) {
            return EMPTY;
        }

        String title = "";
        int start = 0;
        // 第一行够短、且不像一句话，才当标题。直接开写说明的文件不少，
        // 把一整句塞进标题栏会把详情面板顶头那行撑得很难看
        if (looksLikeTitle(block.get(0))) {
            title = block.get(0);
            start = 1;
        }

        List<String> detail = trimBlank(pickSection(block.subList(start, block.size()), lang));
        return new SourceDoc(title, firstSentence(detail), List.copyOf(detail), lang);
    }

    /**
     * 按语言段标记切开说明，挑出该给这门语言看的那段。
     *
     * <p>顺序：正好是这门语言的段 → 中文变体读标记前那段（约定它是中文）→ 英文段 → 标记前那段。
     * 日语玩家拿到英文而不是中文，是因为英文是 mod 的回退语言，界面文案也是这么退的。
     */
    private static List<String> pickSection(List<String> lines, String lang) {
        Map<String, List<String>> sections = new LinkedHashMap<>();
        List<String> primary = new ArrayList<>();
        List<String> current = primary;
        for (String line : lines) {
            var m = SECTION.matcher(line);
            if (m.matches()) {
                current = sections.computeIfAbsent(m.group(1), k -> new ArrayList<>());
                continue;
            }
            current.add(line);
        }
        String want = lang == null ? GtLang.FALLBACK_LANG : lang.toLowerCase(Locale.ROOT).replace('-', '_');
        List<String> exact = sections.get(want);
        if (exact != null && hasText(exact)) {
            return exact;
        }
        if (GtLang.isChinese(want) && hasText(primary)) {
            return primary;
        }
        List<String> english = sections.get(GtLang.FALLBACK_LANG);
        if (english != null && hasText(english)) {
            return english;
        }
        return primary;
    }

    private static boolean hasText(List<String> lines) {
        return lines.stream().anyMatch(l -> !l.isEmpty());
    }

    /** 前导空行不要，但段落之间的空行保留——详情面板靠它分段。 */
    private static List<String> trimBlank(List<String> lines) {
        int from = 0;
        int to = lines.size();
        while (from < to && lines.get(from).isEmpty()) {
            from++;
        }
        while (to > from && lines.get(to - 1).isEmpty()) {
            to--;
        }
        return lines.subList(from, to);
    }

    private static boolean looksLikeTitle(String line) {
        if (line.isEmpty() || line.length() > TITLE_MAX || SECTION.matcher(line).matches()) {
            return false;
        }
        for (int i = 0; i < line.length(); i++) {
            if (SENTENCE_MARKS.indexOf(line.charAt(i)) >= 0) {
                return false;
            }
        }
        return true;
    }

    /**
     * 取出文件开头那一整块 {@code //} 注释，剥掉标记与 {@code <b>} 这类内联标签。
     *
     * <p>遇到 {@code @param} / {@code @group} 就停：那是给参数扫描器看的，不是给人读的说明。
     */
    private static List<String> headComment(String source) {
        List<String> out = new ArrayList<>();
        for (String raw : source.split("\n", -1)) {
            String line = raw.trim();
            if (line.isEmpty()) {
                // 注释块之前的空行跳过；块内部的空行意味着说明结束
                if (out.isEmpty()) {
                    continue;
                }
                break;
            }
            if (!line.startsWith("//")) {
                break;
            }
            String text = line.substring(2).trim();
            if (text.startsWith("@")) {
                break;
            }
            out.add(clean(text));
        }
        while (!out.isEmpty() && out.get(out.size() - 1).isEmpty()) {
            out.remove(out.size() - 1);
        }
        return out;
    }

    private static String clean(String s) {
        return s.replace("<b>", "").replace("</b>", "")
                .replace("{@code ", "").replace("{@link ", "")
                .replace("}", "").trim();
    }

    /**
     * 摘要 = 说明的第一句。
     *
     * <p>列表里一行只放得下一句话，而库里每篇说明的第一句都是刻意写成「一句话讲清这是什么」的。
     * 中英文句号、分号、冒号都算断句——中文说明常写成「致盲的三段观感：…」，
     * 冒号前那半正是最该显示的部分。英文冒号不断：英文说明的冒号后面往往才是要点。
     */
    private static String firstSentence(List<String> detail) {
        if (detail.isEmpty()) {
            return "";
        }
        String first = detail.get(0);
        int cut = -1;
        for (int i = 0; i < first.length(); i++) {
            char c = first.charAt(i);
            if (c == '。' || c == '；' || c == '：' || c == ';') {
                cut = i;
                break;
            }
            // 英文句点只在后面跟空格或行尾时才算断句，否则 0.5 这种小数会被切开
            if (c == '.' && (i + 1 == first.length() || first.charAt(i + 1) == ' ')) {
                cut = i;
                break;
            }
        }
        return cut > 0 ? first.substring(0, cut) : first;
    }
}
