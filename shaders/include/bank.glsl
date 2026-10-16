uniform vec3 uWallCol, uFogOrigin;
uniform vec4 uWall;
float bankSkyK(vec3 dir) { return mix(uWall.z, uWall.w, smoothstep(0.02, 0.55, dir.y)); }
vec3 bankSky(vec3 dir) { return mix(skyColor(dir), uWallCol, bankSkyK(dir)); }
float bankAt(vec3 P) { return smoothstep(uWall.x, uWall.y, length(P - uFogOrigin)); }
vec3 bankApply(vec3 col, vec3 P, vec3 cam) { float k = bankAt(P); return k <= 0.0 ? col : mix(col, bankSky(normalize(P - cam)), k); }
