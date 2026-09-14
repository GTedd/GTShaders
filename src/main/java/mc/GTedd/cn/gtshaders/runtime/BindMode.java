package mc.GTedd.cn.gtshaders.runtime;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;
import mc.GTedd.cn.gtshaders.core.AnchorBinding;
import mc.GTedd.cn.gtshaders.core.AnchorSlot;
import mc.GTedd.cn.gtshaders.core.ShaderProject;
import mc.GTedd.cn.gtshaders.i18n.GtLang;
import mc.GTedd.cn.gtshaders.runtime.AnchorRuntime.Pick;

import java.util.List;
import java.util.Locale;

/**
 * 世界内绑定模式：不开界面，用准星把锚点绑到世界里的东西上。
 *
 * <h2>为什么要有这个东西</h2>
 *
 * <p>锚点面板和准星是<b>互斥</b>的——编辑器一打开鼠标就归界面了，没法再瞄任何东西。
 * 于是「把效果绑到那只怪身上」这件事，在面板里只能表达成往一个文本框里手打
 * {@code 6f3a1c2e-...} 或者 {@code minecraft:zombie}：前者不可能记住，后者要背 id。
 * 坐标同理，得先按 F3 抄下来再切回去打字。
 *
 * <p>而实践中真正好用的那条路径是 B 键——看哪打哪，不选来源、不填选择器、不管触发器。
 * 它好用的原因不是「快捷键快」，是它<b>把意图直接表达在了世界里</b>。这个类做的事就是
 * 把那种表达方式推广到全部绑定方式上。
 *
 * <h2>它怎么工作</h2>
 *
 * <p>进入模式后游戏<b>不暂停</b>，鼠标仍然控制视角，玩家还能走动。变的只有几个键的含义：
 * 左右键不再攻击和使用物品，而是「绑」和「换绑法」；滚轮不再切物品栏，而是切正在编辑
 * 哪一条绑定。这些是靠 {@code MinecraftMixin} / {@code MouseHandlerMixin} 在源头拦掉的，
 * 拦不住的话在生存模式下会一边配绑定一边把面前的怪打死。
 *
 * <h2>「当前编辑哪一条」不另存一份</h2>
 *
 * <p>直接用 {@link ShaderProject#selectedAnchorIndex()}，和面板共用同一个。
 * 各记一份的话，「模式里滚到第三条 → 退出 → 面板停在第一条」这种不一致迟早会出现，
 * 而它表现出来是「我明明刚绑好的，面板上怎么是另一条」。
 */
public final class BindMode {

    /** 准星射程。和 {@link AnchorBinding.Source#LOOK_HIT} 用同一个值，所见即所得。 */
    private static final double RANGE = 128.0;

    /** 删除要按两次，这是两次之间的有效间隔（毫秒）。 */
    private static final long CONFIRM_WINDOW_MS = 2500L;

    private static boolean active;

    /**
     * 当前的绑法。跟着准星上下文走——指着实体和指着方块，可选的绑法本来就不是同一批。
     *
     * <p>存的是枚举本身而不是下标：上下文一变候选列表就换了一批，存下标会让
     * 「看着怪选到第 4 项 → 低头看地 → 那一批只有 3 项」变成一次静默的越界回绕。
     */
    private static Semantic semantic = Semantic.THIS_ONE;

    /** 退出时回哪儿。从编辑器进来时是「重开编辑器」，从局内按键进来时是 null。 */
    private static @Nullable Runnable onExit;

    /** 上一次按删除的时刻，用于两次确认。 */
    private static long deleteArmedAt;

    /** 最近一次操作的反馈，画在状态栏上。比聊天栏消息更贴近视线。 */
    private static String flash = "";
    private static long flashAt;

    /**
     * 本 tick 的准星解析结果，{@link #tick} 里算一次，状态栏和 3D 标记都读它。
     *
     * <p>不缓存的话这条 128 格射线一帧要算好几次：状态栏每帧问一次「准星指着什么」、
     * gizmo 每 tick 问一次落点在哪、换绑法时再问一次上下文。而它要遍历上百个方块的碰撞箱
     * 再扫一遍沿途实体，属于那种「单次不贵、但没理由重复」的开销。
     *
     * <p>按键路径（{@link #bind}）<b>不</b>读缓存：按下的那一刻要的是此刻最准的落点，
     * 而不是最多晚 50 毫秒的一份。
     */
    private static @Nullable Pick cachedPick;
    private static @Nullable BindTarget cachedTarget;

    private BindMode() {
    }

    // ---------------------------------------------------------------- 绑法

    /**
     * 绑法要用到的全部输入，<b>纯数据</b>。
     *
     * <p>不直接用 {@link Pick} 是因为那里面装的是 {@code Entity} 和 {@code BlockPos}——
     * 一旦绑法依赖了它们，「THIS_KIND 会不会漏配触发器」这种纯逻辑问题就只能靠开游戏去试。
     * 转成三个字符串之后，每一条绑法配出来的东西都是可以直接断言的。
     *
     * <p>转换本身很薄（{@link #targetOf}），薄到不值得再写一层测试。
     *
     * @param kind   准星指着哪一类东西
     * @param uuid   实体的 UUID 字符串；非实体时是空串
     * @param typeId 实体类型 id，如 {@code minecraft:zombie}；非实体时是空串
     * @param pos    坐标，已经是 {@code AnchorBinding.parseBlockPos} 认的选择器格式。
     *               指着方块时是整数坐标，打空时是落点的小数坐标
     * @param living 是不是活物。盔甲架、掉落物、船没有死亡和受伤事件
     */
    public record BindTarget(Kind kind, String uuid, String typeId, String pos, boolean living) {
    }

    /** 准星指着哪一类东西。 */
    public enum Kind {
        ENTITY, BLOCK, MISS
    }

    /**
     * 一种「绑法」——把来源、选择器、触发器、时长、钉住/跟随<b>一次配对</b>。
     *
     * <p>面板上这五项是五个独立控件，于是有 8×5 种组合，其中大部分是静默无效的
     * （最典型：方块坐标配「死亡时」，那上面根本没有实体，事件采集永远扫不到它）。
     * 现有代码里的 {@code Source.canFireEvents()}、{@code AnchorBinding.warning()}、
     * 面板上的红字，全都是在<b>事后</b>补救这个组合爆炸。
     *
     * <p>这里换了个方向：只提供那些本来就成立的组合，并且每一条都有一句人话名字。
     * 无效组合不是被警告，而是压根构造不出来。
     *
     * <p>缓动、遮挡、距离上限这些<b>不</b>在这里配——它们是调效果时才动的旋钮，
     * 每一项都有合理默认值，放进来只会让世界内操作重新变成填表。
     */
    public enum Semantic {
        /** 就这一只，按 UUID 认人，走远了、混进怪群里也还是它。 */
        THIS_ONE("this_one") {
            @Override
            void apply(AnchorBinding b, BindTarget t) {
                b.setTrigger(AnchorBinding.Trigger.ALWAYS);
                b.setSource(AnchorBinding.Source.ENTITY_UUID);
                b.setSelector(t.uuid());
                b.setMaxSlots(1);
            }
        },
        /**
         * 就这一只，但要按键才打——「配好效果 → 退出编辑器 → 对着它按 B」这条路径。
         *
         * <p>和 {@link #THIS_ONE} 的分工：那个是常驻，绑上就一直亮着，适合护盾、标记这类;
         * 这个是一次性演出，按一下播一遍完整生命周期。奇点炸弹、天降激光这类<b>有开头有结尾</b>
         * 的效果必须用这个——常驻绑定的 life 恒为 1，效果一上来就停在「已经播完」那一帧。
         */
        THIS_ONE_SHOT("this_one_shot") {
            @Override
            void apply(AnchorBinding b, BindTarget t) {
                b.setTrigger(AnchorBinding.Trigger.MANUAL);
                b.setSource(AnchorBinding.Source.ENTITY_UUID);
                b.setSelector(t.uuid());
                b.setMaxSlots(1);
                // 跟着那只怪走。simulate 记下 uuid，之后按 uuid 跟踪，
                // 和准星还指不指着它无关
                b.setStick(false);
            }
        },
        /** 这一类的全部，视野里每一只各占一个槽位。 */
        THIS_KIND("this_kind") {
            @Override
            void apply(AnchorBinding b, BindTarget t) {
                b.setTrigger(AnchorBinding.Trigger.ALWAYS);
                b.setSource(AnchorBinding.Source.ENTITY_TYPE);
                b.setSelector(t.typeId());
            }
        },
        /**
         * 这一类死掉的那一刻，钉在它倒下的地方。
         *
         * <p>用类型而不是这一只：这一只死了就再也触发不了第二次，而调效果需要反复看。
         */
        KIND_ON_DEATH("kind_on_death") {
            @Override
            void apply(AnchorBinding b, BindTarget t) {
                b.setTrigger(AnchorBinding.Trigger.ON_DEATH);
                b.setSource(AnchorBinding.Source.ENTITY_TYPE);
                b.setSelector(t.typeId());
                b.setStick(true);
            }
        },
        /** 这一类挨打的那一刻，跟着它走——挨打的人还在动，钉住反而不对。 */
        KIND_ON_HURT("kind_on_hurt") {
            @Override
            void apply(AnchorBinding b, BindTarget t) {
                b.setTrigger(AnchorBinding.Trigger.ON_HURT);
                b.setSource(AnchorBinding.Source.ENTITY_TYPE);
                b.setSelector(t.typeId());
                b.setStick(false);
            }
        },
        /** 准星此刻指着谁就是谁，不记住任何东西。适合「看谁给谁上标记」。 */
        LOOK_FOLLOW("look_follow") {
            @Override
            void apply(AnchorBinding b, BindTarget t) {
                b.setTrigger(AnchorBinding.Trigger.ALWAYS);
                b.setSource(AnchorBinding.Source.LOOK_AT);
                b.setSelector("");
                b.setMaxSlots(1);
            }
        },
        /** 钉死在这个方块坐标上，常驻。信标、传送门、法阵这类。 */
        HERE("here") {
            @Override
            void apply(AnchorBinding b, BindTarget t) {
                b.setTrigger(AnchorBinding.Trigger.ALWAYS);
                b.setSource(AnchorBinding.Source.BLOCK_POS);
                b.setSelector(t.pos());
                b.setMaxSlots(1);
            }
        },
        /**
         * 准星落点 + 手动触发——按一下就在看着的地方打一发。
         *
         * <p>时长给足 6 秒而不是默认的 2.5：演出类效果光蓄力就要三秒以上，
         * 默认值只够播个开头就硬切掉。和 B 键那条路径给的是同一个值。
         */
        LOOK_SHOT("look_shot") {
            @Override
            void apply(AnchorBinding b, BindTarget t) {
                b.setTrigger(AnchorBinding.Trigger.MANUAL);
                b.setSource(AnchorBinding.Source.LOOK_HIT);
                b.setSelector("");
                b.setDuration(6f);
                b.setYOffset(0f);
                b.setMaxSlots(1);
            }
        },
        /** 我自己。第一人称下就在屏幕中央，适合护盾、中毒、濒死这类自身状态。 */
        SELF("self") {
            @Override
            void apply(AnchorBinding b, BindTarget t) {
                b.setTrigger(AnchorBinding.Trigger.ALWAYS);
                b.setSource(AnchorBinding.Source.SELF);
                b.setSelector("");
                b.setMaxSlots(1);
            }
        };

        private final String id;

        Semantic(String id) {
            this.id = id;
        }

        /** 把这套绑法写进绑定。调用方保证 pick 与本绑法的上下文匹配。 */
        abstract void apply(AnchorBinding b, BindTarget t);

        public String displayName() {
            return GtLang.get("gtshaders.bind.semantic." + id);
        }

        /** 一句话说明这条绑法到底会在什么时候点亮，画在状态栏第二行。 */
        public String hint() {
            return GtLang.get("gtshaders.bind.semantic." + id + ".hint");
        }

    }

    /**
     * 当前准星上下文下可选的绑法。
     *
     * <p>按上下文收敛而不是固定给全部八种：指着一堵墙时「这一类死了」是个说不通的选项，
     * 而它出现在循环里的唯一后果，是让人按右键时多按两下。
     *
     * <p>每一批的<b>第一项就是默认项</b>——指着怪最常见的意图是「就这一只」，
     * 指着地面最常见的是「钉在这儿」，看着天最常见的是「打一发」。
     */
    public static List<Semantic> candidates(@Nullable BindTarget t) {
        if (t == null) {
            return List.of(Semantic.SELF);
        }
        return switch (t.kind()) {
            // 活物才有死亡和受伤事件；盔甲架、掉落物、船只有前三项有意义
            case ENTITY -> t.living()
                    ? List.of(Semantic.THIS_ONE, Semantic.THIS_ONE_SHOT, Semantic.THIS_KIND,
                            Semantic.KIND_ON_DEATH, Semantic.KIND_ON_HURT, Semantic.LOOK_FOLLOW)
                    : List.of(Semantic.THIS_ONE, Semantic.THIS_ONE_SHOT,
                            Semantic.THIS_KIND, Semantic.LOOK_FOLLOW);
            case BLOCK -> List.of(Semantic.HERE, Semantic.LOOK_SHOT, Semantic.SELF);
            case MISS -> List.of(Semantic.LOOK_SHOT, Semantic.SELF, Semantic.LOOK_FOLLOW);
        };
    }

    /** 把一次准星解析转成绑法要的纯数据。薄薄一层，全部的 MC 类型依赖都收在这里。 */
    private static @Nullable BindTarget targetOf(@Nullable Pick pick) {
        if (pick == null) {
            return null;
        }
        Entity e = pick.entity();
        if (e != null) {
            var key = BuiltInRegistries.ENTITY_TYPE.getKey(e.getType());
            return new BindTarget(Kind.ENTITY, e.getUUID().toString(),
                    key == null ? "" : key.toString(), posSelector(pick),
                    e instanceof LivingEntity);
        }
        return new BindTarget(pick.block() != null ? Kind.BLOCK : Kind.MISS,
                "", "", posSelector(pick), false);
    }

    /**
     * 坐标写成 {@code AnchorBinding.parseBlockPos} 认得的格式。
     *
     * <p>打中方块时用方块整数坐标——解算侧会给它加 0.5 落到方块中心，
     * 这才是「钉在那个方块上」。打空时没有方块可言，退回落点的小数坐标；
     * 写空串的话会造出一条永远解不出目标的死绑定，而面板上看不出任何异常。
     */
    private static String posSelector(Pick pick) {
        BlockPos p = pick.block();
        if (p != null) {
            return p.getX() + "," + p.getY() + "," + p.getZ();
        }
        Vec3 v = pick.point();
        return String.format(Locale.ROOT, "%.2f,%.2f,%.2f", v.x, v.y, v.z);
    }

    // ---------------------------------------------------------------- 状态

    public static boolean isActive() {
        return active;
    }

    /**
     * 退出时会不会自己回到某个界面。
     *
     * <p>从编辑器「出去绑」进来的模式带着回程，这时候外面就不该再自己开一次编辑器——
     * 那会多建一个 {@code EditorScreen}，并且丢掉回程里记着的那一页。
     */
    public static boolean hasReturnPath() {
        return onExit != null;
    }

    /**
     * 进入绑定模式。
     *
     * @param project  要编辑的工程。为 null 时不进入，由调用方提示
     * @param editing  进来时先编辑第几条绑定；负数表示沿用工程当前的选中项
     * @param returnTo 退出时执行的动作（从编辑器进来时用它重开编辑器）；可为 null
     * @return 是否真的进入了
     */
    public static boolean enter(@Nullable ShaderProject project, int editing, @Nullable Runnable returnTo) {
        if (project == null) {
            return false;
        }
        AnchorRuntime.setProject(project);
        if (editing >= 0) {
            project.setSelectedAnchorIndex(editing);
        }
        active = true;
        onExit = returnTo;
        deleteArmedAt = 0;
        // 缓存要在这里先填一次：状态栏是每帧画的，而 tick 最多要等 50 毫秒才来，
        // 中间那几帧会显示成「世界还没就绪」
        Minecraft mc = Minecraft.getInstance();
        cachedPick = AnchorRuntime.pick(mc, RANGE, 0f);
        cachedTarget = targetOf(cachedPick);
        // 进来先按当前准星把绑法定到那一批的第一项，省掉「进来先按几下右键」
        semantic = candidates(cachedTarget).getFirst();
        return true;
    }

    /** 退出。有回程动作就执行它——从编辑器进来的人应该回到编辑器，而不是被丢在世界里。 */
    public static void exit() {
        if (!active) {
            return;
        }
        active = false;
        deleteArmedAt = 0;
        Runnable back = onExit;
        onExit = null;
        if (back != null) {
            back.run();
        }
    }

    /** 每 tick 维护一次：世界没了、工程没了就自己退出，免得留一个没法操作的模式。 */
    public static void tick(Minecraft mc) {
        if (!active) {
            return;
        }
        if (mc.level == null || mc.player == null || AnchorRuntime.project() == null) {
            exit();
            cachedPick = null;
            cachedTarget = null;
            return;
        }
        cachedPick = AnchorRuntime.pick(mc, RANGE, 0f);
        cachedTarget = targetOf(cachedPick);
    }

    /** 本 tick 的准星解析结果，给 3D 标记读。模式没开或世界没就绪时是 null。 */
    public static @Nullable Pick currentPick() {
        return active ? cachedPick : null;
    }

    // ---------------------------------------------------------------- 操作

    /**
     * 左键：把当前绑法应用到正在编辑的那条绑定。
     *
     * @param newBinding true 表示 Shift+左键——新建一条再绑，而不是改现有的
     */
    public static void bind(Minecraft mc, boolean newBinding) {
        ShaderProject project = AnchorRuntime.project();
        if (project == null) {
            return;
        }
        BindTarget target = targetOf(AnchorRuntime.pick(mc, RANGE, 0f));
        if (target == null) {
            return;
        }
        // 上下文可能已经变了（选好绑法之后低头看了地面），先把绑法收回到合法的那一批里。
        // 不收的话会把「这一类死了」写到一个方块坐标上——一条永远不会触发的绑定
        List<Semantic> allowed = candidates(target);
        if (!allowed.contains(semantic)) {
            semantic = allowed.getFirst();
        }

        AnchorBinding b;
        if (newBinding || project.anchors().isEmpty()) {
            if (project.anchorSlotsUsed() >= AnchorSlot.SLOTS) {
                setFlash(GtLang.get("gtshaders.anchor.quick.full"));
                return;
            }
            b = new AnchorBinding(defaultName(mc, semantic));
            project.addAnchor(b);
        } else {
            b = project.selectedAnchor();
            if (b == null) {
                return;
            }
        }

        semantic.apply(b, target);
        b.setEnabled(true);
        // 名字跟着绑的东西走，但只在用户没自己改过名的时候。绑定列表和 3D 标签上
        // 显示的都是它——留着「Anchor」谁也认不出哪条是哪条
        if (isAutoName(b.name())) {
            b.setName(defaultName(mc, semantic));
        }

        int slot = project.anchorSlotBase(project.anchors().indexOf(b));
        setFlash(GtLang.get("gtshaders.bind.bound", b.name(), semantic.displayName(),
                slot < 0 ? "-" : String.valueOf(slot)));
    }

    /** 右键：在当前上下文允许的绑法里循环。 */
    public static void cycleSemantic(Minecraft mc, int dir) {
        List<Semantic> allowed = candidates(cachedTarget);
        int i = allowed.indexOf(semantic);
        if (i < 0) {
            semantic = allowed.getFirst();
        } else {
            semantic = allowed.get(Math.floorMod(i + dir, allowed.size()));
        }
        setFlash(semantic.displayName());
    }

    /** 滚轮：切换正在编辑哪一条绑定。 */
    public static void cycleBinding(int dir) {
        ShaderProject project = AnchorRuntime.project();
        if (project == null || project.anchors().isEmpty()) {
            return;
        }
        int n = project.anchors().size();
        project.setSelectedAnchorIndex(Math.floorMod(project.selectedAnchorIndex() + dir, n));
        // 换了编辑对象，删除确认就得作废——否则「按一下删除 → 滚到别条 → 再按一下」
        // 会删掉一条你根本没打算删的绑定
        deleteArmedAt = 0;
        AnchorBinding b = project.selectedAnchor();
        if (b != null) {
            setFlash(b.name());
        }
    }

    /** 中键：把当前绑定触发一次，看效果长什么样。 */
    public static void simulate() {
        ShaderProject project = AnchorRuntime.project();
        if (project == null) {
            return;
        }
        AnchorBinding b = project.selectedAnchor();
        if (b == null) {
            setFlash(GtLang.get("gtshaders.anchor.pick_no_binding"));
            return;
        }
        AnchorRuntime.simulate(b);
        setFlash(GtLang.get("gtshaders.bind.simulated", b.name()));
    }

    /**
     * 删除当前绑定。要按两次——第一次只是「举起来」，{@value #CONFIRM_WINDOW_MS} 毫秒内
     * 再按一次才真删。
     *
     * <p>之所以不是一下就删：删掉一条绑定会让它<b>后面所有绑定的槽位号往前挪</b>，
     * 而且没有撤销。虽然锚点参数存的是稳定 id 不会跟着漂，但源码里手写的
     * {@code gtAnchorUV(2)} 会——那正是这套设计要消灭的那类静默错误，不该由一次误触引入。
     */
    public static void deleteCurrent() {
        ShaderProject project = AnchorRuntime.project();
        if (project == null || project.anchors().isEmpty()) {
            return;
        }
        AnchorBinding b = project.selectedAnchor();
        if (b == null) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - deleteArmedAt > CONFIRM_WINDOW_MS) {
            deleteArmedAt = now;
            setFlash(GtLang.get("gtshaders.bind.delete_confirm", b.name()));
            return;
        }
        deleteArmedAt = 0;
        String name = b.name();
        AnchorRuntime.stop(b);
        project.removeAnchor(project.anchors().indexOf(b));
        setFlash(GtLang.get("gtshaders.bind.deleted", name));
    }

    /** 删除是否处于「已举起、等第二下」的状态，状态栏据此变色。 */
    public static boolean isDeleteArmed() {
        return deleteArmedAt > 0 && System.currentTimeMillis() - deleteArmedAt <= CONFIRM_WINDOW_MS;
    }

    // ---------------------------------------------------------------- 供 HUD 读

    public static Semantic semantic() {
        return semantic;
    }

    /** 最近一条操作反馈；过期后是空串。 */
    public static String flash() {
        return System.currentTimeMillis() - flashAt < 2000L ? flash : "";
    }

    /** 准星此刻指着什么的一句话描述，画在状态栏上。 */
    public static String describeTarget(Minecraft mc) {
        Pick pick = cachedPick;
        if (pick == null) {
            return GtLang.get("gtshaders.bind.target.none");
        }
        if (pick.entity() != null) {
            return pick.entity().getName().getString();
        }
        if (pick.block() != null) {
            BlockPos p = pick.block();
            return GtLang.get("gtshaders.bind.target.block", p.getX(), p.getY(), p.getZ());
        }
        return GtLang.get("gtshaders.bind.target.sky");
    }

    private static void setFlash(String text) {
        flash = text;
        flashAt = System.currentTimeMillis();
    }

    /**
     * 自动命名。绑到实体上就用实体名，绑到坐标上就用坐标。
     *
     * <p>加方括号是为了让 {@link #isAutoName} 认得出「这个名字是自动生成的、可以被覆盖」——
     * 用户自己起的名字不该在下一次重绑时被冲掉。
     */
    private static String defaultName(Minecraft mc, Semantic s) {
        Pick pick = AnchorRuntime.pick(mc, RANGE, 0f);
        if (pick != null && pick.entity() != null) {
            return "[" + pick.entity().getName().getString() + "]";
        }
        if (pick != null && pick.block() != null) {
            BlockPos p = pick.block();
            return "[" + p.getX() + " " + p.getY() + " " + p.getZ() + "]";
        }
        return "[" + s.displayName() + "]";
    }

    private static boolean isAutoName(String name) {
        return name.isBlank() || "Anchor".equals(name)
                || (name.startsWith("[") && name.endsWith("]"));
    }
}
