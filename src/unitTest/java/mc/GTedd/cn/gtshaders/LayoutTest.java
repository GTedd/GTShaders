package mc.GTedd.cn.gtshaders;

import org.junit.jupiter.api.Test;
import mc.GTedd.cn.gtshaders.ui.LayoutConfig;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 钉住缩放档位的那条不变式：<b>屏幕上的净放大倍数必须是整数</b>，
 * 也就是 {@code guiScale × BASE_SCALE × uiScale}。
 *
 * <p>注意净倍数里有三项而不是两项：{@link LayoutConfig#uiScale} 只是界面上那个百分比，
 * 实际渲染倍数还要乘 {@link LayoutConfig#BASE_SCALE}（见 {@link LayoutConfig#renderScale()}）。
 * 调默认大小时改的是 BASE_SCALE，档位表得跟着它重算，漏乘就会算出一组「看着合法、
 * 实际仍是非整数倍」的档位。
 *
 * <p>为什么非测不可：这条一旦被破坏，症状是「字看着有点糊」——没有报错、没有异常，
 * review 时也看不出来，只有把编辑器缩小才会发现。而它太容易被无意中破坏了：
 * 谁想加一档 0.9、觉得 0.5~2.0 该放宽、或者动一下 BASE_SCALE，就够了。
 */
class LayoutTest {

    @Test
    void 每一档的净放大倍数都是整数() {
        LayoutConfig c = new LayoutConfig();
        for (int g = 1; g <= 8; g++) {
            for (float s : c.scaleSteps(g)) {
                float net = s * g * LayoutConfig.BASE_SCALE;
                assertEquals(Math.round(net), net, 1e-3f,
                        "guiScale=" + g + " 时 " + s + " 档的净倍数 " + net + " 不是整数，位图字体会糊");
                assertTrue(net >= 1f, "净倍数小于 1 会丢笔画：guiScale=" + g + " 档位 " + s);
            }
        }
    }

    @Test
    void 档位有序且落在允许范围内() {
        LayoutConfig c = new LayoutConfig();
        for (int g = 1; g <= 8; g++) {
            float[] steps = c.scaleSteps(g);
            assertTrue(steps.length > 0, "guiScale=" + g + " 一档都没有");
            for (int i = 1; i < steps.length; i++) {
                assertTrue(steps[i] > steps[i - 1], "档位必须递增，否则加减号会走反");
            }
            for (float s : steps) {
                assertTrue(s >= LayoutConfig.MIN_SCALE - 1e-4f && s <= LayoutConfig.MAX_SCALE + 1e-4f,
                        s + " 超出允许范围");
            }
        }
    }

    @Test
    void 加减号在两端不会越界() {
        LayoutConfig c = new LayoutConfig();
        float[] steps = c.scaleSteps(2);
        for (int i = 0; i < 20; i++) {
            c.cycleScale(-1, 2, false);
        }
        assertEquals(steps[0], c.uiScale, 1e-4f, "一直按减号应当停在最小档");
        for (int i = 0; i < 20; i++) {
            c.cycleScale(1, 2, false);
        }
        assertEquals(steps[steps.length - 1], c.uiScale, 1e-4f, "一直按加号应当停在最大档");
    }

    @Test
    void 旧配置里的非法档位会被吸附回来() {
        LayoutConfig c = new LayoutConfig();
        // 0.85 是改造前那张写死的表里的值，在 guiScale=2 下净倍数 1.7，正是模糊的来源
        c.uiScale = 0.85f;
        c.snapScale(2, false);
        float net = c.uiScale * 2 * LayoutConfig.BASE_SCALE;
        assertEquals(Math.round(net), net, 1e-3f, "存盘的老档位必须被吸附到合法值");
    }

    @Test
    void 改了guiScale之后档位表跟着重算() {
        LayoutConfig c = new LayoutConfig();
        int atTwo = c.scaleSteps(2).length;
        int atFour = c.scaleSteps(4).length;
        // 缓存是按 guiScale 判的；写错成只算一次的话这两个值会相等
        assertTrue(atFour > atTwo,
                "GUI Scale 越大可选档位越多，实际 " + atTwo + " → " + atFour);
        assertEquals(atTwo, c.scaleSteps(2).length, "换回去应当拿到同样的表");
    }

    @Test
    void 重置回到百分之百那一档() {
        LayoutConfig c = new LayoutConfig();
        c.uiScale = 2.0f;
        // 有矢量字体时 1.0 一定在档位表里，重置就该正好落回 100%
        c.resetScale(3, true);
        assertEquals(1.0f, c.uiScale, 1e-4f);
    }

    @Test
    void 没有矢量字体时重置会落到最近的合法档() {
        LayoutConfig c = new LayoutConfig();
        c.uiScale = 2.0f;
        c.resetScale(3, false);
        // 位图字体下档位必须让净倍数为整数，1.0 未必在表里（guiScale=3 时不在）。
        // 这时「重置」的语义是回到离 100% 最近的合法档，而不是硬留在 1.0 上糊着
        float net = c.uiScale * 3 * LayoutConfig.BASE_SCALE;
        assertEquals(Math.round(net), net, 1e-3f, "重置后仍须保持整数倍");
        for (float s : c.scaleSteps(3, false)) {
            assertTrue(Math.abs(s - 1.0f) >= Math.abs(c.uiScale - 1.0f) - 1e-4f,
                    "应当落在离 100% 最近的那一档");
        }
    }

    // ---- 缩放滑条 ----

    @Test
    void 滑轨两端正好对上首尾档() {
        LayoutConfig c = new LayoutConfig();
        int n = c.scaleSteps(2, true).length;
        assertEquals(0, LayoutConfig.indexAt(0.0, n), "最左端必须是第一档");
        assertEquals(n - 1, LayoutConfig.indexAt(1.0, n), "最右端必须是最后一档");
    }

    @Test
    void 滑轨位置吸附到最近的一档() {
        // 12 档时每档占 1/11。落在 0.5/11 之内算第 0 档，超过就进第 1 档
        assertEquals(0, LayoutConfig.indexAt(0.4 / 11, 12));
        assertEquals(1, LayoutConfig.indexAt(0.6 / 11, 12));
        assertEquals(6, LayoutConfig.indexAt(6.0 / 11, 12));
    }

    @Test
    void 越界的滑轨位置被夹住而不是算出非法下标() {
        assertEquals(0, LayoutConfig.indexAt(-3.0, 12), "拖出左边界不该算成负数下标");
        assertEquals(11, LayoutConfig.indexAt(4.0, 12), "拖出右边界不该越过表尾");
        assertEquals(0, LayoutConfig.indexAt(0.5, 1), "只有一档时哪儿都是第 0 档");
    }

    /**
     * 这条守的是性能，不是正确性：滑条拖动时每一帧都会调 {@code setScaleAt}，
     * 而「档位变了」要触发重新烘焙字号。不返回「没变」的话，拖一次滑条就会烘焙上百次，
     * 表现是拖动直接卡死——而功能看起来完全正常，最难查的那种。
     */
    @Test
    void 拖到同一档时要报告没变过() {
        LayoutConfig c = new LayoutConfig();
        c.setScaleAt(0.0, 2, true);
        assertFalse(c.setScaleAt(0.0, 2, true), "同一档重复设置必须返回 false");
        assertFalse(c.setScaleAt(0.01, 2, true), "同一档内的微小移动也不算变");
        assertTrue(c.setScaleAt(1.0, 2, true), "真的换档了才返回 true");
    }

    @Test
    void 滑条能走遍每一档且落点就是档位值() {
        LayoutConfig c = new LayoutConfig();
        float[] steps = c.scaleSteps(2, true);
        for (int i = 0; i < steps.length; i++) {
            c.setScaleIndex(i, 2, true);
            assertEquals(steps[i], c.uiScale, 1e-4f, "第 " + i + " 档没落在档位值上");
            assertEquals(i, c.currentStepIndex(2, true), "反查下标应当回到原处");
        }
    }

    @Test
    void 默认档位就是界面上显示的百分之百() {
        LayoutConfig c = new LayoutConfig();
        assertEquals(1.0f, c.uiScale, 1e-4f, "新建配置应当停在 100% 那一档");
        assertEquals("100%", c.scaleLabel(), "默认大小在界面上就该写 100%");
        assertEquals(LayoutConfig.BASE_SCALE, c.renderScale(), 1e-4f,
                "100% 档的实际渲染倍数等于基准本身");
    }

    /**
     * 代码区的缩放套在编辑器整体缩放<b>之内</b>，实际倍数是
     * {@code guiScale × BASE_SCALE × uiScale × zoom} 四者相乘。这里穷举前几层的所有组合，
     * 验证最后一层无论怎么选，最终乘积仍是整数——漏掉这一层的话，代码区一缩小就糊，
     * 而代码恰恰是最需要看清的地方。
     *
     * <p>基准<b>不取整</b>，和生产里的 {@code EditorScreen.baseNet()} 一致：
     * 取整会算出一组反而让代码区发虚的档位。
     */
    @Test
    void 四层缩放叠起来仍然是整数倍() {
        LayoutConfig c = new LayoutConfig();
        for (int g = 1; g <= 6; g++) {
            for (float ui : c.scaleSteps(g)) {
                float baseNet = Math.max(0.1f, g * LayoutConfig.BASE_SCALE * ui);
                for (float zoom : LayoutConfig.integerSteps(baseNet, 0.5f, 2.0f)) {
                    float net = baseNet * zoom;
                    assertEquals(Math.round(net), net, 1e-3f,
                            "guiScale=" + g + " uiScale=" + ui + " zoom=" + zoom
                                    + " 的净倍数 " + net + " 不是整数");
                }
            }
        }
    }

    /**
     * 矢量字体可用时不再受「净倍数必须是整数」约束——字是按字号重新栅格化的，
     * 不存在缩放位图那回事。这里钉住的是<b>档位确实放开了</b>：
     * 如果哪天有人把两条路径合并掉，最先没的就是这些中间档。
     */
    @Test
    void 有矢量字体时档位明显更多() {
        LayoutConfig c = new LayoutConfig();
        for (int g = 1; g <= 4; g++) {
            int bitmap = c.scaleSteps(g, false).length;
            int vector = c.scaleSteps(g, true).length;
            assertTrue(vector > bitmap,
                    "guiScale=" + g + " 时矢量档位(" + vector + ") 应当多于位图档位(" + bitmap + ")");
        }
    }

    @Test
    void 矢量档位与guiScale无关() {
        LayoutConfig c = new LayoutConfig();
        // 位图档位取决于 guiScale，矢量档位不该取决于它——这正是换字体换来的东西
        assertEquals(c.scaleSteps(2, true).length, c.scaleSteps(5, true).length);
    }

    @Test
    void 矢量档位递增且含原始大小() {
        LayoutConfig c = new LayoutConfig();
        float[] steps = c.scaleSteps(2, true);
        boolean hasOne = false;
        for (int i = 0; i < steps.length; i++) {
            if (i > 0) {
                assertTrue(steps[i] > steps[i - 1], "档位必须递增");
            }
            assertTrue(steps[i] >= LayoutConfig.MIN_SCALE - 1e-4f
                    && steps[i] <= LayoutConfig.MAX_SCALE + 1e-4f, steps[i] + " 超出范围");
            hasOne |= Math.abs(steps[i] - 1.0f) < 1e-4f;
        }
        assertTrue(hasOne, "没有 100% 这一档，resetScale 会落到非法值上");
    }

    /**
     * 界面换成矢量字体之后，{@code guiScale × uiScale} 不再保证是整数（0.7 档下它是 1.4）。
     * 但<b>代码区仍然烧原版位图字体</b>——GLSL 要靠等宽对齐，而系统 UI 字体几乎都是变宽的。
     * 所以那一层的整数约束还在，只是基准变成了小数。
     *
     * <p>这条最容易被漏掉：界面清楚了、代码区却糊了，而且只在非整数档位下才出现。
     */
    @Test
    void 矢量档位下代码区仍能凑出整数净倍数() {
        LayoutConfig c = new LayoutConfig();
        for (int g = 1; g <= 4; g++) {
            for (float ui : c.scaleSteps(g, true)) {
                float base = g * ui;
                float[] steps = LayoutConfig.integerSteps(base, 0.5f, 2.0f);
                assertTrue(steps.length > 0, "base=" + base + " 一档都没有");
                for (float zoom : steps) {
                    float net = base * zoom;
                    assertEquals(Math.round(net), net, 1e-3f,
                            "guiScale=" + g + " uiScale=" + ui + " zoom=" + zoom
                                    + " 时代码区净倍数 " + net + " 不是整数");
                }
            }
        }
    }

    @Test
    void 每个基准下都至少给出原始大小这一档() {
        for (int base = 1; base <= 8; base++) {
            boolean hasOne = false;
            for (float s : LayoutConfig.integerSteps(base, 0.5f, 2.0f)) {
                if (Math.abs(s - 1.0f) < 1e-4f) {
                    hasOne = true;
                }
            }
            // 1.0 恒等于 base/base，一定在表里。没有它 resetZoom() 会把值设成非法档位
            assertTrue(hasOne, "base=" + base + " 的档位表里没有 1.0");
        }
    }
}
