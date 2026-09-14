package mc.GTedd.cn.gtshaders.runtime;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import org.jspecify.annotations.Nullable;
import mc.GTedd.cn.gtshaders.GTShaders;
import mc.GTedd.cn.gtshaders.codegen.VanillaImporter;
import mc.GTedd.cn.gtshaders.core.ShaderLayer;
import mc.GTedd.cn.gtshaders.core.ShaderProject;
import mc.GTedd.cn.gtshaders.i18n.GtLang;

import java.io.BufferedReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 原版自带后处理效果的发现、直接调用与导入。
 *
 * <p><b>不写死效果清单</b>，而是扫 {@code assets/*&#47;post_effect/*.json}。
 * 这样资源包新增或覆盖的效果会自动出现在列表里，而 Mojang 以后加了新效果也不用改代码——
 * 「自动识别」在这里不只是识别参数，也包括识别有哪些效果可用。
 *
 * <p>每个效果自动判定两件事：
 * <ul>
 *   <li><b>能不能直接挂上去</b>：所有输入/输出目标要么是 {@code minecraft:main}、
 *       要么是它自己声明的 target。26.3 自带的 {@code entity_outline} 就是反例——它读
 *       {@code minecraft:entity_outline}，而 {@code /posteffect} 挂的链只拿得到
 *       {@code MAIN_TARGETS}（里面只有 {@code minecraft:main}），挂上去会被
 *       {@code PostChain.load} 直接拒掉。</li>
 *   <li><b>能不能导入成可编辑工程</b>：在上一条基础上，还要求每个通道都用
 *       {@code core/screenquad}、只有一个名为 {@code In} 的输入，
 *       且片段着色器不依赖额外采样器或自定义顶点输出。{@code spider} 是反例——
 *       它的通道用 {@code post/rotscale} 当顶点着色器，还多传了一个 {@code scaledCoord}
 *       顶点输出，我们生成的头部里没有这个 varying。</li>
 * </ul>
 */
public final class VanillaEffects {

    private static final String MAIN_TARGET = "minecraft:main";
    private static final String SCREENQUAD = "minecraft:core/screenquad";
    private static final String BLIT = "minecraft:post/blit";
    private static final String DIR = "post_effect";
    private static final String EXT = ".json";

    /**
     * @param id            效果 id，如 {@code minecraft:blur}
     * @param applicable    能否直接挂到渲染器上
     * @param importable    能否导入成可编辑工程
     * @param reason        不可导入的原因（已翻译）；可导入时为 null
     * @param editablePasses 导入后会产生几个效果层
     */
    public record Entry(Identifier id, boolean applicable, boolean importable,
                        @Nullable String reason, int editablePasses) {

        public String label() {
            return id.getNamespace().equals("minecraft") ? id.getPath() : id.toString();
        }
    }

    private VanillaEffects() {
    }

    // ---------------------------------------------------------------- 发现

    /** 扫描所有可用的 post effect。会读文件，只在打开菜单时调一次。 */
    public static List<Entry> scan() {
        List<Entry> out = new ArrayList<>();
        Minecraft mc = Minecraft.getInstance();
        if (mc == null) {
            return out;
        }
        ResourceManager rm = mc.getResourceManager();
        var found = rm.listResources(DIR, id -> id.getPath().endsWith(EXT));
        for (Identifier file : new java.util.TreeSet<>(found.keySet())) {
            Identifier id = toEffectId(file);
            if (id == null) {
                continue;
            }
            try {
                out.add(analyse(rm, id, file));
            } catch (IOException | RuntimeException e) {
                GTShaders.LOGGER.warn("无法分析原版效果 {}：{}", id, e.toString());
            }
        }
        return out;
    }

    private static @Nullable Identifier toEffectId(Identifier file) {
        String path = file.getPath();
        if (!path.startsWith(DIR + "/") || !path.endsWith(EXT)) {
            return null;
        }
        return Identifier.fromNamespaceAndPath(file.getNamespace(),
                path.substring(DIR.length() + 1, path.length() - EXT.length()));
    }

    private static Entry analyse(ResourceManager rm, Identifier id, Identifier file) throws IOException {
        JsonObject root = readJson(rm, file);
        Set<String> declared = new LinkedHashSet<>();
        declared.add(MAIN_TARGET);
        if (root.has("targets") && root.get("targets").isJsonObject()) {
            declared.addAll(root.getAsJsonObject("targets").keySet());
        }

        JsonArray passes = root.has("passes") ? root.getAsJsonArray("passes") : new JsonArray();
        boolean applicable = true;
        String reason = null;
        int editable = 0;

        for (JsonElement el : passes) {
            if (!el.isJsonObject()) {
                continue;
            }
            JsonObject pass = el.getAsJsonObject();
            for (String target : passTargets(pass)) {
                if (!declared.contains(target)) {
                    applicable = false;
                    if (reason == null) {
                        reason = GtLang.get("gtshaders.vanilla.reject_target", target);
                    }
                }
            }
            if (isBlit(pass)) {
                // 收尾的 blit 由我们自己补，不算成一个可编辑层
                continue;
            }
            String passReason = passRejectReason(rm, pass);
            if (passReason != null) {
                if (reason == null) {
                    reason = passReason;
                }
            } else {
                editable++;
            }
        }
        boolean importable = applicable && reason == null && editable > 0;
        return new Entry(id, applicable, importable, reason, editable);
    }

    private static List<String> passTargets(JsonObject pass) {
        List<String> out = new ArrayList<>();
        if (pass.has("output")) {
            out.add(pass.get("output").getAsString());
        }
        if (pass.has("inputs") && pass.get("inputs").isJsonArray()) {
            for (JsonElement in : pass.getAsJsonArray("inputs")) {
                if (in.isJsonObject() && in.getAsJsonObject().has("target")) {
                    out.add(in.getAsJsonObject().get("target").getAsString());
                }
            }
        }
        return out;
    }

    private static boolean isBlit(JsonObject pass) {
        return pass.has("fragment_shader") && BLIT.equals(pass.get("fragment_shader").getAsString());
    }

    /** @return 该通道不可导入的原因；null 表示可以 */
    private static @Nullable String passRejectReason(ResourceManager rm, JsonObject pass) {
        String vsh = pass.has("vertex_shader") ? pass.get("vertex_shader").getAsString() : "";
        if (!SCREENQUAD.equals(vsh)) {
            return GtLang.get("gtshaders.vanilla.reject_vertex", vsh);
        }
        JsonArray inputs = pass.has("inputs") ? pass.getAsJsonArray("inputs") : new JsonArray();
        if (inputs.size() != 1) {
            return GtLang.get("gtshaders.vanilla.reject_inputs", inputs.size());
        }
        try {
            return VanillaImporter.rejectReason(readShader(rm, fragmentShader(pass)));
        } catch (IOException e) {
            return e.getMessage();
        }
    }

    private static Identifier fragmentShader(JsonObject pass) {
        return Identifier.parse(pass.get("fragment_shader").getAsString());
    }

    // ---------------------------------------------------------------- 直接调用

    /**
     * 把原版效果直接挂到渲染器上，等价于 26.3 的 {@code /posteffect add @s <id>}。
     *
     * <p>整条链完全由原版加载和执行，我们只是替它填了那个字段——所以不需要编译、
     * 也不会跟我们自己的预览链抢同一个 id。
     */
    public static void apply(Identifier id) {
        PreviewRuntime.applyVanilla(id);
    }

    // ---------------------------------------------------------------- 导入

    /** 把一个原版效果导入成可编辑工程：每个非 blit 通道变成一个效果层。 */
    public static ShaderProject importProject(Identifier id) throws IOException {
        Minecraft mc = Minecraft.getInstance();
        ResourceManager rm = mc.getResourceManager();
        Identifier file = Identifier.fromNamespaceAndPath(id.getNamespace(),
                DIR + "/" + id.getPath() + EXT);
        JsonObject root = readJson(rm, file);

        ShaderProject project = new ShaderProject(GtLang.get("gtshaders.vanilla.project_name", id.getPath()));
        JsonArray passes = root.has("passes") ? root.getAsJsonArray("passes") : new JsonArray();
        int index = 0;
        for (JsonElement el : passes) {
            if (!el.isJsonObject()) {
                continue;
            }
            JsonObject pass = el.getAsJsonObject();
            if (isBlit(pass)) {
                continue;
            }
            Identifier fsh = fragmentShader(pass);
            String source = VanillaImporter.toAuthorSource(readShader(rm, fsh), fsh.toString(),
                    uniformsOf(pass));

            ShaderLayer layer = new ShaderLayer(id.getPath() + " " + (++index), source);
            // 导入是明确的「我要看这个效果」，所以直接启用——
            // 这跟「新建工程默认无效果」不冲突：后者是没人要求过的效果
            layer.setEnabled(true);
            layer.setBilinear(bilinearOf(pass));
            project.addLayer(layer);
        }
        project.setSelectedIndex(0);
        return project;
    }

    private static boolean bilinearOf(JsonObject pass) {
        if (!pass.has("inputs") || !pass.get("inputs").isJsonArray()) {
            return false;
        }
        for (JsonElement in : pass.getAsJsonArray("inputs")) {
            if (in.isJsonObject() && in.getAsJsonObject().has("bilinear")
                    && in.getAsJsonObject().get("bilinear").getAsBoolean()) {
                return true;
            }
        }
        return false;
    }

    private static List<VanillaImporter.Uniform> uniformsOf(JsonObject pass) {
        List<VanillaImporter.Uniform> out = new ArrayList<>();
        if (!pass.has("uniforms") || !pass.get("uniforms").isJsonObject()) {
            return out;
        }
        for (var block : pass.getAsJsonObject("uniforms").entrySet()) {
            if (!block.getValue().isJsonArray()) {
                continue;
            }
            for (JsonElement el : block.getValue().getAsJsonArray()) {
                if (!el.isJsonObject()) {
                    continue;
                }
                JsonObject u = el.getAsJsonObject();
                if (!u.has("name")) {
                    continue;
                }
                out.add(new VanillaImporter.Uniform(u.get("name").getAsString(),
                        u.has("type") ? u.get("type").getAsString() : "float",
                        valueOf(u.get("value"))));
            }
        }
        return out;
    }

    private static float[] valueOf(@Nullable JsonElement value) {
        float[] v = new float[4];
        if (value == null) {
            return v;
        }
        if (value.isJsonArray()) {
            JsonArray arr = value.getAsJsonArray();
            for (int i = 0; i < Math.min(4, arr.size()); i++) {
                v[i] = arr.get(i).getAsFloat();
            }
        } else if (value.isJsonPrimitive()) {
            v[0] = value.getAsFloat();
        }
        return v;
    }

    // ---------------------------------------------------------------- 资源读取

    private static JsonObject readJson(ResourceManager rm, Identifier file) throws IOException {
        Optional<Resource> res = rm.getResource(file);
        if (res.isEmpty()) {
            throw new IOException(GtLang.get("gtshaders.vanilla.missing", file.toString()));
        }
        try (BufferedReader reader = res.get().openAsReader()) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        }
    }

    /** 片段着色器 id（如 {@code minecraft:post/box_blur}）→ {@code shaders/post/box_blur.fsh}。 */
    private static String readShader(ResourceManager rm, Identifier shader) throws IOException {
        Identifier file = Identifier.fromNamespaceAndPath(shader.getNamespace(),
                "shaders/" + shader.getPath() + ".fsh");
        Optional<Resource> res = rm.getResource(file);
        if (res.isEmpty()) {
            throw new IOException(GtLang.get("gtshaders.vanilla.missing", file.toString()));
        }
        try (BufferedReader reader = res.get().openAsReader()) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append('\n');
            }
            return sb.toString();
        }
    }
}
