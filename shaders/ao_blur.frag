#include "include/head.glsl"
in vec2 vUV;
layout(location=0) out float oAO;
uniform sampler2D uAO, uDepth;
uniform vec2 uScreen;      // size of the half-resolution AO
uniform float uNear, uFar;
float lin(float d) { return 2.0 * uNear * uFar / (uFar + uNear - (d * 2.0 - 1.0) * (uFar - uNear)); }
void main() {
    float zc = lin(textureLod(uDepth, vUV, 0.0).r), sum = 0.0, w = 0.0;
    for (int y = -1; y <= 1; y++) for (int x = -1; x <= 1; x++) {
        vec2 uv = vUV + vec2(x, y) / uScreen;
        float z = lin(textureLod(uDepth, uv, 0.0).r);
        float k = max(0.0, 1.0 - abs(z - zc) / (0.05 * zc + 0.06)) + 1e-4;
        sum += textureLod(uAO, uv, 0.0).r * k; w += k;
    }
    oAO = pow(sum / w, 1.35);
}
