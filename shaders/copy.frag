#include "include/head.glsl"
in vec2 vUV;
layout(location=0) out vec4 oColor;
uniform sampler2D uTex;
void main() { oColor = texture(uTex, vUV); }
