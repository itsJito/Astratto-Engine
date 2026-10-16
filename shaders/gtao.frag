#include "include/head.glsl"
in vec2 vUV;
layout(location=0) out float oAO;
uniform sampler2D uDepth, uNormal;
uniform mat4 uInvProj, uView;
uniform vec2 uScreen;
uniform float uFrame, uRadius, uProjScale;
vec3 viewPos(vec2 uv) { float d = textureLod(uDepth, uv, 0.0).r; vec4 c = uInvProj * vec4(uv * 2.0 - 1.0, d * 2.0 - 1.0, 1.0); return c.xyz / c.w; }
float ign(vec2 p) { return fract(52.9829189 * fract(dot(p, vec2(0.06711056, 0.00583715)))); }
void main() {
    float d = textureLod(uDepth, vUV, 0.0).r;
    if (d >= 1.0 || d < 0.03) { oAO = 1.0; return; }
    vec3 P = viewPos(vUV);
    vec3 N = normalize(mat3(uView) * (texture(uNormal, vUV).xyz * 2.0 - 1.0));
    vec3 V = normalize(-P);
    float noiseA = ign(gl_FragCoord.xy + uFrame * 5.588238), noiseB = ign(gl_FragCoord.xy * 1.7 + uFrame * 3.17);
    // world-space radius projected to the screen, capped so near surfaces do not sample half the frame
    float rUV = clamp(uRadius * uProjScale / -P.z, 4.0 / uScreen.y, 0.12);
    const int SLICES = 2, STEPS = 4;
    float vis = 0.0;
    for (int s = 0; s < SLICES; s++) {
        float phi = (float(s) + noiseA) / float(SLICES) * PI;
        vec2 om = vec2(cos(phi), sin(phi));
        vec3 dirV = vec3(om, 0.0);
        vec3 ortho = dirV - dot(dirV, V) * V;
        vec3 axis = normalize(cross(dirV, V));
        vec3 projN = N - axis * dot(N, axis);
        float pl = length(projN);
        float sgn = sign(dot(ortho, projN));
        float cosN = clamp(dot(projN, V) / max(pl, 1e-4), 0.0, 1.0);
        float n = sgn * acos(cosN);
        for (int side = 0; side < 2; side++) {
            float hc = -1.0;
            float sd = float(side) * 2.0 - 1.0;
            for (int k = 0; k < STEPS; k++) {
                float st = (float(k) + noiseB) / float(STEPS);
                vec2 uv = vUV + sd * om * vec2(1.0, uScreen.x / uScreen.y) * rUV * st * st;
                vec3 sp = viewPos(clamp(uv, vec2(0.001), vec2(0.999)));
                vec3 dv = sp - P;
                float l = length(dv);
                float c = dot(dv / max(l, 1e-4), V);
                c = mix(c, -1.0, smoothstep(uRadius * 0.6, uRadius * 1.5, l));
                hc = max(hc, c);
            }
            float h = n + clamp(sd * acos(clamp(hc, -1.0, 1.0)) - n, -PI * 0.5, PI * 0.5);
            vis += pl * (cosN + 2.0 * h * sin(n) - cos(2.0 * h - n)) * 0.25;
        }
    }
    oAO = clamp(vis / float(SLICES), 0.0, 1.0);
}
