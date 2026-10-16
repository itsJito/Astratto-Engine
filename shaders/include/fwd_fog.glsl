uniform sampler2D uFogTex;
uniform vec2 uFogScreen;
uniform int uUseFog;
uniform float uFogMax, uFogBase;
vec3 airFog(vec3 col, vec3 P, vec3 cam, float density, vec3 air) {
    float L = length(P - cam);
    if (uUseFog == 0) return bankApply(mix(col, air, 1.0 - exp(-density * L * 0.7)), P, cam);
    float rest = L - uFogMax;
    if (rest > 0.0) {
        float h = P.y - uFogBase;
        float tr = exp(-density * (h < -3.0 ? 2.2 : exp(-max(h, 0.0) * 0.045)) * rest);
        col = col * tr + (1.0 - tr) * air;
    }
    col = bankApply(col, P, cam);
    vec4 fog = texture(uFogTex, gl_FragCoord.xy / uFogScreen);
    return col * fog.a + fog.rgb;
}
