#include "include/head.glsl"
layout(location=0) in vec4 aPos;
layout(location=1) in vec4 aTex;
layout(location=2) in vec4 aLight;
uniform mat4 uPV;
uniform vec3 uOrigin;
uniform float uTime;
out vec3 vWorld;
out vec2 vST;
out vec3 vLight;
flat out int vLayer;
flat out int vVar;
flat out int vFlags;
flat out int vNormal;
void main() {
    // shaped blocks carry a sixteenth-of-a-block offset in the spare bits (see ChunkMesher.putVertexF)
    int nb = int(aPos.w + 0.5);
    int fr = int(aLight.w * 255.0 + 0.5);
    vec3 p = aPos.xyz + vec3(float(fr & 15), float(fr >> 4), float(nb >> 3)) / 16.0 + uOrigin;
    int n = nb & 7;
    int pk = int(aTex.w + 0.5);
    vLayer = int(aTex.z + 0.5) + ((pk & 3) << 8);
    vVar = ((pk >> 2) & 3) + 1;
    vFlags = pk >> 4;
    if ((vFlags & 2) != 0 && aPos.y > 0.5) {
        p.x += sin(uTime * 1.7 + p.z * 0.7 + p.y) * 0.05;
        p.z += cos(uTime * 1.3 + p.x * 0.5) * 0.05;
    }
    if ((vFlags & 4) != 0 && n == 2) p.y -= 0.12 + sin(uTime + p.x * 0.3 + p.z * 0.2) * 0.02;
    vWorld = p;
    vST = aTex.xy;
    vNormal = n;
    vLight = aLight.xyz;
    gl_Position = uPV * vec4(p, 1.0);
}
