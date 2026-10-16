#version 450 core
in vec3 vUVL;
in vec4 vTint;
in vec3 vWorld;
uniform sampler2DArray uTex, uAtlas;
uniform float uSnow;
layout(location=0) out vec4 oAlbedo;
void main() {
    // a negative layer is a lining: a face of the world's own atlas, laid over the wall whole
    vec4 t = vUVL.z < -0.5 ? vec4(texture(uAtlas, vec3(vUVL.xy, -vUVL.z - 1.0)).rgb, 1.0) : texture(uTex, vUVL);
    float a = t.a * vTint.a;
    // what lies on the ground is buried when it snows (a mark on a wall is not)
    if (uSnow > 0.0 && abs(normalize(cross(dFdx(vWorld), dFdy(vWorld))).y) > 0.7) a *= 1.0 - smoothstep(0.15, 0.6, uSnow);
    if (a < 0.02) discard;
    oAlbedo = vec4(t.rgb * vTint.rgb, a);
}
