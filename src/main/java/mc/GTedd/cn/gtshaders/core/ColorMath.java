package mc.GTedd.cn.gtshaders.core;

/**
 * HSV ↔ RGB 换算。
 *
 * <p>刻意放在 {@code core} 而不是 {@code ui}：取色器要用它，单元测试也要用它，
 * 而测试跑不起 Minecraft 的类。纯算术不该被拖进 GUI 的依赖里。
 *
 * <p>所有分量都是 0..1。色相超出范围时按环形回绕，调用方不必自己取模。
 */
public final class ColorMath {

    private ColorMath() {
    }

    public static float[] hsvToRgb(float h, float s, float v) {
        h = (h % 1f + 1f) % 1f;
        float i = (float) Math.floor(h * 6f);
        float f = h * 6f - i;
        float p = v * (1f - s);
        float q = v * (1f - f * s);
        float t = v * (1f - (1f - f) * s);
        return switch ((int) i % 6) {
            case 0 -> new float[]{v, t, p};
            case 1 -> new float[]{q, v, p};
            case 2 -> new float[]{p, v, t};
            case 3 -> new float[]{p, q, v};
            case 4 -> new float[]{t, p, v};
            default -> new float[]{v, p, q};
        };
    }

    /** @return {@code {h, s, v}}；灰阶时 h 为 0（灰色本来就没有色相可言） */
    public static float[] rgbToHsv(float r, float g, float b) {
        float max = Math.max(r, Math.max(g, b));
        float min = Math.min(r, Math.min(g, b));
        float d = max - min;
        float h = 0f;
        if (d > 1e-6f) {
            if (max == r) {
                h = ((g - b) / d) / 6f;
            } else if (max == g) {
                h = (2f + (b - r) / d) / 6f;
            } else {
                h = (4f + (r - g) / d) / 6f;
            }
            h = (h % 1f + 1f) % 1f;
        }
        return new float[]{h, max <= 1e-6f ? 0f : d / max, max};
    }

    /**
     * 反算色相，灰阶时沿用旧值。
     *
     * <p>这个回退是必要的：纯黑纯白没有色相，若老实返回 0，把明度拖到底再拖回来
     * 颜色就会从原本的蓝突然变成红——用户会觉得取色器"自己乱跳"。
     */
    public static float hueOf(float r, float g, float b, float previous) {
        float max = Math.max(r, Math.max(g, b));
        float min = Math.min(r, Math.min(g, b));
        return max - min <= 1e-6f ? previous : rgbToHsv(r, g, b)[0];
    }
}
