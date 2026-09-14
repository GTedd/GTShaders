package mc.GTedd.cn.gtshaders.export;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * 先把整份产物写到旁边的临时文件，全部写成功了才让它顶替目标。
 *
 * <p>为什么导出必须这么写：原来是 {@code Files.newOutputStream(目标 zip)} 直接开在最终路径上，
 * 于是「导出到一半失败」会同时造成两件坏事——留下一个能被资源包列表识别、
 * 打开却报错的残缺 zip，并且<b>把上一次成功导出的同名文件覆盖掉了</b>。
 * 作者一般是改了几行重新导出，上一版正是他唯一还能用的那份。
 *
 * <p>临时文件刻意放在目标的同一个目录里：跨卷的 {@code move} 退化成复制+删除，
 * 既不原子也可能半路失败，那就白做了。
 */
public final class StagedWrite {

    /** 往流里写完整一份产物。抛异常即视为这次导出作废。 */
    @FunctionalInterface
    public interface Body {
        void write(OutputStream out) throws IOException;
    }

    private StagedWrite() {
    }

    /** 工程、快照等文本也先完整写到临时文件，成功后才替换已有版本。 */
    public static void writeUtf8(Path target, String text) throws IOException {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        toFile(target, out -> out.write(bytes));
    }

    /**
     * @param target 最终文件；父目录会被自动创建
     * @param body   产物内容；只有它正常返回，target 才会被改动
     */
    public static void toFile(Path target, Body body) throws IOException {
        Path dir = target.toAbsolutePath().getParent();
        Files.createDirectories(dir);
        Path tmp = Files.createTempFile(dir, target.getFileName() + ".", ".part");
        try {
            try (OutputStream out = Files.newOutputStream(tmp)) {
                body.write(out);
            }
            try {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                // 某些文件系统（网络盘、部分 FUSE）不支持原子替换。退化成普通替换：
                // 仍然保证「写完才替换」，只是替换本身不再是一个瞬间。
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException | RuntimeException e) {
            try {
                Files.deleteIfExists(tmp);
            } catch (IOException cleanup) {
                // 清理失败不能盖掉真正的失败原因，挂在它下面就好
                e.addSuppressed(cleanup);
            }
            throw e;
        }
    }
}
