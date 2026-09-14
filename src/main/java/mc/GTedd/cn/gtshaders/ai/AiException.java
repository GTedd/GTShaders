package mc.GTedd.cn.gtshaders.ai;

/**
 * 一次 AI 调用的失败，已经归类到玩家能看懂的几种。
 *
 * <h2>为什么要分类而不是把服务端那句话直接显示出来</h2>
 *
 * <p>服务端返回的是给开发者看的英文，而且各家措辞完全不同：同样是余额不够，
 * 一家说 {@code Insufficient Balance}，一家说 {@code quota exceeded}，中转服务还可能
 * 原样透传一段 HTML。玩家看到这些只会去做无用功——换模型、重启游戏、重装 mod，
 * 唯独想不到该去充值。
 *
 * <p>所以在这里归类，界面按 {@link Kind} 给出「该做什么」的指引，
 * 原文塞进 {@link #detail()} 供想看的人展开。
 */
public final class AiException extends RuntimeException {

    public enum Kind {
        /** key 缺失、写错或已失效。 */
        AUTH,
        /** 余额不足 / 配额用尽。 */
        QUOTA,
        /** 触发限流，等一会儿再试。 */
        RATE_LIMIT,
        /** 地址或模型名不对，404 了。 */
        NOT_FOUND,
        /** 连不上：断网、DNS、被墙、代理没开。 */
        NETWORK,
        /** 连上了但太慢，超时。 */
        TIMEOUT,
        /** 服务端自己出错。 */
        SERVER,
        /** 通了、也返回了，但内容不是我们能用的东西。 */
        BAD_RESPONSE,
        /**
         * 输出被 max_output_tokens 截断。
         *
         * <p>单列成一类是因为它<b>可恢复</b>：把上限调大重试一次通常就好。
         * 归进 BAD_RESPONSE 的话，上层只能当成「模型不会写」而直接失败，
         * 玩家看到的是一句「返回的内容用不了」——他完全不知道该去调哪个数。
         *
         * <p>最常见的成因不是着色器太长，而是<b>推理模型的思维链吃掉了全部预算</b>：
         * reasoning token 也算进这个上限，正文还没开始写就没额度了。
         */
        TRUNCATED,
        /** 玩家自己按了取消。 */
        CANCELLED
    }

    private final Kind kind;
    private final String detail;

    public AiException(Kind kind, String detail) {
        this(kind, detail, null);
    }

    public AiException(Kind kind, String detail, Throwable cause) {
        super(kind + ": " + detail, cause);
        this.kind = kind;
        this.detail = detail == null ? "" : detail;
    }

    public Kind kind() {
        return kind;
    }

    /** 服务端原文或异常原文。可能很长，界面上要折叠。 */
    public String detail() {
        return detail;
    }

    /** 界面用的翻译键，与 {@link Kind} 一一对应。 */
    public String langKey() {
        return "gtshaders.ai.error." + kind.name().toLowerCase(java.util.Locale.ROOT);
    }
}
