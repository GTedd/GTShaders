// 像素熔解 / Pixel Melt
// 画面先马赛克化，然后每一列以各自不同的速度往下淌，像屏幕化掉了往下滴。
// 两件事叠在一起才成立：只马赛克是「像素风」，只下淌是「拖影」，合起来才是熔解。
//
// 每列的速度用列号做随机，所以看起来是参差不齐地往下走，而不是整块下沉。
//
// [en_us]
// The picture pixelates and melts down column by column.
// The image first turns into a mosaic, then every column slides down at its own speed, as if the screen melted
// and dripped away. It only works with both: mosaic alone is "pixel art", sliding alone is "motion smear", and
// together they read as melting.
//
// Each column's speed is randomized from its column index, so the columns move down unevenly instead of the
// whole picture sinking as one block.
//
// @param name=AutoPlay type=bool default=1 zh_cn=随时间自动播放 en_us=Auto Play
// @param name=Duration type=float min=0.2 max=10 default=2.2 zh_cn=转场时长(秒) en_us=Duration
// @param name=Hold type=float min=0 max=10 default=0.6 zh_cn=两端停留(秒) en_us=Hold desc_zh_cn=盖满和揭开之后各停多久再往回走 desc_en_us=How long it rests at each end before reversing
// @param name=Progress type=float min=0 max=1 default=0.5 zh_cn=手动进度 en_us=Progress

// @group 熔解 / Melt
// @param name=MaxBlocks type=float min=4 max=200 default=48 zh_cn=最粗时的块数 en_us=Coarsest Blocks desc_zh_cn=进度拉满时横向剩几块。越小块越大，熔得越彻底 desc_en_us=How many blocks remain across the screen at full progress
// @param name=DropSpeed type=float min=0 max=3 default=1.1 zh_cn=下淌速度 en_us=Drop Speed
// @param name=Randomness type=float min=0 max=1 default=0.7 zh_cn=列间参差 en_us=Column Variance

// @group 外观 / Look
// @param name=Desaturate type=float min=0 max=1 default=0.4 zh_cn=褪色 en_us=Desaturate
// @param name=CoverColor type=color3 default=#000000 zh_cn=露出的底色 en_us=Backdrop

float hash11(float p) {
    return fract(sin(p * 91.3458) * 47453.5453);
}

// 往复播放：盖上 → 停一下 → 揭开 → 停一下，然后重来。
// 这里绝不能写成 clamp(GTTime / Duration)——GTTime 只增不减，跑过一遍之后恒等于 1，
// 画面会永远停在「已经盖满」那一帧。而玩家把效果加进工程时 GTTime 早就几百秒了，
// 于是「加上去只看到一块死板的颜色，怎么调都不动」。
float gtPingPong(float duration, float hold) {
    float d = max(duration, 0.01);
    float h = max(hold, 0.0);
    float age = mod(GTTime, (d + h) * 2.0);
    if (age < d) {
        return age / d;
    }
    if (age < d + h) {
        return 1.0;
    }
    if (age < d * 2.0 + h) {
        return 1.0 - (age - d - h) / d;
    }
    return 0.0;
}

void main() {
    float t = AutoPlay > 0.5 ? gtPingPong(Duration, Hold) : Progress;

    // 块数从「一像素一块」平滑收到 MaxBlocks。用 mix 在倒数上插值，
    // 直接对块数插值的话前半段几乎看不出变化
    float fine = max(OutSize.x, 1.0);
    float blocks = 1.0 / mix(1.0 / fine, 1.0 / max(MaxBlocks, 4.0), pow(t, 0.6));
    vec2 grid = vec2(blocks, blocks * OutSize.y / max(OutSize.x, 1.0));

    vec2 cell = floor(texCoord * grid);
    float speed = mix(1.0, hash11(cell.x * 3.77), Randomness);
    float drop = t * t * DropSpeed * speed;

    vec2 uv = (cell + 0.5) / grid;
    uv.y += drop;   // 采样点上移 = 画面内容下淌

    vec3 col;
    if (uv.y > 1.0) {
        col = CoverColor;
    } else {
        col = texture(InSampler, uv).rgb;
        float g = dot(col, vec3(0.2126, 0.7152, 0.0722));
        col = mix(col, vec3(g), Desaturate * t);
        col = mix(col, CoverColor, smoothstep(0.85, 1.0, uv.y));
    }
    fragColor = vec4(col, 1.0);
}
