package mc.GTedd.cn.gtshaders;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import mc.GTedd.cn.gtshaders.codegen.CoreShaderCodegen;
import mc.GTedd.cn.gtshaders.codegen.ParamScanner;
import mc.GTedd.cn.gtshaders.core.GtProfile;
import mc.GTedd.cn.gtshaders.core.ShaderKind;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 拿<b>真实的原版着色器</b>跑一遍注入，而不是拿手写的最小模板。
 *
 * <p>其余测试用的模板是「足够像原版」的简化版，能覆盖分支，但覆盖不了真实文件里那些
 * 意料之外的写法——多重 {@code #ifdef} 嵌套、location 已经排到 7、
 * {@code gl_Position} 出现在条件分支里。这些只有真文件才暴露得出来。
 *
 * <p>原版资产是<b>专有</b>的，不入库。把 client.jar 里的 {@code assets/minecraft/shaders/} 放到
 * {@code docs/vanilla/<gradle.properties 里的 minecraft_version>/shaders/}，
 * 这条用例就会自动跑起来，否则整类跳过。
 */
@EnabledIf("assetsPresent")
class RealVanillaTest {

    private static final Path ROOT = VanillaAssets.shadersRoot();

    static boolean assetsPresent() {
        return Files.isDirectory(ROOT.resolve("core"));
    }

    private static String read(Path p) {
        try {
            return Files.readString(p, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /** 只写钩子、不碰模板的最小源码，用来单独验注入本身。 */
    private static final String VERTEX_ONLY = """
            vec3 gtVertex(vec3 position) {
                return position + vec3(0.0, 0.01, 0.0);
            }
            """;
    private static final String FRAGMENT_ONLY = """
            vec4 gtFragment(vec4 color) {
                return vec4(color.rgb * 1.1, color.a);
            }
            """;

    @Test
    void 每个种类的真实原版模板都能接上钩子() {
        List<String> bad = new ArrayList<>();
        for (ShaderKind.Entry kind : ShaderKind.all()) {
            for (String stage : kind.stages()) {
                Path file = ROOT.resolve(kind.path() + "." + stage);
                if (!Files.exists(file)) {
                    bad.add(kind.id() + "." + stage + " 原版文件不存在：" + file);
                    continue;
                }
                boolean vertex = "vsh".equals(stage);
                boolean wantHook = vertex ? kind.vertexHook() : kind.fragmentHook();
                if (!wantHook) {
                    continue;
                }
                String author = vertex ? VERTEX_ONLY : FRAGMENT_ONLY;
                try {
                    CoreShaderCodegen.Output out = CoreShaderCodegen.generate(
                            kind, GtProfile.MC_26_3, stage, read(file), author, List.of());
                    if (!out.hooked()) {
                        bad.add(kind.id() + "." + stage + " 没接上钩子");
                    }
                } catch (RuntimeException e) {
                    bad.add(kind.id() + "." + stage + " 抛异常：" + e.getMessage());
                }
            }
        }
        if (!bad.isEmpty()) {
            fail("真实 26.3 模板上注入失败：\n  " + String.join("\n  ", bad));
        }
    }

    @Test
    void 内置示例库在真实模板上都生成得出来() {
        String index;
        try (var in = RealVanillaTest.class.getResourceAsStream(
                "/assets/gtshaders/corelib/index.json")) {
            index = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        var json = com.google.gson.JsonParser.parseString(index).getAsJsonObject();
        List<String> bad = new ArrayList<>();
        int checked = 0;
        for (var el : json.getAsJsonArray("effects")) {
            var o = el.getAsJsonObject();
            String id = o.get("id").getAsString();
            String kindId = o.get("kind").getAsString();
            ShaderKind.Entry kind = ShaderKind.find(kindId);
            if (kind == null) {
                bad.add(id + " 指向不存在的种类 " + kindId);
                continue;
            }
            String author;
            try (var in = RealVanillaTest.class.getResourceAsStream(
                    "/assets/gtshaders/corelib/" + id + ".fsh")) {
                author = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            } catch (Exception e) {
                bad.add(id + " 源码读不到");
                continue;
            }
            var scanned = ParamScanner.scan(author);
            for (String stage : kind.stages()) {
                Path file = ROOT.resolve(kind.path() + "." + stage);
                if (!Files.exists(file)) {
                    bad.add(id + " 的模板 " + file + " 不存在");
                    continue;
                }
                try {
                    CoreShaderCodegen.generate(kind, GtProfile.MC_26_3, stage,
                            read(file), scanned.strippedBody(), scanned.params());
                    checked++;
                } catch (RuntimeException e) {
                    bad.add(id + "." + stage + " 生成失败：" + e.getMessage());
                }
            }
        }
        if (!bad.isEmpty()) {
            fail("示例库在真实 26.3 模板上失败：\n  " + String.join("\n  ", bad));
        }
        assertTrue(checked > 0, "一个都没验到");
    }

    @Test
    void 原版模板全都是二六三格式() {
        List<String> bad = new ArrayList<>();
        try (var walk = Files.walk(ROOT)) {
            for (Path p : walk.filter(Files::isRegularFile).toList()) {
                String name = p.getFileName().toString();
                if (!name.endsWith(".vsh") && !name.endsWith(".fsh")) {
                    continue;
                }
                String src = read(p);
                if (!src.startsWith("#version 330")) {
                    bad.add(p + " 不是 #version 330");
                }
                if (!src.contains("GL_ARB_separate_shader_objects")) {
                    bad.add(p + " 缺 separate_shader_objects 扩展");
                }
                if (src.contains("#moj_import")) {
                    bad.add(p + " 仍在用 #moj_import");
                }
                if (src.contains("gl_VertexID") || src.contains("gl_InstanceID")) {
                    bad.add(p + " 仍在用 gl_VertexID/gl_InstanceID");
                }
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        if (!bad.isEmpty()) {
            fail("提供的原版资产不像 26.3：\n  " + String.join("\n  ", bad));
        }
    }

    @Test
    void 生成的globals块与原版逐字一致() {
        String vanilla = read(ROOT.resolve("include").resolve("globals.glsl"));
        List<String> members = new ArrayList<>();
        for (String line : vanilla.split("\n")) {
            String t = line.trim();
            if (t.endsWith(";") && !t.startsWith("layout") && !t.startsWith("//")
                    && !t.startsWith("}")) {
                members.add(t.substring(0, t.length() - 1).trim().replaceAll("\s+", " "));
            }
        }
        List<String> ours = new ArrayList<>();
        for (String m : GtProfile.MC_26_3.globalsMembers()) {
            ours.add(m.trim().replaceAll("\s+", " "));
        }
        if (!members.equals(ours)) {
            fail("Globals 块与原版不一致\n  原版：" + members + "\n  我们：" + ours);
        }
    }
}
