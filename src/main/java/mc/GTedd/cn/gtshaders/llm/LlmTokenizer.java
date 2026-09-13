package mc.GTedd.cn.gtshaders.llm;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import org.jetbrains.annotations.Nullable;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * GPT-2 的 byte-level BPE。把玩家敲的一句话切成 token id 喂进着色器。
 *
 * <h2>为什么词表从资源包读，而不是打进 jar</h2>
 *
 * <p>词表和权重是<b>配套</b>的：换一个模型就得整套换。分开放迟早会出现
 * 「jar 里的词表对不上包里的权重」——那时 prompt 会被切成一串风马牛不相及的 id，
 * 模型照样生成，只是内容毫无道理，而且没有任何报错。
 * 何况这两个文件加起来 1.2 MB，塞进 jar 会让不用这个功能的人也背着它。
 *
 * <h2>三个容易写错的地方</h2>
 * <ul>
 *   <li>切分正则里的 {@code " ?[A-Za-z]+"}——<b>前导空格跟着后一个词走</b>。
 *       这是 GPT-2 与常见分词器最大的差别，漏掉空格会切出完全不同的 id。</li>
 *   <li>字节要先过一遍「可打印化」映射再查表，直接拿 UTF-8 字节查是查不到的。</li>
 *   <li>合并时每轮只挑<b>排名最小</b>的那一对，不是从左到右能并就并。</li>
 * </ul>
 */
public final class LlmTokenizer {

    /** 资源包命名空间。生成器 {@code tools/build_llm_pack.py} 里的 {@code NS} 必须与之一致。 */
    public static final String NAMESPACE = "gtllm";

    private static final Pattern PAT = Pattern.compile(
            "'s|'t|'re|'ve|'m|'ll|'d| ?[A-Za-z]+| ?[0-9]+| ?[^\\sA-Za-z0-9]+|\\s+(?!\\S)|\\s+");

    /** merges.txt 里一对符号之间的分隔符。写成常量是因为这里踩过一次坑：
     *  查表端的字符字面量被改成了 NUL，与建表端的空格对不上，BPE 于是静默退化成逐字符——
     *  不报错、不崩溃，只是 prompt 被切成一串毫不相干的 id。 */
    private static final String SEP = " ";

    private static final String[] BYTE_TO_UNICODE = new String[256];

    static {
        // GPT-2 那套把任意字节映射到可打印字符的表
        List<Integer> bs = new ArrayList<>();
        for (int b = 33; b < 127; b++) bs.add(b);
        for (int b = 161; b < 173; b++) bs.add(b);
        for (int b = 174; b < 256; b++) bs.add(b);
        List<Integer> cs = new ArrayList<>(bs);
        int n = 0;
        for (int b = 0; b < 256; b++) {
            if (!bs.contains(b)) {
                bs.add(b);
                cs.add(256 + n);
                n++;
            }
        }
        for (int i = 0; i < bs.size(); i++) {
            BYTE_TO_UNICODE[bs.get(i)] = String.valueOf((char) (int) cs.get(i));
        }
    }

    private final Map<String, Integer> encoder;
    private final Map<String, Integer> ranks;

    private LlmTokenizer(Map<String, Integer> encoder, Map<String, Integer> ranks) {
        this.encoder = encoder;
        this.ranks = ranks;
    }

    /**
     * 直接从两份原文构造，不碰 Minecraft。
     *
     * <p>存在的理由是<b>可测</b>：BPE 切错了不会报错，只会把 prompt 变成一串
     * 风马牛不相及的 id，模型照样生成，只是内容毫无道理。
     * 单元测试要能拿真词表跟 Python 那份实现逐 id 对拍，就不能让构造依赖资源管理器。
     */
    public static LlmTokenizer fromSources(InputStream vocabJson, InputStream mergesTxt)
            throws Exception {
        JsonObject obj = JsonParser.parseReader(
                new InputStreamReader(vocabJson, StandardCharsets.UTF_8)).getAsJsonObject();
        Map<String, Integer> enc = new HashMap<>(obj.size() * 2);
        for (var e : obj.entrySet()) {
            enc.put(e.getKey(), e.getValue().getAsInt());
        }
        Map<String, Integer> rk = new HashMap<>();
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(mergesTxt, StandardCharsets.UTF_8))) {
            String line = r.readLine();          // 第一行是版本号，跳过
            int rank = 0;
            while ((line = r.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                int sp = line.indexOf(' ');
                if (sp > 0) {
                    rk.put(line.substring(0, sp) + SEP + line.substring(sp + 1), rank++);
                }
            }
        }
        return new LlmTokenizer(enc, rk);
    }

    /**
     * 从当前已加载的资源包里读词表。资源包没装就返回 null——
     * 那是正常状态（玩家还没装这个包），不该抛异常。
     */
    public static @Nullable LlmTokenizer load() {
        var rm = Minecraft.getInstance().getResourceManager();
        Optional<Resource> vocab = rm.getResource(
                Identifier.fromNamespaceAndPath(NAMESPACE, "tokenizer/vocab.json"));
        Optional<Resource> merges = rm.getResource(
                Identifier.fromNamespaceAndPath(NAMESPACE, "tokenizer/merges.txt"));
        if (vocab.isEmpty() || merges.isEmpty()) {
            return null;
        }
        try (InputStream vs = vocab.get().open(); InputStream ms = merges.get().open()) {
            return fromSources(vs, ms);
        } catch (Exception e) {
            return null;
        }
    }

    /** 把一句话切成 token id。认不出来的片段整块跳过，而不是让整次调用失败。 */
    public int[] encode(String text, int limit) {
        List<Integer> out = new ArrayList<>();
        Matcher m = PAT.matcher(text);
        while (m.find() && out.size() < limit) {
            StringBuilder piece = new StringBuilder();
            for (byte b : m.group().getBytes(StandardCharsets.UTF_8)) {
                piece.append(BYTE_TO_UNICODE[b & 0xFF]);
            }
            for (String sym : merge(piece.toString())) {
                Integer id = encoder.get(sym);
                if (id != null && out.size() < limit) {
                    out.add(id);
                }
            }
        }
        int[] ids = new int[out.size()];
        for (int i = 0; i < ids.length; i++) {
            ids[i] = out.get(i);
        }
        return ids;
    }

    /** 反复合并排名最靠前的相邻符号对，直到没有一对在表里。 */
    private List<String> merge(String token) {
        List<String> word = new ArrayList<>(token.length());
        for (int i = 0; i < token.length(); i++) {
            word.add(String.valueOf(token.charAt(i)));
        }
        while (word.size() > 1) {
            int bestRank = Integer.MAX_VALUE;
            int bestAt = -1;
            for (int i = 0; i < word.size() - 1; i++) {
                Integer r = ranks.get(word.get(i) + SEP + word.get(i + 1));
                if (r != null && r < bestRank) {
                    bestRank = r;
                    bestAt = i;
                }
            }
            if (bestAt < 0) {
                break;
            }
            String a = word.get(bestAt), b = word.get(bestAt + 1);
            List<String> next = new ArrayList<>(word.size());
            int i = 0;
            while (i < word.size()) {
                if (i < word.size() - 1 && word.get(i).equals(a) && word.get(i + 1).equals(b)) {
                    next.add(a + b);
                    i += 2;
                } else {
                    next.add(word.get(i));
                    i++;
                }
            }
            word = next;
        }
        return word;
    }
}
