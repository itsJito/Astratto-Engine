package astratto.decal;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL12.*;
import static org.lwjgl.opengl.GL15.*;
import static org.lwjgl.opengl.GL20.*;
import static org.lwjgl.opengl.GL30.*;

import astratto.math.Mat4;
import astratto.render.Shader;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import org.lwjgl.system.MemoryUtil;

/**
 * The Astratto decal engine (1.0): thin marks laid over the surfaces of the world - dirt run down from a window sill,
 * moss at the foot of a wall, cracks and patches in a path, leaves blown into a corner, oil, rust, water stains - and
 * the marks play leaves behind it: bullet holes, scorches, blood.
 *
 * <b>How a decal is drawn.</b> A decal is a quad on a block face. It is drawn into the G-buffer after the world, and it
 * writes <i>only the surface colour</i>, blended by its own coverage. Normal, roughness, baked light and depth are the
 * wall's and stay the wall's - so a decal is lit, shadowed, fogged and reflected exactly as the surface it lies on, at
 * no cost in the lighting pass, and can never look pasted on.
 *
 * <b>Where static decals come from.</b> Like the foliage, from rules applied to what the {@link Source} says of the
 * world, cell by cell, with every choice drawn from a hash of the cell and a variant picked from a small authored set
 * ({@link DecalAtlas}):
 * <ul>
 * <li>a window with wall under it, facing the open air: a streak of dirt on the wall beneath the sill, one or two blocks long;</li>
 * <li>the foot of an outside wall: moss, more of it over soil and on the damp side, in drifts not everywhere;</li>
 * <li>paving: cracks, repairs, leaves; a road: cracks, oil; bare metal: rust runs; indoors: the dirt of the floor line,
 *     water stains, paper.</li>
 * </ul>
 * The world is cut into 16 m patches, each laid out once when the camera comes near and laid out again if the world
 * there changes (a wall that is blown away takes its stains with it).
 *
 * <b>Dynamic decals</b> ({@link #add}) live in a ring: the oldest gives way to the newest, and each fades out over the
 * last part of its life - nothing left by a fight stays for ever.
 *
 * <b>2.0: native drawing.</b> Given the engine's renderer ({@link #initNative}), the patches' meshes and the decal texture
 * array go to it ({@code LAYER_DECAL}), and each frame - with that frame's marks - is asked for with {@link #frame}.
 */
public final class Decals {
    public interface Source {
        int MASONRY = 1, METAL = 2, WOOD = 3, WINDOW = 4, SOIL = 5, PAVED = 6, ROAD = 7, FLOOR = 8;
        int width();
        int depth();
        int height();
        /** What a cell is, for the rules: one of the constants above for a full block of that kind (WINDOW for glazing), 0 for anything else. */
        int kind(int x, int y, int z);
        /** True when nothing in the cell would hide a decal on a neighbouring face (air, or something thin). */
        boolean open(int x, int y, int z);
        /** Sky light of a cell, 0..15: how far out of doors it is. */
        int skyLight(int x, int y, int z);
        /** The highest cell of a column that is not air. */
        int top(int x, int z);
        /** How neglected the place is, 0 (kept) .. about 1.5. */
        float wear(int x, int z);
        int version(int cx, int cz);
        /**
         * 1.0, linings. A wall between two rooms is one block thick and one material, so one of the two rooms used to
         * be shown the other's wall. A lining is a whole face laid over the side of wall cell (x, y, z) that looks
         * toward direction d (0 -z, 1 +x, 2 +z, 3 -x): return the layer of the world's own texture atlas that side
         * should show - the room's paper, its panelling, its tile - or -1 to leave the wall as it is.
         */
        default int lining(int x, int y, int z, int d) { return -1; }
    }

    private static final int PATCH = 16, VF = 10, MAX_DYNAMIC = 640;

    private static final class Patch { int version, vao, vbo, verts, nat; boolean built; float[] data; int n; }

    private Source src;
    private Patch[] patches;
    private int pw, pd;
    public boolean enabled = true;
    public float density = 1f, drawDistance = 84f;
    private Shader shader;
    private int tex, dynVao, dynVbo;
    /** The world's own texture atlas (an array texture): what linings are drawn from. 0 = no linings. */
    public int atlasTex;
    public int linings;
    private boolean glReady;
    // ---- 2.0: the engine's renderer, when it draws the decals instead, and its id for the decal texture array
    private astratto.bridge.NativeRenderer nat;
    private int natTex;
    private float[] natDyn = new float[0];
    private final float[] scratch = new float[VF * 6];
    private float[] buildBuf = new float[VF * 6 * 256];
    private int buildN;
    // ---- dynamic ring: per decal 6 vertices + {born, life}
    private final float[] dyn = new float[MAX_DYNAMIC * 6 * VF];
    private final float[] dynBorn = new float[MAX_DYNAMIC], dynLife = new float[MAX_DYNAMIC];
    private int dynNext, dynLive;
    private final FloatBuffer dynBuf = MemoryUtil.memAllocFloat(MAX_DYNAMIC * 6 * VF);
    public float time;
    /** How much snow lies (0..1): what is on the ground is under it. */
    public float snow;
    /** Statistics. */
    public int staticQuads, patchesBuilt, dynamicQuads;

    public void attach(Source s) {
        if (patches != null && glReady) for (Patch p : patches) if (p != null && p.vao != 0) { glDeleteVertexArrays(p.vao); glDeleteBuffers(p.vbo); }
        if (patches != null && nat != null) for (Patch p : patches) if (p != null && p.nat != 0) nat.release(astratto.bridge.AstrattoNative.RES_MESH, p.nat);
        src = s;
        pw = (s.width() + PATCH - 1) / PATCH; pd = (s.depth() + PATCH - 1) / PATCH;
        patches = new Patch[pw * pd];
        dynLive = 0; dynNext = 0; patchesBuilt = 0;
        java.util.Arrays.fill(dynLife, 0);
    }

    /** A well-mixed hash of a cell (three rounds: one round leaves lines of equal values running diagonally through the world). */
    private static float hash(int x, int y, int z, int salt) {
        int h = x * 0x9E3779B1 ^ Integer.rotateLeft(y * 0x85EBCA77, 13) ^ Integer.rotateLeft(z * 0xC2B2AE3D, 7) ^ (salt * 0x27D4EB2F);
        h ^= h >>> 16; h *= 0x7FEB352D; h ^= h >>> 15; h *= 0x846CA68B; h ^= h >>> 16;
        return (h & 0xFFFFFF) / (float) 0x1000000;
    }

    private static float vnoise(float x, float z, int salt) {
        int ix = (int) Math.floor(x), iz = (int) Math.floor(z);
        float fx = x - ix, fz = z - iz;
        fx = fx * fx * (3 - 2 * fx); fz = fz * fz * (3 - 2 * fz);
        float a = hash(ix, 0, iz, salt), b = hash(ix + 1, 0, iz, salt), c = hash(ix, 0, iz + 1, salt), d = hash(ix + 1, 0, iz + 1, salt);
        return a + (b - a) * fx + (c - a) * fz + (a - b - c + d) * fx * fz;
    }

    private static final int[][] DIRS = {{0, 0, -1}, {1, 0, 0}, {0, 0, 1}, {-1, 0, 0}};

    private void vert(float x, float y, float z, float u, float v, float layer, float r, float g, float b, float a) {
        if (buildN + VF > buildBuf.length) buildBuf = java.util.Arrays.copyOf(buildBuf, buildBuf.length * 2);
        buildBuf[buildN++] = x; buildBuf[buildN++] = y; buildBuf[buildN++] = z; buildBuf[buildN++] = u; buildBuf[buildN++] = v; buildBuf[buildN++] = layer;
        buildBuf[buildN++] = r; buildBuf[buildN++] = g; buildBuf[buildN++] = b; buildBuf[buildN++] = a;
    }

    /**
     * A decal on the side of the block column (x, y0..y1, z) that faces direction d (0 -z, 1 +x, 2 +z, 3 -x): the
     * picture's top edge is at the top of y1. mirror flips it left to right.
     */
    private void wallQuad(int x, int y0, int y1, int z, int d, int layer, boolean mirror, float alpha) {
        final float e = 0.003f;
        float ax, az, bx, bz;   // the face's two bottom corners, left then right as seen from outside
        switch (d) {
            case 0 -> { ax = x + 1; az = z - e; bx = x; bz = z - e; }
            case 1 -> { ax = x + 1 + e; az = z + 1; bx = x + 1 + e; bz = z; }
            case 2 -> { ax = x; az = z + 1 + e; bx = x + 1; bz = z + 1 + e; }
            default -> { ax = x - e; az = z; bx = x - e; bz = z + 1; }
        }
        float u0 = mirror ? 1 : 0, u1 = mirror ? 0 : 1, yb = y0, yt = y1 + 1;
        vert(ax, yb, az, u0, 1, layer, 1, 1, 1, alpha); vert(bx, yb, bz, u1, 1, layer, 1, 1, 1, alpha); vert(bx, yt, bz, u1, 0, layer, 1, 1, 1, alpha);
        vert(ax, yb, az, u0, 1, layer, 1, 1, 1, alpha); vert(bx, yt, bz, u1, 0, layer, 1, 1, 1, alpha); vert(ax, yt, az, u0, 0, layer, 1, 1, 1, alpha);
    }

    /** A decal lying on top of block (x, y, z), turned by quarter turns, covering size cells from that corner. */
    private void floorQuad(int x, int y, int z, int layer, int turn, float alpha) {
        final float h = y + 1 + 0.003f;
        float[][] uv = {{0, 0}, {1, 0}, {1, 1}, {0, 1}};
        float[][] c = {{x, z}, {x + 1, z}, {x + 1, z + 1}, {x, z + 1}};
        int[] order = {0, 3, 2, 0, 2, 1};        // counter-clockwise seen from above
        for (int k : order) { float[] t = uv[(k + turn) & 3]; vert(c[k][0], h, c[k][1], t[0], t[1], layer, 1, 1, 1, alpha); }
    }

    /** The underside of a block, as a surface: what a room's ceiling is drawn on. */
    private void ceilQuad(int x, int y, int z, int layer) {
        final float h = y - 0.003f;
        float[][] uv = {{0, 0}, {1, 0}, {1, 1}, {0, 1}};
        float[][] c = {{x, z}, {x + 1, z}, {x + 1, z + 1}, {x, z + 1}};
        int[] order = {0, 1, 2, 0, 2, 3};        // counter-clockwise seen from below
        for (int k : order) vert(c[k][0], h, c[k][1], uv[k][0], uv[k][1], layer, 1, 1, 1, 1f);
    }

    private static int layer(int kind, float h) { return DecalAtlas.FIRST[kind] + Math.min(DecalAtlas.VARIANTS[kind] - 1, (int) (h * DecalAtlas.VARIANTS[kind])); }

    private void build(Patch p, int pcx, int pcz) {
        buildN = 0;
        final int x0 = pcx * PATCH, z0 = pcz * PATCH, H = src.height();
        final float D = density;
        for (int lx = 0; lx < PATCH; lx++) for (int lz = 0; lz < PATCH; lz++) {
            int x = x0 + lx, z = z0 + lz;
            int top = Math.min(H - 2, src.top(x, z));
            if (top <= 0) continue;
            float wear = src.wear(x, z) * D;
            if (wear <= 0.01f) continue;
            float drift = vnoise(x * 0.08f, z * 0.08f, 21);
            for (int y = 1; y <= top; y++) {
                int k = src.kind(x, y, z);
                if (k == 0) continue;
                boolean above = src.open(x, y + 1, z);
                // ---- the tops of things: paths, roads, floors
                if (above && (k == Source.PAVED || k == Source.ROAD || k == Source.FLOOR)) {
                    boolean outdoors = src.skyLight(x, y + 1, z) >= 13;
                    float h0 = hash(x, y, z, 1), h1 = hash(x, y, z, 2), h2 = hash(x, y, z, 3);
                    int turn = (int) (h2 * 4) & 3;
                    if (k == Source.PAVED && outdoors) {
                        if (h0 < 0.075f * wear) floorQuad(x, y, z, layer(DecalAtlas.CRACK, h1), turn, 0.85f);
                        else if (h0 < 0.105f * wear) floorQuad(x, y, z, layer(DecalAtlas.PATCH, h1), turn, 0.8f);
                        else if (h0 > 1 - 0.06f * wear * (0.4f + drift)) floorQuad(x, y, z, layer(DecalAtlas.LEAVES, h1), turn, 0.95f);
                        else if (h0 > 0.5f && h0 < 0.5f + 0.012f * wear) floorQuad(x, y, z, layer(DecalAtlas.MUD, h1), turn, 0.8f);
                    } else if (k == Source.ROAD && outdoors) {
                        if (h0 < 0.045f * wear) floorQuad(x, y, z, layer(DecalAtlas.CRACK, h1), turn, 0.9f);
                        else if (h0 < 0.058f * wear) floorQuad(x, y, z, layer(DecalAtlas.OIL, h1), turn, 0.8f);
                        // (1.1: a road is patched with tar. One patch in three used to be the pale concrete one - a light grey
                        // rectangle lying on black asphalt, which looked like a texture that had failed to load.)
                        else if (h0 < 0.078f * wear) floorQuad(x, y, z, layer(DecalAtlas.PATCH, h1 < 0.5f ? 0.05f : 0.95f), turn, 0.75f);
                        else if (h0 > 1 - 0.02f * wear * drift) floorQuad(x, y, z, layer(DecalAtlas.LEAVES, h1), turn, 0.9f);
                    } else if (!outdoors) {
                        if (h0 < 0.022f * wear) floorQuad(x, y, z, layer(DecalAtlas.WATER_STAIN, h1), turn, 0.6f);
                        else if (h0 < 0.036f * wear) floorQuad(x, y, z, layer(DecalAtlas.PAPER, h1), turn, 0.95f);
                        else if (h0 < 0.05f * wear) floorQuad(x, y, z, layer(DecalAtlas.CRACK, h1), turn, 0.6f);
                        else if (h0 > 1 - 0.016f * wear) floorQuad(x, y, z, layer(DecalAtlas.MUD, h1), turn, 0.55f);
                    }
                }
                // ---- the sides of things
                boolean wallish = k == Source.MASONRY || k == Source.METAL || k == Source.WOOD;
                if (k == Source.WINDOW) continue;
                // 1.0: a ceiling of its own - the underside of this block, where it is over a finished room
                if ((atlasTex != 0 || nat != null) && y > 1 && src.open(x, y - 1, z)) { int cl = src.lining(x, y, z, 4); if (cl >= 0) { ceilQuad(x, y, z, -(cl + 1)); linings++; } }
                for (int d = 0; d < 4; d++) {
                    int nx = x + DIRS[d][0], nz = z + DIRS[d][2];
                    if (!src.open(nx, y, nz)) continue;
                    // a lining first: whatever else lies on this face lies on top of it
                    int ln = atlasTex != 0 || nat != null ? src.lining(x, y, z, d) : -1;
                    if (ln >= 0) { wallQuad(x, y, y, z, d, -(ln + 1), false, 1f); linings++; }
                    if (!wallish) continue;
                    boolean outdoors = src.skyLight(nx, y, nz) >= 12;
                    float h0 = hash(x, y, z, 10 + d), h1 = hash(x, y, z, 20 + d);
                    boolean mirror = h1 > 0.5f;
                    int upKind = src.kind(x, y + 1, z);
                    // dirt beneath a window: glazing over this wall cell, open to the same side
                    if (upKind == Source.WINDOW && outdoors && k != Source.METAL && src.open(nx, y + 1, nz) && h0 < 0.62f * Math.min(1.3f, wear + 0.3f)) {
                        int y0 = y;
                        if (h1 < 0.55f && y - 1 > 0 && src.kind(x, y - 1, z) == k && src.open(nx, y - 1, nz)) y0 = y - 1;
                        wallQuad(x, y0, y, z, d, layer(DecalAtlas.DIRT_STREAK, hash(x, y, z, 30 + d)), mirror, 0.85f);
                        continue;
                    }
                    // is this the foot of the wall? (something to stand on in front of it, one down)
                    boolean foot = !src.open(nx, y - 1, nz);
                    if (foot && outdoors) {
                        int under = src.kind(nx, y - 1, nz);
                        float want = (0.16f + (under == Source.SOIL ? 0.34f : 0f) + (d == 2 ? 0.14f : 0f)) * wear * (0.35f + 1.3f * drift);
                        if (k == Source.METAL) { if (h0 < 0.2f * wear) wallQuad(x, y, y, z, d, layer(DecalAtlas.RUST, h1), mirror, 0.8f); }
                        else if (h0 < want) wallQuad(x, y, y, z, d, layer(DecalAtlas.MOSS, hash(x, y, z, 40 + d)), mirror, 0.92f);
                        else if (h0 > 1 - 0.1f * wear) wallQuad(x, y, y, z, d, layer(DecalAtlas.GRIME, h1), mirror, 0.7f);
                    } else if (foot) {
                        // indoors: the dirt of the floor line
                        if (h0 < 0.17f * wear) wallQuad(x, y, y, z, d, layer(DecalAtlas.GRIME, h1), mirror, 0.75f);
                    } else if (outdoors) {
                        if (k == Source.METAL) { if (h0 < 0.05f * wear) wallQuad(x, y, y, z, d, layer(DecalAtlas.RUST, h1), mirror, 0.75f); }
                        else if (k == Source.MASONRY && h0 < 0.022f * wear) wallQuad(x, y, y, z, d, layer(DecalAtlas.WALL_CRACK, h1), mirror, 0.8f);
                        else if (k == Source.MASONRY && h0 > 1 - 0.012f * wear && upKind != 0) wallQuad(x, y, y, z, d, layer(DecalAtlas.DIRT_STREAK, h1), mirror, 0.5f);
                    } else {
                        // indoors, higher up: a water stain under the ceiling, the odd crack
                        if (k != Source.METAL && h0 < 0.02f * wear) wallQuad(x, y, y, z, d, layer(DecalAtlas.WATER_STAIN, h1), mirror, 0.5f);
                        else if (k == Source.MASONRY && h0 > 1 - 0.014f * wear) wallQuad(x, y, y, z, d, layer(DecalAtlas.WALL_CRACK, h1), mirror, 0.65f);
                    }
                }
            }
        }
        p.n = buildN / VF;
        p.data = java.util.Arrays.copyOf(buildBuf, buildN);
        p.built = true;
        patchesBuilt++;
        if (nat != null) uploadNative(p);
        else if (glReady) uploadPatch(p);
    }

    private void uploadNative(Patch p) {
        if (p.nat != 0) nat.release(astratto.bridge.AstrattoNative.RES_MESH, p.nat);
        p.nat = p.n > 0 ? nat.layerMesh(astratto.bridge.AstrattoNative.LAYER_DECAL, p.data, p.n) : 0;
        p.verts = p.n;
        p.data = null;
    }

    private void uploadPatch(Patch p) {
        if (p.vao == 0) { p.vao = glGenVertexArrays(); p.vbo = glGenBuffers(); glBindVertexArray(p.vao); glBindBuffer(GL_ARRAY_BUFFER, p.vbo); layout(); }
        glBindBuffer(GL_ARRAY_BUFFER, p.vbo);
        glBufferData(GL_ARRAY_BUFFER, p.data, GL_STATIC_DRAW);
        p.verts = p.n;
        p.data = null;
        glBindVertexArray(0);
    }

    private static void layout() {
        glVertexAttribPointer(0, 3, GL_FLOAT, false, VF * 4, 0); glEnableVertexAttribArray(0);
        glVertexAttribPointer(1, 3, GL_FLOAT, false, VF * 4, 12); glEnableVertexAttribArray(1);
        glVertexAttribPointer(2, 4, GL_FLOAT, false, VF * 4, 24); glEnableVertexAttribArray(2);
    }

    /** How many decals of the patches laid out so far lie within r of a point (gates). Lays out the point's own patch if need be. */
    public int countNear(float x, float z, float r) {
        if (src == null) return 0;
        int n = 0;
        int c0 = Math.max(0, (int) ((x - r) / PATCH)), c1 = Math.min(pw - 1, (int) ((x + r) / PATCH)), d0 = Math.max(0, (int) ((z - r) / PATCH)), d1 = Math.min(pd - 1, (int) ((z + r) / PATCH));
        for (int cx = c0; cx <= c1; cx++) for (int cz = d0; cz <= d1; cz++) {
            Patch p = patches[cx * pd + cz];
            if (p == null) patches[cx * pd + cz] = p = new Patch();
            if (!p.built) { p.version = src.version(cx, cz); build(p, cx, cz); }
            n += p.n / 6;
        }
        return n;
    }

    // ------------------------------------------------------------------ dynamic
    /**
     * A mark left by something that happened: on the surface at (x, y, z) whose normal is (nx, ny, nz), size metres
     * across, turned by rot, for life seconds (it fades over the last quarter). tint multiplies the picture's colour.
     */
    public void add(int kind, float x, float y, float z, float nx, float ny, float nz, float size, float rot, float life, int tintRgb, float alpha) {
        float l = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (l < 1e-4f) return;
        nx /= l; ny /= l; nz /= l;
        // a tangent frame on the surface
        float tx, ty, tz;
        if (Math.abs(ny) > 0.7f) { tx = 1; ty = 0; tz = 0; } else { tx = -nz; ty = 0; tz = nx; float tl = (float) Math.sqrt(tx * tx + tz * tz); tx /= tl; tz /= tl; }
        float bx = ny * tz - nz * ty, by = nz * tx - nx * tz, bz = nx * ty - ny * tx;
        if (Math.abs(ny) > 0.7f) { float c = (float) Math.cos(rot), s = (float) Math.sin(rot); float ntx = tx * c + bx * s, nty = ty * c + by * s, ntz = tz * c + bz * s; bx = -tx * s + bx * c; by = -ty * s + by * c; bz = -tz * s + bz * c; tx = ntx; ty = nty; tz = ntz; }
        float hs = size * 0.5f, e = 0.006f;
        float cx = x + nx * e, cy = y + ny * e, cz = z + nz * e;
        float r = ((tintRgb >> 16) & 255) / 255f, g = ((tintRgb >> 8) & 255) / 255f, b = (tintRgb & 255) / 255f;
        int lay = DecalAtlas.FIRST[kind] + Math.floorMod(Float.floatToIntBits(x * 31.7f + z * 17.3f + y), DecalAtlas.VARIANTS[kind]);
        int slot = dynNext;
        dynNext = (dynNext + 1) % MAX_DYNAMIC;
        dynLive = Math.min(MAX_DYNAMIC, dynLive + 1);
        dynBorn[slot] = time; dynLife[slot] = life;
        float[][] c4 = {{-1, -1, 0, 1}, {1, -1, 1, 1}, {1, 1, 1, 0}, {-1, 1, 0, 0}};
        int[] order = {0, 1, 2, 0, 2, 3};
        int o = slot * 6 * VF;
        for (int k : order) {
            float[] c = c4[k];
            dyn[o++] = cx + (tx * c[0] + bx * c[1]) * hs; dyn[o++] = cy + (ty * c[0] + by * c[1]) * hs; dyn[o++] = cz + (tz * c[0] + bz * c[1]) * hs;
            dyn[o++] = c[2]; dyn[o++] = c[3]; dyn[o++] = lay; dyn[o++] = r; dyn[o++] = g; dyn[o++] = b; dyn[o++] = alpha;
        }
    }

    public int dynamicCount() { int n = 0; for (int i = 0; i < MAX_DYNAMIC; i++) if (dynLife[i] > 0 && time - dynBorn[i] < dynLife[i]) n++; return n; }
    public void clearDynamic() { java.util.Arrays.fill(dynLife, 0); dynLive = 0; }

    // ------------------------------------------------------------------ 2.0: the engine's renderer
    /** Hands the decal pictures (and every patch laid out so far) to the engine's renderer; from now on it draws them (see {@link #frame}). */
    public void initNative(astratto.bridge.NativeRenderer r) {
        nat = r;
        ByteBuffer buf = pictures();
        try {
            natTex = r.texture(astratto.bridge.AstrattoNative.TEX_ARRAY, DecalAtlas.N, DecalAtlas.N, DecalAtlas.LAYERS,
                    astratto.bridge.AstrattoNative.TEX_MIPMAP | astratto.bridge.AstrattoNative.TEX_MIP_LINEAR, buf);
        } finally { MemoryUtil.memFree(buf); }
        // patches laid out so far: handed over as they are, or (given to the GL already) laid out again
        if (patches != null) for (Patch p : patches) if (p != null && p.built) { if (p.data != null) uploadNative(p); else p.built = false; }
    }

    public boolean isNative() { return nat != null; }

    /** Every decal picture, layer after layer, RGBA (native memory: the caller frees it). */
    private static ByteBuffer pictures() {
        ByteBuffer buf = MemoryUtil.memAlloc(DecalAtlas.N * DecalAtlas.N * 4 * DecalAtlas.LAYERS);
        for (int k = 0; k < DecalAtlas.KINDS; k++) for (int v = 0; v < DecalAtlas.VARIANTS[k]; v++) {
            int[] px = DecalAtlas.paint(k, v);
            for (int c : px) buf.put((byte) (c >> 16)).put((byte) (c >> 8)).put((byte) c).put((byte) (c >>> 24));
        }
        buf.flip();
        return buf;
    }

    /** This frame's decals, for the engine's renderer: the laid-out patches are its own; the marks of what has happened go with the frame, faded. */
    public void frame(astratto.bridge.RenderCommands c) {
        if (nat == null || !enabled || src == null) return;
        int n = 0;
        if (natDyn.length < MAX_DYNAMIC * 6 * VF) natDyn = new float[MAX_DYNAMIC * 6 * VF];
        for (int i = 0; i < MAX_DYNAMIC; i++) {
            if (dynLife[i] <= 0) continue;
            float age = time - dynBorn[i];
            if (age >= dynLife[i] || age < 0) { dynLife[i] = 0; continue; }
            float fade = Math.min(1, (dynLife[i] - age) / (dynLife[i] * 0.25f));
            int o = i * 6 * VF;
            System.arraycopy(dyn, o, natDyn, n * 6 * VF, 6 * VF);
            for (int v = 0; v < 6; v++) natDyn[n * 6 * VF + v * VF + VF - 1] *= fade;
            n++;
        }
        dynamicQuads = n;
        c.decals(natTex, 0, drawDistance * 0.75f, drawDistance, snow, natDyn, n * 6);
    }

    // ------------------------------------------------------------------ GL
    public void init() {
        shader = new Shader(VS, FS);
        tex = glGenTextures();
        glBindTexture(GL_TEXTURE_2D_ARRAY, tex);
        ByteBuffer buf = MemoryUtil.memAlloc(DecalAtlas.N * DecalAtlas.N * 4 * DecalAtlas.LAYERS);
        for (int k = 0; k < DecalAtlas.KINDS; k++) for (int v = 0; v < DecalAtlas.VARIANTS[k]; v++) {
            int[] px = DecalAtlas.paint(k, v);
            for (int c : px) buf.put((byte) (c >> 16)).put((byte) (c >> 8)).put((byte) c).put((byte) (c >>> 24));
        }
        buf.flip();
        glPixelStorei(GL_UNPACK_ALIGNMENT, 1);
        glTexImage3D(GL_TEXTURE_2D_ARRAY, 0, GL_RGBA8, DecalAtlas.N, DecalAtlas.N, DecalAtlas.LAYERS, 0, GL_RGBA, GL_UNSIGNED_BYTE, buf);
        MemoryUtil.memFree(buf);
        glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_MIN_FILTER, GL_LINEAR_MIPMAP_LINEAR);
        glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        glGenerateMipmap(GL_TEXTURE_2D_ARRAY);
        dynVao = glGenVertexArrays();
        dynVbo = glGenBuffers();
        glBindVertexArray(dynVao);
        glBindBuffer(GL_ARRAY_BUFFER, dynVbo);
        glBufferData(GL_ARRAY_BUFFER, (long) MAX_DYNAMIC * 6 * VF * 4, GL_STREAM_DRAW);
        layout();
        glBindVertexArray(0);
        glReady = true;
        if (patches != null) for (Patch p : patches) if (p != null && p.built && p.data != null) uploadPatch(p);
    }

    /** Lays out the patches round the camera that are missing or out of date, a few a frame. */
    public void update(float camX, float camZ, int budget) {
        if (src == null || !enabled) return;
        int ccx = (int) Math.floor(camX / PATCH), ccz = (int) Math.floor(camZ / PATCH), R = (int) Math.ceil(drawDistance / PATCH), built = 0;
        for (int ring = 0; ring <= R && built < budget; ring++) for (int dx = -ring; dx <= ring && built < budget; dx++) for (int dz = -ring; dz <= ring && built < budget; dz++) {
            if (Math.max(Math.abs(dx), Math.abs(dz)) != ring) continue;
            int cx = ccx + dx, cz = ccz + dz;
            if (cx < 0 || cz < 0 || cx >= pw || cz >= pd) continue;
            Patch p = patches[cx * pd + cz];
            if (p == null) patches[cx * pd + cz] = p = new Patch();
            int v = src.version(cx, cz);
            if (!p.built || v != p.version) { p.version = v; build(p, cx, cz); built++; }
        }
    }

    /**
     * Into the G-buffer, straight after the world. Expects the G-buffer bound with its three colour targets; leaves depth
     * writes, colour masks and blending as it found them (off, all on, off).
     */
    public void draw(Mat4 pv, float camX, float camY, float camZ) {
        if (!glReady || !enabled || src == null) return;
        shader.use();
        shader.mat4("uPV", pv);
        shader.f3("uCam", camX, camY, camZ);
        shader.f2("uFade", drawDistance * 0.75f, drawDistance);
        shader.f1("uSnow", snow);
        shader.tex("uTex", 0, GL_TEXTURE_2D_ARRAY, tex);
        if (atlasTex != 0) shader.tex("uAtlas", 1, GL_TEXTURE_2D_ARRAY, atlasTex);
        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
        glDepthMask(false);
        glColorMaski(0, true, true, true, false);         // the surface colour only: its emission (alpha) is not a decal's to change
        glColorMaski(1, false, false, false, false);
        glColorMaski(2, false, false, false, false);
        glEnable(GL_POLYGON_OFFSET_FILL);
        glPolygonOffset(-1.5f, -3f);
        glDisable(GL_CULL_FACE);
        staticQuads = 0;
        int ccx = (int) Math.floor(camX / PATCH), ccz = (int) Math.floor(camZ / PATCH), R = (int) Math.ceil(drawDistance / PATCH);
        for (int dx = -R; dx <= R; dx++) for (int dz = -R; dz <= R; dz++) {
            int cx = ccx + dx, cz = ccz + dz;
            if (cx < 0 || cz < 0 || cx >= pw || cz >= pd) continue;
            Patch p = patches[cx * pd + cz];
            if (p == null || p.verts == 0) continue;
            glBindVertexArray(p.vao);
            glDrawArrays(GL_TRIANGLES, 0, p.verts);
            staticQuads += p.verts / 6;
        }
        // the marks of what has happened: packed fresh each frame, the dead left out and the dying faded
        dynBuf.clear();
        int n = 0;
        for (int i = 0; i < MAX_DYNAMIC; i++) {
            if (dynLife[i] <= 0) continue;
            float age = time - dynBorn[i];
            if (age >= dynLife[i] || age < 0) { dynLife[i] = 0; continue; }
            float fade = Math.min(1, (dynLife[i] - age) / (dynLife[i] * 0.25f));
            int o = i * 6 * VF;
            for (int v = 0; v < 6; v++) { dynBuf.put(dyn, o + v * VF, VF - 1); dynBuf.put(dyn[o + v * VF + VF - 1] * fade); }
            n++;
        }
        dynamicQuads = n;
        if (n > 0) {
            dynBuf.flip();
            glBindVertexArray(dynVao);
            glBindBuffer(GL_ARRAY_BUFFER, dynVbo);
            glBufferSubData(GL_ARRAY_BUFFER, 0, dynBuf);
            glDrawArrays(GL_TRIANGLES, 0, n * 6);
        }
        glBindVertexArray(0);
        glDisable(GL_POLYGON_OFFSET_FILL);
        glColorMaski(0, true, true, true, true);
        glColorMaski(1, true, true, true, true);
        glColorMaski(2, true, true, true, true);
        glDisable(GL_BLEND);
        glDepthMask(true);
        glEnable(GL_CULL_FACE);
    }

    private static final String VS = astratto.render.Shaders.source("decal.vert");

    private static final String FS = astratto.render.Shaders.source("decal.frag");
}
