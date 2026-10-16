package astratto.foliage;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL15.*;
import static org.lwjgl.opengl.GL20.*;
import static org.lwjgl.opengl.GL30.*;
import static org.lwjgl.opengl.GL31.*;
import static org.lwjgl.opengl.GL33.*;

import astratto.math.Mat4;
import astratto.render.Shader;
import java.nio.FloatBuffer;
import org.lwjgl.system.MemoryUtil;

/**
 * The Astratto foliage system (1.0): everything small that grows - grass, weeds, ferns, flowers, shrubs, creepers,
 * reeds, moss - over the whole of a world, without a single plant being placed by hand.
 *
 * <b>Placement is rules, not scatter.</b> A field of evenly random tufts looks like noise; real growth has reasons.
 * For every open-air cell the system asks the {@link Source} what the ground is, how fertile the place is and what
 * stands next to it, and applies rules to the answers:
 * <ul>
 * <li>a <i>density mask</i> - low-frequency noise - makes soil grow in drifts and bare patches, not evenly;</li>
 * <li>along the foot of a wall there is the strip nobody mows: grass and weeds tight against the foundation, moss on
 *     the shaded side, sometimes a creeper going up the brick;</li>
 * <li>a fence line catches seed: tall grass and stalks stand in it, cell after cell;</li>
 * <li>a corner between two walls is neglected ground, and neglected ground grows a bush;</li>
 * <li>paving grows only where it is broken, and then only weeds; water has reeds at its edge.</li>
 * </ul>
 * Every choice is drawn from a hash of the cell, so the same world always grows the same plants, and a region can be
 * thrown away and regrown at any time (see {@link #clear}).
 *
 * <b>Drawing.</b> Plants are voxel meshes ({@link FoliageMeshes}) drawn instanced into the G-buffer, so they are lit,
 * shadowed and fogged exactly as the world is; the larger ones cast shadows. The vertex shader does the living part:
 * every plant leans with the wind and rides two waves that cross the field (stronger in a storm - the wind is the
 * weather system's), bends away from anybody wading through it, sinks into the ground at the edge of the drawn
 * distance instead of popping, and whitens under snow.
 *
 * <b>Cost.</b> The world is cut into 16 m patches. A patch is grown once, when the camera first comes near (a few a
 * frame), and kept; what is drawn is whatever patches lie round the camera, with the small species thinned beyond
 * {@link #nearDistance}. One instanced draw per species variant.
 *
 * <b>2.0: native drawing.</b> Given the engine's renderer ({@link #initNative}), the meshes and instance lists go to it
 * instead ({@code LAYER_FOLIAGE}) and each frame is asked for with {@link #frame}; everything above stays the same.
 */
public final class Foliage {
    /** What the system asks of the world. All queries are cheap and may be called many times for the same cell. */
    public interface Source {
        int SOIL_LUSH = 1, SOIL_DRY = 2, SOIL_BARE = 3, GRAVEL = 4, PAVING = 5, PAVING_BROKEN = 6;
        int WALL = 1, FENCE = 2, WATER = 3, GROWTH = 4;
        int width();
        int depth();
        /** The y of the open cell that stands on the ground of an open-air column, or -1 where nothing can grow (roofed, off the map). */
        int surface(int x, int z);
        /** The class of ground a block is (one of the constants above; 0 when nothing grows on it). */
        int ground(int x, int y, int z);
        /** What stands in a cell beside a plant: a wall, a fence, water, growth the world itself holds there - or 0. */
        int obstacle(int x, int y, int z);
        /** Sky light of a cell, 0..15. */
        int skyLight(int x, int y, int z);
        /** How well things grow here, 0 (nothing) .. about 1.5 (wild). */
        float fertility(int x, int z);
        /** A number that changes whenever the 16 x 16 column (cx, cz) changes. */
        int version(int cx, int cz);
    }

    private static final int PATCH = 16, INST = 12, S = FoliageMeshes.SPECIES, V = FoliageMeshes.VARIANTS;

    private static final class Patch {
        int version;
        boolean built;
        final float[][] inst = new float[S * V][];
        final int[] count = new int[S * V];
        void add(int species, int variant, float x, float y, float z, float scale, float rot, float tint, float phase, float flex, float sky, float snow) {
            int k = species * V + variant;
            if (inst[k] == null) inst[k] = new float[INST * 16];
            if ((count[k] + 1) * INST > inst[k].length) inst[k] = java.util.Arrays.copyOf(inst[k], inst[k].length * 2);
            int o = count[k]++ * INST;
            float[] a = inst[k];
            a[o] = x; a[o + 1] = y; a[o + 2] = z; a[o + 3] = scale; a[o + 4] = rot; a[o + 5] = tint; a[o + 6] = phase; a[o + 7] = flex;
            a[o + 8] = sky; a[o + 9] = 0; a[o + 10] = snow; a[o + 11] = 0;
        }
    }

    private Source src;
    private Patch[] patches;
    private int pw, pd;
    // ---- quality
    /** How much of what the rules ask for is grown, 0..1.5; how far it is drawn; within what distance nothing is thinned. */
    public float density = 1f, drawDistance = 56f, nearDistance = 24f;
    public boolean enabled = true, shadows = true;
    // ---- wind and things that push plants aside (set by whoever owns the weather and the bodies)
    public float windX = 1.2f, windZ = 0.4f, gust, time, snow;
    private final float[] push = new float[8 * 4];
    private int pushN;
    // ---- cleared ground: {x, z, radius, until}
    private final float[][] cleared = new float[48][];
    private int clearedNext;
    // ---- GL
    private Shader gbuf, shadow;
    private final int[] vao = new int[S * V], vbo = new int[S * V], ibo = new int[S * V], verts = new int[S * V], drawn = new int[S * V], cap = new int[S * V];
    private FloatBuffer scratch = MemoryUtil.memAllocFloat(INST * 4096);
    private boolean glReady;
    // ---- 2.0: the engine's renderer, when it draws the plants instead (its mesh id per species variant)
    private astratto.bridge.NativeRenderer nat;
    private final int[] natMesh = new int[S * V];
    private int lastCX = Integer.MIN_VALUE, lastCZ, sinceCheck;
    private boolean dirty = true;
    /** Statistics for the debug line and the gates. */
    public int instances, patchesBuilt, patchesDrawn, triangles;

    public void attach(Source source) {
        src = source;
        pw = (source.width() + PATCH - 1) / PATCH; pd = (source.depth() + PATCH - 1) / PATCH;
        patches = new Patch[pw * pd];
        lastCX = Integer.MIN_VALUE; dirty = true; patchesBuilt = 0;
        java.util.Arrays.fill(cleared, null);
    }

    public Source source() { return src; }

    // ------------------------------------------------------------------ rules
    private static float hash(int x, int z, int salt) {
        int h = x * 0x9E3779B1 ^ Integer.rotateLeft(z * 0xC2B2AE3D, 11) ^ (salt * 0x27D4EB2F);
        h ^= h >>> 16; h *= 0x7FEB352D; h ^= h >>> 15; h *= 0x846CA68B; h ^= h >>> 16;
        return (h & 0xFFFFFF) / (float) 0x1000000;
    }

    private static float vnoise(float x, float z, int salt) {
        int ix = (int) Math.floor(x), iz = (int) Math.floor(z);
        float fx = x - ix, fz = z - iz;
        fx = fx * fx * (3 - 2 * fx); fz = fz * fz * (3 - 2 * fz);
        float a = hash(ix, iz, salt), b = hash(ix + 1, iz, salt), c = hash(ix, iz + 1, salt), d = hash(ix + 1, iz + 1, salt);
        return a + (b - a) * fx + (c - a) * fz + (a - b - c + d) * fx * fz;
    }

    /** The density mask: drifts of growth and bare patches, 0..1, the same for a place every time it is asked. */
    public static float mask(float x, float z) { return vnoise(x * 0.055f, z * 0.055f, 3) * 0.6f + vnoise(x * 0.16f, z * 0.16f, 5) * 0.3f + vnoise(x * 0.43f, z * 0.43f, 7) * 0.1f; }

    private static float sstep(float a, float b, float x) { float t = Math.max(0, Math.min(1, (x - a) / (b - a))); return t * t * (3 - 2 * t); }

    private boolean isCleared(float x, float z) {
        for (float[] c : cleared) if (c != null) { float dx = x - c[0], dz = z - c[1]; if (dx * dx + dz * dz < c[2] * c[2]) return true; }
        return false;
    }

    private void put(Patch p, int species, int x, int y, int z, float ox, float oz, int salt, float scaleMin, float scaleMax, float sky) {
        float px = x + 0.5f + ox, pz = z + 0.5f + oz;
        if (isCleared(px, pz)) return;
        int variant = (int) (hash(x, z, salt + 91) * V) % V;
        float scale = scaleMin + (scaleMax - scaleMin) * hash(x, z, salt + 17);
        p.add(species, variant, px, y, pz, scale, hash(x, z, salt + 23) * 6.2832f, hash(x, z, salt + 29), hash(x, z, salt + 31) * 6.2832f, 1f, sky, 1f);
    }

    private void build(Patch p, int pcx, int pcz) {
        java.util.Arrays.fill(p.count, 0);
        final int x0 = pcx * PATCH, z0 = pcz * PATCH;
        final float D = density;
        for (int lx = 0; lx < PATCH; lx++) for (int lz = 0; lz < PATCH; lz++) {
            int x = x0 + lx, z = z0 + lz;
            int y = src.surface(x, z);
            if (y < 0) continue;
            float fert = src.fertility(x, z) * D;
            if (fert <= 0.01f) continue;
            int here = src.obstacle(x, y, z);
            float sky = src.skyLight(x, y, z) / 15f;
            if (here == Source.GROWTH) {
                // the world itself says tall growth stands here (a meadow, a crop): it is grown thick, whatever the mask says
                for (int k = 0; k < 3; k++) put(p, k == 2 ? FoliageMeshes.GRASS : FoliageMeshes.TALL_GRASS, x, y, z, (hash(x, z, 40 + k) - 0.5f) * 0.8f, (hash(x, z, 50 + k) - 0.5f) * 0.8f, 60 + k, 0.85f, 1.25f, sky);
                continue;
            }
            if (here != 0) continue;
            int g = src.ground(x, y - 1, z);
            if (g == 0) continue;
            float m = mask(x, z), drift = sstep(0.34f, 0.68f, m), m2 = vnoise(x * 0.09f + 40, z * 0.09f - 17, 11);
            float h0 = hash(x, z, 1), h1 = hash(x, z, 2), h2 = hash(x, z, 3);
            boolean soil = g == Source.SOIL_LUSH || g == Source.SOIL_DRY || g == Source.SOIL_BARE;
            // ---- what stands round the cell
            int wN = src.obstacle(x, y, z - 1), wS = src.obstacle(x, y, z + 1), wW = src.obstacle(x - 1, y, z), wE = src.obstacle(x + 1, y, z);
            int walls = (wN == Source.WALL ? 1 : 0) + (wS == Source.WALL ? 1 : 0) + (wW == Source.WALL ? 1 : 0) + (wE == Source.WALL ? 1 : 0);
            boolean fence = wN == Source.FENCE || wS == Source.FENCE || wW == Source.FENCE || wE == Source.FENCE;
            boolean water = wN == Source.WATER || wS == Source.WATER || wW == Source.WATER || wE == Source.WATER;
            boolean corner = (wN == Source.WALL || wS == Source.WALL) && (wW == Source.WALL || wE == Source.WALL);
            // 1.1, where things belong. Flowers, ferns, mushrooms, bushes, saplings and tall stalks are things of a lawn, a
            // garden or a meadow: they grow where there is soil all round them, not on the single grass block of a verge with
            // a road on either side. And paving grows at its edges - against a wall, a fence, a kerb, the soil beside it -
            // where nothing treads: the middle of a street has, at most, a tuft of grass in a crack.
            int soilRound = 0;
            for (int ax = -1; ax <= 1; ax++) for (int az = -1; az <= 1; az++) {
                if (ax == 0 && az == 0) continue;
                int gg = src.ground(x + ax, y - 1, z + az);
                if ((gg == Source.SOIL_LUSH || gg == Source.SOIL_DRY || gg == Source.SOIL_BARE) && src.obstacle(x + ax, y, z + az) != Source.WALL) soilRound++;
            }
            boolean lawn = soil && soilRound == 8;
            // flowers want more than that: open soil two cells out in every direction (a three-wide verge beside a road has grass, not a flower bed)
            boolean meadow = lawn;
            for (int ax = -2; ax <= 2 && meadow; ax++) for (int az = -2; az <= 2 && meadow; az++) {
                if (Math.max(Math.abs(ax), Math.abs(az)) < 2) continue;
                int gg = src.ground(x + ax, y - 1, z + az);
                if (gg != Source.SOIL_LUSH && gg != Source.SOIL_DRY && gg != Source.SOIL_BARE || src.obstacle(x + ax, y, z + az) == Source.WALL) meadow = false;
            }
            boolean edge = walls > 0 || fence || wN != 0 || wS != 0 || wW != 0 || wE != 0 || soilRound > 0;

            // ---- open ground, by what it is
            switch (g) {
                case Source.SOIL_LUSH -> {
                    int n = Math.min(3, (int) (fert * (0.55f + 2.3f * drift) + h0 * 0.9f));
                    for (int k = 0; k < n; k++) {
                        int sp = drift > 0.72f && k == 0 && h1 < 0.6f ? FoliageMeshes.TALL_GRASS : FoliageMeshes.GRASS;
                        put(p, sp, x, y, z, (hash(x, z, 100 + k) - 0.5f) * 0.9f, (hash(x, z, 110 + k) - 0.5f) * 0.9f, 120 + k, 0.8f, 1.35f, sky);
                    }
                    if (meadow && m2 > 0.66f && h1 < 0.22f * fert) put(p, FoliageMeshes.FLOWER, x, y, z, (h2 - 0.5f) * 0.7f, (h0 - 0.5f) * 0.7f, 130, 0.85f, 1.25f, sky);
                    if (h2 < 0.05f * fert) put(p, FoliageMeshes.WEED, x, y, z, (h1 - 0.5f) * 0.6f, (h0 - 0.5f) * 0.6f, 131, 0.8f, 1.3f, sky);
                    if (lawn && h1 > 0.975f && fert > 0.5f) put(p, FoliageMeshes.STALK, x, y, z, 0, 0, 132, 0.8f, 1.3f, sky);
                    if (lawn && sky < 0.8f && h2 > 0.86f) put(p, FoliageMeshes.FERN, x, y, z, 0, 0, 133, 0.8f, 1.3f, sky);
                    if (lawn && sky < 0.86f && h0 > 0.985f) put(p, FoliageMeshes.MUSHROOM, x, y, z, (h1 - 0.5f) * 0.5f, (h2 - 0.5f) * 0.5f, 134, 0.9f, 1.4f, sky);
                    if (lawn && drift > 0.5f && hash(x, z, 4) < 0.007f * fert) put(p, FoliageMeshes.BUSH, x, y, z, 0, 0, 135, 0.8f, 1.35f, sky);
                    if (lawn && fert > 1.05f && hash(x, z, 5) < 0.0035f) put(p, FoliageMeshes.SAPLING, x, y, z, 0, 0, 136, 0.8f, 1.3f, sky);
                }
                case Source.SOIL_DRY -> {
                    int n = Math.min(2, (int) (fert * (0.4f + 1.5f * drift) + h0 * 0.7f));
                    for (int k = 0; k < n; k++) put(p, FoliageMeshes.DRY_GRASS, x, y, z, (hash(x, z, 100 + k) - 0.5f) * 0.9f, (hash(x, z, 110 + k) - 0.5f) * 0.9f, 120 + k, 0.8f, 1.3f, sky);
                    if (h2 < 0.04f * fert) put(p, FoliageMeshes.WEED, x, y, z, (h1 - 0.5f) * 0.6f, (h0 - 0.5f) * 0.6f, 131, 0.8f, 1.2f, sky);
                    if (hash(x, z, 4) < 0.011f) put(p, FoliageMeshes.DEAD_SHRUB, x, y, z, 0, 0, 135, 0.8f, 1.3f, sky);
                    if (lawn && h1 > 0.97f) put(p, FoliageMeshes.STALK, x, y, z, 0, 0, 132, 0.75f, 1.15f, sky);
                }
                case Source.SOIL_BARE -> {
                    if (h0 < 0.4f * drift * fert) put(p, h1 < 0.5f ? FoliageMeshes.DRY_GRASS : FoliageMeshes.GRASS, x, y, z, (h1 - 0.5f) * 0.8f, (h2 - 0.5f) * 0.8f, 120, 0.75f, 1.2f, sky);
                    if (h2 < 0.05f * fert) put(p, FoliageMeshes.WEED, x, y, z, (h1 - 0.5f) * 0.6f, (h0 - 0.5f) * 0.6f, 131, 0.8f, 1.2f, sky);
                    if (hash(x, z, 4) < 0.008f) put(p, FoliageMeshes.DEAD_SHRUB, x, y, z, 0, 0, 135, 0.7f, 1.2f, sky);
                }
                case Source.GRAVEL -> {
                    if (edge && h0 < 0.07f * fert) put(p, FoliageMeshes.WEED, x, y, z, (h1 - 0.5f) * 0.6f, (h2 - 0.5f) * 0.6f, 131, 0.7f, 1.1f, sky);
                    if (h1 < 0.12f * drift * fert) put(p, FoliageMeshes.DRY_GRASS, x, y, z, (h2 - 0.5f) * 0.8f, (h0 - 0.5f) * 0.8f, 120, 0.7f, 1.1f, sky);
                }
                case Source.PAVING_BROKEN -> {
                    // a crack in the open carriageway holds a little grass; the weeds are at its edges
                    if (h0 < (edge ? 0.30f : 0.10f) * Math.min(1.2f, fert + 0.3f)) put(p, FoliageMeshes.GRASS, x, y, z, (h1 - 0.5f) * 0.8f, (h2 - 0.5f) * 0.8f, 120, edge ? 0.6f : 0.45f, edge ? 1.0f : 0.7f, sky);
                    if (edge && h1 < 0.20f * Math.min(1.2f, fert + 0.3f)) put(p, FoliageMeshes.WEED, x, y, z, (h2 - 0.5f) * 0.7f, (h0 - 0.5f) * 0.7f, 131, 0.7f, 1.1f, sky);
                }
                case Source.PAVING -> { if (edge && h0 < 0.014f * fert) put(p, FoliageMeshes.GRASS, x, y, z, (h1 - 0.5f) * 0.8f, (h2 - 0.5f) * 0.8f, 120, 0.55f, 0.9f, sky); }
                default -> { }
            }

            // ---- the foot of a wall: the strip nobody mows
            float edgeF = Math.min(1.25f, fert + 0.3f) * (soil ? 0.85f : g == Source.GRAVEL ? 0.5f : g == Source.PAVING_BROKEN ? 0.4f : 0.2f);
            if (walls > 0) for (int side = 0; side < 4; side++) {
                int ob = side == 0 ? wN : side == 1 ? wE : side == 2 ? wS : wW;
                if (ob != Source.WALL) continue;
                float ox = side == 1 ? 0.36f : side == 3 ? -0.36f : 0, oz = side == 2 ? 0.36f : side == 0 ? -0.36f : 0;
                float along = (hash(x, z, 200 + side) - 0.5f) * 0.7f;
                float hs = hash(x, z, 210 + side), hk = hash(x, z, 220 + side);
                if (hs < edgeF) {
                    // the north face of a wall is the damp one: moss there, grass and weeds elsewhere
                    boolean damp = side == 2 || sky < 0.75f;
                    int sp = hk < 0.14f && damp ? FoliageMeshes.MOSS : hk < 0.30f ? FoliageMeshes.TALL_GRASS : hk < 0.44f ? FoliageMeshes.WEED : hk < 0.5f && damp ? FoliageMeshes.FERN : soil ? FoliageMeshes.GRASS : FoliageMeshes.DRY_GRASS;
                    put(p, sp, x, y, z, ox + (side % 2 == 0 ? along : 0), oz + (side % 2 == 1 ? along : 0), 230 + side, 0.8f, 1.3f, sky);
                    if (soil && hk > 0.6f) put(p, FoliageMeshes.GRASS, x, y, z, ox * 0.6f + (side % 2 == 0 ? -along : 0), oz * 0.6f + (side % 2 == 1 ? -along : 0), 240 + side, 0.8f, 1.2f, sky);
                }
                // a creeper: rooted in soil at the foot of the wall, flat against it
                if (soil && hash(x, z, 250 + side) < 0.085f * fert && src.obstacle(x + (side == 1 ? 1 : side == 3 ? -1 : 0), y + 1, z + (side == 2 ? 1 : side == 0 ? -1 : 0)) == Source.WALL) {
                    float px = x + 0.5f + (side == 1 ? 0.47f : side == 3 ? -0.47f : along), pz = z + 0.5f + (side == 2 ? 0.47f : side == 0 ? -0.47f : along);
                    float rot = side == 0 ? 0 : side == 2 ? 3.14159f : side == 3 ? 1.5708f : -1.5708f;
                    if (!isCleared(px, pz)) p.add(FoliageMeshes.IVY, (int) (hash(x, z, 260 + side) * V) % V, px, y, pz, 0.85f + hash(x, z, 270 + side) * 0.5f, rot, hash(x, z, 280), 0, 1f, sky, 0.3f);
                }
            }
            // ---- a fence line catches seed
            if (fence) for (int side = 0; side < 4; side++) {
                int ob = side == 0 ? wN : side == 1 ? wE : side == 2 ? wS : wW;
                if (ob != Source.FENCE) continue;
                float hs = hash(x, z, 300 + side), hk = hash(x, z, 310 + side);
                if (hs > (soil ? 0.82f : 0.42f) * Math.min(1.2f, fert + 0.35f)) continue;
                float ox = side == 1 ? 0.42f : side == 3 ? -0.42f : (hk - 0.5f) * 0.7f, oz = side == 2 ? 0.42f : side == 0 ? -0.42f : (hk - 0.5f) * 0.7f;
                int sp = hk < 0.45f ? FoliageMeshes.TALL_GRASS : hk < 0.75f ? FoliageMeshes.STALK : FoliageMeshes.WEED;
                put(p, sp, x, y, z, ox, oz, 320 + side, 0.8f, 1.3f, sky);
            }
            // ---- a neglected corner grows a bush
            if (corner) {
                float hc = hash(x, z, 400);
                float ox = (wE == Source.WALL ? 0.12f : -0.12f), oz = (wS == Source.WALL ? 0.12f : -0.12f);
                if (hc < (soil ? 0.42f : 0.12f) * Math.min(1.2f, fert + 0.3f)) put(p, FoliageMeshes.BUSH, x, y, z, ox, oz, 401, 0.6f, 0.95f, sky);
                else if (hc > 0.9f) put(p, FoliageMeshes.DEAD_SHRUB, x, y, z, ox, oz, 402, 0.7f, 1.1f, sky);
            }
            if (water && hash(x, z, 500) < 0.6f) put(p, FoliageMeshes.REED, x, y, z, (h1 - 0.5f) * 0.5f, (h2 - 0.5f) * 0.5f, 501, 0.8f, 1.25f, sky);
        }
        p.built = true;
        patchesBuilt++;
    }

    /** Ground laid bare (a blast, a fire, a vehicle spinning its wheels): nothing grows within radius of the point until the patch is next regrown. */
    public void clear(float x, float z, float radius) {
        if (src == null) return;
        cleared[clearedNext] = new float[]{x, z, radius};
        clearedNext = (clearedNext + 1) % cleared.length;
        int c0 = Math.max(0, (int) ((x - radius) / PATCH)), c1 = Math.min(pw - 1, (int) ((x + radius) / PATCH)), d0 = Math.max(0, (int) ((z - radius) / PATCH)), d1 = Math.min(pd - 1, (int) ((z + radius) / PATCH));
        for (int cx = c0; cx <= c1; cx++) for (int cz = d0; cz <= d1; cz++) { Patch p = patches[cx * pd + cz]; if (p != null) p.built = false; }
        dirty = true;
    }

    /** Somebody or something that pushes plants aside this frame: where its feet are and how wide it is. Call {@link #clearPushers} first. */
    public void pusher(float x, float y, float z, float radius) { if (pushN < 8) { int o = pushN++ * 4; push[o] = x; push[o + 1] = z; push[o + 2] = radius; push[o + 3] = y; } }
    public void clearPushers() { pushN = 0; }

    /** How many plants of a species stand in the patches grown so far within r of a point (gates, debugging). */
    public int countNear(int species, float x, float z, float r) {
        if (src == null) return 0;
        int n = 0;
        int c0 = Math.max(0, (int) ((x - r) / PATCH)), c1 = Math.min(pw - 1, (int) ((x + r) / PATCH)), d0 = Math.max(0, (int) ((z - r) / PATCH)), d1 = Math.min(pd - 1, (int) ((z + r) / PATCH));
        for (int cx = c0; cx <= c1; cx++) for (int cz = d0; cz <= d1; cz++) {
            Patch p = patches[cx * pd + cz];
            if (p == null || !p.built) continue;
            for (int v = 0; v < V; v++) { int k = species * V + v; for (int i = 0; i < p.count[k]; i++) { float dx = p.inst[k][i * INST] - x, dz = p.inst[k][i * INST + 2] - z; if (dx * dx + dz * dz <= r * r) n++; } }
        }
        return n;
    }

    /** Grows the patch a point is in now (without a window: gates, tools). */
    public void growAt(float x, float z) {
        if (src == null) return;
        int cx = (int) (x / PATCH), cz = (int) (z / PATCH);
        if (cx < 0 || cz < 0 || cx >= pw || cz >= pd) return;
        Patch p = patches[cx * pd + cz];
        if (p == null) patches[cx * pd + cz] = p = new Patch();
        p.version = src.version(cx, cz);
        build(p, cx, cz);
    }

    // ------------------------------------------------------------------ per frame
    /** Grows what is missing round the camera (a few patches a frame, nearest first) and rebuilds the instance lists when the set changes. */
    /**
     * 1.0: which way the camera is looking over the ground. What is drawn is what is in front of it: patches well
     * behind the camera are left out of the instance lists (they were all drawn, every frame, in every pass - more than
     * half of the work for nothing). The lists are remade when the look has swung far enough for that to show.
     */
    public void view(float fx, float fz) {
        float l = (float) Math.sqrt(fx * fx + fz * fz);
        if (l < 0.2f) { if (viewL > 0) { viewL = 0; dirty = true; } return; }        // looking straight down or up: everything round the camera
        fx /= l; fz /= l;
        if (viewL == 0 || fx * upX + fz * upZ < VIEW_REMAKE) dirty = true;
        viewX = fx; viewZ = fz; viewL = 1;
    }
    private float viewX, viewZ, viewL, upX, upZ;
    /** The cosine of how far the look may swing before the lists are remade, and of the half-angle kept in front of the camera when they are (wide enough to cover that swing and the widest field of view). */
    public static float VIEW_REMAKE = 0.966f, VIEW_KEEP = 0.17f;
    public int patchesCulled;

    public void update(float camX, float camZ, int budget) {
        if (src == null || !enabled) return;
        int ccx = (int) Math.floor(camX / PATCH), ccz = (int) Math.floor(camZ / PATCH);
        int R = (int) Math.ceil(drawDistance / PATCH);
        boolean moved = ccx != lastCX || ccz != lastCZ;
        boolean check = ++sinceCheck >= 20;
        if (check) sinceCheck = 0;
        int built = 0;
        // nearest rings first
        for (int ring = 0; ring <= R && built < budget; ring++) for (int dx = -ring; dx <= ring && built < budget; dx++) for (int dz = -ring; dz <= ring && built < budget; dz++) {
            if (Math.max(Math.abs(dx), Math.abs(dz)) != ring) continue;
            int cx = ccx + dx, cz = ccz + dz;
            if (cx < 0 || cz < 0 || cx >= pw || cz >= pd) continue;
            Patch p = patches[cx * pd + cz];
            if (p == null) patches[cx * pd + cz] = p = new Patch();
            if (p.built && check) { int v = src.version(cx, cz); if (v != p.version) p.built = false; }
            if (!p.built) { p.version = src.version(cx, cz); build(p, cx, cz); built++; dirty = true; }
        }
        if (moved) { lastCX = ccx; lastCZ = ccz; dirty = true; }
        if (dirty && (glReady || nat != null)) { upload(camX, camZ, ccx, ccz, R); dirty = false; }
    }

    private void upload(float camX, float camZ, int ccx, int ccz, int R) {
        instances = 0; patchesDrawn = 0; triangles = 0; patchesCulled = 0;
        upX = viewX; upZ = viewZ;
        for (int k = 0; k < S * V; k++) {
            scratch.clear();
            int species = k / V, n = 0;
            for (int dx = -R; dx <= R; dx++) for (int dz = -R; dz <= R; dz++) {
                int cx = ccx + dx, cz = ccz + dz;
                if (cx < 0 || cz < 0 || cx >= pw || cz >= pd) continue;
                Patch p = patches[cx * pd + cz];
                if (p == null || !p.built || p.count[k] == 0) continue;
                // behind the camera, and not one of the patches round its feet: not drawn
                if (viewL > 0 && Math.max(Math.abs(dx), Math.abs(dz)) > 1) {
                    float ox = (cx + 0.5f) * PATCH - camX, oz = (cz + 0.5f) * PATCH - camZ, ol = (float) Math.sqrt(ox * ox + oz * oz);
                    if (ox * viewX + oz * viewZ < ol * VIEW_KEEP - PATCH * 0.75f) { if (k == 0) patchesCulled++; continue; }
                }
                // a patch wholly past the near distance keeps one small plant in two (always the same ones: no flicker as you walk)
                float pdist = Math.max(Math.abs((cx + 0.5f) * PATCH - (ccx + 0.5f) * PATCH), Math.abs((cz + 0.5f) * PATCH - (ccz + 0.5f) * PATCH)) - PATCH;
                boolean thin = FoliageMeshes.SMALL[species] && pdist > nearDistance;
                int cnt = p.count[k];
                if (scratch.remaining() < cnt * INST) { FloatBuffer nb = MemoryUtil.memAllocFloat(Math.max(scratch.capacity() * 2, scratch.position() + cnt * INST)); scratch.flip(); nb.put(scratch); MemoryUtil.memFree(scratch); scratch = nb; }
                if (!thin) { scratch.put(p.inst[k], 0, cnt * INST); n += cnt; }
                else for (int i = 0; i < cnt; i += 3) { scratch.put(p.inst[k], i * INST, INST); n++; }
                if (k == 0) patchesDrawn++;
            }
            drawn[k] = n;
            instances += n;
            triangles += n * verts[k] / 3;
            if (nat != null) {
                scratch.flip();
                nat.instances(natMesh[k], scratch, n, shadows && FoliageMeshes.SHADOW[species] ? astratto.bridge.AstrattoNative.INSTANCES_SHADOW : 0);
                continue;
            }
            if (n == 0) continue;
            scratch.flip();
            glBindBuffer(GL_ARRAY_BUFFER, ibo[k]);
            if (n > cap[k]) { cap[k] = Math.max(n, cap[k] * 2); glBufferData(GL_ARRAY_BUFFER, (long) cap[k] * INST * 4, GL_DYNAMIC_DRAW); }
            glBufferSubData(GL_ARRAY_BUFFER, 0, scratch);
        }
        glBindBuffer(GL_ARRAY_BUFFER, 0);
    }

    // ------------------------------------------------------------------ 2.0: the engine's renderer
    /** Hands every species variant's mesh to the engine's renderer; from now on it draws the plants (see {@link #frame}). */
    public void initNative(astratto.bridge.NativeRenderer r) {
        nat = r;
        for (int k = 0; k < S * V; k++) {
            float[] m = FoliageMeshes.mesh(k / V, k % V);
            verts[k] = m.length / FoliageMeshes.FLOATS;
            natMesh[k] = r.layerMesh(astratto.bridge.AstrattoNative.LAYER_FOLIAGE, m, verts[k]);
        }
        dirty = true;
    }

    public boolean isNative() { return nat != null; }

    /** This frame's foliage, for the engine's renderer: the wind, the fade, the snow and whatever is wading through. */
    public void frame(astratto.bridge.RenderCommands c) {
        if (nat == null || !enabled || src == null || instances == 0) return;
        c.foliage(windX, windZ, time, gust, drawDistance * 0.78f, drawDistance, snow, push, pushN);
    }

    // ------------------------------------------------------------------ GL
    public void init() {
        gbuf = new Shader(VS, FS_GBUF);
        shadow = new Shader(VS, FS_SHADOW);
        for (int k = 0; k < S * V; k++) {
            float[] m = FoliageMeshes.mesh(k / V, k % V);
            verts[k] = m.length / FoliageMeshes.FLOATS;
            vao[k] = glGenVertexArrays();
            vbo[k] = glGenBuffers();
            ibo[k] = glGenBuffers();
            glBindVertexArray(vao[k]);
            glBindBuffer(GL_ARRAY_BUFFER, vbo[k]);
            FloatBuffer fb = MemoryUtil.memAllocFloat(Math.max(1, m.length));
            fb.put(m).flip();
            glBufferData(GL_ARRAY_BUFFER, fb, GL_STATIC_DRAW);
            MemoryUtil.memFree(fb);
            int stride = FoliageMeshes.FLOATS * 4;
            glVertexAttribPointer(0, 3, GL_FLOAT, false, stride, 0); glEnableVertexAttribArray(0);
            glVertexAttribPointer(1, 3, GL_FLOAT, false, stride, 12); glEnableVertexAttribArray(1);
            glVertexAttribPointer(2, 4, GL_FLOAT, false, stride, 24); glEnableVertexAttribArray(2);
            glBindBuffer(GL_ARRAY_BUFFER, ibo[k]);
            cap[k] = 256;
            glBufferData(GL_ARRAY_BUFFER, (long) cap[k] * INST * 4, GL_DYNAMIC_DRAW);
            for (int a = 0; a < 3; a++) { glVertexAttribPointer(3 + a, 4, GL_FLOAT, false, INST * 4, a * 16L); glEnableVertexAttribArray(3 + a); glVertexAttribDivisor(3 + a, 1); }
        }
        glBindVertexArray(0);
        glBindBuffer(GL_ARRAY_BUFFER, 0);
        glReady = true;
        dirty = true;
    }

    private void uniforms(Shader s, Mat4 pv, float camX, float camY, float camZ) {
        s.use();
        s.mat4("uPV", pv);
        s.f4("uWind", windX, windZ, time, gust);
        s.f3("uCam", camX, camY, camZ);
        s.f2("uFade", drawDistance * 0.78f, drawDistance);
        s.f1("uSnow", snow);
        s.i1("uPushN", pushN);
        glUniform4fv(glGetUniformLocation(s.id, "uPush"), push);
    }

    /** Into the G-buffer (called by the renderer between the world and the models). */
    public void drawGBuffer(Mat4 pv, float camX, float camY, float camZ) {
        if (!glReady || !enabled || src == null || instances == 0) return;
        uniforms(gbuf, pv, camX, camY, camZ);
        glEnable(GL_CULL_FACE);
        for (int k = 0; k < S * V; k++) {
            if (drawn[k] == 0 || verts[k] == 0) continue;
            glBindVertexArray(vao[k]);
            glDrawArraysInstanced(GL_TRIANGLES, 0, verts[k], drawn[k]);
        }
        glBindVertexArray(0);
    }

    /** Into a shadow cascade: only what is big enough to throw a shadow anyone would miss. */
    public void drawShadow(Mat4 lightPV, float camX, float camY, float camZ) {
        if (!glReady || !enabled || !shadows || src == null || instances == 0) return;
        uniforms(shadow, lightPV, camX, camY, camZ);
        for (int k = 0; k < S * V; k++) {
            if (!FoliageMeshes.SHADOW[k / V] || drawn[k] == 0 || verts[k] == 0) continue;
            glBindVertexArray(vao[k]);
            glDrawArraysInstanced(GL_TRIANGLES, 0, verts[k], drawn[k]);
        }
        glBindVertexArray(0);
    }

    public void dispose() {
        if (nat != null) { for (int k = 0; k < S * V; k++) if (natMesh[k] != 0) nat.release(astratto.bridge.AstrattoNative.RES_MESH, natMesh[k]); nat = null; }
        if (!glReady) return;
        glDeleteVertexArrays(vao); glDeleteBuffers(vbo); glDeleteBuffers(ibo);
        MemoryUtil.memFree(scratch);
        glReady = false;
    }

    private static final String VS = astratto.render.Shaders.source("foliage.vert");

    private static final String FS_GBUF = astratto.render.Shaders.source("foliage_gbuffer.frag");

    private static final String FS_SHADOW = astratto.render.Shaders.source("foliage_shadow.frag");
}
