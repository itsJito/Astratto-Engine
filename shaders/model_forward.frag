#include "include/head.glsl"
#include "include/sky.glsl"
#include "include/bank.glsl"
in vec4 vCol;
in vec3 vWorld;
in vec3 vN;
uniform vec4 uTint;
uniform float uEmissive, uDensity;
uniform vec3 uCam, uFogCol, uAmbient;
uniform sampler3D uProbeR, uProbeG, uProbeB;
layout(location=0) out vec4 oColor;
void main() {
    vec3 n = normalize(vN);
    vec3 albedo = pow(vCol.rgb * uTint.rgb, vec3(2.2));
    vec3 uvw = (vWorld + n) / (vec3(WORLD_X, WORLD_Y, WORLD_Z));
    vec4 r = texture(uProbeR, uvw), g = texture(uProbeG, uvw), b = texture(uProbeB, uvw);
    vec3 irr = max(vec3(r.x, g.x, b.x), vec3(0.0)) + uSunCol * 0.15;
    vec3 col = mix(albedo * irr, albedo * (1.5 + 6.0 * uEmissive), clamp(uEmissive, 0.0, 1.0));
    float dist = length(vWorld - uCam);
    float f = (1.0 - exp(-uDensity * dist * 0.7)) * (1.0 - 0.5 * clamp(uEmissive, 0.0, 1.0));
    oColor = vec4(mix(mix(col, uAmbient * uFogCol, f), bankSky(normalize(vWorld - uCam)), bankAt(vWorld) * (1.0 - 0.35 * clamp(uEmissive, 0.0, 1.0))), vCol.a * uTint.a);
    if (oColor.a < 0.02) discard;
}
