package mc.GTedd.cn.gtshaders;

import org.junit.jupiter.api.Test;
import mc.GTedd.cn.gtshaders.runtime.WorldClock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 钉住「接管世界时钟」的换算。
 *
 * <p>这段换算错了不会报任何错，只表现为动画快了或慢了；而它同时作用于后处理层与核心着色器层，
 * 两边一起错的时候看上去反而「一致」，肉眼根本判不出来。所以用例直接走完整往返：
 * 编辑器秒数 → {@code (gameTime, partialTick)} → 原版折算公式 → 着色器读到的 {@code GTTime}，
 * 要求首尾相等。
 */
class WorldClockTest {

    /** 原版 {@code GlobalSettingsUniform.update} 里的那一句，逐字照抄。 */
    private static float vanillaGtTimeSeconds(long gameTime, float partialTick) {
        float dayFraction = ((float) (gameTime % 24000L) + partialTick) / 24000f;
        return dayFraction * 1200f;
    }

    private static float roundTrip(float seconds) {
        return vanillaGtTimeSeconds(WorldClock.gameTimeFor(seconds), WorldClock.partialTickFor(seconds));
    }

    @Test
    void 一秒等于二十tick() {
        // 24000 tick / 1200 秒。写成别的数就是整体快慢不对
        assertEquals(20L, WorldClock.gameTimeFor(1f));
        assertEquals(0f, WorldClock.partialTickFor(1f), 1e-4f);
    }

    @Test
    void 亚tick精度不丢() {
        // 半个 tick：只换整数部分的话这里会变成 0，动画就以 20 Hz 一格一格跳
        assertEquals(0L, WorldClock.gameTimeFor(0.025f));
        assertEquals(0.5f, WorldClock.partialTickFor(0.025f), 1e-3f);
    }

    @Test
    void 往返回到同一个秒数() {
        for (float s : new float[]{0f, 0.017f, 1f, 3.5f, 42.125f, 599.9f, 1199.95f}) {
            assertEquals(s, roundTrip(s), 1e-2f, "秒数 " + s + " 往返后对不上");
        }
    }

    @Test
    void 一天整数倍处回绕() {
        // 1200 秒 = 24000 tick = 一整天，原版折算里 % 24000 归零。
        // 这与纯资源包里 GTTime 的行为一致，是有意保留的
        assertEquals(0L, WorldClock.gameTimeFor(1200f));
        assertEquals(0f, roundTrip(1200f), 1e-2f);
        // 回绕之后继续往前走
        assertEquals(20L, WorldClock.gameTimeFor(1201f));
    }

    @Test
    void 负数被夹到零() {
        // 时间轴可以被拖到 0 附近；负的 gameTime 会让 % 24000 出负值，折算出负的 GTTime
        assertEquals(0L, WorldClock.gameTimeFor(-5f));
        assertEquals(0f, WorldClock.partialTickFor(-5f), 1e-4f);
    }

    @Test
    void 结果始终落在合法区间() {
        for (float s = 0f; s < 2500f; s += 7.3f) {
            long gt = WorldClock.gameTimeFor(s);
            float pt = WorldClock.partialTickFor(s);
            assertTrue(gt >= 0 && gt < 24000L, "gameTime 越界：" + gt + " @ " + s);
            assertTrue(pt >= 0f && pt < 1f, "partialTick 越界：" + pt + " @ " + s);
        }
    }
}
