#version 330 core
layout(location=0) in vec3 aPos;
layout(location=1) in vec4 aCol;
layout(location=2) in vec3 aNormal;
uniform mat4 uPV, uModel;
out vec4 vCol; out vec3 vWorld;
void main() {
    vec4 w = uModel * vec4(aPos, 1.0);
    vWorld = w.xyz;
    // baked face shading by the model-space face direction: top brightest, underside darkest
    float shade = aNormal.y > 0.5 ? 1.0 : aNormal.y < -0.5 ? 0.55 : abs(aNormal.x) > 0.5 ? 0.80 : aNormal.z > 0.5 ? 0.90 : 0.70;
    vCol = vec4(aCol.rgb * shade, aCol.a);
    gl_Position = uPV * w;
}
