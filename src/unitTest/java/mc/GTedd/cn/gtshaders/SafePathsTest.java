package mc.GTedd.cn.gtshaders;

import org.junit.jupiter.api.Test;
import mc.GTedd.cn.gtshaders.workspace.SafePaths;

import java.io.File;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 钉住「外部字符串拼出来的路径不许跑出工作目录」。
 *
 * <p>为什么非测不可：漏掉这条不会报错，也不会有人在 review 时看出来——
 * 症状是导出的资源包里悄悄多了一个不该在里面的文件。而触发它只需要一行注解：
 * {@code // @texture path=gtshaders:imported/../../../foo.png}。
 * Minecraft 的 Identifier 路径<b>允许</b> {@code .}，所以这串东西一路都是合法的，
 * 只有到 resolve 这一步才越界。
 */
class SafePathsTest {

    private static final Path ROOT = Path.of("C:/game/config/gtshaders/imported_textures")
            .toAbsolutePath().normalize();

    @Test
    void 普通文件名正常解析() {
        Path p = SafePaths.resolveOrNull(ROOT, "killicon.png");
        assertNotNull(p, "普通文件名不该被拒");
        assertEquals(ROOT.resolve("killicon.png"), p);
    }

    @Test
    void 子目录正常解析() {
        Path p = SafePaths.resolveOrNull(ROOT, "sub/deep/a.png");
        assertNotNull(p);
        assertEquals(ROOT.resolve("sub").resolve("deep").resolve("a.png"), p);
    }

    @Test
    void 回退一级就被拒() {
        assertNull(SafePaths.resolveOrNull(ROOT, "../a.png"), "../ 必须被拒");
    }

    @Test
    void 回退多级被拒() {
        assertNull(SafePaths.resolveOrNull(ROOT, "../../../windows/system32/a.png"));
    }

    @Test
    void 先进子目录再回退超出也被拒() {
        // 这种最容易漏：中间路径看起来一直在目录里，只有算到最后才出界
        assertNull(SafePaths.resolveOrNull(ROOT, "sub/../../../a.png"));
    }

    @Test
    void 进子目录再退回来仍在范围内() {
        Path p = SafePaths.resolveOrNull(ROOT, "sub/../a.png");
        assertNotNull(p, "净效果还在目录里，不该被拒");
        assertEquals(ROOT.resolve("a.png"), p);
    }

    @Test
    void 绝对路径被拒() {
        assertNull(SafePaths.resolveOrNull(ROOT, Path.of("/etc/passwd").toAbsolutePath().toString()));
    }

    @Test
    void 指向目录自己被拒() {
        // 调用方要的是目录里的一个文件。返回目录本身会让上层的 readAllBytes 拿到 IsADirectory
        assertNull(SafePaths.resolveOrNull(ROOT, "."));
        assertNull(SafePaths.resolveOrNull(ROOT, "sub/.."));
    }

    @Test
    void 空与null被拒() {
        assertNull(SafePaths.resolveOrNull(ROOT, null));
        assertNull(SafePaths.resolveOrNull(ROOT, ""));
        assertNull(SafePaths.resolveOrNull(ROOT, "   "));
    }

    @Test
    void 同前缀的兄弟目录不算在范围内() {
        // startsWith 若按字符串前缀比较，imported_textures_evil 会被误判成在 imported_textures 下。
        // Path.startsWith 按路径元素比，这里钉住这个行为。
        assertNull(SafePaths.resolveOrNull(ROOT, "../imported_textures_evil/a.png"));
    }

    @Test
    void windows下带根但非绝对的路径被拒() {
        // Path.of("\\foo").isAbsolute() 在 Windows 上是 false（盘符未定），
        // 但它带 root，resolve 时会把基准目录整个顶掉。只判 isAbsolute 会漏掉它。
        if (File.separatorChar != '\\') {
            return;
        }
        assertNull(SafePaths.resolveOrNull(ROOT, "\\Windows\\System32\\a.png"));
        assertNull(SafePaths.resolveOrNull(ROOT, "D:\\a.png"));
    }

    @Test
    void 非法字符返回null而不是抛异常() {
        // Windows 上 "a:b" 这类串会让 Path.of 抛 InvalidPathException。
        // 调用方是「找不到就当没有」的读取路径，不该被一个异常打断
        if (File.separatorChar != '\\') {
            return;
        }
        assertNull(SafePaths.resolveOrNull(ROOT, "a:b.png"));
    }

    @Test
    void resolve在越界时抛异常() {
        assertThrows(IllegalArgumentException.class, () -> SafePaths.resolve(ROOT, "../a.png"));
        assertEquals(ROOT.resolve("a.png"), SafePaths.resolve(ROOT, "a.png"));
    }
}
