package mc.GTedd.cn.gtshaders.core;

import mc.GTedd.cn.gtshaders.i18n.GtLang;

import java.util.Locale;

/**
 * 一条锚点绑定：<b>把世界里的什么东西、在什么时候、以什么节奏</b>喂给着色器。
 *
 * <p>这是「对游戏中实体/物品添加效果」的配置侧。运行时（{@code AnchorRuntime}）按它去世界里
 * 找目标、监听触发事件、维护生命周期，最后解算成 {@link AnchorSlot} 写进 uniform。
 *
 * <h2>槽位是按顺序分配的</h2>
 *
 * <p>工程里的绑定列表<b>按先后顺序瓜分那 {@value AnchorSlot#SLOTS} 个槽位</b>：
 * 第一条绑定的 {@code maxSlots} 个槽排在最前，第二条接着排。所以着色器里
 * {@code gtAnchor(0)} 永远是列表里第一条绑定找到的第一个目标。
 *
 * <p>刻意<b>不做</b>「效果层引用某条绑定」的关联：那要多一层 id 引用、多一处会失效的指向，
 * 而作者本来就得在源码里写死一个槽位下标。让下标直接等于列表顺序，界面上把槽位号标出来，
 * 作者看着面板写 {@code gtAnchor(2)} 就行——少一层间接，也少一类「绑定删了效果还指着它」的坏状态。
 *
 * <h2>为什么触发器和跟随是两件事</h2>
 *
 * <p>{@link #stick} 单独拎出来而不是并进 {@link Trigger}，是因为同一个触发事件的两种用法都合理：
 * 玩家死亡触发的黑洞要<b>钉死在死亡坐标</b>（尸体几秒后就没了，跟着它会让黑洞跟着消失），
 * 而受伤触发的冲击波要<b>跟着人走</b>（挨打的人还在跑）。合并成一个枚举就得写出
 * {@code ON_DEATH_STICK} / {@code ON_DEATH_FOLLOW} 这种笛卡尔积。
 */
public final class AnchorBinding {

    /** 锚点从世界里的什么东西上取坐标。 */
    public enum Source {
        /** 玩家自己。第一人称下就在屏幕中央，第三人称/旁观视角才有意义。 */
        SELF("self", false),
        /** 准星当前指着的实体。适合「看着谁就给谁上效果」。 */
        LOOK_AT("look_at", false),
        /**
         * 视线落点：命中的方块面或实体上的那个交点。「空中激光落地打击」的落点用它。
         *
         * <p>射线是运行时自己射的，射程 128 格，<b>不受玩家交互距离限制</b>——
         * 看着几十格外的地面按一下，落点就在那儿。
         */
        LOOK_HIT("look_hit", false),
        /** 在游戏里拾取过的那个具体实体，按 UUID 记住，走远了也还是它。 */
        ENTITY_UUID("entity_uuid", true),
        /** 按实体类型批量匹配，如 {@code minecraft:zombie}。视野内每个符合的各占一个槽位。 */
        ENTITY_TYPE("entity_type", true),
        /** 掉落物实体。选择器留空匹配全部，填物品 id 则只认那一种。 */
        ITEM_ENTITY("item_entity", true),
        /** 手持指定物品的实体（含玩家自己）。选择器是物品 id。 */
        HELD_ITEM("held_item", true),
        /** 固定世界坐标，选择器写 {@code x,y,z}。信标、传送门这类钉死在地图上的东西用它。 */
        BLOCK_POS("block_pos", true);

        private final String id;
        private final boolean needsSelector;

        Source(String id, boolean needsSelector) {
            this.id = id;
            this.needsSelector = needsSelector;
        }

        public String id() {
            return id;
        }

        /** 是否需要填选择器。不需要的来源把选择器输入框禁掉，免得填了没反应。 */
        public boolean needsSelector() {
            return needsSelector;
        }

        /**
         * 这个来源配事件型触发器能不能真的点亮。
         *
         * <p>{@link #BLOCK_POS} 和 {@link #LOOK_HIT} 身上没有实体，事件采集那一趟
         * 压根扫不到它们——配了「死亡时」也永远不会触发，而界面上看不出任何异常。
         *
         * <p>{@link #LOOK_AT} 在技术上能触发，但实体死掉的那一 tick 你的准星几乎
         * 不可能还正好指着它，实践中等于不会触发，所以一并排除。想要「看谁打谁」
         * 那种语义应该配「一直有效」。
         */
        public boolean canFireEvents() {
            return this != BLOCK_POS && this != LOOK_HIT && this != LOOK_AT;
        }

        public String displayName() {
            return GtLang.get("gtshaders.anchor.source." + id);
        }

        public static Source byId(String id) {
            for (Source s : values()) {
                if (s.id.equals(id)) {
                    return s;
                }
            }
            return SELF;
        }
    }

    /** 什么时候把锚点点亮。 */
    public enum Trigger {
        /** 一直有效，每帧跟着目标走。适合持续性的领域、护盾、标记。 */
        ALWAYS("always"),
        /** 目标死亡的那一刻。客户端靠 {@code LivingEntity.deathTime} 从 0 变 1 的那一 tick 认出来。 */
        ON_DEATH("on_death"),
        /** 目标受伤的那一刻，靠 {@code hurtTime} 被重置成 {@code hurtDuration} 认出来。 */
        ON_HURT("on_hurt"),
        /** 目标进入视野/被加载出来的那一刻。 */
        ON_SPAWN("on_spawn"),
        /** 只由编辑器里的「模拟触发」或外部调用点亮。调效果时用这个，不用真的去死一次。 */
        MANUAL("manual");

        private final String id;

        Trigger(String id) {
            this.id = id;
        }

        public String id() {
            return id;
        }

        /** 是不是「事件型」——一次性点亮然后按时长衰减。{@link #ALWAYS} 之外都是。 */
        public boolean isEvent() {
            return this != ALWAYS;
        }

        /**
         * 这个触发器要不要靠<b>世界事件采集</b>来点亮。
         *
         * <p>{@link #MANUAL} 不要：它由 {@code AnchorRuntime.simulate} 直接解算目标并点亮，
         * 那条路径认得每一种来源，包括身上没有实体的方块坐标和视线落点。
         * 所以 {@code MANUAL} 配任何来源都是合法的。
         *
         * <p>这个判据以前散在两处，而且<b>两处不一致</b>：{@link #warning()} 正确地放过了
         * MANUAL，{@code applyTriggerDefaults} 却没有。后果是在面板上把来源选成
         * 「准星指向的实体」再把触发器改成「仅手动」，来源会被悄悄改回「实体类型」——
         * 界面上没有任何提示说它动过，人只会觉得「这个下拉选不上」。收成一个方法就不会再分叉。
         */
        public boolean needsEventSource() {
            return isEvent() && this != MANUAL;
        }

        public String displayName() {
            return GtLang.get("gtshaders.anchor.trigger." + id);
        }

        public static Trigger byId(String id) {
            for (Trigger t : values()) {
                if (t.id.equals(id)) {
                    return t;
                }
            }
            return ALWAYS;
        }
    }

    /**
     * 强度随生命周期怎么变。
     *
     * <p>这条曲线只影响写进 uniform 的 {@code strength}；着色器里那个 {@code life}
     * 始终是没加工过的 0..1 原始进度，想自己写曲线的作者用它。
     */
    public enum Easing {
        /** 恒定满强度，到时间直接消失。 */
        HOLD("hold"),
        LINEAR("linear"),
        /** 慢起：{@code t²}。蓄力、坍缩这类由弱变强的用它。 */
        EASE_IN("ease_in"),
        /** 快起慢收：{@code 1-(1-t)²}。爆炸、闪光这类瞬间到顶再拖尾的用它。 */
        EASE_OUT("ease_out"),
        EASE_IN_OUT("ease_in_out"),
        /** 先涨后落的一个半波：{@code sin(πt)}。冲击波、脉冲扫描这类一闪而过的用它。 */
        PULSE("pulse");

        private final String id;

        Easing(String id) {
            this.id = id;
        }

        public String id() {
            return id;
        }

        /**
         * @param t 生命周期进度，调用方保证已经钳在 0..1
         * @return 强度系数 0..1
         */
        public float apply(float t) {
            return switch (this) {
                case HOLD -> 1f;
                case LINEAR -> t;
                case EASE_IN -> t * t;
                case EASE_OUT -> 1f - (1f - t) * (1f - t);
                // smoothstep。写成这个形式而不是调库，是为了和着色器里的 smoothstep 逐位一致
                case EASE_IN_OUT -> t * t * (3f - 2f * t);
                case PULSE -> (float) Math.sin(Math.PI * t);
            };
        }

        public String displayName() {
            return GtLang.get("gtshaders.anchor.easing." + id);
        }

        public static Easing byId(String id) {
            for (Easing e : values()) {
                if (e.id.equals(id)) {
                    return e;
                }
            }
            return HOLD;
        }
    }

    /**
     * 载体的朝向从哪来。
     *
     * <p>{@code some_of_fx} 的载体是 {@code item_display}，朝向就是实体自己的旋转，
     * 核心着色器直接把切线和副切线编码出去。我们这边目标未必是展示实体
     * （可能是一只僵尸、一个方块坐标），所以要说清楚朝向该问谁。
     */
    public enum Facing {
        /** 不用朝向。圆对称的效果走这条，解算时一次三角函数都不算。 */
        NONE("none"),
        /** 跟着目标实体的视线走。僵尸头朝哪、光就照哪。固定坐标类的来源退化成 {@link #FIXED}。 */
        TARGET("target"),
        /** 面板上填死的 yaw/pitch。放置假载体时默认记下玩家当时的朝向。 */
        FIXED("fixed"),
        /**
         * 永远正对相机。
         *
         * <p>这不是偷懒——辉光、冲击波这类本来就该是公告板（billboard），
         * 让它有一个"真实"的三维朝向反而会在侧看时塌成一条线。
         */
        CAMERA("camera");

        private final String id;

        Facing(String id) {
            this.id = id;
        }

        public String id() {
            return id;
        }

        public String displayName() {
            return GtLang.get("gtshaders.anchor.facing." + id);
        }

        public static Facing byId(String id) {
            for (Facing f : values()) {
                if (f.id.equals(id)) {
                    return f;
                }
            }
            return NONE;
        }
    }

    /**
     * 稳定标识。绑定建出来就定死，之后改名、换来源、上下移动都不变。
     *
     * <p>存在的理由是<b>槽位号会漂移</b>：{@link ShaderProject#anchorSlotBase} 按绑定在列表里的
     * 顺序累加 {@link #maxSlots} 算出来，所以插一条、停一条、拖一下顺序，
     * 之前手填在效果层里的那个数字就指向别的目标了——而这件事没有任何报错，
     * 画面上只是效果跑到了别处。{@link ParamType#ANCHOR} 类型的参数存这个 id 而不是数字，
     * 每次编译前重新解析，于是顺序怎么变都指得对。
     *
     * <p>不用名字当 id：名字是给人看的，允许重复也允许随时改。
     */
    private String id = java.util.UUID.randomUUID().toString();

    /**
     * 屏幕半径上限的默认值：约三分之一屏高。
     *
     * <p>这个数是这么来的：绑在十几格外的落点上（也就是按 B 键打一发的典型距离），
     * 默认 1.5 格半径投影出来是 0.07 上下；而绑在实体上时人和目标通常隔 2-4 格，
     * 不设限就是 0.36-0.71。取 0.35 让近距离那一档收在「明显但看得清全貌」的位置，
     * 同时远距离那一档完全不受影响——两种绑法于是给出同一个量级的观感。
     */
    public static final float DEFAULT_MAX_SCREEN_RADIUS = 0.35f;

    private String name;
    private Source source = Source.LOOK_AT;
    private String selector = "";
    private Trigger trigger = Trigger.ALWAYS;
    /** 事件触发后钉在事件坐标（true）还是继续跟着目标走（false）。{@link Trigger#ALWAYS} 下无意义。 */
    private boolean stick = true;
    /** 生命周期时长（秒）。{@code <= 0} 表示常驻——此时 life 恒为 1 且不应用缓动。 */
    private float duration = 2.5f;
    private Easing easing = Easing.EASE_OUT;
    /** 目标的世界半径（方块），决定投影出来的屏幕半径有多大。 */
    private float worldRadius = 1.5f;
    /** 基准强度，缓动曲线在它之上做乘法。 */
    private float strength = 1f;
    /** 最多占几个槽位。{@link Source#ENTITY_TYPE} 这类可能匹配到多个目标时才 > 1。 */
    private int maxSlots = 1;
    /** 是否做遮挡测试：目标被方块挡住时把 visibility 压到 0。 */
    private boolean occlusion;
    /** 相对目标脚部的高度偏移（方块）。默认抬到胸口，效果打在脚底板上很少是想要的。 */
    private float yOffset = 1f;
    /** 超过这个距离（方块）就不生成锚点；{@code <= 0} 表示不限。 */
    private float maxDistance;
    /**
     * 投影出来的屏幕半径上限，单位是<b>纵向 UV</b>（1.0 = 一整屏高）。{@code <= 0} 表示不限。
     *
     * <h2>为什么必须有这个东西</h2>
     *
     * <p>屏幕半径是 {@code worldRadius / (dist · 2 · tan(fov/2))}——距离在分母上，
     * 所以走近目标时它<b>无界增长</b>。默认的 1.5 格半径在 15 格外投影出 0.07（一屏高的 7%），
     * 而在 1 格外是 1.07，比整个屏幕还高。
     *
     * <p>数学上没错——世界里一个 1.5 格的球贴到脸上确实占满视野。但效果作者写
     * {@code gtAnchorRadius} 时想的是「这个东西在画面上有多大」，没有人为「贴脸」那一档
     * 设计过观感。表现出来就是：同一个奇点炸弹，绑在远处落点上是个漂亮的黑洞，
     * 绑到一只怪身上、你走近它，整个屏幕变成纯黑。
     *
     * <p>所以给一个默认打开的上限。它不是「又一个要调的旋钮」，是一张安全网：
     * 远距离时根本碰不到它，近距离时把效果按在一个还能看清全貌的尺度上。
     * 真想要糊满屏幕的人把它设成 0。
     */
    private float maxScreenRadius = DEFAULT_MAX_SCREEN_RADIUS;
    private boolean enabled = true;

    // ---- 载体扩展。语义与「为什么只留这几样」见 {@link EmitterSlot} ----

    /**
     * 子类型序号 0..{@link EmitterSlot#MAX_TYPES}-1，着色器里 {@code gtEmitterType(i)} 读到的就是它。
     *
     * <p>之所以是绑定的属性而不是效果的 {@code @param}：参数属于<b>层</b>，
     * 而这个要能在同一层里区分开每一个载体——「这盏是球光、那盏是聚光」。
     */
    private int emitterType;
    /** 作者自定的数值之一。同上，是<b>每个载体各一份</b>的东西。 */
    private float custom1;
    /** 作者自定的数值之二。 */
    private float custom2;
    /**
     * 朝向怎么定。
     *
     * <p>圆对称的效果用不上朝向，所以默认是「不用」——那样解算时连三角函数都不算，
     * 而且面板上少三行。要面朝向的效果（矩形面光、聚光、法阵）才切到别的模式。
     */
    private Facing facing = Facing.NONE;
    /** {@link Facing#FIXED} 时的水平朝向（度）。放置时默认取玩家当时的朝向。 */
    private float yaw;
    /** {@link Facing#FIXED} 时的俯仰（度）。 */
    private float pitch;
    /**
     * 自转速度（度/秒）。写进 {@code gtEmitterSpin}，让同类载体不在同一拍上。
     *
     * <p>0 表示不转，此时 spin 恒为 0——而不是「转速为 0 但相位随机」，
     * 因为那会让同一个载体在两次进入视野时长得不一样。
     */
    private float spin;

    public AnchorBinding(String name) {
        this.name = name == null || name.isBlank() ? "Anchor" : name;
    }

    /** 稳定标识，见 {@link #id} 字段注释。 */
    public String id() {
        return id;
    }

    /**
     * 从存档恢复 id。只该由 {@code ProjectStore} 在反序列化时调用。
     *
     * <p>空值忽略——format 8 之前的工程没存 id，那些绑定拿构造时生成的新 id 即可：
     * 它们的层参数存的本来就是裸数字，没有 anchorRef 会失配。
     */
    public void restoreId(String saved) {
        if (saved != null && !saved.isBlank()) {
            this.id = saved;
        }
    }

    /**
     * 按触发器给一套合理的默认值。
     *
     * <p>「死亡」默认钉住 + 慢起，因为坍缩类效果是这个节奏；「受伤」默认跟随 + 脉冲，
     * 因为挨打的人还在动而效果应该一闪而过。这些默认值省掉的是新手最容易配错的两处。
     */
    public static AnchorBinding withTrigger(String name, Trigger trigger) {
        AnchorBinding b = new AnchorBinding(name);
        b.trigger = trigger == null ? Trigger.ALWAYS : trigger;
        b.applyTriggerDefaults(true);
        return b;
    }

    /**
     * 按当前触发器铺一套能直接跑起来的默认值。
     *
     * @param resetSource 是否连来源一起定。新建绑定时要，中途换触发器时只在
     *                    「当前来源根本不可能触发」的情况下才动它
     */
    private void applyTriggerDefaults(boolean resetSource) {
        switch (trigger) {
            case ON_DEATH -> {
                stick = true;
                duration = 2.5f;
                easing = Easing.EASE_IN;
                worldRadius = 2.5f;
            }
            case ON_HURT -> {
                stick = false;
                duration = 0.6f;
                easing = Easing.PULSE;
            }
            case ON_SPAWN -> {
                stick = false;
                duration = 1.2f;
                easing = Easing.EASE_OUT;
            }
            case MANUAL -> {
                // 跟随而不是钉住：MANUAL 最常见的用法就是「看着那只怪按一下」，
                // 而钉住会把效果留在按下那一刻的坐标上，怪走开效果还在原地。
                // 对没有实体的来源（视线落点、方块坐标）这一项无影响——
                // simulate 记的 uuid 本来就是 null，跟不跟随都是钉在原地
                stick = false;
                // 6 秒而不是 2.5：手动触发是用来「打一发完整的」看效果，
                // 而演出型效果（奇点炸弹这类）光蓄力就要三秒以上，2.5 秒只够播个开头就硬切。
                // B 键自动建的那条测试绑定给的也是这个值，两条路径于是长得一样
                duration = 6f;
                easing = Easing.EASE_OUT;
            }
            case ALWAYS -> {
                stick = false;
                duration = 0f;
                easing = Easing.HOLD;
            }
        }
        if (trigger.needsEventSource() && (resetSource || !source.canFireEvents())) {
            // 事件型配 LOOK_AT 是不成立的：实体死掉的那一 tick，准星几乎不可能还正好指着它。
            // 留空选择器的 ENTITY_TYPE = 全部生物，于是「随便打死点什么就能看见效果」，
            // 想收窄再去填具体的实体 id
            source = Source.ENTITY_TYPE;
        }
    }

    public String name() {
        return name;
    }

    public void setName(String name) {
        if (name != null && !name.isBlank()) {
            this.name = name;
        }
    }

    public Source source() {
        return source;
    }

    public void setSource(Source source) {
        if (source != null) {
            this.source = source;
        }
    }

    public String selector() {
        return selector;
    }

    public void setSelector(String selector) {
        this.selector = selector == null ? "" : selector.trim();
    }

    public Trigger trigger() {
        return trigger;
    }

    /**
     * 换触发器。
     *
     * <p><b>在「常驻 ↔ 事件」之间切换时会把配套默认值一起带上</b>，这不是画蛇添足，
     * 是在堵一个致命的坑：常驻绑定的 {@code duration} 就是 {@code 0}
     * （{@link #isPersistent()} 正是靠它判定的），而事件型绑定带着 0 时长意味着
     * {@code life} 恒等于 1——效果一上来就停在「已经播完」的那一帧。
     *
     * <p>对天降激光这类效果，{@code t == 1} 时光柱、冲击环、落点闪光的包络<b>全是 0</b>，
     * 画面上什么都不会出现。于是症状就是「把触发器改成死亡时之后完全不生效」，
     * 而面板上每一项看起来都填得好好的。
     *
     * <p>同为事件型之间互相切换（死亡 → 受伤）不动已有数值——那些是作者自己调过的。
     */
    public void setTrigger(Trigger trigger) {
        if (trigger == null || trigger == this.trigger) {
            return;
        }
        boolean kindChanged = trigger.isEvent() != this.trigger.isEvent();
        this.trigger = trigger;
        if (kindChanged) {
            applyTriggerDefaults(false);
        } else if (trigger.isEvent() && duration <= 0f) {
            // 兜底：不管从哪条路走到这里，事件型绑定都不该带着 0 时长
            duration = 2.5f;
        }
    }

    public boolean isStick() {
        return stick;
    }

    public void setStick(boolean stick) {
        this.stick = stick;
    }

    public float duration() {
        return duration;
    }

    public void setDuration(float duration) {
        this.duration = Math.max(0f, Math.min(60f, duration));
    }

    /** 常驻绑定：没有时长，life 恒为 1，不应用缓动。 */
    public boolean isPersistent() {
        return duration <= 0f;
    }

    /**
     * 这条绑定有没有「配好了但永远不会生效」的组合。
     *
     * <p>为什么值得单独做一个检查：锚点这条链上出问题时<b>没有任何报错</b>——
     * 着色器照常编译、画面照常渲染、面板上每一项都填着值，只是效果不出现。
     * 靠人回头逐项核对是不现实的，必须由界面主动说出来。
     *
     * @return 提示文案的 lang key；配置没问题时返回空串
     */
    public String warning() {
        if (!trigger.isEvent()) {
            return "";
        }
        if (trigger.needsEventSource() && !source.canFireEvents()) {
            return "gtshaders.anchor.warn.source";
        }
        if (isPersistent()) {
            // duration 为 0 时 life 恒为 1，效果一上来就停在「已经播完」那一帧
            return "gtshaders.anchor.warn.duration";
        }
        return "";
    }

    public Easing easing() {
        return easing;
    }

    public void setEasing(Easing easing) {
        if (easing != null) {
            this.easing = easing;
        }
    }

    public float worldRadius() {
        return worldRadius;
    }

    public void setWorldRadius(float r) {
        this.worldRadius = Math.max(0.05f, Math.min(64f, r));
    }

    public float strength() {
        return strength;
    }

    public void setStrength(float s) {
        this.strength = Math.max(0f, Math.min(1f, s));
    }

    public int maxSlots() {
        return maxSlots;
    }

    public void setMaxSlots(int n) {
        this.maxSlots = Math.max(1, Math.min(AnchorSlot.SLOTS, n));
    }

    public boolean isOcclusion() {
        return occlusion;
    }

    public void setOcclusion(boolean occlusion) {
        this.occlusion = occlusion;
    }

    public float yOffset() {
        return yOffset;
    }

    public void setYOffset(float y) {
        this.yOffset = Math.max(-8f, Math.min(32f, y));
    }

    public float maxDistance() {
        return maxDistance;
    }

    public float maxScreenRadius() {
        return maxScreenRadius;
    }

    public void setMaxScreenRadius(float r) {
        this.maxScreenRadius = Math.max(0f, Math.min(2f, r));
    }

    public void setMaxDistance(float d) {
        this.maxDistance = Math.max(0f, Math.min(512f, d));
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    // ---------------------------------------------------------------- 载体扩展

    public int emitterType() {
        return emitterType;
    }

    public void setEmitterType(int t) {
        this.emitterType = Math.max(0, Math.min(EmitterSlot.MAX_TYPES - 1, t));
    }

    public float custom1() {
        return custom1;
    }

    public void setCustom1(float v) {
        this.custom1 = clampCustom(v);
    }

    public float custom2() {
        return custom2;
    }

    public void setCustom2(float v) {
        this.custom2 = clampCustom(v);
    }

    /**
     * 自定义值的量程。
     *
     * <p>钳在 ±64 而不是放开：这两个值是直接喂给着色器做乘法的，
     * 面板上手滑拖出个几万会让整屏变成一片纯白，而那种"画面全白"的故障
     * 看不出是哪一处出的问题。
     */
    private static float clampCustom(float v) {
        if (Float.isNaN(v)) {
            return 0f;
        }
        return Math.max(-64f, Math.min(64f, v));
    }

    public Facing facing() {
        return facing;
    }

    public void setFacing(Facing f) {
        if (f != null) {
            this.facing = f;
        }
    }

    public float yaw() {
        return yaw;
    }

    public void setYaw(float y) {
        this.yaw = wrapDegrees(y);
    }

    public float pitch() {
        return pitch;
    }

    public void setPitch(float p) {
        // 俯仰钳在 ±90：超过就等于把载体翻了个面，而 yaw 那边已经能表达同一个朝向了。
        // 不钳的话面板上一路拖下去会出现"转着转着突然翻转"的跳变
        this.pitch = Math.max(-90f, Math.min(90f, p));
    }

    private static float wrapDegrees(float deg) {
        float d = deg % 360f;
        if (d >= 180f) {
            d -= 360f;
        }
        if (d < -180f) {
            d += 360f;
        }
        return d;
    }

    public float spin() {
        return spin;
    }

    public void setSpin(float s) {
        this.spin = Math.max(-720f, Math.min(720f, s));
    }

    /** 这条绑定要不要算朝向。全是 {@link Facing#NONE} 时运行时整趟解算都能跳过。 */
    public boolean usesFacing() {
        return facing != Facing.NONE;
    }

    /**
     * 把生命周期进度换算成写进 uniform 的强度。
     *
     * <p>常驻绑定直接给基准强度：{@link Easing#PULSE} 之类的曲线在 {@code t == 1} 处是 0，
     * 而常驻的东西在「进度 100%」时消失显然不是任何人想要的。
     */
    public float strengthAt(float life) {
        if (isPersistent()) {
            return strength;
        }
        float t = Math.max(0f, Math.min(1f, life));
        return strength * easing.apply(t);
    }

    /**
     * 解析 {@link Source#BLOCK_POS} 的选择器。
     *
     * @return 长度为 3 的坐标数组；格式不对时返回 null（界面据此提示，而不是悄悄用 0,0,0）
     */
    public static double @org.jspecify.annotations.Nullable [] parseBlockPos(String selector) {
        if (selector == null) {
            return null;
        }
        String[] parts = selector.replace('，', ',').split("[,\\s]+");
        if (parts.length < 3) {
            return null;
        }
        double[] out = new double[3];
        for (int i = 0; i < 3; i++) {
            try {
                out[i] = Double.parseDouble(parts[i].trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return out;
    }

    /**
     * 把用户填的裸 id 补全成带命名空间的形式。
     *
     * <p>填 {@code zombie} 和填 {@code minecraft:zombie} 应该是一回事——要求手打命名空间
     * 是那种「本来就该由工具做掉」的琐事。
     */
    public static String normalizeId(String raw) {
        if (raw == null) {
            return "";
        }
        String s = raw.trim().toLowerCase(Locale.ROOT);
        if (s.isEmpty()) {
            return "";
        }
        return s.indexOf(':') >= 0 ? s : "minecraft:" + s;
    }

    public AnchorBinding copy() {
        AnchorBinding c = new AnchorBinding(name);
        c.source = source;
        c.selector = selector;
        c.trigger = trigger;
        c.stick = stick;
        c.duration = duration;
        c.easing = easing;
        c.worldRadius = worldRadius;
        c.strength = strength;
        c.maxSlots = maxSlots;
        c.occlusion = occlusion;
        c.yOffset = yOffset;
        c.maxDistance = maxDistance;
        c.enabled = enabled;
        c.emitterType = emitterType;
        c.custom1 = custom1;
        c.custom2 = custom2;
        c.facing = facing;
        c.yaw = yaw;
        c.pitch = pitch;
        c.spin = spin;
        return c;
    }
}
