#include "include/head.glsl"
layout(location=0) in vec3 aPos;
layout(location=1) in vec4 aCol;
layout(location=2) in vec3 aNormal;
uniform mat4 uPV, uModel;
out vec4 vCol;
out vec3 vWorld;
out vec3 vN;
void main() {
    vec4 w = uModel * vec4(aPos, 1.0);
    vWorld = w.xyz;
    vCol = aCol;
    // 1.0: normals go through the normal matrix (the inverse-transpose of the model matrix), not the model matrix: a
    // limb that is stretched, a prop that is squashed or a mirrored part would otherwise be lit as if it were not.
    // The cofactor matrix is that inverse-transpose times the determinant - no division, so a flattened model cannot
    // produce NaNs - and the determinant's sign puts a mirrored model's normals back the right way round.
    mat3 m = mat3(uModel);
    mat3 cof = mat3(cross(m[1], m[2]), cross(m[2], m[0]), cross(m[0], m[1]));
    vN = cof * aNormal * (dot(m[0], cof[0]) < 0.0 ? -1.0 : 1.0);
    gl_Position = uPV * w;
}
