package astratto.foliage;

import java.util.Random;

/**
 * The plants of the Astratto foliage system, grown as voxels (1.0). Nothing here is drawn by hand or loaded from a
 * file: a species is a small routine that grows a plant into a voxel grid from a seed - blades that lean, fronds that
 * arch and droop, a shrub that is a shell of leaves round a few stems - and every species is grown several times over,
 * so a verge is not one tuft repeated. The grid is then turned into a mesh of its outside faces only, each vertex
 * carrying its colour (darker toward the root and inside the plant, so a clump has depth without a light being asked
 * for it) and how much it gives to the wind (nothing at the ground, most at the tip).
 *
 * Vertex layout, ten floats: position (metres, the plant's root at the origin), normal, colour, sway.
 */
public final class FoliageMeshes {
    private FoliageMeshes() {}

    public static final int FLOATS = 10;
    /** The species. Their order is the order of the instance lists. */
    public static final int GRASS = 0, TALL_GRASS = 1, WEED = 2, STALK = 3, BUSH = 4, FERN = 5, FLOWER = 6, DEAD_SHRUB = 7, REED = 8, IVY = 9, MOSS = 10, SAPLING = 11, MUSHROOM = 12, DRY_GRASS = 13, SPECIES = 14;
    public static final String[] NAMES = {"grass", "tall grass", "weed", "stalk", "bush", "fern", "flower", "dead shrub", "reed", "ivy", "moss", "sapling", "mushroom", "dry grass"};
    public static final int VARIANTS = 4;
    /** Which species throw a shadow worth drawing, and which are small enough to thin out at a distance. */
    public static final boolean[] SHADOW = {false, false, false, false, true, false, false, true, false, false, false, true, false, false};
    public static final boolean[] SMALL = {true, true, true, true, false, true, true, false, false, false, true, false, true, true};

    /** A plant being grown: a grid of coloured voxels of one size, the root at the middle of the bottom layer. */
    static final class Grid {
        final int w, h, d;
        final float voxel;
        final int[] c;
        /** How far the wind bends the tip, in metres per unit of wind (stiff wood very little, a grass blade a lot). */
        float flex = 1f;
        Grid(int w, int h, int d, float voxel) { this.w = w; this.h = h; this.d = d; this.voxel = voxel; c = new int[w * h * d]; }
        void set(int x, int y, int z, int rgb) { if (x >= 0 && y >= 0 && z >= 0 && x < w && y < h && z < d) c[(x * d + z) * h + y] = 0xFF000000 | rgb; }
        int get(int x, int y, int z) { return x < 0 || y < 0 || z < 0 || x >= w || y >= h || z >= d ? 0 : c[(x * d + z) * h + y]; }
        boolean full(int x, int y, int z) { return get(x, y, z) != 0; }
    }

    private static int shade(int rgb, float k) {
        int r = Math.max(0, Math.min(255, Math.round(((rgb >> 16) & 255) * k))), g = Math.max(0, Math.min(255, Math.round(((rgb >> 8) & 255) * k))), b = Math.max(0, Math.min(255, Math.round((rgb & 255) * k)));
        return r << 16 | g << 8 | b;
    }

    private static int mix(int a, int b, float t) {
        int r = Math.round(((a >> 16) & 255) * (1 - t) + ((b >> 16) & 255) * t), g = Math.round(((a >> 8) & 255) * (1 - t) + ((b >> 8) & 255) * t), bl = Math.round((a & 255) * (1 - t) + (b & 255) * t);
        return r << 16 | g << 8 | bl;
    }

    // palettes: living greens, the yellowing of a neglected verge, wood, and the few colours flowers come in
    private static final int G_DEEP = 0x2F5A23, G_MID = 0x457A2C, G_LIGHT = 0x6A9A3A, G_YELLOW = 0x8E9A40, G_DRY = 0x9A8C52, G_STRAW = 0xB5A56A,
            WOOD = 0x4A3A28, WOOD_GREY = 0x5E564A, FERN_G = 0x2E6630, MOSS_G = 0x3D6B2A, IVY_G = 0x27522A;
    private static final int[] PETALS = {0xE8E4D8, 0xE2C83A, 0x8E5FB8, 0xC43A30, 0xE0A8B8};

    /** A blade of grass: a column one voxel thick that leans further the higher it goes. */
    private static void blade(Grid g, Random r, float x, float z, int height, float leanX, float leanZ, int base, int tip) {
        for (int y = 0; y < height; y++) {
            float k = y / (float) Math.max(1, height - 1);
            int px = Math.round(x + leanX * k * k * height * 0.5f), pz = Math.round(z + leanZ * k * k * height * 0.5f);
            g.set(px, y, pz, shade(mix(base, tip, k), 0.62f + 0.38f * k + (r.nextFloat() - 0.5f) * 0.08f));
        }
    }

    static Grid grow(int species, long seed) {
        Random r = new Random(seed * 7919L + species * 104729L + 17);
        switch (species) {
            case GRASS: case DRY_GRASS: {
                Grid g = new Grid(13, 9, 13, 0.045f);
                g.flex = 0.55f;
                int n = 8 + r.nextInt(6);
                int base = species == GRASS ? G_DEEP : 0x6E6A3A, tip = species == GRASS ? (r.nextInt(3) == 0 ? G_YELLOW : G_LIGHT) : G_STRAW;
                for (int i = 0; i < n; i++) {
                    float a = r.nextFloat() * 6.2832f, rad = r.nextFloat() * 3.6f;
                    blade(g, r, 6 + (float) Math.cos(a) * rad, 6 + (float) Math.sin(a) * rad, 3 + r.nextInt(5), (float) Math.cos(a) * 0.5f, (float) Math.sin(a) * 0.5f, base, tip);
                }
                return g;
            }
            case TALL_GRASS: {
                Grid g = new Grid(15, 22, 15, 0.045f);
                g.flex = 1.0f;
                int n = 7 + r.nextInt(5);
                for (int i = 0; i < n; i++) {
                    float a = r.nextFloat() * 6.2832f, rad = r.nextFloat() * 3.2f;
                    int hgt = 10 + r.nextInt(11);
                    float x = 7 + (float) Math.cos(a) * rad, z = 7 + (float) Math.sin(a) * rad;
                    blade(g, r, x, z, hgt, (float) Math.cos(a) * 0.32f, (float) Math.sin(a) * 0.32f, G_DEEP, r.nextInt(4) == 0 ? G_DRY : G_LIGHT);
                    // one blade in three has gone to seed: a paler head on top
                    if (r.nextInt(3) == 0) { int px = Math.round(x + (float) Math.cos(a) * 0.32f * hgt * 0.5f), pz = Math.round(z + (float) Math.sin(a) * 0.32f * hgt * 0.5f); g.set(px, hgt, pz, G_STRAW); g.set(px, hgt + 1, pz, shade(G_STRAW, 1.1f)); }
                }
                return g;
            }
            case WEED: {
                // a rosette: broad leaves lying out from the middle (1.1: no flower head - a weed grows in a cracked street,
                // and with its yellow head on it read as a flower planted in the road; flowers are the FLOWER species' and a lawn's)
                Grid g = new Grid(13, 10, 13, 0.05f);
                g.flex = 0.25f;
                int leaves = 5 + r.nextInt(3);
                float a0 = r.nextFloat() * 6.2832f;
                for (int i = 0; i < leaves; i++) {
                    float a = a0 + i * 6.2832f / leaves + (r.nextFloat() - 0.5f) * 0.4f;
                    int len = 3 + r.nextInt(3);
                    for (int k = 1; k <= len; k++) {
                        int px = Math.round(6 + (float) Math.cos(a) * k), pz = Math.round(6 + (float) Math.sin(a) * k), py = k >= len - 1 ? 0 : 1;
                        int col = shade(k < 2 ? G_DEEP : G_MID, 0.85f + r.nextFloat() * 0.2f);
                        g.set(px, py, pz, col);
                        if (k > 1 && k < len) { g.set(px + (Math.abs(Math.cos(a)) < 0.7 ? 1 : 0), py, pz + (Math.abs(Math.cos(a)) < 0.7 ? 0 : 1), shade(col, 0.92f)); }
                    }
                }
                g.set(6, 0, 6, G_DEEP); g.set(6, 1, 6, G_DEEP);
                r.nextInt(2);
                return g;
            }
            case STALK: {
                // nettle, dock, thistle: an upright stem with leaves in pairs, each pair turned from the last
                Grid g = new Grid(11, 24, 11, 0.05f);
                g.flex = 0.6f;
                int stems = 1 + r.nextInt(3);
                for (int s = 0; s < stems; s++) {
                    int x = 5 + (s == 0 ? 0 : r.nextInt(5) - 2), z = 5 + (s == 0 ? 0 : r.nextInt(5) - 2), hgt = 10 + r.nextInt(12);
                    for (int y = 0; y < hgt; y++) {
                        g.set(x, y, z, shade(G_MID, 0.6f + 0.3f * y / hgt));
                        if (y > 1 && y % 3 == (s & 1)) {
                            boolean alongX = (y / 3 + s) % 2 == 0;
                            int reach = Math.max(1, 3 - y * 3 / hgt);
                            for (int k = 1; k <= reach; k++) { int col = shade(G_MID, 0.8f + r.nextFloat() * 0.25f); if (alongX) { g.set(x + k, y, z, col); g.set(x - k, y, z, col); } else { g.set(x, y, z + k, col); g.set(x, y, z - k, col); } }
                        }
                    }
                    if (r.nextInt(3) == 0) { g.set(x, hgt, z, 0x8A5A9A); g.set(x, hgt + 1, z, 0xA070B0); }
                }
                return g;
            }
            case BUSH: {
                // a shell of leaves round a handful of stems: hollow inside, ragged outside, heavier on one side
                Grid g = new Grid(20, 18, 20, 0.085f);
                g.flex = 0.22f;
                float rx = 6.5f + r.nextFloat() * 2.5f, ry = 5.5f + r.nextFloat() * 2.5f, rz = 6.5f + r.nextFloat() * 2.5f, cy = ry * 0.85f + 1;
                float ox = (r.nextFloat() - 0.5f) * 2, oz = (r.nextFloat() - 0.5f) * 2;
                int tone = r.nextInt(3);
                for (int x = 0; x < g.w; x++) for (int y = 0; y < g.h; y++) for (int z = 0; z < g.d; z++) {
                    float dx = (x - 10 - ox) / rx, dy = (y - cy) / ry, dz = (z - 10 - oz) / rz;
                    float d = dx * dx + dy * dy + dz * dz;
                    // lumps: the surface is pushed in and out by a cheap three-wave noise
                    float lump = 0.16f * (float) (Math.sin(x * 0.9 + seed) * Math.cos(z * 0.8 + seed * 0.3) + Math.sin(y * 1.1 + seed * 0.7));
                    if (d > 1 + lump || d < 0.5f + lump) continue;
                    if (r.nextFloat() < 0.24f) continue;                                    // gaps: light gets through a shrub
                    int col = tone == 0 ? G_MID : tone == 1 ? G_DEEP : 0x3F6A2E;
                    if (r.nextInt(7) == 0) col = G_LIGHT;
                    g.set(x, y, z, shade(col, 0.55f + 0.5f * Math.min(1, y / (float) (cy + ry)) + (r.nextFloat() - 0.5f) * 0.12f));
                }
                for (int s = 0; s < 4; s++) { float a = r.nextFloat() * 6.2832f; for (int y = 0; y < cy; y++) g.set(Math.round(10 + (float) Math.cos(a) * y * 0.3f), y, Math.round(10 + (float) Math.sin(a) * y * 0.3f), WOOD); }
                return g;
            }
            case FERN: {
                // fronds that rise, arch over and droop, each a spine with leaflets that shorten toward the tip
                Grid g = new Grid(21, 12, 21, 0.05f);
                g.flex = 0.45f;
                int fronds = 5 + r.nextInt(3);
                float a0 = r.nextFloat() * 6.2832f;
                for (int i = 0; i < fronds; i++) {
                    float a = a0 + i * 6.2832f / fronds + (r.nextFloat() - 0.5f) * 0.5f, ca = (float) Math.cos(a), sa = (float) Math.sin(a);
                    int len = 7 + r.nextInt(3);
                    for (int k = 1; k <= len; k++) {
                        float t = k / (float) len;
                        int y = Math.round((float) Math.sin(t * 2.4f) * 5.5f), px = Math.round(10 + ca * k), pz = Math.round(10 + sa * k);
                        int col = shade(FERN_G, 0.7f + 0.4f * t + (r.nextFloat() - 0.5f) * 0.1f);
                        g.set(px, y, pz, col);
                        int wdt = t < 0.25f ? 1 : t < 0.75f ? 2 : 1;
                        for (int s = 1; s <= wdt && k % 2 == 0; s++) { g.set(Math.round(px - sa * s), y, Math.round(pz + ca * s), shade(col, 1.08f)); g.set(Math.round(px + sa * s), y, Math.round(pz - ca * s), shade(col, 1.08f)); }
                    }
                }
                g.set(10, 0, 10, shade(FERN_G, 0.6f));
                return g;
            }
            case FLOWER: {
                Grid g = new Grid(11, 12, 11, 0.045f);
                g.flex = 0.7f;
                int n = 3 + r.nextInt(4), petal = PETALS[r.nextInt(PETALS.length)];
                for (int i = 0; i < n; i++) {
                    int x = 2 + r.nextInt(7), z = 2 + r.nextInt(7), hgt = 4 + r.nextInt(6);
                    for (int y = 0; y < hgt; y++) g.set(x, y, z, shade(G_MID, 0.65f + 0.3f * y / hgt));
                    if (hgt > 5) g.set(x + (r.nextBoolean() ? 1 : -1), hgt / 2, z, G_MID);
                    g.set(x, hgt, z, 0xD8B030);
                    g.set(x + 1, hgt, z, petal); g.set(x - 1, hgt, z, petal); g.set(x, hgt, z + 1, petal); g.set(x, hgt, z - 1, petal);
                }
                return g;
            }
            case DEAD_SHRUB: {
                // bare wood: a few stems that fork, and fork again
                Grid g = new Grid(19, 20, 19, 0.06f);
                g.flex = 0.12f;
                int stems = 3 + r.nextInt(3);
                for (int s = 0; s < stems; s++) twig(g, r, 9, 0, 9, r.nextFloat() * 6.2832f, 0.9f + r.nextFloat() * 0.3f, 9 + r.nextInt(6), 2);
                return g;
            }
            case REED: {
                Grid g = new Grid(13, 30, 13, 0.05f);
                g.flex = 0.8f;
                int n = 6 + r.nextInt(6);
                for (int i = 0; i < n; i++) {
                    int x = 2 + r.nextInt(9), z = 2 + r.nextInt(9), hgt = 16 + r.nextInt(12);
                    float lx = (r.nextFloat() - 0.5f) * 0.25f, lz = (r.nextFloat() - 0.5f) * 0.25f;
                    blade(g, r, x, z, hgt, lx, lz, 0x4A6A30, 0x8A9A50);
                    if (r.nextInt(2) == 0) for (int y = hgt - 4; y < hgt - 1; y++) { int px = Math.round(x + lx * hgt * 0.4f), pz = Math.round(z + lz * hgt * 0.4f); g.set(px, y, pz, 0x4A3220); g.set(px + 1, y, pz, 0x5A3E28); }
                }
                return g;
            }
            case IVY: {
                // a creeper on a wall: one voxel thick against the plane z = 0, wider low down, with a few runners going higher
                Grid g = new Grid(20, 30, 3, 0.055f);
                g.flex = 0.05f;
                int runners = 3 + r.nextInt(3);
                for (int k = 0; k < runners; k++) {
                    float x = 3 + r.nextFloat() * 14;
                    int top = 12 + r.nextInt(17);
                    for (int y = 0; y < top; y++) {
                        x += (r.nextFloat() - 0.5f) * 1.6f;
                        int px = Math.round(x);
                        g.set(px, y, 0, shade(WOOD, 0.9f));
                        int spread = y < top * 0.6f ? 2 : 1;
                        for (int dx = -spread; dx <= spread; dx++) for (int dy = -1; dy <= 1; dy++) if (r.nextFloat() < 0.62f) g.set(px + dx, y + dy, r.nextInt(4) == 0 ? 1 : 0, shade(r.nextInt(5) == 0 ? G_MID : IVY_G, 0.75f + r.nextFloat() * 0.4f));
                    }
                }
                return g;
            }
            case MOSS: {
                // ground cover: a low uneven cushion, two voxels at its thickest
                Grid g = new Grid(16, 3, 16, 0.06f);
                g.flex = 0f;
                float rx = 4 + r.nextFloat() * 3, rz = 4 + r.nextFloat() * 3;
                for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) {
                    float dx = (x - 8) / rx, dz = (z - 8) / rz, d = dx * dx + dz * dz + (r.nextFloat() - 0.5f) * 0.35f;
                    if (d > 1) continue;
                    g.set(x, 0, z, shade(MOSS_G, 0.75f + r.nextFloat() * 0.3f));
                    if (d < 0.35f && r.nextInt(3) != 0) g.set(x, 1, z, shade(MOSS_G, 0.95f + r.nextFloat() * 0.25f));
                }
                return g;
            }
            case SAPLING: {
                Grid g = new Grid(17, 30, 17, 0.075f);
                g.flex = 0.3f;
                int hgt = 16 + r.nextInt(8);
                float x = 8, z = 8;
                for (int y = 0; y < hgt; y++) { x += (r.nextFloat() - 0.5f) * 0.5f; z += (r.nextFloat() - 0.5f) * 0.5f; g.set(Math.round(x), y, Math.round(z), shade(WOOD, 0.9f + r.nextFloat() * 0.2f)); }
                float cr = 3.5f + r.nextFloat() * 2f;
                for (int px = 0; px < g.w; px++) for (int py = 0; py < g.h; py++) for (int pz = 0; pz < g.d; pz++) {
                    float dx = (px - x) / cr, dy = (py - hgt) / (cr * 1.2f), dz = (pz - z) / cr, d = dx * dx + dy * dy + dz * dz;
                    if (d > 1 || d < 0.3f || r.nextFloat() < 0.35f) continue;
                    g.set(px, py, pz, shade(r.nextInt(5) == 0 ? G_LIGHT : G_MID, 0.7f + 0.4f * (py - hgt + cr) / (2 * cr) + (r.nextFloat() - 0.5f) * 0.1f));
                }
                return g;
            }
            case MUSHROOM: {
                Grid g = new Grid(11, 6, 11, 0.04f);
                g.flex = 0f;
                int n = 2 + r.nextInt(4);
                int cap = r.nextInt(3) == 0 ? 0xA83A28 : r.nextBoolean() ? 0xB89868 : 0xD8CCB0;
                for (int i = 0; i < n; i++) {
                    int x = 2 + r.nextInt(7), z = 2 + r.nextInt(7), hgt = 1 + r.nextInt(3);
                    for (int y = 0; y < hgt; y++) g.set(x, y, z, 0xD8D0BC);
                    for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) if (dx == 0 || dz == 0 || hgt > 2) g.set(x + dx, hgt, z + dz, shade(cap, 0.9f + r.nextFloat() * 0.2f));
                    g.set(x, hgt + 1, z, shade(cap, 1.1f));
                }
                return g;
            }
            default: return new Grid(1, 1, 1, 0.05f);
        }
    }

    private static void twig(Grid g, Random r, float x, float y, float z, float a, float rise, int len, int depth) {
        float ca = (float) Math.cos(a) * (1 - rise * 0.5f), sa = (float) Math.sin(a) * (1 - rise * 0.5f);
        for (int k = 0; k < len; k++) {
            x += ca; z += sa; y += rise;
            g.set(Math.round(x), Math.round(y), Math.round(z), shade(depth == 2 ? WOOD : WOOD_GREY, 0.85f + r.nextFloat() * 0.3f));
            if (depth > 0 && k > 2 && r.nextInt(4) == 0) twig(g, r, x, y, z, a + (r.nextBoolean() ? 1 : -1) * (0.6f + r.nextFloat() * 0.7f), rise * (0.6f + r.nextFloat() * 0.5f), Math.max(2, len - k), depth - 1);
        }
    }

    private static final int[][] FACE_N = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
    // for each face, its four corners as offsets in the voxel (counter-clockwise seen from outside)
    private static final int[][][] FACE_V = {
            {{1, 0, 0}, {1, 1, 0}, {1, 1, 1}, {1, 0, 1}},
            {{0, 0, 1}, {0, 1, 1}, {0, 1, 0}, {0, 0, 0}},
            {{0, 1, 0}, {0, 1, 1}, {1, 1, 1}, {1, 1, 0}},
            {{0, 0, 1}, {0, 0, 0}, {1, 0, 0}, {1, 0, 1}},
            {{1, 0, 1}, {1, 1, 1}, {0, 1, 1}, {0, 0, 1}},
            {{0, 0, 0}, {0, 1, 0}, {1, 1, 0}, {1, 0, 0}}};

    /**
     * The mesh of one grown plant: its outside faces as triangles, the root - the middle of the grid's floor - at the
     * origin. Voxels standing one on another with the same neighbours are drawn as one tall box whose colour runs from
     * the bottom voxel's to the top one's: a blade of grass ten voxels high is five faces, not forty-one.
     */
    public static float[] mesh(int species, int variant) {
        Grid g = grow(species, variant * 31L + 5);
        float[] out = new float[4096];
        int o = 0;
        float cx = g.w / 2f, cz = species == IVY ? 0 : g.d / 2f, v = g.voxel, top = Math.max(1, g.h) * v;
        int[] order = {0, 1, 2, 0, 2, 3};
        for (int x = 0; x < g.w; x++) for (int z = 0; z < g.d; z++) for (int y = 0; y < g.h; ) {
            if (!g.full(x, y, z)) { y++; continue; }
            int y0 = y, pat = pattern(g, x, y, z), y1 = y;
            while (y1 + 1 < g.h && g.full(x, y1 + 1, z) && pattern(g, x, y1 + 1, z) == pat) y1++;
            y = y1 + 1;
            float[] lo = tint(g, x, y0, z), hi = tint(g, x, y1, z);
            for (int f = 0; f < 6; f++) {
                if (f == 3 && y0 == 0) continue;                                          // nobody sees the underside of what stands on the ground
                boolean hidden = f == 2 ? g.full(x, y1 + 1, z) : f == 3 ? g.full(x, y0 - 1, z) : (pat & (1 << f)) != 0;
                if (hidden) continue;
                float fs = f == 2 ? 1.06f : f == 3 ? 0.8f : 1f;
                if (o + 6 * FLOATS > out.length) out = java.util.Arrays.copyOf(out, out.length * 2);
                for (int k = 0; k < 6; k++) {
                    int[] c = FACE_V[f][order[k]];
                    // the corner's height: the top of the run for an upper corner, its foot for a lower one
                    boolean upper = f == 2 || f != 3 && c[1] == 1;
                    float py = (upper ? y1 + 1 : y0) * v;
                    float[] col = upper ? hi : lo;
                    out[o++] = (x + c[0] - cx) * v; out[o++] = py; out[o++] = (z + c[2] - cz) * v;
                    out[o++] = FACE_N[f][0]; out[o++] = FACE_N[f][1]; out[o++] = FACE_N[f][2];
                    out[o++] = col[0] * fs; out[o++] = col[1] * fs; out[o++] = col[2] * fs;
                    float k01 = Math.min(1, py / top);
                    out[o++] = g.flex * k01 * (0.35f + 0.65f * k01) * top;
                }
            }
        }
        return java.util.Arrays.copyOf(out, o);
    }

    /** Which of a voxel's four sides have a neighbour (bit f for face f): voxels in a column are merged while this stays the same. */
    private static int pattern(Grid g, int x, int y, int z) {
        return (g.full(x + 1, y, z) ? 1 : 0) | (g.full(x - 1, y, z) ? 2 : 0) | (g.full(x, y, z + 1) ? 16 : 0) | (g.full(x, y, z - 1) ? 32 : 0);
    }

    /** A voxel's colour, darkened by how buried it is: the inside of a clump is in its own shade. */
    private static float[] tint(Grid g, int x, int y, int z) {
        int col = g.get(x, y, z), near = 0;
        for (int f = 0; f < 6; f++) if (g.full(x + FACE_N[f][0], y + FACE_N[f][1], z + FACE_N[f][2])) near++;
        float occ = 1 - near * 0.06f;
        return new float[]{((col >> 16) & 255) / 255f * occ, ((col >> 8) & 255) / 255f * occ, (col & 255) / 255f * occ};
    }
}
