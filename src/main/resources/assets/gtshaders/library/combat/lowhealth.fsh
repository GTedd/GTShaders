// 濒死 / Low Health
// 心跳节奏是关键：一次强跳紧跟一次弱跳，然后一段停顿。
// 匀速正弦会显得像呼吸而不是心悸，所以用 fract 取周期内位置再叠两个尖脉冲。
//
// [en_us]
// Low health: a pounding heartbeat pulse.
// The heartbeat rhythm is the key: a strong beat closely followed by a weak one, then a pause. A steady sine reads
// as breathing rather than palpitations, so fract takes the position within each cycle and two sharp pulses are
// layered on top.
//
// @param name=Severity type=float min=0 max=1 default=0.7 zh_cn=危急程度 en_us=Severity
// @param name=BPM type=float min=40 max=200 default=110 zh_cn=心率 en_us=Heart Rate
// @param name=BloodColor type=color3 default=#B4121B zh_cn=血色 en_us=Blood Color
// @param name=Desaturate type=float min=0 max=1 default=0.5 zh_cn=失色 en_us=Desaturate
// @param name=Pull type=float min=0 max=0.05 default=0.012 zh_cn=心跳拉扯 en_us=Heartbeat Pull

// 一个周期内的双峰：0.02 处一次强跳，0.24 处一次弱跳，其余时间归零
float heartbeat(float phase) {
    float a = exp(-pow((phase - 0.02) * 14.0, 2.0));
    float b = exp(-pow((phase - 0.24) * 16.0, 2.0)) * 0.6;
    return a + b;
}

void main() {
    float beat = heartbeat(fract(GTTime * BPM / 60.0)) * Severity;

    // 心跳时画面朝中心轻微收缩，配合红晕才有「揪一下」的感觉
    vec2 uv = mix(texCoord, vec2(0.5), beat * Pull);
    vec3 src = texture(InSampler, uv).rgb;

    float luma = dot(src, vec3(0.2126, 0.7152, 0.0722));
    vec3 col = mix(src, vec3(luma), Desaturate * Severity);

    vec2 asp = vec2(OutSize.x / max(OutSize.y, 1.0), 1.0);
    float r = length((texCoord - 0.5) * asp) / max(length(asp * 0.5), 1e-4);

    // 常驻的一层红边，加上心跳时向内推进的一层
    float ring = smoothstep(0.35, 1.05, r) * (0.35 + 0.65 * beat);
    ring *= 0.35 + 0.65 * Severity;

    col = mix(col, BloodColor, clamp(ring, 0.0, 1.0));
    col *= 1.0 - 0.25 * Severity * (1.0 - beat * 0.4);
    fragColor = vec4(col, 1.0);
}
