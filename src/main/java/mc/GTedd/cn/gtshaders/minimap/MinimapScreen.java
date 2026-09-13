package mc.GTedd.cn.gtshaders.minimap;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import java.util.function.Supplier;

/** Expanded map and everyday controls, usable at the minimum vanilla GUI size. */
public final class MinimapScreen extends Screen {
    private int mapX, mapY, mapSize, controlsX, controlsY, controlsW;
    public MinimapScreen() { super(Component.literal(MinimapRuntime.text("title"))); }
    @Override public boolean isPauseScreen() { return false; }

    @Override protected void init() {
        boolean wide = false;
        mapSize = Math.min(height - 88, width - 194);
        mapSize = Math.max(48, mapSize);
        mapX = Math.max(12, (width - mapSize - 174) / 2);
        mapY = 42;
        controlsX = mapX + mapSize + 16;
        controlsY = 42;
        controlsW = 72;
        var cfg = MinimapRuntime.settings();
        control(0, wide, () -> label("visible", cfg.enabled), () -> cfg.enabled = !cfg.enabled);
        control(1, wide, () -> MinimapRuntime.text(cfg.headingUp ? "heading_up" : "north_up"), () -> cfg.headingUp = !cfg.headingUp);
        control(2, wide, () -> label("entities", cfg.entities), () -> cfg.entities = !cfg.entities);
        control(3, wide, () -> MinimapRuntime.text(cfg.slice ? "slice_short" : wide ? "surface_auto" : "surface_short"), () -> cfg.slice = !cfg.slice);
        control(4, wide, () -> MinimapRuntime.text("zoom_in"), () -> cfg.zoom = Math.max(0, cfg.zoom - 1));
        control(5, wide, () -> MinimapRuntime.text("zoom_out"), () -> cfg.zoom = Math.min(3, cfg.zoom + 1));
        control(6, wide, () -> MinimapRuntime.text(wide ? "add" : "add_short"), () -> {
            if (minecraft.player != null) minecraft.gui.setScreen(new WaypointScreen(this, minecraft.player.blockPosition()));
        });
        control(7, wide, () -> MinimapRuntime.text(wide ? "next" : "next_short"), MinimapRuntime::nextWaypoint);
        control(8, wide, () -> MinimapRuntime.text(wide ? "delete" : "delete_short"), () -> {
            if (MinimapRuntime.selectedWaypoint() != null)
                minecraft.gui.setScreen(new net.minecraft.client.gui.screens.ConfirmScreen(confirmed -> {
                    if (confirmed) MinimapRuntime.removeSelected();
                    minecraft.gui.setScreen(this);
                }, Component.literal(MinimapRuntime.text("delete")), Component.literal(MinimapRuntime.selectedWaypoint().name()),
                        Component.literal(MinimapRuntime.text("delete_confirm")), CommonComponents.GUI_CANCEL));
        });
        control(9, wide, () -> MinimapRuntime.text(cfg.entityIcons ? "icons_on" : "icons_off"), () -> cfg.entityIcons = !cfg.entityIcons);
        control(10, wide, () -> MinimapRuntime.text("export_packs"), MinimapRuntime::exportPortable);
        control(11, wide, () -> MinimapRuntime.text("done"), this::onClose);
    }

    private String label(String key, boolean state) {
        return MinimapRuntime.text(key) + ": " + MinimapRuntime.text(state ? "on" : "off");
    }

    private void control(int i, boolean wide, Supplier<String> label, Runnable action) {
        int x = controlsX + (wide ? 0 : i % 2 * (controlsW + 8));
        int y = controlsY + (wide ? i : i / 2) * 21;
        addRenderableWidget(Button.builder(Component.literal(label.get()), button -> {
            action.run(); MinimapRuntime.saveSettings(); button.setMessage(Component.literal(label.get()));
        }).bounds(x, y, controlsW, 20).build());
    }

    @Override public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float partial) {
        g.fill(0, 0, width, height, 0xe80b121c);
        g.centeredText(font, title, width / 2, 10, 0xffedf3f6);
        if (minecraft.player != null) MinimapHud.draw(g, mapX, mapY, mapSize, partial, false);
        if (height >= 220) {
            var p = MinimapRuntime.selectedWaypoint();
            String line = p == null ? MinimapRuntime.text("click_hint") : p.name() + "  " + p.x() + ", " + p.z()
                    + " · " + MinimapRuntime.meters(Math.round(Math.hypot(p.x()+.5-minecraft.player.getX(), p.z()+.5-minecraft.player.getZ())));
            g.text(font, font.plainSubstrByWidth(line, width - 32), 16, height - 28, 0xffffd77a);
            g.text(font, MinimapRuntime.text("legend"), 16, height - 15, 0xffa4b8c7);
        }
        super.extractRenderState(g, mx, my, partial);
    }

    @Override public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (event.button() == InputConstants.MOUSE_BUTTON_LEFT && event.x() >= mapX && event.x() < mapX + mapSize
                && event.y() >= mapY && event.y() < mapY + mapSize && minecraft.player != null) {
            var player = minecraft.player;
            var cfg = MinimapRuntime.settings();
            var q = MinimapMath.unproject(event.x() - mapX - mapSize / 2.0, event.y() - mapY - mapSize / 2.0,
                    MinimapMath.rotation(player.getViewYRot(1), cfg.headingUp), mapSize / (2.0 * cfg.radius()));
            minecraft.gui.setScreen(new WaypointScreen(this, BlockPos.containing(player.getX() + q.x(), player.getY(), player.getZ() + q.y())));
            return true;
        }
        return super.mouseClicked(event, doubleClick);
    }
}
