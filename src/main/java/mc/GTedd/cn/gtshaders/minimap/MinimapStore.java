package mc.GTedd.cn.gtshaders.minimap;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import mc.GTedd.cn.gtshaders.export.StagedWrite;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/** Settings are global; waypoints are isolated by server/save and dimension. */
public final class MinimapStore {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    public static final int[] RADII = {32, 64, 128, 256};
    public static final int MAX_WAYPOINTS = 64;

    public static final class Settings {
        public boolean enabled = true;
        public boolean headingUp;
        public boolean entities = true;
        public boolean entityIcons = true;
        public boolean slice;
        public int zoom = 1;
        public int size = 128;
        public void validate() {
            zoom = Math.clamp(zoom, 0, RADII.length - 1);
            size = Math.clamp(size, 80, 180);
        }
        public int radius() { return RADII[zoom]; }
    }

    public record Waypoint(String name, String dimension, int x, int y, int z, int color,
                           boolean death) {
        public boolean valid() {
            return name != null && !name.isBlank() && name.length() <= 40 && dimension != null
                    && dimension.matches("[a-z0-9_.-]+:[a-z0-9_/.-]+")
                    && Math.abs((long) x) <= 30_000_000 && Math.abs((long) z) <= 30_000_000
                    && y >= -2048 && y <= 2048;
        }
    }

    public static final class Waypoints {
        public List<Waypoint> points = new ArrayList<>();
        public void validate() {
            if (points == null) points = new ArrayList<>();
            points = new ArrayList<>(points.stream().filter(p -> p != null && p.valid())
                    .limit(MAX_WAYPOINTS).toList());
        }
    }

    public static Path worldFile(Path root, String world) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(world.getBytes(StandardCharsets.UTF_8));
            return root.resolve("waypoints").resolve(HexFormat.of().formatHex(hash) + ".json");
        } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    public static <T> T read(Path file, Class<T> type, T fallback) throws IOException {
        if (!Files.exists(file)) return fallback;
        try {
            T value = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), type);
            if (value == null) throw new IllegalArgumentException("null document");
            return value;
        } catch (RuntimeException e) {
            // Preserve the original before allowing future saves to replace it.
            Files.copy(file, file.resolveSibling(file.getFileName() + ".broken-" + System.nanoTime()));
            return fallback;
        }
    }

    public static void write(Path file, Object value) throws IOException {
        StagedWrite.writeUtf8(file, GSON.toJson(value) + "\n");
    }
}
