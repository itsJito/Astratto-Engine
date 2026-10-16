#version 450 core
layout(location=0) in vec3 aPos;
layout(location=1) in vec3 aUVL;
layout(location=2) in vec4 aTint;
uniform mat4 uPV;
uniform vec3 uCam;
uniform vec2 uFade;
out vec3 vUVL;
out vec4 vTint;
out vec3 vWorld;
void main() {
    vUVL = aUVL;
    vWorld = aPos;
    vTint = vec4(aTint.rgb, aTint.a * (1.0 - smoothstep(uFade.x, uFade.y, length(aPos - uCam))));
    gl_Position = uPV * vec4(aPos, 1.0);
}
