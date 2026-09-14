package mc.GTedd.cn.gtshaders.ui;

import mc.GTedd.cn.gtshaders.GTShaders;
import mc.GTedd.cn.gtshaders.export.ExportTarget;
import net.minecraft.client.Minecraft;
import org.jspecify.annotations.Nullable;
import org.lwjgl.sdl.SDLDialog;
import org.lwjgl.sdl.SDLError;
import org.lwjgl.sdl.SDL_DialogFileCallback;
import org.lwjgl.system.MemoryUtil;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.function.Consumer;

/**
 * 系统自带的「选择文件夹」对话框。
 *
 * <h2>为什么是 SDL 而不是 tinyfd</h2>
 *
 * <p>弹系统对话框的老办法是 LWJGL 的 {@code TinyFileDialogs}，很多模组都这么干。
 * <b>但 26.3 的依赖表里已经没有 {@code lwjgl-tinyfd} 了</b>——输入层换成 SDL3 之后，
 * {@code lwjgl-glfw} 与 {@code lwjgl-tinyfd} 一起被摘掉，换成了 {@code lwjgl-sdl}。
 * 照着老教程写会编译得过（开发机的 Gradle 缓存里还留着旧版本的 jar），
 * 运行时才 {@code NoClassDefFoundError}。
 *
 * <p>SDL3 自己带文件对话框，而且比 tinyfd 更好：<b>它是异步的</b>。tinyfd 会阻塞调用线程，
 * 而调用线程就是渲染线程——对话框开着的那几秒里游戏画面完全冻住，Windows 还会给窗口
 * 挂上「无响应」。SDL 版本立刻返回，选完了再回调，游戏照常跑。
 *
 * <h2>两条必须守住的规则</h2>
 *
 * <p><b>1. 必须从主线程调用</b>（我们的调用点是界面的点击回调，正好在渲染线程上，就是主线程）。
 *
 * <p><b>2. 回调不一定在主线程上跑。</b>Windows 上 SDL 专门开一条线程去开对话框，
 * 回调就在那条线程上。所以回调里除了把路径读出来，什么都不能做——
 * 碰界面、碰文件的事一律 {@code Minecraft.execute} 挪回客户端线程。
 * 路径本身<b>必须在回调内读完</b>：那个字符串数组是 SDL 的，回调一返回就被释放了。
 *
 * <p>已知短板：独占全屏下系统对话框可能弹在游戏窗口后面甚至弹不出来（Windows 的老问题）。
 * 弹不出来时 SDL 会带着错误信息回调，我们照原样报给用户，并且那一栏本来就还能手打路径。
 */
public final class FolderPicker {

    /**
     * 对话框正开着。
     *
     * <p>{@code volatile}：置回 {@code false} 的那次写发生在客户端线程，而读它的是渲染线程——
     * 虽然 26.3 里两者是同一条线程，但回调线程的存在让这件事不再显然，标上更省心。
     */
    private static volatile boolean open;

    private FolderPicker() {
    }

    /** 对话框是不是正开着。开着的时候按钮该显示成按下状态，再点也不会弹第二个。 */
    public static boolean isOpen() {
        return open;
    }

    /**
     * 弹出系统的文件夹选择框。<b>立刻返回</b>，结果走回调。
     *
     * @param start  打开时定位到哪个目录；不存在就往上找存在的祖先，都没有就交给系统决定
     * @param onPick 选好了，参数是选中的目录；<b>在客户端线程上跑</b>
     * @param onFail 弹不出来，参数是原因；同样在客户端线程上跑。用户主动取消<b>不算</b>失败，
     *               两个回调都不会调——取消的意思就是「什么都别做」
     */
    public static void open(@Nullable Path start, Consumer<Path> onPick, Consumer<String> onFail) {
        if (open) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        long window = mc.getWindow() == null ? 0L : mc.getWindow().handle();
        Path located = ExportTarget.existingAncestor(start);

        open = true;
        try {
            show(mc, window, located == null ? null : located.toString(), onPick, onFail);
        } catch (Throwable t) {
            // 把整段 SDL 都圈进来是有意的：这个模组要是被搬到一个没有 lwjgl-sdl 的环境里，
            // 第一次点这个按钮会直接 NoClassDefFoundError——那不该让编辑器崩掉，
            // 而应该退化成「弹不出来，你手打路径吧」。所以捕的是 Throwable 不是 Exception
            open = false;
            GTShaders.LOGGER.warn("弹不出系统文件夹选择框", t);
            onFail.accept(String.valueOf(t));
        }
    }

    /**
     * 真正碰 SDL 的那一段，单独一个方法。
     *
     * <p>拆出来是为了让类加载推迟到<b>真的点了按钮</b>那一刻：SDL 的类只出现在这个方法体里，
     * {@link #open} 的方法签名与字节码都碰不到它们，于是缺库时也只有这一次调用会炸，
     * 而且炸在上面那个 {@code catch} 里。
     */
    private static void show(Minecraft mc, long window, @Nullable String location,
                             Consumer<Path> onPick, Consumer<String> onFail) {
        // 回调对象要留个引用才能释放：CallbackI 的 address() 每调一次就新建一个 upcall 桩子，
        // 直接把 lambda 传进去等于每弹一次对话框漏一个，而且再也拿不到它去释放
        SDL_DialogFileCallback[] self = new SDL_DialogFileCallback[1];
        SDL_DialogFileCallback callback = SDL_DialogFileCallback.create((userdata, filelist, filter) -> {
            // 这几行仍在 SDL 的线程上：只把数据读出来，别的什么都不做。
            // 路径必须在这里读完——那个字符串数组是 SDL 的，回调一返回就被释放了
            String picked = firstPath(filelist);
            String error = filelist == MemoryUtil.NULL ? SDLError.SDL_GetError() : null;
            mc.execute(() -> {
                open = false;
                if (self[0] != null) {
                    // 原生那边早已从桩子里返回了（回调是同步调用，这里排到下一帧才跑）
                    self[0].free();
                    self[0] = null;
                }
                if (error != null) {
                    GTShaders.LOGGER.warn("系统文件夹选择框打不开：{}", error);
                    onFail.accept(error);
                } else if (picked != null) {
                    try {
                        onPick.accept(Path.of(picked));
                    } catch (InvalidPathException e) {
                        // 系统给回来的串 Java 认不了。实测没见过，但绝不能让它抛进事件循环
                        onFail.accept(picked);
                    }
                }
            });
        });
        self[0] = callback;
        try {
            SDLDialog.SDL_ShowOpenFolderDialog(callback, MemoryUtil.NULL, window, location, false);
        } catch (Throwable t) {
            // 没弹起来就没人会调回调，桩子得在这儿还回去
            self[0] = null;
            callback.free();
            throw t;
        }
    }

    /**
     * 取出结果里的第一条路径。
     *
     * <p>SDL 给的是一个以 NULL 结尾的 C 字符串数组：数组本身为 NULL 表示<b>出错</b>，
     * 数组非空但第一项为 NULL 表示<b>用户取消</b>——这两件事必须分开，
     * 混成一个「没选」会把「对话框根本没弹出来」也吞掉，用户只会觉得按钮点了没反应。
     */
    private static @Nullable String firstPath(long filelist) {
        if (filelist == MemoryUtil.NULL) {
            return null;
        }
        long first = MemoryUtil.memGetAddress(filelist);
        return first == MemoryUtil.NULL ? null : MemoryUtil.memUTF8(first);
    }
}
