package mc.GTedd.cn.gtshaders.tools;

import com.google.gson.JsonParser;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.shaderc.Shaderc;
import org.lwjgl.util.shaderc.ShadercIncludeResolve;
import org.lwjgl.util.shaderc.ShadercIncludeResult;
import org.lwjgl.util.shaderc.ShadercIncludeResultRelease;
import mc.GTedd.cn.gtshaders.VanillaAssets;
import mc.GTedd.cn.gtshaders.codegen.CoreShaderCodegen;
import mc.GTedd.cn.gtshaders.codegen.DebugInstrument;
import mc.GTedd.cn.gtshaders.codegen.GlslCodegen;
import mc.GTedd.cn.gtshaders.codegen.ParamScanner;
import mc.GTedd.cn.gtshaders.core.BlendMode;
import mc.GTedd.cn.gtshaders.core.GtProfile;
import mc.GTedd.cn.gtshaders.core.ShaderKind;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 拿<b>游戏自己用的那个 ShaderC</b> 把整个素材库编译一遍。
 *
 * <h2>为什么不直接调 GlslValidator</h2>
 *
 * <p>{@code GlslCompiler.createBaseShaderOptions()} 里有一句
 * {@code RenderSystem.getDevice().getDeviceInfo().hintsAndWorkarounds().isExplicitDepthRequired()}，
 * 构建期没有 GPU 设备，走到那里就抛。但那一句<b>只用来决定要不要定义
 * {@code RENDERPEARL_EXPLICIT_DEPTH_INVARIANCE} 这个宏</b>，编译本身不需要设备。
 *
 * <p>所以这里直接用 LWJGL 的 {@code Shaderc}，把那几项编译选项逐项照抄过来
 * （值是从 {@code GlslCompiler} 的字节码里读出来的，不是猜的）：
 * <pre>
 *   target_env          = vulkan 1.2  (0x402000)
 *   auto_bind_uniforms  = true
 *   preserve_bindings   = false
 *   generate_debug_info = on
 *   optimization_level  = 0
 * </pre>
 * 用的是同一个 native 库、同一套选项，所以这不是「近似校验」，
 * 和游戏里那一步是同一个编译器在做同一件事。
 *
 * <h2>两类素材，两种验法</h2>
 *
 * <ul>
 *   <li><b>后处理效果</b>：产物自包含，直接编。</li>
 *   <li><b>核心着色器示例</b>：钩子注入进<b>真实原版模板</b>之后再编，而且要把那个种类的
 *       <b>每个变体宏</b>都过一遍——原版会为 {@code OIT_ALPHA_ONLY} /
 *       {@code PER_FACE_LIGHTING} 这些分别编一份，只要有一个变体编不过，
 *       那个效果在游戏里就是坏的。全组合是 2 的 11 次方，不现实；
 *       这里取「基线 + 每个宏单独 + 几个已知的真实组合」。</li>
 * </ul>
 *
 * <p>核心着色器要解析 {@code #include}，所以必须有原版 include 文件。
 * 没有 {@code docs/vanilla/<版本>/shaders/} 时这一半自动跳过，只验后处理（版本取自
 * {@code gradle.properties} 的 {@code minecraft_version}，见 {@link mc.GTedd.cn.gtshaders.VanillaAssets}）。
 */
public final class ShaderLibCompileCheck {

    private static final int VULKAN_1_2 = 4202496;
    private static final Path VANILLA = VanillaAssets.shadersRoot();

    /**
     * 有值的宏：原版把它们当常量用，只定义不给值会编译不过。
     * 取值抄自 {@code RenderPipelines} 的 {@code withShaderDefine} 调用。
     */
    private static final Map<String, String> MACRO_VALUES = Map.of("ALPHA_CUTOUT", "0.1");

    /**
     * 不是变体、而是<b>建管线时就带上</b>的常量，所以每次编译都要注入。
     *
     * <p>这几个在着色器源码里都<b>没有定义</b>，由 {@code RenderPipelines} 的
     * {@code withShaderDefine(name, value)} 在编译前塞进去。少了它们，模板里的
     * {@code for (int i = 0; i < PORTAL_LAYERS; i++)} 和 {@code oit_common.glsl} 里的
     * 数组尺寸就是「未声明的标识符」——那不是素材库坏了，是校验环境没还原出真实的编译上下文。
     *
     * <p>{@code PORTAL_LAYERS} 取 15（end_portal 那条管线的值，end_gateway 是 16）；
     * OIT 那三个取原版 {@code RenderPipelines} 里的实际取值。
     */
    private static final Map<String, String> PIPELINE_CONSTANTS = Map.of(
            "PORTAL_LAYERS", "15",
            "OIT_COEFF_COUNT", "8",
            "OIT_COEFF_ATTACHMENT_COUNT", "2",
            "OIT_WAVELET_RANK", "2");

    private static final Pattern MACRO_USE = Pattern.compile(
            "#if(?:n?def)\\s+([A-Z_0-9]+)|defined\\s*\\(\\s*([A-Z_0-9]+)\\s*\\)");

    /** native 分配的内存，编译全程要活着，最后统一释放。 */
    private static final List<Long> ALLOCATED = new ArrayList<>();
    private static final Map<String, String> PACK_INCLUDES = new LinkedHashMap<>();

    record Job(String label, String source, int kind, List<String> defines) {
    }

    record Failure(String label, String variant, String log) {
    }

    public static void main(String[] args) throws Exception {
        boolean standalonePack = args.length == 2 && args[0].equals("--pack");
        Path libRoot = Path.of(args.length > 0 ? args[0] : "shaderlib");
        List<Job> post = standalonePack ? collectPack(Path.of(args[1])) : collectPostEffects(libRoot);
        List<Job> core = standalonePack ? List.of() : collectCoreShaders();

        if (post.isEmpty() && core.isEmpty()) {
            System.out.println("没有找到可编译的着色器，先跑 generateShaderLib");
            System.exit(1);
        }

        List<Job> all = new ArrayList<>(post);
        all.addAll(core);
        List<Job> instrumented = standalonePack ? List.of() : collectInstrumented(post);
        if (!standalonePack) {
            all.addAll(collectHelperSmokeTests());
            all.addAll(instrumented);
        }

        long compiler = Shaderc.shaderc_compiler_initialize();
        ShadercIncludeResolve resolver = includeResolver();
        ShadercIncludeResultRelease releaser = ShadercIncludeResultRelease.create((u, r) -> {
        });
        List<Failure> failures = new ArrayList<>();
        int ok = 0;
        try {
            for (boolean depthFlags : new boolean[]{false, true}) {
                String variant = depthFlags ? "深度宏全开" : "深度宏全关";
                long options = baseOptions(depthFlags, resolver, releaser);
                try {
                    for (Job job : all) {
                        long opt = job.defines().isEmpty()
                                ? options : withDefines(options, job.defines());
                        try {
                            String log = compile(compiler, opt, job);
                            if (log == null) {
                                ok++;
                            } else {
                                failures.add(new Failure(job.label(), variant, log));
                            }
                        } finally {
                            if (opt != options) {
                                Shaderc.shaderc_compile_options_release(opt);
                            }
                        }
                    }
                } finally {
                    Shaderc.shaderc_compile_options_release(options);
                }
            }
        } finally {
            Shaderc.shaderc_compiler_release(compiler);
            resolver.free();
            releaser.free();
            for (long addr : ALLOCATED) {
                MemoryUtil.nmemFree(addr);
            }
        }

        System.out.println("=".repeat(72));
        // all 里除了 post 与 core，还有 helper 冒烟用例。
        // 之前这行只报前两项，加起来对不上总数，看的人会以为漏编了。
        System.out.printf("后处理 %d 份，核心着色器变体 %d 份，调试插桩 %d 份，其它 %d 份，各编 2 个设备变体 = %d 次%n",
                post.size(), core.size(), instrumented.size(),
                all.size() - post.size() - core.size() - instrumented.size(), all.size() * 2);
        System.out.printf("通过 %d，失败 %d%n", ok, failures.size());
        if (core.isEmpty() && !standalonePack) {
            System.out.println("（没有 " + VANILLA + "，核心着色器这一半跳过了）");
        }
        if (!failures.isEmpty()) {
            System.out.println("-".repeat(72));
            for (Failure f : failures) {
                System.out.println("[失败] " + f.label() + "  (" + f.variant() + ")");
                for (String line : f.log().split("\n")) {
                    if (!line.isBlank()) {
                        System.out.println("    " + line.trim());
                    }
                }
            }
            System.exit(1);
        }
        System.out.println("全部通过。");
    }

    // ------------------------------------------------------------ 收集

    private static List<Job> collectPack(Path archive) throws Exception {
        List<Job> jobs = new ArrayList<>();
        try (var zip = new java.util.zip.ZipFile(archive.toFile())) {
            for (var entry : zip.stream().filter(e -> e.getName().contains("/shaders/")).toList()) {
                String name = entry.getName();
                String source;
                try (var input = zip.getInputStream(entry)) { source = new String(input.readAllBytes(), StandardCharsets.UTF_8); }
                if (name.endsWith(".glsl")) {
                    String[] parts = name.split("/shaders/include/", 2);
                    if (parts.length == 2) PACK_INCLUDES.put(parts[0].substring("assets/".length()) + ":" + parts[1], source);
                } else if (name.endsWith(".fsh") || name.endsWith(".vsh")) {
                    jobs.add(new Job(name, source, name.endsWith(".vsh") ? Shaderc.shaderc_glsl_vertex_shader : Shaderc.shaderc_glsl_fragment_shader, List.of()));
                }
            }
        }
        return jobs;
    }

    private static List<Job> collectPostEffects(Path libRoot) throws Exception {
        List<Job> jobs = new ArrayList<>();
        Path dir = libRoot.resolve("assets/gtshaders/shaders/post");
        if (!Files.isDirectory(dir)) {
            return jobs;
        }
        try (var s = Files.list(dir)) {
            for (Path p : s.filter(f -> f.toString().endsWith(".fsh")).sorted().toList()) {
                jobs.add(new Job("post/" + p.getFileName(), Files.readString(p),
                        Shaderc.shaderc_glsl_fragment_shader, List.of()));
            }
        }
        return jobs;
    }

    /**
     * 按需注入的 helper 冒烟用例：每组 helper 一份最小源码，确认它<b>自己</b>编得过。
     *
     * <h2>为什么素材库编过了还要这一道</h2>
     *
     * <p>{@link #collectPostEffects} 编的是素材库里实际存在的效果，覆盖到哪组 helper
     * 完全看碰巧有没有人用过。{@code gtProbe} 只有四个效果在用，{@code gtTrail} 一个，
     * 而新加的 {@code gtPack} 一个都没有——也就是说这些 helper 从来没被 ShaderC 真编译过，
     * 它们能不能过全靠写的人肉眼检查。一旦某组 helper 里有个笔误，
     * 要等到玩家写出第一个用它的效果才会暴露，而那时错误信息指着的是玩家的代码。
     *
     * <p>每份源码都刻意写到「让驱动无法把这段调用优化掉」的程度：结果参与 {@code fragColor}。
     * 只声明不使用的话，编译器可能连函数体都不检查。
     */
    private static List<Job> collectHelperSmokeTests() {
        record Case(String id, String body) {
        }
        List<Case> cases = List.of(
                new Case("bitpack", """
                        void main() {
                            // 打包与解包都走一遍，再让结果落到 fragColor 上
                            vec4 packed = gtPackFloat(GTTime);
                            float back = gtUnpackFloat(packed);
                            uint bits = gtUnpackUint(gtPackUint(0xdeadbeefu));
                            float tex = gtUnpackFloatAt(InSampler, ivec2(0, 0));
                            uint texu = gtUnpackUintAt(InSampler, ivec2(1, 0));
                            fragColor = vec4(fract(back), fract(tex),
                                             float(bits & 0xffu) / 255.0,
                                             float(texu & 0xffu) / 255.0);
                        }
                        """),
                new Case("probe", """
                        void main() {
                            vec4 a = gtProbe(0);
                            float n = float(gtProbeCount());
                            fragColor = vec4(a.xyz, gtProbeValid(a) ? n : 0.0);
                        }
                        """),
                new Case("depth", """
                        void main() {
                            float d = gtDepth(texCoord);
                            float e = gtDepthEdge(texCoord, 1.0);
                            fragColor = vec4(d, e, gtIsSky(texCoord) ? 1.0 : 0.0, 1.0);
                        }
                        """),
                new Case("anchor", """
                        void main() {
                            vec4 a = gtAnchor(0);
                            fragColor = vec4(a.xy, gtAnchorDepth(0), gtAnchorStrength(0));
                        }
                        """),
                new Case("emitter", """
                        void main() {
                            fragColor = vec4(gtEmitterDir(0), gtEmitterFacing(0), gtEmitterRoll(0));
                        }
                        """),
                new Case("trail", """
                        void main() {
                            vec4 t = gtTrailAt(0.25);
                            fragColor = vec4(t.xyz, gtTrail(0.25));
                        }
                        """));

        List<Job> jobs = new ArrayList<>();
        for (Case c : cases) {
            GlslCodegen.Output out = GlslCodegen.generate(
                    GtProfile.MC_26_3, c.body(), List.of(), BlendMode.NORMAL);
            jobs.add(new Job("helper/" + c.id(), out.source(),
                    Shaderc.shaderc_glsl_fragment_shader, List.of()));
        }
        return jobs;
    }

    /**
     * 调试视图的插桩版本：素材库里每个效果各插一份异常检查、一份探针，再加驱动器与探针各类型的冒烟用例。
     *
     * <h2>为什么要把整个素材库都插一遍</h2>
     *
     * <p>异常检查是按 token 改写调用的——{@code clamp(ivec2, int, int)}、{@code smoothstep(float, float, vec3)}
     * 这种重载只要少写一个，改写后的着色器就编不过，而编辑器里的表现是「一开异常检查就报错」，
     * 报错还指着作者自己的代码。一百多个真实效果覆盖到的调用写法，比手写用例全面得多。
     * 探针插在 {@code main} 的第一行，验的是头部那组 {@code gtDbgCapture} 重载与输出编码本身。
     */
    private static List<Job> collectInstrumented(List<Job> post) {
        List<Job> jobs = new ArrayList<>();
        for (Job job : post) {
            String author = GlslCodegen.extractAuthorBody(job.source());
            // 实体轮廓层没有调试视图（它的 main 外壳与输入都不同），素材库里那几个跳过
            if (author == null || job.source().contains("uniform sampler2D SceneSampler;")) {
                continue;
            }
            ParamScanner.Result scanned = ParamScanner.scan(author);
            DebugInstrument.Outcome ub = DebugInstrument.ub(scanned.strippedBody());
            if (ub.ok()) {
                jobs.add(new Job("debug/ub/" + job.label(), GlslCodegen.generate(GtProfile.MC_26_3,
                        ub.value().body(), scanned.params(), BlendMode.NORMAL, ub.value().hook()).source(),
                        Shaderc.shaderc_glsl_fragment_shader, List.of()));
            }
            int mainLine = mainLine(scanned.strippedBody());
            if (mainLine > 0) {
                DebugInstrument.Outcome probe = DebugInstrument.probe(scanned.strippedBody(), mainLine, "texCoord");
                if (!probe.ok()) {
                    // main 的第一行必须插得进去；插不进说明判定规则把合法位置误拒了
                    throw new IllegalStateException(job.label() + " 的 main 第一行插不进探针：" + probe.errorKey());
                }
                jobs.add(new Job("debug/probe/" + job.label(), GlslCodegen.generate(GtProfile.MC_26_3,
                        probe.value().body(), scanned.params(), BlendMode.NORMAL, probe.value().hook()).source(),
                        Shaderc.shaderc_glsl_fragment_shader, List.of()));
            }
        }

        String body = """
                // @param name=Glow type=float min=0 max=2 default=1 drive=triangle drive_period=3
                void main() {
                    fragColor = vec4(vec3(Glow), 1.0);
                }
                """;
        for (String expr : new String[]{"1.0", "vec2(1.0)", "vec3(1.0)", "vec4(1.0)", "1", "ivec2(1)",
                "ivec3(1)", "ivec4(1)", "1u", "true", "Glow"}) {
            ParamScanner.Result scanned = ParamScanner.scan(body);
            DebugInstrument.Instrumented inst = DebugInstrument.probe(scanned.strippedBody(), 3, expr).value();
            jobs.add(new Job("debug/capture/" + expr, GlslCodegen.generate(GtProfile.MC_26_3,
                    inst.body(), scanned.params(), BlendMode.NORMAL, inst.hook()).source(),
                    Shaderc.shaderc_glsl_fragment_shader, List.of()));
        }
        return jobs;
    }

    private static int mainLine(String body) {
        String[] lines = body.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].matches(".*\\bvoid\\s+main\\s*\\(\\s*\\)\\s*\\{.*")) {
                return i + 1;
            }
        }
        return -1;
    }

    /** 内置核心示例，每个都注入真实模板，再按变体宏展开。 */
    private static List<Job> collectCoreShaders() throws Exception {
        List<Job> jobs = new ArrayList<>();
        if (!Files.isDirectory(VANILLA.resolve("core"))) {
            return jobs;
        }
        var index = JsonParser.parseString(read("/assets/gtshaders/corelib/index.json"))
                .getAsJsonObject();
        for (var el : index.getAsJsonArray("effects")) {
            var o = el.getAsJsonObject();
            String id = o.get("id").getAsString();
            ShaderKind.Entry kind = ShaderKind.find(o.get("kind").getAsString());
            if (kind == null) {
                continue;
            }
            var scanned = ParamScanner.scan(read("/assets/gtshaders/corelib/" + id + ".fsh"));
            for (String stage : kind.stages()) {
                Path tmpl = VANILLA.resolve(kind.path() + "." + stage);
                if (!Files.exists(tmpl)) {
                    continue;
                }
                String template = Files.readString(tmpl, StandardCharsets.UTF_8);
                CoreShaderCodegen.Output gen = CoreShaderCodegen.generate(
                        kind, GtProfile.MC_26_3, stage, template,
                        scanned.strippedBody(), scanned.params());
                int shKind = "vsh".equals(stage)
                        ? Shaderc.shaderc_glsl_vertex_shader
                        : Shaderc.shaderc_glsl_fragment_shader;
                for (var v : variantsOf(template).entrySet()) {
                    jobs.add(new Job(id + "." + stage + " [" + v.getKey() + "]",
                            gen.source(), shKind, v.getValue()));
                }
            }
        }
        return jobs;
    }

    /**
     * 这个模板值得编哪些变体。
     *
     * <p>全组合不现实（entity 有 11 个宏）。取的是：基线、每个宏单独开、
     * 以及原版真实会一起用的几个组合。
     */
    private static Map<String, List<String>> variantsOf(String template) {
        List<String> macros = new ArrayList<>();
        Matcher m = MACRO_USE.matcher(template);
        while (m.find()) {
            String name = m.group(1) != null ? m.group(1) : m.group(2);
            if (!macros.contains(name) && !name.startsWith("RENDERPEARL_")) {
                macros.add(name);
            }
        }
        Map<String, List<String>> out = new LinkedHashMap<>();
        out.put("基线", List.of());
        for (String macro : macros) {
            out.put(macro, withOitBase(List.of(macro)));
        }
        if (macros.contains("OIT_ACCUMULATE") && macros.contains("ALPHA_CUTOUT")) {
            out.put("OIT_ACCUMULATE+ALPHA_CUTOUT",
                    withOitBase(List.of("OIT_ACCUMULATE", "ALPHA_CUTOUT")));
        }
        if (macros.contains("PER_FACE_LIGHTING") && macros.contains("NO_OVERLAY")) {
            out.put("PER_FACE_LIGHTING+NO_OVERLAY", List.of("PER_FACE_LIGHTING", "NO_OVERLAY"));
        }
        if (macros.contains("GLINT") && macros.contains("EMISSIVE")) {
            out.put("GLINT+EMISSIVE", List.of("GLINT", "EMISSIVE"));
        }
        return out;
    }

    /**
     * 把 OIT 系的宏补成原版真实会用的组合。
     *
     * <p>两条规则，都是从原版 include 的写法反推出来的：
     *
     * <p><b>1. 任何 {@code OIT_*} 都要带 {@code OIT}。</b>
     * {@code oit.glsl} 把所有嵌套 include 整个包在 {@code #ifdef OIT} 里：
     * <pre>
     *   #ifdef OIT
     *       #include &lt;minecraft:oit_common.glsl&gt;
     *       ...
     *       #else
     *           #include &lt;minecraft:oit_sample.glsl&gt;   // sampleColorForAccumulation 在这
     *       #endif
     *   #endif
     * </pre>
     * 只开 {@code OIT_ACCUMULATE} 不开 {@code OIT}，那些函数一个都不会被引进来。
     *
     * <p><b>2. {@code OIT_DEPTH_BOUNDS} / {@code OIT_TRANSMITTANCE} 要带
     * {@code OIT_ALPHA_ONLY}。</b>这两个模式对应的 include <b>自己声明了 location 0 的输出</b>
     * （{@code oit_depth_bounds.glsl} 是 {@code out vec4 fragColor}，
     * {@code oit_add_transmittance.glsl} 是 {@code out vec4 coeff[]}），
     * 而模板自己那句 {@code fragColor} 声明包在 {@code #ifndef OIT_ALPHA_ONLY} 里。
     * 两者只有在 {@code OIT_ALPHA_ONLY} 同时打开时才不会撞车——换句话说，
     * 这两个模式在原版里<b>只出现在 alpha-only 阶段</b>。
     * 不补的话会报 {@code fragColor : redefinition}，看着像素材库坏了，
     * 其实是这个组合在原版里根本不存在。
     */
    private static List<String> withOitBase(List<String> defines) {
        if (defines.stream().noneMatch(d -> d.startsWith("OIT"))) {
            return defines;
        }
        List<String> out = new ArrayList<>(defines);
        if (!out.contains("OIT")) {
            out.add("OIT");
        }
        boolean alphaOnlyMode = out.contains("OIT_DEPTH_BOUNDS") || out.contains("OIT_TRANSMITTANCE");
        if (alphaOnlyMode && !out.contains("OIT_ALPHA_ONLY")) {
            out.add("OIT_ALPHA_ONLY");
        }
        return out;
    }

    private static String read(String resource) throws Exception {
        try (var in = ShaderLibCompileCheck.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("读不到 " + resource);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    // ------------------------------------------------------------ 编译

    private static long baseOptions(boolean depthFlags, ShadercIncludeResolve resolver,
                                    ShadercIncludeResultRelease releaser) {
        long o = Shaderc.shaderc_compile_options_initialize();
        applyBase(o, depthFlags);
        Shaderc.shaderc_compile_options_set_include_callbacks(o, resolver, releaser, 0L);
        return o;
    }

    private static void applyBase(long o, boolean depthFlags) {
        Shaderc.shaderc_compile_options_set_target_env(o,
                Shaderc.shaderc_target_env_vulkan, VULKAN_1_2);
        Shaderc.shaderc_compile_options_set_auto_bind_uniforms(o, true);
        Shaderc.shaderc_compile_options_set_preserve_bindings(o, false);
        Shaderc.shaderc_compile_options_set_generate_debug_info(o);
        Shaderc.shaderc_compile_options_set_optimization_level(o,
                Shaderc.shaderc_optimization_level_zero);
        PIPELINE_CONSTANTS.forEach((k, v) ->
                Shaderc.shaderc_compile_options_add_macro_definition(o, k, v));
        if (depthFlags) {
            Shaderc.shaderc_compile_options_add_macro_definition(o,
                    "RENDERPEARL_DEPTH_IS_ZERO_TO_ONE", "");
            Shaderc.shaderc_compile_options_add_macro_definition(o,
                    "RENDERPEARL_EXPLICIT_DEPTH_INVARIANCE", "");
        }
    }

    private static long withDefines(long base, List<String> defines) {
        long o = Shaderc.shaderc_compile_options_clone(base);
        for (String d : defines) {
            Shaderc.shaderc_compile_options_add_macro_definition(o, d,
                    MACRO_VALUES.getOrDefault(d, ""));
        }
        return o;
    }

    /** 把 include 指令解析到原版 include 目录。 */
    private static ShadercIncludeResolve includeResolver() {
        return ShadercIncludeResolve.create((userData, requested, type, requesting, depth) -> {
            String name = MemoryUtil.memUTF8(requested);
            int colon = name.indexOf(':');
            String file = colon >= 0 ? name.substring(colon + 1) : name;
            String content = "";
            try {
                Path p = VANILLA.resolve("include").resolve(file);
                if (PACK_INCLUDES.containsKey(name)) {
                    content = PACK_INCLUDES.get(name);
                } else if (Files.exists(p)) {
                    content = Files.readString(p, StandardCharsets.UTF_8);
                }
            } catch (Exception ignored) {
                // 读不到就当空文件，让编译器报「未定义的符号」而不是这里崩
            }
            ShadercIncludeResult result = ShadercIncludeResult.calloc();
            ALLOCATED.add(result.address());
            result.source_name(utf8(name));
            result.content(utf8(content));
            return result.address();
        });
    }

    /** 分配一块 native 内存放 UTF-8 字节，生命周期交给 ALLOCATED。 */
    private static ByteBuffer utf8(String s) {
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        ByteBuffer buf = MemoryUtil.memAlloc(bytes.length + 1);
        ALLOCATED.add(MemoryUtil.memAddress(buf));
        buf.put(bytes).put((byte) 0).flip();
        return buf.slice(0, bytes.length);
    }

    /** @return null 表示编译通过；否则是错误日志 */
    private static String compile(long compiler, long options, Job job) {
        long result = Shaderc.shaderc_compile_into_spv(compiler, job.source(), job.kind(),
                job.label(), "main", options);
        if (result == 0) {
            return "shaderc 没有返回结果";
        }
        try {
            if (Shaderc.shaderc_result_get_compilation_status(result)
                    == Shaderc.shaderc_compilation_status_success) {
                return null;
            }
            String msg = Shaderc.shaderc_result_get_error_message(result);
            return msg == null ? "未知错误" : msg;
        } finally {
            Shaderc.shaderc_result_release(result);
        }
    }
}
