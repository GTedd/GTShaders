package mc.GTedd.cn.gtshaders;

import org.junit.jupiter.api.Test;
import mc.GTedd.cn.gtshaders.library.EffectLibrary;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 全库扫描：极角不能<b>裸着</b>喂进噪声函数。
 *
 * <h2>这条测试在防什么</h2>
 *
 * <p>{@code atan(y, x)} 的值域是 {@code (-π, π]}，在负 x 轴上从 {@code +π} 跳到 {@code -π}。
 * 三角函数吃得下这个跳变——{@code sin(θ * 4.0)} 里 4 是整数，绕一圈正好闭合；
 * 但<b>噪声不是周期函数</b>，跳变两侧取到的是毫不相干的两个值。
 * 屏幕上的症状是：以效果中心为原点，<b>正左方劈出一条缝</b>。
 *
 * <p>这道缝在 {@code field/singularity.fsh} 和 {@code field/domain.fsh} 里躺了很久没被找到根因。
 * 它不报错、不影响编译，只是画面上多一条细线，很容易被当成"贴图接缝"或者"显卡的事"。
 *
 * <p>一旦知道成因，修法只有一句：<b>先把角度映射到单位圆上再采噪声</b>。
 * {@code vec2(cos(θ), sin(θ))} 在 {@code θ = ±π} 处都等于 {@code (-1, 0)}，天然闭合。
 * {@code horror/creepdark.fsh} 是库里的标准写法，照抄它即可。
 *
 * <p>所以这条测试认的不是"用没用 {@code atan}"，而是"{@code atan} 的结果有没有
 * <b>不经圆映射</b>就进噪声"。包在 {@code cos()} / {@code sin()} 里的用法是对的，不该报。
 */
class ShaderSeamTest {

    /**
     * 库里的噪声实现是各效果自给自足的，命名五花八门（{@code gtfbm} / {@code noise} /
     * {@code gtrand} / {@code hash}…）。按「名字里带这几个词」来抓，宁可多抓也不能漏——
     * 漏掉一个就等于这条测试不存在。
     */
    private static final Pattern NOISE_CALL = Pattern.compile(
            "\\b(\\w*(?:fbm|noise|rand|hash)\\w*)\\s*\\(([^;]{0,160})",
            Pattern.CASE_INSENSITIVE);

    /** 装着 {@code atan(...)} 结果的变量，就是极角。 */
    private static final Pattern ANGLE_DECL = Pattern.compile(
            "(?:float\\s+)?(\\w+)\\s*=\\s*[^;]*\\batan\\(");

    /** 圆映射：{@code cos(a)} / {@code sin(a)}。这是<b>正确</b>写法，扫描时要先剔掉。 */
    private static final Pattern CIRCLE_MAP = Pattern.compile(
            "\\b(?:cos|sin)\\s*\\(\\s*\\w+\\s*\\)");

    @Test
    void 极角不能裸着喂进噪声否则效果正左方会劈开一条缝() {
        List<String> bad = new ArrayList<>();

        for (EffectLibrary.Entry e : EffectLibrary.all()) {
            String src = EffectLibrary.loadSource(e.id());
            assertNotNull(src, e.id() + " 在索引里但读不到源码");
            // 注释里写 atan 或 noise 不该触发这条检查
            String body = stripLineComments(src);

            List<String> angles = new ArrayList<>();
            Matcher decl = ANGLE_DECL.matcher(body);
            while (decl.find()) {
                angles.add(decl.group(1));
            }
            if (angles.isEmpty()) {
                continue;
            }

            Matcher call = NOISE_CALL.matcher(body);
            while (call.find()) {
                String args = CIRCLE_MAP.matcher(call.group(2)).replaceAll("");
                for (String angle : angles) {
                    if (containsWord(args, angle)) {
                        bad.add(e.id() + " 把极角 " + angle + " 裸着喂进了 "
                                + call.group(1) + "()");
                    }
                }
            }
        }

        assertTrue(bad.isEmpty(), buildMessage(bad));
    }

    /**
     * 反过来钉一条：正确写法不能被误判。
     *
     * <p>没有这一条的话，上面那条测试最省事的"修法"就是把圆映射也算成违规，
     * 于是全库改成不用噪声——缝是没了，效果也平了。
     */
    @Test
    void 圆映射的正确写法不会被误报() {
        String ok = """
                float ang = atan(d.y, d.x);
                float wob = noise(vec2(cos(ang), sin(ang)) * 2.5 + GTTime * 0.3);
                """;
        Matcher call = NOISE_CALL.matcher(ok);
        assertTrue(call.find());
        String args = CIRCLE_MAP.matcher(call.group(2)).replaceAll("");
        assertTrue(!containsWord(args, "ang"), "圆映射是标准修法，不该被判成违规");
    }

    /** 再钉一条：真正的裸用必须被抓到，否则上面那条就是摆设。 */
    @Test
    void 裸用极角一定会被抓到() {
        String bad = """
                float angd = atan(d.y, d.x);
                float swirl = sin(angd * 4.0 + gtfbm(vec2(angd * 2.0, GTTime * 0.6)));
                """;
        Matcher call = NOISE_CALL.matcher(bad);
        boolean caught = false;
        while (call.find()) {
            String args = CIRCLE_MAP.matcher(call.group(2)).replaceAll("");
            if (containsWord(args, "angd")) {
                caught = true;
            }
        }
        assertTrue(caught, "这正是 singularity.fsh 修好之前的写法，必须报出来");
    }

    private static boolean containsWord(String text, String word) {
        return Pattern.compile("\\b" + Pattern.quote(word) + "\\b").matcher(text).find();
    }

    private static String stripLineComments(String src) {
        StringBuilder sb = new StringBuilder(src.length());
        for (String line : src.split("\n", -1)) {
            int i = line.indexOf("//");
            sb.append(i >= 0 ? line.substring(0, i) : line).append('\n');
        }
        return sb.toString();
    }

    private static String buildMessage(List<String> bad) {
        StringBuilder sb = new StringBuilder();
        sb.append("极角在 ±π 处跳变，噪声两侧取值无关，效果中心正左方会出现一条缝。\n");
        sb.append("改法：vec2(cos(角), sin(角)) * 频率 + 时间偏移，见 horror/creepdark.fsh\n");
        for (String b : bad) {
            sb.append("  - ").append(b).append('\n');
        }
        return sb.toString();
    }
}
