#include "include/head.glsl"
in vec2 vUV;
layout(location=0) out vec4 oSSR;
uniform sampler2D uDepth, uNormal, uMat, uHistory;
uniform mat4 uProj, uInvProj, uView, uInvView, uPrevPV;
uniform vec2 uScreen;
uniform float uFrame;
vec3 viewPos(vec2 uv, float d) { vec4 c = uInvProj * vec4(uv * 2.0 - 1.0, d * 2.0 - 1.0, 1.0); return c.xyz / c.w; }
float ign(vec2 p) { return fract(52.9829189 * fract(dot(p, vec2(0.06711056, 0.00583715)))); }
void main() {
    float d = textureLod(uDepth, vUV, 0.0).r;
    float rough = texture(uMat, vUV).r;
    oSSR = vec4(0.0);
    if (d >= 1.0 || d < 0.03 || rough > 0.5) return;
    vec3 P = viewPos(vUV, d);
    vec3 N = normalize(mat3(uView) * (texture(uNormal, vUV).xyz * 2.0 - 1.0));
    vec3 V = normalize(P);
    vec3 R = reflect(V, N);
    if (R.z > -0.02 && dot(R, V) > 0.95) return;
    float jitter = ign(gl_FragCoord.xy + uFrame * 7.31);
    float maxDist = 40.0, stepLen = max(0.12, -P.z * 0.035);
    vec3 pos = P + N * 0.03 + R * stepLen * (0.5 + jitter);
    vec2 hitUV = vec2(-1.0);
    vec3 prev = pos;
    for (int i = 0; i < 40; i++) {
        if (pos.z > -0.06) break;
        vec4 c = uProj * vec4(pos, 1.0);
        vec2 uv = c.xy / c.w * 0.5 + 0.5;
        if (uv.x < 0.0 || uv.y < 0.0 || uv.x > 1.0 || uv.y > 1.0) break;
        float sd = textureLod(uDepth, uv, 0.0).r;
        float sz = viewPos(uv, sd).z;
        float diff = sz - pos.z;                       // > 0: the scene is in front of the ray point
        if (diff > 0.0 && diff < stepLen * 2.5 + 0.4 && sd < 1.0) {
            // refine between the last free point and this one
            vec3 a = prev, b = pos;
            for (int k = 0; k < 5; k++) {
                vec3 m = (a + b) * 0.5;
                vec4 mc = uProj * vec4(m, 1.0);
                vec2 muv = mc.xy / mc.w * 0.5 + 0.5;
                float mz = viewPos(muv, textureLod(uDepth, muv, 0.0).r).z;
                if (mz > m.z) b = m; else a = m;
            }
            vec4 hc = uProj * vec4(b, 1.0);
            hitUV = hc.xy / hc.w * 0.5 + 0.5;
            break;
        }
        prev = pos;
        stepLen *= 1.12;
        pos += R * stepLen;
        if (distance(pos, P) > maxDist) break;
    }
    if (hitUV.x < 0.0) return;
    // the lit image is last frame's: find where the hit point was on screen then
    float hd = textureLod(uDepth, hitUV, 0.0).r;
    vec4 hw = uInvView * vec4(viewPos(hitUV, hd), 1.0);
    vec4 pc = uPrevPV * hw;
    vec2 puv = pc.xy / pc.w * 0.5 + 0.5;
    if (pc.w <= 0.0 || puv.x < 0.0 || puv.y < 0.0 || puv.x > 1.0 || puv.y > 1.0) return;
    vec2 edge = smoothstep(vec2(0.0), vec2(0.08), hitUV) * (1.0 - smoothstep(vec2(0.92), vec2(1.0), hitUV));
    float conf = edge.x * edge.y * (1.0 - smoothstep(0.25, 0.5, rough)) * smoothstep(0.0, 0.15, dot(R, -V) * -1.0 + 1.0);
    oSSR = vec4(min(textureLod(uHistory, puv, 0.0).rgb, vec3(12.0)), conf);
}
