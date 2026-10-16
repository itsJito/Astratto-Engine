#version 450 core
in vec3 vCol;
in vec3 vN;
in vec2 vLight;
layout(location=0) out vec4 oAlbedo;
layout(location=1) out vec4 oNormal;
layout(location=2) out vec4 oMat;
void main() {
    oAlbedo = vec4(vCol, 0.0);
    oNormal = vec4(normalize(vN) * 0.5 + 0.5, 0.0);
    oMat = vec4(0.92, 0.0, 1.0, (floor(vLight.x * 15.0 + 0.5) * 16.0 + floor(vLight.y * 15.0 + 0.5)) / 255.0);
}
