package mc.GTedd.cn.gtshaders.export;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import mc.GTedd.cn.gtshaders.codegen.CoreShaderCodegen;
import mc.GTedd.cn.gtshaders.codegen.ParamScanner;
import mc.GTedd.cn.gtshaders.codegen.PostEffectJsonBuilder;
import mc.GTedd.cn.gtshaders.core.GtProfile;
import mc.GTedd.cn.gtshaders.core.ShaderKind;
import mc.GTedd.cn.gtshaders.i18n.GtLang;
import mc.GTedd.cn.gtshaders.runtime.VanillaSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 把核心着色器效果导出成可以直接丢进 resourcepacks/ 的 zip。
 *
 * <p>和后处理导出的关键差别：核心着色器是<b>覆盖原版文件</b>，不是新增一个自定义 id。
 * 所以产物落在 {@code assets/minecraft/shaders/core/<原版名>.vsh|fsh}，
 * 加载资源包即生效，<b>没有指令可以开关</b>——这一点会写进产物的 README 里。
 */
public final class CoreShaderExporter {

    /**
     * @param file      产出的 zip
     * @param fileCount 写进去几个着色器文件
     * @param missing   因为缺少原版模板而没能导出的种类
     */
    public record Result(Path file, int fileCount, List<String> missing) {
    }

    /**
     * @param kind    要覆盖的原版着色器种类
     * @param author  作者写的钩子源码
     * @param profile 目标版本
     */
    public record Job(ShaderKind.Entry kind, String author, GtProfile profile) {
    }

    private CoreShaderExporter() {
    }

    /** 没自定义文件名时用的名字（不含 {@code .zip}）：工程名 + {@code _core} + 目标版本。 */
    public static String defaultFileName(String packName, GtProfile profile) {
        return ExportTarget.fileName(packName, "untitled")
                + "_core_" + profile.display().replace('.', '_');
    }

    public static Result export(String packName, List<Job> jobs, Path exportDir) throws IOException {
        return export(packName, jobs, exportDir, "");
    }

    /**
     * @param exportDir 目标目录，调用方已经解析成绝对路径
     * @param fileName  文件名（不含 {@code .zip}）；空表示按 {@link #defaultFileName} 取。允许中文
     */
    public static Result export(String packName, List<Job> jobs, Path exportDir, String fileName)
            throws IOException {
        if (jobs.isEmpty()) {
            throw new IllegalArgumentException(GtLang.get("gtshaders.core.error.nothing_to_export"));
        }
        GtProfile profile = jobs.get(0).profile();
        Path out = exportDir.resolve(
                ExportTarget.fileName(fileName, defaultFileName(packName, profile)) + ".zip");

        List<String> missing = new ArrayList<>();
        int[] writtenBox = {0};

        StagedWrite.toFile(out, os -> {
            try (ZipOutputStream zip = new ZipOutputStream(os, StandardCharsets.UTF_8)) {
                put(zip, "pack.mcmeta", packMcmeta(packName, profile));

                for (Job job : jobs) {
                    ShaderKind.Entry kind = job.kind();
                    ParamScanner.Result scanned = ParamScanner.scan(job.author());
                    String path = kind.path();
                    boolean any = false;

                    for (String stage : kind.stages()) {
                        String template = VanillaSource.template(kind, profile, stage);
                        if (template == null) {
                            continue;
                        }
                        CoreShaderCodegen.Output o = CoreShaderCodegen.generate(
                                kind, profile, stage, template, scanned.strippedBody(), scanned.params());
                        put(zip, "assets/minecraft/shaders/" + path + "." + stage, o.source());
                        writtenBox[0]++;
                        any = true;
                    }
                    if (!any) {
                        missing.add(kind.id());
                    }
                }

                put(zip, "README.txt", usageText(packName, profile, jobs, missing));
            }
        });
        return new Result(out, writtenBox[0], missing);
    }

    private static String packMcmeta(String packName, GtProfile profile) {
        JsonObject pack = new JsonObject();
        pack.addProperty("description", "GTShaders · " + packName);
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

    private static String usageText(String packName, GtProfile profile, List<Job> jobs,
                                    List<String> missing) {
        StringBuilder sb = new StringBuilder();
        sb.append("GTShaders core shader pack\n");
        sb.append("==========================\n\n");
        sb.append("Project / 工程名：").append(packName).append('\n');
        sb.append("Target / 目标版本：Minecraft ").append(profile.display()).append("\n\n");

        sb.append("HOW TO USE / 使用方法\n---------------------\n");
        sb.append("Put this zip into .minecraft/resourcepacks/ and enable it.\n");
        sb.append("把本 zip 放进 .minecraft/resourcepacks/ 并启用。\n\n");
        sb.append("These are CORE shaders: they override vanilla files, so they take effect\n");
        sb.append("as soon as the pack is enabled. There is no command to toggle them.\n");
        sb.append("这些是核心着色器，覆盖的是原版文件——启用资源包即生效，没有指令可以开关。\n\n");

        sb.append("OVERRIDDEN / 覆盖了哪些\n-----------------------\n");
        for (Job job : jobs) {
            sb.append("  ").append(job.kind().path())
                    .append("  (").append(job.kind().name("en_us")).append(" / ")
                    .append(job.kind().name("zh_cn")).append(")\n");
        }
        if (!missing.isEmpty()) {
            sb.append("\nSKIPPED / 未能导出\n------------------\n");
            sb.append("These need the vanilla assets of the target version:\n");
            sb.append("以下种类缺少目标版本的原版模板，未能导出：\n");
            for (String id : missing) {
                sb.append("  ").append(id).append('\n');
            }
            sb.append("Put them under config/gtshaders/vanilla/")
                    .append(profile.display()).append("/ and export again.\n");
            sb.append("把该版本的原版着色器放到 config/gtshaders/vanilla/")
                    .append(profile.display()).append("/ 下再导出一次。\n");
        }

        sb.append("\nCOMPATIBILITY / 兼容性\n----------------------\n");
        sb.append("Only one pack can override a given core shader. If another pack\n");
        sb.append("changes the same file, whichever is higher in the list wins.\n");
        sb.append("同一个核心着色器只能被一个资源包覆盖。若有别的包改了同一文件，列表靠上的生效。\n");
        return sb.toString();
    }

    private static void put(ZipOutputStream zip, String path, String content) throws IOException {
        ZipEntry entry = new ZipEntry(path);
        // 固定时间戳：同样的工程导出两次应该字节一致，方便比对与分发校验
        entry.setTime(0L);
        zip.putNextEntry(entry);
        zip.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    /** 缺少目标版本原版资产时给用户的一句话提示。 */
    public static String missingAssetsHint(GtProfile profile) {
        return GtLang.get("gtshaders.core.missing_assets",
                profile.display(), VanillaSource.vanillaDir(profile).toString());
    }
}
