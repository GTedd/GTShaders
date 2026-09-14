package mc.GTedd.cn.gtshaders;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import mc.GTedd.cn.gtshaders.core.ShaderLayer;
import mc.GTedd.cn.gtshaders.core.ShaderParam;
import mc.GTedd.cn.gtshaders.core.ShaderProject;
import mc.GTedd.cn.gtshaders.workspace.ProjectStore;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** 手改工程或升级参数类型后，已有数值与源码默认值都应保留下来。 */
class ProjectValuesTest {
    private static final String SOURCE = """
            // @param name=Tint type=color4 default=0.1,0.2,0.3,1
            // @param name=Amount type=float min=0 max=1 default=0.5
            void main() { fragColor = vec4(Tint.rgb * Amount, Tint.a); }
            """;

    @Test
    void 缺少的向量分量保留源码默认值() {
        ShaderLayer layer = restore("{\"Tint\":[0.8,0.7,0.6]}");
        assertArrayEquals(new float[]{0.8f, 0.7f, 0.6f, 1f}, param(layer, "Tint").value(), 1e-6f);
    }

    @Test
    void 空数组不清空参数默认值() {
        ShaderLayer layer = restore("{\"Tint\":[],\"Amount\":[]}");
        assertArrayEquals(new float[]{0.1f, 0.2f, 0.3f, 1f}, param(layer, "Tint").value(), 1e-6f);
        assertEquals(0.5f, param(layer, "Amount").get(0));
    }

    @Test
    void 单个损坏分量不妨碍其他分量和参数恢复() {
        ShaderLayer layer = assertDoesNotThrow(() ->
                restore("{\"Tint\":[0.8,null,\"bad\",0.9],\"Amount\":[0.6]}"));
        assertArrayEquals(new float[]{0.8f, 0.2f, 0.3f, 0.9f}, param(layer, "Tint").value(), 1e-6f);
        assertEquals(0.6f, param(layer, "Amount").get(0));
    }

    @Test
    void 非有限数值不进入着色器参数() {
        ShaderLayer layer = restore("{\"Tint\":[\"NaN\",\"Infinity\",1e100,0.9]}");
        assertArrayEquals(new float[]{0.1f, 0.2f, 0.3f, 0.9f}, param(layer, "Tint").value(), 1e-6f);
        ShaderProject project = new ShaderProject("finite");
        project.addLayer(layer);
        assertDoesNotThrow(() -> ProjectStore.toJson(project).toString());
    }

    @Test
    void 非数字结构不覆盖默认值() {
        ShaderLayer layer = assertDoesNotThrow(() -> restore("{\"Tint\":[{},[],true,0.9]}"));
        assertArrayEquals(new float[]{0.1f, 0.2f, 0.3f, 0.9f}, param(layer, "Tint").value(), 1e-6f);
    }

    @Test
    void 有效数值仍遵守范围且忽略多余分量() {
        ShaderLayer layer = restore("{\"Tint\":[-1,2,0.6,0.9,\"bad\"],\"Amount\":[2]}");
        assertArrayEquals(new float[]{0f, 1f, 0.6f, 0.9f}, param(layer, "Tint").value(), 1e-6f);
        assertEquals(1f, param(layer, "Amount").get(0));
    }

    @Test
    void 单层旧格式也保留缺少的分量() {
        JsonObject root = new JsonObject();
        root.addProperty("source", SOURCE);
        root.add("values", JsonParser.parseString("{\"Tint\":[0.8]}"));
        ShaderLayer layer = ProjectStore.fromJson(root, "legacy").layers().get(0);
        assertArrayEquals(new float[]{0.8f, 0.2f, 0.3f, 1f}, param(layer, "Tint").value(), 1e-6f);
    }

    private static ShaderLayer restore(String values) {
        ShaderProject project = new ShaderProject("values");
        project.addLayer(new ShaderLayer("layer", SOURCE));
        JsonObject root = ProjectStore.toJson(project);
        root.getAsJsonArray("layers").get(0).getAsJsonObject().add("values", JsonParser.parseString(values));
        return ProjectStore.fromJson(root, "fallback").layers().get(0);
    }

    private static ShaderParam param(ShaderLayer layer, String name) {
        return layer.params().stream().filter(p -> p.name().equals(name)).findFirst().orElseThrow();
    }
}
