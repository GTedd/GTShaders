package mc.GTedd.cn.gtshaders.minimap;

/** Coordinate math shared by terrain, markers and map clicks. X east, Z south. */
public final class MinimapMath {
    private MinimapMath() {}

    public record Point(double x, double y) {}

    public static int origin(double coordinate, int step, int size) {
        return Math.floorDiv((int) Math.floor(coordinate), step * 16) * 16 - size / 2;
    }

    /** GUI coordinates are Y-down. Minecraft yaw 0 points south. */
    public static double rotation(float yaw, boolean headingUp) {
        return headingUp ? Math.toRadians(180.0 - yaw) : 0;
    }

    public static Point project(double dx, double dz, double angle, double pixelsPerBlock) {
        double c = Math.cos(angle), s = Math.sin(angle);
        return new Point((dx * c - dz * s) * pixelsPerBlock,
                (dx * s + dz * c) * pixelsPerBlock);
    }

    public static Point unproject(double x, double y, double angle, double pixelsPerBlock) {
        return project(x, y, -angle, 1.0 / pixelsPerBlock);
    }

    public static Point clamp(Point p, double edge) {
        double scale = Math.max(Math.abs(p.x), Math.abs(p.y)) / edge;
        return scale > 1 ? new Point(p.x / scale, p.y / scale) : p;
    }

    public static int shade(int rgb, double factor) {
        int r = Math.clamp((int) ((rgb >> 16 & 255) * factor), 0, 255);
        int g = Math.clamp((int) ((rgb >> 8 & 255) * factor), 0, 255);
        int b = Math.clamp((int) ((rgb & 255) * factor), 0, 255);
        return 0xff000000 | r << 16 | g << 8 | b;
    }
}
