package mc.GTedd.cn.gtshaders.codegen;

import mc.GTedd.cn.gtshaders.core.ParamDriver;
import mc.GTedd.cn.gtshaders.core.ParamType;
import mc.GTedd.cn.gtshaders.core.ShaderParam;
import mc.GTedd.cn.gtshaders.i18n.GtLang;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 从作者源码里识别可视化参数。
 *
 * <p>两条识别路径，都会被采纳：
 * <ol>
 *   <li><b>显式注解</b>：{@code // @param name=RingSpeed type=float min=0 max=5 default=2.6 zh_cn=环的旋转速度}
 *       —— 作者能完整控制控件类型、范围、默认值和多语言显示名。</li>
 *   <li><b>裸 uniform 声明</b>：作者直接写 {@code uniform float RingSpeed;}，自动提升为参数并推断默认值。
 *       这一条不只是便利——原版 post effect <b>只喂 uniform 块</b>，散装 uniform 根本拿不到值，
 *       所以「提升进块」同时也修正了这种写法。</li>
 * </ol>
 *
 * <p>之所以用词法级扫描而不是完整 GLSL 语法分析：post effect 的 uniform 类型是个封闭集合
 * （int/float/vec2/vec3/vec4 等七种），扫描器覆盖它绰绰有余，而且不会因为作者写了一段
 * 扫描器看不懂的高级语法就整体解析失败——鲁棒性反而更高。
 */
public final class ParamScanner {

    /** {@code // @param ...} 或 {@code //@param ...}，大小写不敏感。 */
    private static final Pattern ANNOTATION = Pattern.compile("^\\s*//\\s*@param\\s+(.*)$", Pattern.CASE_INSENSITIVE);

    /** {@code // @group 外观} 或 {@code // @group zh_cn=外观 en_us=Look}。 */
    private static final Pattern GROUP = Pattern.compile("^\\s*//\\s*@group\\s*(.*)$", Pattern.CASE_INSENSITIVE);

    /** 裸 uniform 声明，只匹配我们支持的标量/向量类型，故意不匹配 sampler2D。 */
    private static final Pattern BARE_UNIFORM = Pattern.compile(
            "^\\s*uniform\\s+(float|int|bool|vec2|vec3|vec4)\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*;.*$");

    /** 把 {@code key=value} 拆开：value 可以是带引号的串，也可以一直吃到下一个 {@code key=} 之前。 */
    private static final Pattern KV = Pattern.compile(
            "([A-Za-z_][A-Za-z0-9_]*)\\s*=\\s*(?:\"([^\"]*)\"|((?:(?!\\s+[A-Za-z_][A-Za-z0-9_]*\\s*=).)*))");

    /** 注解里这些 key 是结构化字段，其余 key 一律当作语言代码处理。 */
    private static final java.util.Set<String> RESERVED =
            java.util.Set.of("name", "type", "min", "max", "default", "label", "desc", "group", "drive");

    /** {@code desc_zh_cn=…} / {@code group_en_us=…} 这类带语言后缀的 key 的前缀。 */
    private static final String DESC_PREFIX = "desc_";
    private static final String GROUP_PREFIX = "group_";
    /** 驱动器的子键：{@code drive_from} {@code drive_to} {@code drive_period} {@code drive_phase}。 */
    private static final String DRIVE_PREFIX = "drive_";
    /** 注解里已有的驱动器键，连同前面的空白。值里不会有空格，按非空白吃到底就够了。 */
    private static final Pattern DRIVE_KEYS =
            Pattern.compile("\\s+drive(?:_[A-Za-z]+)?\\s*=\\s*\\S*", Pattern.CASE_INSENSITIVE);

    public record Result(List<ShaderParam> params, List<String> warnings, String strippedBody) {
    }

    private ParamScanner() {
    }

    /**
     * 扫描作者源码。
     *
     * <h2>为什么进门第一件事是抹掉 CR</h2>
     *
     * <p>Windows 上的编辑器（记事本、某些导出工具、跑偏的脚本）会把文件写成 CRLF。
     * 按 {@code \n} 切开之后，每一行结尾都留着一个 {@code \r}，而 Java 正则里
     * {@code .} <b>不匹配 \r</b>（它算行终止符），于是 {@link #ANNOTATION} 那条
     * 以 {@code $} 收尾的模式做 {@code matches()} 时全部落空——
     * <b>整份文件一个参数都扫不出来</b>。
     *
     * <p>这个故障的可怕之处在于它一声不吭：面板上空空如也，生成的 uniform 块是空的，
     * 然后驱动报一串「未定义的 AutoPlay」指着作者自己写的代码。作者会以为是自己写错了。
     *
     * <p>抹掉 CR 不改变行数，所以报错行号映射仍然准确。
     *
     * @param source 作者写的源码（不含 codegen 生成的头部）
     * @return 参数列表、警告，以及剔除了裸 uniform 声明后的源码主体
     */
    public static Result scan(String source) {
        List<ShaderParam> ordered = new ArrayList<>();
        Map<String, ShaderParam> byName = new LinkedHashMap<>();
        List<String> warnings = new ArrayList<>();
        StringBuilder body = new StringBuilder();

        String[] lines = source.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        List<Map<String, String>> groupAt = groupsPerLine(lines);
        // 注解可以写在被它描述的 uniform 之前，也可以独立存在；先收注解，再处理裸声明，
        // 这样同名时注解的元数据总是赢——作者的显式意图优先于推断。
        for (int i = 0; i < lines.length; i++) {
            Matcher m = ANNOTATION.matcher(lines[i]);
            if (!m.matches()) {
                continue;
            }
            ShaderParam p = parseAnnotation(m.group(1), i + 1, warnings, groupAt.get(i));
            if (p != null) {
                if (byName.containsKey(p.name())) {
                    warnings.add(GtLang.get("gtshaders.scan.duplicate", p.name(), i + 1));
                } else {
                    byName.put(p.name(), p);
                    ordered.add(p);
                }
            }
        }

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            Matcher bare = BARE_UNIFORM.matcher(line);
            if (bare.matches()) {
                String typeName = bare.group(1);
                String name = bare.group(2);
                // 声明本身要从主体中剔除：它会被 codegen 重新生成进 std140 块里。
                // 但不能只留个空行——那样「用裸 uniform 声明的参数」在导出再导入一次之后
                // 就凭空消失了。改成写一行等价的 @param 注解：行数不变（报错行号仍然与作者
                // 看到的源码一一对应），往返也不丢信息。
                // 已经有显式注解的不重复写，否则再导入时会撞成重复定义。
                boolean alreadyAnnotated = byName.containsKey(name);
                if (!alreadyAnnotated) {
                    ParamType type = ParamType.parse(typeName);
                    ShaderParam p = inferFromBareUniform(name, type);
                    groupAt.get(i).forEach(p::putGroup);
                    p.setSourceLine(i + 1);
                    byName.put(name, p);
                    ordered.add(p);
                    body.append(annotationFor(p));
                }
                if (i < lines.length - 1) {
                    body.append('\n');
                }
                continue;
            }
            body.append(line);
            if (i < lines.length - 1) {
                body.append('\n');
            }
        }

        return new Result(ordered, warnings, body.toString());
    }

    /**
     * 预扫一趟，算出每一行「当前生效的分组」。
     *
     * <p>{@code // @group 外观} 之后声明的参数都归到外观，直到下一个 {@code @group}；
     * 写一个不带内容的 {@code // @group} 就退回不分组。做成<b>位置生效</b>而不是让每个参数
     * 各写一遍 {@code group=}，是因为参数天然是成组连写的，逐个标注既啰嗦又容易漏掉一个，
     * 而漏掉的那个会孤零零掉进「常规」组里，看起来像个 bug。
     *
     * <p>两趟扫描（显式注解 / 裸 uniform）都查这张表，所以两种写法的分组行为完全一致。
     */
    private static List<Map<String, String>> groupsPerLine(String[] lines) {
        List<Map<String, String>> out = new ArrayList<>(lines.length);
        Map<String, String> current = Map.of();
        for (String line : lines) {
            Matcher m = GROUP.matcher(line);
            if (m.matches()) {
                current = parseGroupNames(m.group(1).trim());
            }
            out.add(current);
        }
        return out;
    }

    /** {@code zh_cn=外观 en_us=Look} 两种都认，也认裸文本 {@code 外观 / Look}。 */
    private static Map<String, String> parseGroupNames(String rest) {
        if (rest.isEmpty()) {
            return Map.of();
        }
        Map<String, String> names = new LinkedHashMap<>();
        if (rest.contains("=")) {
            Matcher m = KV.matcher(rest);
            while (m.find()) {
                String value = m.group(2) != null ? m.group(2) : m.group(3);
                if (value != null && !value.isBlank()) {
                    names.put(m.group(1).toLowerCase(Locale.ROOT), value.trim());
                }
            }
            if (!names.isEmpty()) {
                return names;
            }
        }
        // 裸文本：`外观 / Look` 拆成中英两份，没有斜杠就当所有语言共用
        int slash = rest.indexOf(" / ");
        if (slash > 0) {
            names.put("zh_cn", rest.substring(0, slash).trim());
            names.put("en_us", rest.substring(slash + 3).trim());
        } else {
            names.put("en_us", rest);
        }
        return names;
    }

    private static ShaderParam parseAnnotation(String rest, int lineNo, List<String> warnings,
                                               Map<String, String> inheritedGroup) {
        Map<String, String> kv = new LinkedHashMap<>();
        Matcher m = KV.matcher(rest);
        while (m.find()) {
            String value = m.group(2) != null ? m.group(2) : m.group(3);
            kv.put(m.group(1).toLowerCase(Locale.ROOT), value == null ? "" : value.trim());
        }

        String name = kv.get("name");
        if (name == null || name.isBlank()) {
            warnings.add(GtLang.get("gtshaders.scan.missing_name", lineNo));
            return null;
        }
        ParamType type = ParamType.parse(kv.getOrDefault("type", "float"));
        if (type == null) {
            warnings.add(GtLang.get("gtshaders.scan.bad_type", lineNo, name));
            type = ParamType.FLOAT;
        }

        float min = parseFloat(kv.get("min"), type.isColor() ? 0f : 0f);
        float max = parseFloat(kv.get("max"), type.isColor() ? 1f : 1f);
        ShaderParam p = new ShaderParam(name, type, min, max);
        p.setSourceLine(lineNo);

        float[] def = parseValue(kv.get("default"), type);
        p.setDefaults(def);
        p.resetToDefault();

        String labelKey = kv.get("label");
        if (labelKey != null && !labelKey.isBlank()) {
            p.setLabelKey(labelKey);
        }

        // 分组：显式 group= 覆盖位置继承来的那个
        Map<String, String> group = new LinkedHashMap<>(inheritedGroup);
        String groupAll = kv.get("group");
        if (groupAll != null && !groupAll.isBlank()) {
            group = parseGroupNames(groupAll.trim());
        }

        // 说明：desc= 是所有语言共用的，desc_zh_cn= 覆盖单一语言
        String descAll = kv.get("desc");

        for (Map.Entry<String, String> e : kv.entrySet()) {
            String key = e.getKey();
            String value = e.getValue();
            if (value.isBlank()) {
                continue;
            }
            if (key.startsWith(DESC_PREFIX)) {
                p.putDesc(key.substring(DESC_PREFIX.length()), value);
            } else if (key.startsWith(GROUP_PREFIX)) {
                group.put(key.substring(GROUP_PREFIX.length()).toLowerCase(Locale.ROOT), value);
            } else if (key.startsWith(DRIVE_PREFIX)) {
                // 驱动器参数，下面单独解析。不能掉进「剩下的都是语言代码」那一支，
                // 否则 drive_period=2 会变成一种叫 drive_period 的语言下的标签
                continue;
            } else if (!RESERVED.contains(key)) {
                // 剩下的非保留 key 一律视为语言代码：zh_cn=环的旋转速度 / en_us=Ring Speed
                p.putLabel(key, value);
            }
        }
        if (descAll != null && !descAll.isBlank() && p.descs().isEmpty()) {
            // 通用说明只在没写分语言版本时兜底，避免把已有的中文说明顶掉
            p.putDesc(GtLang.FALLBACK_LANG, descAll);
        }
        group.forEach(p::putGroup);

        String drive = kv.get("drive");
        if (drive != null && !drive.isBlank()) {
            ParamDriver.Wave wave = ParamDriver.Wave.parse(drive);
            if (wave == null) {
                warnings.add(GtLang.get("gtshaders.scan.drive_bad_wave", lineNo, name, drive));
            } else if (type != ParamType.FLOAT) {
                // 只做 float：整数、颜色、向量被时间函数驱动的需求很少，
                // 而每多一种类型，gtDrive 就要多一组重载、面板就要多一套编辑器
                warnings.add(GtLang.get("gtshaders.scan.drive_float_only", lineNo, name));
            } else {
                ParamDriver base = ParamDriver.defaultsFor(p);
                p.setDriver(new ParamDriver(wave,
                        parseFloat(kv.get(DRIVE_PREFIX + "from"), base.from()),
                        parseFloat(kv.get(DRIVE_PREFIX + "to"), base.to()),
                        parseFloat(kv.get(DRIVE_PREFIX + "period"), base.period()),
                        parseFloat(kv.get(DRIVE_PREFIX + "phase"), base.phase())));
            }
        }
        return p;
    }

    /** 一条推断结果：控件类型 + 取值范围 + 默认值。 */
    private record Hint(ParamType type, float min, float max, float def) {
    }

    /**
     * 名字 → 量纲的启发式表。
     *
     * <h3>为什么值得做</h3>
     *
     * <p>不加注解时旧规则一律给 0..1，于是 {@code uniform float Angle} 的滑块只能在
     * 0..1 之间拖——而它想要的是 0..360，拖满了也几乎看不出变化，看起来像「这个参数坏了」。
     * 对着色器完全没概念的人不会想到去改 min/max，只会以为效果本身不对。
     *
     * <p>关键词取的是着色器里实际通用的命名习惯，不是凭空拟的。命中不了就退回保守的 0..1，
     * 和以前一样——启发式只做加法，不会把原本正确的情形弄坏。
     *
     * <p>顺序有意义：先匹配的赢。所以 {@code Speed} 要排在 {@code Ed}(edge) 这类短词之前，
     * 表里也刻意不放两字母以下的词——{@code UvScale} 里的 {@code uv} 会误伤一大片。
     */
    private static final Object[][] NAME_HINTS = {
            // 关键词（小写）              类型              min     max    default
            {"speed", ParamType.FLOAT, 0f, 5f, 1f},
            {"rate", ParamType.FLOAT, 0f, 5f, 1f},
            {"frequency", ParamType.FLOAT, 0f, 20f, 4f},
            {"freq", ParamType.FLOAT, 0f, 20f, 4f},
            {"duration", ParamType.FLOAT, 0.1f, 10f, 2f},
            {"angle", ParamType.FLOAT, 0f, 360f, 0f},
            {"rotation", ParamType.FLOAT, 0f, 360f, 0f},
            {"degrees", ParamType.FLOAT, 0f, 360f, 0f},
            {"count", ParamType.INT, 1f, 32f, 8f},
            {"steps", ParamType.INT, 1f, 32f, 8f},
            {"samples", ParamType.INT, 1f, 32f, 8f},
            {"iterations", ParamType.INT, 1f, 32f, 8f},
            {"octaves", ParamType.INT, 1f, 8f, 4f},
            {"levels", ParamType.INT, 2f, 32f, 8f},
            {"exposure", ParamType.FLOAT, 0f, 4f, 1f},
            {"contrast", ParamType.FLOAT, 0f, 4f, 1f},
            {"saturation", ParamType.FLOAT, 0f, 2f, 1f},
            {"gamma", ParamType.FLOAT, 0.1f, 4f, 1f},
            {"brightness", ParamType.FLOAT, 0f, 2f, 1f},
            {"scale", ParamType.FLOAT, 0f, 10f, 1f},
            {"zoom", ParamType.FLOAT, 0.1f, 4f, 1f},
            {"seed", ParamType.FLOAT, 0f, 100f, 0f},
            {"distance", ParamType.FLOAT, 0f, 64f, 8f},
            {"pixel", ParamType.FLOAT, 1f, 64f, 8f},
    };

    /** 这些词出现在 vec3/vec4 名字里，说明它是个方向/位置而不是颜色，范围要能取负。 */
    private static final String[] VECTOR_WORDS =
            {"dir", "direction", "normal", "velocity", "offset", "shift", "delta", "axis"};

    /** float 参数名的<b>第一个词</b>是这些时提升成勾选框——作者本意是开关，滑块只会让人困惑。 */
    private static final String[] BOOL_PREFIXES = {"use", "enable", "is", "show", "has", "toggle"};

    /**
     * 裸 uniform 没有元数据，只能推断：先看名字（{@link #NAME_HINTS}），
     * 猜不出再退回保守的 0..1。推断出来的参数会打上标记，界面上提示作者这是猜的。
     */
    private static ShaderParam inferFromBareUniform(String name, ParamType type) {
        Hint hint = hintFor(words(name), type);

        ShaderParam p = new ShaderParam(name, hint.type(), hint.min(), hint.max());
        float[] def = new float[4];
        if (hint.type().isColor()) {
            // 白色：不改变画面的中性默认值，避免作者一加参数画面就变。
            def[0] = def[1] = def[2] = def[3] = 1f;
        } else {
            // 多分量的非颜色量（vec2 的中心点、vec3 的方向）各分量给同一个默认值：
            // 中心点会落在画面正中，方向落在对角线上，两者都是"看得见但不极端"的起点。
            for (int i = 0; i < hint.type().components(); i++) {
                def[i] = hint.def();
            }
        }
        p.setDefaults(def);
        p.resetToDefault();
        p.setInferred(true);
        return p;
    }

    /**
     * 把参数名切成小写单词：驼峰、下划线、字母与数字的交界都算分隔。
     *
     * <p>关键词只和<b>整词</b>比，不做子串匹配。子串匹配会误判：{@code Grayscale} 含 {@code scale}
     * 被当成 0..10 的缩放，{@code IndirectTint} 含 {@code dir} 被当成方向滑块，
     * {@code HashSeed} 以 {@code has} 开头被当成开关。SHADERed 按名字猜系统变量踩的是同一个坑
     * （任何含 {@code res} 的名字都会被当成分辨率）。
     *
     * <p>代价是全小写的复合词（{@code ringspeed}）不再命中，退回 0..1——猜不出来比猜错强。
     */
    static List<String> words(String name) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (!Character.isLetterOrDigit(c)) {
                flushWord(out, cur);
                continue;
            }
            if (cur.length() > 0) {
                char prev = cur.charAt(cur.length() - 1);
                boolean nextLower = i + 1 < name.length() && Character.isLowerCase(name.charAt(i + 1));
                // UVScale → UV / Scale：一串大写之后跟小写时，最后一个大写字母属于下一个词
                boolean camel = Character.isUpperCase(c)
                        && (Character.isLowerCase(prev) || Character.isDigit(prev)
                        || (Character.isUpperCase(prev) && nextLower));
                boolean digitEdge = Character.isDigit(c) != Character.isDigit(prev);
                if (camel || digitEdge) {
                    flushWord(out, cur);
                }
            }
            cur.append(c);
        }
        flushWord(out, cur);
        return out;
    }

    private static void flushWord(List<String> out, StringBuilder cur) {
        if (cur.length() > 0) {
            out.add(cur.toString().toLowerCase(Locale.ROOT));
            cur.setLength(0);
        }
    }

    /** 整词相等，另外容忍单复数：{@code SampleCount} 的 sample 与关键词 samples 算同一个词。 */
    private static boolean hasWord(List<String> words, String keyword) {
        for (String w : words) {
            if (w.equals(keyword) || (w + "s").equals(keyword) || w.equals(keyword + "s")) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasAnyWord(List<String> words, String[] keywords) {
        for (String k : keywords) {
            if (hasWord(words, k)) {
                return true;
            }
        }
        return false;
    }

    private static Hint hintFor(List<String> words, ParamType declared) {
        // 布尔先判：声明就是 bool，或者名字明摆着是开关
        if (declared == ParamType.BOOL) {
            return new Hint(ParamType.BOOL, 0f, 1f, 0f);
        }
        if ((declared == ParamType.FLOAT || declared == ParamType.INT) && words.size() > 1) {
            for (String prefix : BOOL_PREFIXES) {
                if (words.get(0).equals(prefix)) {
                    return new Hint(ParamType.BOOL, 0f, 1f, 0f);
                }
            }
        }

        // 三四分量的量在后处理语境里几乎总是颜色，但方向/偏移是例外
        if (declared == ParamType.VEC3 || declared == ParamType.VEC4) {
            if (hasAnyWord(words, VECTOR_WORDS)) {
                return new Hint(declared, -1f, 1f, 0f);
            }
            return new Hint(declared == ParamType.VEC3 ? ParamType.COLOR3 : ParamType.COLOR4,
                    0f, 1f, 1f);
        }

        // vec2 多半是屏幕上的一个点，0..1 的 UV 空间正合适
        if (declared == ParamType.VEC2) {
            if (hasAnyWord(words, VECTOR_WORDS)) {
                return new Hint(ParamType.VEC2, -1f, 1f, 0f);
            }
            return new Hint(ParamType.VEC2, 0f, 1f, 0.5f);
        }

        for (Object[] row : NAME_HINTS) {
            if (!hasWord(words, (String) row[0])) {
                continue;
            }
            ParamType t = (ParamType) row[1];
            // 声明成 int 的就得留在整数控件上，哪怕关键词表说它是 float
            if (declared == ParamType.INT && t != ParamType.INT) {
                t = ParamType.INT;
            }
            return new Hint(t, (Float) row[2], (Float) row[3], (Float) row[4]);
        }

        // 兜底：和启发式引入之前完全一致
        return declared == ParamType.INT
                ? new Hint(ParamType.INT, 0f, 16f, 1f)
                : new Hint(ParamType.FLOAT, 0f, 1f, 0.5f);
    }

    /**
     * 把一个参数写回成一行 {@code // @param} 注解。
     *
     * <p>必须是<b>一行</b>：它要顶替掉原来那行裸 uniform 声明，多一行少一行都会让
     * 编译器报的行号对不上作者看到的源码。
     */
    public static String annotationFor(ShaderParam p) {
        StringBuilder sb = new StringBuilder("// @param name=").append(p.name())
                .append(" type=").append(typeKeyword(p.type()))
                .append(" min=").append(trim(p.min()))
                .append(" max=").append(trim(p.max()))
                .append(" default=");
        float[] def = p.defaults();
        for (int i = 0; i < p.type().components(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(trim(def[i]));
        }
        appendDriver(sb, p.driver());
        return sb.toString();
    }

    private static void appendDriver(StringBuilder sb, ParamDriver d) {
        if (d == null) {
            return;
        }
        sb.append(" drive=").append(d.wave().id())
                .append(" drive_from=").append(trim(d.from()))
                .append(" drive_to=").append(trim(d.to()))
                .append(" drive_period=").append(trim(d.period()))
                .append(" drive_phase=").append(trim(d.phase()));
    }

    /**
     * 把某个参数的驱动器写回源码，返回改好的源码。
     *
     * <p>改的是那一行 {@code @param} 注解：去掉旧的 {@code drive*} 键，再按需追加新的，
     * 其余键原样保留——标签、说明、分组都不能因为开关一个驱动器而丢掉。
     * 裸 {@code uniform float X;} 没有地方写，就整行换成一行等价注解，和 {@link #scan}
     * 提升裸声明是同一个做法：行数不变，报错行号仍然对得上。
     *
     * @param driver null 表示关掉驱动器
     * @return 改好的源码；找不到这个参数时返回 null
     */
    public static String withDriver(String source, String paramName, ParamDriver driver) {
        if (source == null || paramName == null) {
            return null;
        }
        String[] lines = source.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            Matcher m = ANNOTATION.matcher(lines[i]);
            if (!m.matches() || !paramName.equals(annotationName(m.group(1)))) {
                continue;
            }
            StringBuilder sb = new StringBuilder(DRIVE_KEYS.matcher(lines[i]).replaceAll("").stripTrailing());
            appendDriver(sb, driver);
            lines[i] = sb.toString();
            return String.join("\n", lines);
        }
        for (int i = 0; i < lines.length; i++) {
            Matcher bare = BARE_UNIFORM.matcher(lines[i]);
            if (!bare.matches() || !paramName.equals(bare.group(2))) {
                continue;
            }
            for (ShaderParam p : scan(source).params()) {
                if (p.name().equals(paramName)) {
                    ShaderParam copy = p.copy();
                    copy.setDriver(driver);
                    lines[i] = annotationFor(copy);
                    return String.join("\n", lines);
                }
            }
        }
        return null;
    }

    private static String annotationName(String annotationRest) {
        Matcher m = KV.matcher(annotationRest);
        while (m.find()) {
            if (m.group(1).equalsIgnoreCase("name")) {
                String value = m.group(2) != null ? m.group(2) : m.group(3);
                return value == null ? null : value.trim();
            }
        }
        return null;
    }

    private static String typeKeyword(ParamType type) {
        return switch (type) {
            case BOOL -> "bool";
            case COLOR3 -> "color3";
            case COLOR4 -> "color4";
            case INT -> "int";
            case ANCHOR -> "anchor";
            case VEC2 -> "vec2";
            case VEC3 -> "vec3";
            case VEC4 -> "vec4";
            default -> "float";
        };
    }

    private static String trim(float v) {
        if (v == Math.rint(v) && Math.abs(v) < 1e7f) {
            return Integer.toString((int) v);
        }
        return String.format(Locale.ROOT, "%.4f", v).replaceAll("0+$", "").replaceAll("\\.$", "");
    }

    private static float parseFloat(String s, float fallback) {
        if (s == null || s.isBlank()) {
            return fallback;
        }
        try {
            return Float.parseFloat(s.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /**
     * 解析默认值。支持三种写法：
     * {@code #99CCFF} 十六进制颜色、{@code 1.0,0.5,0.2} 逗号分量、{@code 2.6} 单标量。
     */
    public static float[] parseValue(String raw, ParamType type) {
        float[] out = new float[4];
        if (type.isColor()) {
            // 颜色默认给白色而不是黑色：黑色会让"加了个颜色参数"直接把画面压黑，误导性太强。
            out[0] = out[1] = out[2] = out[3] = 1f;
        }
        if (raw == null || raw.isBlank()) {
            return out;
        }
        String s = raw.trim();

        if (s.startsWith("#") || s.startsWith("0x") || s.startsWith("0X")) {
            String hex = s.startsWith("#") ? s.substring(1) : s.substring(2);
            try {
                long v = Long.parseLong(hex, 16);
                if (hex.length() <= 6) {
                    out[0] = ((v >> 16) & 0xFF) / 255f;
                    out[1] = ((v >> 8) & 0xFF) / 255f;
                    out[2] = (v & 0xFF) / 255f;
                    out[3] = 1f;
                } else {
                    out[0] = ((v >> 24) & 0xFF) / 255f;
                    out[1] = ((v >> 16) & 0xFF) / 255f;
                    out[2] = ((v >> 8) & 0xFF) / 255f;
                    out[3] = (v & 0xFF) / 255f;
                }
            } catch (NumberFormatException ignored) {
                // 保持默认值
            }
            return out;
        }

        String cleaned = s.replace("(", "").replace(")", "").replace("vec2", "")
                .replace("vec3", "").replace("vec4", "").trim();
        String[] parts = cleaned.split("[,\\s]+");
        int n = Math.min(parts.length, 4);
        boolean any = false;
        for (int i = 0; i < n; i++) {
            if (parts[i].isBlank()) {
                continue;
            }
            try {
                out[i] = Float.parseFloat(parts[i]);
                any = true;
            } catch (NumberFormatException ignored) {
                // 跳过无法解析的分量
            }
        }
        // 单个标量填给颜色时，理解为灰度，三个分量都填上。
        if (any && type.isColor() && n == 1) {
            out[1] = out[0];
            out[2] = out[0];
            out[3] = 1f;
        }
        return out;
    }
}
