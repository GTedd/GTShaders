package mc.GTedd.cn.gtshaders.core;

/**
 * 效果作用区域：画布上那个可拖动的取景框。
 *
 * <p>内部用<b>屏幕归一化坐标</b>存（0..1，y 向下，与 GUI 坐标系一致），因为它主要是被界面
 * 拖来拖去的；只有在往着色器里传的时候才转成 GL 纹理坐标（{@link #toGlUv()}）。
 *
 * <p>这个 y 轴翻转是必须的，而且极易出错：原版 {@code core/screenquad.vsh} 里
 * {@code texCoord = uv}，而 {@code gl_Position = uv * 2 - 1}，所以 {@code texCoord.y == 0}
 * 对应的是<b>屏幕底部</b>；GUI 坐标里 y=0 却是顶部。两者不翻转就会出现「框在上面、
 * 效果在下面」这种查半天的问题。
 */
public final class ViewportRect {

    /** 拖动时允许的最小边长（归一化）。太小的话框会缩成一个点，再也抓不住。 */
    private static final float MIN_SIZE = 0.02f;

    private float x0;
    private float y0;
    private float x1 = 1f;
    private float y1 = 1f;

    public float x0() {
        return x0;
    }

    public float y0() {
        return y0;
    }

    public float x1() {
        return x1;
    }

    public float y1() {
        return y1;
    }

    public float width() {
        return x1 - x0;
    }

    public float height() {
        return y1 - y0;
    }

    public boolean isFullScreen() {
        return x0 <= 0.0005f && y0 <= 0.0005f && x1 >= 0.9995f && y1 >= 0.9995f;
    }

    public void reset() {
        x0 = 0f;
        y0 = 0f;
        x1 = 1f;
        y1 = 1f;
    }

    public void set(float nx0, float ny0, float nx1, float ny1) {
        x0 = clamp01(Math.min(nx0, nx1));
        y0 = clamp01(Math.min(ny0, ny1));
        x1 = clamp01(Math.max(nx0, nx1));
        y1 = clamp01(Math.max(ny0, ny1));
        enforceMinSize();
    }

    /** 整体平移，越界时贴边而不是被裁小——拖出屏幕再拖回来时尺寸不该变。 */
    public void translate(float dx, float dy) {
        float w = width();
        float h = height();
        float nx0 = clamp(x0 + dx, 0f, 1f - w);
        float ny0 = clamp(y0 + dy, 0f, 1f - h);
        x0 = nx0;
        y0 = ny0;
        x1 = nx0 + w;
        y1 = ny0 + h;
    }

    /**
     * 拖动某条边或某个角。
     *
     * @param edgeX -1 拖左边、+1 拖右边、0 不动
     * @param edgeY -1 拖上边、+1 拖下边、0 不动
     */
    public void dragEdge(int edgeX, int edgeY, float nx, float ny) {
        if (edgeX < 0) {
            x0 = clamp(nx, 0f, x1 - MIN_SIZE);
        } else if (edgeX > 0) {
            x1 = clamp(nx, x0 + MIN_SIZE, 1f);
        }
        if (edgeY < 0) {
            y0 = clamp(ny, 0f, y1 - MIN_SIZE);
        } else if (edgeY > 0) {
            y1 = clamp(ny, y0 + MIN_SIZE, 1f);
        }
    }

    /**
     * 以某个锚点为中心缩放。滚轮缩放时锚点取鼠标位置，这样「往哪指就往哪缩」，
     * 和设计工具里滚轮缩放画布的手感一致。
     */
    public void zoomAround(float anchorX, float anchorY, float factor) {
        float nx0 = anchorX + (x0 - anchorX) * factor;
        float ny0 = anchorY + (y0 - anchorY) * factor;
        float nx1 = anchorX + (x1 - anchorX) * factor;
        float ny1 = anchorY + (y1 - anchorY) * factor;
        if (nx1 - nx0 < MIN_SIZE || ny1 - ny0 < MIN_SIZE) {
            return;
        }
        x0 = clamp01(nx0);
        y0 = clamp01(ny0);
        x1 = clamp01(nx1);
        y1 = clamp01(ny1);
        enforceMinSize();
    }

    /**
     * 转成着色器要的 GL 纹理坐标 {@code (u0, v0, u1, v1)}。
     * y 轴在这里翻转，见类注释。
     */
    public float[] toGlUv() {
        return new float[]{x0, 1f - y1, x1, 1f - y0};
    }

    public ViewportRect copy() {
        ViewportRect c = new ViewportRect();
        c.x0 = x0;
        c.y0 = y0;
        c.x1 = x1;
        c.y1 = y1;
        return c;
    }

    private void enforceMinSize() {
        if (x1 - x0 < MIN_SIZE) {
            x1 = Math.min(1f, x0 + MIN_SIZE);
            x0 = x1 - MIN_SIZE;
        }
        if (y1 - y0 < MIN_SIZE) {
            y1 = Math.min(1f, y0 + MIN_SIZE);
            y0 = y1 - MIN_SIZE;
        }
    }

    private static float clamp01(float v) {
        return clamp(v, 0f, 1f);
    }

    private static float clamp(float v, float lo, float hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
