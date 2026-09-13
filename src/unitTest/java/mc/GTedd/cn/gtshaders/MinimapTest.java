package mc.GTedd.cn.gtshaders;

import mc.GTedd.cn.gtshaders.minimap.MinimapMath;
import mc.GTedd.cn.gtshaders.minimap.MinimapStore;
import mc.GTedd.cn.gtshaders.minimap.TerrainGrid;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashSet;
import static org.junit.jupiter.api.Assertions.*;

class MinimapTest {
    @TempDir Path dir;

    @Test void headingUpAlwaysPointsForwardAndMapClicksInvertIt() {
        for (int yaw = -720; yaw <= 720; yaw += 15) {
            double angle = MinimapMath.rotation(yaw, true);
            double radians = Math.toRadians(yaw);
            var forward = MinimapMath.project(-Math.sin(radians), Math.cos(radians), angle, 3);
            assertEquals(0, forward.x(), 1e-9); assertEquals(-3, forward.y(), 1e-9);
            var p = MinimapMath.project(-35.75, 91.25, angle, .625);
            var q = MinimapMath.unproject(p.x(), p.y(), angle, .625);
            assertEquals(-35.75, q.x(), 1e-9); assertEquals(91.25, q.y(), 1e-9);
        }
    }

    @Test void northUpAndEdgeIndicatorsRespectWorldAxes() {
        assertEquals(new MinimapMath.Point(10, 0), MinimapMath.project(10, 0, 0, 1));
        assertEquals(new MinimapMath.Point(0, -10), MinimapMath.project(0, -10, 0, 1));
        assertEquals(new MinimapMath.Point(40, -20), MinimapMath.clamp(new MinimapMath.Point(200, -100), 40));
    }

    @Test void negativeCoordinatesUseFloorAndRemainInsideCache() {
        for (double x : new double[]{-29_999_999.9, -256, -16.1, -0.01, 0, 15.99, 16, 29_999_999}) {
            for (int step : new int[]{1, 2, 4}) {
                int origin = MinimapMath.origin(x, step, TerrainGrid.SIZE);
                double local = x / step - origin;
                assertTrue(local >= 128 && local < 144);
            }
        }
    }

    @Test void slidingCacheRetainsTheSameWorldColumnsInAllDirections() {
        for (int dx : new int[]{-256, -32, -16, 0, 16, 32, 256}) {
            for (int dz : new int[]{-256, -16, 0, 16, 256}) {
                var grid = new TerrainGrid(); grid.center(0, 0, 1);
                for (int i = 0; i < 65536; i++) grid.colors()[i] = i;
                grid.center(dx, dz, 1);
                for (int z = 0; z < 256; z++) for (int x = 0; x < 256; x++) {
                    int oldX = x + dx, oldZ = z + dz;
                    int expected = oldX >= 0 && oldX < 256 && oldZ >= 0 && oldZ < 256
                            ? oldZ * 256 + oldX : TerrainGrid.UNKNOWN;
                    assertEquals(expected, grid.colors()[z * 256 + x]);
                }
            }
        }
    }

    @Test void zoomAndLevelChangesDiscardOldTerrainAndScanCoversEveryColumn() {
        var grid = new TerrainGrid(); grid.center(0, 0, 1);
        Arrays.fill(grid.colors(), 0xff123456); grid.center(0, 0, 2);
        assertTrue(Arrays.stream(grid.colors()).allMatch(c -> c == TerrainGrid.UNKNOWN));
        var seen = new HashSet<Integer>();
        for (int i = 0; i < 65536; i++) assertTrue(seen.add(grid.next()));
        assertEquals(128 * 256 + 128, grid.next());
        grid.clear(); assertTrue(Arrays.stream(grid.colors()).allMatch(c -> c == TerrainGrid.UNKNOWN));
    }

    @Test void settingsAndUnicodeWaypointsSurviveRoundTripWithoutCrossingWorlds() throws Exception {
        var settings = new MinimapStore.Settings(); settings.zoom = 900; settings.size = -10; settings.validate();
        assertEquals(256, settings.radius()); assertEquals(80, settings.size);
        Path a = MinimapStore.worldFile(dir, "server:example.org:25565");
        Path b = MinimapStore.worldFile(dir, "server:example.org:25566");
        assertNotEquals(a, b);
        assertTrue(MinimapStore.worldFile(dir, "../../bad/world").startsWith(dir));
        var points = new MinimapStore.Waypoints();
        points.points.add(new MinimapStore.Waypoint("雪山营地", "minecraft:overworld", -100, 120, 800, 0xffffd77a, false));
        points.points.add(new MinimapStore.Waypoint("下界门", "minecraft:the_nether", -10, 64, 100, 0xffffd77a, false));
        MinimapStore.write(a, points);
        var loaded = MinimapStore.read(a, MinimapStore.Waypoints.class, new MinimapStore.Waypoints());
        assertEquals(points.points, loaded.points);
        assertTrue(MinimapStore.read(b, MinimapStore.Waypoints.class, new MinimapStore.Waypoints()).points.isEmpty());
    }

    @Test void malformedFileIsPreservedBeforeReplacingWithDefaults() throws Exception {
        Path file = dir.resolve("settings.json"); Files.writeString(file, "{ unfinished");
        var settings = MinimapStore.read(file, MinimapStore.Settings.class, new MinimapStore.Settings());
        MinimapStore.write(file, settings);
        try (var files = Files.list(dir)) {
            Path backup = files.filter(p -> p.getFileName().toString().contains(".broken-")).findFirst().orElseThrow();
            assertEquals("{ unfinished", Files.readString(backup));
        }
    }

    @Test void invalidWaypointsCannotLeakIntoRenderer() {
        var data = new MinimapStore.Waypoints();
        data.points.add(null);
        data.points.add(new MinimapStore.Waypoint(null, "minecraft:overworld", 0, 0, 0, 0, false));
        data.points.add(new MinimapStore.Waypoint("bad", "../escape", 0, 0, 0, 0, false));
        data.points.add(new MinimapStore.Waypoint("bad", "minecraft:overworld", Integer.MIN_VALUE, 0, 0, 0, false));
        data.validate(); assertTrue(data.points.isEmpty());
    }
}
