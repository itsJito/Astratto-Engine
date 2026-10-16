#include "include/head.glsl"
#include "include/world_tex.glsl"
#include "include/sky.glsl"
#include "include/bank.glsl"
#include "include/fwd_fog.glsl"
uniform sampler3D uProbeR, uProbeG, uProbeB;
uniform vec3 uCam, uFogCol, uAmbient;
uniform float uDensity;
layout(location=0) out vec4 oColor;
void main() {
    vec3 bp;
    vec4 tex = atlasTexel(bp);
    vec3 N = NORMALS[vNormal];
    bool liquid = (vFlags & 4) != 0;
    // 0.8: a pane is drawn here once - its near face - and only where it is glass. The frame round it is solid and
    // was written with the rest of the solid world (it has depth: it hides what is behind it, glass included).
    if (!liquid && (paneAlpha() >= GLASS_SOLID || !gl_FrontFacing)) discard;
    vec3 albedo = pow(tex.rgb, vec3(2.2));
    vec3 V = normalize(uCam - vWorld);
    if (dot(N, V) < 0.0) N = -N;
    vec3 uvw = (vWorld + N) / (vec3(WORLD_X, WORLD_Y, WORLD_Z));
    vec4 r = texture(uProbeR, uvw), g = texture(uProbeG, uvw), b = texture(uProbeB, uvw);
    vec3 irr = max(vec3(r.x + dot(r.yzw, N), g.x + dot(g.yzw, N), b.x + dot(b.yzw, N)), vec3(0.0));
    float sky = vLight.x;
    vec3 col = albedo * (irr + uSunCol * max(dot(N, uSunDir), 0.0) * smoothstep(0.6, 1.0, sky));
    float fres = 0.04 + 0.96 * pow(1.0 - max(dot(N, V), 0.0), 5.0);
    vec3 R = reflect(-V, N);
    vec3 refl = mix(irr, skyColor(R), smoothstep(0.3, 0.9, sky) * smoothstep(-0.1, 0.2, R.y));
    float a = max(tex.a, liquid ? 0.62 : 0.0);
    col = mix(col, refl, fres);
    a = clamp(a + fres * 0.6, 0.0, 1.0);
    oColor = vec4(airFog(col, vWorld, uCam, uDensity, uAmbient * uFogCol), a);
}
