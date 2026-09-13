package mc.GTedd.cn.gtshaders;

import com.google.gson.JsonParser;
import mc.GTedd.cn.gtshaders.minimap.PortableMinimapExporter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import javax.imageio.ImageIO;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.zip.ZipFile;
import static org.junit.jupiter.api.Assertions.*;

class PortableMinimapTest {
    @TempDir Path dir;
    @Test void preservesExactTerrainPixelsAxesAndSignedWorldOrigin() throws Exception {
        int[] pixels = {0xffa12345, 0xff18232d, 0xff4567ab, 0xffabcdef};
        var atlas = new PortableMinimapExporter.Atlas(2, 2, -29_999_000, 29_999_000, 4, "minecraft:overworld", false, pixels);
        pixels[0] = 0;
        Path out = PortableMinimapExporter.export(dir, atlas);
        try (var zip = new ZipFile(out.resolve("GTMinimap-Resources.zip").toFile())) {
            var image = ImageIO.read(zip.getInputStream(zip.getEntry("assets/gtminimap/textures/effect/map/terrain.png")));
            assertEquals(0xffa12345, image.getRGB(0, 0));
            assertEquals(0xff4567ab, image.getRGB(0, 1));
            assertEquals(0xffabcdef, image.getRGB(1, 1));
            var meta = JsonParser.parseString(read(zip, "gtminimap-atlas.json")).getAsJsonObject();
            assertEquals("snapshot", meta.get("terrainMode").getAsString());
            assertEquals(-29_999_000, meta.getAsJsonArray("origin").get(0).getAsInt());
            for (int radius : new int[]{32, 64, 128, 256}) {
                var chain = JsonParser.parseString(read(zip, "assets/gtminimap/post_effect/map_" + radius + ".json")).getAsJsonObject();
                assertEquals(2, chain.getAsJsonArray("passes").size());
                assertEquals("minecraft:main", chain.getAsJsonArray("passes").get(1).getAsJsonObject().get("output").getAsString());
            }
        }
    }
    @Test void helpMessageFollowsTheClientLanguageThroughThePacksOwnLangFiles() throws Exception {
        // 原版客户端没有 GTShaders，聊天栏帮助只能靠资源包自带的语言文件按玩家的游戏语言显示
        Path out = PortableMinimapExporter.export(dir, new PortableMinimapExporter.Atlas(1, 1, 0, 0, 1, "minecraft:overworld", false, new int[]{-1}));
        String key;
        try (var zip = new ZipFile(out.resolve("GTMinimap-Data.zip").toFile())) {
            String help = read(zip, "data/gtminimap/function/help.mcfunction");
            var json = JsonParser.parseString(help.substring("tellraw @s ".length())).getAsJsonObject();
            key = json.get("translate").getAsString();
            assertFalse(json.get("fallback").getAsString().isBlank(), "没装资源包时也得有一句能看的");
        }
        try (var zip = new ZipFile(out.resolve("GTMinimap-Resources.zip").toFile())) {
            for (String lang : new String[]{"en_us", "zh_cn"}) {
                var map = JsonParser.parseString(read(zip, "assets/gtminimap/lang/" + lang + ".json")).getAsJsonObject();
                assertFalse(map.get(key).getAsString().isBlank(), lang + " 缺少帮助文案");
            }
        }
    }
    @Test void vanillaFunctionsHaveSingleLineJsonAndNeverClearOtherEffects() throws Exception {
        Path out = PortableMinimapExporter.export(dir, new PortableMinimapExporter.Atlas(1, 1, 0, 0, 1, "minecraft:the_nether", true, new int[]{-1}));
        try (var zip = new ZipFile(out.resolve("GTMinimap-Data.zip").toFile())) {
            String help = read(zip, "data/gtminimap/function/help.mcfunction");
            assertEquals(1, help.lines().count());
            assertDoesNotThrow(() -> JsonParser.parseString(help.substring("tellraw @s ".length())));
            String sync = read(zip, "data/gtminimap/function/sync.mcfunction");
            assertTrue(sync.contains("execute if dimension minecraft:the_nether"));
            for (var entry : zip.stream().filter(e -> e.getName().endsWith(".mcfunction")).toList())
                assertFalse(read(zip, entry.getName()).contains("posteffect clear"));
            String uninstall = read(zip, "data/gtminimap/function/uninstall.mcfunction");
            assertTrue(uninstall.contains("function gtminimap:remove"));
            assertTrue(uninstall.startsWith("data modify storage gtminimap:state stopped set value 1b"));
            assertTrue(read(zip, "data/gtminimap/function/tick.mcfunction").startsWith("execute if data storage gtminimap:state {stopped:1b} run return 0"));
        }
    }
    @Test void rejectsInvalidAtlasInsteadOfWritingCommandInjection() {
        assertThrows(IllegalArgumentException.class, () -> new PortableMinimapExporter.Atlas(1, 1, 0, 0, 1, "minecraft:overworld\nkill @a", false, new int[]{-1}));
        assertThrows(IllegalArgumentException.class, () -> new PortableMinimapExporter.Atlas(2, 1, 30_000_000, 0, 1, "minecraft:overworld", false, new int[]{-1, -1}));
    }
    @Test void classicExportBindsRealPixelsDimensionAndSpriteProtocol() throws Exception {
        var atlas = new PortableMinimapExporter.Atlas(2,1,-120,45,2,"minecraft:the_nether",true,new int[]{0xff00aa33,0xff2255bb});
        Path out = PortableMinimapExporter.export(dir,atlas);
        try (var zip = new ZipFile(out.resolve(PortableMinimapExporter.worldZipName()).toFile())) {
            assertNotNull(zip.getEntry("pack.mcmeta"));
            assertNotNull(zip.getEntry("data/minecraft/tags/function/tick.json"));
            var image=ImageIO.read(zip.getInputStream(zip.getEntry("assets/gtminimap/textures/effect/terrain.png")));
            assertEquals(2,image.getWidth()); assertEquals(0xff00aa33,image.getRGB(0,0));
            var view=JsonParser.parseString(read(zip,"assets/gtminimap/post_effect/view.json")).getAsJsonObject();
            var draw=view.getAsJsonArray("passes").get(3).getAsJsonObject();
            assertEquals(-120,draw.getAsJsonObject("uniforms").getAsJsonArray("AtlasConfig").get(0).getAsJsonObject().get("value").getAsInt());
            assertTrue(read(zip,"data/gtminimap/function/frame.mcfunction").contains("unless dimension minecraft:the_nether if score @s gtm_atlas"));
            assertTrue(read(zip,"assets/gtminimap/shaders/post/view.fsh").contains("#define CLASSIC_MAP 1"));
            assertFalse(read(zip,"data/gtminimap/function/menu.mcfunction").contains("随机地图种子"));
            var icons=JsonParser.parseString(read(zip,"图标协议.json")).getAsJsonObject().getAsJsonObject("icons");
            assertTrue(icons.has("minecraft:pig")); assertTrue(icons.has("minecraft:zombie"));
            assertNotNull(zip.getEntry("assets/gtminimap/textures/effect/icons.png"));
        }
    }
    private static String read(ZipFile zip, String name) throws Exception {
        try (var stream = zip.getInputStream(zip.getEntry(name))) { return new String(stream.readAllBytes(), StandardCharsets.UTF_8); }
    }
}
