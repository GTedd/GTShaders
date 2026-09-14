package mc.GTedd.cn.gtshaders.runtime;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import mc.GTedd.cn.gtshaders.codegen.GlslCodegen;

/**
 * 世界相机运行时：把这一帧真实相机的投影系数与朝向，解算成着色器能读的四个 vec4。
 *
 * <h2>为什么非要 mod 抄一份过去</h2>
 *
 * <p>后处理链自己也有一个 {@code Projection} uniform 块，但那是<b>正交屏幕投影</b>——
 * 描述的是「把一个全屏四边形铺满屏幕」，跟世界相机毫无关系。于是链里拿到的深度图
 * 只能做相对比较（谁近谁远），换算不成米，更还原不出世界坐标。
 *
 * <p>这个类补上缺的那一半：每帧从 {@link Camera} 抄走投影系数与旋转，写进
 * {@link GlslCodegen#CAMERA_PROJ_UNIFORM} 那一组。有了它，
 * {@code gtWorldPos} / {@code gtLinearDepth} 才有真值可用。
 *
 * <h2>为什么是「反解」而不是直接取投影矩阵</h2>
 *
 * <p>{@link Camera} 只暴露 {@code getViewRotationProjectionMatrix}（即 {@code P·V}）
 * 和 {@code getViewRotationMatrix}（即 {@code V}），没有单独的 {@code P}。
 * 而 {@code V} 是<b>纯旋转、不含平移</b>（相机位置在原版里是另外减掉的），
 * 正交矩阵的逆就是转置，于是 {@code P = (P·V)·Vᵀ} 是精确的，不是近似。
 *
 * <p>这么绕一圈的好处是<b>不对投影矩阵的形式做任何假设</b>：不管 Mojang 用的是
 * 无限远反转投影还是带远平面的版本，元素都是从真矩阵里取的。
 *
 * <h2>唯一的一条假设</h2>
 *
 * <p>{@code ProjMat[3][2] > 0}，也就是反转深度。26.2-snapshot-1 起 Minecraft 用反转深度，
 * 这一项等于 zNear；换回传统深度的话它是负的。判据摆在这里，不成立时整组数据返回全 0，
 * 着色器那边 {@code gtCameraLive()} 转假、退回一台默认相机——
 * 画面会退化，但不会拿一组符号反了的系数去画出满屏鬼影。
 */
public final class CameraRuntime {

    /** 相机块占的 vec4 个数。数值的唯一来源是 {@link GlslCodegen#CAMERA_VEC4}。 */
    public static final int VEC4 = GlslCodegen.CAMERA_VEC4;

    /** 「这一帧没有可用相机」。资源包里 JSON 的初值也是这个，两边看到的退化形态一致。 */
    private static final float[] ZERO = new float[VEC4 * 4];

    /** 透视投影的 {@code ProjMat[2][3]} 恒为 −1；差太远说明拿到的根本不是透视矩阵。 */
    private static final float PERSPECTIVE_W_ROW = -1f;

    private CameraRuntime() {
    }

    /**
     * 解算这一帧的相机数据。
     *
     * @return 长度 {@code VEC4 * 4} 的数组：投影系数、右、上、后各一个 vec4；
     *         没有世界或相机还没初始化时全为 0
     */
    public static float[] solve() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.gameRenderer == null) {
            return ZERO.clone();
        }
        Camera cam = mc.gameRenderer.mainCamera();
        if (!cam.isInitialized()) {
            return ZERO.clone();
        }
        return pack(cam.getViewRotationProjectionMatrix(new Matrix4f()),
                cam.getViewRotationMatrix(new Matrix4f()));
    }

    /**
     * 纯数学部分，单独拆出来是为了能在没有 Minecraft 的单元测试里验证。
     *
     * @param viewProj {@code P·V}，V 是不含平移的视图旋转
     * @param viewRot  {@code V}
     * @return 见 {@link #solve()}
     */
    public static float[] pack(Matrix4fc viewProj, Matrix4fc viewRot) {
        // V 是正交矩阵（纯旋转），转置即逆。invView 同时是「视图 → 世界」的方向变换，
        // 下面三根基向量直接取它的三列
        Matrix4f invView = new Matrix4f(viewRot).transpose();
        Matrix4f proj = new Matrix4f(viewProj).mul(invView);

        float p00 = proj.m00();
        float p11 = proj.m11();
        float p32 = proj.m32();
        if (!Float.isFinite(p00) || !Float.isFinite(p11) || !Float.isFinite(p32)
                || Math.abs(p00) < 1e-6f || Math.abs(p11) < 1e-6f
                || p32 <= 0f
                || Math.abs(proj.m23() - PERSPECTIVE_W_ROW) > 1e-3f) {
            return ZERO.clone();
        }

        float[] out = new float[VEC4 * 4];
        // 竖直半视角的正切 = 1 / ProjMat[1][1]；宽高比 = ProjMat[1][1] / ProjMat[0][0]。
        // 两者都从真矩阵反解，所以窗口被拉成任何形状、FOV 被药水或疾跑改过，这里都跟得上
        out[0] = 1f / p11;
        out[1] = p11 / p00;
        // 深度线性化的两个系数：linearZ = ProjMat[3][2] / (d + ProjMat[2][2])。
        // 这条式子对任何 ProjMat[2][3] = −1 的透视矩阵都成立，与是否反转深度无关
        out[2] = proj.m22();
        out[3] = p32;

        out[4] = invView.m00();
        out[5] = invView.m01();
        out[6] = invView.m02();
        out[8] = invView.m10();
        out[9] = invView.m11();
        out[10] = invView.m12();
        out[12] = invView.m20();
        out[13] = invView.m21();
        out[14] = invView.m22();
        return out;
    }

    /** 全 0 的那一份，给资源包视角与没有世界时用。调用方不得改写返回值。 */
    public static float[] zero() {
        return ZERO.clone();
    }
}
