package mc.GTedd.cn.gtshaders.codegen;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import mc.GTedd.cn.gtshaders.core.AnchorSlot;
import mc.GTedd.cn.gtshaders.core.ShaderParam;
import mc.GTedd.cn.gtshaders.core.ShaderProject;
import mc.GTedd.cn.gtshaders.core.EmitterSlot;
import mc.GTedd.cn.gtshaders.core.TrailSlot;
import mc.GTedd.cn.gtshaders.i18n.GtLang;

import java.util.ArrayList;
import java.util.List;

/**
 * 生成 post effect JSON（{@code assets/<ns>/post_effect/<id>.json}）。
 *
 * <p>多个效果层串成一条通道链，在主缓冲与自建的 {@code swap} 之间<b>乒乓</b>：
 * 第 0 层读主缓冲写 swap，第 1 层读 swap 写主缓冲，如此往复。
 * 这不是绕远路——<b>同一个 target 不能在一个通道里同时作为输入和输出</b>，
 * 原版的 blur 就是这么串六个通道的。
 *
 * <p>通道数为奇数时最终结果停在 swap 里，再用原版 {@code post/blit} 拷回主缓冲。
 *
 * <p>uniform 值的顺序必须和 {@link GlslCodegen} 生成的 std140 块<b>逐项对应</b>，
 * 所以两边都从同一份 {@code orderedParams} 出发，不允许各自排序。
 */
public final class PostEffectJsonBuilder {

    /** 自建的中转缓冲名。 */
    public static final String SWAP_TARGET = "swap";
    private static final String MAIN_TARGET = "minecraft:main";
    /**
     * 逐实体剪影遮罩缓冲。
     *
     * <p>只有 {@code minecraft:entity_outline} 那条链拿得到它——{@code LevelRenderer} 加载它时传的是
     * {@code OUTLINE_TARGETS = {main, entity_outline}}。而 {@code /posteffect} 挂的链拿到的是
     * {@code MAIN_TARGETS = {main}}，声明这个 target 会被 {@code PostChain.load} 直接拒掉。
     */
    private static final String OUTLINE_TARGET = "minecraft:entity_outline";
    /** 轮廓链里读主画面的那个输入，落到 GLSL 里就是 {@code SceneSampler}。 */
    public static final String SCENE_SAMPLER_NAME = "Scene";
    /**
     * 资源包里 {@code GTSystem} 的<b>唯一</b>取值：x=时间偏移 0、y=帧间隔 0、z=帧序号 0、w=播放中 1。
     *
     * <p>纯资源包没有 mod 每帧改写它，JSON 里写什么它就永远是什么。所以这四个数就是
     * 「真实加载时作者的着色器实际拿到的值」——预览要与真实加载无异，
     * 生成 JSON 时也必须用这一份，而不是把编辑器当下的时钟烘进去。
     * 时间之所以仍然会走，是因为 {@link GlslCodegen} 把 {@code GTTime} 定义成
     * {@code GameTime * 1200.0 + GTSystem.x}：资源包里偏移恒为 0，时间直接来自原版游戏时钟。
     */
    public static final float[] PACK_SYSTEM = {0f, 0f, 0f, 1f};

    private PostEffectJsonBuilder() {
    }

    /**
     * @param namespace 着色器所在的资源包命名空间
     * @param build     由 {@link ShaderProject#generate} 产出的通道链
     * @param system    系统参数当前值：x=时间 y=帧间隔 z=帧序号 w=播放中
     */
    public static JsonObject buildChain(String namespace, ShaderProject.Build build, float[] system) {
        JsonObject root = new JsonObject();

        JsonObject targets = new JsonObject();
        targets.add(SWAP_TARGET, new JsonObject());
        root.add("targets", targets);

        JsonArray passes = new JsonArray();
        for (ShaderProject.PassBuild pass : build.passes()) {
            passes.add(buildPass(namespace, pass, system));
        }
        if (build.needsFinalBlit()) {
            passes.add(buildBlitPass(SWAP_TARGET, MAIN_TARGET));
        }
        root.add("passes", passes);

        return root;
    }

    /**
     * 生成<b>实体轮廓链</b>（覆盖原版 {@code assets/minecraft/post_effect/entity_outline.json}）。
     *
     * <p>结构和 {@link #buildChain} 同源，两处不同：
     * <ul>
     *   <li>乒乓的两端是 {@code minecraft:entity_outline} 与 {@code swap}，
     *       而不是 {@code minecraft:main} 与 {@code swap}——引擎最后做
     *       {@code blitAndBlendToTexture} 时拿的是 {@code entity_outline}，
     *       结果没落回它就等于什么都没画。</li>
     *   <li>每个通道<b>多挂一个 {@code Scene} 输入读主画面</b>。这是这条链的价值所在：
     *       同时拿得到「哪块像素属于哪个实体」和「那块像素原本长什么样」，
     *       扭曲、透镜这类要改动原画面的效果才做得出来。</li>
     * </ul>
     *
     * <p>{@code Scene} 排在 {@code In} 之后不是随便定的：原版 {@code PostPass} 往
     * {@code SamplerInfo} 里先写一个 {@code OutSize}，再<b>按 inputs 的声明顺序</b>
     * 每个输入写一个 {@code vec2}。所以 GLSL 那边 {@code SamplerInfo} 的第三个成员
     * 必须是 {@code SceneSize}，顺序反了两个尺寸就互换，模糊半径之类会算错而且不报错。
     */
    public static JsonObject buildOutlineChain(String namespace, ShaderProject.Build build,
                                               float[] system) {
        JsonObject root = new JsonObject();

        JsonObject targets = new JsonObject();
        targets.add(SWAP_TARGET, new JsonObject());
        root.add("targets", targets);

        JsonArray passes = new JsonArray();
        for (ShaderProject.PassBuild pass : build.passes()) {
            passes.add(buildOutlinePass(namespace, pass, system));
        }
        if (build.needsFinalBlit()) {
            passes.add(buildBlitPass(SWAP_TARGET, OUTLINE_TARGET));
        }
        root.add("passes", passes);
        return root;
    }

    private static JsonObject buildOutlinePass(String namespace, ShaderProject.PassBuild pass,
                                               float[] system) {
        boolean readsOutline = pass.passIndex() % 2 == 0;
        String input = readsOutline ? OUTLINE_TARGET : SWAP_TARGET;
        String output = readsOutline ? SWAP_TARGET : OUTLINE_TARGET;

        JsonObject json = new JsonObject();
        json.addProperty("vertex_shader", "minecraft:core/screenquad");
        json.addProperty("fragment_shader", namespace + ":" + pass.shaderPath());

        JsonArray inputs = new JsonArray();
        JsonObject mask = new JsonObject();
        mask.addProperty("sampler_name", GlslCodegen.MAIN_SAMPLER_NAME);
        mask.addProperty("target", input);
        if (pass.layer().isBilinear()) {
            mask.addProperty("bilinear", true);
        }
        inputs.add(mask);
        JsonObject scene = new JsonObject();
        scene.addProperty("sampler_name", SCENE_SAMPLER_NAME);
        scene.addProperty("target", MAIN_TARGET);
        inputs.add(scene);
        json.add("inputs", inputs);

        json.addProperty("output", output);
        json.add("uniforms", uniformsFor(pass, system));
        return json;
    }

    /** 两条链的 uniform 块完全一样，抽出来免得改一处漏一处。 */
    private static JsonObject uniformsFor(ShaderProject.PassBuild pass, float[] system) {
        JsonObject uniforms = new JsonObject();
        JsonArray block = new JsonArray();
        // 三个系统 vec4 必须排在最前，与 GlslCodegen 里的顺序一致
        block.add(uniformEntry(GlslCodegen.SYSTEM_UNIFORM, "vec4", system, 4, false));
        block.add(uniformEntry(GlslCodegen.LAYER_UNIFORM, "vec4", new float[]{
                pass.layer().strength(), pass.enabledIndex(), pass.enabledCount(), 0f
        }, 4, false));
        block.add(uniformEntry(GlslCodegen.VIEWPORT_UNIFORM, "vec4", pass.viewportUv(), 4, false));
        if (pass.output().usesAnchors()) {
            appendAnchorSlots(block);
        }
        if (pass.output().usesEmitters()) {
            appendEmitterSlots(block);
        }
        if (pass.output().usesTrails()) {
            appendTrailSlots(block);
        }
        if (pass.output().usesCamera()) {
            appendCameraSlots(block);
        }
        for (ShaderParam p : pass.output().orderedParams()) {
            block.add(uniformEntry(p.name(), p.type().jsonType(), p.value(),
                    p.type().components(), p.type().isInteger()));
        }
        uniforms.add(GlslCodegen.PARAM_BLOCK, block);
        return uniforms;
    }

    private static JsonObject buildPass(String namespace, ShaderProject.PassBuild pass, float[] system) {
        boolean readsMain = pass.passIndex() % 2 == 0;
        String input = readsMain ? MAIN_TARGET : SWAP_TARGET;
        String output = readsMain ? SWAP_TARGET : MAIN_TARGET;

        JsonObject json = new JsonObject();
        json.addProperty("vertex_shader", "minecraft:core/screenquad");
        json.addProperty("fragment_shader", namespace + ":" + pass.shaderPath());

        JsonArray inputs = new JsonArray();
        JsonObject in = new JsonObject();
        in.addProperty("sampler_name", GlslCodegen.MAIN_SAMPLER_NAME);
        in.addProperty("target", input);
        if (pass.layer().isBilinear()) {
            in.addProperty("bilinear", true);
        }
        inputs.add(in);
        // 场景深度：另一个独立输入，指向 minecraft:main 但取深度纹理。
        //
        // 两个细节都不能改：
        // 1. target 恒为 minecraft:main，<b>不跟着乒乓走</b>——swap 是我们自建的中转缓冲，
        //    压根没有深度附件，指过去拿到的是空的。深度图本来也只有一份，与颜色到哪一步无关。
        // 2. 位置必须排在 In 之后、贴图之前，与 GlslCodegen 里 SamplerInfo 的成员顺序一致。
        if (pass.output().usesDepth()) {
            JsonObject depth = new JsonObject();
            depth.addProperty("sampler_name", GlslCodegen.DEPTH_SAMPLER_NAME);
            depth.addProperty("target", MAIN_TARGET);
            depth.addProperty("use_depth_buffer", true);
            inputs.add(depth);
        }
        // @texture 声明的额外贴图：直接作为资源包贴图输入，供后处理采样。

        for (ShaderTexture tex : pass.output().textures()) {
            JsonObject t = new JsonObject();
            t.addProperty("sampler_name", tex.samplerName());
            t.addProperty("location", tex.location());
            t.addProperty("width", tex.width());
            t.addProperty("height", tex.height());
            t.addProperty("bilinear", tex.bilinear());
            inputs.add(t);
        }
        json.add("inputs", inputs);

        json.addProperty("output", output);
        json.add("uniforms", uniformsFor(pass, system));
        return json;
    }

    /**
     * 把锚点那 {@code 1 + 2×SLOTS} 个 vec4 铺进 JSON 块。
     *
     * <p>GLSL 那边写的是 {@code vec4 GTAnchorA[8]}，这边铺成 {@code GTAnchorA0..A7} 八个独立条目——
     * <b>字节完全一致</b>：std140 下 vec4 数组的跨距就是 16 字节，和连续排列的独立 vec4 没有区别。
     * 之所以敢这么写，是因为原版 {@code PostPass} 只拿这个列表算 std140 尺寸、再把字节灌进 UBO，
     * 从不去反射 GLSL 块里究竟声明了什么，只要块名和总尺寸对得上就行。
     *
     * <p>初值全给 0，也就是「所有槽位都是空的」。这正是导出成纯资源包、没有 mod 每帧改写它时
     * 应有的状态——效果自动退回屏幕空间形态，而不是拿一堆未初始化的值去画。
     */
    private static void appendAnchorSlots(JsonArray block) {
        float[] zero = new float[4];
        block.add(uniformEntry(GlslCodegen.ANCHOR_INFO_UNIFORM, "vec4", zero, 4, false));
        for (int i = 0; i < AnchorSlot.SLOTS; i++) {
            block.add(uniformEntry(GlslCodegen.ANCHOR_A_UNIFORM + i, "vec4", zero, 4, false));
        }
        for (int i = 0; i < AnchorSlot.SLOTS; i++) {
            block.add(uniformEntry(GlslCodegen.ANCHOR_B_UNIFORM + i, "vec4", zero, 4, false));
        }
        for (int i = 0; i < AnchorSlot.SLOTS; i++) {
            block.add(uniformEntry(GlslCodegen.ANCHOR_C_UNIFORM + i, "vec4", zero, 4, false));
        }
    }


    /**
     * 把载体扩展那 {@code 2×SLOTS} 个 vec4 铺进 JSON 块。
     *
     * <p>没有表头：有效性由配对的锚点槽位说了算（{@code gtEmitterDir} 里那句
     * {@code gtAnchorValid(i)}），再写一份计数就是两个可能对不上的事实来源。
     */
    private static void appendEmitterSlots(JsonArray block) {
        float[] zero = new float[4];
        for (int i = 0; i < AnchorSlot.SLOTS; i++) {
            block.add(uniformEntry(GlslCodegen.EMITTER_A_UNIFORM + i, "vec4", zero, 4, false));
        }
        for (int i = 0; i < AnchorSlot.SLOTS; i++) {
            block.add(uniformEntry(GlslCodegen.EMITTER_B_UNIFORM + i, "vec4", zero, 4, false));
        }
    }

    /**
     * 把武器轨迹那 {@code 1 + 2×SLOTS} 个 vec4 铺进 JSON 块。做法与
     * {@link #appendAnchorSlots} 完全相同，理由见那边的注释。
     *
     * <p>初值同样全给 0，也就是「一帧轨迹都没有」。导出成纯资源包后没有 mod 写这个 uniform，
     * {@code gtTrailValid} 一律返回 false，{@code gtTrailAt} 恒返回未命中——
     * 刀光就是不出现，而不是画出一条位置错乱的带子。
     */
    private static void appendTrailSlots(JsonArray block) {
        float[] zero = new float[4];
        block.add(uniformEntry(GlslCodegen.TRAIL_INFO_UNIFORM, "vec4", zero, 4, false));
        for (int i = 0; i < TrailSlot.SLOTS; i++) {
            block.add(uniformEntry(GlslCodegen.TRAIL_A_UNIFORM + i, "vec4", zero, 4, false));
        }
        for (int i = 0; i < TrailSlot.SLOTS; i++) {
            block.add(uniformEntry(GlslCodegen.TRAIL_B_UNIFORM + i, "vec4", zero, 4, false));
        }
    }

    /**
     * 把世界相机那 4 个 vec4 铺进 JSON 块。
     *
     * <p>初值全给 0，也就是「这一帧没有相机数据」。纯资源包里没有 mod 每帧改写它，
     * 着色器那边 {@code gtCameraLive()} 恒为假，一族 helper 会退回一台默认相机：
     * 距离与视图坐标仍然算得出（FOV 70、zNear 0.05），但世界坐标会跟着视角转——
     * 网格钉不到世界上。这是纯资源包的固有限制，不是这里少写了什么。
     */
    private static void appendCameraSlots(JsonArray block) {
        float[] zero = new float[4];
        block.add(uniformEntry(GlslCodegen.CAMERA_PROJ_UNIFORM, "vec4", zero, 4, false));
        block.add(uniformEntry(GlslCodegen.CAMERA_RIGHT_UNIFORM, "vec4", zero, 4, false));
        block.add(uniformEntry(GlslCodegen.CAMERA_UP_UNIFORM, "vec4", zero, 4, false));
        block.add(uniformEntry(GlslCodegen.CAMERA_BACK_UNIFORM, "vec4", zero, 4, false));
    }

    /** 用原版 blit 把一个缓冲原样拷到另一个，不做任何色彩改动。 */
    private static JsonObject buildBlitPass(String from, String to) {
        JsonObject pass = new JsonObject();
        pass.addProperty("vertex_shader", "minecraft:core/screenquad");
        pass.addProperty("fragment_shader", "minecraft:post/blit");

        JsonArray inputs = new JsonArray();
        JsonObject in = new JsonObject();
        in.addProperty("sampler_name", GlslCodegen.MAIN_SAMPLER_NAME);
        in.addProperty("target", from);
        inputs.add(in);
        pass.add("inputs", inputs);

        pass.addProperty("output", to);

        JsonObject uniforms = new JsonObject();
        JsonArray block = new JsonArray();
        block.add(uniformEntry("ColorModulate", "vec4", new float[]{1f, 1f, 1f, 1f}, 4, false));
        uniforms.add("BlitConfig", block);
        pass.add("uniforms", uniforms);

        return pass;
    }

    private static JsonObject uniformEntry(String name, String type, float[] value,
                                           int components, boolean asInt) {
        JsonObject e = new JsonObject();
        e.addProperty("name", name);
        e.addProperty("type", type);
        if (components == 1) {
            e.add("value", scalar(value[0], asInt));
        } else {
            JsonArray arr = new JsonArray();
            for (int i = 0; i < components; i++) {
                arr.add(scalar(value[i], asInt));
            }
            e.add("value", arr);
        }
        return e;
    }

    private static JsonPrimitive scalar(float v, boolean asInt) {
        return asInt ? new JsonPrimitive(Math.round(v)) : new JsonPrimitive(v);
    }

    /** 导出到资源包时用带缩进的写法，方便作者事后手改。 */
    public static String pretty(JsonObject obj) {
        return new GsonBuilder().setPrettyPrinting().create().toJson(obj) + "\n";
    }

    /**
     * 断言 GLSL 块与 JSON 块的成员完全一一对应。std140 块一旦错位，画面不会报错，
     * 只会显示成一堆莫名其妙的颜色——那种问题极难排查，所以宁可在这里直接抛。
     */
    public static void verifyLayout(List<ShaderParam> orderedParams, String generatedGlsl) {
        int blockStart = generatedGlsl.indexOf("uniform " + GlslCodegen.PARAM_BLOCK);
        if (blockStart < 0) {
            throw new IllegalStateException(
                    GtLang.get("gtshaders.layout.block_missing", GlslCodegen.PARAM_BLOCK));
        }
        int blockEnd = generatedGlsl.indexOf("};", blockStart);
        String block = generatedGlsl.substring(blockStart, blockEnd);

        int cursor = block.indexOf(GlslCodegen.SYSTEM_UNIFORM);
        if (cursor < 0) {
            throw new IllegalStateException(
                    GtLang.get("gtshaders.layout.system_missing", GlslCodegen.SYSTEM_UNIFORM));
        }
        List<String> systemUniforms = new ArrayList<>(
                List.of(GlslCodegen.LAYER_UNIFORM, GlslCodegen.VIEWPORT_UNIFORM));
        // 锚点是按需注入的，所以按 GLSL 里实际有没有来决定要不要检查——
        // 用「源码里是不是出现过」当判据，和 GlslCodegen 的注入条件是同一个事实来源
        if (block.contains(GlslCodegen.ANCHOR_INFO_UNIFORM)) {
            systemUniforms.add(GlslCodegen.ANCHOR_INFO_UNIFORM);
            systemUniforms.add(GlslCodegen.ANCHOR_A_UNIFORM);
            systemUniforms.add(GlslCodegen.ANCHOR_B_UNIFORM);
            systemUniforms.add(GlslCodegen.ANCHOR_C_UNIFORM);
        }

        if (block.contains(GlslCodegen.EMITTER_A_UNIFORM)) {
            systemUniforms.add(GlslCodegen.EMITTER_A_UNIFORM);
            systemUniforms.add(GlslCodegen.EMITTER_B_UNIFORM);
        }
        if (block.contains(GlslCodegen.TRAIL_INFO_UNIFORM)) {
            systemUniforms.add(GlslCodegen.TRAIL_INFO_UNIFORM);
            systemUniforms.add(GlslCodegen.TRAIL_A_UNIFORM);
            systemUniforms.add(GlslCodegen.TRAIL_B_UNIFORM);
        }
        if (block.contains(GlslCodegen.CAMERA_PROJ_UNIFORM)) {
            systemUniforms.add(GlslCodegen.CAMERA_PROJ_UNIFORM);
            systemUniforms.add(GlslCodegen.CAMERA_RIGHT_UNIFORM);
            systemUniforms.add(GlslCodegen.CAMERA_UP_UNIFORM);
            systemUniforms.add(GlslCodegen.CAMERA_BACK_UNIFORM);
        }
        for (String systemUniform : systemUniforms) {
            int at = block.indexOf(systemUniform, cursor);
            if (at < 0) {
                throw new IllegalStateException(
                        GtLang.get("gtshaders.layout.system_missing", systemUniform));
            }
            cursor = at;
        }
        for (ShaderParam p : orderedParams) {
            int at = block.indexOf(" " + p.name() + ";", cursor);
            if (at < 0) {
                throw new IllegalStateException(
                        GtLang.get("gtshaders.layout.param_mismatch", p.name()));
            }
            cursor = at;
        }
    }
}
