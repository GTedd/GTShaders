package mc.GTedd.cn.gtshaders;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import mc.GTedd.cn.gtshaders.codegen.GlslCodegen;
import mc.GTedd.cn.gtshaders.core.AnchorSlot;
import mc.GTedd.cn.gtshaders.core.GtProfile;
import mc.GTedd.cn.gtshaders.core.ShaderLayer;
import mc.GTedd.cn.gtshaders.core.ShaderProject;
import mc.GTedd.cn.gtshaders.export.ResourcePackExporter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 把<b>导出产物</b>整个解出来逐项核对。
 *
 * <h2>为什么值得立一套端到端用例</h2>
 *
 * <p>导出物是要分发给别人的，而它出错的方式非常安静：包能加载、游戏不报错、
 * 只是效果不出现。开发机上又没有 Minecraft 运行环境，真机反馈拿不到。
 * 所以这一层必须靠用例顶住。
 *
 * <p>{@code CodegenTest} 已经钉住了 GLSL 方言（330 + 扩展、显式 location、
 * Globals 成员顺序、pack_format 区间）。这里补的是那之上的一层——<b>整个包作为一件成品</b>
 * 拼得对不对：文件落在哪、JSON 里的引用指向的文件是否真的存在、通道链的乒乓方向、
 * 两种触发方式各自的落点。
 *
 * <p>其中「JSON 引用的着色器在包里存在」是最值得钉的一条：路径拼错时资源包<b>照样能加载</b>，
 * 只在执行 {@code /posteffect add} 那一刻才报一句找不到着色器，
 * 而那时人已经在游戏里、离生成它的地方很远了。
 */
class Export263Test {

    private static final String BODY = """
            // @param name=Tint type=color3 default=#99CCFF zh_cn=染色
            // @param name=Amount type=float min=0 max=1 default=0.5 zh_cn=强度
            void main() {
                fragColor = vec4(texture(InSampler, texCoord).rgb * Tint * Amount, 1.0);
            }
            """;

    private static final String ANCHORED_BODY = """
            // @param name=Tint type=color3 default=#FF8844 zh_cn=染色
            void main() {
                float k = gtAnchorStrength(0) * gtAnchorVisible(0)
                        * step(gtAnchorRange(0), gtAnchorRadius(0));
                fragColor = vec4(mix(texture(InSampler, texCoord).rgb, Tint, k), 1.0);
            }
            """;

    private static ShaderProject project(String name, String... bodies) {
        ShaderProject p = new ShaderProject(name);
        p.clearLayers();
        for (String b : bodies) {
            ShaderLayer l = new ShaderLayer("L", b);
            l.setEnabled(true);
            p.addLayer(l);
        }
        p.setExportProfile(GtProfile.MC_26_3);
        return p;
    }

    /** 把导出的 zip 整个读进内存：条目路径 → 文本内容。 */
    private static Map<String, String> unzip(Path zip) throws IOException {
        Map<String, String> out = new HashMap<>();
        try (ZipInputStream in = new ZipInputStream(Files.newInputStream(zip), StandardCharsets.UTF_8)) {
            ZipEntry e;
            while ((e = in.getNextEntry()) != null) {
                if (!e.isDirectory()) {
                    out.put(e.getName(), new String(in.readAllBytes(), StandardCharsets.UTF_8));
                }
            }
        }
        return out;
    }

    private static Map<String, String> exportPack(ShaderProject p, ResourcePackExporter.Options opts)
            throws IOException {
        Path dir = Files.createTempDirectory("gtshaders-export-test");
        try {
            ResourcePackExporter.Result r = ResourcePackExporter.export(p, dir, opts);
            assertTrue(Files.isRegularFile(r.file()), "导出文件没生成：" + r.file());
            return unzip(r.file());
        } finally {
            deleteTree(dir);
        }
    }

    private static void deleteTree(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (var walk = Files.walk(dir)) {
            walk.sorted((a, b) -> b.getNameCount() - a.getNameCount()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // 临时目录清不掉不该让用例失败：它本来就在系统临时区，迟早会被回收
                }
            });
        }
    }

    // ------------------------------------------------------------ /posteffect 模式

    @Test
    void 指令模式的包结构完整() throws IOException {
        ShaderProject p = project("My Effect", BODY);
        Map<String, String> pack = exportPack(p,
                ResourcePackExporter.Options.defaults("myfx"));

        assertTrue(pack.containsKey("pack.mcmeta"), "缺 pack.mcmeta");
        assertTrue(pack.containsKey("assets/gtshaders/post_effect/myfx.json"),
                "post effect JSON 不在 /posteffect 会去找的地方，实际条目：" + pack.keySet());
        assertTrue(pack.containsKey("assets/gtshaders/shaders/post/My_Effect.fsh")
                        || pack.keySet().stream().anyMatch(k -> k.startsWith("assets/gtshaders/shaders/post/")),
                "着色器不在 assets/gtshaders/shaders/post/ 下：" + pack.keySet());
    }

    @Test
    void json里引用的每个着色器在包里都真的存在() throws IOException {
        // 路径拼错时资源包照样加载得上，只在执行 /posteffect add 那一刻才报找不到着色器。
        // 那时人已经在游戏里了，离生成它的地方很远——所以在这里拦住
        ShaderProject p = project("Chain", BODY, BODY, BODY);
        Map<String, String> pack = exportPack(p,
                ResourcePackExporter.Options.defaults("chain"));

        JsonObject json = JsonParser.parseString(pack.get("assets/gtshaders/post_effect/chain.json"))
                .getAsJsonObject();
        JsonArray passes = json.getAsJsonArray("passes");
        int checked = 0;
        for (var el : passes) {
            String ref = el.getAsJsonObject().get("fragment_shader").getAsString();
            if (ref.startsWith("minecraft:")) {
                continue;   // 原版 blit，本来就不在我们的包里
            }
            String ns = ref.substring(0, ref.indexOf(':'));
            String path = ref.substring(ref.indexOf(':') + 1);
            String expected = "assets/" + ns + "/shaders/" + path + ".fsh";
            assertTrue(pack.containsKey(expected),
                    "JSON 引用了 " + ref + "，但包里没有 " + expected + "；实际有：" + pack.keySet());
            checked++;
        }
        assertEquals(3, checked, "三个启用的层应当各占一个通道");
    }

    @Test
    void packmcmeta用区间且下界是新格式起点() throws IOException {
        Map<String, String> pack = exportPack(project("Fmt", BODY),
                ResourcePackExporter.Options.defaults("fmt"));
        JsonObject meta = JsonParser.parseString(pack.get("pack.mcmeta")).getAsJsonObject()
                .getAsJsonObject("pack");
        assertEquals(97, meta.getAsJsonArray("min_format").get(0).getAsInt(),
                "下界必须是 97（26.3 pre 系列）——Globals 重排从 94 起，93 上读出来是错位的");
        assertTrue(meta.getAsJsonArray("max_format").get(0).getAsInt() >= 97,
                "上界要放宽，否则下一个快照涨了 pack_format 包就从列表里消失了");
        assertFalse(meta.has("pack_format"),
                "用 min/max 区间而不是单个 pack_format，包才能在相邻快照上至少被看见");
    }

    @Test
    void 导出的着色器逐条满足二六三的格式规则() throws IOException {
        Map<String, String> pack = exportPack(project("Rules", BODY),
                ResourcePackExporter.Options.defaults("rules"));
        String fsh = pack.entrySet().stream()
                .filter(e -> e.getKey().endsWith(".fsh")).findFirst().orElseThrow().getValue();

        assertTrue(fsh.startsWith("#version 330"), "26.3 仍是 330，不是 450");
        assertTrue(fsh.contains("#extension GL_ARB_separate_shader_objects : require"),
                "layout(location) 用在 in/out 上要靠这个扩展才合法");
        assertTrue(fsh.contains("layout(location = 0) in vec2 texCoord;"));
        assertTrue(fsh.contains("layout(location = 0) out vec4 fragColor;"));
        // Globals 成员顺序错了不会报编译错误，只会让 GameTime 读出垃圾值
        assertTrue(fsh.contains("""
                layout(std140) uniform Globals {
                    ivec3 CameraBlockPos;
                    float GlintAlpha;
                    vec3 CameraOffset;
                    float GameTime;
                    vec2 ScreenSize;"""), "26.3 的 Globals 顺序");
        assertFalse(fsh.contains("#moj_import"), "26.3 改用 #include，且我们本来就内联不 import");
    }

    @Test
    void 导出时时间归零而不是接着编辑那一刻跑() throws IOException {
        // 别人加载资源包时该从头开始播，而不是从作者按下导出的那一秒接上
        Map<String, String> pack = exportPack(project("T", BODY),
                ResourcePackExporter.Options.defaults("t"));
        JsonObject json = JsonParser.parseString(pack.get("assets/gtshaders/post_effect/t.json"))
                .getAsJsonObject();
        JsonArray block = json.getAsJsonArray("passes").get(0).getAsJsonObject()
                .getAsJsonObject("uniforms").getAsJsonArray(GlslCodegen.PARAM_BLOCK);
        JsonArray sys = block.get(0).getAsJsonObject().getAsJsonArray("value");
        assertEquals(GlslCodegen.SYSTEM_UNIFORM, block.get(0).getAsJsonObject().get("name").getAsString());
        assertEquals(0f, sys.get(0).getAsFloat(), 1e-9, "时间必须归零");
    }

    // ------------------------------------------------------------ 通道链

    @Test
    void 多通道在main与swap之间乒乓且奇数时补blit() throws IOException {
        // 同一个 target 不能在一个通道里既当输入又当输出，所以必须乒乓；
        // 奇数个通道时结果停在 swap 里，不补 blit 就什么都看不见
        Map<String, String> pack = exportPack(project("Pp", BODY, BODY, BODY),
                ResourcePackExporter.Options.defaults("pp"));
        JsonObject json = JsonParser.parseString(pack.get("assets/gtshaders/post_effect/pp.json"))
                .getAsJsonObject();
        assertTrue(json.getAsJsonObject("targets").has("swap"), "缺中转缓冲");

        JsonArray passes = json.getAsJsonArray("passes");
        assertEquals(4, passes.size(), "三个通道 + 一个补回主缓冲的 blit");

        String[] wantIn = {"minecraft:main", "swap", "minecraft:main", "swap"};
        String[] wantOut = {"swap", "minecraft:main", "swap", "minecraft:main"};
        for (int i = 0; i < 4; i++) {
            JsonObject pass = passes.get(i).getAsJsonObject();
            assertEquals(wantIn[i], pass.getAsJsonArray("inputs").get(0)
                    .getAsJsonObject().get("target").getAsString(), "第 " + i + " 通道的输入");
            assertEquals(wantOut[i], pass.get("output").getAsString(), "第 " + i + " 通道的输出");
        }
        assertEquals("minecraft:post/blit",
                passes.get(3).getAsJsonObject().get("fragment_shader").getAsString(),
                "收尾必须是原版 blit");
    }

    @Test
    void 偶数通道不补blit() throws IOException {
        Map<String, String> pack = exportPack(project("Even", BODY, BODY),
                ResourcePackExporter.Options.defaults("even"));
        JsonObject json = JsonParser.parseString(pack.get("assets/gtshaders/post_effect/even.json"))
                .getAsJsonObject();
        JsonArray passes = json.getAsJsonArray("passes");
        assertEquals(2, passes.size());
        assertEquals("minecraft:main", passes.get(1).getAsJsonObject().get("output").getAsString(),
                "偶数通道时结果本来就落在主缓冲上");
    }

    // ------------------------------------------------------------ 三种触发模式

    @Test
    void 常驻模式落在原版的end_of_frame上() throws IOException {
        // end_of_frame 是 26.3 的固定 id，加载资源包即常驻；命名空间必须是 minecraft
        Map<String, String> pack = exportPack(project("Eof", BODY),
                ResourcePackExporter.Options.defaults("eof")
                        .withMode(ResourcePackExporter.Mode.END_OF_FRAME));
        assertTrue(pack.containsKey("assets/minecraft/post_effect/end_of_frame.json"),
                "实际条目：" + pack.keySet());
    }

    @Test
    void 常驻模式下着色器仍然放自己的命名空间() throws IOException {
        // end_of_frame 那个 JSON 必须落在 minecraft 命名空间，但 .fsh 不能——
        // 写进 minecraft:shaders/post/ 会把原版同名文件一起盖掉
        Map<String, String> pack = exportPack(project("Eof2", BODY),
                ResourcePackExporter.Options.defaults("eof2")
                        .withMode(ResourcePackExporter.Mode.END_OF_FRAME));
        assertTrue(pack.keySet().stream().anyMatch(k -> k.startsWith("assets/gtshaders/shaders/post/")),
                "着色器不该写进 minecraft 命名空间");
        assertTrue(pack.keySet().stream().noneMatch(k -> k.startsWith("assets/minecraft/shaders/")),
                "实际条目：" + pack.keySet());
    }

    @Test
    void 两种触发方式来回切换() {
        // 26.3 只有指令与常驻两条路；26.2 那条「覆盖原版硬编码 id」的妥协方案已经删掉了
        assertEquals(ResourcePackExporter.Mode.END_OF_FRAME,
                ResourcePackExporter.Mode.POST_EFFECT_COMMAND.next());
        assertEquals(ResourcePackExporter.Mode.POST_EFFECT_COMMAND,
                ResourcePackExporter.Mode.END_OF_FRAME.next());
        assertEquals(ResourcePackExporter.Mode.POST_EFFECT_COMMAND,
                ResourcePackExporter.Options.defaults("x").mode());
    }

    @Test
    void 效果id被清洗成合法的资源路径() throws IOException {
        // /posteffect add @s <ns>:<id> 里的 id 必须是合法资源路径，
        // 大写字母和空格都会让指令直接报错——而那时包已经打好发出去了
        Map<String, String> pack = exportPack(project("Dirty Name", BODY),
                ResourcePackExporter.Options.defaults("My Effect!"));
        String jsonPath = pack.keySet().stream()
                .filter(k -> k.startsWith("assets/gtshaders/post_effect/")).findFirst().orElseThrow();
        String id = jsonPath.substring(jsonPath.lastIndexOf('/') + 1, jsonPath.length() - ".json".length());
        assertTrue(id.matches("[a-z0-9._/-]+"), "效果 id 不是合法资源路径：" + id);

        String fshPath = pack.keySet().stream()
                .filter(k -> k.endsWith(".fsh")).findFirst().orElseThrow();
        String shaderPath = fshPath.substring("assets/gtshaders/shaders/".length(),
                fshPath.length() - ".fsh".length());
        assertTrue(shaderPath.matches("[a-z0-9._/-]+"), "着色器路径不是合法资源路径：" + shaderPath);
    }

    // ------------------------------------------------------------ 锚点在 26.3 上

    @Test
    void 锚点块在二六三导出产物里也三方对齐() throws IOException {
        Map<String, String> pack = exportPack(project("Anch", ANCHORED_BODY),
                ResourcePackExporter.Options.defaults("anch"));

        String fsh = pack.entrySet().stream()
                .filter(e -> e.getKey().endsWith(".fsh")).findFirst().orElseThrow().getValue();
        assertTrue(fsh.contains("vec4 " + GlslCodegen.ANCHOR_A_UNIFORM + "[" + AnchorSlot.SLOTS + "];"));

        JsonObject json = JsonParser.parseString(pack.get("assets/gtshaders/post_effect/anch.json"))
                .getAsJsonObject();
        JsonArray block = json.getAsJsonArray("passes").get(0).getAsJsonObject()
                .getAsJsonObject("uniforms").getAsJsonArray(GlslCodegen.PARAM_BLOCK);
        assertEquals(GlslCodegen.ANCHOR_INFO_UNIFORM, block.get(3).getAsJsonObject().get("name").getAsString());
        assertEquals(3 + 1 + AnchorSlot.SLOTS * AnchorSlot.VEC4_PER_SLOT + 1, block.size(),
                "3 个系统 vec4 + 表头 + 16 个锚点 vec4 + 1 个用户参数");
    }

    @Test
    void 纯资源包里的锚点初值全为零() throws IOException {
        // 这是 gtAnchor 在没有 mod 的 26.3 客户端上的<b>全部</b>行为：
        // 没有人每帧改写这个 uniform，所以永远是 0 —— 即 gtAnchorValid 恒为 false，
        // 效果自动退回屏幕空间形态。这是有意的降级，不是缺陷，所以要钉住
        Map<String, String> pack = exportPack(project("Fallback", ANCHORED_BODY),
                ResourcePackExporter.Options.defaults("fallback"));
        JsonObject json = JsonParser.parseString(pack.get("assets/gtshaders/post_effect/fallback.json"))
                .getAsJsonObject();
        JsonArray block = json.getAsJsonArray("passes").get(0).getAsJsonObject()
                .getAsJsonObject("uniforms").getAsJsonArray(GlslCodegen.PARAM_BLOCK);
        for (int i = 3; i < 3 + 1 + AnchorSlot.SLOTS * AnchorSlot.VEC4_PER_SLOT; i++) {
            JsonArray v = block.get(i).getAsJsonObject().getAsJsonArray("value");
            for (int c = 0; c < 4; c++) {
                assertEquals(0f, v.get(c).getAsFloat(), 1e-9,
                        block.get(i).getAsJsonObject().get("name").getAsString() + " 初值必须是 0");
            }
        }
    }

    // ------------------------------------------------------------ 产物可复现

    @Test
    void 同一个工程导出两次产出逐字一致的着色器() throws IOException {
        // 导出物是要分发的：同样的输入必须给出同样的字节，否则没法做差异比对，
        // 也没法判断「这个包和我上次发的那个是不是同一个」
        String first = firstFsh(exportPack(project("Same", BODY),
                ResourcePackExporter.Options.defaults("same")));
        String second = firstFsh(exportPack(project("Same", BODY),
                ResourcePackExporter.Options.defaults("same")));

        assertNotNull(first);
        assertEquals(first, second);
    }

    private static String firstFsh(Map<String, String> pack) {
        return pack.entrySet().stream()
                .filter(e -> e.getKey().endsWith(".fsh")).findFirst().orElseThrow().getValue();
    }
}
