package mc.GTedd.cn.gtshaders.workspace;

import com.google.gson.JsonParser;
import mc.GTedd.cn.gtshaders.core.ShaderLayer;
import mc.GTedd.cn.gtshaders.core.ShaderProject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 使用独立目录验证真正的落盘与回读，不依赖 Fabric，也不接触玩家工作目录。 */
class ProjectPersistenceTest {
    @TempDir
    Path dir;

    @Test
    void 保存到新目录再覆盖能完整读回中文工程() throws IOException {
        Path projects = dir.resolve("nested/projects");
        ShaderProject project = project("中文效果");
        Path first = ProjectStore.save(project, projects);
        project.layers().get(0).setStrength(0.4f);
        Path second = ProjectStore.save(project, projects);

        assertEquals(first, second);
        ShaderProject restored = ProjectStore.load(second);
        assertEquals("中文效果", restored.name());
        assertEquals(0.4f, restored.layers().get(0).strength());
        assertTrue(restored.layers().get(0).viewport().isFullScreen());
        try (var files = Files.list(projects)) {
            assertEquals(1, files.count(), "成功后只保留完整工程，不留 .part");
        }
    }

    @Test
    void 无法序列化时保留上次保存内容() throws IOException {
        ShaderProject project = project("last-good");
        Path file = ProjectStore.save(project, dir);
        String original = Files.readString(file, StandardCharsets.UTF_8);
        project.layers().get(0).setStrength(Float.NaN);

        assertThrows(IllegalArgumentException.class, () -> ProjectStore.save(project, dir));
        assertEquals(original, Files.readString(file, StandardCharsets.UTF_8));
        try (var files = Files.list(dir)) {
            assertEquals(1, files.count());
        }
    }

    @Test
    void 替换失败时保留原目标并清理临时文件() throws IOException {
        ShaderProject project = project("blocked");
        Path target = dir.resolve(ProjectStore.fileNameFor(project.name()));
        Files.createDirectory(target);
        Path marker = target.resolve("keep.txt");
        Files.writeString(marker, "keep");

        assertThrows(IOException.class, () -> ProjectStore.save(project, dir));
        assertEquals("keep", Files.readString(marker));
        try (var files = Files.list(dir)) {
            assertEquals(1, files.count(), "提交失败也必须清理临时文件");
        }
    }

    @Test
    void 特殊工程名可以实际保存() throws IOException {
        for (String name : new String[]{"CON.demo", "AUX.backup", "中文\uD83C\uDF0C"}) {
            ShaderProject project = project(name);
            Path file = ProjectStore.save(project, dir);
            assertTrue(Files.isRegularFile(file));
            assertEquals(name, ProjectStore.load(file).name());
        }
    }

    @Test
    void 快照提交重命名和恢复保留完整工程() throws IOException {
        ShaderProject project = project("快照工程");
        Path file = SnapshotStore.commit(project, "初稿", 1000L, dir);
        SnapshotStore.Entry entry = SnapshotStore.list(dir).get(0);
        assertEquals(file, entry.file());
        assertEquals(1, entry.layers());
        assertEquals(1, entry.enabled());
        String before = Files.readString(file, StandardCharsets.UTF_8);

        SnapshotStore.rename(entry, "调整后的说明");
        var renamed = SnapshotStore.list(dir).get(0);
        assertEquals(file, renamed.file());
        assertEquals("调整后的说明", renamed.message());
        assertEquals(1000L, renamed.createdAt());
        ShaderProject restored = SnapshotStore.restore(renamed);
        assertEquals(ProjectStore.toJson(project), ProjectStore.toJson(restored));

        var oldJson = JsonParser.parseString(before).getAsJsonObject();
        var newJson = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
        oldJson.remove("snapshotMessage");
        newJson.remove("snapshotMessage");
        assertEquals(oldJson, newJson, "重命名只能改变提交说明");
        try (var files = Files.list(dir)) {
            assertEquals(1, files.count());
        }
    }

    @Test
    void 同一毫秒连续提交保留两版() throws IOException {
        ShaderProject project = project("snapshots");
        Path first = SnapshotStore.commit(project, "first", 1000L, dir);
        project.layers().get(0).setStrength(0.3f);
        Path second = SnapshotStore.commit(project, "second", 1000L, dir);
        assertNotEquals(first, second);
        assertEquals(2, SnapshotStore.list(dir).size());
        assertEquals(1f, ProjectStore.load(first).layers().get(0).strength());
        assertEquals(0.3f, ProjectStore.load(second).layers().get(0).strength());
    }

    @Test
    void 无效数值不能生成损坏快照() throws IOException {
        ShaderProject project = project("invalid");
        project.layers().get(0).setStrength(Float.NaN);
        assertThrows(IllegalArgumentException.class, () -> SnapshotStore.commit(project, "invalid", 1000L, dir));
        try (var files = Files.list(dir)) {
            assertEquals(0, files.count(), "失败的提交不能留下快照或临时文件");
        }
    }

    @Test
    void 损坏的快照不妨碍列出和恢复其他版本() throws IOException {
        SnapshotStore.commit(project("valid"), "有效版本", 1000L, dir);
        Path broken = dir.resolve("2000.gtsnap.json");
        Files.writeString(broken, "{broken");
        var entries = SnapshotStore.list(dir);
        assertEquals(1, entries.size());
        assertEquals("valid", SnapshotStore.restore(entries.get(0)).name());

        SnapshotStore.Entry invalid = new SnapshotStore.Entry(broken, "broken", 2000L, 0, 0);
        assertThrows(RuntimeException.class, () -> SnapshotStore.rename(invalid, "new"));
        assertEquals("{broken", Files.readString(broken));
        assertFalse(entries.stream().anyMatch(e -> e.file().equals(broken)));
    }

    private static ShaderProject project(String name) {
        ShaderProject project = new ShaderProject(name);
        // 模拟从旧格式迁移后，作者又把这一层改成全屏。
        project.viewport().set(0.2f, 0.3f, 0.8f, 0.9f);
        ShaderLayer layer = new ShaderLayer("颜色层", "void main() { fragColor = vec4(1.0); }");
        layer.setEnabled(true);
        project.addLayer(layer);
        return project;
    }
}
