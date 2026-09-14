package mc.GTedd.cn.gtshaders.runtime;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import org.jspecify.annotations.Nullable;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/**
 * 让指定实体在<b>本地</b>发光，好在编辑器里预览实体轮廓效果。
 *
 * <h2>为什么必须有这个</h2>
 *
 * <p>实体轮廓层覆盖的是 {@code minecraft:entity_outline} 那条链，而
 * {@code LevelRenderer} 只在 {@code featureFrame.hasAnyOutline()} 为真时才执行它——
 * <b>一个发光实体都没有的时候，那条链根本不跑</b>。
 *
 * <p>于是没有这个开关的话，调轮廓效果的流程会变成：切出去、进服务器、给自己权限、
 * {@code /effect give} 一只怪、切回来看一眼、发现要改、再来一遍。这不是能迭代的节奏。
 *
 * <h2>怎么做到的，以及它的边界</h2>
 *
 * <p>{@code Entity.setGlowingTag(true)} 会走到 {@code setSharedFlag(6, ...)}，
 * 而客户端的 {@code isCurrentlyGlowing()} 读的正是 {@code getSharedFlag(6)}。
 * 所以在客户端调它，本地立刻就发光了。
 *
 * <p><b>但这只是本地的、而且会被服务端覆盖</b>：实体数据下一次同步就把那个 flag 冲掉了。
 * 所以这里每 tick 补一次，而不是设一次就完事。这也决定了它的定位——
 * <b>纯预览工具</b>，不是「给实体加发光」的功能。真要在服务器上让谁发光，
 * 用 {@code /effect give <target> minecraft:glowing}，那才会同步给所有玩家。
 *
 * <p>轮廓的颜色同理：本地强制发光拿到的是默认色（白），
 * 而真实场景里的颜色来自实体所在记分板队伍。想预览「按队伍颜色分流」的效果，
 * 得在真服务器上配队伍——这一点在文档里要说清楚，免得有人对着一片白色调半天。
 */
public final class GlowRuntime {

    /**
     * 被强制发光的实体。
     *
     * <p>用 {@link LinkedHashSet} 而不是普通 HashSet：界面上要按加入顺序列出来，
     * 而「我刚点的那只在哪」是这个列表唯一会被问到的问题。
     */
    private static final Set<UUID> FORCED = new LinkedHashSet<>();

    private GlowRuntime() {
    }

    /**
     * 每客户端 tick 补一次发光标记。
     *
     * <p>必须每 tick 都补：服务端的实体数据同步会把 flag 6 冲掉，
     * 设一次的话会出现「刚点上时亮了一下，然后就灭了」这种最费解的现象。
     */
    public static void tick() {
        if (FORCED.isEmpty()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            // 切世界了，标记全部作废——UUID 在新世界里指向的可能是完全不同的东西
            FORCED.clear();
            return;
        }
        for (Entity e : mc.level.entitiesForRendering()) {
            if (FORCED.contains(e.getUUID()) && !e.hasGlowingTag()) {
                e.setGlowingTag(true);
            }
        }
    }

    /**
     * 切换一个实体的强制发光。
     *
     * @return 切换之后是不是发光的
     */
    public static boolean toggle(Entity entity) {
        UUID id = entity.getUUID();
        if (FORCED.remove(id)) {
            // 取消时要主动把标记撤掉。只从集合里删掉的话，下一次服务端同步之前它还亮着，
            // 看起来像「点了没反应」
            entity.setGlowingTag(false);
            return false;
        }
        FORCED.add(id);
        entity.setGlowingTag(true);
        return true;
    }

    /** 撤掉全部强制发光。 */
    public static void clear() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level != null) {
            for (Entity e : mc.level.entitiesForRendering()) {
                if (FORCED.contains(e.getUUID())) {
                    e.setGlowingTag(false);
                }
            }
        }
        FORCED.clear();
    }

    public static int count() {
        return FORCED.size();
    }

    public static boolean isForced(@Nullable Entity entity) {
        return entity != null && FORCED.contains(entity.getUUID());
    }

    /**
     * 视野里有没有任何东西在发光。
     *
     * <p>界面拿它提示「轮廓链当前不会执行」——{@code hasAnyOutline()} 为假时原版根本不跑这条链，
     * 而那和「效果写错了」在画面上长得一模一样。
     */
    public static boolean anyGlowingVisible() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            return false;
        }
        for (Entity e : mc.level.entitiesForRendering()) {
            if (e.isCurrentlyGlowing()) {
                return true;
            }
        }
        return false;
    }
}
