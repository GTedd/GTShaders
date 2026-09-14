package mc.GTedd.cn.gtshaders.core;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 一个可视化参数：从作者源码里的 {@code // @param} 注解扫描出来，自动生成对应控件。
 *
 * <p>值统一用 float[4] 存，按 {@link ParamType#components()} 取前 N 个分量。整数类型也存在 float
 * 里，写出时再取整——省掉一整套分支，代价只是 2^24 以上的整数会失真，而这在着色器参数里不会发生。
 *
 * <p>显示名走 {@link #labels}（语言代码 → 文案）。作者可以直接在注解里写多语言，
 * 也可以只写一个 key 交给 mod 的 lang 文件翻译；两条路都通，见 {@link #resolveLabel}。
 */
public final class ShaderParam {

    private final String name;
    private final ParamType type;
    private final float min;
    private final float max;
    private final float[] defaults = new float[4];
    private final float[] value = new float[4];
    /** 语言代码（如 zh_cn）→ 显示名。空表示回退到 name。 */
    private final Map<String, String> labels = new LinkedHashMap<>();
    /** 语言代码 → 一句话说明，鼠标停在参数上时显示。空表示这个参数没有说明。 */
    private final Map<String, String> descs = new LinkedHashMap<>();
    /** 语言代码 → 所属分组名。空表示不分组，参数面板把它归到「常规」。 */
    private final Map<String, String> groups = new LinkedHashMap<>();
    /** 可选的 i18n key，优先级低于 labels 里的直接文案。 */
    private String labelKey;
    /** 该参数在源码中出现的行号，用于点击参数跳转到源码。 */
    private int sourceLine = -1;
    /** 范围与类型是不是猜出来的。猜的要在界面上标一下，作者才知道该不该写死。 */
    private boolean inferred;

    /**
     * 只对 {@link ParamType#ANCHOR} 有意义：这个参数指向哪一条锚点绑定的<b>稳定 id</b>。
     *
     * <p>空串表示「按裸数字用」——老工程、以及用户手工把类型改成 anchor 之前存下的值
     * 都是这种情况，此时 {@link #value}{@code [0]} 就是最终槽位号。
     *
     * <p>非空时，{@code value[0]} 是一份<b>缓存</b>：由 {@code ShaderProject.resolveAnchorRefs}
     * 在编译和上传 uniform 之前按当前绑定顺序重算写回。直接读 {@code value[0]} 永远拿得到
     * 正确的槽位号，所以 codegen、uniform 上传、资源包导出这三条路径都不需要知道 anchorRef 的存在。
     */
    private String anchorRef = "";
    /**
     * 驱动器。非 null 时这个参数的值由时间函数算出，面板上的滑块值不再参与画面。
     * 来源是源码里的 {@code drive=} 注解，所以它和标签、说明一样算元数据。
     */
    private ParamDriver driver;

    public ShaderParam(String name, ParamType type, float min, float max) {
        this.name = name;
        this.type = type;
        this.min = min;
        this.max = max;
    }

    public String name() {
        return name;
    }

    public ParamType type() {
        return type;
    }

    public float min() {
        return min;
    }

    public float max() {
        return max;
    }

    public int sourceLine() {
        return sourceLine;
    }

    public void setSourceLine(int line) {
        this.sourceLine = line;
    }

    public String labelKey() {
        return labelKey;
    }

    public void setLabelKey(String labelKey) {
        this.labelKey = labelKey;
    }

    public Map<String, String> labels() {
        return labels;
    }

    public void putLabel(String lang, String text) {
        labels.put(lang.toLowerCase(Locale.ROOT), text);
    }

    /**
     * 解析显示名。顺序：当前语言的直接文案 → en_us 直接文案 → 任意直接文案 → i18n key 的翻译
     * 结果（由调用方传入 translator）→ 参数名本身。
     *
     * @param lang       当前语言代码，如 {@code zh_cn}
     * @param translator i18n key 翻译器；返回 null 表示没有该 key
     */
    public String resolveLabel(String lang, java.util.function.Function<String, String> translator) {
        String direct = labels.get(lang.toLowerCase(Locale.ROOT));
        if (direct != null && !direct.isBlank()) {
            return direct;
        }
        String fallback = labels.get("en_us");
        if (fallback != null && !fallback.isBlank()) {
            return fallback;
        }
        if (!labels.isEmpty()) {
            return labels.values().iterator().next();
        }
        if (labelKey != null && translator != null) {
            String translated = translator.apply(labelKey);
            if (translated != null && !translated.isBlank()) {
                return translated;
            }
        }
        return name;
    }

    public Map<String, String> descs() {
        return descs;
    }

    public void putDesc(String lang, String text) {
        descs.put(lang.toLowerCase(Locale.ROOT), text);
    }

    /** 说明文案；没有就返回空串。回退链与显示名一致。 */
    public String resolveDesc(String lang) {
        return pick(descs, lang);
    }

    public Map<String, String> groups() {
        return groups;
    }

    public void putGroup(String lang, String text) {
        groups.put(lang.toLowerCase(Locale.ROOT), text);
    }

    /** 分组名；没写 {@code @group} 时返回空串，由界面决定归到哪一组。 */
    public String resolveGroup(String lang) {
        return pick(groups, lang);
    }

    /**
     * 分组的稳定标识。用<b>英文名或首个写下的名字</b>而不是当前语言下的显示名，
     * 否则切一次语言，折叠状态与排序就全乱了。
     */
    public String groupKey() {
        if (groups.isEmpty()) {
            return "";
        }
        String en = groups.get("en_us");
        return en != null && !en.isBlank() ? en : groups.values().iterator().next();
    }

    public boolean isInferred() {
        return inferred;
    }

    /** 指向的锚点绑定 id；空串表示按裸数字用。仅 {@link ParamType#ANCHOR} 有意义。 */
    public String anchorRef() {
        return anchorRef;
    }

    public void setAnchorRef(String ref) {
        this.anchorRef = ref == null ? "" : ref;
    }

    public ParamDriver driver() {
        return driver;
    }

    public void setDriver(ParamDriver driver) {
        this.driver = driver;
    }

    public boolean isDriven() {
        return driver != null;
    }

    public void setInferred(boolean inferred) {
        this.inferred = inferred;
    }

    private static String pick(Map<String, String> map, String lang) {
        String direct = map.get(lang.toLowerCase(Locale.ROOT));
        if (direct != null && !direct.isBlank()) {
            return direct;
        }
        String fallback = map.get("en_us");
        if (fallback != null && !fallback.isBlank()) {
            return fallback;
        }
        return map.isEmpty() ? "" : map.values().iterator().next();
    }

    public float[] defaults() {
        return defaults;
    }

    public float[] value() {
        return value;
    }

    public float get(int component) {
        return value[component];
    }

    public void set(int component, float v) {
        value[component] = clampComponent(v);
    }

    public void setAll(float[] src) {
        for (int i = 0; i < 4; i++) {
            value[i] = i < src.length ? clampComponent(src[i]) : 0f;
        }
    }

    public void setDefaults(float[] src) {
        for (int i = 0; i < 4; i++) {
            defaults[i] = i < src.length ? src[i] : 0f;
        }
    }

    public void resetToDefault() {
        System.arraycopy(defaults, 0, value, 0, 4);
    }

    private float clampComponent(float v) {
        // 颜色分量恒定在 0..1，其余类型受 min/max 约束。
        if (type.isColor()) {
            return Math.max(0f, Math.min(1f, v));
        }
        if (min < max) {
            return Math.max(min, Math.min(max, v));
        }
        return v;
    }

    /**
     * 把当前值归一化到 0..1，供滑块使用。颜色分量本身就是 0..1。
     */
    public float normalized(int component) {
        if (type.isColor() || !(min < max)) {
            return Math.max(0f, Math.min(1f, value[component]));
        }
        return (value[component] - min) / (max - min);
    }

    public void setNormalized(int component, float t) {
        if (type.isColor() || !(min < max)) {
            set(component, Math.max(0f, Math.min(1f, t)));
        } else {
            set(component, min + t * (max - min));
        }
    }

    /** 用于 UI 上的数值显示：整数不带小数点，其余保留两位。 */
    public String formatValue(int component) {
        float v = value[component];
        if (type.isInteger() || type == ParamType.BOOL) {
            return Integer.toString(Math.round(v));
        }
        return String.format(Locale.ROOT, "%.2f", v);
    }

    /** 打包成 0xRRGGBB，仅对颜色类型有意义。 */
    public int packedRgb() {
        int r = Math.round(Math.max(0f, Math.min(1f, value[0])) * 255f);
        int g = Math.round(Math.max(0f, Math.min(1f, value[1])) * 255f);
        int b = Math.round(Math.max(0f, Math.min(1f, value[2])) * 255f);
        return (r << 16) | (g << 8) | b;
    }

    public String hex() {
        return String.format(Locale.ROOT, "%06X", packedRgb());
    }

    /**
     * 接收一次重扫带来的新元数据，<b>保留当前值和对象身份</b>。
     *
     * <p>为什么必须保留对象本身：运行时每帧写 uniform 用的是编译那一刻存下来的
     * {@code ShaderParam} 引用。重扫要是无脑换一批新对象，面板上拖的就成了孤儿——
     * 改了没反应，而且要等下一次编译完成才会恢复。作者改一行注释就会触发重扫，
     * 所以这个窗口在实际使用中相当常见。
     *
     * <p>值不跟着换：作者改的是源码，不是「把我调好的颜色恢复成默认」。
     */
    public void adoptMetadata(ShaderParam fresh) {
        System.arraycopy(fresh.defaults, 0, this.defaults, 0, 4);
        labels.clear();
        labels.putAll(fresh.labels);
        descs.clear();
        descs.putAll(fresh.descs);
        groups.clear();
        groups.putAll(fresh.groups);
        this.labelKey = fresh.labelKey;
        this.sourceLine = fresh.sourceLine;
        this.inferred = fresh.inferred;
        this.driver = fresh.driver;
    }

    /** 两个参数的「可调性」是不是完全一样：类型与取值范围都一致，控件就不用重建。 */
    public boolean sameShapeAs(ShaderParam other) {
        return type == other.type
                && Float.compare(min, other.min) == 0
                && Float.compare(max, other.max) == 0;
    }

    public ShaderParam copy() {
        ShaderParam c = new ShaderParam(name, type, min, max);
        System.arraycopy(defaults, 0, c.defaults, 0, 4);
        System.arraycopy(value, 0, c.value, 0, 4);
        c.labels.putAll(labels);
        c.descs.putAll(descs);
        c.groups.putAll(groups);
        c.labelKey = labelKey;
        c.sourceLine = sourceLine;
        c.inferred = inferred;
        c.driver = driver;
        return c;
    }
}
