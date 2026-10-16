#include "include/head.glsl"
in vec2 vQ;
in vec4 vColor;
in vec4 vParams;
in float vClipW;
uniform sampler2D uNoise, uDepth;
uniform vec2 uScreen;
uniform float uTime, uNear, uFar;
layout(location=0) out vec4 oColor;
float lin(float d) { return 2.0 * uNear * uFar / (uFar + uNear - (d * 2.0 - 1.0) * (uFar - uNear)); }
void main() {
    int kind = int(vParams.y + 0.5);
    float age = vParams.z, seed = vParams.w;
    float r = length(vQ);
    if (r > 1.0 && kind != 5 && kind != 7) discard;
    // soft where the sprite cuts into geometry
    float scene = lin(texture(uDepth, gl_FragCoord.xy / uScreen).r);
    float soft = clamp((scene - vClipW) * 2.5, 0.0, 1.0);
    float a;
    vec3 col = vColor.rgb;
    if (kind == 0) {
        // a tongue of flame: turbulence scrolling upward, hot core, torn edges
        vec2 uv = vQ * 0.5 + 0.5;
        float n1 = texture(uNoise, vec2(uv.x * 0.35 + seed, uv.y * 0.3 - uTime * 0.55 + seed * 3.1)).r;
        float n2 = texture(uNoise, vec2(uv.x * 0.9 - seed, uv.y * 0.7 - uTime * 1.1)).g;
        float shape = (1.0 - abs(vQ.x) * (1.0 + uv.y * 0.9)) * (1.0 - uv.y * uv.y * 0.9) * smoothstep(-1.0, -0.6, vQ.y);
        float f = shape * (0.55 + n1 * 0.9) - (0.35 + age * 0.45) * (0.6 + n2 * 0.8);
        a = smoothstep(0.0, 0.22, f);
        float heat = clamp(f * 2.2 + (1.0 - age) * 0.35, 0.0, 1.0);
        col *= mix(vec3(1.0, 0.16, 0.02), vec3(1.0, 0.78, 0.36), heat) * (1.0 + heat * 2.2);
        a *= vColor.a * (1.0 - age * age);
    } else if (kind == 1) {
        // 1.1, smoke. It was a soft disc with a little noise in it. Now each puff is a billow: its outline is torn by
        // two scales of turbulence that drift up through it, it is eaten away from inside as it ages (old smoke is
        // threads, not a ball), and it is shaded as a lit volume - paler on top where the sky is, darker underneath
        // and where it is thick.
        vec2 uv = vQ * 0.5;
        float n1 = texture(uNoise, uv * 0.50 + vec2(seed * 3.1, seed * 1.7 - uTime * 0.030)).r;
        float n2 = texture(uNoise, uv * 1.45 + vec2(-seed * 2.3, seed * 5.1 - uTime * 0.055)).g;
        float dens = n1 * 0.62 + n2 * 0.38;
        float edge = 1.0 - smoothstep(0.30, 1.0, r + (0.5 - dens) * 0.60);
        float erode = smoothstep(0.16 + age * 0.34, 0.60 + age * 0.28, dens + (1.0 - r) * 0.34);
        a = edge * erode * vColor.a * smoothstep(0.0, 0.07, age) * (1.0 - smoothstep(0.50, 1.0, age));
        float top = vQ.y * 0.5 + 0.5;
        col *= mix(0.60, 1.28, top) * mix(1.12, 0.78, edge * dens);
    } else if (kind == 2) {
        a = (1.0 - smoothstep(0.2, 1.0, r)) * vColor.a * (1.0 - age);
    } else if (kind == 4) {
        // an annulus that thins as it opens; broken up by noise so that it reads as dust, not as a drawn circle
        float n = texture(uNoise, vQ * 0.31 + vec2(seed * 7.0, seed * 3.0)).r;
        float w = mix(0.26, 0.10, age);
        a = (1.0 - smoothstep(0.0, w, abs(r - (0.98 - w)))) * (0.55 + 0.9 * n) * vColor.a * (1.0 - age) * (1.0 - age);
    } else if (kind == 5) {
        // hot at the head, drawn out to nothing behind
        a = (1.0 - abs(vQ.x)) * smoothstep(-1.0, 0.7, vQ.y) * (1.0 - smoothstep(0.8, 1.0, vQ.y)) * vColor.a * (1.0 - age * age);
    } else if (kind == 6) {
        // the flash at a muzzle: a core and a few uneven tongues, different every shot
        float an = atan(vQ.y, vQ.x);
        float spikes = pow(abs(cos(an * 2.5 + seed * 6.2832)), 5.0) + 0.6 * pow(abs(cos(an * 3.5 - seed * 9.0)), 9.0);
        float core = (1.0 - smoothstep(0.0, 0.42, r));
        a = (core * 1.6 + (1.0 - r) * (1.0 - r) * spikes * 1.5) * vColor.a * (1.0 - age);
        col *= mix(vec3(1.0, 0.55, 0.22), vec3(1.0, 0.95, 0.8), core);
    } else if (kind == 7) {
        a = (1.0 - abs(vQ.x)) * (1.0 - vQ.y * vQ.y) * vColor.a;
    } else if (kind == 8) {
        a = (1.0 - smoothstep(0.45, 1.0, r)) * vColor.a * (1.0 - smoothstep(0.75, 1.0, age));
    } else {
        a = (1.0 - r) * (1.0 - r) * vColor.a;
    }
    a *= soft;
    if (a < 0.004) discard;
    // premultiplied output: additive kinds carry no alpha, smoke and dust occlude, rain is half one and half the other
    oColor = (kind == 1 || kind == 8) ? vec4(col * a, a) : kind == 4 ? vec4(col * a, a * 0.8) : kind == 7 ? vec4(col * a * 0.55, a * 0.3) : vec4(col * a, 0.0);
}
