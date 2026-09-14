package mc.GTedd.cn.gtshaders;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import mc.GTedd.cn.gtshaders.codegen.GlslCodegen;
import mc.GTedd.cn.gtshaders.codegen.PackParity;
import mc.GTedd.cn.gtshaders.codegen.PostEffectJsonBuilder;
import mc.GTedd.cn.gtshaders.core.BlendMode;
import mc.GTedd.cn.gtshaders.core.GtProfile;
import mc.GTedd.cn.gtshaders.core.ShaderLayer;
import mc.GTedd.cn.gtshaders.core.ShaderProject;
import mc.GTedd.cn.gtshaders.export.ResourcePackExporter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 钉住「预览 = 直接加载资源包」这条承诺。
 *
 * <p>预览链跑的是原版 {@code PostChain}，编译器也是原版的，所以两边能不一样的地方只剩三处：
 * <ol>
 *   <li><b>着色器源码</b>——预览与导出必须是同一份文本（这里连 {@code GTTime} 的定义一起钉住：
 *       它必须从原版 {@code GameTime} 取时间，否则导出物里时间是死的）；</li>
 *   <li><b>post effect JSON</b>——除了着色器路径，预览生成的和导出的必须逐字节相同；</li>
 *   <li><b>每帧写入的 uniform</b>——这一层在单元测试里够不到 Minecraft，靠 {@link PackParity}
 *       把「资源包里没有来源」的量列出来，导出面板与 README 都要能看到。</li>
 * </ol>
 */
class PackParityTest {

    private static final String PLAIN = """
            // @param name=Amount type=float min=0 max=1 default=0.5
            void main() {
                float k = 0.5 + 0.5 * sin(GTTime);
                fragColor = vec4(texture(InSampler, texCoord).rgb * Amount * k, 1.0);
            }
            """;

    private static final String ANCHORED = """
            void main() {
                float k = gtAnchorStrength(0) * gtAnchorVisible(0);
                fragColor = vec4(mix(texture(InSampler, texCoord).rgb, vec3(1.0), k), 1.0);
            }
            """;

    private static final String EDITOR_ONLY = """
            void main() {
                float k = GTDeltaTime * 60.0 + GTFrame * 0.0;
                fragColor = vec4(texture(InSampler, texCoord).rgb * k, 1.0);
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

    private static ResourcePackExporter.Result export(ShaderProject p, Path dir) throws IOException {
        return ResourcePackExporter.export(p, dir, ResourcePackExporter.Options.defaults("fx"));
    }

    // ------------------------------------------------------------ 1. 源码

    @Test
    void 时间来自原版GameTime而不是编辑器私有量() {
        GlslCodegen.Output out = GlslCodegen.generate(GtProfile.MC_26_3, PLAIN, List.of(), BlendMode.NORMAL);
        String src = out.source();
        // 资源包里 GTSystem.x 恒为 0，于是 GTTime 就是 GameTime 折成的秒；编辑器通过改 x 来暂停/回拨。
        // 没有这一行，127 个用 GTTime 做动画的库效果导出后全是静止画面
        assertTrue(src.contains("#define GTTime (GameTime * 1200.0 + GTSystem.x)"), src);
        assertTrue(src.contains("#define iTime GTTime"), src);
        // GameTime 必须真的在 Globals 块里，否则上面那行编译不过
        assertTrue(src.contains("float GameTime;"), src);
    }

    @Test
    void 预览与导出生成的源码是同一份() throws IOException {
        // 预览和导出各自调 project.generate，只是路径前缀不同；两份源码除了不含路径本来就该完全一致。
        // 这里把它钉成用例：以后谁想给「预览版」加个特殊 define，会先在这里被拦住
        ShaderProject p = project("Same", PLAIN, ANCHORED);
        ShaderProject.Build preview = p.generate(GtProfile.MC_26_3, "post/preview3");
        Path dir = Files.createTempDirectory("gtshaders-parity");
        try {
            Map<String, String> pack = unzip(export(p, dir).file());
            ShaderProject.Build exported = p.generate(GtProfile.MC_26_3,
                    "post/" + ResourcePackExporter.sanitize(p.name()));
            assertEquals(preview.passes().size(), exported.passes().size());
            for (int i = 0; i < preview.passes().size(); i++) {
                String previewSrc = preview.passes().get(i).output().source();
                String packSrc = pack.get("assets/gtshaders/shaders/"
                        + exported.passes().get(i).shaderPath() + ".fsh");
                assertNotNull(packSrc, "包里缺第 " + i + " 个通道的着色器：" + pack.keySet());
                assertEquals(previewSrc, packSrc, "第 " + i + " 个通道预览与导出源码不同");
            }
        } finally {
            deleteTree(dir);
        }
    }

    // ------------------------------------------------------------ 2. JSON

    @Test
    void 预览与导出的JSON只差着色器路径() throws IOException {
        ShaderProject p = project("Json", PLAIN, ANCHORED, PLAIN);
        JsonObject preview = PostEffectJsonBuilder.buildChain("gtshaders",
                p.generate(GtProfile.MC_26_3, "post/preview9"), PostEffectJsonBuilder.PACK_SYSTEM);
        Path dir = Files.createTempDirectory("gtshaders-parity");
        try {
            Map<String, String> pack = unzip(export(p, dir).file());
            JsonObject exported = JsonParser.parseString(
                    pack.get("assets/gtshaders/post_effect/fx.json")).getAsJsonObject();
            assertEquals(normalize(preview), normalize(exported));
        } finally {
            deleteTree(dir);
        }
    }

    @Test
    void 导出的GTSystem就是资源包常量() throws IOException {
        ShaderProject p = project("Sys", PLAIN);
        Path dir = Files.createTempDirectory("gtshaders-parity");
        try {
            Map<String, String> pack = unzip(export(p, dir).file());
            JsonObject json = JsonParser.parseString(
                    pack.get("assets/gtshaders/post_effect/fx.json")).getAsJsonObject();
            JsonArray block = json.getAsJsonArray("passes").get(0).getAsJsonObject()
                    .getAsJsonObject("uniforms").getAsJsonArray(GlslCodegen.PARAM_BLOCK);
            JsonObject sys = block.get(0).getAsJsonObject();
            assertEquals(GlslCodegen.SYSTEM_UNIFORM, sys.get("name").getAsString());
            JsonArray v = sys.getAsJsonArray("value");
            float[] expect = PostEffectJsonBuilder.PACK_SYSTEM;
            for (int i = 0; i < 4; i++) {
                assertEquals(expect[i], v.get(i).getAsFloat(), 0f, "GTSystem[" + i + "]");
            }
            // 偏移必须是 0：任何别的数都会让资源包里的时间从一个莫名其妙的起点开始
            assertEquals(0f, v.get(0).getAsFloat(), 0f);
        } finally {
            deleteTree(dir);
        }
    }

    /** 把 fragment_shader 抹平，剩下的必须完全相同。 */
    private static JsonElement normalize(JsonObject chain) {
        JsonObject copy = JsonParser.parseString(chain.toString()).getAsJsonObject();
        for (JsonElement pass : copy.getAsJsonArray("passes")) {
            JsonObject o = pass.getAsJsonObject();
            if (o.has("fragment_shader") && o.get("fragment_shader").getAsString().startsWith("gtshaders:")) {
                o.addProperty("fragment_shader", "gtshaders:<pass>");
            }
        }
        return copy;
    }

    // ------------------------------------------------------------ 3. 资源包里没有来源的量

    @Test
    void 纯屏幕空间效果没有任何差异备注() {
        ShaderProject p = project("Plain", PLAIN);
        List<PackParity.Note> notes = PackParity.audit(
                p.generate(GtProfile.MC_26_3, "post/a"), p.generateOutline(GtProfile.MC_26_3, "post/b"));
        assertTrue(notes.isEmpty(), notes.toString());
    }

    @Test
    void 用了锚点或编辑器专属量会被点名() {
        ShaderProject p = project("Mixed", PLAIN, ANCHORED, EDITOR_ONLY);
        List<PackParity.Note> notes = PackParity.audit(
                p.generate(GtProfile.MC_26_3, "post/a"), p.generateOutline(GtProfile.MC_26_3, "post/b"));
        assertEquals(2, notes.size(), notes.toString());
        assertTrue(notes.get(0).what().contains("gtAnchor"), notes.toString());
        assertTrue(notes.get(1).what().contains("GTDeltaTime"), notes.toString());
        assertTrue(notes.get(1).what().contains("GTFrame"), notes.toString());
    }

    @Test
    void 导出结果与README都带上这些备注() throws IOException {
        ShaderProject p = project("Readme", ANCHORED);
        Path dir = Files.createTempDirectory("gtshaders-parity");
        try {
            ResourcePackExporter.Result r = export(p, dir);
            assertEquals(1, r.notes().size(), r.notes().toString());
            String readme = unzip(r.file()).get("README.txt");
            assertNotNull(readme);
            assertTrue(readme.contains("gtAnchor"), readme);
            // 时间的行为也要写进说明：拿到包的人不知道 GameTime 会回绕
            assertTrue(readme.contains("GameTime"), readme);
            assertTrue(readme.contains("1200"), readme);
        } finally {
            deleteTree(dir);
        }
    }

    @Test
    void 没有差异时README不出现那一节() throws IOException {
        ShaderProject p = project("Clean", PLAIN);
        Path dir = Files.createTempDirectory("gtshaders-parity");
        try {
            ResourcePackExporter.Result r = export(p, dir);
            assertTrue(r.notes().isEmpty());
            String readme = unzip(r.file()).get("README.txt");
            assertFalse(readme.contains("没有来源的量"), readme);
        } finally {
            deleteTree(dir);
        }
    }

    private static void deleteTree(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (var walk = Files.walk(dir)) {
            walk.sorted((a, b) -> b.getNameCount() - a.getNameCount()).forEach(f -> {
                try {
                    Files.deleteIfExists(f);
                } catch (IOException ignored) {
                    // 临时目录清不掉不该让用例失败
                }
            });
        }
    }
}
