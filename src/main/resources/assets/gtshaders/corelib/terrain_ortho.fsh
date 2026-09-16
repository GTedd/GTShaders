// 等距视角（2.5D） / Isometric View
//
// 把原版的透视投影换成正交投影，做出类 RTS 的俯视画面：远处的方块不再变小，
// 平行的边在屏幕上依然平行。附带两件配套的事——锁死视角、让被挡住的表面透出来。
//
// 做法来自轩宇1725《着色器实践篇：简单2D场景的搭建》，但那篇是 1.21.10 的，
// <b>三处必须改</b>才能在 26.3 上跑对：
//
// 1. 26.2-snapshot-1 起 Minecraft 用<b>反转深度</b>，而且是无限远投影（ProjMat[2][2] 恒为 0）。
//    照抄那篇的 -2/(f-n)、-(f+n)/(f-n) 会让深度测试整个反过来，远处的方块盖住近处。
//    正确写法是近端映射到 1、远端映射到 0，见下面 gtOrthoProj。
// 2. 同样因为反转深度，「这个片元更近」在 26.3 是 gl_FragCoord.z <b>更大</b>，
//    比较方向和那篇相反。方向写反的表现是挖穿了远景、该透的近景反而实心。
// 3. 远平面<b>不在矩阵里</b>（无限远投影），反解不出来，只能由 ViewFar 给。
//    那篇的 n = (ProjMat[3][2]+1)/ProjMat[2][2] 在 26.3 上直接是除以 0。
//
// 想铺满整个画面，把这份源码<b>原样</b>加到每一个种类上：地形、实体、物品、粒子、
// 天空、云、告示牌文字……漏掉哪个，那类物体就还是透视的，会和场景错位。
// 界面不受影响——gtOrthoProj 认得出 GUI 用的正交矩阵并原样放行。
//
// [en_us]
// Replaces the vanilla perspective projection with an orthographic one, giving the RTS-style
// overhead look: distant blocks no longer shrink, and parallel edges stay parallel on screen.
// Two companion features come with it: locking the view angle, and letting occluding surfaces
// fade out so you can see what is behind them.
//
// The approach comes from Xuanyu1725 "Building a simple 2D scene with shaders", but that article
// targets 1.21.10 and <b>three things must change</b> for 26.3:
//
// 1. Since 26.2-snapshot-1 Minecraft uses <b>reversed depth</b> with an infinite far plane
//    (ProjMat[2][2] is always 0). Copying that article's -2/(f-n) and -(f+n)/(f-n) inverts the
//    depth test, so far blocks draw over near ones. The near plane must map to 1 and the far
//    plane to 0 instead; see gtOrthoProj below.
// 2. For the same reason, "this fragment is closer" means a <b>larger</b> gl_FragCoord.z on 26.3,
//    the opposite of the article. Get it backwards and you punch through the background while
//    the foreground stays solid.
// 3. The far plane is <b>not in the matrix</b>, so it cannot be recovered; ViewFar supplies it.
//    The article's n = (ProjMat[3][2]+1)/ProjMat[2][2] is a division by zero on 26.3.
//
// To cover the whole frame, add this <b>same</b> source to every kind: terrain, entities, items,
// particles, sky, clouds, sign text and so on. Whatever you skip stays perspective and will not
// line up with the rest. The GUI is safe: gtOrthoProj recognises the interface's own
// orthographic matrix and passes it through untouched.
//
// @param name=Ortho type=bool default=1 zh_cn=正交投影 en_us=Orthographic desc_zh_cn=关掉就退回原版透视，方便对照 desc_en_us=Turn off to fall back to vanilla perspective for comparison
// @param name=Zoom type=float min=2 max=128 default=24 zh_cn=视野半高（格） en_us=Zoom (blocks) desc_zh_cn=屏幕上下方向一共看到两倍这个值的格数 desc_en_us=The screen covers twice this many blocks vertically
// @param name=FovZoom type=bool default=1 zh_cn=用视场角缩放 en_us=Zoom With FOV desc_zh_cn=开着就能用游戏里的视场角滑块实时缩放；参数是编译期常量，这是唯一能运行时调的办法 desc_en_us=Lets the in-game FOV slider zoom in real time; params compile to constants, so this is the only runtime knob
// @param name=FovBase type=float min=30 max=110 default=70 zh_cn=视场角基准 en_us=FOV Baseline desc_zh_cn=视场角等于这个值时，缩放正好是上面的视野半高 desc_en_us=At this FOV the zoom equals the value above
// @param name=ViewFar type=float min=32 max=1024 default=320 zh_cn=视锥远端（格） en_us=Far Plane desc_zh_cn=比这更远的东西被裁掉。设太大会浪费深度精度，出现深度冲突 desc_en_us=Anything beyond this is clipped. Too large wastes depth precision and causes z-fighting
// @param name=ViewBack type=float min=0 max=256 default=64 zh_cn=相机身后深度（格） en_us=Behind Camera desc_zh_cn=正交视锥是个盒子，相机身后的东西不给留位置就会被近平面切掉 desc_en_us=The ortho frustum is a box; without room behind the camera the near plane slices things off
// @param name=LockView type=bool default=0 zh_cn=锁死视角 en_us=Lock View desc_zh_cn=不管玩家怎么转头，画面朝向都不变。开了如果左右反了，把下面的水平角取负 desc_en_us=Freezes the view direction no matter where the player looks. If it comes out mirrored, negate the yaw below
// @param name=ViewYaw type=float min=-180 max=180 default=45 zh_cn=水平角 en_us=Yaw desc_zh_cn=F3 里显示的那个水平角 desc_en_us=The yaw as shown in F3
// @param name=ViewPitch type=float min=-90 max=90 default=-30 zh_cn=俯仰角 en_us=Pitch desc_zh_cn=负值是俯视 desc_en_us=Negative looks down
// @param name=FlatFog type=bool default=1 zh_cn=平行雾 en_us=Parallel Fog desc_zh_cn=球状雾在正交画面里会变成一个圆形亮斑；改成等值面平行于屏幕就正常了 desc_en_us=Spherical fog shows up as a bright disc under ortho; this makes its iso-surfaces parallel to the screen
// @param name=Xray type=bool default=0 zh_cn=表面透射 en_us=Surface X-Ray desc_zh_cn=让挡在屏幕中心前面的表面透出来，看得见建筑内部 desc_en_us=Fades out surfaces occluding the screen centre so you can see inside
// @param name=XrayDepth type=float min=0 max=128 default=24 zh_cn=透射深度（格） en_us=X-Ray Depth desc_zh_cn=离相机比这更近的表面才参与透射。正交投影下深度是线性的，这里填的就是真实格数 desc_en_us=Only surfaces closer than this fade. Depth is linear under ortho, so this is real blocks
// @param name=XrayRadius type=float min=0 max=0.4 default=0.06 zh_cn=透射半径 en_us=X-Ray Radius desc_zh_cn=屏幕中心多大一圈内完全透明。屏幕对角线的一半约等于 0.5 desc_en_us=Fully transparent within this radius of the screen centre; half the diagonal is about 0.5
// @param name=XrayFeather type=float min=0.001 max=0.3 default=0.09 zh_cn=边缘羽化 en_us=X-Ray Feather desc_zh_cn=从全透到不透的过渡宽度 desc_en_us=Width of the fade from transparent to solid

// 只有透视投影才是「世界画面」。界面和物品栏图标用的本来就是正交矩阵，
// 第三行第四列不是 -1 —— 认出来原样放行，否则整个 GUI 会被二次投影毁掉。
bool gtIsWorldProjection(mat4 src) {
    return abs(src[2][3] + 1.0) < 1.0e-3;
}

mat4 gtFixedView(mat4 src) {
    if (LockView < 0.5) {
        return src;
    }
    float y = radians(ViewYaw);
    float p = radians(ViewPitch);
    // 和标准旋转矩阵差 180 度：F3 显示的朝向与相机实际朝向本来就是反的
    return mat4(
        -cos(y),  sin(y) * sin(p), -sin(y) * cos(p), 0.0,
         0.0,     cos(p),           sin(p),          0.0,
         sin(y), -cos(y) * sin(p), -cos(y) * cos(p), 0.0,
         0.0,     0.0,              0.0,             1.0
    );
}

mat4 gtOrthoProj(mat4 src) {
    if (Ortho < 0.5 || !gtIsWorldProjection(src)) {
        return src;
    }
    // ProjMat[1][1] 就是 1/tan(FOV/2)，宽高比等于两个对角元素的商。
    // 这两项在反转投影里照样成立，能反解出来的也就只有它们了。
    float aspect = src[1][1] / src[0][0];
    float halfH = Zoom;
    if (FovZoom > 0.5) {
        halfH = Zoom / (src[1][1] * tan(radians(FovBase) * 0.5));
    }
    float halfW = halfH * aspect;

    float zFar = ViewFar;
    float zNear = -ViewBack;    // 负值：把相机身后也收进盒子

    // ★ 反转深度：近端 -> 1，远端 -> 0。写成传统的 [-1,1] 会让深度测试整个反过来
    return mat4(
        1.0 / halfW, 0.0,         0.0,                   0.0,
        0.0,         1.0 / halfH, 0.0,                   0.0,
        0.0,         0.0,         1.0 / (zFar - zNear),  0.0,
        0.0,         0.0,         zFar / (zFar - zNear), 1.0
    );
}

// ★ 整个效果的支点：注入块落在 main 函数之前的全局作用域，所以这里的宏
//   能改写 main 里对这几个名字的引用，而上面 include 进来的 UBO 声明不受影响。
//   自引用是故意的，预处理器不会在宏自己的展开结果里再展开它一次。
//   顺序要求：这两行必须排在所有函数定义之后，否则函数体里的 ProjMat
//   会被替换成对自身的调用，变成无限递归。
#define ProjMat gtOrthoProj(ProjMat)
#define ModelViewMat gtFixedView(ModelViewMat)

// 离相机平面的距离，取代原版以相机为球心的距离。
//
// 视图矩阵是<b>当参数传进来</b>的，函数体里绝不出现 ModelViewMat——这是必须的：
// 删钩子只删钩子本身，这个函数会原样跟进片段阶段，而 clouds.fsh、
// rendertype_end_portal.fsh、rendertype_leash.fsh 根本没有 DynamicTransforms 块，
// 直接引用就是一句「未定义的 ModelViewMat」。写成参数以后，片段阶段留下的只是
// 一个没人调用的函数，编译器会把它消掉。
//
// 调用点在下面那行宏里，展开时 ModelViewMat 已经是锁定之后的视图矩阵——要的就是它。
// 原版只有 16 个顶点着色器调 fog_spherical_distance，片段着色器一个都没有，
// 所以这个宏在 fsh 里永远不会展开。
float gtFlatFogDistance(vec3 p, mat4 view) {
    if (FlatFog < 0.5) {
        return length(p);
    }
    return max(-(view * vec4(p, 1.0)).z, 0.0);
}
#define fog_spherical_distance(p) gtFlatFogDistance(p, ModelViewMat)

vec3 gtVertex(vec3 position) {
    // 顶点位置不用动：视角的事全部由上面的矩阵替换完成
    return position;
}

vec4 gtFragment(vec4 color) {
    if (Xray < 0.5) {
        return color;
    }

    // 透射的计算全部写在函数体里，不抽成同层的 helper：删钩子只删钩子本身，
    // 留在外面的 helper 会跟着进顶点阶段，而 gl_FragCoord 在那里不存在，
    // 编译直接报 C5052。
    float zFar = ViewFar;
    float zNear = -ViewBack;
    // 正交投影下深度是线性的，可以精确换算回「离相机平面几格」。
    // 反转深度：近端是 1、远端是 0，所以是减不是加。
    float viewDepth = zFar - gl_FragCoord.z * (zFar - zNear);
    if (viewDepth >= XrayDepth) {
        return color;
    }

    // 归一化因子取<b>屏幕尺寸</b>的较大边，不是当前片元坐标——
    // 用片元坐标的话每个像素的归一化尺度都不一样，透射窗口会被拉成不规则形状
    float scale = max(ScreenSize.x, ScreenSize.y);
    float dist = distance(gl_FragCoord.xy / scale, ScreenSize / (2.0 * scale));
    float fade = 1.0 - clamp((dist - XrayRadius) / XrayFeather, 0.0, 1.0);
    if (fade <= 0.0) {
        return color;
    }

    // 很多渲染阶段不接受半透明输出，所以用按概率丢弃片元来模拟。
    // 有序抖动（Bayer 4x4）而不是伪随机：同样的覆盖率下网点是规整的，
    // 看起来像磨砂玻璃；随机噪点在大面积上会明显发脏。
    const mat4 bayer = mat4(
         0.0625, 0.5625, 0.1875, 0.6875,
         0.8125, 0.3125, 0.9375, 0.4375,
         0.2500, 0.7500, 0.1250, 0.6250,
         1.0000, 0.5000, 0.8750, 0.3750
    );
    int bx = int(mod(gl_FragCoord.x, 4.0));
    int by = int(mod(gl_FragCoord.y, 4.0));
    if (bayer[bx][by] <= fade) {
        discard;
    }
    return color;
}
