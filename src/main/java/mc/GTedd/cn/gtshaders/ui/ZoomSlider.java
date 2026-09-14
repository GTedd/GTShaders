package mc.GTedd.cn.gtshaders.ui;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * 顶栏那个界面缩放控件。
 *
 * <h2>为什么是滑条而不是加减号</h2>
 *
 * <p>缩放是个「一眼看出自己在哪、顺手拖到想要的地方」的操作。加减号只回答得了
 * 「再大一点」，回答不了「离最大还有多远」——而档位数量本来就随 GUI Scale 变，
 * 用户连总共几档都不知道，只能一下一下试。滑条把整个可选范围一次摊开，
 * 刻度点直接告诉他有几档、当前在第几档。
 *
 * <h2>为什么滑块会滑，界面却是跳的</h2>
 *
 * <p>这不是没做完，是刻意的：档位之间的中间值<b>不能</b>拿去渲染。
 * 位图字体要求 {@code guiScale × BASE_SCALE × uiScale} 是整数倍，
 * 停在两档中间就等于让整个界面发虚；而且每变一次实际缩放都要重新烘焙一次字号，
 * 拖动时每帧烘焙会直接卡住。
 *
 * <p>所以动画只作用在<b>这个控件自己</b>身上：滑块平滑滑过去、百分比数字滚过去，
 * 而界面在跨档的那一瞬间整体切换。手感上是连续的，渲染上仍然只用合法档位。
 *
 * <h2>动效都用时间驱动，不依赖 tick</h2>
 *
 * <p>这个控件画在 {@code render} 里，拿不到 tick 回调，所以每一项动效都按
 * 两帧之间的真实间隔做指数逼近。好处是帧率高低不影响动画速度，
 * 掉帧时也只是步子变大，不会变慢。
 */
final class ZoomSlider {

    /** 指数逼近的速度。约 55ms 走完大半程——跟手，又不会看起来在瞬移。 */
    private static final float FOLLOW_SPEED = 18f;
    /** hover / 按下这类状态切换得更快一点，否则鼠标划过去半天没反应。 */
    private static final float STATE_SPEED = 14f;
    /** 跨档时那圈扩散环的持续时间。 */
    private static final long SNAP_PULSE_MS = 220;

    private static final int TRACK_H = 3;
    private static final int KNOB_R = 4;
    /** 右侧留给百分比数字的宽度。"200%" 是最长的情况。 */
    private static final int LABEL_W = 32;

    /** 当前显示位置，单位是<b>档位索引</b>而不是缩放值——档位在数值上不等距，按索引插值滑块才匀速。 */
    private float animIndex = -1f;
    /** 显示用的缩放值，跟着 animIndex 插值出来，让百分比数字滚动而不是跳变。 */
    private float animScale = -1f;
    private long lastFrameAt;

    private float hover;
    private float press;

    /** 上一次跨档的时刻与落点，用来画那圈扩散环。 */
    private long snapAt;
    private int snapIndex = -1;

    /**
     * 画出来。
     *
     * @param steps    当前可选的档位表
     * @param index    当前档位在表里的下标
     * @param hovering 鼠标是否在控件上
     * @param dragging 是否正在拖这个滑条
     * @return 滑轨的命中矩形 {x, y, w, h}，调用方拿它登记 region
     */
    int[] render(GuiGraphicsExtractor g, Font font, int x, int y, int w, int h,
                 float[] steps, int index, String label, boolean hovering, boolean dragging) {
        float dt = tick();
        int last = Math.max(0, steps.length - 1);
        int target = Math.max(0, Math.min(last, index));

        if (animIndex < 0f) {
            // 首次绘制：直接落位，不要从 0 滑过来
            animIndex = target;
            animScale = steps[target];
        }
        // 档位表可能因为 GUI Scale 改了而变短，先夹回来免得滑块飞出轨道
        animIndex = Math.min(animIndex, last);
        if (target != snapIndex) {
            snapIndex = target;
            snapAt = System.currentTimeMillis();
        }
        animIndex = approach(animIndex, target, dt, FOLLOW_SPEED);
        animScale = approach(animScale, steps[target], dt, FOLLOW_SPEED);
        hover = approach(hover, hovering || dragging ? 1f : 0f, dt, STATE_SPEED);
        press = approach(press, dragging ? 1f : 0f, dt, STATE_SPEED);

        int trackX = x + KNOB_R + 2;
        int trackW = Math.max(8, w - LABEL_W - (KNOB_R + 2) * 2);
        int trackY = y + (h - TRACK_H) / 2;
        int travel = trackW - 1;
        float t = last == 0 ? 0f : animIndex / last;
        int knobCx = trackX + Math.round(travel * t);

        drawContainer(g, x, y, w, h);
        UiUtil.roundRect(g, trackX, trackY, trackW, TRACK_H, TRACK_H / 2, Theme.SLIDER_TRACK);
        int fillW = Math.max(TRACK_H, knobCx - trackX + 1);
        UiUtil.roundRect(g, trackX, trackY, fillW, TRACK_H, TRACK_H / 2, Theme.ACCENT);

        drawTicks(g, steps.length, trackX, travel, trackY, knobCx);
        drawSnapPulse(g, trackX, travel, last, trackY);
        drawKnob(g, knobCx, trackY + TRACK_H / 2);

        // 数字跟着 animScale 走，于是它是滚过去的。四舍五入到整数百分比，
        // 动画停下时正好等于 layout.scaleLabel()，不会出现「停住了但数字对不上」
        String shown = animating() ? Math.round(animScale * 100) + "%" : label;
        UiUtil.textRight(g, font, shown, x + w - 4,
                y + (h - font.lineHeight) / 2 + 1,
                dragging ? Theme.ACCENT : Theme.TEXT);

        return new int[]{trackX - KNOB_R, y, trackW + KNOB_R * 2, h};
    }

    /** 容器底：hover 时微微浮起来一点，给「这里可以动」一个暗示。 */
    private void drawContainer(GuiGraphicsExtractor g, int x, int y, int w, int h) {
        UiUtil.roundRect(g, x, y, w, h, 4,
                lerpColor(Theme.PANEL_ALT, Theme.PANEL, hover));
        UiUtil.roundOutline(g, x, y, w, h, 4,
                lerpColor(Theme.BORDER, Theme.ACCENT_SOFT, hover));
    }

    /**
     * 刻度点：有几档就有几个点。
     *
     * <p>已经走过的点用强调色、没走到的用灰色，于是「当前在第几档」不用数也看得出来。
     * 档位密到画不下时（间距不足 3px）整体不画——一排糊在一起的点比没有点更难看。
     */
    private void drawTicks(GuiGraphicsExtractor g, int count, int trackX, int travel,
                           int trackY, int knobCx) {
        if (count < 2 || travel / (count - 1) < 3) {
            return;
        }
        int cy = trackY + TRACK_H / 2;
        for (int i = 0; i < count; i++) {
            int cx = trackX + Math.round((float) travel * i / (count - 1));
            // 滑块正压着的那个点不画，否则会从滑块中心透出来一个小色斑
            if (Math.abs(cx - knobCx) <= KNOB_R) {
                continue;
            }
            boolean passed = cx <= knobCx;
            int color = passed
                    ? withAlpha(Theme.TEXT_ON_ACCENT, 0.55f)
                    : lerpColor(Theme.BORDER_STRONG, Theme.TEXT_DIM, hover);
            g.fill(cx, cy - 1, cx + 1, cy + 1, color);
        }
    }

    /** 跨档瞬间在落点上扩一圈，让「咔哒吸附」这件事看得见。 */
    private void drawSnapPulse(GuiGraphicsExtractor g, int trackX, int travel, int last, int trackY) {
        long age = System.currentTimeMillis() - snapAt;
        if (snapIndex < 0 || age >= SNAP_PULSE_MS) {
            return;
        }
        float p = age / (float) SNAP_PULSE_MS;
        int cx = trackX + (last == 0 ? 0 : Math.round((float) travel * snapIndex / last));
        int cy = trackY + TRACK_H / 2;
        int r = Math.round(KNOB_R + 1 + p * 5);
        UiUtil.ring(g, cx, cy, r, r, withAlpha(Theme.ACCENT, (1f - p) * 0.45f));
    }

    /** 滑块：外圈光晕 + 白底 + 强调色内核。按下时整体略微鼓一点。 */
    private void drawKnob(GuiGraphicsExtractor g, int cx, int cy) {
        float grow = hover * 0.6f + press * 0.9f;
        int glow = Math.round(KNOB_R + 2 + grow * 2);
        if (hover > 0.01f) {
            circle(g, cx, cy, glow, withAlpha(Theme.ACCENT, hover * 0.18f));
        }
        int outer = KNOB_R + Math.round(grow);
        circle(g, cx, cy, outer, Theme.PANEL);
        UiUtil.ring(g, cx, cy, outer, outer, lerpColor(Theme.BORDER_STRONG, Theme.ACCENT, hover));
        circle(g, cx, cy, Math.max(1, outer - 2), Theme.ACCENT);
    }

    /** 还在动画中途。数字要不要滚就看它。 */
    private boolean animating() {
        return Math.abs(animScale - Math.round(animScale * 100) / 100f) > 1e-4f
                || System.currentTimeMillis() - snapAt < 260;
    }

    /** 两帧之间的真实间隔，单位秒。夹上限是为了让切回窗口的那一帧不要瞬移。 */
    private float tick() {
        long now = System.currentTimeMillis();
        float dt = lastFrameAt == 0L ? 1f / 60f : (now - lastFrameAt) / 1000f;
        lastFrameAt = now;
        return Math.max(0f, Math.min(0.1f, dt));
    }

    /** 指数逼近：每秒把差距缩小固定比例，与帧率无关。 */
    private static float approach(float current, float target, float dt, float speed) {
        float k = 1f - (float) Math.exp(-dt * speed);
        float next = current + (target - current) * k;
        return Math.abs(target - next) < 1e-3f ? target : next;
    }

    private static void circle(GuiGraphicsExtractor g, int cx, int cy, int r, int color) {
        int d = Math.max(1, r * 2);
        UiUtil.roundRect(g, cx - r, cy - r, d, d, d / 2, color);
    }

    private static int lerpColor(int a, int b, float t) {
        float k = Math.max(0f, Math.min(1f, t));
        int out = 0;
        for (int shift = 0; shift < 32; shift += 8) {
            int ca = (a >>> shift) & 0xFF;
            int cb = (b >>> shift) & 0xFF;
            out |= (Math.round(ca + (cb - ca) * k) & 0xFF) << shift;
        }
        return out;
    }

    private static int withAlpha(int argb, float a) {
        int alpha = Math.round(((argb >>> 24) & 0xFF) * Math.max(0f, Math.min(1f, a)));
        return (alpha << 24) | (argb & 0x00FFFFFF);
    }
}
