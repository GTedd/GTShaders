package mc.GTedd.cn.gtshaders.ui;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 哪个面板停在哪个区、每个区当前显示哪一个。
 *
 * <h2>唯一一条必须守住的不变式</h2>
 *
 * <p><b>每个 {@link DockPanel} 恰好出现一次。</b>少一个，那块内容就在界面上彻底消失
 * 且没有任何入口找回来；多一个，同一个面板会被画两遍、注册两套点击区，
 * 点一下触发两次回调。两种都不会抛异常，只会表现成「界面坏了」。
 *
 * <p>所以 {@link #normalize()} 在每次读盘和每次改动之后都会跑一遍：
 * 重复的只留第一个，缺失的送回它的 {@link DockPanel#home()}。
 * 手改坏了 {@code layout.json} 也不会让编辑器打不开。
 *
 * <h2>选中项存面板而不是下标</h2>
 *
 * <p>下标会在面板搬走之后失效——把左栏第一个面板拖去底部，剩下两个的下标全变，
 * 原本选中的那个会莫名其妙换成别的。存面板本身就没这个问题。
 */
public final class DockLayout {

    private final Map<DockZone, List<DockPanel>> zones = new EnumMap<>(DockZone.class);
    private final Map<DockZone, DockPanel> active = new EnumMap<>(DockZone.class);
    /** 浮动面板各自的矩形 {x, y, w, h}。只有停在 {@link DockZone#FLOATING} 的才有。 */
    private final Map<DockPanel, int[]> floatRects = new EnumMap<>(DockPanel.class);

    public DockLayout() {
        reset();
    }

    /** 回到出厂布局：每个面板各回各家。 */
    public void reset() {
        zones.clear();
        active.clear();
        floatRects.clear();
        for (DockZone z : DockZone.values()) {
            zones.put(z, new ArrayList<>());
        }
        for (DockPanel p : DockPanel.values()) {
            zones.get(p.home()).add(p);
        }
        normalize();
    }

    public List<DockPanel> panelsIn(DockZone zone) {
        return List.copyOf(zones.getOrDefault(zone, List.of()));
    }

    public boolean isEmpty(DockZone zone) {
        return zones.getOrDefault(zone, List.of()).isEmpty();
    }

    /** 这个区当前显示的面板；区是空的就返回 null。 */
    public @Nullable DockPanel active(DockZone zone) {
        List<DockPanel> list = zones.get(zone);
        if (list == null || list.isEmpty()) {
            return null;
        }
        DockPanel a = active.get(zone);
        return a != null && list.contains(a) ? a : list.get(0);
    }

    public void setActive(DockZone zone, DockPanel panel) {
        if (zones.getOrDefault(zone, List.of()).contains(panel)) {
            active.put(zone, panel);
        }
    }

    public DockZone zoneOf(DockPanel panel) {
        for (var e : zones.entrySet()) {
            if (e.getValue().contains(panel)) {
                return e.getKey();
            }
        }
        // normalize 保证不会走到这里；真走到了也给个能用的答案而不是抛
        return panel.home();
    }

    /**
     * 把面板挪到 {@code zone} 的第 {@code index} 位。
     *
     * @param index 目标位置；越界会被夹到末尾。传 -1 表示追加
     * @return 位置是否真的变了。拖动时每帧都会问，靠它避免无谓的重排与存盘
     */
    public boolean move(DockPanel panel, DockZone zone, int index) {
        DockZone from = zoneOf(panel);
        List<DockPanel> src = zones.get(from);
        List<DockPanel> dst = zones.computeIfAbsent(zone, k -> new ArrayList<>());
        int at = index < 0 ? dst.size() : Math.min(index, dst.size());
        if (from == zone && src.indexOf(panel) == at) {
            return false;
        }
        src.remove(panel);
        // 同区内前移时，移除自己会让目标下标整体左移一位
        int fixed = from == zone ? Math.min(at, dst.size()) : at;
        dst.add(fixed, panel);
        if (zone != DockZone.FLOATING) {
            floatRects.remove(panel);
        }
        active.put(zone, panel);
        // 原来那个区如果空了，选中项也没有意义了
        if (src.isEmpty()) {
            active.remove(from);
        }
        normalize();
        return true;
    }

    /** 浮动面板的矩形。没停在浮动区、或还没给过位置时返回 null。 */
    public int @Nullable [] floatRect(DockPanel panel) {
        return zoneOf(panel) == DockZone.FLOATING ? floatRects.get(panel) : null;
    }

    public void setFloatRect(DockPanel panel, int x, int y, int w, int h) {
        floatRects.put(panel, new int[]{x, y, w, h});
    }

    /**
     * 修复不变式：每个面板恰好一次。
     *
     * <p>重复的只留最先遇到的那个，缺失的送回 {@link DockPanel#home()}。
     * 读盘之后和每次改动之后都要跑——前者防手改坏的配置，后者防将来某个改动
     * 不小心把面板弄丢或弄重。
     */
    private void normalize() {
        Set<DockPanel> seen = new LinkedHashSet<>();
        for (DockZone z : DockZone.values()) {
            List<DockPanel> list = zones.computeIfAbsent(z, k -> new ArrayList<>());
            list.removeIf(p -> !seen.add(p));
        }
        for (DockPanel p : DockPanel.values()) {
            if (!seen.contains(p)) {
                zones.get(p.home()).add(p);
            }
        }
        floatRects.keySet().removeIf(p -> zoneOf(p) != DockZone.FLOATING);
    }

    // ---------------------------------------------------------------- 存盘

    public JsonObject toJson() {
        JsonObject root = new JsonObject();
        for (DockZone z : DockZone.values()) {
            JsonArray arr = new JsonArray();
            for (DockPanel p : zones.getOrDefault(z, List.of())) {
                arr.add(p.name());
            }
            root.add(key(z), arr);
        }
        JsonObject act = new JsonObject();
        for (var e : active.entrySet()) {
            act.addProperty(key(e.getKey()), e.getValue().name());
        }
        root.add("active", act);
        JsonObject rects = new JsonObject();
        for (var e : floatRects.entrySet()) {
            JsonArray r = new JsonArray();
            for (int v : e.getValue()) {
                r.add(v);
            }
            rects.add(e.getKey().name(), r);
        }
        // key 不能叫 "floating"：那个名字已经被 FLOATING 区的<b>面板列表</b>占了
        // （key(DockZone.FLOATING) 就是它）。撞名的后果是矩形把列表整个覆盖掉，
        // 于是浮动的面板存盘之后凭空消失、被 normalize 送回默认区——不报错，只是丢东西
        root.add("floatRects", rects);
        return root;
    }

    /** 读盘。任何一项读坏都只影响那一项，剩下的照常，最后由 normalize 兜底。 */
    public void fromJson(JsonObject root) {
        zones.clear();
        active.clear();
        floatRects.clear();
        for (DockZone z : DockZone.values()) {
            List<DockPanel> list = new ArrayList<>();
            JsonElement el = root.get(key(z));
            if (el != null && el.isJsonArray()) {
                for (JsonElement item : el.getAsJsonArray()) {
                    DockPanel p = parsePanel(item);
                    if (p != null) {
                        list.add(p);
                    }
                }
            }
            zones.put(z, list);
        }
        if (root.has("active") && root.get("active").isJsonObject()) {
            JsonObject act = root.getAsJsonObject("active");
            for (DockZone z : DockZone.values()) {
                DockPanel p = parsePanel(act.get(key(z)));
                if (p != null) {
                    active.put(z, p);
                }
            }
        }
        if (root.has("floatRects") && root.get("floatRects").isJsonObject()) {
            for (var e : root.getAsJsonObject("floatRects").entrySet()) {
                DockPanel p = parsePanel(e.getKey());
                if (p == null || !e.getValue().isJsonArray()) {
                    continue;
                }
                JsonArray r = e.getValue().getAsJsonArray();
                if (r.size() == 4) {
                    floatRects.put(p, new int[]{
                            r.get(0).getAsInt(), r.get(1).getAsInt(),
                            r.get(2).getAsInt(), r.get(3).getAsInt()});
                }
            }
        }
        normalize();
    }

    private static String key(DockZone zone) {
        return zone.name().toLowerCase(Locale.ROOT);
    }

    private static @Nullable DockPanel parsePanel(@Nullable JsonElement el) {
        return el == null || !el.isJsonPrimitive() ? null : parsePanel(el.getAsString());
    }

    /** 认不出的名字返回 null——多半是老配置里已经删掉的面板，忽略即可。 */
    private static @Nullable DockPanel parsePanel(@Nullable String name) {
        if (name == null) {
            return null;
        }
        for (DockPanel p : DockPanel.values()) {
            if (p.name().equals(name)) {
                return p;
            }
        }
        return null;
    }
}
