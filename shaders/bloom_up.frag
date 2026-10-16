#include "include/head.glsl"
in vec2 vUV;
layout(location=0) out vec4 oColor;
uniform sampler2D uTex;
uniform vec2 uTexel;
void main() {
    vec3 col = texture(uTex, vUV).rgb * 4.0;
    col += (texture(uTex, vUV + uTexel * vec2(-1, 0)).rgb + texture(uTex, vUV + uTexel * vec2(1, 0)).rgb + texture(uTex, vUV + uTexel * vec2(0, -1)).rgb + texture(uTex, vUV + uTexel * vec2(0, 1)).rgb) * 2.0;
    col += texture(uTex, vUV + uTexel * vec2(-1, -1)).rgb + texture(uTex, vUV + uTexel * vec2(1, -1)).rgb + texture(uTex, vUV + uTexel * vec2(-1, 1)).rgb + texture(uTex, vUV + uTexel * vec2(1, 1)).rgb;
    oColor = vec4(col / 16.0, 1.0);
}
