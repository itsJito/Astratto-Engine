#version 330 core
in vec4 vCol; in vec3 vWorld;
uniform vec3 uFog, uCam; uniform float uFogDensity, uLight, uEmissive; uniform vec4 uTint;
out vec4 frag;
void main() {
    vec3 c = vCol.rgb * uTint.rgb * mix(uLight, 1.4, uEmissive);
    float d = length(vWorld - uCam);
    float f = clamp(1.0 - exp(-pow(d * uFogDensity, 1.35)), 0.0, 1.0);
    frag = vec4(mix(c, uFog, f * (1.0 - uEmissive * 0.5)), vCol.a * uTint.a);
    if (frag.a < 0.02) discard;
}
