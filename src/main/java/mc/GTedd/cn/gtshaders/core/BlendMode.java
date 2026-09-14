package mc.GTedd.cn.gtshaders.core;

/**
 * 效果层与下层画面的混合方式。
 *
 * <p>对应 MasterGo 图层面板上的「混合模式」。这不是装饰性的映射——后处理效果本来就是
 * 「拿到已渲染的画面，算出新画面」，把「怎么把算出来的结果合回原画面」独立成一个可选项，
 * 作者就能用同一份源码试出完全不同的观感，而不必回去改代码。
 *
 * <p>混合是<b>编译期</b>生成到着色器里的（每种模式一段表达式），强度则是运行时 uniform，
 * 所以切模式要重编译，拖强度不用。
 */
public enum BlendMode {
    /** 直接用效果结果替换原画面。 */
    NORMAL("normal", "result"),
    MULTIPLY("multiply", "base * result"),
    SCREEN("screen", "1.0 - (1.0 - base) * (1.0 - result)"),
    ADD("add", "base + result"),
    /**
     * 逐分量的 overlay：base 暗处走 multiply、亮处走 screen。
     * 用 mix + step 写成无分支形式，避免在片段着色器里引入动态分支。
     */
    OVERLAY("overlay", "mix(2.0 * base * result, 1.0 - 2.0 * (1.0 - base) * (1.0 - result), step(0.5, base))");

    private final String id;
    private final String expression;

    BlendMode(String id, String expression) {
        this.id = id;
        this.expression = expression;
    }

    public String id() {
        return id;
    }

    /** 以 {@code base} 与 {@code result} 两个 vec3 为输入的混合表达式。 */
    public String expression() {
        return expression;
    }

    public String translationKey() {
        return "gtshaders.blend." + id;
    }

    public BlendMode next() {
        BlendMode[] all = values();
        return all[(ordinal() + 1) % all.length];
    }

    public static BlendMode byId(String id) {
        for (BlendMode m : values()) {
            if (m.id.equalsIgnoreCase(id)) {
                return m;
            }
        }
        return NORMAL;
    }
}
