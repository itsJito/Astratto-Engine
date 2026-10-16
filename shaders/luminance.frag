#include "include/head.glsl"
in vec2 vUV;
layout(location=0) out float oLum;
uniform sampler2D uTex;
void main() { vec3 c = texture(uTex, vUV).rgb; oLum = log(max(dot(c, vec3(0.2126, 0.7152, 0.0722)), 1e-4)); }
