package mc.GTedd.cn.gtshaders.llm;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.PostChain;
import net.minecraft.client.renderer.PostPass;
import net.minecraft.resources.Identifier;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.system.MemoryStack;

import mc.GTedd.cn.gtshaders.mixin.PostChainAccessor;
import mc.GTedd.cn.gtshaders.mixin.PostPassAccessor;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.Set;

/**
 * 「在后处理里跑的语言模型」的挂载与喂数据。
 *
 * <h2>它和效果库那条路的区别</h2>
 *
 * <p>效果库里的每个效果是<b>一层</b>，多层串成一条 main↔swap 的乒乓链，
 * 由 {@code PostEffectJsonBuilder} 现拼 JSON。语言模型不是这个形状：
 * 它是一条 72 个通道、21 个目标（其中 7 个跨帧持久）的<b>固定</b>链，
 * 由 {@code tools/build_llm_pack.py} 离线生成，随资源包发布。
 * 所以这里不走 codegen，只做两件事：每帧报到，以及把 prompt 写进 uniform。
 *
 * <h2>为什么 prompt 走 uniform，而不是社区那套「效果列表当比特」</h2>
 *
 * <p>xpncvr/llm-postshader 得用 289 条效果链、每帧 8 字节地往屏幕像素里写位，
 * 因为它的客户端是<b>纯原版</b>，服务端只能通过「挂哪些效果、按什么顺序」传信息。
 * GTShaders 本身就是客户端 mod，直接改写 uniform 缓冲即可——
 * 256 个 token 一帧写完，没有分块、没有 nonce、没有重放校验。
 * 这是「装了 mod」换来的最大一笔简化。
 *
 * <h2>状态在 GPU 上，不在这里</h2>
 *
 * <p>生成是<b>闭环跑在显卡上</b>的：argmax 的结果写进持久目标 {@code tok}，
 * 下一帧由 {@code seqput} 追加进 {@code seq}。Java 侧从头到尾不知道模型输出了什么，
 * 也不需要知道——回读像素要同步 GPU，那会把帧率拖垮。
 * 换 prompt 靠 {@code epoch} 自增：着色器发现它变了就整条复位。
 */
public final class LlmRuntime {

    /** 资源包里那条链的 id。资源包没装时原版会判定它不存在，于是什么都不会发生。 */
    public static final Identifier CHAIN = Identifier.fromNamespaceAndPath(
            LlmTokenizer.NAMESPACE, "llm");
    /** 与着色器里的 uniform 块同名。按块名找通道，就不必假设它排在第几个。 */
    private static final String CTL_BLOCK = "LlmCtl";
    /** 与 {@code build_llm_pack.py} 的 MAXSEQ 一致。改一处必须改两处。 */
    public static final int MAXSEQ = 256;

    private static boolean active;
    private static int epoch;
    private static int promptLen;
    private static int nGen = 120;
    private static final int[] prompt = new int[MAXSEQ];
    private static @Nullable LlmTokenizer tokenizer;
    private static String lastPrompt = "";

    private LlmRuntime() {
    }

    public static boolean isActive() {
        return active;
    }

    public static String lastPrompt() {
        return lastPrompt;
    }

    public static int generateCount() {
        return nGen;
    }

    public static void setGenerateCount(int n) {
        nGen = Math.max(1, Math.min(n, MAXSEQ - 1));
    }

    /**
     * 换一句 prompt 并从头开始生成。
     *
     * @return 切出来的 token 数；0 表示资源包没装或者这句话一个 token 都切不出来
     */
    public static int start(String text) {
        if (tokenizer == null) {
            tokenizer = LlmTokenizer.load();
        }
        if (tokenizer == null) {
            return 0;
        }
        // 留一格给「至少要能生成一个」，也避免 prompt 塞满整个序列
        int[] ids = tokenizer.encode(text, Math.min(MAXSEQ - 2, MAXSEQ - nGen - 1));
        if (ids.length == 0) {
            return 0;
        }
        System.arraycopy(ids, 0, prompt, 0, ids.length);
        promptLen = ids.length;
        lastPrompt = text;
        epoch++;                 // 着色器认这个数变没变，不认具体值
        active = true;
        return ids.length;
    }

    /** 停下并清屏。epoch 照样自增，好让链把持久目标复位，不留上一次的半截故事。 */
    public static void stop() {
        active = false;
        promptLen = 0;
        epoch++;
    }

    /** 资源包装没装。装了才有词表可读。 */
    public static boolean packInstalled() {
        if (tokenizer == null) {
            tokenizer = LlmTokenizer.load();
        }
        return tokenizer != null;
    }

    /** 资源包重载后词表要重新读——旧的那份可能来自已经被换掉的包。 */
    public static void invalidate() {
        tokenizer = null;
    }

    /** 每帧往原版的后处理请求列表里报到。由 {@code GameRendererMixin} 调用。 */
    public static void appendActiveIds(List<Identifier> requested) {
        if (active && !requested.contains(CHAIN)) {
            requested.add(CHAIN);
        }
    }

    /**
     * 把控制块写进链里每个声明了 {@code LlmCtl} 的通道。
     *
     * <p><b>按块名找，不按下标找</b>：链有 72 个通道，硬编码「第 4 和第 5 个」
     * 在生成器改动一次之后就会静默地把数据写到别的通道的缓冲上——那不会报错，
     * 只会让模型读到一堆垃圾 token。
     */
    public static void uploadUniforms() {
        if (!active) {
            return;
        }
        PostChain chain = Minecraft.getInstance().getShaderManager()
                .getPostChain(CHAIN, Set.of(Identifier.withDefaultNamespace("main")));
        if (chain == null) {
            return;
        }
        // 1 个控制向量 + MAXSEQ/4 个装 token 的 vec4；std140 下 vec4 数组跨距就是 16 字节
        final int vec4Count = 1 + MAXSEQ / 4;
        final int size = vec4Count * 16;
        for (PostPass pass : ((PostChainAccessor) chain).gtshaders$passes()) {
            GpuBuffer buffer = ((PostPassAccessor) pass).gtshaders$customUniforms().get(CTL_BLOCK);
            if (buffer == null || buffer.isClosed() || buffer.size() < size) {
                continue;
            }
            try (MemoryStack stack = MemoryStack.stackPush()) {
                ByteBuffer bytes = stack.malloc(size);
                bytes.putFloat(epoch).putFloat(promptLen).putFloat(nGen).putFloat(0f);
                for (int i = 0; i < MAXSEQ; i++) {
                    bytes.putFloat(i < promptLen ? prompt[i] : 0f);
                }
                bytes.position(0).limit(size);
                RenderSystem.getDevice().createCommandEncoder()
                        .writeToBuffer(buffer.slice(0L, size), bytes);
            }
        }
    }
}
