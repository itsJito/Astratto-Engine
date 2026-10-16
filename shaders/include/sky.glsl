uniform vec3 uSkyZenith, uSkyHorizon, uSunDir, uSunCol, uMoonDir;
uniform float uDay, uRain, uTime, uLightning;
float h21(vec2 p) { return fract(sin(dot(p, vec2(12.9898, 78.233))) * 43758.5453); }
float n2(vec2 p) { vec2 i = floor(p), f = fract(p); f = f * f * (3.0 - 2.0 * f);
    return mix(mix(h21(i), h21(i + vec2(1, 0)), f.x), mix(h21(i + vec2(0, 1)), h21(i + vec2(1, 1)), f.x), f.y); }
vec3 skyBase(vec3 d) { return mix(uSkyHorizon, uSkyZenith, smoothstep(-0.02, 0.45, d.y)); }
/**
 * 1.0, atmospheric scattering: the light of the sun (or moon) scattered once on its way to the eye, laid over the
 * base gradient (which stands for everything scattered more than once). Rayleigh scattering by the air - blue, even
 * in every direction - and Mie scattering by haze - white, thrown forward, the glow round the sun. Both are
 * integrated analytically along the view ray through air whose thickness grows toward the horizon, and the sun's
 * own light is thinned by the air it crossed first, which is what reddens it when it is low.
 */
vec3 skyScatter(vec3 d) {
    const vec3 bR = vec3(0.0128, 0.0297, 0.0728);
    float bM = 0.006 + 0.030 * uRain;
    vec3 ext = bR + vec3(bM);
    float am = 1.0 / (max(d.y, 0.0) + 0.09), amS = 1.0 / (max(uSunDir.y, 0.0) + 0.09);
    float mu = dot(d, uSunDir);
    float pR = 0.0597 * (1.0 + mu * mu);
    const float g = 0.76;
    float pM = 0.0796 * (1.0 - g * g) / pow(1.0 + g * g - 2.0 * g * mu, 1.5);
    vec3 inS = (bR * pR * 4.1 + vec3(bM) * pM * 2.2) * (1.0 - exp(-ext * am)) / ext;
    return uSunCol * exp(-ext * amS) * inS * (1.0 - uRain * 0.7);
}
vec3 skyColor(vec3 d) {
    vec3 col = skyBase(d) + skyScatter(d);
    float sd = max(dot(d, uSunDir), 0.0);
    col += uSunCol * (pow(sd, 900.0) * 6.0 + pow(sd, 14.0) * 0.12) * (1.0 - uRain * 0.8) * step(-0.05, uSunDir.y);
    float md = max(dot(d, uMoonDir), 0.0);
    col += vec3(0.8, 0.85, 0.95) * pow(md, 1500.0) * 1.2 * (1.0 - uDay);
    if (d.y > 0.0) {
        vec2 cp = d.xz / (d.y + 0.15) * 2.0 + vec2(uTime * 0.01, 0.0);
        float c = n2(cp) * 0.6 + n2(cp * 2.3) * 0.3 + n2(cp * 5.1) * 0.1;
        float cover = mix(0.55, 0.2, uRain);
        float cl = smoothstep(cover, cover + 0.3, c) * smoothstep(0.0, 0.25, d.y);
        vec3 lit = uSkyHorizon * 1.1 + uSunCol * 0.10 * max(uSunDir.y, 0.0);
        col = mix(col, lit * mix(1.0, 0.55, uRain), cl * 0.85);
        // 1.0: lightning lights the cloud from inside - unevenly, brightest where the cloud is thickest
        col += vec3(0.62, 0.68, 0.90) * uLightning * (0.9 + 3.2 * cl * c);
        if (uDay < 0.35) col += vec3(step(0.9975, h21(floor(d.xz / (d.y + 0.3) * 300.0)))) * (0.35 - uDay) * 0.9 * (1.0 - cl);
    }
    return col;
}
