package mc.GTedd.cn.gtshaders.workspace;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.Strictness;
import mc.GTedd.cn.gtshaders.GTShaders;
import mc.GTedd.cn.gtshaders.core.ShaderProject;
import mc.GTedd.cn.gtshaders.export.StagedWrite;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 效果快照，落在 {@code config/gtshaders/snapshots/}。
 *
 * <p>就是 git commit 的心智：随时把当前这一版效果<b>钉住</b>，起个名字，之后想回哪一版点哪一版。
 * 调着色器的过程本来就是「改一点看一眼」，上一版好看这一版难看是常态，而
 * 撤销栈只覆盖代码编辑器里的文本——图层的启用状态、混合模式、每个参数拖到哪，它一概不管。
 * 快照存的是整个工程，回退才是真的回得去。
 *
 * <p>几个刻意的决定：
 * <ul>
 *   <li><b>文件名只用时间戳，名字存在文件内容里。</b>这样重命名不需要改文件名，
 *       也就不会因为名字里有奇怪字符而失败。</li>
 *   <li><b>与工程共用一套序列化</b>（{@link ProjectStore#toJson}）。快照和工程存的是同一样东西，
 *       各写一份迟早会漂移。</li>
 *   <li><b>不自动裁剪。</b>快照就几 KB，替用户悄悄删掉「太旧的版本」是最不该发生的事——
 *       想回退的往往正是那一版。</li>
 * </ul>
 */
public final class SnapshotStore {

    private static final String EXT = ".gtsnap.json";

    /**
     * @param file      快照文件
     * @param message   提交信息，玩家可改
     * @param createdAt 提交时间（毫秒）
     * @param layers    层数，列表上做摘要用
     * @param enabled   其中启用的层数
     */
    public record Entry(Path file, String message, long createdAt, int layers, int enabled) {
    }

    private SnapshotStore() {
    }

    public static Path dir() {
        return Workspace.rootDir().resolve("snapshots");
    }

    /** 提交一版。返回写出的文件。 */
    public static Path commit(ShaderProject project, String message, long now) throws IOException {
        return commit(project, message, now, dir());
    }

    static Path commit(ShaderProject project, String message, long now, Path directory) throws IOException {
        Files.createDirectories(directory);

        JsonObject root = ProjectStore.toJson(project);
        root.addProperty("snapshotMessage", message);
        root.addProperty("createdAt", now);

        // 同一毫秒内连点两次提交也不该互相覆盖
        Path file = directory.resolve(now + EXT);
        int n = 1;
        while (Files.exists(file)) {
            file = directory.resolve(now + "-" + n++ + EXT);
        }
        StagedWrite.writeUtf8(file, new GsonBuilder().setPrettyPrinting().setStrictness(Strictness.STRICT)
                .create().toJson(root) + "\n");
        return file;
    }

    /** 最新的排在最前，和 {@code git log} 一致。读坏的条目直接跳过，不影响其余快照。 */
    public static List<Entry> list() {
        return list(dir());
    }

    static List<Entry> list(Path dir) {
        List<Entry> out = new ArrayList<>();
        if (!Files.isDirectory(dir)) {
            return out;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*" + EXT)) {
            for (Path p : stream) {
                Entry e = read(p);
                if (e != null) {
                    out.add(e);
                }
            }
        } catch (IOException e) {
            GTShaders.LOGGER.warn("快照目录读取失败：{}", e.getMessage());
        }
        out.sort(Comparator.comparingLong(Entry::createdAt).reversed());
        return out;
    }

    private static Entry read(Path file) {
        try {
            JsonObject root = JsonParser.parseString(
                    Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
            String message = root.has("snapshotMessage")
                    ? root.get("snapshotMessage").getAsString()
                    : stem(file);
            long createdAt = root.has("createdAt")
                    ? root.get("createdAt").getAsLong()
                    : parseStemAsMillis(file);
            int layers = 0;
            int enabled = 0;
            if (root.has("layers") && root.get("layers").isJsonArray()) {
                for (var el : root.getAsJsonArray("layers")) {
                    if (!el.isJsonObject()) {
                        continue;
                    }
                    layers++;
                    JsonObject lo = el.getAsJsonObject();
                    if (lo.has("enabled") && lo.get("enabled").getAsBoolean()) {
                        enabled++;
                    }
                }
            }
            return new Entry(file, message, createdAt, layers, enabled);
        } catch (IOException | RuntimeException e) {
            GTShaders.LOGGER.warn("快照 {} 读取失败：{}", file.getFileName(), e.getMessage());
            return null;
        }
    }

    public static ShaderProject restore(Entry entry) throws IOException {
        JsonObject root = JsonParser.parseString(
                Files.readString(entry.file(), StandardCharsets.UTF_8)).getAsJsonObject();
        return ProjectStore.fromJson(root, entry.message());
    }

    /** 改提交信息。只重写文件内容，文件名不动。 */
    public static void rename(Entry entry, String message) throws IOException {
        JsonObject root = JsonParser.parseString(
                Files.readString(entry.file(), StandardCharsets.UTF_8)).getAsJsonObject();
        root.addProperty("snapshotMessage", message);
        StagedWrite.writeUtf8(entry.file(),
                new GsonBuilder().setPrettyPrinting().setStrictness(Strictness.STRICT)
                        .create().toJson(root) + "\n");
    }

    public static boolean delete(Entry entry) {
        try {
            return Files.deleteIfExists(entry.file());
        } catch (IOException e) {
            GTShaders.LOGGER.warn("快照 {} 删除失败：{}", entry.file().getFileName(), e.getMessage());
            return false;
        }
    }

    private static String stem(Path file) {
        String n = file.getFileName().toString();
        return n.endsWith(EXT) ? n.substring(0, n.length() - EXT.length()) : n;
    }

    /** 文件里没记时间时的兜底：文件名前缀本来就是时间戳。 */
    private static long parseStemAsMillis(Path file) {
        String s = stem(file);
        int dash = s.indexOf('-');
        try {
            return Long.parseLong(dash > 0 ? s.substring(0, dash) : s);
        } catch (NumberFormatException e) {
            return 0L;
        }
    }
}
