package mc.GTedd.cn.gtshaders.ui;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import mc.GTedd.cn.gtshaders.GTShaders;
import mc.GTedd.cn.gtshaders.workspace.Workspace;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 工作台布局，持久化到 {@code config/gtshaders/layout.json}。
 *
 * <p>调好的栏宽、缩放、面板位置属于「一次性投入、长期受益」的东西，每次打开都要重调一遍
 * 会非常恼人，所以整套状态都存盘。文件是可读 JSON，手改也行。
 */
public final class LayoutConfig {

    /**
     * 编辑器自己的缩放<b>档位</b>，1.0 就是界面上显示的 100%。
     *
     * <p>存在的理由是 Minecraft 的 GUI Scale 档位太粗、通常偏大，而工作台需要更高的信息密度。
     *
     * <p><b>它不是实际渲染倍数</b>——渲染时还要再乘 {@link #BASE_SCALE}，见 {@link #renderScale()}。
     * 分成两层是为了让「默认大小」和「用户调的百分比」可以各自变动：调默认只动 BASE_SCALE，
     * 界面上那个百分比数字不受影响，仍然从 100% 起步。
     *
     * <p>可选档位取决于有没有矢量字体：有的话按好用排（{@link #VECTOR_STEPS}）；
     * 没有的话只能给让净倍数为整数的那几档，否则位图字体会糊。见 {@link #scaleSteps}。
     */
    public float uiScale = 1.0f;

    /**
     * 100% 档对应的实际渲染倍数。
     *
     * <p>取 0.75：原先 100% 直接等于渲染倍数 1.0，实测下来信息密度偏低——工作台上同时要放
     * 代码区、参数面板、效果库和预览，1.0 会让侧栏挤掉代码区的可视行数。
     * 把基准压到 0.75 之后默认视野正好，而界面上仍然写 100%，
     * 因为对用户来说「默认」本来就该叫 100%，没必要让他看见一个 75%。
     *
     * <p>调这个值不影响档位表，也不影响存盘：{@code layout.json} 里存的一直是
     * {@link #uiScale} 那个档位。
     */
    public static final float BASE_SCALE = 0.75f;

    public int leftWidth = 192;
    public int rightWidth = 236;
    /** 底部停靠区的高度。默认 0 是因为出厂布局那里空着，有面板拖进去才会撑开。 */
    public int bottomHeight = 180;
    public boolean leftCollapsed;
    public boolean rightCollapsed;
    public boolean statusVisible = true;

    /**
     * 哪个面板停在哪。
     *
     * <p>和上面那几个尺寸分开放：尺寸是「区有多大」，它是「区里有谁」，
     * 两件事的改动频率和失效方式都不一样——尺寸拖坏了一眼看得见，
     * 归属弄丢了则是整块内容凭空消失。
     */
    public final DockLayout dock = new DockLayout();

    /**
     * 导出落点：自定义目录与文件名，空串表示「用默认的」。
     *
     * <p>放在布局配置里而不是跟着工程走，是因为它描述的是<b>这台机器</b>上的习惯——
     * 「我的包都往 D 盘那个整理好的文件夹里放」——换个工程并不会改变这件事。
     * 存进工程反而会让别人打开你的工程时，导出目录指向一个他机器上不存在的路径。
     *
     * <p>同样的理由要求它必须存盘：把路径指向 {@code resourcepacks/} 是一次性设置，
     * 每次打开编辑器重敲一遍绝对路径没有人受得了。
     */
    public String exportDir = "";
    public String exportName = "";

    public int panelX = -1;
    public int panelY = -1;
    public int panelWidth = 306;
    public int panelHeight = 330;
    public boolean panelVisible = true;

    public static final float MIN_SCALE = 0.5f;
    public static final float MAX_SCALE = 2.0f;
    public static final int MIN_SIDE = 130;
    public static final int MAX_SIDE = 420;
    public static final int MIN_BOTTOM = 90;
    public static final int MAX_BOTTOM = 480;

    /** 上一次算档位用的 Minecraft GUI Scale。切换游戏设置后要重算。 */
    private transient int cachedGuiScale;
    private transient boolean cachedVector;
    private transient float[] cachedSteps = {1.0f};

    /**
     * 矢量字体可用时的档位。
     *
     * <p>此时不再需要「净倍数必须是整数」那条约束——字体是按当前字号重新栅格化的，
     * 不存在缩放一张位图的问题。所以档位可以按<b>好用</b>来排，而不是按数学约束来排。
     *
     * <p>仍然分档而不是给连续值，即使入口已经换成了滑条：连续缩放意味着字号要跟着连续变，
     * 而每变一次都得重新烘焙一遍字形——拖动时会直接卡住。分档之后拖动全程最多烘焙十几次，
     * 而这十二档已经覆盖了从「塞得下更多内容」到「看得清」的全部实际需要。
     * 滑条上的刻度点画的就是这十二档。
     */
    private static final float[] VECTOR_STEPS = {
            0.5f, 0.6f, 0.7f, 0.8f, 0.9f, 1.0f, 1.1f, 1.25f, 1.4f, 1.6f, 1.8f, 2.0f};

    private static Path file() {
        return Workspace.rootDir().resolve("layout.json");
    }

    public static LayoutConfig load() {
        LayoutConfig c = new LayoutConfig();
        Path path = file();
        if (!Files.isRegularFile(path)) {
            return c;
        }
        try {
            JsonObject root = JsonParser.parseString(
                    Files.readString(path, StandardCharsets.UTF_8)).getAsJsonObject();
            c.uiScale = getFloat(root, "uiScale", c.uiScale);
            c.leftWidth = getInt(root, "leftWidth", c.leftWidth);
            c.rightWidth = getInt(root, "rightWidth", c.rightWidth);
            c.leftCollapsed = getBool(root, "leftCollapsed", c.leftCollapsed);
            c.rightCollapsed = getBool(root, "rightCollapsed", c.rightCollapsed);
            c.statusVisible = getBool(root, "statusVisible", c.statusVisible);
            c.panelX = getInt(root, "panelX", c.panelX);
            c.panelY = getInt(root, "panelY", c.panelY);
            c.panelWidth = getInt(root, "panelWidth", c.panelWidth);
            c.panelHeight = getInt(root, "panelHeight", c.panelHeight);
            c.panelVisible = getBool(root, "panelVisible", c.panelVisible);
            c.bottomHeight = getInt(root, "bottomHeight", c.bottomHeight);
            c.exportDir = getString(root, "exportDir", c.exportDir);
            c.exportName = getString(root, "exportName", c.exportName);
            if (root.has("dock") && root.get("dock").isJsonObject()) {
                c.dock.fromJson(root.getAsJsonObject("dock"));
            }
        } catch (IOException | RuntimeException e) {
            // 配置坏掉不该让编辑器打不开，退回默认布局即可
            GTShaders.LOGGER.warn("layout.json 读取失败，使用默认布局：{}", e.getMessage());
        }
        c.clamp();
        return c;
    }

    public void save() {
        clamp();
        JsonObject root = new JsonObject();
        root.addProperty("uiScale", uiScale);
        root.addProperty("leftWidth", leftWidth);
        root.addProperty("rightWidth", rightWidth);
        root.addProperty("leftCollapsed", leftCollapsed);
        root.addProperty("rightCollapsed", rightCollapsed);
        root.addProperty("statusVisible", statusVisible);
        root.addProperty("panelX", panelX);
        root.addProperty("panelY", panelY);
        root.addProperty("panelWidth", panelWidth);
        root.addProperty("panelHeight", panelHeight);
        root.addProperty("panelVisible", panelVisible);
        root.addProperty("bottomHeight", bottomHeight);
        root.addProperty("exportDir", exportDir);
        root.addProperty("exportName", exportName);
        root.add("dock", dock.toJson());
        try {
            Files.createDirectories(file().getParent());
            Files.writeString(file(),
                    new GsonBuilder().setPrettyPrinting().create().toJson(root) + "\n",
                    StandardCharsets.UTF_8);
        } catch (IOException e) {
            GTShaders.LOGGER.warn("layout.json 写入失败：{}", e.getMessage());
        }
    }

    /**
     * 实际渲染倍数 = 基准 × 档位。
     *
     * <p>所有真正用来缩放绘制、算命中区域、烘焙字号的地方都该用它，
     * 而不是直接读 {@link #uiScale}——后者只是给人看的那个百分比。
     */
    public float renderScale() {
        return BASE_SCALE * uiScale;
    }

    public void clamp() {
        uiScale = Math.max(MIN_SCALE, Math.min(MAX_SCALE, uiScale));
        leftWidth = Math.max(MIN_SIDE, Math.min(MAX_SIDE, leftWidth));
        rightWidth = Math.max(MIN_SIDE, Math.min(MAX_SIDE, rightWidth));
        bottomHeight = Math.max(MIN_BOTTOM, Math.min(MAX_BOTTOM, bottomHeight));
        panelWidth = Math.max(220, Math.min(560, panelWidth));
        panelHeight = Math.max(180, Math.min(720, panelHeight));
    }

    /**
     * 可用的缩放档位。
     *
     * <h3>为什么必须由 Minecraft 的 GUI Scale 算出来</h3>
     *
     * <p>屏幕上一个字符的实际放大倍数是 <b>{@code guiScale × uiScale}</b>。Minecraft 的字体是
     * 位图（ASCII 8×8、CJK 走 unifont 16×16），字形被烘焙进图集后按这个倍数采样贴到屏幕上。
     * 倍数是整数时，每个源像素正好落在整数个屏幕像素上，笔画清清楚楚；一旦是 1.7 这种值，
     * 同一根竖线有的地方占 2 个像素、有的占 1 个，横笔画干脆被采没——看上去就是「发虚」。
     *
     * <p>原来那张写死的表 {@code {0.6, 0.75, 0.85, 1.0, 1.15, 1.3, 1.6}} 里，只有 1.0 一档
     * 在常见的 guiScale=2 下能凑出整数倍。换句话说<b>一旦缩小界面就必然糊</b>，
     * 而这跟用哪套字体没有关系——换成任何位图字体结果一样。
     *
     * <p>所以档位改成 {@code k / guiScale}（k 为正整数）：净倍数恒为 k，永远是整数。
     * guiScale 越大可选档位越多，这也合理——高 GUI Scale 意味着屏幕像素更富余。
     *
     * <h3>有矢量字体时这条约束就不存在了</h3>
     *
     * <p>{@link UiFont} 能用时，文字是按当前字号从 TTF 重新栅格化的，不再是「缩放一张位图」，
     * 于是档位可以按好用来排（{@link #VECTOR_STEPS}）。这也是 Axiom 那类工具能给
     * 0.5~2 连续滑块的原因——它们用 imgui，而 imgui 同样是按 fontScale 重建字体图集。
     *
     * @param guiScale   Minecraft 当前的 GUI Scale，非整数时按最近的整数处理
     * @param vectorFont 是否有矢量字体可用
     */
    public float[] scaleSteps(double guiScale, boolean vectorFont) {
        int g = Math.max(1, (int) Math.round(guiScale));
        if (g == cachedGuiScale && vectorFont == cachedVector && cachedSteps.length > 1) {
            return cachedSteps;
        }
        // 没有矢量字体时，要让净倍数 guiScale × BASE_SCALE × uiScale 是整数，
        // 所以基准是 g × BASE_SCALE 而不是 g——漏乘的话算出来的档位在屏幕上仍是非整数倍，
        // 位图字体照糊不误
        cachedSteps = vectorFont
                ? VECTOR_STEPS
                : integerSteps(g * BASE_SCALE, MIN_SCALE, MAX_SCALE);
        cachedGuiScale = g;
        cachedVector = vectorFont;
        return cachedSteps;
    }

    /** 没有矢量字体时的档位。留给测试与不关心字体的调用方。 */
    public float[] scaleSteps(double guiScale) {
        return scaleSteps(guiScale, false);
    }

    /**
     * 在已有 {@code base} 倍放大的基础上，还能再乘哪些系数而保持净倍数是整数。
     *
     * <p>答案是 {@code m / base}（m 为正整数）：净倍数 {@code base × m / base = m}。
     *
     * <p>抽成静态方法是因为<b>缩放会叠加</b>：代码区的实际倍数是
     * {@code guiScale × uiScale × codeZoom}，三者相乘才是最终采样倍数。
     * 代码区的档位得以「前两者之积」为基准再算一遍，两处用同一个实现才不会算出两套规则。
     *
     * <p>{@code base} 是浮点的：界面用上矢量字体之后 {@code guiScale × uiScale} 不再保证是整数
     * （0.7 档下它是 1.4），而代码区仍然烧的是原版位图字体——那一层的整数约束还在，
     * 只是基准变成了一个小数。按整数处理会算出错误的档位，代码区反而糊掉。
     *
     * @param base 已有的放大倍数，可以不是整数
     * @return 递增的档位，至少含一项
     */
    public static float[] integerSteps(float base, float min, float max) {
        float b = Math.max(0.01f, base);
        List<Float> out = new ArrayList<>();
        for (int m = 1; m <= Math.ceil(b * max); m++) {
            float s = m / b;
            if (s >= min - 1e-4f && s <= max + 1e-4f) {
                out.add(s);
            }
        }
        if (out.isEmpty()) {
            // base 大到连 1/base 都超过上限时兜底。理论上不会发生，但不能返回空表
            out.add(1.0f);
        }
        float[] steps = new float[out.size()];
        for (int i = 0; i < steps.length; i++) {
            steps[i] = out.get(i);
        }
        return steps;
    }

    /** 当前 uiScale 在档位表里的下标；不在表上时取最近的一档。 */
    private int stepIndex(float[] steps) {
        int best = 0;
        float bestDiff = Float.MAX_VALUE;
        for (int i = 0; i < steps.length; i++) {
            float d = Math.abs(steps[i] - uiScale);
            if (d < bestDiff) {
                bestDiff = d;
                best = i;
            }
        }
        return best;
    }

    public void cycleScale(int delta, double guiScale, boolean vectorFont) {
        float[] steps = scaleSteps(guiScale, vectorFont);
        int next = Math.max(0, Math.min(steps.length - 1, stepIndex(steps) + delta));
        uiScale = steps[next];
    }

    /** 当前档位在档位表里的下标。滑条要靠它知道滑块该停在哪。 */
    public int currentStepIndex(double guiScale, boolean vectorFont) {
        return stepIndex(scaleSteps(guiScale, vectorFont));
    }

    /**
     * 直接跳到第 {@code index} 档。
     *
     * @return 档位是否真的变了。滑条拖动时每帧都会调进来，
     *         靠这个返回值决定要不要做那些「变了才需要做」的重活（重新烘焙字号、
     *         吸附代码区缩放）——不判的话拖一次滑条会烘焙上百次，直接卡住。
     */
    public boolean setScaleIndex(int index, double guiScale, boolean vectorFont) {
        float[] steps = scaleSteps(guiScale, vectorFont);
        float next = steps[Math.max(0, Math.min(steps.length - 1, index))];
        if (Math.abs(next - uiScale) < 1e-4f) {
            return false;
        }
        uiScale = next;
        return true;
    }

    /**
     * 滑轨上的归一化位置 {@code t}（0~1）落在第几档。
     *
     * <p>档位是<b>等距</b>摊在滑轨上的，不是按缩放值线性分布——档位表本身在数值上就不等距
     * （0.9 和 1.0 差 0.1，1.8 和 2.0 差 0.2），按数值分布会让高档位那头挤成一团。
     * 等距分布还有个好处：拖过去的手感是「一格一格」的，和刻度点对得上。
     */
    public static int indexAt(double t, int stepCount) {
        if (stepCount <= 1) {
            return 0;
        }
        double clamped = Math.max(0, Math.min(1, t));
        return (int) Math.round(clamped * (stepCount - 1));
    }

    /**
     * 滑条拖到了归一化位置 {@code t}。
     *
     * @return 档位是否真的变了，语义同 {@link #setScaleIndex}
     */
    public boolean setScaleAt(double t, double guiScale, boolean vectorFont) {
        return setScaleIndex(indexAt(t, scaleSteps(guiScale, vectorFont).length),
                guiScale, vectorFont);
    }

    /**
     * 把 uiScale 吸附到最近的合法档位。
     *
     * <p>两种情况需要：从旧版本的 {@code layout.json} 读到 0.85 这种老档位，
     * 以及玩家在游戏设置里改了 GUI Scale——后者会让原本清晰的比例变成非整数倍。
     */
    public void snapScale(double guiScale, boolean vectorFont) {
        float[] steps = scaleSteps(guiScale, vectorFont);
        uiScale = steps[stepIndex(steps)];
    }

    /** 净倍数为 1 的那一档，也就是「和游戏界面同样大小」。 */
    public void resetScale(double guiScale, boolean vectorFont) {
        uiScale = 1.0f;
        snapScale(guiScale, vectorFont);
    }

    public String scaleLabel() {
        return Math.round(uiScale * 100) + "%";
    }

    /** 提示气泡里说明当前档位在屏幕上的实际放大倍数——那个整数才是清不清晰的关键。 */
    public String scaleDetail(double guiScale) {
        int g = Math.max(1, (int) Math.round(guiScale));
        return Math.round(renderScale() * g) + "x";
    }

    private static float getFloat(JsonObject o, String k, float d) {
        return o.has(k) ? o.get(k).getAsFloat() : d;
    }

    private static int getInt(JsonObject o, String k, int d) {
        return o.has(k) ? o.get(k).getAsInt() : d;
    }

    private static boolean getBool(JsonObject o, String k, boolean d) {
        return o.has(k) ? o.get(k).getAsBoolean() : d;
    }

    /** 手改过的 layout.json 里这一项可能是 null 或数字，那种情况下当成没设过。 */
    private static String getString(JsonObject o, String k, String d) {
        return o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsString() : d;
    }
}
