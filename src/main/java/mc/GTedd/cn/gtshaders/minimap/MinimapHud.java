package mc.GTedd.cn.gtshaders.minimap;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

/** One terrain texture, clipped in GUI space; markers use the same transform as the terrain. */
public final class MinimapHud {
    private static final int TEXT = 0xffedf3f6, MUTED = 0xffa4b8c7, ACCENT = 0xff76dfce;
    private MinimapHud() {}

    public static void renderHud(GuiGraphicsExtractor g, DeltaTracker delta) {
        Minecraft mc = Minecraft.getInstance();
        if (!MinimapRuntime.settings().enabled || mc.player == null || mc.level == null
                || mc.gui.hud == null || mc.gui.hud.isHidden() || mc.gui.screen() != null) return;
        int size = Math.min(MinimapRuntime.settings().size, Math.min(g.guiWidth() / 3, g.guiHeight() - 88));
        if (size < 64) return;
        // Leave the upper-right vanilla status-effect icons their own row.
        draw(g, g.guiWidth() - size - 10, 42, size, delta.getGameTimeDeltaPartialTick(false), true);
    }

    public static void draw(GuiGraphicsExtractor g, int x, int y, int size, float partial, boolean compact) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || !MinimapRuntime.ready()) return;
        var cfg = MinimapRuntime.settings();
        Vec3 player = mc.player.getPosition(partial);
        float yaw = mc.player.getViewYRot(partial);
        double angle = MinimapMath.rotation(yaw, cfg.headingUp);
        double scale = size / (2.0 * cfg.radius());
        double cx = x + size / 2.0, cy = y + size / 2.0;
        g.fill(x - 4, y - 17, x + size + 4, y + size + (compact ? 30 : 17), 0xe5101821);
        g.fill(x - 4, y - 17, x - 2, y + size + (compact ? 30 : 17), ACCENT);
        g.text(mc.font, MinimapRuntime.text(MinimapRuntime.slice() ? "slice" : "surface"), x, y - 12, TEXT, false);
        String radius = MinimapRuntime.meters(cfg.radius());
        g.text(mc.font, radius, x + size - mc.font.width(radius), y - 12, MUTED, false);
        g.enableScissor(x, y, x + size, y + size);
        g.fill(x, y, x + size, y + size, TerrainGrid.UNKNOWN);
        var grid = MinimapRuntime.grid();
        g.pose().pushMatrix();
        g.pose().translate((float) cx, (float) cy).rotate((float) angle);
        g.pose().scale((float) (scale * grid.step()));
        g.pose().translate((float) (grid.originX() - player.x / grid.step()),
                (float) (grid.originZ() - player.z / grid.step()));
        g.blit(RenderPipelines.GUI_TEXTURED, MinimapRuntime.TEXTURE, 0, 0, 0, 0,
                TerrainGrid.SIZE, TerrainGrid.SIZE, TerrainGrid.SIZE, TerrainGrid.SIZE);
        g.pose().popMatrix();

        // Draw far contacts first, leaving nearer faces readable in dense groups.
        var contacts = MinimapRuntime.contacts();
        if (cfg.entities) for (int i = contacts.size() - 1; i >= 0; i--) {
            Entity entity = contacts.get(i);
            if (entity.isRemoved() || !entity.isAlive() || entity.isInvisible()) continue;
            Vec3 p = entity.getPosition(partial);
            var q = MinimapMath.project(p.x - player.x, p.z - player.z, angle, scale);
            if (Math.max(Math.abs(q.x()), Math.abs(q.y())) > size / 2.0 - 7) continue;
            int color = entity instanceof Player ? 0xff73ccff : entity instanceof Enemy ? 0xffff6879 : 0xffa4e398;
            int ex = (int)(cx + q.x()), ey = (int)(cy + q.y());
            var icon = cfg.entityIcons ? MinimapIcons.of(entity) : null;
            if (icon == null) diamond(g, ex, ey, 2, color);
            else {
                int iconSize = compact ? 7 : 9;
                int left = ex-iconSize/2, top = ey-iconSize/2;
                g.fill(left-1, top-1, left+iconSize+1, top+iconSize+2, 0xd9101821);
                g.fill(left, top+iconSize, left+iconSize, top+iconSize+1, color);
                g.blitSprite(RenderPipelines.GUI_TEXTURED, icon, left, top, iconSize, iconSize);
            }
        }
        for (var p : MinimapRuntime.waypoints()) {
            if (!p.dimension().equals(MinimapRuntime.dimension())) continue;
            var q = MinimapMath.project(p.x() + 0.5 - player.x, p.z() + 0.5 - player.z, angle, scale);
            boolean outside = Math.max(Math.abs(q.x()), Math.abs(q.y())) > size / 2.0 - 6;
            var clipped = MinimapMath.clamp(q, size / 2.0 - 6);
            int px = (int) (cx + clipped.x()), py = (int) (cy + clipped.y());
            if (outside) arrow(g, px, py, Math.atan2(q.y(), q.x()) + Math.PI / 2, p.color());
            else diamond(g, px, py, 3, p.color());
            if (!compact && p.equals(MinimapRuntime.selectedWaypoint()))
                g.text(mc.font, mc.font.plainSubstrByWidth(p.name(), size / 2), px + 5, py - 4, p.color(), true);
        }
        arrow(g, (int) cx, (int) cy, Math.toRadians(yaw + 180) + angle, TEXT);
        g.disableScissor();
        g.outline(x - 1, y - 1, size + 2, size + 2, 0xff577080);
        String[] names = {MinimapRuntime.text("compass_n"), MinimapRuntime.text("compass_e"),
                MinimapRuntime.text("compass_s"), MinimapRuntime.text("compass_w")};
        double[][] directions = {{0, -1}, {1, 0}, {0, 1}, {-1, 0}};
        for (int i = 0; i < 4; i++) {
            var p = MinimapMath.project(directions[i][0], directions[i][1], angle, size / 2.0 - 6);
            var q = MinimapMath.clamp(p, size / 2.0 - 6);
            int tx = (int) (cx + q.x()) - mc.font.width(names[i]) / 2;
            int ty = (int) (cy + q.y()) - 4;
            g.text(mc.font, names[i], tx, ty, i == 0 ? ACCENT : TEXT, true);
        }
        String coords = (int) Math.floor(player.x) + "  " + (int) Math.floor(player.y) + "  " + (int) Math.floor(player.z);
        g.text(mc.font, mc.font.plainSubstrByWidth(coords, size), x, y + size + 5, TEXT, false);
        if (compact) {
            var point = MinimapRuntime.selectedWaypoint();
            String footer = point == null ? MinimapRuntime.keyHint() : point.name() + " "
                    + MinimapRuntime.meters(Math.round(Math.hypot(point.x() + .5 - player.x, point.z() + .5 - player.z)));
            g.text(mc.font, mc.font.plainSubstrByWidth(footer, size), x, y + size + 17,
                    point == null ? MUTED : point.color(), false);
        }
    }

    private static void diamond(GuiGraphicsExtractor g, int x, int y, int r, int color) {
        for (int row = -r - 1; row <= r + 1; row++) {
            int w = r + 1 - Math.abs(row);
            g.fill(x - w, y + row, x + w + 1, y + row + 1, 0xff101821);
        }
        for (int row = -r; row <= r; row++) {
            int w = r - Math.abs(row); g.fill(x - w, y + row, x + w + 1, y + row + 1, color);
        }
    }

    private static void arrow(GuiGraphicsExtractor g, int x, int y, double angle, int color) {
        g.pose().pushMatrix();
        g.pose().translate(x, y).rotate((float) angle);
        for (int row = 0; row < 8; row++) {
            int w = row / 2;
            g.fill(-w - 1, row - 5, w + 2, row - 4, 0xff101821);
            g.fill(-w, row - 5, w + 1, row - 4, color);
        }
        g.pose().popMatrix();
    }
}
