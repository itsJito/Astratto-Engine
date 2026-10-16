#version 450 core
layout(location=0) in vec3 aPos;
layout(location=1) in vec3 aNormal;
layout(location=2) in vec4 aColSway;
layout(location=3) in vec4 iPosScale;
layout(location=4) in vec4 iRot;       // turn, tint, phase, flex
layout(location=5) in vec4 iLight;     // sky light, block light, how much snow settles on it, -
uniform mat4 uPV;
uniform vec4 uWind;                    // x, z in m/s; time; gust
uniform vec3 uCam;
uniform vec2 uFade;
uniform vec4 uPush[8];                 // x, z, radius, y of whatever is wading through
uniform int uPushN;
uniform float uSnow;
out vec3 vCol;
out vec3 vN;
out vec2 vLight;
void main() {
    float c = cos(iRot.x), s = sin(iRot.x);
    vec3 lp = aPos * iPosScale.w;
    vec3 p = vec3(lp.x * c + lp.z * s, lp.y, -lp.x * s + lp.z * c);
    vec3 n = vec3(aNormal.x * c + aNormal.z * s, aNormal.y, -aNormal.x * s + aNormal.z * c);
    vec3 root = iPosScale.xyz;
    // at the edge of the drawn distance a plant sinks into the ground rather than appearing out of nothing
    p *= 1.0 - smoothstep(uFade.x, uFade.y, length(root.xz - uCam.xz));
    float sway = aColSway.a * iPosScale.w * iRot.w;
    // the wind: a steady lean, and two waves that cross the field so that it moves in swathes, not in step
    float strength = length(uWind.xy);
    vec2 dir = strength > 1e-3 ? uWind.xy / strength : vec2(1.0, 0.0);
    float ph = iRot.z + dot(root.xz, dir) * 0.35;
    float wave = sin(uWind.z * 1.9 - ph) * 0.6 + sin(uWind.z * 4.3 - ph * 1.7 + root.x) * 0.25 + sin(uWind.z * 8.1 + iRot.z * 5.0 + p.y * 5.0) * 0.15;
    vec2 off = dir * sway * (strength * 0.020 * (1.0 + 0.5 * uWind.w) + wave * (0.012 + strength * 0.010));
    // anything wading through bends what it touches over, away from itself. (1.1: the whole plant leans one way, from
    // its root, by how high up the stem a point is. It used to be worked out a vertex at a time from where each
    // vertex stood - so a plant with a boot in the middle of it was pushed outward on every side at once: it grew.)
    for (int i = 0; i < uPushN; i++) {
        vec2 d = root.xz - uPush[i].xy;
        float l = length(d), r = uPush[i].z;
        if (l < r && abs(root.y - uPush[i].w) < 1.6) {
            vec2 away = l > 0.03 ? d / l : vec2(cos(iRot.z), sin(iRot.z));
            float k = 1.0 - l / r;
            off += away * (k * k * (3.0 - 2.0 * k)) * min(max(p.y, 0.0) * 0.85, 0.42) * step(0.01, aColSway.a);
        }
    }
    p.xz += off;
    p.y -= min(p.y * 0.7, dot(off, off) / max(p.y * 1.4, 0.10));
    vCol = aColSway.rgb * mix(vec3(0.82, 0.86, 0.80), vec3(1.14, 1.08, 0.86), iRot.y);
    float snowOn = uSnow * iLight.z * smoothstep(0.1, 0.9, n.y * 0.6 + 0.4);
    vCol = mix(vCol, vec3(0.84, 0.86, 0.90), snowOn);
    // lit as a soft mass, not as a heap of little cubes: the normal leans toward the sky
    vN = normalize(mix(n, vec3(0.0, 1.0, 0.0), 0.5));
    vLight = iLight.xy;
    gl_Position = uPV * vec4(root + p, 1.0);
}
