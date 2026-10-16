#include "include/head.glsl"
#include "include/sky.glsl"
#include "include/bank.glsl"
in vec2 vUV;
layout(location=0) out vec4 oColor;
uniform sampler2D uColor, uFog, uDepth;
uniform vec2 uFogSize;
uniform int uUseFog;
uniform mat4 uInvPV;
uniform vec3 uCam, uAmbient, uFogCol;
uniform float uDensity, uHeightBase, uMaxDist, uNear, uFar;
// 0.6, the fog bank: past uWall.x the world dissolves and by uWall.y it is gone, well inside the distance the
// world is drawn to; the sky is the same bank seen from below (uWall.z of it at the horizon, uWall.w overhead).
// Nothing ever appears out of clear air. 1.0: see BANK for what the world dissolves into, and from where.
float lin(float d) { return 2.0 * uNear * uFar / (uFar + uNear - (d * 2.0 - 1.0) * (uFar - uNear)); }
void main() {
    vec3 col = texture(uColor, vUV).rgb;
    float zc = texture(uDepth, vUV).r;
    vec4 c = uInvPV * vec4(vUV * 2.0 - 1.0, zc * 2.0 - 1.0, 1.0);
    vec3 P = c.xyz / c.w;
    vec3 ray = P - uCam;
    float L = max(length(ray), 1e-3);
    vec3 dir = ray / L;
    if (zc >= 1.0) col = mix(col, uWallCol, bankSkyK(dir));
    else {
        // the air past the end of the march: thinner with height, lit by the open sky (aerial perspective)
        float rest = L - uMaxDist;
        if (rest > 0.0 && uUseFog != 0) {
            float h = P.y - uHeightBase;
            float tr = exp(-uDensity * (h < -3.0 ? 2.2 : exp(-max(h, 0.0) * 0.045)) * rest);
            col = col * tr + (1.0 - tr) * uAmbient * uFogCol;
        }
        col = bankApply(col, P, uCam);
    }
    if (uUseFog == 0) { oColor = vec4(col, 1.0); return; }
    // the half-resolution haze, upsampled by depth so that it does not bleed round silhouettes
    vec4 sum = vec4(0.0);
    float w = 0.0, lc = lin(zc);
    vec2 base = floor(vUV * uFogSize - 0.5) + 0.5;
    vec2 f = vUV * uFogSize - base;
    for (int i = 0; i < 4; i++) {
        vec2 o = vec2(i & 1, i >> 1);
        vec2 uv = (base + o) / uFogSize;
        float lz = min(lin(textureLod(uDepth, uv, 0.0).r), uMaxDist);
        vec2 bw = mix(1.0 - f, f, o);
        float k = bw.x * bw.y / (0.02 + abs(lz - min(lc, uMaxDist)) / max(min(lc, uMaxDist), 1.0) * 24.0) + 1e-5;
        sum += texture(uFog, uv) * k; w += k;
    }
    vec4 fog = sum / w;
    oColor = vec4(col * fog.a + fog.rgb, 1.0);
}
