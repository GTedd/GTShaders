package mc.GTedd.cn.gtshaders.minimap;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.NativeImage;
import mc.GTedd.cn.gtshaders.GTShaders;
import mc.GTedd.cn.gtshaders.i18n.GtLang;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.BiomeColors;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.storage.LevelResource;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** All level reads and texture uploads happen on the client thread, within a tick budget. */
public final class MinimapRuntime {
    public static final Identifier TEXTURE = Identifier.fromNamespaceAndPath("gtshaders", "minimap/terrain");
    private static final Path ROOT = FabricLoader.getInstance().getConfigDir().resolve("gtshaders/minimap");
    private static final TerrainGrid GRID = new TerrainGrid();
    private static final BlockPos.MutableBlockPos POS = new BlockPos.MutableBlockPos();
    private static MinimapStore.Settings settings = new MinimapStore.Settings();
    private static MinimapStore.Waypoints waypoints = new MinimapStore.Waypoints();
    private static ClientLevel level;
    private static DynamicTexture texture;
    private static Path worldFile;
    private static String worldKey = "";
    private static boolean settingsWritable = true, worldWritable = true, wasDead;
    private static int ticks, sliceBand = Integer.MIN_VALUE;
    private static KeyMapping open, toggle, waypoint, zoomIn, zoomOut;
    private static List<Entity> contacts = List.of();
    public static int selected = -1;
    private MinimapRuntime() {}

    public static void register(KeyMapping.Category category) {
        try { settings = MinimapStore.read(ROOT.resolve("settings.json"), MinimapStore.Settings.class, settings); }
        catch (IOException e) { settingsWritable = false; GTShaders.LOGGER.warn("Cannot read minimap settings", e); }
        settings.validate();
        open = key("open", InputConstants.KEY_M, category);
        toggle = key("toggle", InputConstants.KEY_N, category);
        waypoint = key("waypoint", InputConstants.KEY_V, category);
        zoomIn = key("zoom_in", InputConstants.KEY_EQUALS, category);
        zoomOut = key("zoom_out", InputConstants.KEY_MINUS, category);
        ClientTickEvents.END_CLIENT_TICK.register(MinimapRuntime::tick);
        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("gtshaders", "minimap"), MinimapHud::renderHud);
    }

    private static KeyMapping key(String id, int code, KeyMapping.Category category) {
        return KeyMappingHelper.registerKeyMapping(new KeyMapping("key.gtshaders.minimap." + id,
                InputConstants.Type.KEYBOARD, code, category));
    }

    public static MinimapStore.Settings settings() { return settings; }
    public static TerrainGrid grid() { return GRID; }
    public static List<Entity> contacts() { return contacts; }
    public static List<MinimapStore.Waypoint> waypoints() { return List.copyOf(waypoints.points); }
    public static boolean ready() { return texture != null && level != null && GRID.step() > 0; }
    public static String dimension() { return level == null ? "" : level.dimension().identifier().toString(); }
    public static boolean slice() { return settings.slice || level != null && level.dimensionType().hasCeiling(); }
    /** 小地图文案与编辑器同一套语言文件，跟着游戏语言切换。 */
    public static String text(String key, Object... args) { return GtLang.get("gtshaders.minimap." + key, args); }
    /** 距离：{@code 128 米} / {@code 128 m}。 */
    public static String meters(long value) { return text("meters", value); }
    public static String keyHint() {
        return text("hud_hint", open.getTranslatedKeyMessage().getString(), waypoint.getTranslatedKeyMessage().getString());
    }

    public static void saveSettings() {
        if (!settingsWritable) { say("save_failed"); return; }
        try { MinimapStore.write(ROOT.resolve("settings.json"), settings); }
        catch (IOException e) { GTShaders.LOGGER.warn("Cannot save minimap settings", e); say("save_failed"); }
    }

    public static void exportPortable() {
        if (!ready()) return;
        try {
            Path path = PortableMinimapExporter.export(ROOT.resolve("exports"), new PortableMinimapExporter.Atlas(
                    TerrainGrid.SIZE, TerrainGrid.SIZE, GRID.originX() * GRID.step(), GRID.originZ() * GRID.step(),
                    GRID.step(), dimension(), slice(), GRID.colors()));
            var mc = Minecraft.getInstance();
            if (mc.player != null) mc.gui.chatListener().handleSystemMessage(Component.literal(text("exported", path.toAbsolutePath().toString())), false);
        } catch (IOException | IllegalArgumentException e) {
            GTShaders.LOGGER.warn("Cannot export portable minimap", e); say("save_failed");
        }
    }

    private static void saveWaypoints() {
        if (!worldWritable || worldFile == null) { say("save_failed"); return; }
        try { MinimapStore.write(worldFile, waypoints); }
        catch (IOException e) { GTShaders.LOGGER.warn("Cannot save minimap waypoints", e); say("save_failed"); }
    }

    public static boolean addWaypoint(String name, int x, int y, int z, boolean death) {
        if (level == null || worldFile == null) return false;
        if (death) waypoints.points.removeIf(p -> p.death() && p.dimension().equals(dimension()));
        if (waypoints.points.size() >= MinimapStore.MAX_WAYPOINTS) { say("limit"); return false; }
        String label = name.strip();
        if (label.isEmpty()) label = text("waypoint");
        if (label.length() > 40) label = label.substring(0, 40);
        var p = new MinimapStore.Waypoint(label, dimension(), x, y, z,
                death ? 0xffff6879 : 0xffffd77a, death);
        if (!p.valid()) return false;
        waypoints.points.add(p);
        selected = waypoints.points.size() - 1;
        saveWaypoints();
        return true;
    }

    public static void removeSelected() {
        if (selected >= 0 && selected < waypoints.points.size()) {
            waypoints.points.remove(selected); selected = -1; saveWaypoints();
        }
    }

    public static void nextWaypoint() {
        for (int i = 0; i < waypoints.points.size(); i++) {
            selected = (selected + 1) % waypoints.points.size();
            if (waypoints.points.get(selected).dimension().equals(dimension())) return;
        }
        selected = -1;
    }

    public static MinimapStore.Waypoint selectedWaypoint() {
        if (selected < 0 || selected >= waypoints.points.size()) return null;
        var p = waypoints.points.get(selected);
        return p.dimension().equals(dimension()) ? p : null;
    }

    public static void zoom(int direction) {
        settings.zoom = Math.clamp(settings.zoom + direction, 0, MinimapStore.RADII.length - 1);
        saveSettings();
    }

    private static void say(String id) {
        var mc = Minecraft.getInstance();
        if (mc.player != null && mc.gui.hud != null)
            mc.gui.hud.setOverlayMessage(Component.literal(text(id)), false);
    }

    private static void tick(Minecraft mc) {
        if (mc.level != level) {
            level = mc.level; GRID.clear(); contacts = List.of(); sliceBand = Integer.MIN_VALUE;
            if (texture != null) { mc.getTextureManager().release(TEXTURE); texture = null; }
            if (level == null) {
                worldKey = ""; worldFile = null; waypoints = new MinimapStore.Waypoints();
                selected = -1; wasDead = false;
            } else {
                String key = mc.getSingleplayerServer() != null
                        ? "local:" + mc.getSingleplayerServer().getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize()
                        : "server:" + (mc.getCurrentServer() == null ? "unknown" : mc.getCurrentServer().ip);
                if (!key.equals(worldKey)) {
                    worldKey = key; worldFile = MinimapStore.worldFile(ROOT, key); worldWritable = true;
                    waypoints = new MinimapStore.Waypoints(); selected = -1; wasDead = false;
                    try { waypoints = MinimapStore.read(worldFile, MinimapStore.Waypoints.class, waypoints); }
                    catch (IOException e) { worldWritable = false; GTShaders.LOGGER.warn("Cannot read minimap waypoints", e); }
                    waypoints.validate();
                }
            }
        }
        boolean inGame = level != null && mc.player != null && mc.gui.screen() == null;
        while (open.consumeClick()) if (inGame) { mc.gui.setScreen(new MinimapScreen()); inGame = false; }
        while (toggle.consumeClick()) if (inGame) { settings.enabled = !settings.enabled; saveSettings(); }
        while (waypoint.consumeClick()) if (inGame) {
            mc.gui.setScreen(new WaypointScreen(null, mc.player.blockPosition())); inGame = false;
        }
        while (zoomIn.consumeClick()) if (inGame) zoom(-1);
        while (zoomOut.consumeClick()) if (inGame) zoom(1);
        if (level == null || mc.player == null) return;
        boolean dead = !mc.player.isAlive();
        if (dead && !wasDead) {
            BlockPos p = mc.player.blockPosition(); addWaypoint(text("death"), p.getX(), p.getY(), p.getZ(), true);
        }
        wasDead = dead;
        if (!settings.enabled && !(mc.gui.screen() instanceof MinimapScreen)) return;

        int band = slice() ? Math.floorDiv(mc.player.getBlockY(), 8) : Integer.MIN_VALUE;
        if (band != sliceBand) { GRID.clear(); sliceBand = band; }
        int step = Math.max(1, settings.radius() / 64);
        boolean changed = GRID.center(mc.player.getX(), mc.player.getZ(), step);
        long deadline = System.nanoTime() + 2_000_000L;
        // One tick does at most 4096 columns / about 2 ms of reads. Start near the player.
        for (int samples = 0; samples < 4096; samples++) {
            int i = GRID.next();
            int x = (GRID.originX() + i % TerrainGrid.SIZE) * step;
            int z = (GRID.originZ() + i / TerrainGrid.SIZE) * step;
            int color = sample(x, z, band);
            if (GRID.colors()[i] != color) { GRID.colors()[i] = color; changed = true; }
            if ((samples & 31) == 31 && System.nanoTime() >= deadline) break;
        }
        if (texture == null) {
            texture = new DynamicTexture("GTShaders minimap", TerrainGrid.SIZE, TerrainGrid.SIZE, false);
            mc.getTextureManager().register(TEXTURE, texture); changed = true;
        }
        if (changed) {
            NativeImage image = texture.getPixels();
            if (image != null) {
                for (int i = 0; i < GRID.colors().length; i++)
                    image.setPixel(i % TerrainGrid.SIZE, i / TerrainGrid.SIZE, GRID.colors()[i]);
                texture.upload();
            }
        }
        if (++ticks % 5 == 0) {
            if (!settings.entities) { contacts = List.of(); return; }
            List<Entity> found = new ArrayList<>();
            double limit = settings.radius() * 1.5;
            for (Entity e : level.entitiesForRendering()) {
                if (e != mc.player && e instanceof LivingEntity && e.isAlive() && !e.isInvisible()
                        && e.distanceToSqr(mc.player) <= limit * limit
                        && Math.abs(e.getY() - mc.player.getY()) <= 32) found.add(e);
            }
            found.sort(Comparator.comparingDouble(e -> e.distanceToSqr(mc.player)));
            contacts = List.copyOf(found.subList(0, Math.min(32, found.size())));
        }
    }

    private static int sample(int x, int z, int band) {
        LevelChunk chunk = level.getChunkSource().getChunk(x >> 4, z >> 4, ChunkStatus.FULL, false);
        if (chunk == null) return TerrainGrid.UNKNOWN;
        int min = level.getMinY();
        int y = band == Integer.MIN_VALUE
                ? chunk.getHeight(Heightmap.Types.WORLD_SURFACE, x & 15, z & 15)
                : Math.min(level.getMaxY() - 1, band * 8 + 7);
        // In a ceiling dimension / slice mode find a floor below an air pocket.
        boolean airSeen = band == Integer.MIN_VALUE;
        BlockState state = null;
        MapColor color = MapColor.NONE;
        int scans = band == Integer.MIN_VALUE ? 64 : 96;
        for (int n = 0; y >= min && n < scans; n++, y--) {
            POS.set(x, y, z); state = chunk.getBlockState(POS);
            if (state.isAir()) { airSeen = true; continue; }
            color = state.getMapColor(level, POS);
            if (airSeen && color != MapColor.NONE) break;
            // Fluids describe a useful local surface even if the camera is submerged.
            if (!state.getFluidState().isEmpty()) { airSeen = true; break; }
            color = MapColor.NONE;
        }
        if (!airSeen || color == MapColor.NONE || state == null || y < min) return TerrainGrid.UNKNOWN;
        POS.set(x, y, z);
        int rgb = color.col;
        if (color == MapColor.WATER) rgb = BiomeColors.getAverageWaterColor(level, POS);
        else if (state.is(BlockTags.LEAVES)) rgb = BiomeColors.getAverageFoliageColor(level, POS);
        else if (color == MapColor.GRASS) rgb = BiomeColors.getAverageGrassColor(level, POS);
        double shade = 0.9;
        if (band == Integer.MIN_VALUE) {
            LevelChunk north = level.getChunkSource().getChunk(x >> 4, (z - GRID.step()) >> 4, ChunkStatus.FULL, false);
            if (north != null) {
                int h = north.getHeight(Heightmap.Types.WORLD_SURFACE, x & 15, (z - GRID.step()) & 15);
                shade += Math.clamp((y - h) * 0.055 / GRID.step(), -0.22, 0.2);
            }
        }
        if (color == MapColor.WATER) shade = 0.92;
        return MinimapMath.shade(rgb, shade);
    }
}
