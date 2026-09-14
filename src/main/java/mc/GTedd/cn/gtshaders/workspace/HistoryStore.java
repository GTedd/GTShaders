package mc.GTedd.cn.gtshaders.workspace;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.Strictness;
import mc.GTedd.cn.gtshaders.GTShaders;
import mc.GTedd.cn.gtshaders.i18n.GtLang;
import mc.GTedd.cn.gtshaders.export.StagedWrite;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 本地使用历史，落在 {@code config/gtshaders/history.json}。
 *
 * <p>存的是「最近保存过 / 打开过哪个工程」，供菜单里的「最近使用」直接点开，
 * 这样调好的效果不必每次都去左栏的工程列表里翻。
 *
 * <p>两条刻意的决定：
 * <ul>
 *   <li><b>只记文件名，不记绝对路径。</b>整合包、实例被整体搬走时路径会全废，
 *       而工程永远躺在 {@code config/gtshaders/projects/} 下，用文件名反而稳。</li>
 *   <li><b>历史只是入口，不会被自动加载。</b>本局游戏里<b>第一次</b>打开编辑器时是空白无效果的工程，
 *       要不要把上次那套效果挂回去，由玩家点一下决定。（同一局里关掉编辑器再打开会保持当前效果，
 *       那是会话状态，与这份历史无关。）</li>
 * </ul>
 */
public final class HistoryStore {

    /** 历史条目上限。再多的话菜单里翻不动，价值也趋近于零。 */
    private static final int MAX_ENTRIES = 12;

    /**
     * @param name     工程名（存下来是为了文件被手工改名后仍能显示得出东西）
     * @param fileName {@code projects/} 下的文件名
     * @param usedAt   最近一次使用的时间戳（毫秒）
     */
    public record Entry(String name, String fileName, long usedAt) {

        public Path file() {
            return Workspace.projectsDir().resolve(fileName);
        }

        /** 文件名是否只指向 {@code projects/} 里的一个文件。越界的条目在 {@link #load()} 里就被丢掉了。 */
        boolean isSafe() {
            return SafePaths.resolveOrNull(Workspace.projectsDir(), fileName) != null;
        }

        public boolean exists() {
            return Files.isRegularFile(file());
        }
    }

    private HistoryStore() {
    }

    private static Path file() {
        return Workspace.rootDir().resolve("history.json");
    }

    /** 读历史。文件缺失或损坏都返回空列表——历史记录丢了不该影响任何功能。 */
    public static List<Entry> load() {
        List<Entry> out = new ArrayList<>();
        Path path = file();
        if (!Files.isRegularFile(path)) {
            return out;
        }
        try {
            JsonObject root = JsonParser.parseString(
                    Files.readString(path, StandardCharsets.UTF_8)).getAsJsonObject();
            if (!root.has("entries") || !root.get("entries").isJsonArray()) {
                return out;
            }
            for (JsonElement el : root.getAsJsonArray("entries")) {
                if (!el.isJsonObject()) {
                    continue;
                }
                JsonObject o = el.getAsJsonObject();
                if (!o.has("file")) {
                    continue;
                }
                Entry entry = new Entry(
                        o.has("name") ? o.get("name").getAsString() : o.get("file").getAsString(),
                        o.get("file").getAsString(),
                        o.has("usedAt") ? o.get("usedAt").getAsLong() : 0L);
                // history.json 是纯文本，玩家和别的工具都能改。越界的文件名在这里就丢掉，
                // 而不是留到 loadProject() 去读——那时候它已经是一个「看起来正常」的菜单项了。
                if (!entry.isSafe()) {
                    GTShaders.LOGGER.warn("history.json 里的文件名越界，已忽略：{}", entry.fileName());
                    continue;
                }
                out.add(entry);
            }
        } catch (IOException | RuntimeException e) {
            GTShaders.LOGGER.warn("history.json 读取失败：{}", e.getMessage());
        }
        return out;
    }

    /** 只返回文件还在的条目。工程被手工删掉后，菜单里不该继续列一个点了会报错的入口。 */
    public static List<Entry> loadExisting() {
        List<Entry> out = new ArrayList<>();
        for (Entry e : load()) {
            if (e.exists()) {
                out.add(e);
            }
        }
        return out;
    }

    /** 记一笔。同一个文件只保留最新的一条，并置顶。 */
    public static void record(String name, Path projectFile, long now) {
        String fileName = projectFile.getFileName().toString();
        List<Entry> entries = new ArrayList<>();
        entries.add(new Entry(name, fileName, now));
        for (Entry e : load()) {
            if (!e.fileName().equals(fileName) && entries.size() < MAX_ENTRIES) {
                entries.add(e);
            }
        }
        save(entries);
    }

    public static void clear() {
        save(List.of());
    }

    private static void save(List<Entry> entries) {
        JsonObject root = new JsonObject();
        root.addProperty("format", 1);
        JsonArray arr = new JsonArray();
        for (Entry e : entries) {
            JsonObject o = new JsonObject();
            o.addProperty("name", e.name());
            o.addProperty("file", e.fileName());
            o.addProperty("usedAt", e.usedAt());
            arr.add(o);
        }
        root.add("entries", arr);
        try {
            StagedWrite.writeUtf8(file(),
                    new GsonBuilder().setPrettyPrinting().setStrictness(Strictness.STRICT)
                            .create().toJson(root) + "\n");
        } catch (IOException e) {
            GTShaders.LOGGER.warn("history.json 写入失败：{}", e.getMessage());
        }
    }

    /**
     * 「3 分钟前」这类相对时间。绝对时间戳在这里没什么用——玩家关心的是
     * 「哪个是刚才那个」，而不是具体几点几分。
     */
    public static String relativeTime(long usedAt, long now) {
        long diff = Math.max(0L, now - usedAt);
        long minutes = diff / 60_000L;
        if (minutes < 1) {
            return GtLang.get("gtshaders.history.just_now");
        }
        if (minutes < 60) {
            return GtLang.get("gtshaders.history.minutes", minutes);
        }
        long hours = minutes / 60;
        if (hours < 24) {
            return GtLang.get("gtshaders.history.hours", hours);
        }
        return GtLang.get("gtshaders.history.days", hours / 24);
    }
}
