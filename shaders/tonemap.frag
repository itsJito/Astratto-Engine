#include "include/head.glsl"
in vec2 vUV;
layout(location=0) out vec4 oColor;
uniform sampler2D uColor, uBloom, uExposure;
uniform float uBloomStrength, uTime, uDeath, uGrain, uFlash, uVignette, uHurt, uInfection, uSharpen, uBile;
uniform vec2 uScreen;
vec3 aces(vec3 x) { return clamp((x * (2.51 * x + 0.03)) / (x * (2.43 * x + 0.59) + 0.14), 0.0, 1.0); }
float h21(vec2 p) { return fract(sin(dot(p, vec2(12.9898, 78.233))) * 43758.5453); }
void main() {
    vec2 px = 1.0 / uScreen;
    vec3 col = texture(uColor, vUV).rgb;
    if (uSharpen > 0.0) {
        vec3 blur = (texture(uColor, vUV + vec2(px.x, 0)).rgb + texture(uColor, vUV - vec2(px.x, 0)).rgb + texture(uColor, vUV + vec2(0, px.y)).rgb + texture(uColor, vUV - vec2(0, px.y)).rgb) * 0.25;
        col = max(col + (col - blur) * uSharpen, vec3(0.0));
    }
    if (uBile > 0.0) {
        // bile in the eyes: the picture goes out of focus (two rings of taps, wider toward the edge of the frame) and drifts
        vec2 c0 = vUV - 0.5;
        float rad = uBile * (5.0 + 16.0 * dot(c0, c0) * 4.0) * (1.0 + 0.25 * sin(uTime * 2.3));
        vec2 drift = vec2(sin(uTime * 1.7 + vUV.y * 9.0), cos(uTime * 1.3 + vUV.x * 7.0)) * px * 3.0 * uBile;
        vec3 acc = col;
        for (int i = 0; i < 12; i++) {
            float an = float(i) * 0.5236 + uTime * 0.4;
            vec2 o = vec2(cos(an), sin(an)) * px * rad * (i % 2 == 0 ? 1.0 : 0.5);
            acc += texture(uColor, vUV + o + drift).rgb;
        }
        col = mix(col, acc / 13.0, min(1.0, uBile * 1.15));
    }
    col *= texelFetch(uExposure, ivec2(0), 0).r;
    col += texture(uBloom, vUV).rgb * uBloomStrength;
    col = aces(col);
    col = pow(col, vec3(1.0 / 2.2));
    vec2 q = vUV - 0.5;
    float vig = dot(q, q);
    col *= 1.0 - vig * uVignette;
    // injury: the edges of the frame close in red
    col = mix(col, vec3(0.45, 0.02, 0.01), clamp(uHurt * vig * 2.6, 0.0, 0.85));
    // infection: a sick green cast that pulses, strongest at the edges
    col = mix(col, col * vec3(0.72, 1.05, 0.62) + vec3(0.0, 0.03, 0.0), clamp(uInfection * (0.25 + vig * 2.0), 0.0, 0.8));
    // bile: dark green creeping in from the edges, running down the glass of the eye
    if (uBile > 0.0) {
        float run = 0.5 + 0.5 * sin(vUV.x * 37.0 + sin(vUV.y * 5.0 + uTime * 0.8) * 2.0);
        col = mix(col, col * vec3(0.55, 0.86, 0.42) + vec3(0.01, 0.05, 0.0), clamp(uBile * (0.22 + vig * 2.4 + run * vig * 1.2), 0.0, 0.85));
    }
    // death: colour drains to black and white while the grain rises
    float luma = dot(col, vec3(0.299, 0.587, 0.114));
    col = mix(col, vec3(luma) * (1.0 - uDeath * 0.25), uDeath);
    float grain = h21(floor(gl_FragCoord.xy / (1.0 + uDeath * 1.5)) + fract(uTime * 13.7) * 91.0) - 0.5;
    col += grain * (uGrain + uDeath * 0.22) * (0.35 + luma);
    // scan flicker of a dying picture
    col *= 1.0 - uDeath * 0.12 * step(0.985, h21(vec2(floor(gl_FragCoord.y / 3.0), floor(uTime * 24.0))));
    col = mix(col, vec3(1.0), clamp(uFlash, 0.0, 1.0));
    oColor = vec4(col + (h21(gl_FragCoord.xy) - 0.5) / 255.0, 1.0);
}
