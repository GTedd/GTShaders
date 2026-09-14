package mc.GTedd.cn.gtshaders.ai;

import org.jspecify.annotations.Nullable;
import mc.GTedd.cn.gtshaders.GTShaders;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 「一句话 → 能在画面上跑起来的着色器」这条流水线。
 *
 * <h2>为什么编译要委托出去</h2>
 *
 * <p>校验用的是玩家自己那块显卡上的 GLSL 编译器——和游戏真正会用的是同一个，
 * 这是这套自动修错唯一可信的基础。但它必须在渲染线程上调用（要碰 GL 上下文），
 * 而这里跑在后台线程上等网络。所以编译动作以 {@link Compiler} 的形式交回给界面层，
 * 由它负责切到主线程再回来。这个类只管「拿到错误之后该怎么办」。
 *
 * <h2>为什么要留住 lastGood</h2>
 *
 * <p>陷阱修正那一轮是在一份<b>已经能编译的</b>着色器上继续改。改坏是完全可能的，
 * 而那时把失败结果交给玩家，等于用一个「本可以用」的效果换来一个报错——
 * 净损失。所以任何时候都记着最后一份编译通过的源码，
 * 后续轮次失败就退回它，玩家至少拿到能跑的东西。
 */
public final class ShaderSmith {

    public enum Stage {
        /** 首轮生成，等模型吐字。 */
        GENERATING,
        /** 拿到源码，正在真机编译。 */
        COMPILING,
        /** 编译失败，把编译器报错回喂给模型。 */
        REPAIRING,
        /** 编译通过但踩了已知陷阱，正在让模型改掉。 */
        POLISHING
    }

    /** 在渲染线程上编译并应用一次。返回空列表表示通过，否则是「第 N 行: 说明」这样的错误行。 */
    public interface Compiler {
        List<String> compile(String source);
    }

    /** 全部回调都发生在<b>后台线程</b>上。要动界面状态的，自己切回主线程。 */
    public interface Listener {
        void onStage(Stage stage, int round, int budget);

        void onDelta(String text);

        void onSuccess(String source, List<String> notes);

        void onFailure(AiException error);
    }

    /** 一次生成的句柄。玩家关掉界面或按取消时用它收尾。 */
    public static final class Handle {
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private volatile boolean running = true;

        public void cancel() {
            cancelled.set(true);
        }

        public boolean isCancelled() {
            return cancelled.get();
        }

        public boolean isRunning() {
            return running;
        }
    }

    private ShaderSmith() {
    }

    /**
     * 起一次生成。立刻返回，实际工作在虚拟线程上跑。
     *
     * @param wish 玩家那句话
     */
    public static Handle start(AiEndpoint endpoint, AiConfig config, String wish,
                               Compiler compiler, Listener listener) {
        Handle handle = new Handle();
        Thread.ofVirtual().name("gtshaders-ai").start(() -> {
            try {
                run(endpoint, config, wish, compiler, listener, handle);
            } catch (AiException e) {
                AiLog.failed(e);
                if (!handle.isCancelled()) {
                    listener.onFailure(e);
                }
            } catch (Throwable t) {
                // 后台线程里漏出去的异常会被默默吞掉，界面就永远停在「生成中」。
                // 这里兜住并转成一次明确的失败，比让玩家盯着转圈强
                GTShaders.LOGGER.error("AI 生成线程异常", t);
                if (!handle.isCancelled()) {
                    listener.onFailure(new AiException(AiException.Kind.BAD_RESPONSE,
                            t.getClass().getSimpleName() + ": " + t.getMessage(), t));
                }
            } finally {
                handle.running = false;
            }
        });
        return handle;
    }

    /** 截断后加大上限重试的次数上限。翻一次通常就够，翻不动就是真的写太长了。 */
    private static final int TRUNCATION_RETRIES = 2;

    /**
     * 自动档被截断时改用的显式上限。
     *
     * <p>自动档下服务端用的是模型自己的上限，被它截断说明这个模型的输出额度本来就小。
     * 这时候翻倍无从下手（我们不知道它是多少），只能换成一个明确的大值再试一次。
     */
    private static final int TRUNCATION_FALLBACK = 32768;

    /**
     * 发一轮，并在被截断时自动加大输出上限重试。
     *
     * <p>截断几乎总是<b>推理模型的思维链吃掉了预算</b>——reasoning token 也算进
     * max_output_tokens，正文还没开始写就没额度了。直接失败的话玩家看到的是
     * 「返回的内容用不了」，而他既不知道是哪个数不够，也不知道该去哪里调。
     *
     * <p>翻倍重试是这里唯一说得通的自救：它不需要玩家理解任何东西，
     * 而代价只是多一次请求。翻两次还不行就如实失败——那时候确实该他自己看一眼了。
     */
    private static String askWithTruncationRecovery(AiClient client, AiEndpoint endpoint,
                                                    String system, List<AiClient.Turn> turns,
                                                    boolean stream, Listener listener,
                                                    Handle handle) {
        AiEndpoint ep = endpoint;
        for (int attempt = 0; ; attempt++) {
            try {
                return client.send(ep, system, turns, stream,
                        listener::onDelta, handle::isCancelled);
            } catch (AiException e) {
                if (e.kind() != AiException.Kind.TRUNCATED || attempt >= TRUNCATION_RETRIES) {
                    throw e;
                }
                // 自动档被服务端自己的上限截断了，那就改成显式给一个大值再试；
                // 已经是显式值的翻倍
                int next = ep.autoMaxOutput()
                        ? TRUNCATION_FALLBACK
                        : Math.min(1_000_000, ep.maxOutputTokens() * 2);
                if (!ep.autoMaxOutput() && next <= ep.maxOutputTokens()) {
                    throw e;
                }
                AiLog.truncated(ep.maxOutputTokens(), next);
                ep = ep.withMaxOutputTokens(next);
                // 重试要从头吐字，界面上的尾巴得清掉，否则看起来像卡在半句上
                listener.onStage(Stage.GENERATING, 0, 0);
            }
        }
    }

    private static void run(AiEndpoint endpoint, AiConfig config, String wish,
                            Compiler compiler, Listener listener, Handle handle) {
        String system = AiPrompt.system();
        // 协议只在这里分叉一次，往下全是同一个接口——差异不许漏到这个类里
        AiClient client = AiClient.of(endpoint.protocol());
        List<AiClient.Turn> turns = new ArrayList<>();
        turns.add(AiClient.Turn.user(AiPrompt.initialRequest(wish)));

        int budget = config.repairRounds();
        String lastGood = null;
        List<String> lastGoodNotes = List.of();

        for (int round = 0; round <= budget; round++) {
            if (handle.isCancelled()) {
                return;
            }
            Stage stage = round == 0 ? Stage.GENERATING : Stage.REPAIRING;
            AiLog.stage(stage.name(), round, budget);
            listener.onStage(stage, round, budget);

            String reply = askWithTruncationRecovery(client, endpoint, system, turns,
                    config.stream(), listener, handle);
            if (handle.isCancelled()) {
                return;
            }

            ShaderExtractor.Result extracted = ShaderExtractor.extract(reply);
            AiLog.extracted(reply.length(), extracted.source().length(), extracted.notes());
            if (extracted.isEmpty()) {
                if (round >= budget) {
                    throw new AiException(AiException.Kind.BAD_RESPONSE, "no glsl block in reply");
                }
                // 模型偶尔会先聊两句再问要不要写。把要求重申一遍通常一次就好
                turns = nextTurns(turns, reply,
                        "回答里没有找到 ```glsl 代码块。请只输出一个 ```glsl 代码块，不要写任何别的内容。");
                continue;
            }

            listener.onStage(Stage.COMPILING, round, budget);
            List<String> errors = compiler.compile(extracted.source());
            AiLog.compile(round, errors);
            if (handle.isCancelled()) {
                return;
            }

            if (!errors.isEmpty()) {
                if (round >= budget) {
                    // 预算用尽还是编译不过。有能用的旧版就交旧版，否则如实失败
                    if (lastGood != null) {
                        finishWith(compiler, listener, lastGood, lastGoodNotes);
                        return;
                    }
                    throw new AiException(AiException.Kind.BAD_RESPONSE,
                            "compile failed after " + (budget + 1) + " attempts: "
                                    + String.join("; ", errors));
                }
                turns = nextTurns(turns, fence(extracted.source()),
                        AiPrompt.repairRequest(extracted.source(), errors));
                continue;
            }

            // 编译通过。留住它，再看有没有踩「跑起来什么都不发生」的坑
            lastGood = extracted.source();
            lastGoodNotes = extracted.notes();

            List<PitfallCheck.Warning> pitfalls = PitfallCheck.scan(lastGood);
            if (!pitfalls.isEmpty()) {
                AiLog.pitfalls(pitfalls.stream().map(PitfallCheck.Warning::code).toList());
            }
            if (pitfalls.isEmpty() || round >= budget) {
                AiLog.done(round, lastGood.length());
                listener.onSuccess(lastGood, withPitfallNotes(lastGoodNotes, pitfalls));
                return;
            }

            listener.onStage(Stage.POLISHING, round, budget);
            turns = nextTurns(turns, fence(lastGood),
                    AiPrompt.pitfallRequest(lastGood, PitfallCheck.hints(pitfalls)));
        }

        // 循环体的每条分支都会 return 或 throw，正常情况下到不了这里。
        // 留一个明确的兜底而不是静默收场：真走到这一步说明上面漏了一条路径，
        // 而静默收场的表现是「界面一直转圈」，那种故障没有任何线索可查
        if (lastGood != null) {
            finishWith(compiler, listener, lastGood, lastGoodNotes);
            return;
        }
        throw new AiException(AiException.Kind.BAD_RESPONSE,
                "generation loop ended without a verdict");
    }

    /**
     * 退回上一份能编译的源码并交付。
     *
     * <p>要<b>重新编译一次</b>：中间那些失败的尝试已经把预览改成别的东西了，
     * 不重编的话玩家看到的画面和最终交给他的源码对不上。
     */
    private static void finishWith(Compiler compiler, Listener listener,
                                   String source, List<String> notes) {
        compiler.compile(source);
        List<String> withNote = new ArrayList<>(notes);
        withNote.add("reverted");
        listener.onSuccess(source, withNote);
    }

    /**
     * 组装下一轮的对话。
     *
     * <p>刻意<b>不累积历史</b>，每轮都是「最初的需求 + 上一版代码 + 这一轮的要求」三条。
     * 累积下去的话，第三轮时模型面对的是三份互相矛盾的代码和三段报错，
     * 而它需要的信息其实只有「要做什么」和「现在这版哪里不对」。上下文短还便宜。
     */
    private static List<AiClient.Turn> nextTurns(List<AiClient.Turn> current,
                                                        String assistantSaid, String nextAsk) {
        List<AiClient.Turn> out = new ArrayList<>(3);
        out.add(current.getFirst());
        out.add(AiClient.Turn.assistant(assistantSaid));
        out.add(AiClient.Turn.user(nextAsk));
        return out;
    }

    /** 回填给模型的上一版代码只留代码本身，去掉它当时写的解释——那些只会干扰下一轮。 */
    private static String fence(String source) {
        return "```glsl\n" + source.strip() + "\n```";
    }

    private static List<String> withPitfallNotes(List<String> notes,
                                                 List<PitfallCheck.@Nullable Warning> pitfalls) {
        if (pitfalls.isEmpty()) {
            return notes;
        }
        List<String> out = new ArrayList<>(notes);
        for (PitfallCheck.Warning w : pitfalls) {
            if (w != null) {
                out.add("pitfall:" + w.code());
            }
        }
        return out;
    }
}
