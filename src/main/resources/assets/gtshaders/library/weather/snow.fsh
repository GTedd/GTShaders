// 落雪 / Snowfall
// 三层不同大小、不同速度的雪片。分层是必须的：单层雪片没有远近，
// 会像一张动图贴在屏幕上；大而快的在前、小而慢的在后，立刻就有了空间。
//
// [en_us]
// Falling snow in three layers of flakes.
// Each layer has its own flake size and speed. The layering is essential: a single layer has no near and far and
// looks like an animated GIF stuck on the screen. Big, fast flakes in front and small, slow ones behind give an
// instant sense of space.
//
// @param name=Amount type=float min=0 max=1 default=0.55 zh_cn=雪量 en_us=Amount
// @param name=FlakeColor type=color3 default=#FFFFFF zh_cn=雪色 en_us=Flake Color
// @param name=FallSpeed type=float min=0 max=2 default=0.35 zh_cn=下落速度 en_us=Fall Speed
// @param name=Wind type=float min=-1 max=1 default=0.2 zh_cn=风向 en_us=Wind
// @param name=Cool type=float min=0 max=1 default=0.3 zh_cn=冷色调 en_us=Cool Grade

float hash(vec2 p) {
    return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453);
}

// 一层雪：把屏幕切格，每格一片，格子越密雪片越小越远
float layer(vec2 uv, float density, float speed, float size) {
    vec2 asp = vec2(OutSize.x / max(OutSize.y, 1.0), 1.0);
    vec2 grid = vec2(density) * vec2(asp.x, 1.0);

    float t = GTTime * FallSpeed * speed;
    // 整层一起下落 + 横向摆动，格子坐标随之平移
    vec2 p = uv * grid + vec2(Wind * t * 0.5 + sin(t * 0.8) * 0.3, -t);
    vec2 cell = floor(p);
    vec2 local = fract(p) - 0.5;

    float seed = hash(cell);
    vec2 jitter = (vec2(hash(cell + 1.7), hash(cell + 5.3)) - 0.5) * 0.7;
    float d = length(local - jitter);
    // 只有一部分格子有雪片，且大小随机
    return smoothstep(size * (0.6 + seed * 0.8), 0.0, d) * step(0.45, seed);
}

void main() {
    vec3 col = texture(InSampler, texCoord).rgb;

    float flakes = layer(texCoord, 26.0, 0.4, 0.09) * 0.35   // 远层：小、慢、暗
                 + layer(texCoord, 15.0, 0.7, 0.13) * 0.6
                 + layer(texCoord, 8.0, 1.2, 0.18) * 1.0;    // 近层：大、快、亮

    float luma = dot(col, vec3(0.2126, 0.7152, 0.0722));
    col = mix(col, vec3(luma) * vec3(0.88, 0.94, 1.05), Cool);
    col += FlakeColor * clamp(flakes, 0.0, 1.0) * Amount;
    fragColor = vec4(col, 1.0);
}
