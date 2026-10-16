#include "include/head.glsl"
#include "include/voxel.glsl"
#include "include/sky.glsl"
#include "include/probes.glsl"
#include "include/light_buffers.glsl"
layout(std430, binding = 1) readonly buffer Tiles { uint tile[]; };
in vec2 vUV;
layout(location=0) out vec4 oColor;
uniform sampler2D uAlbedo, uNormal, uMat, uDepth, uAO, uSSR;
uniform sampler2DArrayShadow uShadow;
uniform mat4 uInvPV;
uniform mat4 uShadowVP[4];
uniform vec4 uSplits, uCascadeTexel;
uniform int uCascades, uTileSize, uShadowedLights, uUseAO, uUseSSR;
// 1.1: the bodies that stand in the way of a local light - five floats each: the feet (xyz), the radius, the height
uniform int uBodyCount;
uniform float uBodies[120];
uniform ivec2 uTiles;
uniform vec3 uCam, uCamFwd, uTint;
uniform float uShadowRes, uAmbientFloor;
uniform sampler2D uExposure;

vec3 worldPos(vec2 uv, float d) { vec4 c = uInvPV * vec4(uv * 2.0 - 1.0, d * 2.0 - 1.0, 1.0); return c.xyz / c.w; }

float cascadeShadow(int c, vec3 P, vec3 N, float ndl) {
    float texel = uCascadeTexel[c];
    vec3 Pb = P + N * texel * (1.6 + 2.0 * (1.0 - ndl));
    vec4 sp = uShadowVP[c] * vec4(Pb, 1.0);
    vec3 s = sp.xyz * 0.5 + 0.5;
    if (s.x < 0.0 || s.y < 0.0 || s.x > 1.0 || s.y > 1.0 || s.z > 1.0) return 1.0;
    float px = 1.0 / uShadowRes, sum = 0.0;
    float bias = 0.00035 * (1.0 + float(c));
    for (int y = -1; y <= 1; y++) for (int x = -1; x <= 1; x++)
        sum += texture(uShadow, vec4(s.xy + vec2(x, y) * px * 1.1, float(c), s.z - bias));
    return sum / 9.0;
}

float sunShadow(vec3 P, vec3 N, float ndl, float viewZ, float skyVis) {
    int c = 0;
    for (int i = 0; i < uCascades; i++) { c = i; if (viewZ < uSplits[i]) break; }
    float last = uSplits[uCascades - 1];
    float baked = smoothstep(0.5, 0.95, skyVis);
    if (viewZ >= last) return baked;
    float sh = cascadeShadow(c, P, N, ndl);
    // cross-fade into the next cascade (or into the baked sky term past the last one)
    float end = uSplits[c], band = end * 0.12;
    float k = smoothstep(end - band, end, viewZ);
    if (k > 0.0) sh = mix(sh, c + 1 < uCascades ? cascadeShadow(c + 1, P, N, ndl) : baked, k);
    return sh;
}

/** Visibility between a surface point and a local light, marched through the voxel volume. */
// 1.1: how much of a local light gets past the bodies between it and a surface. A local light's shadow was traced
// through the voxel world and nothing else, so a wall threw a shadow in a car's headlights and the person standing in
// front of them did not. Each body is an upright capsule; the ray from the surface to the light is tested against its
// axis, and the edge of the shadow is soft by the width of the body. A surface that is part of a body is not
// shadowed by that body.
float bodyVisibility(vec3 a, vec3 b) {
    float vis = 1.0;
    vec3 ab = b - a;
    float abl = max(dot(ab, ab), 1e-6);
    for (int i = 0; i < uBodyCount; i++) {
        vec3 foot = vec3(uBodies[i * 5], uBodies[i * 5 + 1], uBodies[i * 5 + 2]);
        float r = uBodies[i * 5 + 3], h = uBodies[i * 5 + 4];
        vec3 c0 = foot + vec3(0.0, r, 0.0), c1 = foot + vec3(0.0, max(h - r, r), 0.0);
        // the surface itself belongs to this body
        vec3 ax = c1 - c0;
        float axl = max(dot(ax, ax), 1e-6);
        float ta = clamp(dot(a - c0, ax) / axl, 0.0, 1.0);
        if (length(a - (c0 + ax * ta)) < r * 1.35) continue;
        // closest approach of the two segments
        vec3 w0 = a - c0;
        float bb = dot(ab, ax), dd = dot(ab, w0), ee = dot(ax, w0), den = abl * axl - bb * bb;
        float s = den > 1e-6 ? clamp((bb * ee - axl * dd) / den, 0.0, 1.0) : 0.0;
        float t = clamp((bb * s + ee) / axl, 0.0, 1.0);
        s = clamp((bb * t - dd) / abl, 0.0, 1.0);
        float dist = length((a + ab * s) - (c0 + ax * t));
        // softer the further the body is from what its shadow falls on
        float soft = r * (0.25 + 0.6 * clamp(s * sqrt(abl) / 6.0, 0.0, 1.0));
        vis *= smoothstep(r - soft, r + soft, dist);
        if (vis < 0.02) return 0.0;
    }
    return vis;
}

float voxelVisibility(vec3 a, vec3 b) {
    vec3 d = b - a;
    float len = length(d);
    if (len < 0.75) return 1.0;
    d /= len;
    ivec3 c = ivec3(floor(a));
    ivec3 st = ivec3(sign(d));
    vec3 inv = 1.0 / max(abs(d), vec3(1e-5));
    vec3 tm = ddaStart(a, d, c, inv);
    float t = 0.0;
    for (int i = 0; i < 22; i++) {
        if (tm.x < tm.y && tm.x < tm.z) { t = tm.x; c.x += st.x; tm.x += inv.x; }
        else if (tm.y < tm.z) { t = tm.y; c.y += st.y; tm.y += inv.y; }
        else { t = tm.z; c.z += st.z; tm.z += inv.z; }
        if (t >= len - 0.6) return 1.0;
        if (!inWorld(c)) return 1.0;
        if (opq(voxel(c))) return 0.0;
    }
    return 1.0;
}

float distGGX(float ndh, float a) { float a2 = a * a; float d = ndh * ndh * (a2 - 1.0) + 1.0; return a2 / (PI * d * d + 1e-5); }
float visSmith(float ndv, float ndl, float a) { float k = a * 0.5 + 1e-4; return 1.0 / ((ndv * (1.0 - k) + k) * (ndl * (1.0 - k) + k) * 4.0 + 1e-4); }
vec3 fresnel(vec3 f0, float c) { float f = pow(1.0 - c, 5.0); return f0 + (1.0 - f0) * f; }

vec3 shade(vec3 albedo, vec3 f0, float rough, float metal, vec3 N, vec3 V, vec3 L, vec3 Lc) {
    float ndl = max(dot(N, L), 0.0);
    if (ndl <= 0.0) return vec3(0.0);
    vec3 H = normalize(V + L);
    float ndv = max(dot(N, V), 1e-3), ndh = max(dot(N, H), 0.0), vdh = max(dot(V, H), 0.0);
    float a = max(rough * rough, 0.02);
    vec3 F = fresnel(f0, vdh);
    vec3 spec = F * (distGGX(ndh, a) * visSmith(ndv, ndl, a));
    vec3 diff = albedo * (1.0 - metal) * (1.0 - F);
    return (diff + min(spec, vec3(8.0))) * Lc * ndl;
}

void main() {
    float depth = texture(uDepth, vUV).r;
    vec3 Pfar = worldPos(vUV, 1.0);
    vec3 viewDir = normalize(Pfar - uCam);
    if (depth >= 1.0) { oColor = vec4(skyColor(viewDir), 1.0); return; }
    vec4 ga = texture(uAlbedo, vUV), gn = texture(uNormal, vUV), gm = texture(uMat, vUV);
    int flag = int(gn.a * 3.0 + 0.5);
    vec3 N = normalize(gn.xyz * 2.0 - 1.0);
    // the viewmodel is drawn with its own projection in a reserved depth band: light it where the hands are
    vec3 P = flag == 1 ? uCam + viewDir * 0.45 : worldPos(vUV, depth);
    vec3 V = -viewDir;
    vec3 albedo = pow(ga.rgb, vec3(2.2));
    float rough = gm.r, metal = gm.g, bakedAO = gm.b;
    int pk = int(gm.a * 255.0 + 0.5);
    float skyVis = float(pk >> 4) / 15.0, blk = float(pk & 15) / 15.0;
    vec3 f0 = mix(vec3(0.04), albedo, metal);
    float viewZ = dot(P - uCam, uCamFwd);

    float ao = uUseAO != 0 && flag != 1 ? texture(uAO, vUV).r : 1.0;
    float occl = min(ao, 0.45 + 0.55 * bakedAO);

    // ---- sun or moon
    vec3 col = vec3(0.0);
    float ndl = max(dot(N, uSunDir), 0.0);
    if (ndl > 0.0 && dot(uSunCol, vec3(1.0)) > 1e-4) {
        float sh = sunShadow(P, N, ndl, flag == 1 ? 0.5 : viewZ, skyVis);
        if (sh > 0.0) col += shade(albedo, f0, rough, metal, N, V, uSunDir, uSunCol) * sh;
    }

    // ---- indirect diffuse from the irradiance probes
    float conf;
    vec3 irr = probeIrradiance(P, N, conf);
    vec3 fallback = skyBase(N) * skyVis * skyVis * 0.5;
    irr = mix(fallback, irr, conf) + vec3(uAmbientFloor);
    col += albedo * (1.0 - metal) * irr * occl;

    // ---- indirect specular: screen-space reflection where it found one, probe / sky lobe elsewhere
    vec3 R = reflect(-V, N);
    float ndv = max(dot(N, V), 1e-3);
    vec3 Fenv = fresnel(f0, ndv) * (1.0 - rough * 0.85);
    vec3 envSpec = mix(probeFiltered(P, R), skyBase(R), clamp(skyVis * skyVis * (1.0 - rough), 0.0, 1.0) * smoothstep(-0.1, 0.3, R.y));
    if (uUseSSR != 0 && flag != 1) { vec4 ssr = texture(uSSR, vUV); envSpec = mix(envSpec, ssr.rgb, ssr.a); }
    col += envSpec * Fenv * mix(occl, 1.0, 0.3);

    // ---- local lights of this tile
    ivec2 t = min(ivec2(gl_FragCoord.xy) / uTileSize, uTiles - 1);
    uint base = uint(t.y * uTiles.x + t.x) * 32u;
    uint n = min(tile[base], 31u);
    for (uint i = 0u; i < n; i++) {
        uint li = tile[base + 1u + i];
        Light l = lights[li];
        vec3 d = l.posR.xyz - P;
        float dist = length(d), r = l.posR.w;
        if (dist >= r) continue;
        vec3 L = d / max(dist, 1e-3);
        float x = dist / r;
        float att = (1.0 - x * x * x * x); att = att * att / (dist * dist * 0.35 + 1.0);
        int lf = int(l.col.a + 0.5);
        if ((lf & 2) != 0) att *= smoothstep(l.dir.w, mix(l.dir.w, 1.0, 0.45), dot(-L, l.dir.xyz));
        if (att < 1e-3) continue;
        if ((lf & 1) != 0 && int(li) < uShadowedLights) { att *= voxelVisibility(P + N * 0.08, l.posR.xyz); if (uBodyCount > 0 && att > 1e-3) att *= bodyVisibility(P + N * 0.05, l.posR.xyz); }
        else if ((lf & 4) != 0 && flag != 1) att *= smoothstep(0.0, 0.3, blk);   // unshadowed lamps never reach where their baked light did not
        if (att < 1e-3) continue;
        col += shade(albedo, f0, rough, metal, N, V, L, l.col.rgb * att);
    }

    // ---- 1.0: a lightning flash falls on everything the sky can see
    col += albedo * (1.0 - metal) * vec3(0.75, 0.80, 1.0) * uLightning * 5.5 * skyVis * skyVis * (0.35 + 0.65 * max(N.y, 0.0)) * occl;

    // ---- emission. 1.0: a lit surface is as bright on the screen in a dark room as in daylight - it used to be a
    // fixed radiance, which the eye's adaptation multiplied sixfold indoors: every lamp, screen and candle burnt out
    // to white and bloomed across the room. What lights the room is the lamp's light, not the brightness of its glass.
    float expo = clamp(texelFetch(uExposure, ivec2(0), 0).r, 0.5, 8.0);
    col += albedo * ga.a * (3.0 / expo + 1.2);
    if (flag == 2) col = albedo * (1.0 + ga.a * (2.6 / expo + 1.0));
    oColor = vec4(col * uTint, 1.0);
}
