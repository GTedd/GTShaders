package mc.GTedd.cn.gtshaders;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import mc.GTedd.cn.gtshaders.core.TargetVersion;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * {@link TargetVersion} 读到的值确实来自 {@code gradle.properties}，且和原版 {@code version.json} 对得上。
 *
 * <p>两条各拦一类静默错误：第一条拦「资源没展开 / 展开的是旧值」（processResources 被判 UP-TO-DATE），
 * 第二条拦「版本号跟了、包格式号忘了跟」——26.3-pre-3 数据包格式从 120 涨到 121，就是这么漏过去的。
 */
class TargetVersionTest {

    @Test
    void 运行时读到的版本号就是gradle里的那个() {
        assertEquals(VanillaAssets.version(), TargetVersion.minecraft());
    }

    @Test
    void 包格式号与原版versionJson一致() throws Exception {
        assumeTrue(Files.isRegularFile(VanillaAssets.versionJson()),
                "没放 " + VanillaAssets.versionJson() + "，跳过（从 client.jar 根目录取）");
        JsonObject root = JsonParser.parseString(
                Files.readString(VanillaAssets.versionJson(), StandardCharsets.UTF_8)).getAsJsonObject();
        assertEquals(VanillaAssets.version(), root.get("id").getAsString(), "version.json 不是这个版本的");
        JsonObject pack = root.getAsJsonObject("pack_version");
        assertEquals(List.of(pack.get("resource_major").getAsInt(), pack.get("resource_minor").getAsInt()),
                TargetVersion.resourcePackFormat(), "gradle.properties 的 resource_pack_format 没跟上");
        assertEquals(List.of(pack.get("data_major").getAsInt(), pack.get("data_minor").getAsInt()),
                TargetVersion.dataPackFormat(), "gradle.properties 的 data_pack_format 没跟上");
    }
}
