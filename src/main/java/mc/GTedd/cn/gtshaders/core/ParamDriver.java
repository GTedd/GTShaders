package mc.GTedd.cn.gtshaders.core;

import java.util.Locale;

/**
 * 参数驱动器：让一个 float 参数随时间自己动，而不是停在滑块拖到的那个值上。
 *
 * <h2>为什么写进源码注解，而不是存在工程文件里</h2>
 *
 * <p>驱动器最终要在<b>纯资源包</b>里照样生效——导出的包里没有 mod，没人每帧改写 uniform。
 * 所以它必须被烤成 GLSL 表达式（{@code #define Strength gtDrive(...)}），而表达式的来源
 * 只能是源码本身。写在 {@code @param} 注解上还顺带解决了三件事：导出的 {@code .fsh} 拖回来
 * 驱动器不丢、快照与撤销天然覆盖、AI 与效果库也能直接写。
 *
 * <p>时间源是 {@code GTTime}：编辑器里跟着可暂停的时间轴走，资源包里是原版游戏时钟
 * （每 1200 秒回绕一次，周期不能整除 1200 时回绕处会跳一下）。
 *
 * <p>思路借鉴 SHADERed（dfranx，MIT）的 variable functions（一个 uniform 的值由函数算出），未复制代码。
 * 与它的区别是这里烤进源码而不是每帧写 uniform，理由见上一段。
 *
 * @param period 一个周期多少秒，下限 0.001
 * @param phase  相位偏移，按周期计（0..1）
 */
public record ParamDriver(Wave wave, float from, float to, float period, float phase) {

    public enum Wave {
        /** 余弦呼吸：从 {@code from} 平滑到 {@code to} 再回来。 */
        SINE("sine"),
        /** 三角波：匀速往返。 */
        TRIANGLE("triangle"),
        /** 方波：前半周期停在 {@code from}，后半周期停在 {@code to}。 */
        SQUARE("square"),
        /** 锯齿：匀速从 {@code from} 走到 {@code to} 后跳回。 */
        SAW("saw");

        private final String id;

        Wave(String id) {
            this.id = id;
        }

        public String id() {
            return id;
        }

        /** 传给着色器 {@code gtDrive} 的整数编号，顺序即 ordinal。 */
        public int index() {
            return ordinal();
        }

        public Wave next() {
            Wave[] all = values();
            return all[(ordinal() + 1) % all.length];
        }

        public String translationKey() {
            return "gtshaders.driver.wave." + id;
        }

        public static Wave parse(String raw) {
            if (raw == null) {
                return null;
            }
            String s = raw.trim().toLowerCase(Locale.ROOT);
            for (Wave w : values()) {
                if (w.id.equals(s)) {
                    return w;
                }
            }
            return null;
        }
    }

    public ParamDriver {
        if (wave == null) {
            wave = Wave.SINE;
        }
        if (!(period >= 0.001f)) {
            period = 0.001f;
        }
        if (!Float.isFinite(phase)) {
            phase = 0f;
        }
    }

    /** 一个新驱动器的起手值：在参数自己的取值范围里、两秒一个来回。 */
    public static ParamDriver defaultsFor(ShaderParam p) {
        float lo = p.min() < p.max() ? p.min() : 0f;
        float hi = p.min() < p.max() ? p.max() : 1f;
        return new ParamDriver(Wave.SINE, lo, hi, 2f, 0f);
    }

    public ParamDriver withWave(Wave w) {
        return new ParamDriver(w, from, to, period, phase);
    }

    public ParamDriver withFrom(float v) {
        return new ParamDriver(wave, v, to, period, phase);
    }

    public ParamDriver withTo(float v) {
        return new ParamDriver(wave, from, v, period, phase);
    }

    public ParamDriver withPeriod(float v) {
        return new ParamDriver(wave, from, to, v, phase);
    }

    public ParamDriver withPhase(float v) {
        return new ParamDriver(wave, from, to, period, v);
    }

    /**
     * Java 侧的同一个公式，给界面画「此刻驱动到了多少」。
     *
     * <p>必须与 {@code GlslCodegen} 里的 {@code gtDrive} 逐项一致，由 {@code ParamDriverTest} 钉住。
     */
    public float evaluate(float seconds) {
        double t = seconds / (double) period + phase;
        t = t - Math.floor(t);
        double w = switch (wave) {
            case TRIANGLE -> 1.0 - Math.abs(t * 2.0 - 1.0);
            case SQUARE -> t < 0.5 ? 0.0 : 1.0;
            case SAW -> t;
            case SINE -> 0.5 - 0.5 * Math.cos(t * Math.PI * 2.0);
        };
        return (float) (from + (to - from) * w);
    }
}
