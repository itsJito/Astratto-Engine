#include "include/head.glsl"
layout(location=0) out float oExposure;
uniform sampler2D uLum, uPrev;
uniform float uDt, uMin, uMax, uReset;
void main() {
    float avg = exp(textureLod(uLum, vec2(0.5), 10.0).r);
    float target = clamp(0.16 / max(avg, 1e-4), uMin, uMax);
    float prev = texelFetch(uPrev, ivec2(0), 0).r;
    if (uReset > 0.5 || !(prev > 0.0)) prev = target;
    // eyes open slowly in the dark and shut quickly in the light
    float rate = target > prev ? 0.7 : 2.4;
    oExposure = mix(prev, target, 1.0 - exp(-uDt * rate));
}
