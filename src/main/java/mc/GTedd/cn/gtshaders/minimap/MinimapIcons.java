package mc.GTedd.cn.gtshaders.minimap;

import com.google.gson.JsonParser;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/** Sprite identifiers and shader atlas slots originate from the same resource manifest. */
public final class MinimapIcons {
    private static final Map<String, Identifier> ICONS = load();
    private MinimapIcons() {}
    private static Map<String, Identifier> load() {
        try (var stream = MinimapIcons.class.getResourceAsStream("/assets/gtshaders/minimap/icons.json")) {
            if (stream == null) return Map.of();
            var result = new HashMap<String, Identifier>();
            var json = JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject();
            json.getAsJsonObject("icons").entrySet().forEach(entry -> result.put(entry.getKey(),
                    Identifier.parse(entry.getValue().getAsJsonObject().get("sprite").getAsString())));
            return Map.copyOf(result);
        } catch (java.io.IOException | RuntimeException error) {
            mc.GTedd.cn.gtshaders.GTShaders.LOGGER.warn("小地图头像目录读取失败，回退到分类标记", error);
            return Map.of();
        }
    }
    public static Identifier of(Entity entity) {
        return ICONS.get(BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString());
    }
}
