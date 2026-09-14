package mc.GTedd.cn.gtshaders;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import mc.GTedd.cn.gtshaders.export.StagedWrite;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 钉住「导出失败不许毁掉上一次导出的成果」。
 *
 * <p>为什么非测不可：出错路径本来就跑得少，而这条一旦回归，代价落在最不该承担的时刻——
 * 作者改了几行重新导出，中途失败，于是他<b>唯一还能用的那一份</b>被一个残缺的 zip 覆盖了。
 * 只要有人图省事把 {@code StagedWrite.toFile} 换回 {@code Files.newOutputStream(目标)}，
 * 这里就会红。
 */
class StagedWriteTest {

    @Test
    void 写入期间仍能读到完整旧版本且运行时异常会清理临时文件(@TempDir Path dir) throws IOException {
        Path target = dir.resolve("project.json");
        Files.writeString(target, "old");

        assertThrows(IllegalStateException.class, () -> StagedWrite.toFile(target, out -> {
            out.write("partial".getBytes(StandardCharsets.UTF_8));
            assertEquals("old", Files.readString(target));
            throw new IllegalStateException("interrupted");
        }));
        assertEquals("old", Files.readString(target));
        try (var files = Files.list(dir)) {
            assertEquals(1, files.count());
        }
    }

    @Test
    void 文本写入保留UTF8字符(@TempDir Path dir) throws IOException {
        Path target = dir.resolve("nested/project.json");
        String text = "{\"name\":\"中文与星空 \uD83C\uDF0C\"}\n";
        StagedWrite.writeUtf8(target, text);
        assertEquals(text, Files.readString(target, StandardCharsets.UTF_8));
    }

    @Test
    void 写成功后内容正确() throws IOException {
        Path dir = Files.createTempDirectory("gtshaders-staged");
        Path target = dir.resolve("pack.zip");

        StagedWrite.toFile(target, os -> os.write("hello".getBytes(StandardCharsets.UTF_8)));

        assertEquals("hello", Files.readString(target, StandardCharsets.UTF_8));
    }

    @Test
    void 写失败时不产生目标文件() throws IOException {
        Path dir = Files.createTempDirectory("gtshaders-staged");
        Path target = dir.resolve("pack.zip");

        assertThrows(IOException.class, () -> StagedWrite.toFile(target, os -> {
            os.write("half".getBytes(StandardCharsets.UTF_8));
            throw new IOException("boom");
        }));

        assertFalse(Files.exists(target), "失败时不该留下一个能被资源包列表识别、打开却报错的残缺文件");
    }

    @Test
    void 写失败时旧文件原样保留() throws IOException {
        Path dir = Files.createTempDirectory("gtshaders-staged");
        Path target = dir.resolve("pack.zip");
        Files.writeString(target, "上一次导出成功的内容", StandardCharsets.UTF_8);

        assertThrows(IOException.class, () -> StagedWrite.toFile(target, os -> {
            os.write("half".getBytes(StandardCharsets.UTF_8));
            throw new IOException("boom");
        }));

        assertEquals("上一次导出成功的内容", Files.readString(target, StandardCharsets.UTF_8),
                "这是整个改动的意义所在：失败的重导出不能吃掉上一版");
    }

    @Test
    void 写失败后不留临时残file() throws IOException {
        Path dir = Files.createTempDirectory("gtshaders-staged");
        Path target = dir.resolve("pack.zip");

        assertThrows(IOException.class, () -> StagedWrite.toFile(target, os -> {
            throw new IOException("boom");
        }));

        try (var s = Files.list(dir)) {
            List<Path> left = s.toList();
            assertTrue(left.isEmpty(), "临时文件没清干净：" + left);
        }
    }

    @Test
    void 成功覆盖旧文件() throws IOException {
        Path dir = Files.createTempDirectory("gtshaders-staged");
        Path target = dir.resolve("pack.zip");
        Files.writeString(target, "旧", StandardCharsets.UTF_8);

        StagedWrite.toFile(target, os -> os.write("新".getBytes(StandardCharsets.UTF_8)));

        assertEquals("新", Files.readString(target, StandardCharsets.UTF_8));
        try (var s = Files.list(dir)) {
            assertEquals(1, s.toList().size(), "覆盖之后目录里只该剩目标文件本身");
        }
    }

    @Test
    void 父目录不存在时自动创建() throws IOException {
        Path dir = Files.createTempDirectory("gtshaders-staged");
        Path target = dir.resolve("a/b/c/pack.zip");

        StagedWrite.toFile(target, os -> os.write("x".getBytes(StandardCharsets.UTF_8)));

        assertTrue(Files.isRegularFile(target));
    }
}
