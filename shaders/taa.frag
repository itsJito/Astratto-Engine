#include "include/head.glsl"
in vec2 vUV;
layout(location=0) out vec4 oColor;
uniform sampler2D uColor, uHistory, uDepth, uNormal;
uniform mat4 uInvPV, uPrevPV;
uniform vec2 uScreen;
uniform float uBlend;
vec3 tm(vec3 c) { return c / (1.0 + max(c.r, max(c.g, c.b))); }
vec3 itm(vec3 c) { return c / max(1e-4, 1.0 - max(c.r, max(c.g, c.b))); }
void main() {
    vec3 cur = tm(texture(uColor, vUV).rgb);
    float d = texture(uDepth, vUV).r;
    // the viewmodel moves with the camera: it is never reprojected
    bool hands = int(texture(uNormal, vUV).a * 3.0 + 0.5) == 1 && d < 0.05;
    vec2 puv = vUV;
    if (!hands) {
        vec4 w = uInvPV * vec4(vUV * 2.0 - 1.0, d * 2.0 - 1.0, 1.0);
        vec4 pc = uPrevPV * vec4(w.xyz / w.w, 1.0);
        puv = pc.xy / pc.w * 0.5 + 0.5;
        if (pc.w <= 0.0) puv = vec2(-1.0);
    }
    vec3 mn = cur, mx = cur, avg = cur;
    vec2 px = 1.0 / uScreen;
    for (int y = -1; y <= 1; y++) for (int x = -1; x <= 1; x++) {
        if (x == 0 && y == 0) continue;
        vec3 s = tm(texture(uColor, vUV + vec2(x, y) * px).rgb);
        mn = min(mn, s); mx = max(mx, s); avg += s;
    }
    avg /= 9.0;
    if (puv.x < 0.0 || puv.y < 0.0 || puv.x > 1.0 || puv.y > 1.0) { oColor = vec4(itm(cur), 1.0); return; }
    vec3 hist = clamp(tm(texture(uHistory, puv).rgb), mn, mx);
    float speed = length((puv - vUV) * uScreen);
    float k = mix(uBlend, 0.35, clamp(speed * 0.05, 0.0, 1.0));
    oColor = vec4(itm(mix(hist, cur, k)), 1.0);
}
