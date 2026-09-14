package mc.GTedd.cn.gtshaders;

import mc.GTedd.cn.gtshaders.workspace.ProjectStore;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class ProjectFileNameTest {
    @Test
    void 带扩展名的Windows设备名也要避开() {
        for (String name : new String[]{"CON", "con.demo", "AUX.backup", "COM1.effect", "LPT9.test"}) {
            assertEquals("_" + name + ".gtshader.json", ProjectStore.fileNameFor(name));
        }
    }

    @Test
    void 普通中文名称和带点名称保持可读() {
        assertEquals("我的效果.v2.gtshader.json", ProjectStore.fileNameFor("我的效果.v2"));
        assertEquals("console.gtshader.json", ProjectStore.fileNameFor("console"));
        assertEquals("a_b_c.gtshader.json", ProjectStore.fileNameFor("a/b:c"));
    }

    @Test
    void 长名称截断不拆开Unicode字符() {
        String name = "a".repeat(195) + "\uD83C\uDF0C" + "v2";
        String stem = ProjectStore.displayName(java.nio.file.Path.of(ProjectStore.fileNameFor(name)));
        assertFalse(Character.isHighSurrogate(stem.charAt(stem.length() - 1)),
                "不能留下半个代理对，后续文件操作会拒绝这个名字");
    }
}
