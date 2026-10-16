#version 330 core
layout(location=0) in vec2 aPos;
layout(location=1) in vec2 aUV;
layout(location=2) in vec4 aCol;
layout(location=3) in float aMode;
uniform mat4 uProj;
out vec2 vUV; out vec4 vCol; out float vMode;
void main() { vUV = aUV; vCol = aCol; vMode = aMode; gl_Position = uProj * vec4(aPos, 0.0, 1.0); }
