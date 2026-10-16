#include "include/head.glsl"
#include "include/light_buffers.glsl"
in vec2 vUV;
layout(location=0) out vec4 oFog;      // rgb in-scattered light, a transmittance
uniform sampler2D uDepth;
uniform sampler2DArrayShadow uShadow;
uniform mat4 uInvPV;
uniform mat4 uShadowVP[4];
uniform vec4 uSplits;
uniform int uCascades, uLightCount, uSteps;
uniform vec3 uCam, uCamFwd, uSunDir, uSunCol, uFogCol, uAmbient;
uniform float uDensity, uFrame, uHeightBase, uMaxDist;
uniform sampler3D uProbeR, uProbeG, uProbeB;
// 0.9, interiors: how much of the sky's light there is in the air at a point, 0..1, from the light the probes hold there.
// The haze used to glow with the full ambient of the open sky wherever it was - in a sealed corridor, a cellar, a
// windowless hall - so the air indoors was milky by day and dark rooms were never dark. Air glows by the light that
// reaches it: in the open that is the sky's, in a lit room the room's, in a shut one almost nothing.
float airLight(vec3 p) {
    vec3 uvw = p / (vec3(WORLD_X, WORLD_Y, WORLD_Z));
    float l = dot(max(vec3(texture(uProbeR, uvw).x, texture(uProbeG, uvw).x, texture(uProbeB, uvw).x), vec3(0.0)), vec3(0.2126, 0.7152, 0.0722));
    float open = dot(uAmbient, vec3(0.2126, 0.7152, 0.0722));
    return clamp(l / max(open, 1e-4), 0.035, 1.0);
}
float ign(vec2 p) { return fract(52.9829189 * fract(dot(p, vec2(0.06711056, 0.00583715)))); }
float sunVis(vec3 P, float viewZ) {
    int c = uCascades - 1;
    for (int i = 0; i < uCascades; i++) if (viewZ < uSplits[i]) { c = i; break; }
    if (viewZ >= uSplits[uCascades - 1]) return 1.0;
    vec4 sp = uShadowVP[c] * vec4(P, 1.0);
    vec3 s = sp.xyz * 0.5 + 0.5;
    if (s.x < 0.0 || s.y < 0.0 || s.x > 1.0 || s.y > 1.0 || s.z > 1.0) return 1.0;
    return texture(uShadow, vec4(s.xy, float(c), s.z - 0.0006));
}
float density(vec3 p) {
    // thicker near the street, thinning with height; underground air is still and heavy
    float h = p.y - uHeightBase;
    return uDensity * (h < -3.0 ? 2.2 : exp(-max(h, 0.0) * 0.045));
}
void main() {
    float d = textureLod(uDepth, vUV, 0.0).r;
    vec4 c = uInvPV * vec4(vUV * 2.0 - 1.0, d * 2.0 - 1.0, 1.0);
    vec3 P = c.xyz / c.w;
    vec3 ray = P - uCam;
    float len = min(length(ray), uMaxDist);
    vec3 dir = normalize(ray);
    float stepLen = len / float(uSteps);
    float j = ign(gl_FragCoord.xy + uFrame * 5.588238);
    float mu = dot(dir, uSunDir);
    float g = 0.62;
    float phase = (1.0 - g * g) / (4.0 * PI * pow(1.0 + g * g - 2.0 * g * mu, 1.5));
    vec3 scat = vec3(0.0);
    float trans = 1.0;
    for (int i = 0; i < uSteps; i++) {
        float t = (float(i) + j) * stepLen;
        vec3 p = uCam + dir * t;
        float dens = density(p);
        if (dens <= 0.0) continue;
        float ext = dens * stepLen;
        float tr = exp(-ext);
        vec3 light = uAmbient * airLight(p) + uSunCol * phase * 6.0 * sunVis(p, dot(p - uCam, uCamFwd));
        // the nearest local lights glow in the haze (flashlight cone, fires, muzzle flashes)
        for (int k = 0; k < min(uLightCount, 4); k++) {
            vec3 ld = lights[k].posR.xyz - p;
            float ll = length(ld), r = lights[k].posR.w;
            if (ll >= r) continue;
            float x = ll / r, att = (1.0 - x * x); att = att * att / (ll * ll * 0.35 + 1.0);
            if ((int(lights[k].col.a + 0.5) & 2) != 0) att *= smoothstep(lights[k].dir.w, mix(lights[k].dir.w, 1.0, 0.45), dot(-ld / max(ll, 1e-3), lights[k].dir.xyz));
            light += lights[k].col.rgb * att * 0.35;
        }
        scat += trans * (1.0 - tr) * light * uFogCol;
        trans *= tr;
        if (trans < 0.01) break;
    }
    // (what lies beyond the march is fogged where this is applied, the same for a wall as for the sky behind it)
    oFog = vec4(scat, trans);
}
