package mc.GTedd.cn.gtshaders.runtime;

import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.repository.PackRepository;
import org.jspecify.annotations.Nullable;
import mc.GTedd.cn.gtshaders.GTShaders;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * 真实加载验证：把导出的 zip 当成一个普通资源包装进游戏，走原版的资源重载，
 * 再按导出时选的触发方式挂上——<b>此时屏幕上跑的就是玩家拿到包后会看到的东西</b>，
 * 预览运行时完全不参与。
 *
 * <p>预览链本身已经在用原版 {@code PostChain} 与原版编译器，剩下的差异全在
 * 「资源包加载」这一步：{@code pack.mcmeta} 的格式区间、贴图落在不落在
 * {@code textures/effect/} 下、JSON 里引用的着色器文件在不在、
 * {@code isPostEffectValid} 认不认这个 id。这些错误在预览里根本不会出现，
 * 只在别人加载包的那一刻静静地表现为「没效果」。所以给作者一个按钮，在自己机器上先加载一次。
 *
 * <p>做法和 README.txt 教玩家的一模一样：把 zip 放进 {@code resourcepacks/}、启用、
 * 然后 {@code /posteffect add}（或 {@code end_of_frame} 自动生效）。
 * 「挂上」用的是 {@link PreviewRuntime#applyVanilla}——它只是往请求列表里报一个 id，
 * 不提供任何源码，mixin 也不拦截，和玩家敲指令是同一条路径。
 *
 * <p>整个流程是可逆的：{@link #stop} 把包从选中列表里摘掉、再重载一次、删掉 zip。
 * 中途崩溃的话包会留在 {@code resourcepacks/} 里并保持选中，文件名带 {@code gtshaders-verify}
 * 前缀，一眼能认出来手动删。
 */
public final class PackVerify {

    private static final String FILE_PREFIX = "gtshaders-verify-";

    private static @Nullable String packId;
    private static final List<String> packIds = new ArrayList<>();
    private static final List<Path> installed = new ArrayList<>();
    private static @Nullable Identifier effect;
    private static boolean busy;

    private PackVerify() {
    }

    public static boolean isActive() {
        return packId != null;
    }

    /** 重载正在进行中；这段时间里再点一次按钮应当被忽略。 */
    public static boolean isBusy() {
        return busy;
    }

    /** 当前装着的验证包在资源包列表里的 id（{@code file/...}），没有则为 null。 */
    public static @Nullable String activePackId() {
        return packId;
    }

    /**
     * 装包、重载、挂载。
     *
     * @param exportedZips 导出器刚写出来的 zip；后处理包与核心着色器包都装，顺序即优先级（后者高）
     * @param effectId     指令触发时要 add 的效果 id；{@code end_of_frame} 模式传 null
     * @return 重载完成（含挂载）后完成；失败时异常在 future 里
     */
    public static CompletableFuture<Void> start(List<Path> exportedZips, @Nullable Identifier effectId) {
        Minecraft mc = Minecraft.getInstance();
        if (busy) {
            return CompletableFuture.failedFuture(new IllegalStateException("verify already running"));
        }
        if (exportedZips.isEmpty()) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("nothing to verify"));
        }
        Path dir = mc.getResourcePackDirectory();
        List<String> ids = new ArrayList<>();
        List<Path> targets = new ArrayList<>();
        try {
            Files.createDirectories(dir);
            for (Path zip : exportedZips) {
                Path target = dir.resolve(FILE_PREFIX + zip.getFileName());
                Files.copy(zip, target, StandardCopyOption.REPLACE_EXISTING);
                targets.add(target);
                ids.add("file/" + target.getFileName());
            }
        } catch (IOException e) {
            return CompletableFuture.failedFuture(e);
        }

        // 预览先全摘掉——后处理链和核心着色器接管都是「mod 参与」，验证的意义就是没有 mod 参与
        PreviewRuntime.clear();
        PreviewRuntime.clearCore();

        PackRepository repo = mc.getResourcePackRepository();
        repo.reload();
        for (String id : ids) {
            if (!repo.getAvailableIds().contains(id)) {
                return CompletableFuture.failedFuture(new IllegalStateException(
                        "resource pack not discovered after reload: " + id));
            }
        }
        List<String> selected = new ArrayList<>(repo.getSelectedIds());
        selected.removeAll(ids);
        // 排在最后 = 最高优先级：验证包必须压过玩家自己启用的其它包，
        // 否则别的包里同名的 end_of_frame 会赢，看到的就不是自己的东西
        selected.addAll(ids);
        repo.setSelected(selected);
        mc.options.updateResourcePacks(repo);

        busy = true;
        packId = ids.get(0);
        packIds.clear();
        packIds.addAll(ids);
        installed.clear();
        installed.addAll(targets);
        effect = effectId;
        return mc.reloadResourcePacks().whenCompleteAsync((v, err) -> {
            busy = false;
            if (err != null) {
                GTShaders.LOGGER.error("真实加载验证：资源重载失败", err);
                return;
            }
            if (effectId != null) {
                // 等价于 /posteffect add @s <id>：只报 id，不供源码，mixin 不拦截
                PreviewRuntime.applyVanilla(effectId);
            }
        }, mc);
    }

    /**
     * 撤销：摘掉验证包、重载、删 zip。完成后预览运行时处于「没有效果」状态，
     * 由调用方决定要不要把工程重新编译挂回去。
     */
    public static CompletableFuture<Void> stop() {
        Minecraft mc = Minecraft.getInstance();
        String id = packId;
        if (id == null || busy) {
            return CompletableFuture.completedFuture(null);
        }
        PreviewRuntime.clear();
        PackRepository repo = mc.getResourcePackRepository();
        List<String> selected = new ArrayList<>(repo.getSelectedIds());
        selected.removeAll(packIds);
        repo.setSelected(selected);
        mc.options.updateResourcePacks(repo);

        List<Path> files = new ArrayList<>(installed);
        packId = null;
        packIds.clear();
        installed.clear();
        effect = null;
        busy = true;
        return mc.reloadResourcePacks().whenCompleteAsync((v, err) -> {
            busy = false;
            if (err != null) {
                GTShaders.LOGGER.error("真实加载验证：撤销时资源重载失败", err);
            }
            for (Path file : files) {
                try {
                    Files.deleteIfExists(file);
                } catch (IOException e) {
                    GTShaders.LOGGER.warn("真实加载验证：删不掉 {}，请手动删除", file, e);
                }
            }

            // 文件没了，仓库里那条记录也该跟着消失，免得资源包界面上还列着一个打不开的包
            mc.getResourcePackRepository().reload();
        }, mc);
    }

    /** 当前验证挂着的效果 id；{@code end_of_frame} 模式或未验证时为 null。 */
    public static @Nullable Identifier activeEffect() {
        return effect;
    }
}
