package mc.GTedd.cn.gtshaders.llm;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import mc.GTedd.cn.gtshaders.i18n.GtLang;

/**
 * 语言模型的控制面板：敲一句开头，按开始，字就一帧一个地长在画面上。
 *
 * <p>刻意做得很小。这里只负责三件事——把话切成 token、定生成多少个、开关，
 * 剩下的全在显卡上：{@link LlmRuntime} 从头到尾不知道模型写了什么。
 *
 * <p>资源包没装时不隐藏面板，而是把原因写在上面。装包这一步在别处（下载 zip、丢进
 * resourcepacks、启用），玩家最需要的信息是「为什么按了没反应」。
 */
public final class LlmScreen extends Screen {

    private final Screen parent;
    private EditBox promptBox;
    private String notice = "";

    public LlmScreen(Screen parent) {
        super(Component.literal(GtLang.get("gtshaders.llm.title")));
        this.parent = parent;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    protected void init() {
        String value = promptBox == null ? LlmRuntime.lastPrompt() : promptBox.getValue();
        if (value.isEmpty()) {
            value = GtLang.get("gtshaders.llm.default_prompt");
        }
        int x = width / 2 - 150;
        int y = height / 2 - 20;

        promptBox = addRenderableWidget(new EditBox(font, x, y, 300, 20, title));
        promptBox.setMaxLength(200);
        promptBox.setValue(value);
        setInitialFocus(promptBox);

        addRenderableWidget(Button.builder(
                Component.literal(GtLang.get("gtshaders.llm.start")), b -> {
                    int n = LlmRuntime.start(promptBox.getValue());
                    notice = n > 0
                            ? GtLang.get("gtshaders.llm.started", n, LlmRuntime.generateCount())
                            : GtLang.get("gtshaders.llm.no_pack");
                    if (n > 0) {
                        onClose();
                    }
                }).bounds(x, y + 28, 146, 20).build());

        addRenderableWidget(Button.builder(
                Component.literal(GtLang.get("gtshaders.llm.stop")), b -> {
                    LlmRuntime.stop();
                    notice = GtLang.get("gtshaders.llm.stopped");
                }).bounds(x + 154, y + 28, 146, 20).build());

        // 生成长度按档位切换：滑条在这里没有意义，玩家只关心「短一点还是长一点」
        addRenderableWidget(Button.builder(
                Component.literal(GtLang.get("gtshaders.llm.length", LlmRuntime.generateCount())),
                b -> {
                    int n = LlmRuntime.generateCount();
                    int next = n >= 200 ? 40 : n >= 120 ? 200 : n >= 80 ? 120 : 80;
                    LlmRuntime.setGenerateCount(next);
                    b.setMessage(Component.literal(
                            GtLang.get("gtshaders.llm.length", LlmRuntime.generateCount())));
                }).bounds(x, y + 52, 300, 20).build());
    }

    @Override
    public void onClose() {
        minecraft.gui.setScreen(parent);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float partial) {
        g.fill(0, 0, width, height, 0xeb0b121c);
        g.centeredText(font, title, width / 2, height / 2 - 62, 0xffedf3f6);
        String state = LlmRuntime.packInstalled()
                ? GtLang.get("gtshaders.llm.ready")
                : GtLang.get("gtshaders.llm.no_pack");
        g.centeredText(font, state, width / 2, height / 2 - 44, 0xffa4b8c7);
        if (!notice.isEmpty()) {
            g.centeredText(font, notice, width / 2, height / 2 + 56, 0xff8fd3a0);
        }
        super.extractRenderState(g, mx, my, partial);
    }
}
