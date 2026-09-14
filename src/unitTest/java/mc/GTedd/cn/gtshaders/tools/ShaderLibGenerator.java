package mc.GTedd.cn.gtshaders.tools;

import mc.GTedd.cn.gtshaders.codegen.PostEffectJsonBuilder;
import mc.GTedd.cn.gtshaders.core.GtProfile;
import mc.GTedd.cn.gtshaders.core.ShaderLayer;
import mc.GTedd.cn.gtshaders.core.ShaderProject;
import mc.GTedd.cn.gtshaders.library.EffectLibrary;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 把效果库编译成一个<b>可以直接丢进 resourcepacks/ 的 26.3 资源包</b>，落到工程根目录的
 * {@code shaderlib/}。
 *
 * <p>为什么产物要同时满足两种身份：
 * <ul>
 *   <li>对 Minecraft，它是合法的 26.3 着色器——{@code #version 330} + sso 扩展 +
 *       {@code layout(location)} + 正确顺序的 {@code Globals} 块，加载即用。</li>
 *   <li>对 GTShaders 编辑器，它又是可以<b>原样拖回来</b>的编辑文档——因为 {@code // @param}
 *       注解被留在了函数体里，导入时剥掉生成的头部就能完整恢复参数面板。</li>
 * </ul>
 *
 * <p>一份文件两种用途，作者就不必维护"源码版"和"发布版"两套东西。
 *
 * <p>用法：{@code ./gradlew generateShaderLib}
 */
public final class ShaderLibGenerator {

    /** 生成的着色器所在命名空间。 */
    private static final String NAMESPACE = "gtshaders";

    private ShaderLibGenerator() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            throw new IllegalArgumentException("用法：ShaderLibGenerator <输出目录>");
        }
        Path out = Path.of(args[0]).toAbsolutePath().normalize();
        // 源码目录从 classpath 反查：Gradle 已经把 main 的资源同步到了 ASCII 暂存目录，
        // 那里能当普通目录遍历，比让调用方再传一次含中文的工程路径可靠。
        Path sourceRoot = Path.of(EffectLibrary.class.getResource("/assets/gtshaders/library/").toURI());

        List<EffectLibrary.Entry> entries = EffectLibrary.all();
        if (entries.isEmpty()) {
            throw new IllegalStateException("效果库索引为空，检查 assets/gtshaders/library/index.json");
        }
        verifyIndexMatchesDisk(sourceRoot, entries);

        deleteRecursively(out);
        Files.createDirectories(out);

        Files.writeString(out.resolve("pack.mcmeta"), packMcmeta(), StandardCharsets.UTF_8);

        // @texture 声明的自定义贴图也要进资源包，否则 post_effect JSON 里引用的
        // location 在运行时找不到贴图，效果会整个加载失败。
        java.net.URL texturesUrl = EffectLibrary.class.getResource("/assets/gtshaders/textures/");
        if (texturesUrl != null) {
            Path texturesRoot = Path.of(texturesUrl.toURI());
            if (Files.isDirectory(texturesRoot)) {
                copyRecursively(texturesRoot, out.resolve("assets/gtshaders/textures"));
            }
        }

        int shaders = 0;
        for (EffectLibrary.Entry entry : entries) {
            String author = EffectLibrary.loadSource(entry.id());
            if (author == null) {
                throw new IllegalStateException("索引里有 " + entry.id() + " 但 classpath 上找不到源码");
            }
            // 每个效果自成一条单通道链：库文件是给人挑选和试用的最小单位，
            // 打包成多层反而没法单独启用其中一个。
            ShaderProject project = new ShaderProject(entry.id());
            project.clearLayers();
            ShaderLayer layer = new ShaderLayer(entry.id(), author);
            // 逐实体效果覆盖的是 minecraft:entity_outline 那条链，必须按轮廓层生成：
            // 它多一个 SceneSampler 输入、SamplerInfo 多一个 vec2，
            // 而且 gtMask/gtScene 这些 helper 只在轮廓层里注入。
            // 按后处理层生成的话，编译器会报一串「未定义的 gtMask」——这正是加这一行前踩到的
            layer.setOutline(entry.outline());
            layer.setEnabled(true);
            project.addLayer(layer);

            ShaderProject.Build build = entry.outline()
                    ? project.generateOutline(GtProfile.MC_26_3, "post/" + entry.id())
                    : project.generate(GtProfile.MC_26_3, "post/" + entry.id());
            ShaderProject.PassBuild pass = build.passes().get(0);
            PostEffectJsonBuilder.verifyLayout(pass.output().orderedParams(), pass.output().source());

            // 路径必须取自 pass 本身而不是自己再拼一遍——post_effect JSON 里的
            // fragment_shader 引用的就是它，两边各拼各的迟早会对不上（而且对不上时
            // Minecraft 只会安静地不加载，不报错）
            write(out.resolve("assets/" + NAMESPACE + "/shaders/" + pass.shaderPath() + ".fsh"),
                    pass.output().source());
            // 轮廓效果的 post_effect JSON 落在 minecraft:entity_outline 上——那个 id 是
            // LevelRenderer 写死的，没有命名空间可选。也因此这类效果在一个包里只能启用一个，
            // 库里各出一份是为了让人挑，不是为了同时加载
            if (entry.outline()) {
                write(out.resolve("assets/" + NAMESPACE + "/post_effect/" + entry.id() + ".json"),
                        PostEffectJsonBuilder.pretty(PostEffectJsonBuilder.buildOutlineChain(
                                NAMESPACE, build, new float[]{0f, 0f, 0f, 1f})));
            } else {
                write(out.resolve("assets/" + NAMESPACE + "/post_effect/" + entry.id() + ".json"),
                        PostEffectJsonBuilder.pretty(PostEffectJsonBuilder.buildChain(
                                NAMESPACE, build, new float[]{0f, 0f, 0f, 1f})));
            }
            shaders++;
        }

        Files.writeString(out.resolve("README.md"), readme(entries), StandardCharsets.UTF_8);
        System.out.println("生成 " + shaders + " 个效果 -> " + out);
    }

    /**
     * 索引与磁盘必须一一对应。
     *
     * <p>这条检查是必要的：漏登记的效果会静悄悄不出现在菜单里，而登记了却没有文件的会在
     * 运行时才炸——两种都比在生成阶段直接失败难查得多。
     */
    private static void verifyIndexMatchesDisk(Path sourceRoot, List<EffectLibrary.Entry> entries)
            throws IOException {
        List<String> onDisk = new ArrayList<>();
        if (Files.isDirectory(sourceRoot)) {
            try (var stream = Files.walk(sourceRoot)) {
                stream.filter(p -> p.getFileName().toString().endsWith(".fsh"))
                        .forEach(p -> {
                            String n = p.getFileName().toString();
                            onDisk.add(n.substring(0, n.length() - 4));
                        });
            }
        }
        List<String> indexed = entries.stream().map(EffectLibrary.Entry::id)
                .sorted(Comparator.naturalOrder()).toList();
        List<String> disk = onDisk.stream().sorted(Comparator.naturalOrder()).toList();
        if (!indexed.equals(disk)) {
            List<String> missingFile = new ArrayList<>(indexed);
            missingFile.removeAll(disk);
            List<String> missingIndex = new ArrayList<>(disk);
            missingIndex.removeAll(indexed);
            throw new IllegalStateException(
                    "索引与磁盘不一致：索引里有但没文件 " + missingFile + "；有文件但没登记 " + missingIndex);
        }
    }

    private static String packMcmeta() {
        JsonObject pack = new JsonObject();
        pack.addProperty("description", "GTShaders effect library / 效果库");
        JsonArray min = new JsonArray();
        min.add(GtProfile.MC_26_3.minPackFormat());
        min.add(0);
        pack.add("min_format", min);
        JsonArray max = new JsonArray();
        max.add(GtProfile.MC_26_3.maxPackFormat());
        max.add(0);
        pack.add("max_format", max);
        JsonObject root = new JsonObject();
        root.add("pack", pack);
        return PostEffectJsonBuilder.pretty(root);
    }

    private static String readme(List<EffectLibrary.Entry> entries) {
        StringBuilder sb = new StringBuilder();
        sb.append("# GTShaders 效果库（26.3 格式）\n\n");
        sb.append("本目录由 `./gradlew generateShaderLib` 生成，**不要手改**——改动请回到\n");
        sb.append("`src/main/resources/assets/gtshaders/library/<分类>/<id>.fsh`。\n\n");
        sb.append("## 它是什么\n\n");
        sb.append("整个目录就是一个可加载的 26.3 资源包：\n\n");
        sb.append("```\n");
        sb.append("pack.mcmeta\n");
        sb.append("assets/gtshaders/shaders/post/<id>.fsh        完整的 26.3 片段着色器\n");
        sb.append("assets/gtshaders/post_effect/<id>.json        单通道后处理链\n");
        sb.append("assets/gtshaders/textures/...                  @texture 引用的自定义贴图\n");
        sb.append("```\n\n");
        sb.append("直接把本目录（或它的 zip）放进 `.minecraft/resourcepacks/` 启用，然后：\n\n");
        sb.append("```\n/posteffect add @s gtshaders:<id>\n/posteffect clear @s\n```\n\n");
        sb.append("## 拖回编辑器\n\n");
        sb.append("`.fsh` 里保留了 `// @param` 注解，把文件拖进 GTShaders 编辑器即可恢复完整的\n");
        sb.append("参数面板继续调——不需要复制源码，也不存在\"发布版\"与\"源码版\"两份。\n\n");
        sb.append("## 效果一览（共 ").append(entries.size()).append(" 个）\n\n");
        String category = null;
        for (EffectLibrary.Entry e : entries) {
            if (!e.category().equals(category)) {
                category = e.category();
                sb.append("\n### ").append(EffectLibrary.categoryName(category, "zh_cn"))
                        .append(" / ").append(EffectLibrary.categoryName(category, "en_us")).append("\n\n");
                sb.append("| id | 中文名 | English |\n|---|---|---|\n");
            }
            sb.append("| `").append(e.id()).append("` | ").append(e.name("zh_cn"))
                    .append(" | ").append(e.name("en_us")).append(" |\n");
        }
        return sb.toString();
    }

    private static void write(Path path, String content) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, content, StandardCharsets.UTF_8);
    }

    private static void copyRecursively(Path from, Path to) throws IOException {
        if (!Files.isDirectory(from)) {
            return;
        }
        try (var stream = Files.walk(from)) {
            for (Path p : stream.toList()) {
                Path rel = from.relativize(p);
                Path target = to.resolve(rel.toString());
                if (Files.isDirectory(p)) {
                    Files.createDirectories(target);
                } else {
                    Files.createDirectories(target.getParent());
                    Files.copy(p, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    private static void deleteRecursively(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (var stream = Files.walk(dir)) {
            for (Path p : stream.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        }
    }
}
