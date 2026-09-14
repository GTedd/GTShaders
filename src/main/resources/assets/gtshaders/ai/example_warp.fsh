// 水下折射 / Underwater Refraction
// 两层错频的正弦波叠出水面晃动，再按到画面中心的距离给一点色散。
// 单层波看起来像果冻——只有两层频率不成整数比时，晃动才有水的那种不规则感。
//
// [en_us]
// Two out-of-sync sine waves create a water-like wobble. A little color dispersion is added based on
// distance from the screen center.
// A single wave looks like jelly. Only when the two frequencies are not an integer ratio does the wobble get
// that irregular feel of water.
//
// @param name=WaveScale type=float min=1 max=40 default=14 zh_cn=波纹密度 en_us=Wave Scale
// @param name=WaveSpeed type=float min=0 max=3 default=0.8 zh_cn=流动速度 en_us=Flow Speed
// @param name=Distort type=float min=0 max=0.05 default=0.012 zh_cn=扭曲强度 en_us=Distortion
// @param name=Drift type=vec2 min=-1 max=1 default=0.3,1 zh_cn=水流方向 en_us=Flow Direction desc_zh_cn=波纹整体往哪个方向漂 desc_en_us=Which way the ripples drift

// @group 色散 / Chromatic
// @param name=Chroma type=float min=0 max=0.02 default=0.004 zh_cn=色散量 en_us=Chromatic Shift
// @param name=Tint type=color3 default=#7FD4FF zh_cn=水色 en_us=Water Tint

// @group 光斑 / Caustics
// @param name=UseCaustics type=bool default=1 zh_cn=开启光斑 en_us=Caustics
// @param name=CausticSteps type=int min=1 max=8 default=4 zh_cn=光斑层数 en_us=Caustic Layers
// @param name=CausticGain type=float min=0 max=1 default=0.25 zh_cn=光斑强度 en_us=Caustic Gain

void main() {
    vec2 uv = texCoord;

    // 方向要先归一化，否则「方向」滑块同时也在改速度。除以长度而不是用 normalize：
    // Drift 拖到 (0,0) 时 normalize 会出 NaN，整块画面变黑
    vec2 dir = Drift / max(length(Drift), 1e-4);
    vec2 flow = dir * WaveSpeed * GTTime;

    // 1.37 不是随手写的：两层频率成整数比时会锁相，叠出规则的网格花纹
    float w1 = sin((uv.y + flow.y) * WaveScale);
    float w2 = sin((uv.x + flow.x) * WaveScale * 1.37 + 1.7);
    vec2 offset = vec2(w1, w2) * Distort;

    // 色散量随半径放大，中心保持干净——中心也色散的话像镜头脏了，不像水
    vec2 fromCenter = uv - 0.5;
    float radius = length(fromCenter * vec2(OutSize.x / max(OutSize.y, 1.0), 1.0));
    vec2 disp = fromCenter * Chroma * radius;

    // 三个通道各偏一点。采样坐标一律 clamp，否则画面边缘会取到拉伸的垃圾像素
    vec3 col;
    col.r = texture(InSampler, clamp(uv + offset + disp, 0.0, 1.0)).r;
    col.g = texture(InSampler, clamp(uv + offset, 0.0, 1.0)).g;
    col.b = texture(InSampler, clamp(uv + offset - disp, 0.0, 1.0)).b;

    col *= Tint;

    // 光斑。循环上界写成编译期常量 8，再用 break 提前退出——
    // 拿 uniform 当上界在 GLSL 330 里不保证能编译
    if (UseCaustics > 0.5) {
        float caustic = 0.0;
        for (int i = 0; i < 8; i++) {
            if (i >= CausticSteps) {
                break;
            }
            float fi = float(i) + 1.0;
            caustic += sin((uv.x * WaveScale + flow.x) * fi)
                    * cos((uv.y * WaveScale - flow.y) * fi) / fi;
        }
        col += max(caustic, 0.0) * CausticGain * Tint;
    }

    fragColor = vec4(col, 1.0);
}
