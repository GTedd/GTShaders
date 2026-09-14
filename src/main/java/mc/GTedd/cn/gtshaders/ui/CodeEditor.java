package mc.GTedd.cn.gtshaders.ui;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import mc.GTedd.cn.gtshaders.codegen.GlslLexer;
import mc.GTedd.cn.gtshaders.codegen.GlslSanitizer;
import mc.GTedd.cn.gtshaders.codegen.GlslSymbols;
import mc.GTedd.cn.gtshaders.i18n.GtLang;
import org.jspecify.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * 一个自绘的 GLSL 代码编辑器：光标、选区、滚动、语法高亮、错误行标记、撤销。
 *
 * <p>没有用原版的 {@code MultiLineEditBox}，因为它不支持语法高亮，也无法在指定行画错误底色，
 * 而「点一下错误就跳到出错那行」正是这个工具最需要的能力。自绘的代码量不小，
 * 但换来的是对渲染和输入的完全掌控，也不用跟着原版 widget 的 API 变动走。
 *
 * <p>文本以「行列表」而不是单个大字符串保存：着色、点击定位、错误行标记全都是按行做的，
 * 用行列表可以避免每次绘制都去切分字符串。
 */
public final class CodeEditor {

    private static final int PADDING = 4;
    private static final int GUTTER_WIDTH = 28;
    private static final int MAX_UNDO = 120;
    /** 滚动条的可视/可拖宽度。 */
    private static final int BAR = 4;
    /** 代码区缩放的允许范围。具体档位由 {@link LayoutConfig#integerSteps} 按当前基准算出。 */
    private static final float ZOOM_MIN = 0.5f;
    private static final float ZOOM_MAX = 2.0f;

    private final List<String> lines = new ArrayList<>();
    private final Deque<Snapshot> undoStack = new ArrayDeque<>();
    private final Deque<Snapshot> redoStack = new ArrayDeque<>();
    private final Set<Integer> errorLines = new HashSet<>();

    private int x;
    private int y;
    private int width;
    private int height;

    private int cursorLine;
    private int cursorCol;
    private int anchorLine;
    private int anchorCol;

    private int scrollLine;
    private int scrollPx;
    private boolean focused;
    private Runnable onChange = () -> {
    };
    /**
     * 代码区自己的缩放，独立于编辑器整体缩放。
     *
     * <p>看长表达式时想放大、通读整个 main 时想缩小，这个需求只发生在代码区，
     * 跟着整个界面一起缩放反而会把面板挤变形。
     */
    private float zoom = 1.0f;
    /** 最近一次用过的外层倍数。代码区内部的 Ctrl+0 拿不到它，只能记下来。 */
    private float lastBaseNet = 1.0f;
    /** 上一次粘贴被清洗掉的字符数，供界面提示；读走即清零。 */
    private int lastSanitized;
    /** 正在拖哪条滚动条。 */
    private boolean draggingV;
    private boolean draggingH;
    private int longestLineWidth;
    private boolean contentWidthDirty = true;

    private record Snapshot(List<String> lines, int cursorLine, int cursorCol) {
    }

    // ---- 辅助能力：补全、签名提示、括号配对、查找替换、悬停 ----
    // 思路借鉴 SHADERed（dfranx，MIT）的编辑器，未复制代码。与它不同的是作者名字只做词法扫描，
    // 不依赖「上次编译成功」的反射结果——写到一半编译不过是常态，补全不能在那时失效

    /** 补全弹窗最多列几条。再多一屏也看不完，而且说明挑词挑得不够准。 */
    private static final int COMPLETION_ROWS = 8;
    /** 鼠标停多久才出悬停说明。立即出的话，划过一段代码会一路闪过去。 */
    private static final long HOVER_DWELL_MS = 450;

    /** 作者源码里的名字从哪来。由外面给：参数表在图层上，编辑器只有文本。 */
    private Supplier<List<GlslSymbols.Symbol>> symbolSource = List::of;
    /** 文本每改一次加一。按它缓存各种派生结果，避免每帧重新切词。 */
    private int version;
    private int symbolsVersion = -1;
    private List<GlslSymbols.Symbol> cachedSymbols = List.of();
    private String cachedText = "";
    private int cachedTextVersion = -1;

    private List<GlslSymbols.Symbol> completions = List.of();
    private int completionIndex;
    private int completionCol;

    private int bracketVersion = -1;
    private int bracketCursor = -1;
    private int @Nullable [] bracketPair;
    private int callVersion = -1;
    private int callCursor = -1;
    private CodeAssist.@Nullable CallContext cachedCall;

    private final Map<Integer, String> errorMessages = new HashMap<>();

    private boolean findOpen;
    private boolean replaceOpen;
    /** 查找条里哪个框有焦点：0 查找，1 替换，-1 焦点在代码上。 */
    private int findFocus = -1;
    private final StringBuilder findQuery = new StringBuilder();
    private final StringBuilder replaceText = new StringBuilder();
    private boolean findCase;
    private int findIndex;
    /** 查找条上各个按钮这一帧的位置（内容坐标）：{x, y, w, h, 动作编号}。 */
    private final List<int[]> findButtons = new ArrayList<>();
    private int[] findBarRect = new int[4];

    private double hoverX = -1;
    private double hoverY = -1;
    private long hoverSince;

    /** 悬停到的东西：一个词，或者一行的报错。 */
    public record Hover(@Nullable String word, int line, @Nullable String error) {
    }

    public CodeEditor() {
        lines.add("");
    }

    /** 作者源码里的名字（函数、变量、参数）。补全、悬停、Ctrl+点击跳转都从这里取。 */
    public void setSymbolSource(Supplier<List<GlslSymbols.Symbol>> source) {
        this.symbolSource = source == null ? List::of : source;
        this.symbolsVersion = -1;
    }

    public List<GlslSymbols.Symbol> userSymbols() {
        if (symbolsVersion != version) {
            cachedSymbols = symbolSource.get();
            symbolsVersion = version;
        }
        return cachedSymbols;
    }

    private String text() {
        if (cachedTextVersion != version) {
            cachedText = String.join("\n", lines);
            cachedTextVersion = version;
        }
        return cachedText;
    }

    private int offsetOf(int line, int col) {
        int off = 0;
        for (int i = 0; i < line && i < lines.size(); i++) {
            off += lines.get(i).length() + 1;
        }
        return off + col;
    }

    private int[] posOf(int offset) {
        int line = 0;
        int rest = offset;
        while (line < lines.size() - 1 && rest > lines.get(line).length()) {
            rest -= lines.get(line).length() + 1;
            line++;
        }
        return new int[]{line, clamp(rest, 0, lines.get(line).length())};
    }

    /**
     * 界面上别处改了源码（例如开关参数驱动器）：整份替换，但保留光标与滚动位置，并且可以撤销。
     * 和 {@link #setText} 的区别正在这两点——那个是换图层时用的，撤销栈要清掉。
     */
    public void applyExternalEdit(String text) {
        if (text == null || text.equals(getText())) {
            return;
        }
        pushUndo();
        replaceAllLines(text);
        completions = List.of();
        fireChange();
    }

    /** 光标所在行，1 起。 */
    public int cursorLine1() {
        return cursorLine + 1;
    }

    /**
     * 探针要看的表达式：有单行选区就用选区，否则取光标所在的词。
     *
     * @return 取不到时为空串
     */
    public String probeExpression() {
        if (hasSelection()) {
            int[] s = selectionStart();
            int[] e = selectionEnd();
            if (s[0] == e[0]) {
                return safeSubstring(lines.get(s[0]), s[1], e[1]).strip();
            }
            return "";
        }
        int[] r = CodeAssist.wordRange(lines.get(cursorLine), cursorCol);
        return r == null ? "" : lines.get(cursorLine).substring(r[0], r[1]);
    }

    /** 各行的报错原文（1 起），悬停在那一行时显示。 */
    public void setErrorMessages(Map<Integer, String> messages) {
        errorMessages.clear();
        if (messages != null) {
            errorMessages.putAll(messages);
        }
    }

    public void setOnChange(Runnable onChange) {
        this.onChange = onChange == null ? () -> {
        } : onChange;
    }

    public void setBounds(int x, int y, int width, int height) {
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
    }

    public void setFocused(boolean focused) {
        // 同 TextField：SDL 的文本输入要显式开关，否则代码编辑器同样只能删不能打
        if (focused) {
            TextInputGate.begin(this);
        } else {
            TextInputGate.end(this);
        }
        this.focused = focused;
    }

    public boolean isFocused() {
        return focused;
    }

    public void setText(String text) {
        lines.clear();
        for (String l : text.split("\n", -1)) {
            lines.add(l);
        }
        if (lines.isEmpty()) {
            lines.add("");
        }
        clampCursor();
        scrollPx = 0;
        contentWidthDirty = true;
        undoStack.clear();
        redoStack.clear();
        version++;
        completions = List.of();
    }

    public String getText() {
        return String.join("\n", lines);
    }

    /** 设置要高亮的错误行（1 起）。传空集合即清除。 */
    public void setErrorLines(Set<Integer> errors) {
        errorLines.clear();
        if (errors != null) {
            errorLines.addAll(errors);
        }
    }

    /** 把光标移到指定行（1 起）并滚动到可见位置——点击错误条目时用。 */
    public void gotoLine(int line1Based, Font font) {
        int idx = Math.max(0, Math.min(lines.size() - 1, line1Based - 1));
        cursorLine = idx;
        cursorCol = 0;
        anchorLine = idx;
        anchorCol = 0;
        int visible = visibleRows(font);
        // 让目标行落在视口中部，而不是紧贴顶部——上下文比"刚好可见"更有用。
        scrollLine = Math.max(0, idx - visible / 2);
    }

    // ------------------------------------------------------------------ 渲染

    /**
     * 代码区在<b>内容坐标</b>下的尺寸。缩放靠一层 pose 矩阵实现，
     * 于是内部所有布局都可以当作「原点在左上、没有缩放」来算，
     * 只有鼠标坐标需要在入口处换算一次。
     */
    private int contentW() {
        return Math.max(40, Math.round(width / zoom));
    }

    private int contentH() {
        return Math.max(20, Math.round(height / zoom));
    }

    private int textLeft() {
        return GUTTER_WIDTH + PADDING;
    }

    private int textWidth() {
        return Math.max(20, contentW() - GUTTER_WIDTH - PADDING * 2 - BAR);
    }

    public void render(GuiGraphicsExtractor g, Font font, int mouseX, int mouseY) {
        int cw = contentW();
        int ch = contentH();

        g.pose().pushMatrix();
        g.pose().translate((float) x, (float) y);
        g.pose().scale(zoom, zoom);

        g.fill(0, 0, cw, ch, Theme.CODE_BG);
        g.fill(0, 0, GUTTER_WIDTH, ch, Theme.CODE_GUTTER_BG);

        int rows = visibleRows(font);
        int lineH = font.lineHeight + 1;
        int textX = textLeft();

        clampScroll(font);

        List<int[]> hits = findOpen ? findHitPositions() : List.of();
        int[] brackets = focused ? bracketPositions() : null;

        // 裁剪跟随 pose 变换（原版 enableScissor 内部会用当前矩阵换算），所以直接用内容坐标
        g.enableScissor(0, 0, cw, ch);
        for (int r = 0; r < rows; r++) {
            int li = scrollLine + r;
            if (li >= lines.size()) {
                break;
            }
            int ly = PADDING + r * lineH;
            String line = lines.get(li);

            if (errorLines.contains(li + 1)) {
                g.fill(GUTTER_WIDTH, ly - 1, cw, ly + lineH - 1, Theme.CODE_ERROR_LINE);
            } else if (li == cursorLine && focused) {
                g.fill(GUTTER_WIDTH, ly - 1, cw, ly + lineH - 1, Theme.CODE_CURSOR_LINE);
            }

            drawSelection(g, font, li, ly, textX, lineH);
            for (int h = 0; h < hits.size(); h++) {
                int[] hit = hits.get(h);
                if (hit[0] != li) {
                    continue;
                }
                int hx0 = textX - scrollPx + font.width(safeSubstring(line, 0, hit[1]));
                int hx1 = textX - scrollPx + font.width(safeSubstring(line, 0, hit[1] + hit[2]));
                g.fill(hx0, ly - 1, hx1, ly + lineH - 1, h == findIndex ? 0x80E0A030 : 0x40E0A030);
            }
            if (brackets != null) {
                for (int b = 0; b < 2; b++) {
                    if (brackets[b * 2] != li) {
                        continue;
                    }
                    int col = brackets[b * 2 + 1];
                    int bx0 = textX - scrollPx + font.width(safeSubstring(line, 0, col));
                    int bx1 = textX - scrollPx + font.width(safeSubstring(line, 0, col + 1));
                    UiUtil.box(g, bx0 - 1, ly - 1, bx1 - bx0 + 2, lineH, Theme.ACCENT);
                }
            }
            drawHighlightedLine(g, font, line, textX - scrollPx, ly);
        }

        // 光标：闪烁靠系统时间，避免自己维护计时器
        if (focused && (System.currentTimeMillis() / 500) % 2 == 0) {
            int cr = cursorLine - scrollLine;
            if (cr >= 0 && cr < rows) {
                int cx = textX - scrollPx + font.width(safeSubstring(lines.get(cursorLine), 0, cursorCol));
                int cy = PADDING + cr * lineH;
                g.fill(cx, cy - 1, cx + 1, cy + font.lineHeight, Theme.TEXT);
            }
        }
        g.disableScissor();

        // 行号单独画在裁剪之外、且不受横向滚动影响——代码往左滚时行号必须钉在原地
        for (int r = 0; r < rows; r++) {
            int li = scrollLine + r;
            if (li >= lines.size()) {
                break;
            }
            String no = Integer.toString(li + 1);
            g.text(font, no, GUTTER_WIDTH - PADDING - font.width(no), PADDING + r * lineH,
                    errorLines.contains(li + 1) ? Theme.ERROR : Theme.CODE_LINE_NO, false);
        }

        drawScrollbars(g, font);
        if (findOpen) {
            drawFindBar(g, font);
        }
        if (focused && !completions.isEmpty()) {
            drawCompletions(g, font);
        } else if (focused) {
            drawSignature(g, font);
        }
        g.pose().popMatrix();
    }

    // ------------------------------------------------------------------ 辅助能力：绘制

    /** 当前可见的查找命中：{行, 列, 长度}。 */
    private List<int[]> findHitPositions() {
        List<int[]> out = new ArrayList<>();
        String q = findQuery.toString();
        for (int off : CodeAssist.findAll(text(), q, findCase)) {
            int[] p = posOf(off);
            out.add(new int[]{p[0], p[1], q.length()});
        }
        if (findIndex >= out.size()) {
            findIndex = out.isEmpty() ? 0 : out.size() - 1;
        }
        return out;
    }

    /** 与光标挨着的那对括号：{行1, 列1, 行2, 列2}。按文本版本与光标位置缓存。 */
    private int @Nullable [] bracketPositions() {
        int cursor = offsetOf(cursorLine, cursorCol);
        if (bracketVersion != version || bracketCursor != cursor) {
            bracketPair = CodeAssist.matchBracket(text(), cursor);
            bracketVersion = version;
            bracketCursor = cursor;
        }
        if (bracketPair == null) {
            return null;
        }
        int[] a = posOf(bracketPair[0]);
        int[] b = posOf(bracketPair[1]);
        return new int[]{a[0], a[1], b[0], b[1]};
    }

    private int kindColor(GlslSymbols.Kind kind) {
        return switch (kind) {
            case FUNCTION, VARIABLE, PARAM -> Theme.TEXT;
            case HELPER, BUILTIN_VALUE -> Theme.SYN_BUILTIN;
            case GLSL_FUNCTION -> Theme.SYN_TYPE;
            case KEYWORD -> Theme.SYN_KEYWORD;
        };
    }

    private void drawCompletions(GuiGraphicsExtractor g, Font font) {
        int lineH = font.lineHeight + 1;
        int row = cursorLine - scrollLine;
        if (row < 0 || row >= visibleRows(font)) {
            return;
        }
        String line = lines.get(cursorLine);
        int nameW = 0;
        int detailW = 0;
        for (GlslSymbols.Symbol s : completions) {
            nameW = Math.max(nameW, font.width(s.name()));
            detailW = Math.max(detailW, font.width(s.detail()));
        }
        int w = Math.min(contentW() - 8, Math.max(120, nameW + Math.min(detailW, 180) + 16));
        int h = completions.size() * lineH + 4;
        GlslSymbols.Symbol selected = completions.get(Math.min(completionIndex, completions.size() - 1));
        boolean hasDoc = !selected.doc().isEmpty();
        if (hasDoc) {
            h += lineH + 2;
        }
        int px = textLeft() - scrollPx + font.width(safeSubstring(line, 0, completionCol));
        px = clamp(px, 2, Math.max(2, contentW() - w - 2));
        int py = PADDING + (row + 1) * lineH + 1;
        if (py + h > contentH()) {
            py = Math.max(0, PADDING + row * lineH - h - 1);
        }
        g.fill(px, py, px + w, py + h, Theme.PANEL);
        UiUtil.box(g, px, py, w, h, Theme.BORDER_STRONG);
        for (int i = 0; i < completions.size(); i++) {
            GlslSymbols.Symbol s = completions.get(i);
            int ry = py + 2 + i * lineH;
            if (i == completionIndex) {
                g.fill(px + 1, ry - 1, px + w - 1, ry + lineH - 1, Theme.ACCENT_SOFT);
            }
            g.text(font, s.name(), px + 4, ry, kindColor(s.kind()), false);
            int dx = px + 8 + nameW;
            int room = px + w - 4 - dx;
            if (room > 12 && !s.detail().isEmpty()) {
                g.text(font, UiUtil.ellipsize(font, s.detail(), room), dx, ry, Theme.TEXT_DIM, false);
            }
        }
        if (hasDoc) {
            int dy = py + 2 + completions.size() * lineH + 1;
            UiUtil.hLine(g, px + 1, px + w - 1, dy - 1, Theme.BORDER);
            g.text(font, UiUtil.ellipsize(font, selected.doc(), w - 8), px + 4, dy + 1, Theme.TEXT_SUB, false);
        }
    }

    /** 光标在一次调用的括号里时，在上方显示签名，当前实参高亮。 */
    private void drawSignature(GuiGraphicsExtractor g, Font font) {
        int row = cursorLine - scrollLine;
        if (row < 0 || row >= visibleRows(font) || hasSelection()) {
            return;
        }
        int cursor = offsetOf(cursorLine, cursorCol);
        if (callVersion != version || callCursor != cursor) {
            cachedCall = CodeAssist.callAt(text(), cursor);
            callVersion = version;
            callCursor = cursor;
        }
        CodeAssist.CallContext call = cachedCall;
        if (call == null) {
            return;
        }
        GlslSymbols.Symbol sym = GlslSymbols.lookup(call.name(), userSymbols());
        if (sym == null || sym.signatures().isEmpty()) {
            return;
        }
        // 多个重载时挑实参个数够用的第一个
        String sig = sym.signatures().get(0);
        for (String candidate : sym.signatures()) {
            if (argCount(candidate) > call.argIndex()) {
                sig = candidate;
                break;
            }
        }
        int lineH = font.lineHeight + 1;
        int open = sig.indexOf('(');
        int close = sig.lastIndexOf(')');
        if (open < 0 || close < open) {
            return;
        }
        List<String> parts = new ArrayList<>();
        parts.add(sig.substring(0, open + 1));
        String[] args = sig.substring(open + 1, close).split(",", -1);
        for (int i = 0; i < args.length; i++) {
            parts.add((i > 0 ? "," : "") + args[i]);
        }
        parts.add(sig.substring(close));
        int total = 0;
        for (String p : parts) {
            total += font.width(p);
        }
        String more = sym.signatures().size() > 1 ? "  (+" + (sym.signatures().size() - 1) + ")" : "";
        int w = Math.min(contentW() - 8, total + font.width(more) + 8);
        int px = clamp(textLeft() - scrollPx + font.width(safeSubstring(lines.get(cursorLine), 0, cursorCol)) - 12,
                2, Math.max(2, contentW() - w - 2));
        int py = PADDING + row * lineH - lineH - 3;
        if (py < 0) {
            py = PADDING + (row + 1) * lineH + 1;
        }
        g.fill(px, py, px + w, py + lineH + 2, Theme.PANEL);
        UiUtil.box(g, px, py, w, lineH + 2, Theme.BORDER);
        int tx = px + 4;
        g.enableScissor(px, py, px + w - 2, py + lineH + 2);
        for (int i = 0; i < parts.size(); i++) {
            boolean active = i - 1 == call.argIndex() && i > 0 && i < parts.size() - 1;
            String p = parts.get(i);
            g.text(font, p, tx, py + 2, active ? Theme.ACCENT : Theme.TEXT_SUB, false);
            tx += font.width(p);
        }
        g.text(font, more, tx, py + 2, Theme.TEXT_DIM, false);
        g.disableScissor();
    }

    private static int argCount(String sig) {
        int open = sig.indexOf('(');
        int close = sig.lastIndexOf(')');
        if (open < 0 || close <= open + 1) {
            return 0;
        }
        return sig.substring(open + 1, close).split(",", -1).length;
    }

    private static final int FIND_PREV = 1;
    private static final int FIND_NEXT = 2;
    private static final int FIND_CASE = 3;
    private static final int FIND_CLOSE = 4;
    private static final int FIND_REPLACE = 5;
    private static final int FIND_REPLACE_ALL = 6;
    private static final int FIND_FOCUS_QUERY = 7;
    private static final int FIND_FOCUS_REPLACE = 8;

    private void drawFindBar(GuiGraphicsExtractor g, Font font) {
        findButtons.clear();
        int lineH = font.lineHeight + 1;
        int rowH = lineH + 6;
        int w = Math.min(contentW() - GUTTER_WIDTH - 6, 260);
        int h = rowH * (replaceOpen ? 2 : 1) + 4;
        int px = contentW() - BAR - w - 2;
        int py = 2;
        findBarRect = new int[]{px, py, w, h};
        g.fill(px, py, px + w, py + h, Theme.PANEL);
        UiUtil.box(g, px, py, w, h, Theme.BORDER_STRONG);

        List<int[]> hits = findHitPositions();
        String count = findQuery.isEmpty() ? ""
                : hits.isEmpty() ? GtLang.get("gtshaders.editor.find.none")
                : (findIndex + 1) + "/" + hits.size();
        String[] icons = {"<", ">", "Aa", "x"};
        int[] actions = {FIND_PREV, FIND_NEXT, FIND_CASE, FIND_CLOSE};
        int bw = 14;
        int buttonsW = bw * icons.length + 2;
        int countW = font.width(count) + 6;
        int fieldW = Math.max(40, w - buttonsW - countW - 8);

        int ry = py + 2;
        drawFindField(g, font, px + 3, ry, fieldW, rowH - 2, findQuery.toString(),
                GtLang.get("gtshaders.editor.find.placeholder"), findFocus == 0);
        findButtons.add(new int[]{px + 3, ry, fieldW, rowH - 2, FIND_FOCUS_QUERY});
        g.text(font, count, px + 3 + fieldW + 4, ry + 3, hits.isEmpty() && !findQuery.isEmpty()
                ? Theme.ERROR : Theme.TEXT_DIM, false);
        int bx = px + w - buttonsW;
        for (int i = 0; i < icons.length; i++) {
            boolean on = actions[i] == FIND_CASE && findCase;
            if (on) {
                g.fill(bx, ry, bx + bw - 1, ry + rowH - 2, Theme.BUTTON_ACTIVE_BG);
            }
            g.text(font, icons[i], bx + (bw - font.width(icons[i])) / 2, ry + 3,
                    on ? Theme.ACCENT : Theme.TEXT_SUB, false);
            findButtons.add(new int[]{bx, ry, bw, rowH - 2, actions[i]});
            bx += bw;
        }

        if (replaceOpen) {
            ry += rowH;
            String one = GtLang.get("gtshaders.editor.find.replace");
            String all = GtLang.get("gtshaders.editor.find.replace_all");
            int oneW = font.width(one) + 8;
            int allW = font.width(all) + 8;
            int rw = Math.max(40, w - oneW - allW - 10);
            drawFindField(g, font, px + 3, ry, rw, rowH - 2, replaceText.toString(),
                    GtLang.get("gtshaders.editor.find.replace_placeholder"), findFocus == 1);
            findButtons.add(new int[]{px + 3, ry, rw, rowH - 2, FIND_FOCUS_REPLACE});
            int ax = px + 3 + rw + 3;
            for (String label : new String[]{one, all}) {
                int lw = font.width(label) + 8;
                g.fill(ax, ry, ax + lw - 1, ry + rowH - 2, Theme.BUTTON_BG);
                g.text(font, label, ax + 4, ry + 3, Theme.TEXT_SUB, false);
                findButtons.add(new int[]{ax, ry, lw, rowH - 2, label.equals(one) ? FIND_REPLACE : FIND_REPLACE_ALL});
                ax += lw + 2;
            }
        }
    }

    private void drawFindField(GuiGraphicsExtractor g, Font font, int fx, int fy, int fw, int fh,
                               String value, String placeholder, boolean active) {
        g.fill(fx, fy, fx + fw, fy + fh, active ? Theme.INPUT_BG_FOCUS : Theme.INPUT_BG);
        if (active) {
            UiUtil.box(g, fx, fy, fw, fh, Theme.ACCENT);
        }
        g.enableScissor(fx + 1, fy, fx + fw - 1, fy + fh);
        if (value.isEmpty()) {
            g.text(font, placeholder, fx + 3, fy + 3, Theme.TEXT_DIM, false);
        } else {
            // 放不下时让末尾可见：正在敲的永远是最后那几个字
            int tw = font.width(value);
            int tx = fx + 3 - Math.max(0, tw - (fw - 8));
            g.text(font, value, tx, fy + 3, Theme.TEXT, false);
            if (active && (System.currentTimeMillis() / 500) % 2 == 0) {
                g.fill(tx + tw, fy + 2, tx + tw + 1, fy + fh - 2, Theme.TEXT);
            }
        }
        g.disableScissor();
    }

    private void drawHighlightedLine(GuiGraphicsExtractor g, Font font, String line, int startX, int ly) {
        if (line.isEmpty()) {
            return;
        }
        int cx = startX;
        for (GlslHighlighter.Span span : GlslHighlighter.highlight(line)) {
            String part = safeSubstring(line, span.start(), span.end());
            if (part.isEmpty()) {
                continue;
            }
            g.text(font, part, cx, ly, span.color(), false);
            cx += font.width(part);
        }
    }

    private void drawSelection(GuiGraphicsExtractor g, Font font, int li, int ly, int textX, int lineH) {
        if (!hasSelection()) {
            return;
        }
        int[] s = selectionStart();
        int[] e = selectionEnd();
        if (li < s[0] || li > e[0]) {
            return;
        }
        String line = lines.get(li);
        int from = li == s[0] ? s[1] : 0;
        int to = li == e[0] ? e[1] : line.length();
        int px0 = textX - scrollPx + font.width(safeSubstring(line, 0, from));
        int px1 = textX - scrollPx + font.width(safeSubstring(line, 0, to));
        // 跨行选区里的空行也要有可见宽度，否则看起来像是漏选了
        if (px1 == px0 && li != e[0]) {
            px1 = px0 + 3;
        }
        g.fill(px0, ly - 1, px1, ly + lineH - 1, Theme.CODE_SELECTION);
    }

    private void drawScrollbars(GuiGraphicsExtractor g, Font font) {
        int cw = contentW();
        int ch = contentH();
        int rows = visibleRows(font);

        if (lines.size() > rows) {
            int trackX = cw - BAR;
            g.fill(trackX, 0, trackX + BAR, ch, Theme.SCROLL_TRACK);
            int barH = Math.max(14, (ch - BAR) * rows / lines.size());
            int maxScroll = lines.size() - rows;
            int barY = (ch - BAR - barH) * Math.min(scrollLine, maxScroll) / Math.max(1, maxScroll);
            g.fill(trackX, barY, trackX + BAR, barY + barH,
                    draggingV ? Theme.ACCENT : Theme.SCROLL_THUMB);
        }

        int maxX = maxScrollPx(font);
        if (maxX > 0) {
            // 横向滚动条只在真的有内容超出时出现，否则平白占掉一行高度
            int trackY = ch - BAR;
            int trackW = cw - GUTTER_WIDTH - BAR;
            g.fill(GUTTER_WIDTH, trackY, GUTTER_WIDTH + trackW, trackY + BAR, Theme.SCROLL_TRACK);
            int total = textWidth() + maxX;
            int barW = Math.max(20, trackW * textWidth() / Math.max(1, total));
            int barX = GUTTER_WIDTH + (trackW - barW) * Math.min(scrollPx, maxX) / maxX;
            g.fill(barX, trackY, barX + barW, trackY + BAR,
                    draggingH ? Theme.ACCENT : Theme.SCROLL_THUMB);
        }
    }

    /**
     * 横向能滚多远。
     *
     * <p>最长行的宽度按需重算并缓存：每帧对所有行调 {@code font.width} 太浪费，
     * 而只统计可见行又会让滚动条粗细随滚动跳来跳去。
     */
    private int maxScrollPx(Font font) {
        if (contentWidthDirty) {
            int max = 0;
            for (String line : lines) {
                max = Math.max(max, font.width(line));
            }
            longestLineWidth = max;
            contentWidthDirty = false;
        }
        return Math.max(0, longestLineWidth + PADDING * 2 - textWidth());
    }

    private void clampScroll(Font font) {
        scrollLine = clamp(scrollLine, 0, Math.max(0, lines.size() - visibleRows(font)));
        scrollPx = clamp(scrollPx, 0, maxScrollPx(font));
    }

    // ------------------------------------------------------------------ 输入

    public boolean isOver(double mx, double my) {
        return mx >= x && mx < x + width && my >= y && my < y + height;
    }

    /** 屏幕坐标 → 代码区内容坐标。缩放之后所有命中测试都必须先过这一步。 */
    private double localX(double mx) {
        return (mx - x) / zoom;
    }

    private double localY(double my) {
        return (my - y) / zoom;
    }

    public boolean mouseClicked(double mx, double my, Font font, boolean extendSelection) {
        return mouseClicked(mx, my, font, extendSelection, false);
    }

    /** @param ctrl Ctrl+点击：跳到作者源码里这个名字的定义处 */
    public boolean mouseClicked(double mx, double my, Font font, boolean extendSelection, boolean ctrl) {
        if (!isOver(mx, my)) {
            return false;
        }
        focused = true;
        TextInputGate.begin(this);
        double lx = localX(mx);
        double ly = localY(my);
        completions = List.of();

        if (findOpen && lx >= findBarRect[0] && lx < findBarRect[0] + findBarRect[2]
                && ly >= findBarRect[1] && ly < findBarRect[1] + findBarRect[3]) {
            for (int[] b : findButtons) {
                if (lx >= b[0] && lx < b[0] + b[2] && ly >= b[1] && ly < b[1] + b[3]) {
                    findAction(b[4], font);
                    break;
                }
            }
            return true;
        }
        findFocus = -1;

        // 滚动条优先：它压在文本区边缘上，先判定才抓得住
        if (lines.size() > visibleRows(font) && lx >= contentW() - BAR) {
            draggingV = true;
            dragVerticalTo(ly, font);
            return true;
        }
        if (maxScrollPx(font) > 0 && ly >= contentH() - BAR && lx >= GUTTER_WIDTH) {
            draggingH = true;
            dragHorizontalTo(lx, font);
            return true;
        }

        placeCursorAt(lx, ly, font);
        if (!extendSelection) {
            anchorLine = cursorLine;
            anchorCol = cursorCol;
        }
        if (ctrl) {
            int[] r = CodeAssist.wordRange(lines.get(cursorLine), cursorCol);
            if (r != null) {
                GlslSymbols.Symbol s = GlslSymbols.lookup(lines.get(cursorLine).substring(r[0], r[1]), userSymbols());
                if (s != null && s.line() > 0) {
                    gotoLine(s.line(), font);
                }
            }
        }
        return true;
    }

    /**
     * 鼠标悬停在代码区上的内容。停够 {@link #HOVER_DWELL_MS} 才返回，划过去的时候不打扰。
     *
     * @return 没停够、不在代码上或者什么都没指着时返回 null
     */
    public @Nullable Hover hover(double mx, double my, Font font) {
        if (!isOver(mx, my) || (findOpen && localY(my) < findBarRect[1] + findBarRect[3]
                && localX(mx) >= findBarRect[0])) {
            hoverX = -1;
            return null;
        }
        long now = System.currentTimeMillis();
        if (Math.abs(mx - hoverX) > 2 || Math.abs(my - hoverY) > 2) {
            hoverX = mx;
            hoverY = my;
            hoverSince = now;
            return null;
        }
        if (now - hoverSince < HOVER_DWELL_MS || !completions.isEmpty()) {
            return null;
        }
        double lx = localX(mx);
        double ly = localY(my);
        int lineH = font.lineHeight + 1;
        int li = scrollLine + (int) ((ly - PADDING) / lineH);
        if (li < 0 || li >= lines.size()) {
            return null;
        }
        String error = errorMessages.get(li + 1);
        if (lx < GUTTER_WIDTH) {
            return error == null ? null : new Hover(null, li + 1, error);
        }
        String line = lines.get(li);
        int targetX = (int) (lx - textLeft() + scrollPx);
        if (targetX < 0 || targetX > font.width(line)) {
            return error == null ? null : new Hover(null, li + 1, error);
        }
        int col = columnForX(line, targetX, font);
        // columnForX 取最近的字符边界，指着一个字母的右半边时会落到它后面，所以两侧都试
        int[] r = CodeAssist.wordRange(line, col);
        String word = null;
        if (r != null && !GlslLexer.isInCommentOrPreprocessor(GlslLexer.tokenize(text()), offsetOf(li, r[0]))) {
            word = line.substring(r[0], r[1]);
        }
        if (word == null && error == null) {
            return null;
        }
        return new Hover(word, li + 1, error);
    }

    // ------------------------------------------------------------------ 辅助能力：输入

    /** 打开查找条。有单行选区时把选区填进去，这是「查找选中的词」最快的一条路。 */
    public void openFind(boolean withReplace) {
        findOpen = true;
        replaceOpen = replaceOpen || withReplace;
        findFocus = withReplace && !findQuery.isEmpty() ? 1 : 0;
        if (hasSelection()) {
            int[] s = selectionStart();
            int[] e = selectionEnd();
            if (s[0] == e[0]) {
                findQuery.setLength(0);
                findQuery.append(safeSubstring(lines.get(s[0]), s[1], e[1]));
                findFocus = withReplace ? 1 : 0;
            }
        }
        findIndex = 0;
    }

    public boolean isFindOpen() {
        return findOpen;
    }

    private void findAction(int action, Font font) {
        switch (action) {
            case FIND_PREV -> stepFind(-1, font);
            case FIND_NEXT -> stepFind(1, font);
            case FIND_CASE -> {
                findCase = !findCase;
                findIndex = 0;
            }
            case FIND_CLOSE -> {
                findOpen = false;
                findFocus = -1;
            }
            case FIND_REPLACE -> replaceCurrent(font);
            case FIND_REPLACE_ALL -> {
                String next = CodeAssist.replace(text(), findQuery.toString(), replaceText.toString(), findCase, -1);
                if (!next.equals(text())) {
                    pushUndo();
                    replaceAllLines(next);
                    fireChange();
                }
            }
            case FIND_FOCUS_QUERY -> findFocus = 0;
            case FIND_FOCUS_REPLACE -> findFocus = 1;
            default -> {
            }
        }
    }

    private void stepFind(int delta, Font font) {
        List<Integer> hits = CodeAssist.findAll(text(), findQuery.toString(), findCase);
        if (hits.isEmpty()) {
            return;
        }
        findIndex = Math.floorMod(findIndex + delta, hits.size());
        selectHit(hits.get(findIndex), findQuery.length(), font);
    }

    private void selectHit(int offset, int length, Font font) {
        int[] a = posOf(offset);
        int[] b = posOf(offset + length);
        anchorLine = a[0];
        anchorCol = a[1];
        cursorLine = b[0];
        cursorCol = b[1];
        int visible = visibleRows(font);
        if (cursorLine < scrollLine || cursorLine >= scrollLine + visible) {
            scrollLine = Math.max(0, cursorLine - visible / 2);
        }
        ensureVisible(font);
    }

    private void replaceCurrent(Font font) {
        List<Integer> hits = CodeAssist.findAll(text(), findQuery.toString(), findCase);
        if (hits.isEmpty()) {
            return;
        }
        int which = Math.min(findIndex, hits.size() - 1);
        pushUndo();
        replaceAllLines(CodeAssist.replace(text(), findQuery.toString(), replaceText.toString(), findCase, which));
        fireChange();
        List<Integer> after = CodeAssist.findAll(text(), findQuery.toString(), findCase);
        if (!after.isEmpty()) {
            findIndex = Math.min(which, after.size() - 1);
            selectHit(after.get(findIndex), findQuery.length(), font);
        }
    }

    private void replaceAllLines(String text) {
        int line = cursorLine;
        int col = cursorCol;
        lines.clear();
        lines.addAll(List.of(text.split("\n", -1)));
        cursorLine = line;
        cursorCol = col;
        anchorLine = line;
        anchorCol = col;
        clampCursor();
    }

    /** 查找条的框有焦点时，按键归它。返回 null 表示没处理，交还给代码区。 */
    private @Nullable Boolean findKey(int key, int modifiers, Font font) {
        if (!findOpen) {
            return null;
        }
        if (key == InputConstants.KEY_ESCAPE) {
            findOpen = false;
            findFocus = -1;
            return true;
        }
        if (findFocus < 0) {
            return null;
        }
        if ((modifiers & InputConstants.MOD_CONTROL) != 0
                && (key == InputConstants.KEY_F || key == InputConstants.KEY_H)) {
            // 焦点已在框里时再按一次：Ctrl+H 展开替换行，Ctrl+F 回到查找框
            replaceOpen = replaceOpen || key == InputConstants.KEY_H;
            findFocus = key == InputConstants.KEY_H ? 1 : 0;
            return true;
        }
        StringBuilder target = findFocus == 0 ? findQuery : replaceText;
        boolean shift = (modifiers & InputConstants.MOD_SHIFT) != 0;
        switch (key) {
            case InputConstants.KEY_BACKSPACE -> {
                if (!target.isEmpty()) {
                    target.setLength(target.length() - 1);
                    findIndex = 0;
                }
                return true;
            }
            case InputConstants.KEY_RETURN, InputConstants.KEY_NUMPADENTER -> {
                if (findFocus == 1) {
                    replaceCurrent(font);
                } else {
                    stepFind(shift ? -1 : 1, font);
                }
                return true;
            }
            case InputConstants.KEY_TAB -> {
                findFocus = replaceOpen ? 1 - findFocus : 0;
                return true;
            }
            case InputConstants.KEY_V -> {
                if ((modifiers & InputConstants.MOD_CONTROL) != 0) {
                    String clip = Minecraft.getInstance().keyboardHandler.getClipboard();
                    target.append(clip.replace("\n", " ").replace("\r", ""));
                    findIndex = 0;
                    return true;
                }
                return false;
            }
            default -> {
                // 其余按键（方向键之类）不传给代码区：焦点在框里时移动代码光标只会让人困惑
                return false;
            }
        }
    }

    /** 光标左边的前缀够长就刷新补全列表，否则收起。 */
    private void refreshCompletions(boolean force) {
        String line = lines.get(cursorLine);
        String prefix = CodeAssist.prefixAt(line, cursorCol);
        boolean inComment = GlslLexer.isInCommentOrPreprocessor(GlslLexer.tokenize(text()),
                offsetOf(cursorLine, cursorCol));
        if (inComment || prefix.isEmpty() || (!force && prefix.length() < 2)) {
            completions = List.of();
            return;
        }
        completions = GlslSymbols.complete(prefix, userSymbols(), COMPLETION_ROWS);
        completionCol = cursorCol - prefix.length();
        completionIndex = 0;
    }

    private void acceptCompletion() {
        if (completions.isEmpty()) {
            return;
        }
        GlslSymbols.Symbol s = completions.get(Math.min(completionIndex, completions.size() - 1));
        completions = List.of();
        pushUndo();
        String line = lines.get(cursorLine);
        int start = clamp(completionCol, 0, cursorCol);
        lines.set(cursorLine, line.substring(0, start) + s.name() + line.substring(cursorCol));
        cursorCol = start + s.name().length();
        anchorLine = cursorLine;
        anchorCol = cursorCol;
        fireChange();
    }

    public boolean mouseDragged(double mx, double my, Font font) {
        if (!focused) {
            return false;
        }
        double lx = localX(mx);
        double ly = localY(my);
        if (draggingV) {
            dragVerticalTo(ly, font);
            return true;
        }
        if (draggingH) {
            dragHorizontalTo(lx, font);
            return true;
        }
        placeCursorAt(lx, ly, font);
        return true;
    }

    public void mouseReleased() {
        draggingV = false;
        draggingH = false;
    }

    private void dragVerticalTo(double ly, Font font) {
        int rows = visibleRows(font);
        int maxScroll = Math.max(1, lines.size() - rows);
        double t = (ly - BAR) / Math.max(1, contentH() - BAR * 2);
        scrollLine = clamp((int) Math.round(t * maxScroll), 0, maxScroll);
    }

    private void dragHorizontalTo(double lx, Font font) {
        int maxX = maxScrollPx(font);
        double t = (lx - GUTTER_WIDTH) / Math.max(1, contentW() - GUTTER_WIDTH - BAR);
        scrollPx = clamp((int) Math.round(t * maxX), 0, maxX);
    }

    /**
     * @param alt     Alt 按住 = 缩放代码区
     * @param shift   Shift 按住 = 横向滚动（和绝大多数编辑器一致）
     * @param baseNet 外层已有的整数倍放大，缩放档位要以它为基准，见 {@link #cycleZoom}
     */
    public boolean mouseScrolled(double mx, double my, double dy, boolean alt, boolean shift,
                                 Font font, float baseNet) {
        if (!isOver(mx, my)) {
            return false;
        }
        if (alt) {
            cycleZoom((int) Math.signum(dy), baseNet);
            return true;
        }
        if (shift) {
            scrollPx = clamp(scrollPx - (int) Math.signum(dy) * 24, 0, maxScrollPx(font));
            return true;
        }
        scrollLine = clamp(scrollLine - (int) Math.signum(dy) * 3, 0,
                Math.max(0, lines.size() - visibleRows(font)));
        return true;
    }

    /**
     * 代码区缩放。
     *
     * <h3>为什么要知道外层已经放大了几倍</h3>
     *
     * <p>代码区的实际采样倍数是 <b>{@code guiScale × uiScale × zoom}</b>——它套在编辑器
     * 整体缩放<b>之内</b>，是第三层。前两层已经保证乘积是整数 {@code baseNet}，
     * 这一层只要再乘上 {@code m / baseNet} 就仍是整数，字才不会糊。
     *
     * <p>原来那张写死的表 {@code {0.75, 0.85, …}} 不管外层是几倍，于是 0.85 这一档
     * 在任何情况下都是非整数倍——代码区一缩小就发虚，而代码恰恰是最需要看清的地方。
     *
     * @param baseNet 外层已有的整数倍放大，即 {@code round(guiScale × uiScale)}
     */
    public void cycleZoom(int delta, float baseNet) {
        lastBaseNet = baseNet;
        float[] steps = LayoutConfig.integerSteps(baseNet, ZOOM_MIN, ZOOM_MAX);
        int best = 0;
        float bestDiff = Float.MAX_VALUE;
        for (int i = 0; i < steps.length; i++) {
            float d = Math.abs(steps[i] - zoom);
            if (d < bestDiff) {
                bestDiff = d;
                best = i;
            }
        }
        zoom = steps[clamp(best + delta, 0, steps.length - 1)];
    }

    /** 外层倍数变了（改了界面缩放或游戏 GUI Scale）时把 zoom 吸附回合法档位。 */
    public void snapZoom(float baseNet) {
        cycleZoom(0, baseNet);
    }

    /**
     * 回到最接近「原始大小」的那一档。
     *
     * <p>不能直接写 {@code zoom = 1.0f}：基准是小数时 1.0 未必在合法档位里，
     * 硬设过去会让代码区落回非整数倍——正是这个方法本来要避免的事。
     */
    public void resetZoom(float baseNet) {
        zoom = 1.0f;
        snapZoom(baseNet);
    }

    /** 代码区内部按 Ctrl+0 时用，基准取最近一次记下的那个。 */
    public void resetZoom() {
        resetZoom(lastBaseNet);
    }

    public float zoom() {
        return zoom;
    }

    private void placeCursorAt(double lx, double ly, Font font) {
        int lineH = font.lineHeight + 1;
        int row = (int) ((ly - PADDING) / lineH);
        cursorLine = clamp(scrollLine + row, 0, lines.size() - 1);
        String line = lines.get(cursorLine);
        int targetX = (int) (lx - textLeft() + scrollPx);
        cursorCol = columnForX(line, targetX, font);
    }

    private int columnForX(String line, int targetX, Font font) {
        int best = 0;
        int bestDist = Integer.MAX_VALUE;
        for (int c = 0; c <= line.length(); c++) {
            int w = font.width(safeSubstring(line, 0, c));
            int d = Math.abs(w - targetX);
            if (d < bestDist) {
                bestDist = d;
                best = c;
            }
        }
        return best;
    }

    public boolean keyPressed(int key, int modifiers, Font font) {
        if (!focused) {
            return false;
        }
        boolean ctrl = (modifiers & InputConstants.MOD_CONTROL) != 0;
        boolean shift = (modifiers & InputConstants.MOD_SHIFT) != 0;

        Boolean findHandled = findKey(key, modifiers, font);
        if (findHandled != null) {
            return findHandled;
        }
        if (!completions.isEmpty()) {
            switch (key) {
                case InputConstants.KEY_UP -> {
                    completionIndex = Math.floorMod(completionIndex - 1, completions.size());
                    return true;
                }
                case InputConstants.KEY_DOWN -> {
                    completionIndex = Math.floorMod(completionIndex + 1, completions.size());
                    return true;
                }
                case InputConstants.KEY_TAB, InputConstants.KEY_RETURN, InputConstants.KEY_NUMPADENTER -> {
                    acceptCompletion();
                    return true;
                }
                case InputConstants.KEY_ESCAPE -> {
                    completions = List.of();
                    return true;
                }
                case InputConstants.KEY_BACKSPACE -> {
                    // 往下走正常删除，删完再刷新列表
                }
                default -> completions = List.of();
            }
        }

        if (ctrl) {
            switch (key) {
                case InputConstants.KEY_F -> {
                    openFind(false);
                    return true;
                }
                case InputConstants.KEY_H -> {
                    openFind(true);
                    return true;
                }
                case InputConstants.KEY_SPACE -> {
                    refreshCompletions(true);
                    return true;
                }
                case InputConstants.KEY_A -> {
                    anchorLine = 0;
                    anchorCol = 0;
                    cursorLine = lines.size() - 1;
                    cursorCol = lines.get(cursorLine).length();
                    return true;
                }
                case InputConstants.KEY_C -> {
                    copySelection();
                    return true;
                }
                case InputConstants.KEY_X -> {
                    copySelection();
                    if (hasSelection()) {
                        pushUndo();
                        deleteSelection();
                        fireChange();
                    }
                    return true;
                }
                case InputConstants.KEY_V -> {
                    pushUndo();
                    // 剪贴板里的全角符号、不换行空格、BOM 会让编译莫名其妙地失败，
                    // 而它们在编辑器里几乎看不出来——所以在入口处就换掉
                    GlslSanitizer.Result cleaned =
                            GlslSanitizer.sanitize(Minecraft.getInstance().keyboardHandler.getClipboard());
                    lastSanitized = cleaned.replaced();
                    insertText(cleaned.text());
                    fireChange();
                    ensureVisible(font);
                    return true;
                }
                case InputConstants.KEY_0, InputConstants.KEY_NUMPAD0 -> {
                    resetZoom();
                    return true;
                }
                case InputConstants.KEY_Z -> {
                    undo();
                    return true;
                }
                case InputConstants.KEY_Y -> {
                    redo();
                    return true;
                }
                default -> {
                }
            }
        }

        switch (key) {
            case InputConstants.KEY_LEFT -> {
                moveLeft();
                collapseIfNoShift(shift);
                ensureVisible(font);
                return true;
            }
            case InputConstants.KEY_RIGHT -> {
                moveRight();
                collapseIfNoShift(shift);
                ensureVisible(font);
                return true;
            }
            case InputConstants.KEY_UP -> {
                if (cursorLine > 0) {
                    cursorLine--;
                    cursorCol = Math.min(cursorCol, lines.get(cursorLine).length());
                }
                collapseIfNoShift(shift);
                ensureVisible(font);
                return true;
            }
            case InputConstants.KEY_DOWN -> {
                if (cursorLine < lines.size() - 1) {
                    cursorLine++;
                    cursorCol = Math.min(cursorCol, lines.get(cursorLine).length());
                }
                collapseIfNoShift(shift);
                ensureVisible(font);
                return true;
            }
            case InputConstants.KEY_HOME -> {
                // 先跳到首个非空白字符，再按一次才回到行首——代码编辑器的通用行为
                String line = lines.get(cursorLine);
                int indent = 0;
                while (indent < line.length() && Character.isWhitespace(line.charAt(indent))) {
                    indent++;
                }
                cursorCol = cursorCol == indent ? 0 : indent;
                collapseIfNoShift(shift);
                return true;
            }
            case InputConstants.KEY_END -> {
                cursorCol = lines.get(cursorLine).length();
                collapseIfNoShift(shift);
                return true;
            }
            case InputConstants.KEY_PAGEUP -> {
                int rows = visibleRows(font);
                cursorLine = Math.max(0, cursorLine - rows);
                cursorCol = Math.min(cursorCol, lines.get(cursorLine).length());
                collapseIfNoShift(shift);
                ensureVisible(font);
                return true;
            }
            case InputConstants.KEY_PAGEDOWN -> {
                int rows = visibleRows(font);
                cursorLine = Math.min(lines.size() - 1, cursorLine + rows);
                cursorCol = Math.min(cursorCol, lines.get(cursorLine).length());
                collapseIfNoShift(shift);
                ensureVisible(font);
                return true;
            }
            case InputConstants.KEY_BACKSPACE -> {
                boolean wasOpen = !completions.isEmpty();
                pushUndo();
                if (hasSelection()) {
                    deleteSelection();
                } else {
                    backspace();
                }
                fireChange();
                ensureVisible(font);
                if (wasOpen) {
                    refreshCompletions(true);
                }
                return true;
            }
            case InputConstants.KEY_DELETE -> {
                pushUndo();
                if (hasSelection()) {
                    deleteSelection();
                } else {
                    moveRight();
                    backspace();
                }
                fireChange();
                return true;
            }
            case InputConstants.KEY_RETURN, InputConstants.KEY_NUMPADENTER -> {
                pushUndo();
                insertNewlineKeepingIndent();
                fireChange();
                ensureVisible(font);
                return true;
            }
            case InputConstants.KEY_TAB -> {
                pushUndo();
                insertText("    ");
                fireChange();
                return true;
            }
            default -> {
                return false;
            }
        }
    }

    public boolean charTyped(int codepoint) {
        if (!focused || Character.isISOControl(codepoint)) {
            return false;
        }
        if (findOpen && findFocus >= 0) {
            (findFocus == 0 ? findQuery : replaceText).appendCodePoint(codepoint);
            findIndex = 0;
            return true;
        }
        pushUndo();
        // 中文输入法下敲出的全角分号/括号在这里就换成半角。
        // 事后靠肉眼从一行代码里挑出全角分号，是这个工具最不该让人做的事
        String inserted = GlslSanitizer.sanitizeChar(codepoint);
        insertText(inserted);
        fireChange();
        // 敲的是标识符字符就刷新补全；敲了别的（空格、括号、分号）说明这个词已经写完了
        if (inserted.length() == 1 && CodeAssist.isIdentChar(inserted.charAt(0))) {
            refreshCompletions(false);
        } else {
            completions = List.of();
        }
        return true;
    }

    /**
     * 取走并清零「上次粘贴清洗掉多少个字符」。
     *
     * <p>读一次就清零，是为了让界面只在真正发生替换的那一次弹提示，
     * 而不是此后每帧都提示一遍。
     */
    public int takeSanitizedCount() {
        int n = lastSanitized;
        lastSanitized = 0;
        return n;
    }

    // ------------------------------------------------------------------ 编辑操作

    private void insertNewlineKeepingIndent() {
        // 自动沿用当前行的缩进：不这么做的话，写着色器每换一行都要手动敲四个空格
        String line = lines.get(cursorLine);
        int indent = 0;
        while (indent < line.length() && (line.charAt(indent) == ' ' || line.charAt(indent) == '\t')) {
            indent++;
        }
        String prefix = line.substring(0, indent);
        // 刚写完 '{' 时多缩进一级，符合直觉
        String beforeCursor = safeSubstring(line, 0, cursorCol).stripTrailing();
        if (beforeCursor.endsWith("{")) {
            prefix = prefix + "    ";
        }
        insertText("\n" + prefix);
    }

    private void insertText(String text) {
        if (text == null || text.isEmpty()) {
            return;
        }
        if (hasSelection()) {
            deleteSelection();
        }
        text = text.replace("\r\n", "\n").replace('\r', '\n');
        String line = lines.get(cursorLine);
        String head = safeSubstring(line, 0, cursorCol);
        String tail = safeSubstring(line, cursorCol, line.length());

        String[] parts = text.split("\n", -1);
        if (parts.length == 1) {
            lines.set(cursorLine, head + parts[0] + tail);
            cursorCol = head.length() + parts[0].length();
        } else {
            lines.set(cursorLine, head + parts[0]);
            for (int i = 1; i < parts.length; i++) {
                lines.add(cursorLine + i, parts[i]);
            }
            int lastIdx = cursorLine + parts.length - 1;
            cursorCol = parts[parts.length - 1].length();
            lines.set(lastIdx, lines.get(lastIdx) + tail);
            cursorLine = lastIdx;
        }
        anchorLine = cursorLine;
        anchorCol = cursorCol;
    }

    private void backspace() {
        if (cursorCol > 0) {
            String line = lines.get(cursorLine);
            lines.set(cursorLine, safeSubstring(line, 0, cursorCol - 1) + safeSubstring(line, cursorCol, line.length()));
            cursorCol--;
        } else if (cursorLine > 0) {
            String prev = lines.get(cursorLine - 1);
            String cur = lines.remove(cursorLine);
            cursorLine--;
            cursorCol = prev.length();
            lines.set(cursorLine, prev + cur);
        }
        anchorLine = cursorLine;
        anchorCol = cursorCol;
    }

    private void deleteSelection() {
        if (!hasSelection()) {
            return;
        }
        int[] s = selectionStart();
        int[] e = selectionEnd();
        String head = safeSubstring(lines.get(s[0]), 0, s[1]);
        String tail = safeSubstring(lines.get(e[0]), e[1], lines.get(e[0]).length());
        for (int i = e[0]; i > s[0]; i--) {
            lines.remove(i);
        }
        lines.set(s[0], head + tail);
        cursorLine = s[0];
        cursorCol = s[1];
        anchorLine = cursorLine;
        anchorCol = cursorCol;
    }

    private void copySelection() {
        if (!hasSelection()) {
            return;
        }
        int[] s = selectionStart();
        int[] e = selectionEnd();
        StringBuilder sb = new StringBuilder();
        if (s[0] == e[0]) {
            sb.append(safeSubstring(lines.get(s[0]), s[1], e[1]));
        } else {
            sb.append(safeSubstring(lines.get(s[0]), s[1], lines.get(s[0]).length()));
            for (int i = s[0] + 1; i < e[0]; i++) {
                sb.append('\n').append(lines.get(i));
            }
            sb.append('\n').append(safeSubstring(lines.get(e[0]), 0, e[1]));
        }
        Minecraft.getInstance().keyboardHandler.setClipboard(sb.toString());
    }

    private void moveLeft() {
        if (cursorCol > 0) {
            cursorCol--;
        } else if (cursorLine > 0) {
            cursorLine--;
            cursorCol = lines.get(cursorLine).length();
        }
    }

    private void moveRight() {
        if (cursorCol < lines.get(cursorLine).length()) {
            cursorCol++;
        } else if (cursorLine < lines.size() - 1) {
            cursorLine++;
            cursorCol = 0;
        }
    }

    private void collapseIfNoShift(boolean shift) {
        if (!shift) {
            anchorLine = cursorLine;
            anchorCol = cursorCol;
        }
    }

    // ------------------------------------------------------------------ 撤销

    private void pushUndo() {
        undoStack.push(new Snapshot(new ArrayList<>(lines), cursorLine, cursorCol));
        if (undoStack.size() > MAX_UNDO) {
            undoStack.removeLast();
        }
        redoStack.clear();
    }

    private void undo() {
        if (undoStack.isEmpty()) {
            return;
        }
        redoStack.push(new Snapshot(new ArrayList<>(lines), cursorLine, cursorCol));
        applySnapshot(undoStack.pop());
    }

    private void redo() {
        if (redoStack.isEmpty()) {
            return;
        }
        undoStack.push(new Snapshot(new ArrayList<>(lines), cursorLine, cursorCol));
        applySnapshot(redoStack.pop());
    }

    private void applySnapshot(Snapshot s) {
        lines.clear();
        lines.addAll(s.lines());
        cursorLine = s.cursorLine();
        cursorCol = s.cursorCol();
        clampCursor();
        anchorLine = cursorLine;
        anchorCol = cursorCol;
        fireChange();
    }

    // ------------------------------------------------------------------ 杂项

    private void fireChange() {
        contentWidthDirty = true;
        version++;
        onChange.run();
    }

    private void ensureVisible(Font font) {
        int rows = visibleRows(font);
        if (cursorLine < scrollLine) {
            scrollLine = cursorLine;
        } else if (cursorLine >= scrollLine + rows) {
            scrollLine = cursorLine - rows + 1;
        }
        scrollLine = Math.max(0, scrollLine);

        // 横向同理：光标跑到视口外时把它拉回来，不然打字打着打着就看不见光标了
        int cx = font.width(safeSubstring(lines.get(cursorLine), 0, cursorCol));
        int tw = textWidth();
        if (cx - scrollPx < 0) {
            scrollPx = Math.max(0, cx - 8);
        } else if (cx - scrollPx > tw - 8) {
            scrollPx = cx - tw + 8;
        }
        scrollPx = clamp(scrollPx, 0, maxScrollPx(font));
    }

    private int visibleRows(Font font) {
        // 减去底部横向滚动条占的那条，否则最后一行会被压在滚动条底下
        return Math.max(1, (contentH() - PADDING * 2 - BAR) / (font.lineHeight + 1));
    }

    private boolean hasSelection() {
        return anchorLine != cursorLine || anchorCol != cursorCol;
    }

    private int[] selectionStart() {
        if (anchorLine < cursorLine || (anchorLine == cursorLine && anchorCol <= cursorCol)) {
            return new int[]{anchorLine, anchorCol};
        }
        return new int[]{cursorLine, cursorCol};
    }

    private int[] selectionEnd() {
        if (anchorLine < cursorLine || (anchorLine == cursorLine && anchorCol <= cursorCol)) {
            return new int[]{cursorLine, cursorCol};
        }
        return new int[]{anchorLine, anchorCol};
    }

    private void clampCursor() {
        cursorLine = clamp(cursorLine, 0, lines.size() - 1);
        cursorCol = clamp(cursorCol, 0, lines.get(cursorLine).length());
        anchorLine = clamp(anchorLine, 0, lines.size() - 1);
        anchorCol = clamp(anchorCol, 0, lines.get(anchorLine).length());
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static String safeSubstring(String s, int from, int to) {
        int f = clamp(from, 0, s.length());
        int t = clamp(to, f, s.length());
        return s.substring(f, t);
    }
}
