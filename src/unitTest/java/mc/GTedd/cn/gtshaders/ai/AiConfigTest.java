package mc.GTedd.cn.gtshaders.ai;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 覆盖服务商列表的存取、迁移，以及模型清单的解析。
 *
 * <p>这里最要紧的是最后那组<b>安全回归</b>：{@code config/} 会被整合包整个打包带走，
 * 所以那份文件里一个字节的 key 都不能有。防线建在类型上——
 * {@link AiConfig} 和 {@link AiProvider} 里都没有能装 key 的组件，
 * 想序列化都无从下手。这几条测试就是那道防线的守卫：
 * 谁要是图省事加一个 apiKey 字段，它们立刻变红。
 */
class AiConfigTest {

    // ------------------------------------------------------------ 存取

    @Test
    void configRoundTripsAndClampsGarbage(@TempDir Path dir) throws Exception {
        AiConfig original = AiConfig.defaults().withRepairRounds(2).withStream(false);
        original = original.withProvider(original.active().withModel("deepseek-v4-flash"));
        original.save(dir);
        assertEquals(original, AiConfig.load(dir));

        // 手改坏了也要能开机：这份配置里没有任何丢不起的东西
        Files.writeString(dir.resolve("ai.json"),
                "{\"temperature\":99,\"repairRounds\":-4,\"timeoutSeconds\":1}",
                StandardCharsets.UTF_8);
        AiConfig loaded = AiConfig.load(dir);
        assertTrue(loaded.temperature() <= 2f, "temperature=" + loaded.temperature());
        assertTrue(loaded.repairRounds() >= 0, "repairRounds=" + loaded.repairRounds());
        assertTrue(loaded.timeoutSeconds() >= 10, "timeoutSeconds=" + loaded.timeoutSeconds());
        assertFalse(loaded.providers().isEmpty(), "provider 列表不能是空的");
        assertFalse(loaded.active().model().isBlank());
    }

    @Test
    void missingConfigFallsBackToDeepSeek(@TempDir Path dir) {
        AiConfig c = AiConfig.load(dir);
        assertEquals("https://api.deepseek.com", c.active().baseUrl());
        assertTrue(c.active().model().startsWith("deepseek-"), c.active().model());
        assertTrue(c.repairRounds() > 0, "默认要开着自动修错，否则小模型的成功率撑不住");
    }

    /**
     * 只有一套设置的旧配置要能升上来。
     *
     * <p>直接忽略旧字段的话，已经配好并用了一段时间的玩家升级之后会发现地址和模型都变回默认——
     * 而 key 还在（它按 host 存，不在这个文件里），于是他会以为是自己手滑改了什么。
     */
    @Test
    void legacySingleProviderConfigIsMigrated(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("ai.json"),
                "{\"baseUrl\":\"https://relay.example.com/v1\",\"model\":\"my-model\","
                        + "\"repairRounds\":2,\"stream\":false}",
                StandardCharsets.UTF_8);
        AiConfig c = AiConfig.load(dir);
        assertEquals("https://relay.example.com/v1", c.active().baseUrl());
        assertEquals("my-model", c.active().model());
        assertEquals(2, c.repairRounds());
        assertFalse(c.stream());
        assertEquals(AiProtocol.RESPONSES, c.active().protocol(),
                "旧配置的年代还没有第二种协议，迁上来必须是 Responses");
    }

    // ------------------------------------------------------------ 服务商列表

    @Test
    void switchingProviderKeepsSharedSettings() {
        AiConfig c = AiConfig.defaults().withStream(false).withRepairRounds(1);
        AiProvider second = c.providers().get(1);
        AiConfig switched = c.withActive(second.id());
        assertEquals(second.id(), switched.active().id());
        assertFalse(switched.stream(), "换服务商不该把其它设置一起重置");
        assertEquals(1, switched.repairRounds());
    }

    @Test
    void addedProviderBecomesActive() {
        AiConfig c = AiConfig.defaults();
        AiProvider copy = c.active().copyAsNew();
        assertNotEquals(c.active().id(), copy.id(), "复制出来的必须是新 id，否则会覆盖原来那条");

        AiConfig plus = c.plusProvider(copy);
        assertEquals(copy.id(), plus.active().id(), "加完还得自己去点一下选中，是没有道理的");
        assertEquals(c.providers().size() + 1, plus.providers().size());
    }

    @Test
    void editingActiveDoesNotTouchOthers() {
        AiConfig c = AiConfig.defaults();
        String otherId = c.providers().get(1).id();
        String otherUrl = c.providers().get(1).baseUrl();
        AiConfig edited = c.withProvider(c.active().withBaseUrl("https://changed.example.com"));
        assertEquals("https://changed.example.com", edited.active().baseUrl());
        assertEquals(otherUrl, edited.providers().stream()
                .filter(p -> p.id().equals(otherId)).findFirst().orElseThrow().baseUrl());
    }

    /** 删到一条不剩时要补回预设：空列表意味着界面上没有任何可选项。 */
    @Test
    void removingTheLastProviderRestoresPresets() {
        AiConfig c = AiConfig.defaults();
        for (AiProvider p : List.copyOf(c.providers())) {
            c = c.minusProvider(p.id());
        }
        assertFalse(c.providers().isEmpty());
        assertNotNull(c.active());
    }

    @Test
    void activeFallsBackWhenIdIsStale() {
        AiConfig c = AiConfig.defaults().withActive("no-such-id");
        assertNotNull(c.active(), "activeId 指向不存在的条目时不能返回 null");
        assertEquals(c.providers().getFirst().id(), c.active().id());
    }

    /** 没起标签时拿域名顶上，界面上不能是一片空白。 */
    @Test
    void displayNameFallsBackToHost() {
        assertEquals("DeepSeek", new AiProvider("a", "DeepSeek", "https://api.deepseek.com", "m", AiProtocol.CHAT)
                .displayName());
        assertEquals("api.deepseek.com", new AiProvider("a", "", "https://api.deepseek.com", "m", AiProtocol.CHAT)
                .displayName());
        assertFalse(new AiProvider("a", "", "", "", AiProtocol.CHAT).displayName().isBlank());
    }

    @Test
    void incompleteProviderIsDetected() {
        assertFalse(new AiProvider("a", "x", "", "m", AiProtocol.CHAT).isComplete());
        assertFalse(new AiProvider("a", "x", "https://h", "", AiProtocol.CHAT).isComplete());
        assertTrue(new AiProvider("a", "x", "https://h", "m", AiProtocol.CHAT).isComplete());
    }

    // ------------------------------------------------------------ 协议

    /**
     * 两种协议的端点路径必须落在同一个 base 下。
     *
     * <p>协议填错的症状是 404，而 404 和「地址写错了」长得一模一样。
     * 所以至少要保证：同一个 base，换协议只换最后那一段路径。
     */
    @Test
    void protocolDecidesOnlyTheEndpointPath() {
        AiConfig c = AiConfig.defaults();
        AiProvider p = new AiProvider("t", "t", "https://api.example.com", "m", AiProtocol.RESPONSES);
        assertEquals("https://api.example.com/v1/responses",
                AiEndpoint.of(p, c, "sk-x").requestUrl());
        assertEquals("https://api.example.com/v1/chat/completions",
                AiEndpoint.of(p.withProtocol(AiProtocol.CHAT), c, "sk-x").requestUrl());
        // 模型清单和协议无关，两种协议都是同一个 /v1/models
        assertEquals("https://api.example.com/v1/models",
                AiEndpoint.of(p, c, "sk-x").modelsUrl());
        assertEquals("https://api.example.com/v1/models",
                AiEndpoint.of(p.withProtocol(AiProtocol.CHAT), c, "sk-x").modelsUrl());
    }

    /** 玩家把完整端点粘进来时，换协议要能把旧的那一段剥掉，不能拼成两段。 */
    @Test
    void pastedEndpointPathIsReplacedNotAppended() {
        assertEquals("https://api.example.com/v1/chat/completions",
                AiEndpoint.resolveChatUrl("https://api.example.com/v1/responses"));
        assertEquals("https://api.example.com/v1/responses",
                AiEndpoint.resolveUrl("https://api.example.com/v1/chat/completions"));
    }

    @Test
    void protocolParsingFallsBackToTheWiderOne() {
        assertEquals(AiProtocol.RESPONSES, AiProtocol.parse("responses"));
        assertEquals(AiProtocol.RESPONSES, AiProtocol.parse("  RESPONSES "));
        assertEquals(AiProtocol.CHAT, AiProtocol.parse("chat"));
        // 认不出来退回 CHAT：它的兼容面最广，猜错的代价最小
        assertEquals(AiProtocol.CHAT, AiProtocol.parse("nonsense"));
        assertEquals(AiProtocol.CHAT, AiProtocol.parse(null));
    }

    @Test
    void protocolSurvivesRoundTrip(@TempDir Path dir) {
        AiConfig c = AiConfig.defaults();
        AiProvider chatOne = new AiProvider("z", "zhipu", "https://open.bigmodel.cn/api/paas/v4",
                "glm", AiProtocol.CHAT);
        c = c.plusProvider(chatOne);
        c.save(dir);
        AiConfig back = AiConfig.load(dir);
        assertEquals(AiProtocol.CHAT, back.active().protocol());
        assertEquals("glm", back.active().model());
    }

    // ------------------------------------------------------------ 预设

    /**
     * 预设要真的覆盖到玩家手上可能有的那些 key。
     *
     * <p>只列 DeepSeek 和 OpenAI 的话，这个功能对大多数人不可用——
     * 他手上有哪家的 key 不是我们能决定的。
     */
    @Test
    void presetsCoverBothProtocolsAndAreWellFormed() {
        List<AiProvider> presets = AiProvider.presets();
        assertTrue(presets.size() >= 6, "预设太少，实际 " + presets.size() + " 条");
        assertTrue(presets.stream().anyMatch(p -> p.protocol() == AiProtocol.RESPONSES));
        assertTrue(presets.stream().anyMatch(p -> p.protocol() == AiProtocol.CHAT));

        for (AiProvider p : presets) {
            assertFalse(p.id().isBlank(), "预设缺 id");
            assertFalse(p.label().isBlank(), p.id() + " 缺显示名");
            assertFalse(p.baseUrl().isBlank(), p.id() + " 缺地址");
            // 地址必须能归一化出一个像样的端点，否则玩家选中即 404
            String url = AiEndpoint.of(p, AiConfig.defaults(), "sk-x").requestUrl();
            assertTrue(url.startsWith("http"), p.id() + " 的地址归一化不出端点: " + url);
            assertTrue(url.endsWith(p.protocol().path()), p.id() + " 端点路径不对: " + url);
        }
        // id 不能撞：撞了就会在 withProvider 里互相覆盖
        assertEquals(presets.size(), presets.stream().map(AiProvider::id).distinct().count());
    }

    /** 从预设导入必须换新 id，否则「点了新建却什么都没多出来」。 */
    @Test
    void importingAPresetTwiceYieldsTwoEntries() {
        AiConfig c = AiConfig.defaults();
        AiProvider preset = AiProvider.presets().getFirst();
        int before = c.providers().size();
        c = c.plusProvider(preset.withNewId());
        c = c.plusProvider(preset.withNewId());
        assertEquals(before + 2, c.providers().size());
    }

    // ------------------------------------------------------------ 模型清单

    @Test
    void parsesOpenAiStyleModelList() {
        String json = """
                {"object":"list","data":[
                  {"id":"deepseek-v4-pro","object":"model","owned_by":"deepseek"},
                  {"id":"deepseek-v4-flash","object":"model","owned_by":"deepseek"}
                ]}
                """;
        assertEquals(List.of("deepseek-v4-flash", "deepseek-v4-pro"), ModelCatalog.parse(json));
    }

    /** 中转服务这一层的实现相当随意，多认几种形状的成本只有几行。 */
    @Test
    void parsesLooserModelListShapes() {
        assertEquals(List.of("a", "b"), ModelCatalog.parse("[\"b\",\"a\"]"));
        assertEquals(List.of("m1"), ModelCatalog.parse("{\"data\":[{\"model\":\"m1\"}]}"));
        assertTrue(ModelCatalog.parse("not json").isEmpty());
        assertTrue(ModelCatalog.parse("{}").isEmpty());
        assertTrue(ModelCatalog.parse("{\"data\":[]}").isEmpty());
    }

    /**
     * 模型清单和 responses 是同一个 base 下的两个端点。
     *
     * <p>玩家把完整的 {@code .../v1/responses} 粘进来是很常见的，那时拉清单要是不剥掉尾巴，
     * 拼出来的就是 {@code .../v1/responses/models}——404，而 404 长得就像地址填错了。
     */
    @Test
    void modelsUrlSitsBesideResponsesUrl() {
        for (String written : List.of(
                "https://api.deepseek.com",
                "https://api.deepseek.com/",
                "https://api.deepseek.com/v1",
                "https://api.deepseek.com/v1/",
                "https://api.deepseek.com/v1/responses",
                "api.deepseek.com")) {
            assertEquals("https://api.deepseek.com/v1/responses",
                    AiEndpoint.resolveUrl(written), written);
            assertEquals("https://api.deepseek.com/v1/models",
                    AiEndpoint.resolveModelsUrl(written), written);
        }
    }

    // ------------------------------------------------------------ 安全回归

    /**
     * <b>类型上就没有装 key 的地方。</b>
     *
     * <p>钉类型而不是钉序列化行为：钉后者的话，防线是「记得跳过那个字段」，
     * 而那种约定第一次重构就会被忘掉——忘掉之后一切照常工作，没有任何报错。
     */
    @Test
    void neitherConfigNorProviderHasSomewhereToPutAKey() {
        for (Class<?> type : List.of(AiConfig.class, AiProvider.class)) {
            for (var component : type.getRecordComponents()) {
                String name = component.getName().toLowerCase(Locale.ROOT);
                for (String forbidden : List.of("key", "secret", "credential", "auth", "password")) {
                    assertFalse(name.contains(forbidden),
                            type.getSimpleName() + " 多了一个能装凭据的组件 " + component.getName()
                                    + "：key 必须走 AiCredentials，落在 ~/.gtshaders/ 而不是 config/");
                }
            }
        }
    }

    /**
     * 落盘的那份文件里也不能有，而且要<b>递归</b>查。
     *
     * <p>providers 是数组套对象，只查顶层的话，有人往单条 provider 里塞一个 apiKey
     * 这条测试照样绿——而那正是最可能发生的加法。
     */
    @Test
    void savedFileCarriesNoCredentials(@TempDir Path dir) throws Exception {
        AiConfig c = AiConfig.defaults();
        c = c.plusProvider(new AiProvider("x", "relay", "https://relay.example.com", "m", AiProtocol.CHAT));
        c.save(dir);
        String json = Files.readString(dir.resolve("ai.json"), StandardCharsets.UTF_8);
        assertNoCredentials(JsonParser.parseString(json), json);
        assertTrue(json.contains("relay.example.com"), "provider 列表没有被写出去");
    }

    private static void assertNoCredentials(JsonElement e, String json) {
        if (e.isJsonArray()) {
            for (JsonElement child : e.getAsJsonArray()) {
                assertNoCredentials(child, json);
            }
            return;
        }
        if (!e.isJsonObject()) {
            return;
        }
        JsonObject o = e.getAsJsonObject();
        for (String key : o.keySet()) {
            // 文件头上那句说明本身就要提到 credentials.json 在哪，跳过它。
            // 整体搜关键词会被自己的说明命中，而那种假警报最后一定是靠加例外绕过去的
            if (key.equals("_comment")) {
                continue;
            }
            String lowerKey = key.toLowerCase(Locale.ROOT);
            // 刻意不把 "token" 列进来：maxOutputTokens 是个正当字段
            for (String forbidden : List.of("key", "secret", "credential", "bearer", "auth")) {
                assertFalse(lowerKey.contains(forbidden),
                        "ai.json 里出现了不该有的字段 " + key + "：\n" + json);
            }
            JsonElement value = o.get(key);
            if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) {
                assertFalse(value.getAsString().startsWith("sk-"),
                        "ai.json 的 " + key + " 看起来是一把 key：\n" + json);
            }
            assertNoCredentials(value, json);
        }
    }
}
