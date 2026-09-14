package mc.GTedd.cn.gtshaders.core;

import java.util.Locale;

/**
 * 着色器参数类型。
 *
 * <p>刻意只覆盖原版 post effect uniform 支持的封闭集合（int / float / ivec3 / vec2 / vec3 / vec4 /
 * matrix4x4），再加上 GTShaders 自己的语义化包装（color3 / color4 / bool）。因为这是个封闭集合，
 * 参数识别不需要上完整 GLSL 语法分析，一个词法级扫描器就足够且更鲁棒。
 *
 * <p>{@link #glslType()} 是写进 GLSL uniform 块的类型，{@link #jsonType()} 是写进 post effect
 * JSON 的类型——两者对 color / bool 这类语义包装并不相同，这正是包装存在的意义：
 * UI 上是取色器和开关，落到管线里仍是原版认得的 vec3 / float。
 */
public enum ParamType {
    FLOAT("float", "float", 1),
    INT("int", "int", 1),
    /** UI 呈现为开关，落地为 float 0.0/1.0——避开 bool 在 std140 里的实现差异。 */
    BOOL("float", "float", 1),
    VEC2("vec2", "vec2", 2),
    VEC3("vec3", "vec3", 3),
    VEC4("vec4", "vec4", 4),
    /** UI 呈现为取色器（RGB），落地为 vec3。 */
    COLOR3("vec3", "vec3", 3),
    /** UI 呈现为取色器（RGBA），落地为 vec4。 */
    COLOR4("vec4", "vec4", 4),
    /**
     * UI 呈现为「从工程的锚点绑定里选一条」的下拉，落地为 int 槽位号。
     *
     * <p>存在的理由是槽位号<b>会漂移</b>：{@code ShaderProject.anchorSlotBase} 按绑定在列表里的
     * 顺序累加 {@code maxSlots} 算出来，于是新增一条绑定、停用一条、上下移动顺序，都会让
     * 后面所有层手填的那个数字指向<b>别的目标</b>——而且是静默的，画面上只是效果跑到别处去了。
     *
     * <p>本类型的参数额外存一个 {@link ShaderParam#anchorRef()}（绑定的稳定 id），
     * 每次编译和上传 uniform 之前重新解析成当时的槽位号写回 {@code value[0]}。
     * 于是顺序怎么变都指得对，而落到管线里仍然只是一个 int，导出资源包时也不需要特殊处理。
     *
     * <p>{@code anchorRef} 为空时退化成普通 int——老工程存的就是裸数字，行为原样不变。
     */
    ANCHOR("int", "int", 1);

    private final String glslType;
    private final String jsonType;
    private final int components;

    ParamType(String glslType, String jsonType, int components) {
        this.glslType = glslType;
        this.jsonType = jsonType;
        this.components = components;
    }

    public String glslType() {
        return glslType;
    }

    public String jsonType() {
        return jsonType;
    }

    public int components() {
        return components;
    }

    public boolean isColor() {
        return this == COLOR3 || this == COLOR4;
    }

    public boolean isInteger() {
        return this == INT || this == ANCHOR;
    }

    /**
     * std140 对齐所需的基准对齐（以 4 字节为单位）。用于把参数按对齐降序排布，
     * 规避 vec3 后紧跟 float 之类的填充陷阱——原版自己会正确打包，但我们主动排序
     * 可以让生成的块布局稳定、可预测，也更容易人工核对。
     */
    public int std140AlignmentUnits() {
        return switch (components) {
            case 1 -> 1;
            case 2 -> 2;
            default -> 4;
        };
    }

    public static ParamType parse(String raw) {
        if (raw == null) {
            return null;
        }
        String key = raw.trim().toLowerCase(Locale.ROOT);
        return switch (key) {
            case "float" -> FLOAT;
            case "int" -> INT;
            case "bool", "boolean", "toggle" -> BOOL;
            case "vec2" -> VEC2;
            case "vec3" -> VEC3;
            case "vec4" -> VEC4;
            case "color", "color3", "rgb" -> COLOR3;
            case "color4", "rgba" -> COLOR4;
            case "anchor", "anchor_slot", "binding" -> ANCHOR;
            default -> null;
        };
    }
}
