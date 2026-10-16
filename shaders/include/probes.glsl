uniform sampler3D uProbeR, uProbeG, uProbeB, uProbeVis;
const ivec3 PD = ivec3(WORLD_X / 4, WORLD_Y / 4, WORLD_Z / 4);
const float PSP = 4.0;
/** Hardware-filtered lookup with no visibility test: cheap, for the wide specular lobe and for forward-lit things. */
vec3 probeFiltered(vec3 P, vec3 N) {
    vec3 uvw = (P + N * 1.0) / (vec3(PD) * PSP);
    vec4 r = texture(uProbeR, uvw), g = texture(uProbeG, uvw), b = texture(uProbeB, uvw);
    return max(vec3(r.x + dot(r.yzw, N), g.x + dot(g.yzw, N), b.x + dot(b.yzw, N)), vec3(r.x, g.x, b.x) * 0.12);
}
vec3 probeIrradiance(vec3 P, vec3 N, out float confidence) {
    vec3 g = (P + N * 0.7 - PSP * 0.5) / PSP;
    ivec3 b = ivec3(floor(g));
    vec3 f = g - vec3(b);
    vec3 sum = vec3(0.0);
    float wsum = 0.0;
    for (int i = 0; i < 8; i++) {
        ivec3 o = ivec3(i & 1, (i >> 1) & 1, (i >> 2) & 1);
        ivec3 pc = clamp(b + o, ivec3(0), PD - 1);
        // a probe buried in a wall is switched off: its visibility record is negative
        vec4 vis = texelFetch(uProbeVis, pc, 0);
        if (vis.x < 0.0) continue;
        vec3 pp = (vec3(pc) + 0.5) * PSP;
        vec3 tri = mix(1.0 - f, f, vec3(o));
        float w = tri.x * tri.y * tri.z + 0.002;
        vec3 dir = pp - P;
        float dist = length(dir);
        dir /= max(dist, 1e-3);
        // probes behind the surface contribute little: they see the other side of the wall
        float wn = dot(dir, N) * 0.5 + 0.5;
        w *= wn * wn + 0.03;
        // visibility: the probe knows roughly how far it can see toward this point
        float free = max(0.35, vis.x + dot(vis.yzw, -dir));
        if (dist > free + 0.6) { float r = free / dist; w *= max(0.015, r * r * r); }
        vec4 pr = texelFetch(uProbeR, pc, 0), pg = texelFetch(uProbeG, pc, 0), pb = texelFetch(uProbeB, pc, 0);
        vec3 e = vec3(pr.x + dot(pr.yzw, N), pg.x + dot(pg.yzw, N), pb.x + dot(pb.yzw, N));
        // an L1 lobe can ring negative behind a strong light: floor it at a share of the constant term
        e = max(e, vec3(pr.x, pg.x, pb.x) * 0.12);
        sum += max(e, 0.0) * w;
        wsum += w;
    }
    confidence = clamp(wsum * 6.0, 0.0, 1.0);
    return wsum > 0.0 ? sum / wsum : vec3(0.0);
}
