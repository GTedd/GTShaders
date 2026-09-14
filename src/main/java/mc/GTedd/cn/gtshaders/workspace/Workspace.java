package mc.GTedd.cn.gtshaders.workspace;

import net.fabricmc.loader.api.FabricLoader;
import mc.GTedd.cn.gtshaders.GTShaders;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 工作目录布局。全部落在 {@code config/gtshaders/} 下：
 * <pre>
 *   projects/   工程文件（.gtshader）
 *   snapshots/  效果快照，「回到上一版」用
 *   export/     导出的资源包 zip
 *   lang/       用户自定义语言包，丢 json 进来即可新增语言
 * </pre>
 *
 * <p>刻意放在 config 而不是 .minecraft 根目录：这里是模组约定的可写位置，
 * 玩家整理实例、同步配置时不会被当成垃圾清掉。
 */
public final class Workspace {

    private Workspace() {
    }

    public static Path rootDir() {
        return FabricLoader.getInstance().getConfigDir().resolve(GTShaders.MOD_ID);
    }

    public static Path projectsDir() {
        return rootDir().resolve("projects");
    }

    public static Path exportDir() {
        return rootDir().resolve("export");
    }

    public static Path langDir() {
        return rootDir().resolve("lang");
    }

    /** 用户拖入的自定义贴图。 */
    public static Path importedTexturesDir() {
        return rootDir().resolve("imported_textures");
    }

    /** 建目录，并在语言目录里放一份说明，让用户知道该怎么加语言。 */
    public static void ensureLayout() {
        try {
            Files.createDirectories(projectsDir());
            Files.createDirectories(SnapshotStore.dir());
            Files.createDirectories(exportDir());
            Files.createDirectories(langDir());
            Files.createDirectories(importedTexturesDir());
            Path readme = langDir().resolve("README.txt");
            if (!Files.exists(readme)) {
                Files.writeString(readme, """
                        Drop <language_code>.json here to add or override GTShaders UI languages.
                        在这个目录里放 <语言代码>.json 就能新增或覆盖 GTShaders 的界面语言。

                        e.g. ja_jp.json, zh_tw.json — UTF-8 encoded flat key/value pairs:
                        例如 ja_jp.json、zh_tw.json，必须是 UTF-8 编码的扁平键值对：

                        {
                          "gtshaders.panel.shader_settings": "シェーダー設定",
                          "gtshaders.panel.tab.props": "プロパティ"
                        }

                        These override the built-in translations, so you may list only the
                        few keys you want to change. Full key lists live in the mod jar under
                        assets/gtshaders/lang/.
                        这里的翻译优先级高于内置翻译，所以也可以只写你想改的那几条。
                        完整的 key 列表在 mod jar 的 assets/gtshaders/lang/ 下。

                        Hit the globe button in the editor to reload without restarting.
                        改完在编辑器里点地球图标即可生效，不用重启游戏。
                        """, StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            GTShaders.LOGGER.warn("无法创建 GTShaders 工作目录", e);
        }
    }
}
