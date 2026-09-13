package mc.GTedd.cn.gtshaders.minimap;

import java.util.Arrays;
import java.util.Comparator;
import java.util.stream.IntStream;

/** Bounded, sliding cache. No Minecraft objects survive a level change. */
public final class TerrainGrid {
    public static final int SIZE = 256;
    public static final int UNKNOWN = 0xff18232d;
    private static final int[] ORDER = IntStream.range(0, SIZE * SIZE).boxed()
            .sorted(Comparator.comparingInt(i -> {
                int x = i % SIZE - SIZE / 2, z = i / SIZE - SIZE / 2;
                return x * x + z * z;
            })).mapToInt(Integer::intValue).toArray();
    private int[] colors = new int[SIZE * SIZE];
    private int[] scratch = new int[SIZE * SIZE];
    private int originX, originZ, step, cursor;
    private boolean initialized;

    public TerrainGrid() { Arrays.fill(colors, UNKNOWN); }
    public int originX() { return originX; }
    public int originZ() { return originZ; }
    public int step() { return step; }
    public int[] colors() { return colors; }
    public int next() { int i = ORDER[cursor]; cursor = (cursor + 1) % ORDER.length; return i; }
    public void clear() { initialized = false; Arrays.fill(colors, UNKNOWN); cursor = 0; }

    public boolean center(double x, double z, int newStep) {
        int ox = MinimapMath.origin(x, newStep, SIZE), oz = MinimapMath.origin(z, newStep, SIZE);
        if (initialized && step == newStep && ox == originX && oz == originZ) return false;
        Arrays.fill(scratch, UNKNOWN);
        if (initialized && step == newStep) {
            int dx = ox - originX, dz = oz - originZ;
            int fromX = Math.max(0, -dx), toX = Math.min(SIZE, SIZE - dx);
            if (fromX < toX) {
                for (int row = Math.max(0, -dz); row < Math.min(SIZE, SIZE - dz); row++) {
                    System.arraycopy(colors, (row + dz) * SIZE + fromX + dx,
                            scratch, row * SIZE + fromX, toX - fromX);
                }
            }
        }
        int[] old = colors; colors = scratch; scratch = old;
        originX = ox; originZ = oz; step = newStep; initialized = true; cursor = 0;
        return true;
    }
}
