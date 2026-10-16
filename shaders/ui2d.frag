#version 330 core
in vec2 vUV; in vec4 vCol; in float vMode;
uniform sampler2D uTex;
out vec4 frag;
void main() {
    vec4 t = texture(uTex, vUV);
    if (vMode > 0.5) frag = vec4(vCol.rgb, vCol.a * t.r);
    else frag = t * vCol;
    if (frag.a < 0.003) discard;
}
