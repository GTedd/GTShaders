package mc.GTedd.cn.gtshaders.ai;

import org.junit.jupiter.api.Test;
import mc.GTedd.cn.gtshaders.library.EffectLibrary;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 拿整个内置效果库当语料，把 {@link PitfallCheck} 的误报率钉在零。
 *
 * <h2>为什么这条测试比其它任何一条都重要</h2>
 *
 * <p>陷阱检查的每一条判据都是启发式的，而启发式只有两个失败方向：漏报和误报。
 * 漏报的代价是回到「没有这道检查」的状态；<b>误报的代价要大得多</b>——
 * 它会让流水线在一份本来完全没问题的着色器上白跑一轮生成，花玩家的钱、
 * 让他多等十几秒，而那一轮很可能把能用的东西改坏。
 *
 * <p>库里这一百多个效果是人工写的、经过 {@code glslcheck} 真机编译、
 * 也经过 {@code effectprobe} 实测画面差异的成品。<b>它们全都是正样本。</b>
 * 判据在它们身上出声，就一定是判据错了，不是效果错了。
 *
 * <p>所以这条测试的实际作用是：任何人往 PitfallCheck 里加判据、或者放宽某个阈值时，
 * 立刻就能知道自己有没有波及到正常的写法。没有它，判据只会越加越松，
 * 直到玩家开始抱怨「它老是无缘无故重写我的效果」。
 */
class PitfallCorpusTest {

    /** 语料规模的下限。库缩水到这个数以下，说明索引或资源出了问题，此时通过毫无意义。 */
    private static final int MIN_CORPUS = 100;

    @Test
    void noFalsePositiveAcrossTheWholeEffectLibrary() {
        List<EffectLibrary.Entry> entries = EffectLibrary.all();
        assertTrue(entries.size() >= MIN_CORPUS,
                "效果库只读到 " + entries.size() + " 个，语料不足，这条测试通过了也不说明任何事情");

        Map<String, List<String>> offenders = new LinkedHashMap<>();
        Map<String, Integer> byCode = new LinkedHashMap<>();
        int scanned = 0;

        for (EffectLibrary.Entry entry : entries) {
            String source = EffectLibrary.loadSource(entry.id());
            assertNotNull(source, "索引里有 " + entry.id() + " 但读不到源码");
            scanned++;
            List<PitfallCheck.Warning> warnings = PitfallCheck.scan(source);
            if (!warnings.isEmpty()) {
                List<String> codes = new ArrayList<>();
                for (PitfallCheck.Warning w : warnings) {
                    codes.add(w.code());
                    byCode.merge(w.code(), 1, Integer::sum);
                }
                offenders.put(entry.id(), codes);
            }
        }

        if (!offenders.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            sb.append("PitfallCheck 在 ").append(offenders.size()).append('/').append(scanned)
                    .append(" 个人工效果上误报了。\n判据命中次数：").append(byCode).append('\n');
            offenders.forEach((id, codes) ->
                    sb.append("  ").append(id).append(" -> ").append(String.join(", ", codes)).append('\n'));
            sb.append("\n这些效果都是编译通过、实测有效的正样本。要么收紧判据，"
                    + "要么这确实是个真问题——那就去修那个效果，别放宽判据。");
            throw new AssertionError(sb.toString());
        }
    }

    /**
     * 反向确认：判据不是因为「什么都不查」才不误报的。
     *
     * <p>上面那条测试有个退化解——把所有判据删掉，它一样绿。所以这里拿几份<b>确定有问题</b>的
     * 源码过一遍，确认每一类判据都还活着。两条测试必须一起看才有意义。
     */
    @Test
    void everyCheckStillFiresOnRealBreakage() {
        assertFires("no_frag_color", """
                // 没输出 / No Output
                // @param name=A type=float min=0 max=1 default=0.5
                // @param name=B type=float min=0 max=1 default=0.5
                // @param name=C type=float min=0 max=1 default=0.5
                void main() {
                    vec3 col = texture(InSampler, texCoord).rgb * A * B * C;
                }
                """);

        assertFires("one_shot", """
                // @param name=Duration type=float min=0.1 max=5 default=1
                // @param name=B type=float min=0 max=1 default=0.5
                // @param name=C type=float min=0 max=1 default=0.5
                void main() {
                    float t = clamp(GTTime / Duration, 0.0, 1.0);
                    fragColor = vec4(vec3(t * B * C), 1.0);
                }
                """);

        assertFires("absolute_threshold", """
                // @param name=A type=float min=0 max=1 default=0.5
                // @param name=B type=float min=0 max=1 default=0.5
                // @param name=C type=float min=0 max=1 default=0.5
                void main() {
                    vec3 col = texture(InSampler, texCoord).rgb;
                    float luma = dot(col, vec3(0.299, 0.587, 0.114));
                    fragColor = vec4(col * smoothstep(0.75, 0.95, luma) * A * B * C, 1.0);
                }
                """);

        assertFires("game_time", """
                // @param name=A type=float min=0 max=1 default=0.5
                // @param name=B type=float min=0 max=1 default=0.5
                // @param name=C type=float min=0 max=1 default=0.5
                void main() {
                    float w = sin(GameTime * 40.0) * A * B * C;
                    fragColor = vec4(vec3(w), 1.0);
                }
                """);

        assertFires("screen_size_texel", """
                // @param name=A type=float min=0 max=1 default=0.5
                // @param name=B type=float min=0 max=1 default=0.5
                // @param name=C type=float min=0 max=1 default=0.5
                void main() {
                    vec2 px = 1.0 / ScreenSize;
                    fragColor = texture(InSampler, texCoord + px * A * B * C);
                }
                """);

        assertFires("reserved_name", """
                // @param name=OutSize type=vec2 min=0 max=1 default=0.5,0.5
                // @param name=B type=float min=0 max=1 default=0.5
                // @param name=C type=float min=0 max=1 default=0.5
                void main() {
                    fragColor = vec4(vec3(OutSize.x * B * C), 1.0);
                }
                """);

        // 这一条是全场最恶劣的故障：面板上滑块好好地摆着，拖动毫无反应，而编译一声不响
        assertFires("unused_param", """
                // @param name=Used type=float min=0 max=1 default=0.5
                // @param name=Forgotten type=float min=0 max=1 default=0.5
                // @param name=AlsoUsed type=float min=0 max=1 default=0.5
                void main() {
                    fragColor = vec4(texture(InSampler, texCoord).rgb * Used * AlsoUsed, 1.0);
                }
                """);

        assertFires("too_few_params", """
                // @param name=Only type=float min=0 max=1 default=0.5
                void main() {
                    fragColor = vec4(texture(InSampler, texCoord).rgb * Only, 1.0);
                }
                """);

        // 注解写错了，那一条参数会悄悄消失或退化成 float 滑块
        assertFires("bad_annotation", """
                // @param type=float min=0 max=1 default=0.5
                // @param name=B type=float min=0 max=1 default=0.5
                // @param name=C type=float min=0 max=1 default=0.5
                // @param name=D type=float min=0 max=1 default=0.5
                void main() {
                    fragColor = vec4(vec3(B * C * D), 1.0);
                }
                """);
    }

    private static void assertFires(String code, String source) {
        List<PitfallCheck.Warning> warnings = PitfallCheck.scan(source);
        boolean hit = warnings.stream().anyMatch(w -> w.code().equals(code));
        assertTrue(hit, "判据 " + code + " 没有对确定有问题的源码出声，实际命中："
                + warnings.stream().map(PitfallCheck.Warning::code).toList());
    }

    /** 每条判据都得有中文说明，否则回喂给模型的是一句空话。 */
    @Test
    void everyWarningCarriesAnActionableHint() {
        List<PitfallCheck.Warning> warnings = PitfallCheck.scan("void main() { }");
        assertFalse(warnings.isEmpty(), "空 main 应当至少触发 no_frag_color 和 too_few_params");
        for (PitfallCheck.Warning w : warnings) {
            assertFalse(w.hint().isBlank(), w.code() + " 没有说明");
            assertTrue(w.hint().length() > 20, w.code() + " 的说明太短，模型看不出该怎么改：" + w.hint());
            assertFalse(w.hint().contains("<b>"), w.code() + " 的说明里混进了 HTML 标签：" + w.hint());
        }
    }
}
