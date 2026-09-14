package mc.GTedd.cn.gtshaders.runtime;

import mc.GTedd.cn.gtshaders.core.AnchorBinding;
import mc.GTedd.cn.gtshaders.core.AnchorSlot;
import mc.GTedd.cn.gtshaders.core.ParamType;
import mc.GTedd.cn.gtshaders.core.ShaderLayer;
import mc.GTedd.cn.gtshaders.core.ShaderParam;
import mc.GTedd.cn.gtshaders.core.ShaderProject;
import org.jspecify.annotations.Nullable;

/**
 * 回答一条锚点绑定「为什么画面上什么都没有」。
 *
 * <h2>为什么需要它</h2>
 *
 * <p>锚点从配好到看见效果，中间要串起来的环节比看上去多：
 *
 * <pre>
 *   绑定启用 → 挤得进槽位 → 预览开着 → 有效果层用了 gtAnchor
 *            → 那个层的 AnchorSlot 参数指向的是这条绑定 → 目标解算出来了 → 没被挡住
 * </pre>
 *
 * <p>任何一环断掉，画面上的表现<b>完全一样</b>：什么都没有。而其中三环
 * （预览、有没有层读锚点、读的是不是这一条）光看锚点面板根本看不出来。
 * 最容易踩的是最后那个——效果层的 {@code AnchorSlot} 参数默认指向槽位 0，
 * 而新加的绑定往往落在 #1、#2，于是「配好了、也触发了、就是不亮」。
 *
 * <h2>顺序不是随便排的</h2>
 *
 * <p>{@link AnchorRuntime#solve()} 只在预览挂着时被调用（走
 * {@code PreviewRuntime.getOrBuildChain}）。所以<b>预览没开时槽位数据是陈旧的</b>，
 * 拿它判断「有没有解算出目标」会误报。因此预览这一环必须排在解算状态之前。
 *
 * <p>其余顺序按「从静态到动态」：先报配置问题（改一次就好），再报运行时问题
 * （目标不在视野这类，换个位置就变）。
 */
public final class AnchorCheck {

    /** 链路上第一个断掉的环节。 */
    public enum Stage {
        /** 全通。 */
        OK,
        /** 绑定自己被关掉了。 */
        DISABLED,
        /** 前面的绑定把 8 个槽位占满了，这一条分不到。 */
        NO_SLOT,
        /** 实时预览没开——锚点根本不会被解算。 */
        NO_PREVIEW,
        /** 工程里没有任何一层用到 {@code gtAnchor}。 */
        NO_ANCHOR_LAYER,
        /** 有层读锚点，但没有一个指向这条绑定的槽位。 */
        SLOT_UNREAD
    }

    /**
     * @param stage 第一个断掉的环节；{@link Stage#OK} 表示配置这一侧没问题
     * @param slot  这条绑定的起始槽位号；分不到时是 -1
     */
    public record Report(Stage stage, int slot) {
        public boolean ok() {
            return stage == Stage.OK;
        }
    }

    private AnchorCheck() {
    }

    /**
     * 查<b>配置链</b>：从绑定启用一直到「有层读这个槽位」。
     *
     * <p>不含「目标解算出来了没有」——那是运行时状态，事件型刚触发时下一帧才有数据，
     * 拿它当判据会在刚按下触发键时误报。要那部分用 {@link #liveSlot}。
     */
    public static Report check(ShaderProject project, AnchorBinding binding) {
        if (!binding.isEnabled()) {
            return new Report(Stage.DISABLED, -1);
        }
        int slot = project.anchorSlotBaseById(binding.id());
        if (slot < 0) {
            return new Report(Stage.NO_SLOT, -1);
        }
        if (!PreviewRuntime.isActive()) {
            return new Report(Stage.NO_PREVIEW, slot);
        }
        if (!PreviewRuntime.usesAnchors()) {
            return new Report(Stage.NO_ANCHOR_LAYER, slot);
        }
        if (!isSlotRead(project, binding, slot)) {
            return new Report(Stage.SLOT_UNREAD, slot);
        }
        return new Report(Stage.OK, slot);
    }

    /**
     * 有没有哪个启用的层，其 {@link ParamType#ANCHOR} 参数指向这条绑定。
     *
     * <p>两种指法都算：参数直接绑了这条绑定的稳定 id（正常路径），
     * 或者没绑 id、按裸槽位号用而那个号正好落在这条绑定的槽位区间里
     * （作者手填数字的老路径，仍然有效）。
     *
     * <p>纯函数，不碰 Minecraft，可以直接测。
     */
    public static boolean isSlotRead(ShaderProject project, AnchorBinding binding, int slot) {
        int span = Math.max(1, binding.maxSlots());
        for (ShaderLayer layer : project.layers()) {
            if (!layer.isEnabled()) {
                continue;
            }
            for (ShaderParam p : layer.params()) {
                if (p.type() != ParamType.ANCHOR) {
                    continue;
                }
                if (!p.anchorRef().isEmpty()) {
                    if (p.anchorRef().equals(binding.id())) {
                        return true;
                    }
                    continue;
                }
                int used = Math.round(p.value()[0]);
                if (used >= slot && used < slot + span) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 这条绑定当前<b>解算出来的</b>第一个非空槽位；没解算出来返回 null。
     *
     * <p>只有预览开着时才有意义（见类注释），调用方应当先看 {@link #check} 的结果。
     */
    public static @Nullable AnchorSlot liveSlot(ShaderProject project, AnchorBinding binding) {
        int base = project.anchorSlotBaseById(binding.id());
        if (base < 0) {
            return null;
        }
        AnchorSlot[] all = AnchorRuntime.slots();
        for (int i = 0; i < binding.maxSlots() && base + i < all.length; i++) {
            AnchorSlot s = all[base + i];
            if (!s.isEmpty()) {
                return s;
            }
        }
        return null;
    }
}
