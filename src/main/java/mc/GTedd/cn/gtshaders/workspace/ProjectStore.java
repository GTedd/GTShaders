package mc.GTedd.cn.gtshaders.workspace;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.Strictness;
import mc.GTedd.cn.gtshaders.core.AnchorBinding;
import mc.GTedd.cn.gtshaders.core.BlendMode;
import mc.GTedd.cn.gtshaders.core.GtProfile;
import mc.GTedd.cn.gtshaders.core.ShaderLayer;
import mc.GTedd.cn.gtshaders.core.ParamType;
import mc.GTedd.cn.gtshaders.core.ShaderParam;
import mc.GTedd.cn.gtshaders.core.ShaderProject;
import mc.GTedd.cn.gtshaders.export.ExportTarget;
import mc.GTedd.cn.gtshaders.export.StagedWrite;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 工程的存取。格式是可读的 JSON，作者可以直接拿文本编辑器改、也能塞进 git。
 *
 * <p>只存「源码 + 图层设置 + 参数当前值」，不存参数定义本身——参数定义永远由源码扫描得来。
 * 这样即使作者在外部编辑器里改了源码再回来打开，参数面板也不会和源码脱节。
 */
public final class ProjectStore {

    private static final String EXT = ".gtshader.json";

    private ProjectStore() {
    }

    public static Path save(ShaderProject project) throws IOException {
        return save(project, Workspace.projectsDir());
    }

    /** 保存到指定工程目录；与游戏内保存共用相同的文件名和写入保护。 */
    public static Path save(ShaderProject project, Path dir) throws IOException {
        Path file = dir.resolve(fileNameFor(project.name()));
        // JsonObject 的默认宽松写法会把 NaN 原样写入。保存前按标准 JSON 校验，
        // 无效状态必须报错，不能覆盖上一份仍可用的工程。
        StagedWrite.writeUtf8(file,
                new GsonBuilder().setPrettyPrinting().setStrictness(Strictness.STRICT)
                        .create().toJson(toJson(project)) + "\n");
        return file;
    }

    /** 工程文件名（含后缀）。保留可读字符（含中文），只收敛文件系统不允许的部分。 */
    public static String fileNameFor(String name) {
        return safeFileName(name) + EXT;
    }

    /**
     * 工程文件名的安全化：保留可读字符（中文、空格都留着），只处理文件系统不接受的部分。
     *
     * <p>刻意<b>不用</b> {@code ResourcePackExporter.sanitize} 那种资源路径收敛：
     * 那是给 zip 里的路径用的，ASCII-only 且有损，会把「未命名」整个丢掉变成
     * {@code untitled}——于是所有中文工程名都存进同一个文件、互相覆盖。
     * 工程文件名是给人看的，跟导出包里的资源名不是一回事。
     *
     * <p>规则本体在 {@link ExportTarget#fileName}，和导出包的文件名共用一份：
     * 「哪些字符文件系统收不下」跟文件里装的是什么无关，各写一份迟早会漂移，
     * 而漂移的表现是某一边在某台机器上突然写不出文件。
     */
    private static String safeFileName(String name) {
        return ExportTarget.fileName(name, "untitled");
    }

    /**
     * 工程 → JSON。抽出来是为了让快照复用同一份格式：
     * 快照和工程存的本来就是同一样东西，各写一份序列化迟早会漂移。
     */
    public static JsonObject toJson(ShaderProject project) {
        JsonObject root = new JsonObject();
        // format 3 起多了 anchors、format 4 起取景框记在每一层上、
        // format 5 起绑定有稳定 id，锚点参数存 id 而不是槽位号。
        // 老工程缺哪一段都能正常打开：缺 anchors 就是零条绑定，缺层级取景框就沿用工程级那个
        root.addProperty("format", 5);
        root.addProperty("name", project.name());
        root.addProperty("exportProfile", project.exportProfile().name());

        JsonArray vp = new JsonArray();
        vp.add(project.viewport().x0());
        vp.add(project.viewport().y0());
        vp.add(project.viewport().x1());
        vp.add(project.viewport().y1());
        root.add("viewport", vp);

        JsonArray layers = new JsonArray();
        for (ShaderLayer layer : project.layers()) {
            JsonObject lo = new JsonObject();
            lo.addProperty("name", layer.name());
            lo.addProperty("enabled", layer.isEnabled());
            lo.addProperty("strength", layer.strength());
            lo.addProperty("blend", layer.blendMode().id());
            lo.addProperty("bilinear", layer.isBilinear());
            // 只有核心着色器层才写这一项：后处理层没有 kind，写个 null 进去徒增噪音，
            // 而且老版本的工程文件里本来就没有，缺省即"后处理"是天然向后兼容的
            if (layer.kindId() != null) {
                lo.addProperty("kind", layer.kindId());
            }
            lo.addProperty("source", layer.authorSource());

            // format 4 起取景框记在层上。老工程只有工程级的那一个，读进来会迁移到每一层
            JsonArray lvp = new JsonArray();
            lvp.add(layer.viewport().x0());
            lvp.add(layer.viewport().y0());
            lvp.add(layer.viewport().x1());
            lvp.add(layer.viewport().y1());
            lo.add("viewport", lvp);

            JsonObject values = new JsonObject();
            for (ShaderParam p : layer.params()) {
                JsonArray arr = new JsonArray();
                for (int i = 0; i < p.type().components(); i++) {
                    arr.add(p.get(i));
                }
                values.add(p.name(), arr);
            }
            lo.add("values", values);

            // 锚点参数除了值还要存它指向<b>哪一条</b>绑定。和 values 平行放而不是塞进
            // values 里，是因为 values 的形状（名字 → 数组）被 applyValues 和外部工具依赖着
            JsonObject refs = new JsonObject();
            for (ShaderParam p : layer.params()) {
                if (p.type() == ParamType.ANCHOR && !p.anchorRef().isEmpty()) {
                    refs.addProperty(p.name(), p.anchorRef());
                }
            }
            if (!refs.isEmpty()) {
                lo.add("anchorRefs", refs);
            }

            layers.add(lo);
        }
        root.add("layers", layers);

        // 一条绑定都没有时整个键都不写。空数组和「这个工程根本没用锚点」在语义上是一回事，
        // 而不写就让老工具、老版本读到的东西完全一样
        if (!project.anchors().isEmpty()) {
            JsonArray anchors = new JsonArray();
            for (AnchorBinding a : project.anchors()) {
                anchors.add(writeAnchor(a));
            }
            root.add("anchors", anchors);
        }
        return root;
    }

    private static JsonObject writeAnchor(AnchorBinding a) {
        JsonObject o = new JsonObject();
        o.addProperty("id", a.id());
        o.addProperty("name", a.name());
        o.addProperty("enabled", a.isEnabled());
        o.addProperty("source", a.source().id());
        o.addProperty("selector", a.selector());
        o.addProperty("trigger", a.trigger().id());
        o.addProperty("stick", a.isStick());
        o.addProperty("duration", a.duration());
        o.addProperty("easing", a.easing().id());
        o.addProperty("worldRadius", a.worldRadius());
        o.addProperty("strength", a.strength());
        o.addProperty("maxSlots", a.maxSlots());
        o.addProperty("occlusion", a.isOcclusion());
        o.addProperty("yOffset", a.yOffset());
        o.addProperty("maxDistance", a.maxDistance());
        o.addProperty("maxScreenRadius", a.maxScreenRadius());
        // 载体扩展。全是默认值时一个键都不写——绝大多数绑定用不上朝向，
        // 每条都多七行会让手改工程文件的人以为这些是必填项
        if (a.emitterType() != 0) {
            o.addProperty("emitterType", a.emitterType());
        }
        if (a.custom1() != 0f) {
            o.addProperty("custom1", a.custom1());
        }
        if (a.custom2() != 0f) {
            o.addProperty("custom2", a.custom2());
        }
        if (a.facing() != AnchorBinding.Facing.NONE) {
            o.addProperty("facing", a.facing().id());
            o.addProperty("yaw", a.yaw());
            o.addProperty("pitch", a.pitch());
        }
        if (a.spin() != 0f) {
            o.addProperty("spin", a.spin());
        }
        return o;
    }

    /**
     * 逐项容错地读一条绑定。
     *
     * <p>每个字段都单独判 {@code has}，缺哪个用哪个的默认值——工程文件是鼓励作者手改的，
     * 少写一个键不该让整条绑定读不出来。枚举值同理：{@code byId} 认不出来时回落到默认项。
     */
    private static AnchorBinding readAnchor(JsonObject o) {
        AnchorBinding a = new AnchorBinding(
                o.has("name") ? o.get("name").getAsString() : "Anchor");
        if (o.has("id")) {
            a.restoreId(o.get("id").getAsString());
        }
        if (o.has("enabled")) {
            a.setEnabled(o.get("enabled").getAsBoolean());
        }
        // 触发器<b>必须最先读</b>：切换「常驻 ↔ 事件」时它会连带铺一套配套默认值
        // （时长、钉住/跟随、缓动，必要时还有来源）。放在后面读的话，那套默认值会盖掉
        // 文件里存的真值——存进去是什么、读出来就得是什么，往返必须无损。
        if (o.has("trigger")) {
            a.setTrigger(AnchorBinding.Trigger.byId(o.get("trigger").getAsString()));
        }
        if (o.has("source")) {
            a.setSource(AnchorBinding.Source.byId(o.get("source").getAsString()));
        }
        if (o.has("selector")) {
            a.setSelector(o.get("selector").getAsString());
        }
        if (o.has("stick")) {
            a.setStick(o.get("stick").getAsBoolean());
        }
        if (o.has("duration")) {
            a.setDuration(o.get("duration").getAsFloat());
        }
        if (o.has("easing")) {
            a.setEasing(AnchorBinding.Easing.byId(o.get("easing").getAsString()));
        }
        if (o.has("worldRadius")) {
            a.setWorldRadius(o.get("worldRadius").getAsFloat());
        }
        if (o.has("strength")) {
            a.setStrength(o.get("strength").getAsFloat());
        }
        if (o.has("maxSlots")) {
            a.setMaxSlots(o.get("maxSlots").getAsInt());
        }
        if (o.has("occlusion")) {
            a.setOcclusion(o.get("occlusion").getAsBoolean());
        }
        if (o.has("yOffset")) {
            a.setYOffset(o.get("yOffset").getAsFloat());
        }
        if (o.has("emitterType")) {
            a.setEmitterType(o.get("emitterType").getAsInt());
        }
        if (o.has("custom1")) {
            a.setCustom1(o.get("custom1").getAsFloat());
        }
        if (o.has("custom2")) {
            a.setCustom2(o.get("custom2").getAsFloat());
        }
        if (o.has("facing")) {
            a.setFacing(AnchorBinding.Facing.byId(o.get("facing").getAsString()));
        }
        if (o.has("yaw")) {
            a.setYaw(o.get("yaw").getAsFloat());
        }
        if (o.has("pitch")) {
            a.setPitch(o.get("pitch").getAsFloat());
        }
        if (o.has("spin")) {
            a.setSpin(o.get("spin").getAsFloat());
        }
        // 缺这个键的是 format 5 之前的工程，此时字段保持构造时的默认值（打开上限）。
        // 那正是我们想要的：那些工程存下来的时候还没有上限，而它们大概率正撞在这个坑上
        if (o.has("maxScreenRadius")) {
            a.setMaxScreenRadius(o.get("maxScreenRadius").getAsFloat());
        }
        if (o.has("maxDistance")) {
            a.setMaxDistance(o.get("maxDistance").getAsFloat());
        }
        return a;
    }

    public static ShaderProject load(Path file) throws IOException {
        String text = Files.readString(file, StandardCharsets.UTF_8);
        JsonObject root = JsonParser.parseString(text).getAsJsonObject();
        return fromJson(root, displayName(file));
    }

    /** JSON → 工程。{@code fallbackName} 只在 JSON 里没记名字时使用。 */
    public static ShaderProject fromJson(JsonObject root, String fallbackName) {
        ShaderProject project = new ShaderProject(
                root.has("name") ? root.get("name").getAsString() : fallbackName);

        if (root.has("exportProfile")) {
            try {
                project.setExportProfile(GtProfile.valueOf(root.get("exportProfile").getAsString()));
            } catch (IllegalArgumentException ignored) {
                // 手改坏了或是旧枚举名，保持默认 profile，不该因此打不开工程
            }
        }

        if (root.has("viewport") && root.get("viewport").isJsonArray()) {
            JsonArray vp = root.getAsJsonArray("viewport");
            if (vp.size() >= 4) {
                project.viewport().set(vp.get(0).getAsFloat(), vp.get(1).getAsFloat(),
                        vp.get(2).getAsFloat(), vp.get(3).getAsFloat());
            }
        }

        if (root.has("layers") && root.get("layers").isJsonArray()) {
            for (JsonElement el : root.getAsJsonArray("layers")) {
                if (el.isJsonObject()) {
                    JsonObject lo = el.getAsJsonObject();
                    ShaderLayer layer = readLayer(lo);
                    // 只迁移没有层级取景框的旧数据。全屏也是明确的设置，
                    // 不能拿 isFullScreen() 判断是否缺字段，否则重开工程时会被旧框裁掉。
                    if (!lo.has("viewport")) {
                        inheritViewport(layer, project);
                    }
                    project.addLayer(layer);
                }
            }
        } else if (root.has("source")) {
            // format 1 的单层工程：直接当成唯一一层读进来，老工程不至于打不开
            ShaderLayer layer = new ShaderLayer("Layer 1", root.get("source").getAsString());
            applyValues(layer, root.has("values") ? root.getAsJsonObject("values") : null);
            inheritViewport(layer, project);
            project.addLayer(layer);
        }

        if (root.has("anchors") && root.get("anchors").isJsonArray()) {
            for (JsonElement el : root.getAsJsonArray("anchors")) {
                if (el.isJsonObject()) {
                    project.addAnchor(readAnchor(el.getAsJsonObject()));
                }
            }
        }

        // 零层是合法状态（「没有任何效果」），不再自动补一层——
        // 用户明确清空过的工程，再打开时不该又冒出一层
        project.setSelectedIndex(0);
        project.setSelectedAnchorIndex(0);
        return project;
    }

    private static void inheritViewport(ShaderLayer layer, ShaderProject project) {
        layer.viewport().set(project.viewport().x0(), project.viewport().y0(),
                project.viewport().x1(), project.viewport().y1());
    }

    private static ShaderLayer readLayer(JsonObject lo) {
        ShaderLayer layer = new ShaderLayer(
                lo.has("name") ? lo.get("name").getAsString() : "Layer",
                lo.has("source") ? lo.get("source").getAsString() : "");
        if (lo.has("enabled")) {
            layer.setEnabled(lo.get("enabled").getAsBoolean());
        }
        if (lo.has("strength")) {
            layer.setStrength(lo.get("strength").getAsFloat());
        }
        if (lo.has("blend")) {
            layer.setBlendMode(BlendMode.byId(lo.get("blend").getAsString()));
        }
        if (lo.has("kind")) {
            layer.setKindId(lo.get("kind").getAsString());
        }
        if (lo.has("bilinear")) {
            layer.setBilinear(lo.get("bilinear").getAsBoolean());
        }
        if (lo.has("viewport") && lo.get("viewport").isJsonArray()) {
            JsonArray lvp = lo.getAsJsonArray("viewport");
            if (lvp.size() >= 4) {
                layer.viewport().set(lvp.get(0).getAsFloat(), lvp.get(1).getAsFloat(),
                        lvp.get(2).getAsFloat(), lvp.get(3).getAsFloat());
            }
        }
        applyValues(layer, lo.has("values") ? lo.getAsJsonObject("values") : null);
        applyAnchorRefs(layer, lo.has("anchorRefs") ? lo.getAsJsonObject("anchorRefs") : null);
        return layer;
    }

    /**
     * 把存下来的锚点引用装回参数。
     *
     * <p><b>要在 {@link #applyValues} 之后调用</b>：装引用只是记下「指向哪一条」，
     * 真正的槽位号由 {@code ShaderProject.resolveAnchorRefs} 在编译前算。而在那之前，
     * 参数里得先有 values 存下来的那个旧值兜底——工程刚读出来还没编译过时，
     * 界面显示的就是它。
     */
    private static void applyAnchorRefs(ShaderLayer layer, JsonObject refs) {
        if (refs == null) {
            return;
        }
        for (Map.Entry<String, JsonElement> e : refs.entrySet()) {
            if (!e.getValue().isJsonPrimitive()) {
                continue;
            }
            for (ShaderParam p : layer.params()) {
                if (p.name().equals(e.getKey())) {
                    p.setAnchorRef(e.getValue().getAsString());
                }
            }
        }
    }

    private static void applyValues(ShaderLayer layer, JsonObject values) {
        if (values == null) {
            return;
        }
        for (Map.Entry<String, JsonElement> e : values.entrySet()) {
            if (!e.getValue().isJsonArray()) {
                continue;
            }
            for (ShaderParam p : layer.params()) {
                if (!p.name().equals(e.getKey())) {
                    continue;
                }
                JsonArray arr = e.getValue().getAsJsonArray();
                // 参数定义来自当前源码：旧文件可能只存了 RGB，而源码已升级成 RGBA。
                // 逐分量回填，缺少或损坏的分量保持默认值，尤其不能把新增的 alpha 清成 0。
                for (int i = 0; i < Math.min(p.type().components(), arr.size()); i++) {
                    JsonElement component = arr.get(i);
                    if (!component.isJsonPrimitive() || component.getAsJsonPrimitive().isBoolean()) {
                        continue;
                    }
                    try {
                        float value = component.getAsFloat();
                        if (Float.isFinite(value)) {
                            p.set(i, value);
                        }
                    } catch (NumberFormatException ignored) {
                        // 一项手改坏了不该阻止其余参数恢复。
                    }
                }
            }
        }
    }

    public static List<Path> listProjects() {
        List<Path> out = new ArrayList<>();
        Path dir = Workspace.projectsDir();
        if (!Files.isDirectory(dir)) {
            return out;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*" + EXT)) {
            for (Path p : stream) {
                out.add(p);
            }
        } catch (IOException ignored) {
            // 列不出来就当作没有工程，UI 上是空列表而不是崩溃
        }
        out.sort((a, b) -> a.getFileName().toString().compareToIgnoreCase(b.getFileName().toString()));
        return out;
    }

    public static String displayName(Path file) {
        String n = file.getFileName().toString();
        return n.endsWith(EXT) ? n.substring(0, n.length() - EXT.length()) : n;
    }
}
