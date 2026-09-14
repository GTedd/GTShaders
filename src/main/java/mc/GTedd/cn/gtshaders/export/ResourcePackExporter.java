package mc.GTedd.cn.gtshaders.export;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import mc.GTedd.cn.gtshaders.GTShaders;
import mc.GTedd.cn.gtshaders.codegen.PackParity;
import mc.GTedd.cn.gtshaders.codegen.PostEffectJsonBuilder;
import mc.GTedd.cn.gtshaders.codegen.ShaderTexture;
import mc.GTedd.cn.gtshaders.core.GtProfile;
import mc.GTedd.cn.gtshaders.core.ShaderProject;
import mc.GTedd.cn.gtshaders.i18n.GtLang;
import mc.GTedd.cn.gtshaders.runtime.ImportedTextureManager;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;

import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 把工程导出成可以直接丢进 resourcepacks/ 的 zip。
 *
 * <p>两种落地方式，对应两种实际用法：
 * <ul>
 *   <li>{@link Mode#POST_EFFECT_COMMAND}：自定义 id，靠 {@code /posteffect add @s <id>} 触发。
 *       这是 Mojang 设计的正路，也是唯一能按玩家、按时机精确控制的方式。</li>
 *   <li>{@link Mode#END_OF_FRAME}：写成 {@code minecraft:end_of_frame}，
 *       资源包一加载就常驻生效，不受指令控制。适合做整包的统一色调，
 *       而且它排在所有 {@code /posteffect} 效果<b>之前</b>执行。</li>
 * </ul>
 *
 * <p>26.2 时代还有第三种「覆盖原版硬编码 id」的妥协方案（那时自定义 post effect
 * 没有任何原版触发手段，只能劫持 {@code blur} / {@code invert} 之类）。
 * 26.3 有了正路，那套连同它的副作用一起删掉了。
 */
public final class ResourcePackExporter {

    public enum Mode {
        POST_EFFECT_COMMAND("command"),
        END_OF_FRAME("end_of_frame");

        public final String id;

        Mode(String id) {
            this.id = id;
        }

        public String translationKey() {
            return "gtshaders.export.mode." + id;
        }

        public Mode next() {
            Mode[] all = values();
            return all[(ordinal() + 1) % all.length];
        }
    }

    /**
     * @param dir      导出目录；空表示用调用方给的默认目录。相对路径按默认目录解析
     * @param fileName 文件名（不含 {@code .zip}）；空表示按工程名自动取。允许中文
     */
    public record Options(Mode mode, String namespace, String effectId, String dir, String fileName) {

        public Options {
            // 落点这两项是从输入框直接来的，null 与空白在这里是同一个意思：「没自定义」。
            // 统一收在构造里，下游就不必到处判空
            dir = dir == null ? "" : dir;
            fileName = fileName == null ? "" : fileName;
        }

        /** 默认走指令触发：它是唯一能按玩家、按时机精确控制的方式，也是绝大多数人要的。 */
        public static Options defaults(String effectId) {
            return new Options(Mode.POST_EFFECT_COMMAND, "gtshaders", effectId, "", "");
        }

        public Options withMode(Mode mode) {
            return new Options(mode, namespace, effectId, dir, fileName);
        }

        public Options withEffectId(String id) {
            return new Options(mode, namespace, id, dir, fileName);
        }

        public Options withDir(String dir) {
            return new Options(mode, namespace, effectId, dir, fileName);
        }

        public Options withFileName(String fileName) {
            return new Options(mode, namespace, effectId, dir, fileName);
        }
    }

    /**
     * @param usageHint 一句话说明该怎么用，直接显示给用户
     * @param notes     资源包里没有来源的量（见 {@link PackParity}）；空表示预览与导出物完全一致
     */
    public record Result(Path file, String usageHint, int passCount, List<PackParity.Note> notes) {
    }

    private ResourcePackExporter() {
    }

    /**
     * 没自定义文件名时用的名字（不含 {@code .zip}）：工程名 + 目标版本。
     *
     * <p>这里用 {@link ExportTarget#fileName} 而不是 {@link #sanitize}——后者是给<b>包内</b>
     * 资源路径用的 ASCII 收敛，拿来当文件名会把每个中文工程名都压成 {@code untitled}，
     * 于是所有中文工程都往同一个 zip 上写，后导出的默默覆盖先导出的。
     *
     * <p>预览「将写入哪个文件」时也调它，界面上显示的和真正写出来的因此不可能对不上。
     */
    public static String defaultFileName(ShaderProject project) {
        return ExportTarget.fileName(project.name(), "untitled")
                + "_" + project.exportProfile().display().replace('.', '_');
    }

    public static Result export(ShaderProject project, Path exportDir, Options options) throws IOException {
        GtProfile profile = project.exportProfile();
        String safeName = sanitize(project.name());
        // 着色器始终放在自己的命名空间下，这样即使是「覆盖原版 id」的模式，
        // 也不会连原版的 .fsh 文件一起覆盖掉。
        String shaderNs = "gtshaders";
        ShaderProject.Build build = project.generate(profile, "post/" + safeName);

        // 落点可以自定义，包内路径不能：上面那个 safeName 仍然是 ASCII 收敛过的。
        // 换句话说文件名随便叫中文，zip 里面照样是原版加载得了的资源路径
        Path out = ExportTarget.directory(options.dir(), exportDir)
                .resolve(ExportTarget.fileName(options.fileName(), defaultFileName(project)) + ".zip");

        String effectNamespace;
        String effectName;
        switch (options.mode()) {
            case END_OF_FRAME -> {
                effectNamespace = "minecraft";
                effectName = "end_of_frame";
            }
            default -> {
                effectNamespace = options.namespace();
                effectName = sanitize(options.effectId());
            }
        }

        // 参数默认值取当前值；系统量只有一种合法取值（PACK_SYSTEM）——
        // 预览生成 JSON 时用的也是它，两边的 JSON 只差着色器路径。
        JsonObject effectJson = PostEffectJsonBuilder.buildChain(shaderNs, build,
                PostEffectJsonBuilder.PACK_SYSTEM);

        // 实体轮廓链是<b>另一条独立的链</b>，和主链共存在同一个包里。
        // 它落在原版固定的 assets/minecraft/post_effect/entity_outline.json 上——
        // 那个 id 是 LevelRenderer 写死的，没有命名空间可选，也因此一个包只能有一条。
        ShaderProject.Build outlineBuild = project.generateOutline(profile, "post/" + safeName + "_outline");
        List<PackParity.Note> notes = PackParity.audit(build, outlineBuild);

        StagedWrite.toFile(out, os -> {
            try (ZipOutputStream zip = new ZipOutputStream(os, StandardCharsets.UTF_8)) {
                put(zip, "pack.mcmeta", packMcmeta(project, profile));
                // 一个后处理层都没有时不写主链的 JSON：空 passes 的 post effect 加载得上但什么都不做，
                // 而它会让 /posteffect 列表里多出一个点了没反应的 id。「只做实体轮廓」是合法用法
                if (!build.passes().isEmpty()) {
                    put(zip, "assets/" + effectNamespace + "/post_effect/" + effectName + ".json",
                            PostEffectJsonBuilder.pretty(effectJson));
                }
                for (ShaderProject.PassBuild pass : build.passes()) {
                    put(zip, "assets/" + shaderNs + "/shaders/" + pass.shaderPath() + ".fsh",
                            pass.output().source());
                }
                if (!outlineBuild.passes().isEmpty()) {
                    put(zip, "assets/minecraft/post_effect/entity_outline.json",
                            PostEffectJsonBuilder.pretty(PostEffectJsonBuilder.buildOutlineChain(
                                    shaderNs, outlineBuild, PostEffectJsonBuilder.PACK_SYSTEM)));
                    for (ShaderProject.PassBuild pass : outlineBuild.passes()) {
                        put(zip, "assets/" + shaderNs + "/shaders/" + pass.shaderPath() + ".fsh",
                                pass.output().source());
                    }
                }
                writeTextures(zip, build, outlineBuild);
                put(zip, "README.txt", usageText(project, profile, options, effectNamespace, effectName,
                        build, notes));
            }
        });

        return new Result(out, usageHint(options, effectNamespace, effectName), build.passes().size(), notes);
    }

    private static String packMcmeta(ShaderProject project, GtProfile profile) {
        JsonObject pack = new JsonObject();
        pack.addProperty("description", "GTShaders · " + project.name());
        // 用 min/max 区间而不是单个 pack_format：区间写法能让包在相邻版本上也至少被识别出来，
        // 用户能看到它、能启用它，比「包直接不出现在列表里」好排查得多。
        // 具体数值由 profile 给：下界取 97（26.3 pre 系列）。不取更低的 93 是因为着色器的
        // 新写法从 93 起生效，但 Globals 的成员重排要到 94 才发生——声明成 93 会让包在
        // 那一版上加载成功却把 GameTime 读成 GlintAlpha，不报错、只是画面不对。
        JsonArray min = new JsonArray();
        min.add(profile.minPackFormat());
        min.add(0);
        pack.add("min_format", min);
        JsonArray max = new JsonArray();
        max.add(profile.maxPackFormat());
        max.add(0);
        pack.add("max_format", max);

        JsonObject root = new JsonObject();
        root.add("pack", pack);
        return PostEffectJsonBuilder.pretty(root);
    }

    /** 把所有 @texture 引用的贴图一并写进导出包。 */
    private static void writeTextures(java.util.zip.ZipOutputStream zip,
                                      ShaderProject.Build build,
                                      ShaderProject.Build outlineBuild) throws IOException {
        Set<String> written = new HashSet<>();
        for (ShaderProject.PassBuild pass : build.passes()) {
            for (ShaderTexture tex : pass.output().textures()) {
                writeTexture(zip, tex.location(), written);
            }
        }
        for (ShaderProject.PassBuild pass : outlineBuild.passes()) {
            for (ShaderTexture tex : pass.output().textures()) {
                writeTexture(zip, tex.location(), written);
            }
        }
    }

    private static void writeTexture(java.util.zip.ZipOutputStream zip, String location,
                                     Set<String> written) throws IOException {
        if (location == null || !location.startsWith("gtshaders:")) {
            return;
        }
        String rel = location.substring("gtshaders:".length()); // killicon/foo
        String assetPath = "assets/gtshaders/textures/effect/" + rel + ".png";
        if (!written.add(assetPath)) {
            return;
        }
        if (location.startsWith("gtshaders:imported/")) {
            Path local = ImportedTextureManager.pathFor(location);
            if (local != null && Files.isRegularFile(local)) {
                put(zip, assetPath, Files.readAllBytes(local));
            } else {
                GTShaders.LOGGER.warn("导出时找不到导入贴图：{}", location);
            }
            return;
        }
        try (InputStream in = ResourcePackExporter.class.getResourceAsStream("/assets/gtshaders/" + assetPath)) {
            if (in != null) {
                put(zip, assetPath, in.readAllBytes());
            } else {
                GTShaders.LOGGER.warn("导出时找不到内置贴图：{}", location);
            }
        }
    }

    private static void put(java.util.zip.ZipOutputStream zip, String path, byte[] content) throws IOException {
        java.util.zip.ZipEntry entry = new java.util.zip.ZipEntry(path);
        entry.setTime(0L);
        zip.putNextEntry(entry);
        zip.write(content);
        zip.closeEntry();
    }

    public static String usageHint(Options options, String ns, String name) {
        return switch (options.mode()) {
            case POST_EFFECT_COMMAND -> GtLang.get("gtshaders.export.hint.command", ns + ":" + name);
            case END_OF_FRAME -> GtLang.get("gtshaders.export.hint.end_of_frame");
        };
    }

    /**
     * 导出包里的说明文件。刻意写成中英双语：资源包是要分发给别人的，
     * 拿到包的人未必和作者用同一种语言，而这份说明恰恰是他唯一的入口。
     */
    private static String usageText(ShaderProject project, GtProfile profile, Options options,
                                    String ns, String name, ShaderProject.Build build,
                                    List<PackParity.Note> notes) {
        StringBuilder sb = new StringBuilder();
        sb.append("GTShaders post-processing resource pack\n");
        sb.append("=======================================\n\n");
        sb.append("Project / 工程名：").append(project.name()).append('\n');
        sb.append("Target / 目标版本：Minecraft ").append(profile.display()).append('\n');
        sb.append("Passes / 渲染通道：").append(build.passes().size());
        if (build.needsFinalBlit()) {
            sb.append(" (+1 blit)");
        }
        sb.append("\n\n");

        sb.append("HOW TO USE / 使用方法\n---------------------\n");
        sb.append("1. Put this zip into .minecraft/resourcepacks/ and enable it.\n");
        sb.append("   把本 zip 放进 .minecraft/resourcepacks/ 并在游戏里启用。\n");
        switch (options.mode()) {
            case POST_EFFECT_COMMAND -> {
                sb.append("2. Run in game / 在游戏中执行：\n");
                sb.append("     /posteffect add @s ").append(ns).append(':').append(name).append('\n');
                sb.append("   Remove / 关闭：\n");
                sb.append("     /posteffect remove @s ").append(ns).append(':').append(name).append('\n');
                sb.append("     /posteffect clear @s\n\n");
                sb.append("Note: /posteffect requires permission level 2.\n");
                sb.append("注意：/posteffect 需要权限等级 2（管理员）。\n");
            }
            case END_OF_FRAME -> {
                sb.append("2. It applies immediately, no command needed.\n");
                sb.append("   效果会立刻常驻生效，无需任何指令。\n\n");
                sb.append("Note: end_of_frame cannot be turned off with /posteffect;\n");
                sb.append("      disable the resource pack instead.\n");
                sb.append("注意：end_of_frame 无法用 /posteffect 关闭，只能停用资源包。\n");
            }
        }
        sb.append("\nParameter defaults are baked into the post_effect JSON and can be hand-tuned there.\n");
        sb.append("参数默认值已烘进 post_effect JSON，可直接手改该文件微调。\n");
        sb.append("Time (GTTime) is driven by the vanilla GameTime clock: it wraps every 1200 s\n");
        sb.append("and freezes while the game is paused, exactly as the editor's pack view shows.\n");
        sb.append("时间（GTTime）由原版 GameTime 驱动：每 1200 秒回绕一次，游戏暂停时静止，\n");
        sb.append("与编辑器「资源包视角」看到的完全一致。\n");
        if (!notes.isEmpty()) {
            sb.append("\nINPUTS WITHOUT A SOURCE IN A PLAIN RESOURCE PACK / 资源包里没有来源的量\n");
            sb.append("--------------------------------------------------------------------\n");
            sb.append("These are fed by the GTShaders mod every frame in the editor; here they stay at 0.\n");
            sb.append("下面这些量在编辑器里由 mod 每帧写入，在资源包里恒为 0：\n");
            for (PackParity.Note n : notes) {
                sb.append("  - ").append(n).append('\n');
            }
        }
        return sb.toString();
    }

    private static void put(ZipOutputStream zip, String path, String content) throws IOException {
        ZipEntry entry = new ZipEntry(path);
        // 固定时间戳：同样的工程导出两次应该得到字节一致的 zip，方便做差异比对与分发校验。
        entry.setTime(0L);
        zip.putNextEntry(entry);
        zip.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    /** 资源路径只允许 [a-z0-9_.-]，这里做一次收敛，避免中文工程名把资源包写坏。 */
    public static String sanitize(String raw) {
        if (raw == null || raw.isBlank()) {
            return "untitled";
        }
        StringBuilder sb = new StringBuilder();
        for (char c : raw.toLowerCase(Locale.ROOT).toCharArray()) {
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_' || c == '-') {
                sb.append(c);
            } else if (c == ' ' || c == '.') {
                sb.append('_');
            }
        }
        String s = sb.toString().replaceAll("_+", "_").replaceAll("^_|_$", "");
        return s.isEmpty() ? "untitled" : s;
    }
}
