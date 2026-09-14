package mc.GTedd.cn.gtshaders;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import org.jspecify.annotations.Nullable;
import mc.GTedd.cn.gtshaders.core.AnchorBinding;
import mc.GTedd.cn.gtshaders.core.AnchorSlot;
import mc.GTedd.cn.gtshaders.core.ShaderProject;
import mc.GTedd.cn.gtshaders.i18n.GtLang;
import mc.GTedd.cn.gtshaders.runtime.AnchorCheck;
import mc.GTedd.cn.gtshaders.runtime.AnchorRuntime;
import mc.GTedd.cn.gtshaders.runtime.BindMode;
import mc.GTedd.cn.gtshaders.runtime.GlowRuntime;
import mc.GTedd.cn.gtshaders.runtime.PreviewRuntime;
import mc.GTedd.cn.gtshaders.ui.AnchorGizmos;
import mc.GTedd.cn.gtshaders.ui.AnchorHud;
import mc.GTedd.cn.gtshaders.ui.BindModeHud;
import mc.GTedd.cn.gtshaders.ui.EditorScreen;
import mc.GTedd.cn.gtshaders.workspace.Workspace;
import java.util.Locale;

public final class GTShadersClient implements ClientModInitializer {

    private static KeyMapping openEditorKey;
    private static KeyMapping togglePreviewKey;
    private static KeyMapping bindModeKey;
    private static KeyMapping triggerCrosshairKey;
    private static KeyMapping toggleGlowKey;

    @Override
    public void onInitializeClient() {
        GtLang.setWarnSink(GTShaders.LOGGER::warn);
        // 启动就跟着游戏语言走。之前默认英文、等到 tick 里发现不一致才切，
        // 结果是开局那一小段时间界面语言是错的，而且 mod 初始化期间产生的文案也会是英文。
        GtLang.setCurrentLang(detectGameLanguage());

        Workspace.ensureLayout();
        GtLang.reloadExternal(Workspace.langDir());

        KeyMapping.Category category = KeyMapping.Category.register(
                Identifier.fromNamespaceAndPath(GTShaders.MOD_ID, "main"));

        openEditorKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.gtshaders.open_editor",
                InputConstants.Type.KEYBOARD,
                InputConstants.KEY_INSERT,
                category));
        togglePreviewKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.gtshaders.toggle_preview",
                InputConstants.Type.KEYBOARD,
                InputConstants.KEY_DELETE,
                category));
        // 绑定模式：全部绑定操作的唯一入口，必须在编辑器<b>关着</b>的时候能用——
        // 编辑器一打开鼠标就归界面了，没法再用准星去瞄任何东西，而「看着它按一下」
        // 正是绑定实体最直觉的做法。以前的拾取（Home）、触发选中项（End）、
        // 快建绑定（PageDown）三个键做的都是这件事的一部分，现在它们是模式里的
        // 左键、中键、Shift+左键
        bindModeKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.gtshaders.bind_mode",
                InputConstants.Type.KEYBOARD,
                InputConstants.KEY_HOME,
                category));
        // 触发键：把<b>当前选中的那条</b>绑定打一发，不管它绑的是准星落点还是某只怪。
        // 从前它写死了「找一条视线落点的绑定，没有就建一条」，于是在面板里把锚点绑到实体上之后
        // 按它，触发的是另一条毫不相干的绑定——配好的那条永远没机会亮。
        // 一条绑定都没有时才自动建一条视线落点的，保住「装上就能按一下看见东西」
        triggerCrosshairKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.gtshaders.trigger_crosshair",
                InputConstants.Type.KEYBOARD,
                InputConstants.KEY_B,
                category));
        // 实体轮廓链只在「有实体发光」时才被引擎执行，所以调这类效果的第一步永远是
        // 先让某个东西发光。给它一个键，省掉每次都要切出去 /effect give 一遍
        toggleGlowKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.gtshaders.toggle_glow",
                InputConstants.Type.KEYBOARD,
                InputConstants.KEY_PAGEUP,
                category));

        ClientTickEvents.END_CLIENT_TICK.register(GTShadersClient::onTick);
        AnchorHud.register();
        BindModeHud.register();

        GTShaders.LOGGER.info("GTShaders loaded (lang={}): Insert = editor, Delete = toggle preview",
                GtLang.currentLang());
    }

    /**
     * 读取游戏当前语言。初始化时机很早，{@code options} 可能还没建好，
     * 拿不到就先用回退语言，随后 tick 里会立刻纠正。
     */
    private static String detectGameLanguage() {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc != null && mc.options != null && mc.options.languageCode != null) {
                return mc.options.languageCode;
            }
        } catch (RuntimeException ignored) {
            // 初始化早期访问 options 可能抛异常，回退即可，不该因此拦住 mod 加载
        }
        return GtLang.FALLBACK_LANG;
    }

    private static void onTick(Minecraft mc) {
        // 玩家可能在设置里改语言，跟着走一下，界面与参数标签才会跟着变
        String lang = mc.options.languageCode;
        if (lang != null && !lang.equalsIgnoreCase(GtLang.currentLang())) {
            GtLang.setCurrentLang(lang);
        }

        while (openEditorKey.consumeClick()) {
            // 编辑器一开鼠标就归界面，绑定模式的左右键再也收不到事件——留着它只会让人
            // 关掉编辑器之后发现自己还在一个不知道什么时候进去的模式里。
            //
            // 从编辑器「出去绑」进来的模式自带回程，让它自己带回去即可；再自己开一次
            // 会白白多建一个 EditorScreen，而且丢掉回程里记着的那一页
            boolean returnsItself = BindMode.hasReturnPath();
            BindMode.exit();
            if (!returnsItself) {
                mc.gui.setScreen(new EditorScreen(mc.gui.screen()));
            }
        }
        while (togglePreviewKey.consumeClick()) {
            if (PreviewRuntime.isActive()) {
                PreviewRuntime.disable();
            } else {
                PreviewRuntime.activate();
            }
        }

        while (bindModeKey.consumeClick()) {
            toggleBindMode(mc);
        }
        while (triggerCrosshairKey.consumeClick()) {
            triggerSelected(mc);
        }
        while (toggleGlowKey.consumeClick()) {
            toggleCrosshairGlow(mc);
        }

        // 世界没了、工程没了就自动退出，免得留一个没法操作的模式
        BindMode.tick(mc);
        // 锚点标记走 Minecraft.collectPerTickGizmos()，必须在 tick 期间画
        AnchorGizmos.tick(mc);

        // 强制发光每 tick 都要补：服务端的实体数据同步会把标记冲掉
        GlowRuntime.tick();

        // 死亡、受伤这些是离散事件，只在某一 tick 发生一次——必须跟着 tick 采集，
        // 放到渲染帧里去查会因为一帧跨不过一个 tick 而漏掉，或者同一次死亡被记好几遍
        AnchorRuntime.tick();
    }

    /**
     * 进/出绑定模式。
     *
     * <p>没有工程时不进——绑定模式的每一个操作都要往工程里写东西，进去了也只能看着
     * 一个什么都做不了的状态栏。这时候该说的是「先建个工程」而不是让人自己发现。
     */
    private static void toggleBindMode(Minecraft mc) {
        if (BindMode.isActive()) {
            BindMode.exit();
            say(mc, GtLang.get("gtshaders.bind.exited"));
            return;
        }
        ShaderProject project = activeProject();
        if (project == null) {
            say(mc, GtLang.get("gtshaders.anchor.quick.no_project"));
            return;
        }
        BindMode.enter(project, -1, null);
        say(mc, GtLang.get("gtshaders.bind.entered"));
        // 下游断了一环的话，绑定配得再对画面上也什么都不会发生。
        // 这是整条链上最容易被忽略的一环，进模式的第一时间就该说出来；
        // 而「预览没开」和「没有层读锚点」要分开说，两者的下一步动作完全不同
        if (!PreviewRuntime.isActive()) {
            say(mc, GtLang.get("gtshaders.bind.no_preview"));
        } else if (!PreviewRuntime.usesAnchors()) {
            say(mc, GtLang.get("gtshaders.anchor.quick.no_layer"));
        }
    }


    /**
     * 让准星指着的实体本地发光，好预览实体轮廓效果。
     *
     * <p>只影响本地渲染，不会同步给别人——真要让全服看见得用
     * {@code /effect give <target> minecraft:glowing}。
     */
    private static void toggleCrosshairGlow(Minecraft mc) {
        Entity target = mc.crosshairPickEntity;
        if (target == null) {
            say(mc, GtLang.get("gtshaders.outline.glow_none"));
            return;
        }
        boolean on = GlowRuntime.toggle(target);
        say(mc, GtLang.get(on ? "gtshaders.outline.glow_on" : "gtshaders.outline.glow_off",
                target.getName().getString()));
    }


    /**
     * 检查<b>当前选中的那条</b>锚点绑定——按下去一定会知道它现在处于什么状态。
     *
     * <p>一个动作，按触发类型分成两种含义，但<b>两种都给可执行的结论</b>：
     * <ul>
     *   <li><b>事件型</b>（手动 / 死亡 / 受伤 / 生成）：打一发，然后报链路。</li>
     *   <li><b>常驻型</b>：没有「触发」可言，它本来就一直亮着。所以这里不是拒绝，
     *       而是把它<b>现在</b>的真实状态读出来——解到目标没有、在屏幕什么位置、被没被挡住。
     *       从前这一支直接返回一句「不需要触发」，那是个死胡同：人想知道的是
     *       「那我为什么没看见」，而这句话一个字都没回答。</li>
     * </ul>
     *
     * <p>两条路径共用 {@link AnchorCheck} 的配置链检查。链上有六个环节
     * （启用、槽位、预览、有层读锚点、读的是不是这一条、目标解算），
     * 断掉任何一个画面表现都一样是「什么都没有」，所以必须逐级报，不能笼统说「检查一下」。
     *
     * <p>一条绑定都没有时才建一条测试绑定，见 {@link #createTestBinding}。
     */
    private static void triggerSelected(Minecraft mc) {
        ShaderProject project = activeProject();
        if (project == null) {
            say(mc, GtLang.get("gtshaders.anchor.quick.no_project"));
            return;
        }

        AnchorBinding binding = project.selectedAnchor();
        if (binding == null) {
            binding = createTestBinding(mc, project);
            if (binding == null) {
                return;
            }
        }

        // 槽位号从绑定的稳定 id 反查，而不是拿列表下标——绑定可以在局内增删、排序，
        // 下标会漂，id 不会
        AnchorCheck.Report report = AnchorCheck.check(project, binding);

        if (binding.trigger() == AnchorBinding.Trigger.ALWAYS) {
            reportPersistent(mc, project, binding, report);
            return;
        }

        if (report.stage() == AnchorCheck.Stage.DISABLED) {
            say(mc, GtLang.get("gtshaders.anchor.trigger_disabled", binding.name()));
            return;
        }
        AnchorRuntime.simulate(binding);
        say(mc, GtLang.get("gtshaders.anchor.triggered", binding.name(),
                report.slot() < 0 ? "-" : String.valueOf(report.slot())));
        // 触发成功不等于看得见：链路后面几环断了照样什么都不会出现。
        // 解算状态这里不查——刚触发，要等下一帧 solve 才有数据
        sayWiring(mc, binding, report);
    }

    /**
     * 常驻绑定的状态报告：它不需要触发，需要的是「现在到底亮没亮、亮在哪」。
     */
    private static void reportPersistent(Minecraft mc, ShaderProject project,
                                         AnchorBinding binding, AnchorCheck.Report report) {
        say(mc, GtLang.get("gtshaders.anchor.check.persistent", binding.name(),
                report.slot() < 0 ? "-" : String.valueOf(report.slot())));
        if (!report.ok()) {
            sayWiring(mc, binding, report);
            return;
        }
        // 配置链通了，才轮到看运行时解算。预览必须已经开着——solve() 只在预览挂着时跑，
        // 否则这里读到的是陈旧数据（AnchorCheck 的顺序已经保证走到这里时预览是开的）
        AnchorSlot live = AnchorCheck.liveSlot(project, binding);
        if (live == null) {
            say(mc, GtLang.get("gtshaders.anchor.check.unresolved"));
            return;
        }
        say(mc, GtLang.get("gtshaders.anchor.check.live",
                String.format(Locale.ROOT, "%.3f, %.3f", live.u(), live.v()),
                String.format(Locale.ROOT, "%.1f", live.distance())));
        if (live.visibility() <= 0f) {
            // 解出来了、位置也对，但被挡住——大多数效果会按 visibility 淡掉，看着就像没生效
            say(mc, GtLang.get("gtshaders.anchor.check.occluded"));
        }
    }

    /** 配置链上第一个断掉的环节，用一句可执行的话说出来。 */
    private static void sayWiring(Minecraft mc, AnchorBinding binding, AnchorCheck.Report report) {
        switch (report.stage()) {
            case DISABLED -> say(mc, GtLang.get("gtshaders.anchor.trigger_disabled", binding.name()));
            case NO_SLOT -> say(mc, GtLang.get("gtshaders.anchor.check.no_slot"));
            case NO_PREVIEW -> say(mc, GtLang.get("gtshaders.bind.no_preview"));
            case NO_ANCHOR_LAYER -> say(mc, GtLang.get("gtshaders.anchor.quick.no_layer"));
            case SLOT_UNREAD -> say(mc, GtLang.get("gtshaders.anchor.check.slot_unread",
                    String.valueOf(report.slot()), binding.name()));
            case OK -> {
                // 链路通，不用多说一句
            }
        }
    }


    /**
     * 一条绑定都没有时，就地建一条能立刻开测的。
     *
     * <p>保留这条兜底是为了「装上就能按一下看见东西」——效果调好了、锚点一条没配的人
     * 按 B 键，得到的应该是画面上炸开一发，而不是一句「你还没有绑定」。
     *
     * <p>给足 6 秒而不是默认的 2.5：演出类效果光蓄力就要三秒以上，默认值只够播个开头就硬切掉。
     * 这个值只加在<b>我们自己建的</b>这条上，用户在面板里配的绑定按 B 时时长原样不动——
     * 按键篡改用户配好的字段，是那种「改完再回面板发现值变了」的困惑来源。
     */
    private static @Nullable AnchorBinding createTestBinding(Minecraft mc, ShaderProject project) {
        if (project.anchorSlotsUsed() >= AnchorSlot.SLOTS) {
            say(mc, GtLang.get("gtshaders.anchor.quick.full"));
            return null;
        }
        AnchorBinding binding = AnchorBinding.withTrigger(
                GtLang.get("gtshaders.anchor.crosshair_name"),
                AnchorBinding.Trigger.MANUAL);
        binding.setSource(AnchorBinding.Source.LOOK_HIT);
        // 落点本身就在方块表面，黑洞中心贴着落点，不再抬高度
        binding.setYOffset(0f);
        binding.setDuration(6f);
        project.addAnchor(binding);
        return binding;
    }

    /**
     * 当前工程。优先用运行时正持有的那个（预览开着时就是它），否则退回编辑器的会话工程
     * 并顺手交给运行时——否则在编辑器还没编译过一次的情况下，局内加的绑定不会被解算。
     */
    private static @Nullable ShaderProject activeProject() {
        ShaderProject p = AnchorRuntime.project();
        if (p == null) {
            p = EditorScreen.sessionProject();
            if (p != null) {
                AnchorRuntime.setProject(p);
            }
        }
        return p;
    }

    private static String typeId(Entity e) {
        var key = BuiltInRegistries.ENTITY_TYPE.getKey(e.getType());
        return key == null ? "" : key.toString();
    }


    /** 走物品栏上方那条 action bar：这类反馈是瞬时的，塞进聊天记录只会积一堆没人回头看的行。 */
    private static void say(Minecraft mc, String message) {
        if (mc.gui != null && mc.gui.hud != null) {
            mc.gui.hud.setOverlayMessage(Component.literal(message), false);
        }
    }
}
