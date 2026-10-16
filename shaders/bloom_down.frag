#include "include/head.glsl"
in vec2 vUV;
layout(location=0) out vec4 oColor;
uniform sampler2D uTex, uExposure;
uniform vec2 uTexel;
uniform float uThreshold;
// 1.0: the threshold is applied to what the eye will see - the frame times the exposure - not to raw radiance. It
// used to be raw, so in a dark room, where the exposure is high, anything a little brighter than the walls bloomed.
// A soft knee below the threshold keeps the glow from switching on and off as a light flickers across it.
vec3 pre(vec3 c) {
    if (uThreshold <= 0.0) return c;
    c *= clamp(texelFetch(uExposure, ivec2(0), 0).r, 0.3, 8.0);
    float l = max(c.r, max(c.g, c.b)), knee = uThreshold * 0.5;
    float soft = clamp(l - uThreshold + knee, 0.0, 2.0 * knee);
    soft = soft * soft / (4.0 * knee + 1e-4);
    return min(c * max(soft, l - uThreshold) / max(l, 1e-4), vec3(6.0));
}
void main() {
    // 13-tap downsample: stable under motion, no pulsing from single hot pixels
    vec3 a = pre(texture(uTex, vUV + uTexel * vec2(-2, 2)).rgb), b = pre(texture(uTex, vUV + uTexel * vec2(0, 2)).rgb), c = pre(texture(uTex, vUV + uTexel * vec2(2, 2)).rgb);
    vec3 d = pre(texture(uTex, vUV + uTexel * vec2(-2, 0)).rgb), e = pre(texture(uTex, vUV).rgb), f = pre(texture(uTex, vUV + uTexel * vec2(2, 0)).rgb);
    vec3 g = pre(texture(uTex, vUV + uTexel * vec2(-2, -2)).rgb), h = pre(texture(uTex, vUV + uTexel * vec2(0, -2)).rgb), i = pre(texture(uTex, vUV + uTexel * vec2(2, -2)).rgb);
    vec3 j = pre(texture(uTex, vUV + uTexel * vec2(-1, 1)).rgb), k = pre(texture(uTex, vUV + uTexel * vec2(1, 1)).rgb);
    vec3 l = pre(texture(uTex, vUV + uTexel * vec2(-1, -1)).rgb), m = pre(texture(uTex, vUV + uTexel * vec2(1, -1)).rgb);
    vec3 col = e * 0.125 + (a + c + g + i) * 0.03125 + (b + d + f + h) * 0.0625 + (j + k + l + m) * 0.125;
    oColor = vec4(min(col, vec3(64.0)), 1.0);
}
