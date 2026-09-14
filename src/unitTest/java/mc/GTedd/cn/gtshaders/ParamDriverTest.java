package mc.GTedd.cn.gtshaders;

import mc.GTedd.cn.gtshaders.codegen.GlslCodegen;
import mc.GTedd.cn.gtshaders.codegen.ParamScanner;
import mc.GTedd.cn.gtshaders.codegen.PostEffectJsonBuilder;
import mc.GTedd.cn.gtshaders.core.BlendMode;
import mc.GTedd.cn.gtshaders.core.GtProfile;
import mc.GTedd.cn.gtshaders.core.ParamDriver;
import mc.GTedd.cn.gtshaders.core.ParamType;
import mc.GTedd.cn.gtshaders.core.ShaderParam;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 参数驱动器：注解解析、烤进 GLSL、写回源码，以及参数名推断改成整词匹配之后的行为。 */
class ParamDriverTest {

    private static ShaderParam only(String source) {
        List<ShaderParam> ps = ParamScanner.scan(source).params();
        assertEquals(1, ps.size());
        return ps.get(0);
    }

    @Test
    void 注解里的驱动器被解析且子键不当成语言标签() {
        ShaderParam p = only("// @param name=Glow type=float min=0 max=2 default=1 zh_cn=辉光 "
                + "drive=triangle drive_from=0.2 drive_to=1.5 drive_period=4 drive_phase=0.25\n");
        ParamDriver d = p.driver();
        assertNotNull(d);
        assertEquals(ParamDriver.Wave.TRIANGLE, d.wave());
        assertEquals(0.2f, d.from());
        assertEquals(1.5f, d.to());
        assertEquals(4f, d.period());
        assertEquals(0.25f, d.phase());
        assertEquals("辉光", p.resolveLabel("zh_cn", k -> null));
        assertFalse(p.labels().containsKey("drive_period"));
    }

    @Test
    void 省略的子键取参数范围与两秒周期() {
        ParamDriver d = only("// @param name=A type=float min=-1 max=3 drive=saw\n").driver();
        assertEquals(-1f, d.from());
        assertEquals(3f, d.to());
        assertEquals(2f, d.period());
    }

    @Test
    void 非float参数的驱动器被忽略并给出警告() {
        ParamScanner.Result r = ParamScanner.scan("// @param name=C type=color3 drive=sine\n");
        assertNull(r.params().get(0).driver());
        assertEquals(1, r.warnings().size());
    }

    @Test
    void 生成物用宏把参数替换成时间函数且块布局不变() {
        ShaderParam p = only("// @param name=Glow type=float min=0 max=2 default=1 drive=sine drive_period=3\n");
        GlslCodegen.Output out = GlslCodegen.generate(GtProfile.MC_26_3,
                "void main() { fragColor = vec4(Glow); }\n", List.of(p), BlendMode.NORMAL);
        assertTrue(out.source().contains("float gtDrive(int wave"));
        assertTrue(out.source().contains("#define Glow gtDrive(0, 0.0, 2.0, 3.0, 0.0)"), out.source());
        assertTrue(out.source().indexOf("#define Glow") > out.source().indexOf("float Glow;"),
                "宏必须写在块声明之后，否则块里那一行也会被替换");
        assertDoesNotThrow(() -> PostEffectJsonBuilder.verifyLayout(out.orderedParams(), out.source()));
    }

    @Test
    void 没有驱动器时生成物里没有驱动代码() {
        ShaderParam p = only("// @param name=Glow type=float\n");
        String src = GlslCodegen.generate(GtProfile.MC_26_3, "void main() { fragColor = vec4(Glow); }\n",
                List.of(p), BlendMode.NORMAL).source();
        assertFalse(src.contains("gtDrive"));
    }

    @Test
    void 写回源码保留其他键且不增删行() {
        String source = "// @param name=Glow type=float min=0 max=2 default=1 zh_cn=辉光 desc=发光强度\n"
                + "void main() { fragColor = vec4(Glow); }\n";
        String on = ParamScanner.withDriver(source, "Glow",
                new ParamDriver(ParamDriver.Wave.SQUARE, 0f, 2f, 1.5f, 0f));
        assertNotNull(on);
        assertEquals(source.split("\n", -1).length, on.split("\n", -1).length);
        assertTrue(on.contains("zh_cn=辉光 desc=发光强度 drive=square"), on);
        ShaderParam parsed = ParamScanner.scan(on).params().get(0);
        assertEquals(ParamDriver.Wave.SQUARE, parsed.driver().wave());
        assertEquals("发光强度", parsed.resolveDesc("zh_cn"));

        String changed = ParamScanner.withDriver(on, "Glow", parsed.driver().withPeriod(6f));
        assertEquals(1, changed.split("drive=", -1).length - 1, "旧的驱动器键要先去掉，不能越叠越多");
        assertEquals(6f, ParamScanner.scan(changed).params().get(0).driver().period());

        String off = ParamScanner.withDriver(changed, "Glow", null);
        assertFalse(off.contains("drive"));
        assertNull(ParamScanner.scan(off).params().get(0).driver());
    }

    @Test
    void 裸uniform开驱动器时整行换成注解() {
        String source = "uniform float Speed;\nvoid main() { fragColor = vec4(Speed); }\n";
        String on = ParamScanner.withDriver(source, "Speed", new ParamDriver(ParamDriver.Wave.SINE, 0f, 5f, 2f, 0f));
        assertTrue(on.startsWith("// @param name=Speed type=float min=0 max=5"), on);
        assertEquals(source.split("\n", -1).length, on.split("\n", -1).length);
        assertNotNull(ParamScanner.scan(on).params().get(0).driver());
        assertNull(ParamScanner.withDriver(source, "Nope", null));
    }

    @Test
    void java侧求值与着色器公式一致() {
        ParamDriver sine = new ParamDriver(ParamDriver.Wave.SINE, 1f, 3f, 2f, 0f);
        assertEquals(1f, sine.evaluate(0f), 1e-5);
        assertEquals(3f, sine.evaluate(1f), 1e-5);
        ParamDriver tri = new ParamDriver(ParamDriver.Wave.TRIANGLE, 0f, 1f, 4f, 0.25f);
        assertEquals(0.5f, tri.evaluate(0f), 1e-5);
        ParamDriver square = new ParamDriver(ParamDriver.Wave.SQUARE, 0f, 1f, 1f, 0f);
        assertEquals(0f, square.evaluate(0.25f));
        assertEquals(1f, square.evaluate(0.5f), "step(0.5, t) 在 t = 0.5 处取 1");
        assertEquals(0.5f, new ParamDriver(ParamDriver.Wave.SAW, 0f, 1f, 2f, 0f).evaluate(1f), 1e-5);
    }

    @Test
    void 名字推断只认整词() {
        assertEquals(1f, only("uniform float Grayscale;\n").max(), "Grayscale 不是缩放");
        assertEquals(ParamType.COLOR3, only("uniform vec3 IndirectTint;\n").type(), "IndirectTint 不是方向");
        assertEquals(ParamType.FLOAT, only("uniform float HashSeed;\n").type(), "HashSeed 不是开关");
        assertEquals(10f, only("uniform float UVScale;\n").max());
        assertEquals(ParamType.VEC3, only("uniform vec3 LightDirection;\n").type());
        assertEquals(ParamType.INT, only("uniform float NumSamples;\n").type());
        assertEquals(ParamType.BOOL, only("uniform float use_probe;\n").type());
    }
}
