package mc.GTedd.cn.gtshaders.minimap;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParser;
import mc.GTedd.cn.gtshaders.core.TargetVersion;
import mc.GTedd.cn.gtshaders.i18n.GtLang;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import java.util.zip.ZipInputStream;

/** Vanilla-only playback. Minecraft is used by the caller to AUTHOR the atlas, never to render it.
 * The wire/texture contract deliberately describes a finite snapshot, not live chunk streaming.
 */
public final class PortableMinimapExporter {
    /** 聊天栏帮助的翻译键，放在导出的资源包 {@code assets/gtminimap/lang/} 里。 */
    static final String HELP_KEY = "gtminimap.help";
    static final String HELP_EN = "GTMinimap | /trigger gtm set 1 show or hide | set 2 zoom | set 3 help. "
            + "North-up real terrain snapshot; it does not update as you mine.";
    static final String HELP_ZH = "原版小地图｜/trigger gtm set 1 显示或隐藏｜set 2 缩放｜set 3 帮助。当前为北向真实地形快照，不会随挖掘更新。";

    /** 推荐安装的那个包的文件名。跟着导出时的游戏语言走，英文玩家拿到的不该是一个中文文件名。 */
    public static String worldZipName() {
        return GtLang.isChinese(GtLang.currentLang()) ? "GTMinimap-你的世界.zip" : "GTMinimap-YourWorld.zip";
    }

    private static final Gson JSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    public static final String NAMESPACE = "gtminimap";
    public record Atlas(int width, int height, int originX, int originZ, int blocksPerPixel,
                        String dimension, boolean slice, int[] argb) {
        public Atlas {
            if (width < 1 || height < 1 || width > 4096 || height > 4096 || blocksPerPixel < 1 || blocksPerPixel > 16
                    || argb.length != width * height || dimension == null
                    || !dimension.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) throw new IllegalArgumentException("Invalid atlas");
            if (Math.abs((long) originX) > 30_000_000 || Math.abs((long) originZ) > 30_000_000
                    || (long) originX + (long) width * blocksPerPixel > 30_000_000
                    || (long) originZ + (long) height * blocksPerPixel > 30_000_000)
                throw new IllegalArgumentException("Atlas outside world bounds");
            argb = argb.clone();
        }
        @Override public int[] argb() { return argb.clone(); }
    }
    private PortableMinimapExporter() {}

    /** Authoring CLI for a future server/plugin baker: PNG, atlas.json, output-directory. */
    public static void main(String[] args) throws IOException {
        if (args.length != 3) throw new IllegalArgumentException("Usage: PortableMinimapExporter terrain.png atlas.json output-directory");
        BufferedImage image = ImageIO.read(Path.of(args[0]).toFile());
        if (image == null) throw new IOException("Not a supported image");
        var meta = JsonParser.parseString(Files.readString(Path.of(args[1]), StandardCharsets.UTF_8)).getAsJsonObject();
        var origin = meta.getAsJsonArray("origin");
        Atlas atlas = new Atlas(image.getWidth(), image.getHeight(), origin.get(0).getAsInt(), origin.get(1).getAsInt(),
                meta.get("blocksPerPixel").getAsInt(), meta.get("dimension").getAsString(),
                meta.has("slice") && meta.get("slice").getAsBoolean(), image.getRGB(0, 0, image.getWidth(), image.getHeight(), null, 0, image.getWidth()));
        System.out.println(export(Path.of(args[2]), atlas).toAbsolutePath());
    }

    public static Path export(Path parent, Atlas atlas) throws IOException {
        Files.createDirectories(parent);
        Path destination = Files.createTempDirectory(parent, "vanilla-");
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("schema", "gtminimap.atlas.v1");
        manifest.put("minecraft", TargetVersion.minecraft());
        manifest.put("terrainMode", "snapshot");
        manifest.put("dimension", atlas.dimension());
        manifest.put("slice", atlas.slice());
        manifest.put("origin", List.of(atlas.originX(), atlas.originZ()));
        manifest.put("size", List.of(atlas.width(), atlas.height()));
        manifest.put("blocksPerPixel", atlas.blocksPerPixel());
        manifest.put("pixelAxes", "PNG column +X, row +Z; origin is northwest block corner");
        manifest.put("unknownArgb", "ff18232d");
        manifest.put("camera", "vanilla Globals.CameraBlockPos + CameraOffset, north-up");
        manifest.put("features", List.of("real-terrain-snapshot", "frame-rate-camera-translation", "north-up", "zoom", "toggle", "dimension-gating"));
        manifest.put("notImplemented", List.of("live-terrain-updates", "heading-up", "entities", "waypoints", "custom-keys", "clickable-map", "F1-detection"));

        try (var zip = new ZipOutputStream(Files.newOutputStream(destination.resolve("GTMinimap-Resources.zip")))) {
            json(zip, "pack.mcmeta", Map.of("pack", Map.of("description", "GTMinimap · terrain snapshot / 原版小地图 · 真实地形快照资源包", "min_format", TargetVersion.resourcePackFormat(), "max_format", TargetVersion.resourcePackFormat())));
            json(zip, "gtminimap-atlas.json", manifest);
            BufferedImage image = new BufferedImage(atlas.width(), atlas.height(), BufferedImage.TYPE_INT_ARGB);
            image.setRGB(0, 0, atlas.width(), atlas.height(), atlas.argb(), 0, atlas.width());
            var png = new ByteArrayOutputStream();
            if (!ImageIO.write(image, "png", png)) throw new IOException("PNG encoder unavailable");
            entry(zip, "assets/gtminimap/textures/effect/map/terrain.png", png.toByteArray());
            try (var shader = PortableMinimapExporter.class.getResourceAsStream("/assets/gtshaders/portable/minimap.fsh")) {
                if (shader == null) throw new IOException("Missing portable shader template");
                entry(zip, "assets/gtminimap/shaders/post/minimap.fsh", shader.readAllBytes());
            }
            for (int radius : MinimapStore.RADII)
                json(zip, "assets/gtminimap/post_effect/map_" + radius + ".json", effect(atlas, radius));
            json(zip, "assets/gtminimap/lang/en_us.json", Map.of(HELP_KEY, HELP_EN));
            json(zip, "assets/gtminimap/lang/zh_cn.json", Map.of(HELP_KEY, HELP_ZH));
        }
        try (var zip = new ZipOutputStream(Files.newOutputStream(destination.resolve("GTMinimap-Data.zip")))) {
            json(zip, "pack.mcmeta", Map.of("pack", Map.of("description", "GTMinimap · snapshot controls / 原版小地图 · 快照控制数据包", "min_format", TargetVersion.dataPackFormat(), "max_format", TargetVersion.dataPackFormat())));
            json(zip, "data/minecraft/tags/function/load.json", Map.of("values", List.of("gtminimap:load")));
            json(zip, "data/minecraft/tags/function/tick.json", Map.of("values", List.of("gtminimap:tick")));
            function(zip, "load", """
                    data remove storage gtminimap:state stopped
                    scoreboard objectives add gtm trigger
                    scoreboard objectives add gtm_zoom dummy
                    scoreboard objectives add gtm_on dummy
                    scoreboard objectives add gtm_seen dummy
                    execute as @a run function gtminimap:remove
                    scoreboard players reset @a gtm_seen
                    """);
            function(zip, "tick", """
                    execute if data storage gtminimap:state {stopped:1b} run return 0
                    execute as @a unless score @s gtm_seen matches 1 run function gtminimap:join
                    execute as @a[scores={gtm=1}] run function gtminimap:toggle
                    execute as @a[scores={gtm=2}] run function gtminimap:zoom
                    execute as @a[scores={gtm=3}] run function gtminimap:help
                    scoreboard players set @a gtm 0
                    scoreboard players enable @a gtm
                    execute as @a at @s run function gtminimap:sync
                    """);
            function(zip, "join", """
                    execute unless score @s gtm_on matches 0..1 run scoreboard players set @s gtm_on 1
                    execute unless score @s gtm_zoom matches 0..3 run scoreboard players set @s gtm_zoom 1
                    scoreboard players set @s gtm_seen 1
                    function gtminimap:help
                    """);
            function(zip, "toggle", """
                    scoreboard players add @s gtm_on 1
                    execute if score @s gtm_on matches 2.. run scoreboard players set @s gtm_on 0
                    """);
            function(zip, "zoom", """
                    scoreboard players add @s gtm_zoom 1
                    execute if score @s gtm_zoom matches 4.. run scoreboard players set @s gtm_zoom 0
                    """);
            // translate + fallback：客户端装了资源包就按它自己的游戏语言显示，没装也有英文可看
            function(zip, "help", "tellraw @s " + new Gson().toJson(Map.of("translate", HELP_KEY,
                    "fallback", HELP_EN, "color", "aqua")) + "\n");
            StringBuilder remove = new StringBuilder(), sync = new StringBuilder();
            for (int i = 0; i < MinimapStore.RADII.length; i++) {
                String id = "gtminimap:map_" + MinimapStore.RADII[i];
                remove.append("posteffect remove @s ").append(id).append('\n');
                sync.append("execute unless dimension ").append(atlas.dimension()).append(" run posteffect remove @s ").append(id).append('\n');
                sync.append("execute if score @s gtm_on matches 0 run posteffect remove @s ").append(id).append('\n');
                sync.append("execute unless score @s gtm_zoom matches ").append(i).append(" run posteffect remove @s ").append(id).append('\n');
            }
            for (int i = 0; i < MinimapStore.RADII.length; i++)
                sync.append("execute if dimension ").append(atlas.dimension()).append(" if score @s gtm_on matches 1 if score @s gtm_zoom matches ").append(i)
                        .append(" run posteffect add @s gtminimap:map_").append(MinimapStore.RADII[i]).append('\n');
            function(zip, "remove", remove.toString());
            function(zip, "sync", sync.toString());
            function(zip, "uninstall", "data modify storage gtminimap:state stopped set value 1b\nexecute as @a run function gtminimap:remove\nscoreboard objectives remove gtm\nscoreboard objectives remove gtm_on\nscoreboard objectives remove gtm_zoom\nscoreboard objectives remove gtm_seen\n");
        }
        Files.writeString(destination.resolve("atlas.json"), JSON.toJson(manifest), StandardCharsets.UTF_8);
        Files.writeString(destination.resolve("README.txt"), """
                Minecraft Java %1$s only. Players do not need any client mod.
                1. Put GTMinimap-Resources.zip into the instance's resourcepacks folder and enable it.
                2. Put GTMinimap-Data.zip into the same world's datapacks folder and run /reload.
                3. /trigger gtm set 1 shows or hides the map; /trigger gtm set 2 cycles the zoom.
                Installing and reloading need operator permission; the trigger commands do not.
                Before uninstalling, run /function gtminimap:uninstall, then disable or remove the data pack.
                Uninstalling only removes this pack's effects, not effects from other packs.

                This is a limited terrain snapshot of the sampled area of the current world; unknown areas stay dark.
                Mining and building do not update the exported image; a Nether export keeps the height layer it was taken at.
                The top-left corner of the PNG is the origin; columns go east and rows go south. See atlas.json for the range.
                The vanilla per-frame camera position is used, subtracting integers before converting to float to keep precision.
                Enable only one exported pair at a time; they share one namespace.
                This snapshot pack has no entities, waypoints, rotation, custom keys or F1 detection.
                For the full video-feature recreation, run the project's build_minimap_reproduction.py
                with this terrain PNG and atlas.json to reuse the pig tracking, arrow waypoints and ray-traced view.

                ----------------------------------------------------------------

                仅适用于 Minecraft Java %1$s，播放端不需要客户端模组。
                1. 将 GTMinimap-Resources.zip 放入当前实例 resourcepacks 目录并启用。
                2. 将 GTMinimap-Data.zip 放入同一个世界的 datapacks 目录，执行 /reload。
                3. /trigger gtm set 1 显示或隐藏；/trigger gtm set 2 循环缩放。
                安装和重载需要管理员权限，普通玩家的 trigger 操作不需要 OP。
                卸载前运行 /function gtminimap:uninstall，然后停用或移除数据包。
                卸载仅移除本包效果，不会清空其他包的效果。

                这是当前世界已采样区域的有限地形快照。未知区域保持深色。
                挖掘和建造不会更新导出的图像；下界导出的是当时的高度层。
                PNG 左上角是原点；列向东、行向南，具体范围见 atlas.json。
                使用原版逐帧相机位置，先计算整数差再转换浮点，保持平移精度。
                每次只启用一对导出包，它们共用同一个命名空间。
                本快照包不包含实体、路点、旋转、自定义按键和 F1 检测。
                如需视频功能复原版，请使用项目的 build_minimap_reproduction.py，
                并传入本底图 PNG 与 atlas.json，以复用猪群、射箭路点和光追扩展。
                """.formatted(TargetVersion.minecraft()), StandardCharsets.UTF_8);
        exportClassic(destination, atlas);
        return destination;
    }

    /** Shared shader/data template plus this world's pixels; no Python required by the author. */
    private static void exportClassic(Path destination, Atlas atlas) throws IOException {
        try (var source = PortableMinimapExporter.class.getResourceAsStream("/assets/gtshaders/minimap/classic-template.zip")) {
            if (source == null) throw new IOException("Missing the real-terrain vanilla template; rebuild GTShaders");
            BufferedImage image = new BufferedImage(atlas.width(), atlas.height(), BufferedImage.TYPE_INT_ARGB);
            image.setRGB(0,0,atlas.width(),atlas.height(),atlas.argb(),0,atlas.width());
            var png = new ByteArrayOutputStream();
            if (!ImageIO.write(image,"png",png)) throw new IOException("PNG encoding failed");
            Files.write(destination.resolve("terrain.png"), png.toByteArray());
            String zipName = worldZipName();
            String instructions = """
                    Surface minimap · real terrain and mob faces (%1$s)

                    Recommended: copy %2$s into both the instance's resourcepacks/ folder
                    and the world's datapacks/ folder, enable the resource pack, then /reload. No unzipping; players need no mod.
                    Do not enable it together with the old GTMinimap-Data.zip, GTMinimap-Resources.zip or the procedural-terrain version.
                    The other two ZIPs in this folder are kept only as the legacy north-up snapshot export; they are not recommended.

                    /trigger gtm set 3 opens the button menu (Simplified Chinese only).
                    set 1 show/hide; set 2 zoom; set 5 north-up/heading-up; set 7 clear waypoints; set 9 repair; set 11 pig faces.
                    Tracks the nearest 12 pigs. An arrow landing on a block sets your waypoint, with horizontal distance and edge direction.
                    The map only shows the real terrain captured in this export; no seed-based terrain is substituted, other dimensions show no map.
                    Real terrain is a limited snapshot: mining and building do not refresh it, and areas outside the range stay blank.
                    This is not the full GTShaders client settings screen; the M key, clicking the map, multiple waypoints and all mob faces need the mod.
                    Plugin developers can reuse the icon protocol, textures and bit channels in the pack; live terrain transfer needs its own implementation.

                    ----------------------------------------------------------------

                    地表小地图 · 真实底图与生物头像（%1$s）

                    推荐安装：将 %2$s 同时复制到当前实例 resourcepacks/
                    和该世界 datapacks/，启用资源包后 /reload。无需解压，玩家无需模组。
                    不要与旧 GTMinimap-Data.zip、GTMinimap-Resources.zip 或程序地形版同时启用。
                    目录中另外两个 ZIP 仅保留为旧版北向快照兼容导出，不是推荐版本。

                    /trigger gtm set 3 打开简体中文按钮菜单。
                    set 1 显隐；set 2 缩放；set 5 北向／朝向；set 7 清路点；set 9 修复；set 11 猪头像。
                    跟踪最近 12 头猪。射箭落到方块设置自己的路点，显示水平距离与边缘方向。
                    地图仅显示本次导出的真实地形；不使用程序种子替代，其他维度显示未载入底图。
                    真实地形是有限快照，挖掘和建造不会自动刷新；超出范围留空。
                    这不是 GTShaders 的完整客户端设置界面，M 键、点击地图、多路点及全部实体头像仍由模组提供。
                    开发插件时可复用包内图标协议、纹理和位通道，实时地形传输需要另外实现。
                    """.formatted(TargetVersion.minecraft(), zipName);
            try (var input = new ZipInputStream(source);
                 var output = new ZipOutputStream(Files.newOutputStream(destination.resolve(zipName)))) {
                for (ZipEntry file; (file=input.getNextEntry())!=null;) {
                    String name=file.getName(); byte[] bytes=input.readAllBytes();
                    if (name.equals("assets/gtminimap/textures/effect/terrain.png")) bytes=png.toByteArray();
                    else if (name.equals("assets/gtminimap/post_effect/view.json")) {
                        var config=JsonParser.parseString(new String(bytes,StandardCharsets.UTF_8)).getAsJsonObject();
                        for (var pass:config.getAsJsonArray("passes")) {
                            var object=pass.getAsJsonObject();
                            for (var in:object.getAsJsonArray("inputs")) {
                                var texture=in.getAsJsonObject();
                                if (texture.has("sampler_name") && texture.get("sampler_name").getAsString().equals("Atlas")) {
                                    texture.addProperty("width",atlas.width()); texture.addProperty("height",atlas.height());
                                }
                            }
                            if (object.has("uniforms") && object.getAsJsonObject("uniforms").has("AtlasConfig"))
                                for (var uniform:object.getAsJsonObject("uniforms").getAsJsonArray("AtlasConfig")) {
                                    var u=uniform.getAsJsonObject();
                                    switch (u.get("name").getAsString()) {
                                        case "OriginX" -> u.addProperty("value",atlas.originX());
                                        case "OriginZ" -> u.addProperty("value",atlas.originZ());
                                        case "Step" -> u.addProperty("value",atlas.blocksPerPixel());
                                        default -> { }
                                    }
                                }
                        }
                        bytes=JSON.toJson(config).getBytes(StandardCharsets.UTF_8);
                    } else if (name.equals("data/gtminimap/function/frame.mcfunction"))
                        bytes=new String(bytes,StandardCharsets.UTF_8).replace("unless dimension minecraft:overworld if score @s gtm_atlas",
                                "unless dimension "+atlas.dimension()+" if score @s gtm_atlas").getBytes(StandardCharsets.UTF_8);
                    else if (name.equals("安装与使用.txt")) bytes=instructions.getBytes(StandardCharsets.UTF_8);
                    else if (name.equals("pack.mcmeta")) bytes=JSON.toJson(Map.of("pack",Map.of("description","GTMinimap · real terrain, install in both folders / 地表小地图 · 真实底图与生物头像（两处安装）",
                            "min_format",TargetVersion.resourcePackFormat(),"max_format",TargetVersion.dataPackFormat()))).getBytes(StandardCharsets.UTF_8);
                    entry(output,name,bytes);
                }
                entry(output,"atlas.json",Files.readAllBytes(destination.resolve("atlas.json")));
            }
            Files.writeString(destination.resolve("README.txt"),instructions,StandardCharsets.UTF_8);
        }
    }

    private static Object effect(Atlas atlas, int radius) {
        var config = List.of(uniform("OriginX", "int", atlas.originX()), uniform("OriginZ", "int", atlas.originZ()),
                uniform("AtlasSize", "vec2", List.of(atlas.width(), atlas.height())),
                uniform("BlocksPerPixel", "float", atlas.blocksPerPixel()), uniform("Radius", "float", radius));
        var draw = Map.of("vertex_shader", "minecraft:core/screenquad", "fragment_shader", "gtminimap:post/minimap",
                "inputs", List.of(Map.of("sampler_name", "In", "target", "minecraft:main"),
                        Map.of("sampler_name", "Atlas", "location", "gtminimap:map/terrain", "width", atlas.width(), "height", atlas.height(), "bilinear", false)),
                "output", "swap", "uniforms", Map.of("AtlasConfig", config));
        var blit = Map.of("vertex_shader", "minecraft:core/screenquad", "fragment_shader", "minecraft:post/blit",
                "inputs", List.of(Map.of("sampler_name", "In", "target", "swap")), "output", "minecraft:main",
                "uniforms", Map.of("BlitConfig", List.of(uniform("ColorModulate", "vec4", List.of(1, 1, 1, 1)))));
        return Map.of("targets", Map.of("swap", Map.of()), "passes", List.of(draw, blit));
    }
    private static Object uniform(String name, String type, Object value) { return Map.of("name", name, "type", type, "value", value); }
    private static void function(ZipOutputStream zip, String name, String value) throws IOException {
        // A Minecraft command must occupy exactly one physical line, including JSON arguments.
        entry(zip, "data/gtminimap/function/" + name + ".mcfunction", value.getBytes(StandardCharsets.UTF_8));
    }
    private static void json(ZipOutputStream zip, String name, Object value) throws IOException { entry(zip, name, JSON.toJson(value).getBytes(StandardCharsets.UTF_8)); }
    private static void entry(ZipOutputStream zip, String name, byte[] value) throws IOException {
        zip.putNextEntry(new ZipEntry(name)); zip.write(value); zip.closeEntry();
    }
}
