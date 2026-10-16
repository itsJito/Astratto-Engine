#include "include/head.glsl"
#include "include/world_tex.glsl"
uniform sampler2D uNoise;
uniform sampler2D uLayerMat;
uniform float uRain;
uniform float uTime;
uniform vec4 uWeather;     // 1.0: how much snow lies; how hard it is raining now
// 0.5 for ordinary geometry. For the second, small draw of the see-through geometry it is GLASS_SOLID: only the
// texels of a pane that are not glass at all - the casement round it - are written here, with depth, as the solid
// things they are (and no liquid: water has no solid part).
uniform float uAlphaCut;
layout(location=0) out vec4 oAlbedo;
layout(location=1) out vec4 oNormal;
layout(location=2) out vec4 oMat;
void main() {
    vec3 bp;
    vec4 tex = atlasTexel(bp);
    if (uAlphaCut > 0.6) { if ((vFlags & 4) != 0 || paneAlpha() < uAlphaCut) discard; }
    else if (tex.a < uAlphaCut) discard;
    vec3 N = NORMALS[vNormal];
    vec3 col = tex.rgb;
    bool emissive = (vFlags & 1) != 0;
    vec4 lm = texelFetch(uLayerMat, ivec2(vLayer, 0), 0);
    float rough = lm.r, metal = lm.g;
    float sky = vLight.x, blk = vLight.y, ao = vLight.z;
    // material wear: per-block tone jitter, grime blotches, rain streaks, street-level dirt band
    col *= 0.92 + 0.16 * hash3(bp + vec3(13.1, 7.7, 3.3));
    if (!emissive) {
        vec2 np = (abs(N.y) > 0.5) ? vWorld.xz : (abs(N.x) > 0.5 ? vWorld.zy : vWorld.xy);
        vec4 nz = texture(uNoise, np / 40.0);
        float grime = smoothstep(0.52, 0.78, nz.r) * 0.28;
        if (abs(N.y) < 0.5) {
            grime += smoothstep(0.55, 0.85, texture(uNoise, vec2(np.x / 12.0, np.y / 80.0)).g) * 0.20;
            float gy = vWorld.y - GROUND_Y;
            grime += (1.0 - smoothstep(0.0, 1.6, gy)) * step(-0.5, gy) * 0.22;
        } else if (N.y > 0.5) {
            grime += smoothstep(0.6, 0.9, texture(uNoise, np / 9.0).b) * 0.15;
        }
        col *= 1.0 - grime;
        col = mix(col, col * vec3(0.85, 0.95, 0.75), smoothstep(0.7, 0.95, nz.a) * 0.5);
        rough = min(1.0, rough + grime * 0.5);
        // rain: open-sky tops darken and turn glossy, puddles gather in the low-frequency noise
        if (uRain > 0.0 && N.y > 0.5 && sky > 0.8) {
            float puddle = smoothstep(0.42, 0.62, texture(uNoise, vWorld.xz / 23.0).r);
            float wet = uRain * mix(0.45, 1.0, puddle);
            rough = mix(rough, 0.06, wet);
            col *= 1.0 - 0.35 * wet;
            // 1.0: while it is raining the puddles are struck: rings open on them (two layers of cells, out of step)
            if (uWeather.y > 0.02 && puddle > 0.25) {
                vec2 rn = vec2(0.0);
                for (int k = 0; k < 2; k++) {
                    vec2 rp = vWorld.xz * (k == 0 ? 1.7 : 2.6) + float(k) * 7.3;
                    vec2 cell = floor(rp), f = fract(rp) - 0.5;
                    float h = hash3(vec3(cell, float(k) * 3.0 + 1.0));
                    float t = fract(uTime * (0.9 + h * 0.6) + h * 7.0), d = length(f), ring = t * 0.46;
                    rn += f / max(d, 1e-3) * sin((d - ring) * 44.0) * exp(-abs(d - ring) * 22.0) * (1.0 - t);
                }
                N = normalize(vec3(rn.x * 0.55 * uWeather.y * puddle, 1.0, rn.y * 0.55 * uWeather.y * puddle));
            }
        }
        // 1.0: snow settles on whatever lies open to the sky, in drifts that close up as more falls
        if (uWeather.x > 0.0 && N.y > 0.5 && sky > 0.8) {
            // three scales of noise: where drifts lie, how ragged their edges are, and the grain of the surface itself
            float sn = texture(uNoise, vWorld.xz / 23.0).g * 0.55 + texture(uNoise, vWorld.xz / 4.3).r * 0.30 + texture(uNoise, vWorld.xz / 0.9).b * 0.15;
            float cover = smoothstep(1.0 - uWeather.x * 1.05, 1.16 - uWeather.x * 0.72, sn + 0.10);
            float grain = texture(uNoise, vWorld.xz / 1.7).a;
            vec3 snowCol = mix(vec3(0.70, 0.74, 0.82), vec3(0.90, 0.91, 0.93), smoothstep(0.25, 0.8, grain + sn * 0.5));
            col = mix(col, snowCol, cover * (0.80 + 0.14 * grain));
            rough = mix(rough, 0.72, cover);
        }
    }
    oAlbedo = vec4(col, emissive ? lm.b : 0.0);
    oNormal = vec4(N * 0.5 + 0.5, 0.0);
    oMat = vec4(rough, metal, ao, (floor(sky * 15.0 + 0.5) * 16.0 + floor(blk * 15.0 + 0.5)) / 255.0);
}
