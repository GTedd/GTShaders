package mc.GTedd.cn.gtshaders.tools;

import mc.GTedd.cn.gtshaders.VanillaAssets;
import mc.GTedd.cn.gtshaders.codegen.CoreShaderCodegen;
import mc.GTedd.cn.gtshaders.codegen.ParamScanner;
import mc.GTedd.cn.gtshaders.core.GtProfile;
import mc.GTedd.cn.gtshaders.core.ShaderKind;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

/**
 * 拿<b>真实的</b>原版模板，给每一个 {@link ShaderKind} × 每一个版本生成一遍核心着色器，
 * 落到磁盘供本地的编译脚本真编译（脚本不随仓库发布；日常校验用 {@code ./gradlew checkShaderLib}）。
 *
 * <p>这一步是必要的：注入靠的是「原版源码里稳定存在的文本模式」，
 * 而"稳定"这个假设只能靠把三十多个着色器<b>全部生成一遍并编译</b>来证实。
 * 少一个种类没试到，就等于那个种类的锚点从没验证过。
 *
 * <p>模板从 {@code %TEMP%/mcref/<版本>/} 读（版本取自 {@code gradle.properties} 的
 * {@code minecraft_version}）——那是从 client.jar
 * 解出来的官方资产。<b>不入库、不打包</b>：Minecraft 的资产是专有的。
 *
 * <p>用法：{@code ./gradlew generateCoreProbes}
 */
public final class CoreShaderProbeGenerator {

    /** 一段有代表性的钩子：既动顶点也动颜色，还带一个参数，能把整条链路走通。 */
    private static final String DEMO_HOOK = """
            // @param name=Amount type=float min=0 max=1 default=0.35
            // @param name=Tint type=color3 default=#88CCFF

            vec3 gtVertex(vec3 position) {
                // 刻意读一个<b>只有顶点阶段才有</b>的属性：把这个钩子原样塞进片段着色器
                // 就会编译失败，于是这段探针同时验证了「另一个阶段的钩子有没有被删干净」
                float wobble = sin(position.x * 4.0 + float(GT_VERTEX_INDEX)) * Amount * 0.02;
                return position + vec3(0.0, wobble, 0.0);
            }

            vec4 gtFragment(vec4 color) {
                return vec4(mix(color.rgb, color.rgb * Tint, Amount), color.a);
            }
            """;

    private CoreShaderProbeGenerator() {
    }

    public static void main(String[] args) throws IOException {
        if (args.length < 1) {
            throw new IllegalArgumentException("用法：CoreShaderProbeGenerator <输出目录>");
        }
        Path out = Path.of(args[0]).toAbsolutePath().normalize();

        deleteRecursively(out);

        List<ShaderKind.Entry> kinds = ShaderKind.all();
        if (kinds.isEmpty()) {
            throw new IllegalStateException("kinds.json 没读到，检查 assets/gtshaders/vanilla/kinds.json");
        }

        ParamScanner.Result scanned = ParamScanner.scan(DEMO_HOOK);
        int generated = 0;
        int skipped = 0;

        GtProfile profile = GtProfile.MC_26_3;
        Path src = VanillaAssets.mcrefRoot();
        if (!Files.isDirectory(src)) {
            System.out.println("没有 " + VanillaAssets.version()
                    + " 的原版资产，先从 client.jar 解出 assets/minecraft/shaders/");
            return;
        }
        {
            Path packRoot = out.resolve("coreprobe-" + profile.display());
            write(packRoot.resolve("pack.mcmeta"), packMcmeta(profile));

            for (ShaderKind.Entry kind : kinds) {
                String path = kind.path();
                boolean any = false;
                for (String stage : kind.stages()) {
                    Path tpl = src.resolve(path + "." + stage);
                    if (!Files.exists(tpl)) {
                        // clouds / world_border 这类改过名的，另一个版本上确实没有这个文件
                        continue;
                    }
                    String template = Files.readString(tpl, StandardCharsets.UTF_8);
                    CoreShaderCodegen.Output o = CoreShaderCodegen.generate(
                            kind, profile, stage, template, scanned.strippedBody(), scanned.params());
                    write(packRoot.resolve("assets/minecraft/shaders/" + path + "." + stage), o.source());
                    any = true;
                }
                if (any) {
                    generated++;
                } else {
                    skipped++;
                    System.out.println("  跳过 " + kind.id() + "：模板不存在（" + path + "）");
                }
            }
        }
        generateLibrary(out);
        System.out.println("生成 " + generated + " 个种类的探针，跳过 " + skipped + " -> " + out);
    }

    /**
     * 把示例库也生成一遍。
     *
     * <p>探针验证的是「注入锚点对不对」，示例库验证的是「这些示例本身写得对不对」——
     * 两件事都得验，而且后者更容易错：示例里会用 {@code ChunkPosition}、{@code texCoord0}
     * 这类<b>只有特定种类才有</b>的东西，放错种类就编译不过。
     */
    private static void generateLibrary(Path out) throws IOException {
        List<mc.GTedd.cn.gtshaders.library.CoreLibrary.Entry> examples =
                mc.GTedd.cn.gtshaders.library.CoreLibrary.all();
        if (examples.isEmpty()) {
            System.out.println("示例库为空，跳过");
            return;
        }
        int n = 0;
        GtProfile profile = GtProfile.MC_26_3;
        Path src = VanillaAssets.mcrefRoot();
        if (!Files.isDirectory(src)) {
            return;
        }
        {
            for (mc.GTedd.cn.gtshaders.library.CoreLibrary.Entry ex : examples) {
                ShaderKind.Entry kind = ShaderKind.find(ex.kindId());
                if (kind == null) {
                    throw new IllegalStateException(ex.id() + " 指向的种类不存在：" + ex.kindId());
                }
                String source = mc.GTedd.cn.gtshaders.library.CoreLibrary.loadSource(ex.id());
                if (source == null) {
                    throw new IllegalStateException(ex.id() + " 在索引里但读不到源码");
                }
                ParamScanner.Result scanned = ParamScanner.scan(source);
                // 每个示例单独一个包：它们各自覆盖同一个种类，放一个包里会互相顶掉
                Path packRoot = out.resolve("corelib-" + ex.id() + "-" + profile.display());
                boolean any = false;
                for (String stage : kind.stages()) {
                    Path tpl = src.resolve(kind.path() + "." + stage);
                    if (!Files.exists(tpl)) {
                        continue;
                    }
                    CoreShaderCodegen.Output o = CoreShaderCodegen.generate(kind, profile, stage,
                            Files.readString(tpl, StandardCharsets.UTF_8),
                            scanned.strippedBody(), scanned.params());
                    write(packRoot.resolve("assets/minecraft/shaders/"
                            + kind.path() + "." + stage), o.source());
                    any = true;
                }
                if (any) {
                    write(packRoot.resolve("pack.mcmeta"), packMcmeta(profile));
                    n++;
                }
            }
        }
        System.out.println("生成 " + n + " 个示例包（" + examples.size() + " 个示例）");
    }

    private static String packMcmeta(GtProfile profile) {
        return """
                {
                  "pack": {
                    "description": "GTShaders core shader probes / 核心着色器注入探针",
                    "min_format": [%d, 0],
                    "max_format": [%d, %d]
                  }
                }
                """.formatted(profile.minPackFormat(), profile.maxPackFormat(), 0);
    }

    private static void write(Path path, String content) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, content, StandardCharsets.UTF_8);
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
