package mc.GTedd.cn.gtshaders.ui;

import mc.GTedd.cn.gtshaders.i18n.GtLang;

/**
 * 一个可以被拖来拖去的面板。
 *
 * <p>原先这七块内容是写死的：三块只能在左栏、四块只能在右栏，靠 tab 切换。
 * 那套结构里「面板」这个概念根本不存在——渲染方法直接假设了自己画在哪一侧，
 * 左栏那三个甚至把 x 坐标写成了常量。想让它们能搬家，第一步就是先把
 * 「有哪些面板、它们叫什么、默认在哪」这件事变成数据。
 *
 * <p>枚举名会写进 {@code layout.json}，改名等于让老配置里的那一项失效
 * （失效的后果只是那个面板回到默认位置，不会炸），所以改名前想清楚。
 */
public enum DockPanel {

    LAYERS("gtshaders.left.tab.layers", DockZone.LEFT),
    ASSETS("gtshaders.left.tab.assets", DockZone.LEFT),
    VERSIONS("gtshaders.left.tab.versions", DockZone.LEFT),
    DESIGN("gtshaders.right.tab.design", DockZone.RIGHT),
    ANCHOR("gtshaders.right.tab.anchor", DockZone.RIGHT),
    PIPELINE("gtshaders.right.tab.pipeline", DockZone.RIGHT),
    DEBUG("gtshaders.right.tab.debug", DockZone.RIGHT),
    EXPORT("gtshaders.right.tab.export", DockZone.RIGHT);

    private final String titleKey;
    private final DockZone home;

    DockPanel(String titleKey, DockZone home) {
        this.titleKey = titleKey;
        this.home = home;
    }

    /** 标签上显示的名字。跟着语言走，所以每次都重新取而不是缓存。 */
    public String title() {
        return GtLang.get(titleKey);
    }

    /** 出厂默认待的那个区。重置布局时用得上。 */
    public DockZone home() {
        return home;
    }
}
