package mc.GTedd.cn.gtshaders.llm;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 钉住「在后处理里跑的语言模型」这个产物的三类约定。
 *
 * <p>它有三道各管一段的校验，缺一段就有一类错误会漏到玩家那儿：
 * <ul>
 *   <li>{@code checkLlmPack}（ShaderC 真编译）—— 语法对不对</li>
 *   <li>{@code tools/llm_simulate.py}（numpy 按着色器语义重跑并对拍）—— 算得对不对</li>
 *   <li><b>这一份</b> —— 链的结构、两处必须一致的常量、以及 Java 侧的分词</li>
 * </ul>
 *
 * <p>包是构建产物（`gradlew generateLlmPack`），不在仓库里。没有就整类跳过，
 * 和 {@code RealVanillaTest} 对原版资产的处理一致——本地没生成过不该让整个 check 变红。
 */
class LlmPackTest {

    private static final Path PACK = Path.of("build/distributions/GTShaders-LLM-26.3.zip");
    private static final String NS = "gtllm";

    private static ZipFile open() throws Exception {
        Assumptions.assumeTrue(Files.exists(PACK),
                "没有 " + PACK + "，先跑 gradlew generateLlmPack");
        return new ZipFile(PACK.toFile());
    }

    private static JsonObject chain(ZipFile z) throws Exception {
        return JsonParser.parseString(new String(
                z.getInputStream(z.getEntry("assets/" + NS + "/post_effect/llm.json"))
                        .readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
    }

    private static JsonObject layout(ZipFile z) throws Exception {
        return JsonParser.parseString(new String(
                z.getInputStream(z.getEntry("layout.json")).readAllBytes(),
                StandardCharsets.UTF_8)).getAsJsonObject();
    }

    /**
     * MAXSEQ 写在两个地方：生成器定链的形状，Java 侧按它算 uniform 块大小。
     * 对不上时 uniform 会写短一截或越界——前者让 prompt 尾部变成 0（模型读到一堆
     * 感叹号），后者直接跳过写入（面板按了没反应），两种都不报错。
     */
    @Test
    void MAXSEQ_两边必须一致() throws Exception {
        try (ZipFile z = open()) {
            assertEquals(layout(z).get("MAXSEQ").getAsInt(), LlmRuntime.MAXSEQ,
                    "build_llm_pack.py 的 MAXSEQ 和 LlmRuntime.MAXSEQ 对不上");
        }
    }

    /** 命名空间三处一致：生成器、Java 常量、包里的实际路径。 */
    @Test
    void 命名空间一致() throws Exception {
        try (ZipFile z = open()) {
            assertEquals(NS, LlmTokenizer.NAMESPACE);
            assertTrue(z.getEntry("assets/" + NS + "/post_effect/llm.json") != null,
                    "包里没有 " + NS + " 命名空间的链");
        }
    }

    /** 每个通道引用的目标都得在 targets 里声明过，或者是外部的 minecraft:main。 */
    @Test
    void 通道引用的目标都存在() throws Exception {
        try (ZipFile z = open()) {
            JsonObject c = chain(z);
            Set<String> declared = new HashSet<>(c.getAsJsonObject("targets").keySet());
            declared.add("minecraft:main");
            for (var el : c.getAsJsonArray("passes")) {
                JsonObject p = el.getAsJsonObject();
                String out = p.get("output").getAsString();
                assertTrue(declared.contains(out), "通道输出到未声明的目标：" + out);
                for (var in : p.getAsJsonArray("inputs")) {
                    JsonObject i = in.getAsJsonObject();
                    if (i.has("target")) {
                        assertTrue(declared.contains(i.get("target").getAsString()),
                                "通道读取未声明的目标：" + i.get("target").getAsString());
                    }
                }
            }
        }
    }

    /**
     * 同一个通道不能既读又写同一个目标——这是后处理的硬约束，
     * 违反时不会报错，只会读到上一帧或者半写的内容。
     */
    @Test
    void 没有通道自读自写() throws Exception {
        try (ZipFile z = open()) {
            for (var el : chain(z).getAsJsonArray("passes")) {
                JsonObject p = el.getAsJsonObject();
                String out = p.get("output").getAsString();
                for (var in : p.getAsJsonArray("inputs")) {
                    JsonObject i = in.getAsJsonObject();
                    assertFalse(i.has("target") && out.equals(i.get("target").getAsString()),
                            "通道 " + p.get("fragment_shader").getAsString()
                                    + " 同时读写 " + out);
                }
            }
        }
    }

    /** 每个持久目标都得配一个拷贝目标，否则它没法在跨帧的同时被更新。 */
    @Test
    void 持久目标都有配套拷贝() throws Exception {
        try (ZipFile z = open()) {
            JsonObject targets = chain(z).getAsJsonObject("targets");
            List<String> missing = new ArrayList<>();
            for (String name : targets.keySet()) {
                JsonObject t = targets.getAsJsonObject(name);
                if (t.has("persistent") && t.get("persistent").getAsBoolean()) {
                    boolean paired = targets.has(name + "_swap") || "tok".equals(name);
                    if (!paired) {
                        missing.add(name);
                    }
                }
            }
            // tok 是例外：它只被 argmax 写、被下一帧的 seqput 读，同一帧里没人既读又写
            assertTrue(missing.isEmpty(), "持久目标缺配套拷贝：" + missing);
        }
    }

    /**
     * 控制块必须恰好出现在两个通道上。
     *
     * <p>{@link LlmRuntime} 是<b>按块名</b>找通道写 uniform 的，多一个少一个都说明
     * 链的形状变了：少了意味着 prompt 喂不进去，多了意味着有通道拿到了它不该有的数据。
     */
    @Test
    void 控制块恰好挂在两个通道上() throws Exception {
        try (ZipFile z = open()) {
            int n = 0;
            for (var el : chain(z).getAsJsonArray("passes")) {
                JsonObject p = el.getAsJsonObject();
                if (p.has("uniforms") && p.getAsJsonObject("uniforms").has("LlmCtl")) {
                    n++;
                    JsonArray block = p.getAsJsonObject("uniforms").getAsJsonArray("LlmCtl");
                    assertEquals(1 + LlmRuntime.MAXSEQ / 4, block.size(),
                            "LlmCtl 的 vec4 数量和 Java 侧算的块大小对不上");
                }
            }
            assertEquals(2, n, "写 prompt 的通道应当恰好是 seqput 与 ingest 两个");
        }
    }

    /** 链的规模。变了不一定是错，但一定要有人看见——这条会在改动时逼出一次确认。 */
    @Test
    void 链的规模符合预期() throws Exception {
        try (ZipFile z = open()) {
            JsonObject c = chain(z);
            assertEquals(72, c.getAsJsonArray("passes").size(), "通道数变了");
            assertEquals(21, c.getAsJsonObject("targets").size(), "目标数变了");
        }
    }

    // ---------------------------------------------------------------- 分词

    private static LlmTokenizer tokenizer(ZipFile z) throws Exception {
        try (InputStream v = z.getInputStream(z.getEntry("assets/" + NS + "/tokenizer/vocab.json"));
             InputStream m = z.getInputStream(z.getEntry("assets/" + NS + "/tokenizer/merges.txt"))) {
            return LlmTokenizer.fromSources(v, m);
        }
    }

    /**
     * Java 的 BPE 必须和 Python 那份切出完全一样的 id。
     *
     * <p>期望值来自 {@code tools/llm_tokenizer.py} 在同一份词表上的输出。
     * 这一条是<b>必须</b>有的：切错了不会报错，模型照样生成通顺的句子，
     * 只是和玩家敲的那句话毫无关系——症状是「它没听懂我说什么」，极难联想到分词器。
     */
    @Test
    void 分词结果与Python实现逐id一致() throws Exception {
        try (ZipFile z = open()) {
            LlmTokenizer t = tokenizer(z);
            assertArrayEquals(new int[]{7454, 2402, 257, 640, 11, 612, 373, 257, 10441},
                    t.encode("Once upon a time, there was a dragon", 64));
            assertArrayEquals(new int[]{43, 813, 1816, 284, 262, 3952, 351, 607, 1995, 13},
                    t.encode("Lily went to the park with her mom.", 64));
            // 数字、引号、感叹号：非字母分支和「前导空格跟着后一个词走」都在这一条里
            assertArrayEquals(new int[]{13787, 531, 25, 366, 40, 423, 513, 2266, 11333, 2474},
                    t.encode("Tom said: \"I have 3 red balls!\"", 64));
            // 连续空格：GPT-2 会把多余的空格单独切出来，不是合并成一个
            assertArrayEquals(new int[]{464, 220, 2068, 220, 220, 7586, 21831},
                    t.encode("The  quick   brown fox", 64));
        }
    }

    @Test
    void 分词遵守上限() throws Exception {
        try (ZipFile z = open()) {
            LlmTokenizer t = tokenizer(z);
            assertEquals(3, t.encode("Once upon a time, there was a dragon", 3).length);
            assertEquals(0, t.encode("anything", 0).length);
        }
    }

    /** 空串和纯符号不该抛异常——玩家会把任何东西敲进输入框。 */
    @Test
    void 古怪输入不抛异常() throws Exception {
        try (ZipFile z = open()) {
            LlmTokenizer t = tokenizer(z);
            assertEquals(0, t.encode("", 64).length);
            assertTrue(t.encode("你好，世界", 64).length > 0, "非 ASCII 应当走字节回退而不是被丢光");
            assertTrue(t.encode("\n\t  ", 64).length >= 0);
        }
    }

    /** 词表和权重必须是同一次生成的产物。 */
    @Test
    void 词表规模与模型词表一致() throws Exception {
        try (ZipFile z = open()) {
            int vocab = layout(z).getAsJsonObject("dims").get("VOCAB").getAsInt();
            String json = new String(z.getInputStream(
                    z.getEntry("assets/" + NS + "/tokenizer/vocab.json")).readAllBytes(),
                    StandardCharsets.UTF_8);
            assertEquals(vocab, JsonParser.parseString(json).getAsJsonObject().size(),
                    "词表条目数和模型的 VOCAB 对不上，说明两者不是同一次生成的");
        }
    }

    /** 分词器不依赖 Minecraft 也能构造——这条保证上面那些测试本身是可跑的。 */
    @Test
    void 可脱离资源管理器构造() throws Exception {
        String vocab = "{\"a\":0,\"b\":1,\"ab\":2}";
        String merges = "#version: 0.2\na b\n";
        LlmTokenizer t = LlmTokenizer.fromSources(
                new ByteArrayInputStream(vocab.getBytes(StandardCharsets.UTF_8)),
                new ByteArrayInputStream(merges.getBytes(StandardCharsets.UTF_8)));
        assertArrayEquals(new int[]{2}, t.encode("ab", 8), "合并规则没生效");
    }
}
