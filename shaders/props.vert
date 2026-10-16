#version 450 core
layout(location=0) in vec3 aPos;
layout(location=1) in vec3 aNormal;
layout(location=2) in vec4 aCol;
layout(location=3) in vec4 iPosScale;
layout(location=4) in vec4 iRot;       // turn, sky light, block light, -
uniform mat4 uPV;
uniform vec3 uCam;
uniform float uDist;
out vec4 vCol;
out vec3 vN;
out vec2 vLight;
void main() {
    float c = cos(iRot.x), s = sin(iRot.x);
    vec3 lp = aPos * iPosScale.w;
    vec3 p = vec3(lp.x * c + lp.z * s, lp.y, -lp.x * s + lp.z * c);
    vN = vec3(aNormal.x * c + aNormal.z * s, aNormal.y, -aNormal.x * s + aNormal.z * c);
    vCol = aCol;
    vLight = iRot.yz;
    vec3 w = iPosScale.xyz + p;
    // past the drawn distance the instance is collapsed to nothing (it is still in the list: lists follow patches, not metres)
    if (length(iPosScale.xz - uCam.xz) > uDist) w = iPosScale.xyz;
    gl_Position = uPV * vec4(w, 1.0);
}
