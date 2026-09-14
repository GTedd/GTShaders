package mc.GTedd.cn.gtshaders.ui;

import com.mojang.blaze3d.font.GlyphProvider;
import com.mojang.blaze3d.font.TrueTypeGlyphProvider;
import com.mojang.blaze3d.platform.TextureUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GlyphSource;
import net.minecraft.client.gui.font.FontOption;
import net.minecraft.client.gui.font.FontSet;
import net.minecraft.client.gui.font.GlyphStitcher;
import net.minecraft.client.gui.font.glyphs.EffectGlyph;
import net.minecraft.client.gui.font.providers.FreeTypeUtil;
import net.minecraft.network.chat.FontDescription;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.freetype.FT_Face;
import org.lwjgl.util.freetype.FreeType;
import mc.GTedd.cn.gtshaders.GTShaders;
import mc.GTedd.cn.gtshaders.workspace.Workspace;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * 工作台的矢量字体：按当前缩放<b>重新栅格化</b>，而不是把一张位图放大缩小。
 *
 * <h2>要解决的问题</h2>
 *
 * <p>Minecraft 的默认字体是位图（ASCII 8×8、CJK 走 unihex 16×16）。字形烘焙进图集后
 * 按缩放倍数采样贴到屏幕上，倍数不是整数时同一个字里笔画忽粗忽细——看上去就是发虚。
 * 于是编辑器的缩放只能给几个整数倍档位，而那对一个信息密集的工作台来说太粗了。
 *
 * <h2>为什么不引入 imgui</h2>
 *
 * <p>Axiom 那类工具用 Dear ImGui，它的字体清晰是因为
 * {@code RegisterImGuiFontsEvent(atlas, font, fontScale)} —— <b>按缩放重建整张字体图集</b>，
 * 每个字号都是从 TTF 原生栅格化出来的。
 *
 * <p>但这件事不需要 imgui：<b>Minecraft 自己就带着同一套能力</b>。
 * {@link TrueTypeGlyphProvider} 用 FreeType 栅格化，{@code size} 是浮点、还有 {@code oversample}，
 * 和 imgui 的机制是一回事，只是栅格化器一个用 FreeType 一个用 stb_truetype。
 * 而引入 imgui 要重写全部界面代码，还得对付 1.21.6+ 那套两阶段 GUI 渲染
 * （先 {@code prepare} 提交到 {@code GuiRenderState} 再统一排序绘制）——imgui 是立即模式，
 * 只能整个绕开去自己管 GL 状态。拿那个代价换一个已经能用别的办法拿到的东西，不划算。
 *
 * <h2>字体从哪来</h2>
 *
 * <p>不打包任何字体：一份覆盖 CJK 的字体 5~20MB，会让 jar 涨十几倍，而它并不解决别的问题。
 * 按顺序找：
 *
 * <ol>
 *   <li>{@code config/gtshaders/font/} 下玩家自己放的 ttf/otf/ttc</li>
 *   <li>系统字体目录里的常见中文字体（Windows 雅黑、macOS 苹方、Linux Noto）</li>
 *   <li>都没有 → {@link #isAvailable()} 返回 false，整个界面退回原版位图字体与整数倍档位</li>
 * </ol>
 *
 * <p>第三条是<b>必须能走通</b>的路径：字体是锦上添花，任何一步失败都只是少一点清晰度，
 * 绝不能让编辑器打不开。所以这里所有异常都就地吞掉并记一行日志。
 *
 * <h2>线程与生命周期</h2>
 *
 * <p>{@link FontSet#reload} 会建纹理，只能在渲染线程调用。字体按<b>物理像素字号</b>缓存，
 * 缓存上限见 {@link #MAX_CACHED}——每个字号是一张字形图集，无上限地留着等于慢慢吃显存。
 */
public final class UiFont {

    /** 逻辑基准字号。11 是 Mojang 自己给 ttf 示例用的值，对应 lineHeight 9 的观感。 */
    public static final float BASE_SIZE = 11.0f;

    /** 同时留几个字号。缩放档位就那么几档，超过说明有人在疯狂拖滑块，淘汰最早的即可。 */
    private static final int MAX_CACHED = 6;

    /** 字号的上下限（Minecraft 逻辑单位）。太小认不出字，太大是在浪费显存。 */
    private static final float MIN_SIZE = 4f;
    private static final float MAX_SIZE = 48f;

    /**
     * 一份烘焙参数。
     *
     * <h3>两个数各自是什么单位</h3>
     *
     * <p>{@code size} 是 <b>Minecraft 逻辑单位</b>——字形按它排版，最终还要再乘一次
     * 游戏的 GUI Scale 才落到物理像素上。所以想让屏幕上的字高固定，size 只能带上编辑器
     * 自己的缩放，<b>不能</b>把 GUI Scale 也乘进去，否则字会大出整整一个 GUI Scale 倍。
     *
     * <p>{@code oversample} 是栅格化时相对 size 放大的倍数：字形纹理有 {@code size × oversample}
     * 像素，而显示占 {@code size × guiScale} 像素。<b>两者相等时才是像素级 1:1</b>——
     * 这正是 Mojang 把默认值定成 2.0 的原因（最常见的 GUI Scale 就是 2）。
     * 我们不写死，直接跟着当前 GUI Scale 走。
     */
    private record Key(int sizeQ, int oversample) {
        static Key of(float size, int oversample) {
            // 量化到 1/4 逻辑像素：再细的差别肉眼看不出，却会让缓存条目翻倍
            return new Key(Math.round(size * 4f), oversample);
        }

        float size() {
            return sizeQ / 4f;
        }
    }

    private static @Nullable UiFont instance;

    private @Nullable ByteBuffer fontMemory;
    private @Nullable FT_Face face;
    private String sourceName = "";
    private boolean triedLoad;

    /** 烘焙参数 → 已烘焙的字体。LinkedHashMap 的插入序就是淘汰序。 */
    private final Map<Key, Baked> baked = new LinkedHashMap<>();

    private record Baked(Font font, FontSet set, GlyphStitcher stitcher, GlyphProvider provider) {
        void close() {
            try {
                set.close();
                stitcher.close();
                provider.close();
            } catch (RuntimeException e) {
                GTShaders.LOGGER.debug("释放字体资源时出错：{}", e.getMessage());
            }
        }
    }

    private UiFont() {
    }

    public static synchronized UiFont get() {
        if (instance == null) {
            instance = new UiFont();
        }
        return instance;
    }

    /** 字体文件是否已就绪。false 表示整个界面走原版位图字体。 */
    public boolean isAvailable() {
        ensureLoaded();
        return face != null;
    }

    /** 字体来源的可读名字，显示在设置提示里，好让玩家知道现在用的是哪一份。 */
    public String sourceName() {
        ensureLoaded();
        return sourceName;
    }

    /**
     * 取一份在屏幕上正好是 {@code BASE_SIZE × uiScale × guiScale} 物理像素高的字体。
     *
     * @param uiScale  编辑器自己的缩放
     * @param guiScale 游戏的 GUI Scale
     * @return 对应字号的字体；字体不可用时返回 null，调用方退回原版字体
     */
    public @Nullable Font fontFor(float uiScale, int guiScale) {
        if (face == null) {
            return null;
        }
        Baked b = baked.get(keyFor(uiScale, guiScale));
        return b == null ? null : b.font();
    }

    /**
     * 预先烘焙某个缩放下要用的字体。
     *
     * <p><b>必须在渲染帧之外调用</b>——打开编辑器、改缩放这些时候。烘焙会建 GL 纹理，
     * 而 1.21.6+ 的 GUI 分两阶段：{@code extract} 只负责把要画的东西提交进
     * {@code GuiRenderState}，真正的绘制在之后。在 extract 里建纹理是在错误的阶段碰 GL。
     *
     * <p>所以 {@link #fontFor} 只查缓存、查不到就返回 null 让调用方退回原版字体：
     * 宁可这一帧字体没换上，也不要在渲染中途去建资源。
     */
    public void prepare(float uiScale, int guiScale) {
        ensureLoaded();
        if (face == null) {
            return;
        }
        Key key = keyFor(uiScale, guiScale);
        if (baked.containsKey(key)) {
            return;
        }
        Baked b = bake(key);
        if (b == null) {
            return;
        }
        baked.put(key, b);
        while (baked.size() > MAX_CACHED) {
            Key oldest = baked.keySet().iterator().next();
            Baked dropped = baked.remove(oldest);
            if (dropped != null) {
                dropped.close();
            }
        }
    }

    private static Key keyFor(float uiScale, int guiScale) {
        float size = Math.max(MIN_SIZE, Math.min(MAX_SIZE, BASE_SIZE * uiScale));
        return Key.of(size, Math.max(1, guiScale));
    }

    /** 该字号下一行有多高（Minecraft 逻辑单位）。原版是写死的 9，矢量字体得按字号推算。 */
    public static float lineHeightFor(float uiScale) {
        // 0.82 是从 Mojang 那份 ttf 示例反推的：size 11 对应 lineHeight 9
        return Math.max(1f, BASE_SIZE * uiScale * 0.82f);
    }

    /**
     * 按物理字号烘焙一份字体。
     *
     * <p>这段是照着 {@code TrueTypeGlyphProviderDefinition.load} 走的，只是字体不从资源包读、
     * 且允许 CFF（OpenType）而不只是 TrueType——玩家手上的字体是什么格式不该由我们挑。
     */
    private @Nullable Baked bake(Key key) {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || face == null || fontMemory == null) {
            return null;
        }
        try {
            GlyphProvider provider = new TrueTypeGlyphProvider(
                    fontMemory, face, key.size(), key.oversample(), 0.0f, 0.0f, "");
            GlyphStitcher stitcher = new GlyphStitcher(mc.getTextureManager(),
                    Identifier.fromNamespaceAndPath(GTShaders.MOD_ID,
                            "ui_font_" + key.sizeQ() + "_" + key.oversample()));
            FontSet set = new FontSet(stitcher);
            set.reload(List.of(new GlyphProvider.Conditional(provider, FontOption.Filter.ALWAYS_PASS)),
                    Set.of());

            GlyphSource source = set.source(false);
            EffectGlyph white = set.whiteGlyph();
            Font font = new Font(new Font.Provider() {
                @Override
                public GlyphSource glyphs(FontDescription description) {
                    // 我们这份字体只有一套字形；样式里指定的其它字体一律忽略，
                    // 否则界面上会突然冒出一行原版位图字，粗细完全对不上
                    return source;
                }

                @Override
                public EffectGlyph effect() {
                    return white;
                }
            });
            return new Baked(font, set, stitcher, provider);
        } catch (RuntimeException e) {
            GTShaders.LOGGER.warn("烘焙 {} 号字体失败，退回原版字体：{}", key.size(), e.toString());
            return null;
        }
    }

    // ---------------------------------------------------------------- 字体发现

    private void ensureLoaded() {
        if (triedLoad) {
            return;
        }
        triedLoad = true;
        Path file = discover();
        if (file == null) {
            GTShaders.LOGGER.info("没有找到可用的界面字体，使用原版位图字体（把 ttf 放到 {} 可启用矢量字体）",
                    fontDir());
            return;
        }
        try {
            open(file);
            sourceName = file.getFileName().toString();
            GTShaders.LOGGER.info("界面字体：{}", file);
        } catch (IOException | RuntimeException e) {
            GTShaders.LOGGER.warn("界面字体 {} 打不开，使用原版位图字体：{}", file, e.toString());
            release();
        }
    }

    private void open(Path file) throws IOException {
        ByteBuffer buffer;
        try (InputStream in = Files.newInputStream(file)) {
            buffer = TextureUtil.readResource(in);
        }
        buffer.flip();

        FT_Face f;
        // FreeType 的 library 是全局共享的，Mojang 自己也用这把锁保护它
        synchronized (FreeTypeUtil.LIBRARY_LOCK) {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                PointerBuffer ptr = stack.mallocPointer(1);
                // face index 取 0：ttc 里可能装着好几个字体，第一个就是主字体
                FreeTypeUtil.assertError(
                        FreeType.FT_New_Memory_Face(FreeTypeUtil.getLibrary(), buffer, 0L, ptr),
                        "打开字体失败");
                f = FT_Face.create(ptr.get());
            }
        }
        String format = FreeType.FT_Get_Font_Format(f);
        // Mojang 只认 TrueType；CFF 是 OpenType 轮廓，FreeType 一样能栅格化，没理由拒绝
        if (!"TrueType".equals(format) && !"CFF".equals(format)) {
            FreeType.FT_Done_Face(f);
            MemoryUtil.memFree(buffer);
            throw new IOException("不支持的字体格式：" + format);
        }
        FreeTypeUtil.assertError(FreeType.FT_Select_Charmap(f, FreeType.FT_ENCODING_UNICODE),
                "字体没有 Unicode 字符表");

        this.fontMemory = buffer;
        this.face = f;
    }

    private static Path fontDir() {
        return Workspace.rootDir().resolve("font");
    }

    /**
     * 找一份能用的字体。
     *
     * <p>玩家自己放的优先——他放了就是想用那一份。其次才轮到系统字体，
     * 而系统字体的候选顺序按「界面字体的观感」排：雅黑/苹方这类 UI 字体在小字号下
     * 比宋体一类的衬线字体清楚得多。
     */
    private static @Nullable Path discover() {
        Path dir = fontDir();
        if (Files.isDirectory(dir)) {
            try (Stream<Path> s = Files.list(dir)) {
                List<Path> found = s.filter(Files::isRegularFile)
                        .filter(UiFont::isFontFile)
                        .sorted()
                        .toList();
                if (!found.isEmpty()) {
                    return found.get(0);
                }
            } catch (IOException e) {
                GTShaders.LOGGER.debug("列出字体目录失败：{}", e.getMessage());
            }
        }
        for (Path p : systemCandidates()) {
            if (Files.isRegularFile(p)) {
                return p;
            }
        }
        return null;
    }

    private static boolean isFontFile(Path p) {
        String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
        return n.endsWith(".ttf") || n.endsWith(".otf") || n.endsWith(".ttc");
    }

    private static List<Path> systemCandidates() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        List<Path> out = new ArrayList<>();
        if (os.contains("win")) {
            Path fonts = Path.of(System.getenv().getOrDefault("WINDIR", "C:\\Windows"), "Fonts");
            for (String n : new String[]{"msyh.ttc", "msyhl.ttc", "Deng.ttf", "simhei.ttf",
                    "msyh.ttf", "simsun.ttc", "arial.ttf"}) {
                out.add(fonts.resolve(n));
            }
        } else if (os.contains("mac")) {
            for (String n : new String[]{"/System/Library/Fonts/PingFang.ttc",
                    "/System/Library/Fonts/STHeiti Light.ttc",
                    "/System/Library/Fonts/Hiragino Sans GB.ttc",
                    "/System/Library/Fonts/Helvetica.ttc"}) {
                out.add(Path.of(n));
            }
        } else {
            for (String n : new String[]{
                    "/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc",
                    "/usr/share/fonts/truetype/noto/NotoSansCJK-Regular.ttc",
                    "/usr/share/fonts/noto-cjk/NotoSansCJK-Regular.ttc",
                    "/usr/share/fonts/truetype/wqy/wqy-microhei.ttc",
                    "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf"}) {
                out.add(Path.of(n));
            }
        }
        return out;
    }

    // ---------------------------------------------------------------- 释放

    /** 显存里的字形图集不会自己消失，退出编辑器时把它们还回去。 */
    public void releaseBaked() {
        for (Baked b : baked.values()) {
            b.close();
        }
        baked.clear();
    }

    private void release() {
        releaseBaked();
        if (face != null) {
            synchronized (FreeTypeUtil.LIBRARY_LOCK) {
                FreeType.FT_Done_Face(face);
            }
            face = null;
        }
        if (fontMemory != null) {
            MemoryUtil.memFree(fontMemory);
            fontMemory = null;
        }
    }
}
