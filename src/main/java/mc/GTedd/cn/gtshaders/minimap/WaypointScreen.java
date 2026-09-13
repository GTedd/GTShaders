package mc.GTedd.cn.gtshaders.minimap;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

public final class WaypointScreen extends Screen {
    private final Screen parent;
    private final BlockPos position;
    private EditBox name;
    public WaypointScreen(Screen parent, BlockPos position) {
        super(Component.literal(MinimapRuntime.text("add"))); this.parent = parent; this.position = position;
    }
    @Override public boolean isPauseScreen() { return false; }
    @Override protected void init() {
        String value = name == null ? MinimapRuntime.text("waypoint") : name.getValue();
        int x = width / 2 - 120, y = height / 2 - 12;
        name = addRenderableWidget(new EditBox(font, x, y, 240, 20, title));
        name.setMaxLength(40); name.setValue(value); setInitialFocus(name);
        addRenderableWidget(Button.builder(Component.literal(MinimapRuntime.text("save")), b -> {
            if (MinimapRuntime.addWaypoint(name.getValue(), position.getX(), position.getY(), position.getZ(), false)) onClose();
        }).bounds(x, y + 32, 116, 20).build());
        addRenderableWidget(Button.builder(CommonComponents.GUI_CANCEL, b -> onClose())
                .bounds(x + 124, y + 32, 116, 20).build());
    }
    @Override public void onClose() { minecraft.gui.setScreen(parent); }
    @Override public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float partial) {
        g.fill(0, 0, width, height, 0xeb0b121c);
        g.centeredText(font, title, width / 2, height / 2 - 52, 0xffedf3f6);
        g.centeredText(font, position.getX() + " / " + position.getY() + " / " + position.getZ(), width / 2,
                height / 2 - 32, 0xffa4b8c7);
        super.extractRenderState(g, mx, my, partial);
    }
}
