package mc.GTedd.cn.gtshaders.codegen;

import mc.GTedd.cn.gtshaders.core.ShaderProject;
import mc.GTedd.cn.gtshaders.i18n.GtLang;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 找出「预览里有值、资源包里没有来源」的量。
 *
 * <p>预览与真实加载的差别只剩下一类：mod 每帧写进 uniform 的东西——世界锚点、载体、武器轨迹，
 * 以及 {@code GTSystem} 的帧间隔 / 帧序号 / 播放位。资源包里没有 mod，它们全是 JSON 里的常量 0。
 * 用到这些量的效果导出后<b>不会报错、只会退化</b>：锚点效果退回屏幕空间形态、刀光不出现、
 * 依赖帧间隔的积分停在初值。这种「安静地不一样」正是预览承诺要消灭的，所以在导出时、
 * 以及切到资源包视角时都把它们列出来。
 *
 * <p>时间不在此列：{@code GTTime} 在资源包里由原版 {@code GameTime} 驱动，两边一致
 * （见 {@link GlslCodegen} 的第 3 条设计决定）。
 */
public final class PackParity {

    /** @param layerName 哪一层；{@code what} 是已翻译的、给人看的一句话 */
    public record Note(String layerName, String what) {
        @Override
        public String toString() {
            return layerName + ": " + what;
        }
    }

    /** 只在编辑器里有意义的三个系统量，以及它的 Shadertoy 别名。 */
    private static final Pattern EDITOR_ONLY_SYSTEM = Pattern.compile(
            "\\b(GTDeltaTime|GTFrame|GTPlaying|iTimeDelta)\\b");

    private PackParity() {
    }

    /** 主链与轮廓链一起审。没有任何差异时返回空列表。 */
    public static List<Note> audit(ShaderProject.Build main, ShaderProject.Build outline) {
        return audit(main.passes(), outline.passes());
    }

    /** 同上，直接拿通道列表——预览运行时手里只有这个。 */
    public static List<Note> audit(List<ShaderProject.PassBuild> main,
                                   List<ShaderProject.PassBuild> outline) {
        List<Note> notes = new ArrayList<>();
        for (ShaderProject.PassBuild pass : main) {
            auditPass(pass, notes);
        }
        for (ShaderProject.PassBuild pass : outline) {
            auditPass(pass, notes);
        }
        return notes;
    }


    private static void auditPass(ShaderProject.PassBuild pass, List<Note> notes) {
        GlslCodegen.Output out = pass.output();
        String layer = pass.layer().name();
        if (out.usesAnchors()) {
            notes.add(new Note(layer, GtLang.get("gtshaders.parity.anchors")));
        }
        if (out.usesEmitters()) {
            notes.add(new Note(layer, GtLang.get("gtshaders.parity.emitters")));
        }
        if (out.usesTrails()) {
            notes.add(new Note(layer, GtLang.get("gtshaders.parity.trails")));
        }
        if (out.usesCamera()) {
            notes.add(new Note(layer, GtLang.get("gtshaders.parity.camera")));
        }
        Set<String> systems = new LinkedHashSet<>();
        var m = EDITOR_ONLY_SYSTEM.matcher(pass.layer().authorSource());
        while (m.find()) {
            systems.add(m.group(1));
        }
        if (!systems.isEmpty()) {
            notes.add(new Note(layer, GtLang.get("gtshaders.parity.system", String.join(", ", systems))));
        }
    }
}
