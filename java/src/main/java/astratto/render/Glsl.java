package astratto.render;

/**
 * GLSL 4.50 sources of the hybrid global-illumination renderer (see {@link HybridRenderer}).
 *
 * Conventions shared by every program:
 *   - light "colour" values are radiance-per-unit-albedo (irradiance / pi): outgoing = albedo * L * N.L
 *   - probes store irradiance / pi as a constant term and a linear (L1) term per colour channel
 *   - albedo in the G-buffer is display-encoded (as painted) and linearised where it is lit
 *   - depth is the ordinary 0..1 window depth of the jittered projection; 1.0 is sky
 */
public final class Glsl {
    private Glsl() {}

    public static final String HEAD = "#version 450 core\nconst float PI = 3.14159265359;\n";

    // ------------------------------------------------------------------ world geometry (chunk columns)
    public static final String WORLD_VS = HEAD + """
        layout(location=0) in vec4 aPos;
        layout(location=1) in vec4 aTex;
        layout(location=2) in vec4 aLight;
        uniform mat4 uPV;
        uniform vec3 uOrigin;
        uniform float uTime;
        out vec3 vWorld;
        out vec2 vST;
        out vec3 vLight;
        flat out int vLayer;
        flat out int vVar;
        flat out int vFlags;
        flat out int vNormal;
        void main() {
            // shaped blocks carry a sixteenth-of-a-block offset in the spare bits (see ChunkMesher.putVertexF)
            int nb = int(aPos.w + 0.5);
            int fr = int(aLight.w * 255.0 + 0.5);
            vec3 p = aPos.xyz + vec3(float(fr & 15), float(fr >> 4), float(nb >> 3)) / 16.0 + uOrigin;
            int n = nb & 7;
            int pk = int(aTex.w + 0.5);
            vLayer = int(aTex.z + 0.5) + ((pk & 3) << 8);
            vVar = ((pk >> 2) & 3) + 1;
            vFlags = pk >> 4;
            if ((vFlags & 2) != 0 && aPos.y > 0.5) {
                p.x += sin(uTime * 1.7 + p.z * 0.7 + p.y) * 0.05;
                p.z += cos(uTime * 1.3 + p.x * 0.5) * 0.05;
            }
            if ((vFlags & 4) != 0 && n == 2) p.y -= 0.12 + sin(uTime + p.x * 0.3 + p.z * 0.2) * 0.02;
            vWorld = p;
            vST = aTex.xy;
            vNormal = n;
            vLight = aLight.xyz;
            gl_Position = uPV * vec4(p, 1.0);
        }
        """;

    /** Atlas lookup shared by every world fragment shader: the texel, and the block-space hash that picks a variant. */
    /** Texels of a see-through block at or above this opacity are not glass: they are the frame, and are drawn solid. */
    public static final float GLASS_SOLID = 0.92f;

    public static final String WORLD_TEX = """
        const float GLASS_SOLID = 0.92;
        in vec3 vWorld;
        in vec2 vST;
        in vec3 vLight;
        flat in int vLayer;
        flat in int vVar;
        flat in int vFlags;
        flat in int vNormal;
        uniform sampler2DArray uAtlas;
        const vec3 NORMALS[7] = vec3[](vec3(1,0,0), vec3(-1,0,0), vec3(0,1,0), vec3(0,-1,0), vec3(0,0,1), vec3(0,0,-1), vec3(0,1,0));
        float hash3(vec3 p) { p = fract(p * 0.3183099 + vec3(0.71, 0.113, 0.419)); p *= 17.0; return fract(p.x * p.y * p.z * (p.x + p.y + p.z)); }
        vec3 gAtlasUVW;
        vec4 atlasTexel(out vec3 bp) {
            vec3 N = NORMALS[vNormal];
            vec2 uv;
            if (vNormal == 2 || vNormal == 3) uv = vST;
            else { float sg = (vNormal == 0 || vNormal == 5) ? -1.0 : 1.0; uv = vec2(vST.x * sg, -vST.y); }
            bp = floor(vWorld - N * 0.02);
            int layer = vLayer + min(int(hash3(bp) * float(vVar)), vVar - 1);
            gAtlasUVW = vec3(uv, float(layer));
            return texture(uAtlas, gAtlasUVW);
        }
        // 1.1: which texels of a pane are casement and which are glass is read from the texture as it was painted (level 0),
        // never from a mip. At a distance the mips average the two: the casement's alpha fell under GLASS_SOLID and it
        // dropped out of the G-buffer - the frame vanished, the pane "ate" the wall it sat in - and at the distances where
        // the averaged alpha sat near the threshold the two passes disagreed from frame to frame, which was the flicker.
        float paneAlpha() { return textureLod(uAtlas, gAtlasUVW, 0.0).a; }
        """;

    public static final String WORLD_GBUF_FS = HEAD + WORLD_TEX + """
        uniform sampler2D uNoise;
        uniform sampler2D uLayerMat;
        uniform float uRain;
        uniform float uTime;
        uniform vec4 uWeather;     // 1.0: how much snow lies; how hard it is raining now
        // 0.5 for ordinary geometry. For the second, small draw of the see-through geometry it is GLASS_SOLID: only the
        // texels of a pane that are not glass at all - the casement round it - are written here, with depth, as the solid
        // things they are (and no liquid: water has no solid part).
        uniform float uAlphaCut;
        layout(location=0) out vec4 oAlbedo;
        layout(location=1) out vec4 oNormal;
        layout(location=2) out vec4 oMat;
        void main() {
            vec3 bp;
            vec4 tex = atlasTexel(bp);
            if (uAlphaCut > 0.6) { if ((vFlags & 4) != 0 || paneAlpha() < uAlphaCut) discard; }
            else if (tex.a < uAlphaCut) discard;
            vec3 N = NORMALS[vNormal];
            vec3 col = tex.rgb;
            bool emissive = (vFlags & 1) != 0;
            vec4 lm = texelFetch(uLayerMat, ivec2(vLayer, 0), 0);
            float rough = lm.r, metal = lm.g;
            float sky = vLight.x, blk = vLight.y, ao = vLight.z;
            // material wear: per-block tone jitter, grime blotches, rain streaks, street-level dirt band
            col *= 0.92 + 0.16 * hash3(bp + vec3(13.1, 7.7, 3.3));
            if (!emissive) {
                vec2 np = (abs(N.y) > 0.5) ? vWorld.xz : (abs(N.x) > 0.5 ? vWorld.zy : vWorld.xy);
                vec4 nz = texture(uNoise, np / 40.0);
                float grime = smoothstep(0.52, 0.78, nz.r) * 0.28;
                if (abs(N.y) < 0.5) {
                    grime += smoothstep(0.55, 0.85, texture(uNoise, vec2(np.x / 12.0, np.y / 80.0)).g) * 0.20;
                    float gy = vWorld.y - 24.0;
                    grime += (1.0 - smoothstep(0.0, 1.6, gy)) * step(-0.5, gy) * 0.22;
                } else if (N.y > 0.5) {
                    grime += smoothstep(0.6, 0.9, texture(uNoise, np / 9.0).b) * 0.15;
                }
                col *= 1.0 - grime;
                col = mix(col, col * vec3(0.85, 0.95, 0.75), smoothstep(0.7, 0.95, nz.a) * 0.5);
                rough = min(1.0, rough + grime * 0.5);
                // rain: open-sky tops darken and turn glossy, puddles gather in the low-frequency noise
                if (uRain > 0.0 && N.y > 0.5 && sky > 0.8) {
                    float puddle = smoothstep(0.42, 0.62, texture(uNoise, vWorld.xz / 23.0).r);
                    float wet = uRain * mix(0.45, 1.0, puddle);
                    rough = mix(rough, 0.06, wet);
                    col *= 1.0 - 0.35 * wet;
                    // 1.0: while it is raining the puddles are struck: rings open on them (two layers of cells, out of step)
                    if (uWeather.y > 0.02 && puddle > 0.25) {
                        vec2 rn = vec2(0.0);
                        for (int k = 0; k < 2; k++) {
                            vec2 rp = vWorld.xz * (k == 0 ? 1.7 : 2.6) + float(k) * 7.3;
                            vec2 cell = floor(rp), f = fract(rp) - 0.5;
                            float h = hash3(vec3(cell, float(k) * 3.0 + 1.0));
                            float t = fract(uTime * (0.9 + h * 0.6) + h * 7.0), d = length(f), ring = t * 0.46;
                            rn += f / max(d, 1e-3) * sin((d - ring) * 44.0) * exp(-abs(d - ring) * 22.0) * (1.0 - t);
                        }
                        N = normalize(vec3(rn.x * 0.55 * uWeather.y * puddle, 1.0, rn.y * 0.55 * uWeather.y * puddle));
                    }
                }
                // 1.0: snow settles on whatever lies open to the sky, in drifts that close up as more falls
                if (uWeather.x > 0.0 && N.y > 0.5 && sky > 0.8) {
                    // three scales of noise: where drifts lie, how ragged their edges are, and the grain of the surface itself
                    float sn = texture(uNoise, vWorld.xz / 23.0).g * 0.55 + texture(uNoise, vWorld.xz / 4.3).r * 0.30 + texture(uNoise, vWorld.xz / 0.9).b * 0.15;
                    float cover = smoothstep(1.0 - uWeather.x * 1.05, 1.16 - uWeather.x * 0.72, sn + 0.10);
                    float grain = texture(uNoise, vWorld.xz / 1.7).a;
                    vec3 snowCol = mix(vec3(0.70, 0.74, 0.82), vec3(0.90, 0.91, 0.93), smoothstep(0.25, 0.8, grain + sn * 0.5));
                    col = mix(col, snowCol, cover * (0.80 + 0.14 * grain));
                    rough = mix(rough, 0.72, cover);
                }
            }
            oAlbedo = vec4(col, emissive ? lm.b : 0.0);
            oNormal = vec4(N * 0.5 + 0.5, 0.0);
            oMat = vec4(rough, metal, ao, (floor(sky * 15.0 + 0.5) * 16.0 + floor(blk * 15.0 + 0.5)) / 255.0);
        }
        """;

    public static final String WORLD_SHADOW_FS = HEAD + WORLD_TEX + """
        void main() {
            vec3 bp;
            if (atlasTexel(bp).a < 0.5) discard;
        }
        """;

    // ------------------------------------------------------------------ voxel models (characters, items, props, vehicles)
    public static final String MODEL_VS = HEAD + """
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
        """;

    public static final String MODEL_GBUF_FS = HEAD + """
        in vec4 vCol;
        in vec3 vWorld;
        in vec3 vN;
        uniform vec4 uTint;
        uniform vec4 uMatParams;   // roughness, metallic, sky visibility, baked block light
        uniform float uEmissive, uFlag;
        layout(location=0) out vec4 oAlbedo;
        layout(location=1) out vec4 oNormal;
        layout(location=2) out vec4 oMat;
        void main() {
            vec3 n = normalize(vN);
            oAlbedo = vec4(vCol.rgb * uTint.rgb, clamp(uEmissive, 0.0, 1.0));
            oNormal = vec4(n * 0.5 + 0.5, uFlag / 3.0);
            oMat = vec4(uMatParams.x, uMatParams.y, 1.0, (floor(uMatParams.z * 15.0 + 0.5) * 16.0 + floor(uMatParams.w * 15.0 + 0.5)) / 255.0);
        }
        """;

    public static final String MODEL_SHADOW_FS = HEAD + "void main() {}\n";

    // ------------------------------------------------------------------ full-screen passes
    public static final String FS_VS = HEAD + """
        out vec2 vUV;
        void main() {
            vec2 p = vec2((gl_VertexID << 1) & 2, gl_VertexID & 2);
            vUV = p;
            gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
        }
        """;

    /** Voxel occupancy queries shared by the lighting pass and the probe tracer. The volume is stored (y, z, x). */
    public static final String VOXEL = """
        uniform usampler3D uVox;
        uniform uint uOpaque[8];
        const ivec3 WORLD = ivec3(704, 96, 704);
        bool opq(uint id) { return ((uOpaque[id >> 5u] >> (id & 31u)) & 1u) != 0u; }
        uint voxel(ivec3 c) { return texelFetch(uVox, ivec3(c.y, c.z, c.x), 0).r; }
        bool inWorld(ivec3 c) { return all(greaterThanEqual(c, ivec3(0))) && all(lessThan(c, WORLD)); }
        /** Distance along the ray to the first cell boundary on each axis (a zero component never wins). */
        vec3 ddaStart(vec3 a, vec3 d, ivec3 c, vec3 inv) {
            vec3 tm = (vec3(c) + step(vec3(0.0), d) - a) * sign(d) * inv;
            return mix(tm, vec3(1e30), lessThan(abs(d), vec3(1e-5)));
        }
        """;

    public static final String SKY = """
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
        """;

    /** Irradiance probe lookup with visibility weighting (lighting pass). */
    public static final String PROBES = """
        uniform sampler3D uProbeR, uProbeG, uProbeB, uProbeVis;
        const ivec3 PD = ivec3(176, 24, 176);
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
        """;

    /**
     * 1.0, the fog bank, shared by every pass that draws into the lit frame. Two faults of the 0.6 bank are fixed here.
         * It was measured from the camera, so it stood somewhere else in third person than in first and moved when the
         * camera swung round the player: it is measured from the player (uFogOrigin). And geometry dissolved into one flat
         * colour while the sky behind it kept part of its own, so every tall building past the bank stood out as a cut-out
         * of fog against the sky - "outlines": geometry now dissolves into exactly what the sky shows in that direction.
         */
    public static final String BANK = """
        uniform vec3 uWallCol, uFogOrigin;
        uniform vec4 uWall;
        float bankSkyK(vec3 dir) { return mix(uWall.z, uWall.w, smoothstep(0.02, 0.55, dir.y)); }
        vec3 bankSky(vec3 dir) { return mix(skyColor(dir), uWallCol, bankSkyK(dir)); }
        float bankAt(vec3 P) { return smoothstep(uWall.x, uWall.y, length(P - uFogOrigin)); }
        vec3 bankApply(vec3 col, vec3 P, vec3 cam) { float k = bankAt(P); return k <= 0.0 ? col : mix(col, bankSky(normalize(P - cam)), k); }
        """;

    public static final String LIGHT_BUFFERS = """
        struct Light { vec4 posR; vec4 col; vec4 dir; };
        layout(std430, binding = 0) readonly buffer Lights { Light lights[]; };
        """;

    public static final String LIGHTING_FS = HEAD + VOXEL + SKY + PROBES + LIGHT_BUFFERS + """
        layout(std430, binding = 1) readonly buffer Tiles { uint tile[]; };
        in vec2 vUV;
        layout(location=0) out vec4 oColor;
        uniform sampler2D uAlbedo, uNormal, uMat, uDepth, uAO, uSSR;
        uniform sampler2DArrayShadow uShadow;
        uniform mat4 uInvPV;
        uniform mat4 uShadowVP[4];
        uniform vec4 uSplits, uCascadeTexel;
        uniform int uCascades, uTileSize, uShadowedLights, uUseAO, uUseSSR;
        // 1.1: the bodies that stand in the way of a local light - five floats each: the feet (xyz), the radius, the height
        uniform int uBodyCount;
        uniform float uBodies[120];
        uniform ivec2 uTiles;
        uniform vec3 uCam, uCamFwd, uTint;
        uniform float uShadowRes, uAmbientFloor;
        uniform sampler2D uExposure;

        vec3 worldPos(vec2 uv, float d) { vec4 c = uInvPV * vec4(uv * 2.0 - 1.0, d * 2.0 - 1.0, 1.0); return c.xyz / c.w; }

        float cascadeShadow(int c, vec3 P, vec3 N, float ndl) {
            float texel = uCascadeTexel[c];
            vec3 Pb = P + N * texel * (1.6 + 2.0 * (1.0 - ndl));
            vec4 sp = uShadowVP[c] * vec4(Pb, 1.0);
            vec3 s = sp.xyz * 0.5 + 0.5;
            if (s.x < 0.0 || s.y < 0.0 || s.x > 1.0 || s.y > 1.0 || s.z > 1.0) return 1.0;
            float px = 1.0 / uShadowRes, sum = 0.0;
            float bias = 0.00035 * (1.0 + float(c));
            for (int y = -1; y <= 1; y++) for (int x = -1; x <= 1; x++)
                sum += texture(uShadow, vec4(s.xy + vec2(x, y) * px * 1.1, float(c), s.z - bias));
            return sum / 9.0;
        }

        float sunShadow(vec3 P, vec3 N, float ndl, float viewZ, float skyVis) {
            int c = 0;
            for (int i = 0; i < uCascades; i++) { c = i; if (viewZ < uSplits[i]) break; }
            float last = uSplits[uCascades - 1];
            float baked = smoothstep(0.5, 0.95, skyVis);
            if (viewZ >= last) return baked;
            float sh = cascadeShadow(c, P, N, ndl);
            // cross-fade into the next cascade (or into the baked sky term past the last one)
            float end = uSplits[c], band = end * 0.12;
            float k = smoothstep(end - band, end, viewZ);
            if (k > 0.0) sh = mix(sh, c + 1 < uCascades ? cascadeShadow(c + 1, P, N, ndl) : baked, k);
            return sh;
        }

        /** Visibility between a surface point and a local light, marched through the voxel volume. */
        // 1.1: how much of a local light gets past the bodies between it and a surface. A local light's shadow was traced
        // through the voxel world and nothing else, so a wall threw a shadow in a car's headlights and the person standing in
        // front of them did not. Each body is an upright capsule; the ray from the surface to the light is tested against its
        // axis, and the edge of the shadow is soft by the width of the body. A surface that is part of a body is not
        // shadowed by that body.
        float bodyVisibility(vec3 a, vec3 b) {
            float vis = 1.0;
            vec3 ab = b - a;
            float abl = max(dot(ab, ab), 1e-6);
            for (int i = 0; i < uBodyCount; i++) {
                vec3 foot = vec3(uBodies[i * 5], uBodies[i * 5 + 1], uBodies[i * 5 + 2]);
                float r = uBodies[i * 5 + 3], h = uBodies[i * 5 + 4];
                vec3 c0 = foot + vec3(0.0, r, 0.0), c1 = foot + vec3(0.0, max(h - r, r), 0.0);
                // the surface itself belongs to this body
                vec3 ax = c1 - c0;
                float axl = max(dot(ax, ax), 1e-6);
                float ta = clamp(dot(a - c0, ax) / axl, 0.0, 1.0);
                if (length(a - (c0 + ax * ta)) < r * 1.35) continue;
                // closest approach of the two segments
                vec3 w0 = a - c0;
                float bb = dot(ab, ax), dd = dot(ab, w0), ee = dot(ax, w0), den = abl * axl - bb * bb;
                float s = den > 1e-6 ? clamp((bb * ee - axl * dd) / den, 0.0, 1.0) : 0.0;
                float t = clamp((bb * s + ee) / axl, 0.0, 1.0);
                s = clamp((bb * t - dd) / abl, 0.0, 1.0);
                float dist = length((a + ab * s) - (c0 + ax * t));
                // softer the further the body is from what its shadow falls on
                float soft = r * (0.25 + 0.6 * clamp(s * sqrt(abl) / 6.0, 0.0, 1.0));
                vis *= smoothstep(r - soft, r + soft, dist);
                if (vis < 0.02) return 0.0;
            }
            return vis;
        }

        float voxelVisibility(vec3 a, vec3 b) {
            vec3 d = b - a;
            float len = length(d);
            if (len < 0.75) return 1.0;
            d /= len;
            ivec3 c = ivec3(floor(a));
            ivec3 st = ivec3(sign(d));
            vec3 inv = 1.0 / max(abs(d), vec3(1e-5));
            vec3 tm = ddaStart(a, d, c, inv);
            float t = 0.0;
            for (int i = 0; i < 22; i++) {
                if (tm.x < tm.y && tm.x < tm.z) { t = tm.x; c.x += st.x; tm.x += inv.x; }
                else if (tm.y < tm.z) { t = tm.y; c.y += st.y; tm.y += inv.y; }
                else { t = tm.z; c.z += st.z; tm.z += inv.z; }
                if (t >= len - 0.6) return 1.0;
                if (!inWorld(c)) return 1.0;
                if (opq(voxel(c))) return 0.0;
            }
            return 1.0;
        }

        float distGGX(float ndh, float a) { float a2 = a * a; float d = ndh * ndh * (a2 - 1.0) + 1.0; return a2 / (PI * d * d + 1e-5); }
        float visSmith(float ndv, float ndl, float a) { float k = a * 0.5 + 1e-4; return 1.0 / ((ndv * (1.0 - k) + k) * (ndl * (1.0 - k) + k) * 4.0 + 1e-4); }
        vec3 fresnel(vec3 f0, float c) { float f = pow(1.0 - c, 5.0); return f0 + (1.0 - f0) * f; }

        vec3 shade(vec3 albedo, vec3 f0, float rough, float metal, vec3 N, vec3 V, vec3 L, vec3 Lc) {
            float ndl = max(dot(N, L), 0.0);
            if (ndl <= 0.0) return vec3(0.0);
            vec3 H = normalize(V + L);
            float ndv = max(dot(N, V), 1e-3), ndh = max(dot(N, H), 0.0), vdh = max(dot(V, H), 0.0);
            float a = max(rough * rough, 0.02);
            vec3 F = fresnel(f0, vdh);
            vec3 spec = F * (distGGX(ndh, a) * visSmith(ndv, ndl, a));
            vec3 diff = albedo * (1.0 - metal) * (1.0 - F);
            return (diff + min(spec, vec3(8.0))) * Lc * ndl;
        }

        void main() {
            float depth = texture(uDepth, vUV).r;
            vec3 Pfar = worldPos(vUV, 1.0);
            vec3 viewDir = normalize(Pfar - uCam);
            if (depth >= 1.0) { oColor = vec4(skyColor(viewDir), 1.0); return; }
            vec4 ga = texture(uAlbedo, vUV), gn = texture(uNormal, vUV), gm = texture(uMat, vUV);
            int flag = int(gn.a * 3.0 + 0.5);
            vec3 N = normalize(gn.xyz * 2.0 - 1.0);
            // the viewmodel is drawn with its own projection in a reserved depth band: light it where the hands are
            vec3 P = flag == 1 ? uCam + viewDir * 0.45 : worldPos(vUV, depth);
            vec3 V = -viewDir;
            vec3 albedo = pow(ga.rgb, vec3(2.2));
            float rough = gm.r, metal = gm.g, bakedAO = gm.b;
            int pk = int(gm.a * 255.0 + 0.5);
            float skyVis = float(pk >> 4) / 15.0, blk = float(pk & 15) / 15.0;
            vec3 f0 = mix(vec3(0.04), albedo, metal);
            float viewZ = dot(P - uCam, uCamFwd);

            float ao = uUseAO != 0 && flag != 1 ? texture(uAO, vUV).r : 1.0;
            float occl = min(ao, 0.45 + 0.55 * bakedAO);

            // ---- sun or moon
            vec3 col = vec3(0.0);
            float ndl = max(dot(N, uSunDir), 0.0);
            if (ndl > 0.0 && dot(uSunCol, vec3(1.0)) > 1e-4) {
                float sh = sunShadow(P, N, ndl, flag == 1 ? 0.5 : viewZ, skyVis);
                if (sh > 0.0) col += shade(albedo, f0, rough, metal, N, V, uSunDir, uSunCol) * sh;
            }

            // ---- indirect diffuse from the irradiance probes
            float conf;
            vec3 irr = probeIrradiance(P, N, conf);
            vec3 fallback = skyBase(N) * skyVis * skyVis * 0.5;
            irr = mix(fallback, irr, conf) + vec3(uAmbientFloor);
            col += albedo * (1.0 - metal) * irr * occl;

            // ---- indirect specular: screen-space reflection where it found one, probe / sky lobe elsewhere
            vec3 R = reflect(-V, N);
            float ndv = max(dot(N, V), 1e-3);
            vec3 Fenv = fresnel(f0, ndv) * (1.0 - rough * 0.85);
            vec3 envSpec = mix(probeFiltered(P, R), skyBase(R), clamp(skyVis * skyVis * (1.0 - rough), 0.0, 1.0) * smoothstep(-0.1, 0.3, R.y));
            if (uUseSSR != 0 && flag != 1) { vec4 ssr = texture(uSSR, vUV); envSpec = mix(envSpec, ssr.rgb, ssr.a); }
            col += envSpec * Fenv * mix(occl, 1.0, 0.3);

            // ---- local lights of this tile
            ivec2 t = min(ivec2(gl_FragCoord.xy) / uTileSize, uTiles - 1);
            uint base = uint(t.y * uTiles.x + t.x) * 32u;
            uint n = min(tile[base], 31u);
            for (uint i = 0u; i < n; i++) {
                uint li = tile[base + 1u + i];
                Light l = lights[li];
                vec3 d = l.posR.xyz - P;
                float dist = length(d), r = l.posR.w;
                if (dist >= r) continue;
                vec3 L = d / max(dist, 1e-3);
                float x = dist / r;
                float att = (1.0 - x * x * x * x); att = att * att / (dist * dist * 0.35 + 1.0);
                int lf = int(l.col.a + 0.5);
                if ((lf & 2) != 0) att *= smoothstep(l.dir.w, mix(l.dir.w, 1.0, 0.45), dot(-L, l.dir.xyz));
                if (att < 1e-3) continue;
                if ((lf & 1) != 0 && int(li) < uShadowedLights) { att *= voxelVisibility(P + N * 0.08, l.posR.xyz); if (uBodyCount > 0 && att > 1e-3) att *= bodyVisibility(P + N * 0.05, l.posR.xyz); }
                else if ((lf & 4) != 0 && flag != 1) att *= smoothstep(0.0, 0.3, blk);   // unshadowed lamps never reach where their baked light did not
                if (att < 1e-3) continue;
                col += shade(albedo, f0, rough, metal, N, V, L, l.col.rgb * att);
            }

            // ---- 1.0: a lightning flash falls on everything the sky can see
            col += albedo * (1.0 - metal) * vec3(0.75, 0.80, 1.0) * uLightning * 5.5 * skyVis * skyVis * (0.35 + 0.65 * max(N.y, 0.0)) * occl;

            // ---- emission. 1.0: a lit surface is as bright on the screen in a dark room as in daylight - it used to be a
            // fixed radiance, which the eye's adaptation multiplied sixfold indoors: every lamp, screen and candle burnt out
            // to white and bloomed across the room. What lights the room is the lamp's light, not the brightness of its glass.
            float expo = clamp(texelFetch(uExposure, ivec2(0), 0).r, 0.5, 8.0);
            col += albedo * ga.a * (3.0 / expo + 1.2);
            if (flag == 2) col = albedo * (1.0 + ga.a * (2.6 / expo + 1.0));
            oColor = vec4(col * uTint, 1.0);
        }
        """;

    // ------------------------------------------------------------------ GPU light culling (compute)
    public static final String LIGHT_CULL_CS = HEAD + """
        layout(local_size_x = 8, local_size_y = 8) in;
        struct Light { vec4 posR; vec4 col; vec4 dir; };
        layout(std430, binding = 0) readonly buffer Lights { Light lights[]; };
        layout(std430, binding = 1) writeonly buffer Tiles { uint tile[]; };
        uniform sampler2D uDepth;
        uniform mat4 uInvProj, uView;
        uniform ivec2 uTiles, uScreen;
        uniform int uTileSize, uLightCount;
        vec3 viewPos(vec2 uv, float d) { vec4 c = uInvProj * vec4(uv * 2.0 - 1.0, d * 2.0 - 1.0, 1.0); return c.xyz / c.w; }
        void main() {
            ivec2 t = ivec2(gl_GlobalInvocationID.xy);
            if (t.x >= uTiles.x || t.y >= uTiles.y) return;
            uint base = uint(t.y * uTiles.x + t.x) * 32u;
            float dmin = 1.0, dmax = 0.0;
            for (int y = 0; y < 4; y++) for (int x = 0; x < 4; x++) {
                ivec2 p = min(t * uTileSize + ivec2(x, y) * uTileSize / 4 + uTileSize / 8, uScreen - 1);
                float d = texelFetch(uDepth, p, 0).r;
                if (d < 1.0) { dmin = min(dmin, d); dmax = max(dmax, d); }
            }
            if (dmax < dmin) { tile[base] = 0u; return; }
            vec2 uv0 = vec2(t * uTileSize) / vec2(uScreen), uv1 = min(vec2((t + 1) * uTileSize) / vec2(uScreen), vec2(1.0));
            vec3 mn = vec3(1e9), mx = vec3(-1e9);
            for (int i = 0; i < 8; i++) {
                vec3 p = viewPos(vec2((i & 1) == 0 ? uv0.x : uv1.x, (i & 2) == 0 ? uv0.y : uv1.y), (i & 4) == 0 ? dmin : dmax);
                mn = min(mn, p); mx = max(mx, p);
            }
            // the sixteen depth taps can miss the extremes of a tile: pad the slab
            float pad = 0.75 + (mx.z - mn.z) * 0.15;
            mn -= vec3(pad); mx += vec3(pad);
            uint n = 0u;
            for (int i = 0; i < uLightCount && n < 31u; i++) {
                vec3 lp = (uView * vec4(lights[i].posR.xyz, 1.0)).xyz;
                float r = lights[i].posR.w;
                vec3 c = clamp(lp, mn, mx);
                vec3 dd = c - lp;
                if (dot(dd, dd) <= r * r) { tile[base + 1u + n] = uint(i); n++; }
            }
            tile[base] = n;
        }
        """;

    // ------------------------------------------------------------------ irradiance probe update (compute, voxel ray traversal)
    public static final String PROBE_CS = HEAD + VOXEL + """
        layout(local_size_x = 64) in;
        layout(rgba16f, binding = 0) uniform image3D imgR;
        layout(rgba16f, binding = 1) uniform image3D imgG;
        layout(rgba16f, binding = 2) uniform image3D imgB;
        layout(rgba16f, binding = 3) uniform image3D imgVis;
        layout(rgba8, binding = 4) uniform image3D imgMeta;
        uniform sampler3D uProbeR, uProbeG, uProbeB;
        uniform sampler2D uPalette;             // row 0: albedo; row 1: emitted radiance
        uniform ivec2 uRegions[48];
        uniform float uRegionBlend[48];
        uniform int uRegionCount, uRays;
        uniform mat3 uRot;
        uniform vec3 uSunDir, uSunCol, uSkyZenith, uSkyHorizon;
        const ivec3 PD = ivec3(176, 24, 176);
        const float PSP = 4.0;
        const float MAXT = 40.0;

        vec3 skyRadiance(vec3 d) { return d.y > 0.0 ? mix(uSkyHorizon, uSkyZenith, smoothstep(0.0, 0.5, d.y)) : uSkyHorizon * mix(1.0, 0.25, smoothstep(0.0, 0.3, -d.y)); }

        /** True when nothing opaque stands between a point and the sky along the sun direction. */
        bool sunVisible(vec3 a) {
            vec3 d = uSunDir;
            ivec3 c = ivec3(floor(a));
            ivec3 st = ivec3(sign(d));
            vec3 inv = 1.0 / max(abs(d), vec3(1e-5));
            vec3 tm = ddaStart(a, d, c, inv);
            for (int i = 0; i < 48; i++) {
                if (tm.x < tm.y && tm.x < tm.z) { c.x += st.x; tm.x += inv.x; }
                else if (tm.y < tm.z) { c.y += st.y; tm.y += inv.y; }
                else { c.z += st.z; tm.z += inv.z; }
                if (c.y >= WORLD.y || !inWorld(c)) return true;
                if (opq(voxel(c))) return false;
            }
            return true;
        }

        vec3 bounce(vec3 p, vec3 n) {
            vec3 uvw = (p + n * 1.2) / (vec3(PD) * PSP);
            vec4 r = texture(uProbeR, uvw), g = texture(uProbeG, uvw), b = texture(uProbeB, uvw);
            return max(vec3(r.x + dot(r.yzw, n), g.x + dot(g.yzw, n), b.x + dot(b.yzw, n)), vec3(0.0));
        }

        /** One ray: radiance arriving at the probe from direction d, and how far the ray flew. */
        vec3 trace(vec3 o, vec3 d, out float dist) {
            ivec3 c = ivec3(floor(o));
            ivec3 st = ivec3(sign(d));
            vec3 inv = 1.0 / max(abs(d), vec3(1e-5));
            vec3 tm = ddaStart(o, d, c, inv);
            float t = 0.0;
            vec3 n = vec3(0.0), through = vec3(0.0);
            for (int i = 0; i < 64; i++) {
                if (tm.x < tm.y && tm.x < tm.z) { t = tm.x; c.x += st.x; tm.x += inv.x; n = vec3(-st.x, 0, 0); }
                else if (tm.y < tm.z) { t = tm.y; c.y += st.y; tm.y += inv.y; n = vec3(0, -st.y, 0); }
                else { t = tm.z; c.z += st.z; tm.z += inv.z; n = vec3(0, 0, -st.z); }
                if (t > MAXT || !inWorld(c)) break;
                uint id = voxel(c);
                if (id == 0u) continue;
                vec3 emit = texelFetch(uPalette, ivec2(int(id), 1), 0).rgb;
                if (!opq(id)) { through += emit * 0.5; continue; }       // lamps, screens, embers: light sources you can see past
                vec3 albedo = texelFetch(uPalette, ivec2(int(id), 0), 0).rgb;
                vec3 hp = o + d * t + n * 0.5;
                vec3 lit = bounce(hp, n);
                float ndl = dot(n, uSunDir);
                if (ndl > 0.0 && dot(uSunCol, vec3(1.0)) > 1e-4 && sunVisible(hp)) lit += uSunCol * ndl;
                dist = t;
                return albedo * lit + emit + through;
            }
            dist = MAXT;
            return skyRadiance(d) + through;
        }

        void main() {
            int job = int(gl_GlobalInvocationID.x);
            int region = job / 384, local = job % 384;
            if (region >= uRegionCount) return;
            ivec3 pc = ivec3(uRegions[region].x * 4 + (local & 3), local >> 4, uRegions[region].y * 4 + ((local >> 2) & 3));
            if (any(greaterThanEqual(pc, PD))) return;
            // ---- adaptive placement: a probe buried in a wall is moved into the nearest open cell, or switched off
            vec3 base = (vec3(pc) + 0.5) * PSP;
            const vec3 OFF[15] = vec3[](vec3(0), vec3(1.4, 0, 0), vec3(-1.4, 0, 0), vec3(0, 0, 1.4), vec3(0, 0, -1.4), vec3(0, 1.4, 0), vec3(0, -1.4, 0),
                    vec3(1.4, 0, 1.4), vec3(-1.4, 0, 1.4), vec3(1.4, 0, -1.4), vec3(-1.4, 0, -1.4), vec3(1.4, 1.4, 0), vec3(-1.4, 1.4, 0), vec3(0, 1.4, 1.4), vec3(0, 1.4, -1.4));
            vec3 off = vec3(0.0);
            bool valid = false;
            for (int i = 0; i < 15; i++) {
                ivec3 c = ivec3(floor(base + OFF[i]));
                if (inWorld(c) && !opq(voxel(c))) { off = OFF[i]; valid = true; break; }
            }
            if (!valid) {
                imageStore(imgMeta, pc, vec4(0.5, 0.5, 0.5, 0.0));
                imageStore(imgR, pc, vec4(0.0)); imageStore(imgG, pc, vec4(0.0)); imageStore(imgB, pc, vec4(0.0));
                imageStore(imgVis, pc, vec4(-1.0, 0.0, 0.0, 0.0));
                return;
            }
            vec3 o = floor(base + off) + 0.5;      // the centre of the open cell
            off = o - base;
            // ---- gather
            vec3 a0 = vec3(0.0), ax = vec3(0.0), ay = vec3(0.0), az = vec3(0.0);
            float d0 = 0.0;
            vec3 d1 = vec3(0.0);
            float fn = float(uRays);
            for (int i = 0; i < uRays; i++) {
                float z = 1.0 - (2.0 * float(i) + 1.0) / fn;
                float r = sqrt(max(0.0, 1.0 - z * z)), phi = float(i) * 2.39996323;
                vec3 d = uRot * vec3(cos(phi) * r, sin(phi) * r, z);
                float dist;
                vec3 L = min(trace(o, d, dist), vec3(24.0));
                a0 += L; ax += L * d.x; ay += L * d.y; az += L * d.z;
                d0 += dist; d1 += dist * d;
            }
            a0 /= fn; ax *= 2.0 / fn; ay *= 2.0 / fn; az *= 2.0 / fn;
            d0 /= fn; d1 *= 3.0 / fn;
            float k = uRegionBlend[region];
            vec4 nr = vec4(a0.r, ax.r, ay.r, az.r), ng = vec4(a0.g, ax.g, ay.g, az.g), nb = vec4(a0.b, ax.b, ay.b, az.b), nv = vec4(d0, d1);
            if (k < 1.0 && imageLoad(imgMeta, pc).a > 0.5) {
                nr = mix(imageLoad(imgR, pc), nr, k); ng = mix(imageLoad(imgG, pc), ng, k); nb = mix(imageLoad(imgB, pc), nb, k);
                vec4 ov = imageLoad(imgVis, pc);
                if (ov.x >= 0.0) nv = mix(ov, nv, max(k, 0.5));
            }
            imageStore(imgR, pc, nr); imageStore(imgG, pc, ng); imageStore(imgB, pc, nb);
            imageStore(imgVis, pc, nv);
            imageStore(imgMeta, pc, vec4(off / PSP + 0.5, 1.0));
        }
        """;

    // ------------------------------------------------------------------ ground-truth ambient occlusion
    public static final String GTAO_FS = HEAD + """
        in vec2 vUV;
        layout(location=0) out float oAO;
        uniform sampler2D uDepth, uNormal;
        uniform mat4 uInvProj, uView;
        uniform vec2 uScreen;
        uniform float uFrame, uRadius, uProjScale;
        vec3 viewPos(vec2 uv) { float d = textureLod(uDepth, uv, 0.0).r; vec4 c = uInvProj * vec4(uv * 2.0 - 1.0, d * 2.0 - 1.0, 1.0); return c.xyz / c.w; }
        float ign(vec2 p) { return fract(52.9829189 * fract(dot(p, vec2(0.06711056, 0.00583715)))); }
        void main() {
            float d = textureLod(uDepth, vUV, 0.0).r;
            if (d >= 1.0 || d < 0.03) { oAO = 1.0; return; }
            vec3 P = viewPos(vUV);
            vec3 N = normalize(mat3(uView) * (texture(uNormal, vUV).xyz * 2.0 - 1.0));
            vec3 V = normalize(-P);
            float noiseA = ign(gl_FragCoord.xy + uFrame * 5.588238), noiseB = ign(gl_FragCoord.xy * 1.7 + uFrame * 3.17);
            // world-space radius projected to the screen, capped so near surfaces do not sample half the frame
            float rUV = clamp(uRadius * uProjScale / -P.z, 4.0 / uScreen.y, 0.12);
            const int SLICES = 2, STEPS = 4;
            float vis = 0.0;
            for (int s = 0; s < SLICES; s++) {
                float phi = (float(s) + noiseA) / float(SLICES) * PI;
                vec2 om = vec2(cos(phi), sin(phi));
                vec3 dirV = vec3(om, 0.0);
                vec3 ortho = dirV - dot(dirV, V) * V;
                vec3 axis = normalize(cross(dirV, V));
                vec3 projN = N - axis * dot(N, axis);
                float pl = length(projN);
                float sgn = sign(dot(ortho, projN));
                float cosN = clamp(dot(projN, V) / max(pl, 1e-4), 0.0, 1.0);
                float n = sgn * acos(cosN);
                for (int side = 0; side < 2; side++) {
                    float hc = -1.0;
                    float sd = float(side) * 2.0 - 1.0;
                    for (int k = 0; k < STEPS; k++) {
                        float st = (float(k) + noiseB) / float(STEPS);
                        vec2 uv = vUV + sd * om * vec2(1.0, uScreen.x / uScreen.y) * rUV * st * st;
                        vec3 sp = viewPos(clamp(uv, vec2(0.001), vec2(0.999)));
                        vec3 dv = sp - P;
                        float l = length(dv);
                        float c = dot(dv / max(l, 1e-4), V);
                        c = mix(c, -1.0, smoothstep(uRadius * 0.6, uRadius * 1.5, l));
                        hc = max(hc, c);
                    }
                    float h = n + clamp(sd * acos(clamp(hc, -1.0, 1.0)) - n, -PI * 0.5, PI * 0.5);
                    vis += pl * (cosN + 2.0 * h * sin(n) - cos(2.0 * h - n)) * 0.25;
                }
            }
            oAO = clamp(vis / float(SLICES), 0.0, 1.0);
        }
        """;

    /** Depth-aware upsample and blur of the half-resolution AO: a 3x3 footprint of half-res taps per full-res pixel. */
    public static final String AO_BLUR_FS = HEAD + """
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
        """;

    // ------------------------------------------------------------------ screen-space reflections
    public static final String SSR_FS = HEAD + """
        in vec2 vUV;
        layout(location=0) out vec4 oSSR;
        uniform sampler2D uDepth, uNormal, uMat, uHistory;
        uniform mat4 uProj, uInvProj, uView, uInvView, uPrevPV;
        uniform vec2 uScreen;
        uniform float uFrame;
        vec3 viewPos(vec2 uv, float d) { vec4 c = uInvProj * vec4(uv * 2.0 - 1.0, d * 2.0 - 1.0, 1.0); return c.xyz / c.w; }
        float ign(vec2 p) { return fract(52.9829189 * fract(dot(p, vec2(0.06711056, 0.00583715)))); }
        void main() {
            float d = textureLod(uDepth, vUV, 0.0).r;
            float rough = texture(uMat, vUV).r;
            oSSR = vec4(0.0);
            if (d >= 1.0 || d < 0.03 || rough > 0.5) return;
            vec3 P = viewPos(vUV, d);
            vec3 N = normalize(mat3(uView) * (texture(uNormal, vUV).xyz * 2.0 - 1.0));
            vec3 V = normalize(P);
            vec3 R = reflect(V, N);
            if (R.z > -0.02 && dot(R, V) > 0.95) return;
            float jitter = ign(gl_FragCoord.xy + uFrame * 7.31);
            float maxDist = 40.0, stepLen = max(0.12, -P.z * 0.035);
            vec3 pos = P + N * 0.03 + R * stepLen * (0.5 + jitter);
            vec2 hitUV = vec2(-1.0);
            vec3 prev = pos;
            for (int i = 0; i < 40; i++) {
                if (pos.z > -0.06) break;
                vec4 c = uProj * vec4(pos, 1.0);
                vec2 uv = c.xy / c.w * 0.5 + 0.5;
                if (uv.x < 0.0 || uv.y < 0.0 || uv.x > 1.0 || uv.y > 1.0) break;
                float sd = textureLod(uDepth, uv, 0.0).r;
                float sz = viewPos(uv, sd).z;
                float diff = sz - pos.z;                       // > 0: the scene is in front of the ray point
                if (diff > 0.0 && diff < stepLen * 2.5 + 0.4 && sd < 1.0) {
                    // refine between the last free point and this one
                    vec3 a = prev, b = pos;
                    for (int k = 0; k < 5; k++) {
                        vec3 m = (a + b) * 0.5;
                        vec4 mc = uProj * vec4(m, 1.0);
                        vec2 muv = mc.xy / mc.w * 0.5 + 0.5;
                        float mz = viewPos(muv, textureLod(uDepth, muv, 0.0).r).z;
                        if (mz > m.z) b = m; else a = m;
                    }
                    vec4 hc = uProj * vec4(b, 1.0);
                    hitUV = hc.xy / hc.w * 0.5 + 0.5;
                    break;
                }
                prev = pos;
                stepLen *= 1.12;
                pos += R * stepLen;
                if (distance(pos, P) > maxDist) break;
            }
            if (hitUV.x < 0.0) return;
            // the lit image is last frame's: find where the hit point was on screen then
            float hd = textureLod(uDepth, hitUV, 0.0).r;
            vec4 hw = uInvView * vec4(viewPos(hitUV, hd), 1.0);
            vec4 pc = uPrevPV * hw;
            vec2 puv = pc.xy / pc.w * 0.5 + 0.5;
            if (pc.w <= 0.0 || puv.x < 0.0 || puv.y < 0.0 || puv.x > 1.0 || puv.y > 1.0) return;
            vec2 edge = smoothstep(vec2(0.0), vec2(0.08), hitUV) * (1.0 - smoothstep(vec2(0.92), vec2(1.0), hitUV));
            float conf = edge.x * edge.y * (1.0 - smoothstep(0.25, 0.5, rough)) * smoothstep(0.0, 0.15, dot(R, -V) * -1.0 + 1.0);
            oSSR = vec4(min(textureLod(uHistory, puv, 0.0).rgb, vec3(12.0)), conf);
        }
        """;

    // ------------------------------------------------------------------ volumetric fog and sun shafts
    public static final String VOLUMETRIC_FS = HEAD + LIGHT_BUFFERS + """
        in vec2 vUV;
        layout(location=0) out vec4 oFog;      // rgb in-scattered light, a transmittance
        uniform sampler2D uDepth;
        uniform sampler2DArrayShadow uShadow;
        uniform mat4 uInvPV;
        uniform mat4 uShadowVP[4];
        uniform vec4 uSplits;
        uniform int uCascades, uLightCount, uSteps;
        uniform vec3 uCam, uCamFwd, uSunDir, uSunCol, uFogCol, uAmbient;
        uniform float uDensity, uFrame, uHeightBase, uMaxDist;
        uniform sampler3D uProbeR, uProbeG, uProbeB;
        // 0.9, interiors: how much of the sky's light there is in the air at a point, 0..1, from the light the probes hold there.
        // The haze used to glow with the full ambient of the open sky wherever it was - in a sealed corridor, a cellar, a
        // windowless hall - so the air indoors was milky by day and dark rooms were never dark. Air glows by the light that
        // reaches it: in the open that is the sky's, in a lit room the room's, in a shut one almost nothing.
        float airLight(vec3 p) {
            vec3 uvw = p / (vec3(176.0, 24.0, 176.0) * 4.0);
            float l = dot(max(vec3(texture(uProbeR, uvw).x, texture(uProbeG, uvw).x, texture(uProbeB, uvw).x), vec3(0.0)), vec3(0.2126, 0.7152, 0.0722));
            float open = dot(uAmbient, vec3(0.2126, 0.7152, 0.0722));
            return clamp(l / max(open, 1e-4), 0.035, 1.0);
        }
        float ign(vec2 p) { return fract(52.9829189 * fract(dot(p, vec2(0.06711056, 0.00583715)))); }
        float sunVis(vec3 P, float viewZ) {
            int c = uCascades - 1;
            for (int i = 0; i < uCascades; i++) if (viewZ < uSplits[i]) { c = i; break; }
            if (viewZ >= uSplits[uCascades - 1]) return 1.0;
            vec4 sp = uShadowVP[c] * vec4(P, 1.0);
            vec3 s = sp.xyz * 0.5 + 0.5;
            if (s.x < 0.0 || s.y < 0.0 || s.x > 1.0 || s.y > 1.0 || s.z > 1.0) return 1.0;
            return texture(uShadow, vec4(s.xy, float(c), s.z - 0.0006));
        }
        float density(vec3 p) {
            // thicker near the street, thinning with height; underground air is still and heavy
            float h = p.y - uHeightBase;
            return uDensity * (h < -3.0 ? 2.2 : exp(-max(h, 0.0) * 0.045));
        }
        void main() {
            float d = textureLod(uDepth, vUV, 0.0).r;
            vec4 c = uInvPV * vec4(vUV * 2.0 - 1.0, d * 2.0 - 1.0, 1.0);
            vec3 P = c.xyz / c.w;
            vec3 ray = P - uCam;
            float len = min(length(ray), uMaxDist);
            vec3 dir = normalize(ray);
            float stepLen = len / float(uSteps);
            float j = ign(gl_FragCoord.xy + uFrame * 5.588238);
            float mu = dot(dir, uSunDir);
            float g = 0.62;
            float phase = (1.0 - g * g) / (4.0 * PI * pow(1.0 + g * g - 2.0 * g * mu, 1.5));
            vec3 scat = vec3(0.0);
            float trans = 1.0;
            for (int i = 0; i < uSteps; i++) {
                float t = (float(i) + j) * stepLen;
                vec3 p = uCam + dir * t;
                float dens = density(p);
                if (dens <= 0.0) continue;
                float ext = dens * stepLen;
                float tr = exp(-ext);
                vec3 light = uAmbient * airLight(p) + uSunCol * phase * 6.0 * sunVis(p, dot(p - uCam, uCamFwd));
                // the nearest local lights glow in the haze (flashlight cone, fires, muzzle flashes)
                for (int k = 0; k < min(uLightCount, 4); k++) {
                    vec3 ld = lights[k].posR.xyz - p;
                    float ll = length(ld), r = lights[k].posR.w;
                    if (ll >= r) continue;
                    float x = ll / r, att = (1.0 - x * x); att = att * att / (ll * ll * 0.35 + 1.0);
                    if ((int(lights[k].col.a + 0.5) & 2) != 0) att *= smoothstep(lights[k].dir.w, mix(lights[k].dir.w, 1.0, 0.45), dot(-ld / max(ll, 1e-3), lights[k].dir.xyz));
                    light += lights[k].col.rgb * att * 0.35;
                }
                scat += trans * (1.0 - tr) * light * uFogCol;
                trans *= tr;
                if (trans < 0.01) break;
            }
            // (what lies beyond the march is fogged where this is applied, the same for a wall as for the sky behind it)
            oFog = vec4(scat, trans);
        }
        """;

    /** Applies the half-resolution fog to the lit frame with a depth-aware upsample. */
    public static final String FOG_APPLY_FS = HEAD + SKY + BANK + """
        in vec2 vUV;
        layout(location=0) out vec4 oColor;
        uniform sampler2D uColor, uFog, uDepth;
        uniform vec2 uFogSize;
        uniform int uUseFog;
        uniform mat4 uInvPV;
        uniform vec3 uCam, uAmbient, uFogCol;
        uniform float uDensity, uHeightBase, uMaxDist, uNear, uFar;
        // 0.6, the fog bank: past uWall.x the world dissolves and by uWall.y it is gone, well inside the distance the
        // world is drawn to; the sky is the same bank seen from below (uWall.z of it at the horizon, uWall.w overhead).
        // Nothing ever appears out of clear air. 1.0: see BANK for what the world dissolves into, and from where.
        float lin(float d) { return 2.0 * uNear * uFar / (uFar + uNear - (d * 2.0 - 1.0) * (uFar - uNear)); }
        void main() {
            vec3 col = texture(uColor, vUV).rgb;
            float zc = texture(uDepth, vUV).r;
            vec4 c = uInvPV * vec4(vUV * 2.0 - 1.0, zc * 2.0 - 1.0, 1.0);
            vec3 P = c.xyz / c.w;
            vec3 ray = P - uCam;
            float L = max(length(ray), 1e-3);
            vec3 dir = ray / L;
            if (zc >= 1.0) col = mix(col, uWallCol, bankSkyK(dir));
            else {
                // the air past the end of the march: thinner with height, lit by the open sky (aerial perspective)
                float rest = L - uMaxDist;
                if (rest > 0.0 && uUseFog != 0) {
                    float h = P.y - uHeightBase;
                    float tr = exp(-uDensity * (h < -3.0 ? 2.2 : exp(-max(h, 0.0) * 0.045)) * rest);
                    col = col * tr + (1.0 - tr) * uAmbient * uFogCol;
                }
                col = bankApply(col, P, uCam);
            }
            if (uUseFog == 0) { oColor = vec4(col, 1.0); return; }
            // the half-resolution haze, upsampled by depth so that it does not bleed round silhouettes
            vec4 sum = vec4(0.0);
            float w = 0.0, lc = lin(zc);
            vec2 base = floor(vUV * uFogSize - 0.5) + 0.5;
            vec2 f = vUV * uFogSize - base;
            for (int i = 0; i < 4; i++) {
                vec2 o = vec2(i & 1, i >> 1);
                vec2 uv = (base + o) / uFogSize;
                float lz = min(lin(textureLod(uDepth, uv, 0.0).r), uMaxDist);
                vec2 bw = mix(1.0 - f, f, o);
                float k = bw.x * bw.y / (0.02 + abs(lz - min(lc, uMaxDist)) / max(min(lc, uMaxDist), 1.0) * 24.0) + 1e-5;
                sum += texture(uFog, uv) * k; w += k;
            }
            vec4 fog = sum / w;
            oColor = vec4(col * fog.a + fog.rgb, 1.0);
        }
        """;

    // ------------------------------------------------------------------ forward pass (glass, water, particles, emissive props)
    /**
     * 1.1: the air between the camera and something drawn in the forward pass, exactly as the lit frame behind it got it -
     * the haze past the end of the march, the bank, and the marched volumetric fog read from the same buffer at this
     * pixel. Glass used to get a thin exponential fog of its own, so in thick fog a window stood out clear in a wall
     * that had gone into the fog.
     */
    public static final String FWD_FOG = """
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
        """;

    public static final String WORLD_FWD_FS = HEAD + WORLD_TEX + SKY + BANK + FWD_FOG + """
        uniform sampler3D uProbeR, uProbeG, uProbeB;
        uniform vec3 uCam, uFogCol, uAmbient;
        uniform float uDensity;
        layout(location=0) out vec4 oColor;
        void main() {
            vec3 bp;
            vec4 tex = atlasTexel(bp);
            vec3 N = NORMALS[vNormal];
            bool liquid = (vFlags & 4) != 0;
            // 0.8: a pane is drawn here once - its near face - and only where it is glass. The frame round it is solid and
            // was written with the rest of the solid world (it has depth: it hides what is behind it, glass included).
            if (!liquid && (paneAlpha() >= GLASS_SOLID || !gl_FrontFacing)) discard;
            vec3 albedo = pow(tex.rgb, vec3(2.2));
            vec3 V = normalize(uCam - vWorld);
            if (dot(N, V) < 0.0) N = -N;
            vec3 uvw = (vWorld + N) / (vec3(176.0, 24.0, 176.0) * 4.0);
            vec4 r = texture(uProbeR, uvw), g = texture(uProbeG, uvw), b = texture(uProbeB, uvw);
            vec3 irr = max(vec3(r.x + dot(r.yzw, N), g.x + dot(g.yzw, N), b.x + dot(b.yzw, N)), vec3(0.0));
            float sky = vLight.x;
            vec3 col = albedo * (irr + uSunCol * max(dot(N, uSunDir), 0.0) * smoothstep(0.6, 1.0, sky));
            float fres = 0.04 + 0.96 * pow(1.0 - max(dot(N, V), 0.0), 5.0);
            vec3 R = reflect(-V, N);
            vec3 refl = mix(irr, skyColor(R), smoothstep(0.3, 0.9, sky) * smoothstep(-0.1, 0.2, R.y));
            float a = max(tex.a, liquid ? 0.62 : 0.0);
            col = mix(col, refl, fres);
            a = clamp(a + fres * 0.6, 0.0, 1.0);
            oColor = vec4(airFog(col, vWorld, uCam, uDensity, uAmbient * uFogCol), a);
        }
        """;

    public static final String MODEL_FWD_FS = HEAD + SKY + BANK + """
        in vec4 vCol;
        in vec3 vWorld;
        in vec3 vN;
        uniform vec4 uTint;
        uniform float uEmissive, uDensity;
        uniform vec3 uCam, uFogCol, uAmbient;
        uniform sampler3D uProbeR, uProbeG, uProbeB;
        layout(location=0) out vec4 oColor;
        void main() {
            vec3 n = normalize(vN);
            vec3 albedo = pow(vCol.rgb * uTint.rgb, vec3(2.2));
            vec3 uvw = (vWorld + n) / (vec3(176.0, 24.0, 176.0) * 4.0);
            vec4 r = texture(uProbeR, uvw), g = texture(uProbeG, uvw), b = texture(uProbeB, uvw);
            vec3 irr = max(vec3(r.x, g.x, b.x), vec3(0.0)) + uSunCol * 0.15;
            vec3 col = mix(albedo * irr, albedo * (1.5 + 6.0 * uEmissive), clamp(uEmissive, 0.0, 1.0));
            float dist = length(vWorld - uCam);
            float f = (1.0 - exp(-uDensity * dist * 0.7)) * (1.0 - 0.5 * clamp(uEmissive, 0.0, 1.0));
            oColor = vec4(mix(mix(col, uAmbient * uFogCol, f), bankSky(normalize(vWorld - uCam)), bankAt(vWorld) * (1.0 - 0.35 * clamp(uEmissive, 0.0, 1.0))), vCol.a * uTint.a);
            if (oColor.a < 0.02) discard;
        }
        """;

    /**
     * Camera-facing particles: one instanced quad each. kind 0 flame, 1 smoke, 2 ember / spark, 3 soft glow; 1.0 (the
     * Astratto particle system): 4 a ring lying flat on the ground (shockwaves, splashes), 5 a hot streak drawn out along
     * its own motion, 6 a muzzle flash, 7 a raindrop, 8 a soft mote (snow, dust, spray).
     */
    public static final String PARTICLE_VS = HEAD + """
        layout(location=0) in vec4 iPosSize;     // world position, size
        layout(location=1) in vec4 iColor;       // rgb (HDR), alpha
        layout(location=2) in vec4 iParams;      // rotation, kind, age 0..1, seed
        uniform mat4 uPV;
        uniform vec3 uRight, uUp;
        out vec2 vQ;
        out vec4 vColor;
        out vec4 vParams;
        out float vClipW;
        void main() {
            vec2 q = vec2(gl_VertexID & 1, (gl_VertexID >> 1) & 1) * 2.0 - 1.0;
            int kind = int(iParams.y + 0.5);
            float cr = cos(iParams.x), sr = sin(iParams.x);
            // a streak or a drop is long and thin: its width is a share of its length (carried where the others carry a seed)
            vec2 lq = (kind == 5 || kind == 7) ? vec2(q.x * iParams.w, q.y) : q;
            vec2 rq = vec2(lq.x * cr - lq.y * sr, lq.x * sr + lq.y * cr);
            // flames are taller than they are wide and stay upright
            vec3 up = kind == 0 ? vec3(0.0, 1.35, 0.0) : uUp;
            vec3 p = iPosSize.xyz + (uRight * rq.x + up * rq.y) * iPosSize.w;
            // a ring lies on the ground whichever way the camera looks at it
            if (kind == 4) p = iPosSize.xyz + vec3(q.x, 0.0, q.y) * iPosSize.w;
            vQ = q; vColor = iColor; vParams = iParams;
            gl_Position = uPV * vec4(p, 1.0);
            vClipW = gl_Position.w;
        }
        """;

    public static final String PARTICLE_FS = HEAD + """
        in vec2 vQ;
        in vec4 vColor;
        in vec4 vParams;
        in float vClipW;
        uniform sampler2D uNoise, uDepth;
        uniform vec2 uScreen;
        uniform float uTime, uNear, uFar;
        layout(location=0) out vec4 oColor;
        float lin(float d) { return 2.0 * uNear * uFar / (uFar + uNear - (d * 2.0 - 1.0) * (uFar - uNear)); }
        void main() {
            int kind = int(vParams.y + 0.5);
            float age = vParams.z, seed = vParams.w;
            float r = length(vQ);
            if (r > 1.0 && kind != 5 && kind != 7) discard;
            // soft where the sprite cuts into geometry
            float scene = lin(texture(uDepth, gl_FragCoord.xy / uScreen).r);
            float soft = clamp((scene - vClipW) * 2.5, 0.0, 1.0);
            float a;
            vec3 col = vColor.rgb;
            if (kind == 0) {
                // a tongue of flame: turbulence scrolling upward, hot core, torn edges
                vec2 uv = vQ * 0.5 + 0.5;
                float n1 = texture(uNoise, vec2(uv.x * 0.35 + seed, uv.y * 0.3 - uTime * 0.55 + seed * 3.1)).r;
                float n2 = texture(uNoise, vec2(uv.x * 0.9 - seed, uv.y * 0.7 - uTime * 1.1)).g;
                float shape = (1.0 - abs(vQ.x) * (1.0 + uv.y * 0.9)) * (1.0 - uv.y * uv.y * 0.9) * smoothstep(-1.0, -0.6, vQ.y);
                float f = shape * (0.55 + n1 * 0.9) - (0.35 + age * 0.45) * (0.6 + n2 * 0.8);
                a = smoothstep(0.0, 0.22, f);
                float heat = clamp(f * 2.2 + (1.0 - age) * 0.35, 0.0, 1.0);
                col *= mix(vec3(1.0, 0.16, 0.02), vec3(1.0, 0.78, 0.36), heat) * (1.0 + heat * 2.2);
                a *= vColor.a * (1.0 - age * age);
            } else if (kind == 1) {
                // 1.1, smoke. It was a soft disc with a little noise in it. Now each puff is a billow: its outline is torn by
                // two scales of turbulence that drift up through it, it is eaten away from inside as it ages (old smoke is
                // threads, not a ball), and it is shaded as a lit volume - paler on top where the sky is, darker underneath
                // and where it is thick.
                vec2 uv = vQ * 0.5;
                float n1 = texture(uNoise, uv * 0.50 + vec2(seed * 3.1, seed * 1.7 - uTime * 0.030)).r;
                float n2 = texture(uNoise, uv * 1.45 + vec2(-seed * 2.3, seed * 5.1 - uTime * 0.055)).g;
                float dens = n1 * 0.62 + n2 * 0.38;
                float edge = 1.0 - smoothstep(0.30, 1.0, r + (0.5 - dens) * 0.60);
                float erode = smoothstep(0.16 + age * 0.34, 0.60 + age * 0.28, dens + (1.0 - r) * 0.34);
                a = edge * erode * vColor.a * smoothstep(0.0, 0.07, age) * (1.0 - smoothstep(0.50, 1.0, age));
                float top = vQ.y * 0.5 + 0.5;
                col *= mix(0.60, 1.28, top) * mix(1.12, 0.78, edge * dens);
            } else if (kind == 2) {
                a = (1.0 - smoothstep(0.2, 1.0, r)) * vColor.a * (1.0 - age);
            } else if (kind == 4) {
                // an annulus that thins as it opens; broken up by noise so that it reads as dust, not as a drawn circle
                float n = texture(uNoise, vQ * 0.31 + vec2(seed * 7.0, seed * 3.0)).r;
                float w = mix(0.26, 0.10, age);
                a = (1.0 - smoothstep(0.0, w, abs(r - (0.98 - w)))) * (0.55 + 0.9 * n) * vColor.a * (1.0 - age) * (1.0 - age);
            } else if (kind == 5) {
                // hot at the head, drawn out to nothing behind
                a = (1.0 - abs(vQ.x)) * smoothstep(-1.0, 0.7, vQ.y) * (1.0 - smoothstep(0.8, 1.0, vQ.y)) * vColor.a * (1.0 - age * age);
            } else if (kind == 6) {
                // the flash at a muzzle: a core and a few uneven tongues, different every shot
                float an = atan(vQ.y, vQ.x);
                float spikes = pow(abs(cos(an * 2.5 + seed * 6.2832)), 5.0) + 0.6 * pow(abs(cos(an * 3.5 - seed * 9.0)), 9.0);
                float core = (1.0 - smoothstep(0.0, 0.42, r));
                a = (core * 1.6 + (1.0 - r) * (1.0 - r) * spikes * 1.5) * vColor.a * (1.0 - age);
                col *= mix(vec3(1.0, 0.55, 0.22), vec3(1.0, 0.95, 0.8), core);
            } else if (kind == 7) {
                a = (1.0 - abs(vQ.x)) * (1.0 - vQ.y * vQ.y) * vColor.a;
            } else if (kind == 8) {
                a = (1.0 - smoothstep(0.45, 1.0, r)) * vColor.a * (1.0 - smoothstep(0.75, 1.0, age));
            } else {
                a = (1.0 - r) * (1.0 - r) * vColor.a;
            }
            a *= soft;
            if (a < 0.004) discard;
            // premultiplied output: additive kinds carry no alpha, smoke and dust occlude, rain is half one and half the other
            oColor = (kind == 1 || kind == 8) ? vec4(col * a, a) : kind == 4 ? vec4(col * a, a * 0.8) : kind == 7 ? vec4(col * a * 0.55, a * 0.3) : vec4(col * a, 0.0);
        }
        """;

    // ------------------------------------------------------------------ temporal anti-aliasing
    public static final String TAA_FS = HEAD + """
        in vec2 vUV;
        layout(location=0) out vec4 oColor;
        uniform sampler2D uColor, uHistory, uDepth, uNormal;
        uniform mat4 uInvPV, uPrevPV;
        uniform vec2 uScreen;
        uniform float uBlend;
        vec3 tm(vec3 c) { return c / (1.0 + max(c.r, max(c.g, c.b))); }
        vec3 itm(vec3 c) { return c / max(1e-4, 1.0 - max(c.r, max(c.g, c.b))); }
        void main() {
            vec3 cur = tm(texture(uColor, vUV).rgb);
            float d = texture(uDepth, vUV).r;
            // the viewmodel moves with the camera: it is never reprojected
            bool hands = int(texture(uNormal, vUV).a * 3.0 + 0.5) == 1 && d < 0.05;
            vec2 puv = vUV;
            if (!hands) {
                vec4 w = uInvPV * vec4(vUV * 2.0 - 1.0, d * 2.0 - 1.0, 1.0);
                vec4 pc = uPrevPV * vec4(w.xyz / w.w, 1.0);
                puv = pc.xy / pc.w * 0.5 + 0.5;
                if (pc.w <= 0.0) puv = vec2(-1.0);
            }
            vec3 mn = cur, mx = cur, avg = cur;
            vec2 px = 1.0 / uScreen;
            for (int y = -1; y <= 1; y++) for (int x = -1; x <= 1; x++) {
                if (x == 0 && y == 0) continue;
                vec3 s = tm(texture(uColor, vUV + vec2(x, y) * px).rgb);
                mn = min(mn, s); mx = max(mx, s); avg += s;
            }
            avg /= 9.0;
            if (puv.x < 0.0 || puv.y < 0.0 || puv.x > 1.0 || puv.y > 1.0) { oColor = vec4(itm(cur), 1.0); return; }
            vec3 hist = clamp(tm(texture(uHistory, puv).rgb), mn, mx);
            float speed = length((puv - vUV) * uScreen);
            float k = mix(uBlend, 0.35, clamp(speed * 0.05, 0.0, 1.0));
            oColor = vec4(itm(mix(hist, cur, k)), 1.0);
        }
        """;

    // ------------------------------------------------------------------ bloom, exposure, tone mapping
    public static final String BLOOM_DOWN_FS = HEAD + """
        in vec2 vUV;
        layout(location=0) out vec4 oColor;
        uniform sampler2D uTex, uExposure;
        uniform vec2 uTexel;
        uniform float uThreshold;
        // 1.0: the threshold is applied to what the eye will see - the frame times the exposure - not to raw radiance. It
        // used to be raw, so in a dark room, where the exposure is high, anything a little brighter than the walls bloomed.
        // A soft knee below the threshold keeps the glow from switching on and off as a light flickers across it.
        vec3 pre(vec3 c) {
            if (uThreshold <= 0.0) return c;
            c *= clamp(texelFetch(uExposure, ivec2(0), 0).r, 0.3, 8.0);
            float l = max(c.r, max(c.g, c.b)), knee = uThreshold * 0.5;
            float soft = clamp(l - uThreshold + knee, 0.0, 2.0 * knee);
            soft = soft * soft / (4.0 * knee + 1e-4);
            return min(c * max(soft, l - uThreshold) / max(l, 1e-4), vec3(6.0));
        }
        void main() {
            // 13-tap downsample: stable under motion, no pulsing from single hot pixels
            vec3 a = pre(texture(uTex, vUV + uTexel * vec2(-2, 2)).rgb), b = pre(texture(uTex, vUV + uTexel * vec2(0, 2)).rgb), c = pre(texture(uTex, vUV + uTexel * vec2(2, 2)).rgb);
            vec3 d = pre(texture(uTex, vUV + uTexel * vec2(-2, 0)).rgb), e = pre(texture(uTex, vUV).rgb), f = pre(texture(uTex, vUV + uTexel * vec2(2, 0)).rgb);
            vec3 g = pre(texture(uTex, vUV + uTexel * vec2(-2, -2)).rgb), h = pre(texture(uTex, vUV + uTexel * vec2(0, -2)).rgb), i = pre(texture(uTex, vUV + uTexel * vec2(2, -2)).rgb);
            vec3 j = pre(texture(uTex, vUV + uTexel * vec2(-1, 1)).rgb), k = pre(texture(uTex, vUV + uTexel * vec2(1, 1)).rgb);
            vec3 l = pre(texture(uTex, vUV + uTexel * vec2(-1, -1)).rgb), m = pre(texture(uTex, vUV + uTexel * vec2(1, -1)).rgb);
            vec3 col = e * 0.125 + (a + c + g + i) * 0.03125 + (b + d + f + h) * 0.0625 + (j + k + l + m) * 0.125;
            oColor = vec4(min(col, vec3(64.0)), 1.0);
        }
        """;

    public static final String BLOOM_UP_FS = HEAD + """
        in vec2 vUV;
        layout(location=0) out vec4 oColor;
        uniform sampler2D uTex;
        uniform vec2 uTexel;
        void main() {
            vec3 col = texture(uTex, vUV).rgb * 4.0;
            col += (texture(uTex, vUV + uTexel * vec2(-1, 0)).rgb + texture(uTex, vUV + uTexel * vec2(1, 0)).rgb + texture(uTex, vUV + uTexel * vec2(0, -1)).rgb + texture(uTex, vUV + uTexel * vec2(0, 1)).rgb) * 2.0;
            col += texture(uTex, vUV + uTexel * vec2(-1, -1)).rgb + texture(uTex, vUV + uTexel * vec2(1, -1)).rgb + texture(uTex, vUV + uTexel * vec2(-1, 1)).rgb + texture(uTex, vUV + uTexel * vec2(1, 1)).rgb;
            oColor = vec4(col / 16.0, 1.0);
        }
        """;

    public static final String LUM_FS = HEAD + """
        in vec2 vUV;
        layout(location=0) out float oLum;
        uniform sampler2D uTex;
        void main() { vec3 c = texture(uTex, vUV).rgb; oLum = log(max(dot(c, vec3(0.2126, 0.7152, 0.0722)), 1e-4)); }
        """;

    public static final String EXPOSURE_FS = HEAD + """
        layout(location=0) out float oExposure;
        uniform sampler2D uLum, uPrev;
        uniform float uDt, uMin, uMax, uReset;
        void main() {
            float avg = exp(textureLod(uLum, vec2(0.5), 10.0).r);
            float target = clamp(0.16 / max(avg, 1e-4), uMin, uMax);
            float prev = texelFetch(uPrev, ivec2(0), 0).r;
            if (uReset > 0.5 || !(prev > 0.0)) prev = target;
            // eyes open slowly in the dark and shut quickly in the light
            float rate = target > prev ? 0.7 : 2.4;
            oExposure = mix(prev, target, 1.0 - exp(-uDt * rate));
        }
        """;

    public static final String TONEMAP_FS = HEAD + """
        in vec2 vUV;
        layout(location=0) out vec4 oColor;
        uniform sampler2D uColor, uBloom, uExposure;
        uniform float uBloomStrength, uTime, uDeath, uGrain, uFlash, uVignette, uHurt, uInfection, uSharpen, uBile;
        uniform vec2 uScreen;
        vec3 aces(vec3 x) { return clamp((x * (2.51 * x + 0.03)) / (x * (2.43 * x + 0.59) + 0.14), 0.0, 1.0); }
        float h21(vec2 p) { return fract(sin(dot(p, vec2(12.9898, 78.233))) * 43758.5453); }
        void main() {
            vec2 px = 1.0 / uScreen;
            vec3 col = texture(uColor, vUV).rgb;
            if (uSharpen > 0.0) {
                vec3 blur = (texture(uColor, vUV + vec2(px.x, 0)).rgb + texture(uColor, vUV - vec2(px.x, 0)).rgb + texture(uColor, vUV + vec2(0, px.y)).rgb + texture(uColor, vUV - vec2(0, px.y)).rgb) * 0.25;
                col = max(col + (col - blur) * uSharpen, vec3(0.0));
            }
            if (uBile > 0.0) {
                // bile in the eyes: the picture goes out of focus (two rings of taps, wider toward the edge of the frame) and drifts
                vec2 c0 = vUV - 0.5;
                float rad = uBile * (5.0 + 16.0 * dot(c0, c0) * 4.0) * (1.0 + 0.25 * sin(uTime * 2.3));
                vec2 drift = vec2(sin(uTime * 1.7 + vUV.y * 9.0), cos(uTime * 1.3 + vUV.x * 7.0)) * px * 3.0 * uBile;
                vec3 acc = col;
                for (int i = 0; i < 12; i++) {
                    float an = float(i) * 0.5236 + uTime * 0.4;
                    vec2 o = vec2(cos(an), sin(an)) * px * rad * (i % 2 == 0 ? 1.0 : 0.5);
                    acc += texture(uColor, vUV + o + drift).rgb;
                }
                col = mix(col, acc / 13.0, min(1.0, uBile * 1.15));
            }
            col *= texelFetch(uExposure, ivec2(0), 0).r;
            col += texture(uBloom, vUV).rgb * uBloomStrength;
            col = aces(col);
            col = pow(col, vec3(1.0 / 2.2));
            vec2 q = vUV - 0.5;
            float vig = dot(q, q);
            col *= 1.0 - vig * uVignette;
            // injury: the edges of the frame close in red
            col = mix(col, vec3(0.45, 0.02, 0.01), clamp(uHurt * vig * 2.6, 0.0, 0.85));
            // infection: a sick green cast that pulses, strongest at the edges
            col = mix(col, col * vec3(0.72, 1.05, 0.62) + vec3(0.0, 0.03, 0.0), clamp(uInfection * (0.25 + vig * 2.0), 0.0, 0.8));
            // bile: dark green creeping in from the edges, running down the glass of the eye
            if (uBile > 0.0) {
                float run = 0.5 + 0.5 * sin(vUV.x * 37.0 + sin(vUV.y * 5.0 + uTime * 0.8) * 2.0);
                col = mix(col, col * vec3(0.55, 0.86, 0.42) + vec3(0.01, 0.05, 0.0), clamp(uBile * (0.22 + vig * 2.4 + run * vig * 1.2), 0.0, 0.85));
            }
            // death: colour drains to black and white while the grain rises
            float luma = dot(col, vec3(0.299, 0.587, 0.114));
            col = mix(col, vec3(luma) * (1.0 - uDeath * 0.25), uDeath);
            float grain = h21(floor(gl_FragCoord.xy / (1.0 + uDeath * 1.5)) + fract(uTime * 13.7) * 91.0) - 0.5;
            col += grain * (uGrain + uDeath * 0.22) * (0.35 + luma);
            // scan flicker of a dying picture
            col *= 1.0 - uDeath * 0.12 * step(0.985, h21(vec2(floor(gl_FragCoord.y / 3.0), floor(uTime * 24.0))));
            col = mix(col, vec3(1.0), clamp(uFlash, 0.0, 1.0));
            oColor = vec4(col + (h21(gl_FragCoord.xy) - 0.5) / 255.0, 1.0);
        }
        """;

    /** Depth-only visualisers and debug views share this passthrough. */
    public static final String COPY_FS = HEAD + """
        in vec2 vUV;
        layout(location=0) out vec4 oColor;
        uniform sampler2D uTex;
        void main() { oColor = texture(uTex, vUV); }
        """;
}
