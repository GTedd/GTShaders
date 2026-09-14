package mc.GTedd.cn.gtshaders;

import mc.GTedd.cn.gtshaders.core.GtProfile;
import mc.GTedd.cn.gtshaders.core.ShaderLayer;
import mc.GTedd.cn.gtshaders.core.ShaderProject;
import mc.GTedd.cn.gtshaders.export.ExportTarget;
import mc.GTedd.cn.gtshaders.export.ResourcePackExporter;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Comparator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 钉住「自定义导出落点」这条路：目录和文件名由用户给，<b>中文必须活着到磁盘上</b>。
 *
 * <p>为什么要单独钉：包<i>内部</i>的资源路径必须是 ASCII（原版加载器的硬性要求），
 * 于是很容易顺手拿同一个收敛函数去处理文件名——一旦这么做，所有中文名都会塌成
 * {@code untitled}，两个中文工程导出后互相覆盖，而现场看起来只是「导出好像没生效」。
 * 这一层没有报错、没有日志，只能靠用例挡住。
 */
class ExportTargetTest {

    private static final String BODY = """
            void main() {
                fragColor = texture(InSampler, texCoord);
            }
            """;

    // ------------------------------------------------------------------ 文件名

    @Test
    void 中文文件名原样保留() {
        assertEquals("我的赛博朋克效果", ExportTarget.fileName("我的赛博朋克效果", "fallback"));
        assertEquals("空格 和.点 都留着", ExportTarget.fileName("空格 和.点 都留着", "fallback"));
    }

    @Test
    void 留空时用调用方给的默认名() {
        assertEquals("fx_26_3", ExportTarget.fileName("", "fx_26_3"));
        assertEquals("fx_26_3", ExportTarget.fileName("   ", "fx_26_3"));
        assertEquals("fx_26_3", ExportTarget.fileName(null, "fx_26_3"));
    }

    @Test
    void 自己带上zip后缀不会拼成两层() {
        assertEquals("我的包", ExportTarget.fileName("我的包.zip", "fallback"));
        assertEquals("我的包", ExportTarget.fileName("我的包.ZIP", "fallback"));
        // 只削末尾那一次：名字里本来就有 .zip 字样的不该被吃掉
        assertEquals("zip包.v2", ExportTarget.fileName("zip包.v2", "fallback"));
    }

    @Test
    void 文件系统不收的字符换成下划线() {
        assertEquals("a_b_c_d", ExportTarget.fileName("a/b:c|d", "fallback"));
        assertFalse(ExportTarget.fileName("我<的>包?", "fallback").matches(".*[<>?].*"));
    }

    @Test
    void 首尾的点和空格被收掉() {
        // Windows 会静默地吃掉它们，于是「我的包 」和「我的包」指向同一个文件
        assertEquals("我的包", ExportTarget.fileName("  我的包 . ", "fallback"));
    }

    @Test
    void 带扩展名的Windows设备名也要避开() {
        assertEquals("_CON", ExportTarget.fileName("CON", "fallback"));
        assertEquals("_com1.effect", ExportTarget.fileName("com1.effect", "fallback"));
    }

    @Test
    void 超长名称截断不拆开Unicode字符() {
        String raw = "包".repeat(150) + "🌌" + "v2";
        String out = ExportTarget.fileName(raw, "fallback");
        assertTrue(out.length() <= 196);
        assertFalse(Character.isHighSurrogate(out.charAt(out.length() - 1)),
                "不能留下半个代理对，后续文件操作会拒绝这个名字");
    }

    // ------------------------------------------------------------------ 目录

    @Test
    void 空目录退回默认导出目录() {
        Path fallback = Path.of("game", "config", "gtshaders", "export");
        assertEquals(fallback.toAbsolutePath().normalize(), ExportTarget.directory("", fallback));
        assertEquals(fallback.toAbsolutePath().normalize(), ExportTarget.directory(null, fallback));
    }

    @Test
    void 绝对路径原样用() {
        Path fallback = Path.of("game", "export");
        Path abs = Path.of("D:", "我的资源包").toAbsolutePath();
        assertEquals(abs.normalize(), ExportTarget.directory(abs.toString(), fallback));
    }

    @Test
    void 相对路径落在默认导出目录下面() {
        Path fallback = Path.of("game", "export").toAbsolutePath();
        assertEquals(fallback.resolve("我的包").normalize(),
                ExportTarget.directory("我的包", fallback));
    }

    @Test
    void 粘进来的带引号路径也认() {
        // Windows 资源管理器的「复制文件地址」给的就是带引号的串
        Path fallback = Path.of("game", "export").toAbsolutePath();
        Path abs = Path.of("D:", "包").toAbsolutePath();
        assertEquals(abs.normalize(), ExportTarget.directory("\"" + abs + "\"", fallback));
    }

    @Test
    void 非法路径抛出来而不是悄悄换个地方写() {
        Path fallback = Path.of("game", "export").toAbsolutePath();
        // Windows 上 "a:b" 这类串不是合法路径。悄悄退回默认目录的话，
        // 用户会拿着一个「导出成功」的提示去他指定的目录里找一个不存在的文件
        assertThrows(InvalidPathException.class,
                () -> ExportTarget.directory("a:b:c*?", fallback));
    }

    // ------------------------------------------------------------------ 选择框定位

    @Test
    void 选择框定位退到第一个存在的祖先() throws IOException {
        Path root = Files.createTempDirectory("gtshaders-ancestor-test");
        try {
            Path deep = root.resolve("还没建的").resolve("更深的一层");
            assertEquals(root.toAbsolutePath().normalize(), ExportTarget.existingAncestor(deep),
                    "传不存在的目录进系统对话框，各平台表现不一，必须先退到存在的那一级");
            assertEquals(root.toAbsolutePath().normalize(), ExportTarget.existingAncestor(root));
        } finally {
            deleteTree(root);
        }
    }

    @Test
    void 选择框定位允许没有落点() {
        assertNull(ExportTarget.existingAncestor(null));
    }

    // ------------------------------------------------------------------ 端到端

    @Test
    void 自定义中文名和子目录真的落到磁盘上() throws IOException {
        Path root = Files.createTempDirectory("gtshaders-target-test");
        try {
            ShaderProject p = project("未命名工程");
            ResourcePackExporter.Result r = ResourcePackExporter.export(p, root,
                    ResourcePackExporter.Options.defaults("fx")
                            .withDir("我的包")
                            .withFileName("赛博朋克 v2"));

            assertEquals(root.resolve("我的包").toAbsolutePath().normalize(),
                    r.file().getParent(), "自定义子目录要落在默认导出目录下面");
            assertEquals("赛博朋克 v2.zip", r.file().getFileName().toString());
            assertTrue(Files.isRegularFile(r.file()), "文件没生成：" + r.file());
        } finally {
            deleteTree(root);
        }
    }

    @Test
    void 没自定义时中文工程名不再塌成untitled() throws IOException {
        Path root = Files.createTempDirectory("gtshaders-target-test");
        try {
            // 两个不同的中文工程如果都取 untitled，第二次导出会默默覆盖第一次的产物
            ResourcePackExporter.Result a = ResourcePackExporter.export(project("霓虹"), root,
                    ResourcePackExporter.Options.defaults("fx"));
            ResourcePackExporter.Result b = ResourcePackExporter.export(project("雨夜"), root,
                    ResourcePackExporter.Options.defaults("fx"));

            assertEquals("霓虹_26_3.zip", a.file().getFileName().toString());
            assertEquals("雨夜_26_3.zip", b.file().getFileName().toString());
        } finally {
            deleteTree(root);
        }
    }

    @Test
    void 包内资源路径仍然是ASCII() throws IOException {
        Path root = Files.createTempDirectory("gtshaders-target-test");
        try {
            ResourcePackExporter.Result r = ResourcePackExporter.export(project("霓虹"), root,
                    ResourcePackExporter.Options.defaults("fx").withFileName("霓虹"));
            try (java.util.zip.ZipInputStream in = new java.util.zip.ZipInputStream(
                    Files.newInputStream(r.file()))) {
                java.util.zip.ZipEntry e;
                while ((e = in.getNextEntry()) != null) {
                    assertTrue(e.getName().chars().allMatch(c -> c < 0x80),
                            "包内路径必须是 ASCII，原版加载器不认别的：" + e.getName());
                }
            }
        } finally {
            deleteTree(root);
        }
    }

    private static ShaderProject project(String name) {
        ShaderProject p = new ShaderProject(name);
        p.clearLayers();
        ShaderLayer l = new ShaderLayer("L", BODY);
        l.setEnabled(true);
        p.addLayer(l);
        p.setExportProfile(GtProfile.MC_26_3);
        return p;
    }

    private static void deleteTree(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (var walk = Files.walk(dir)) {
            for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }
}
