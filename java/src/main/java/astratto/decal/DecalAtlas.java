package astratto.decal;

import java.util.Random;

/**
 * The decal engine's authored variants (1.0), painted in code: for every kind of mark a small set of 64 x 64 pictures,
 * so that no two neighbouring stains are the same stain. A picture is colour and coverage; where it is transparent the
 * surface under it shows through untouched.
 *
 * Kinds and how many variants of each there are are listed in {@link #KINDS}; the layer of variant v of kind k is
 * {@code FIRST[k] + v}.
 */
public final class DecalAtlas {
    private DecalAtlas() {}

    public static final int N = 64;
    public static final int DIRT_STREAK = 0, MOSS = 1, CRACK = 2, PATCH = 3, RUST = 4, LEAVES = 5, OIL = 6, SCORCH = 7, BLOOD = 8, BULLET = 9,
            WATER_STAIN = 10, GRIME = 11, MUD = 12, PAPER = 13, WALL_CRACK = 14, KINDS = 15;
    public static final int[] VARIANTS = {4, 4, 4, 3, 3, 3, 2, 2, 4, 2, 3, 3, 2, 3, 3};
    public static final int[] FIRST = new int[KINDS];
    public static final int LAYERS;

    static { int n = 0; for (int k = 0; k < KINDS; k++) { FIRST[k] = n; n += VARIANTS[k]; } LAYERS = n; }

    private static int argb(float a, int rgb) { return (Math.max(0, Math.min(255, Math.round(a * 255))) << 24) | (rgb & 0xFFFFFF); }

    private static float vnoise(Random base, float[] grid, int gs, float x, float y) {
        int ix = (int) Math.floor(x), iy = (int) Math.floor(y);
        float fx = x - ix, fy = y - iy;
        fx = fx * fx * (3 - 2 * fx); fy = fy * fy * (3 - 2 * fy);
        float a = grid[Math.floorMod(ix, gs) + Math.floorMod(iy, gs) * gs], b = grid[Math.floorMod(ix + 1, gs) + Math.floorMod(iy, gs) * gs];
        float c = grid[Math.floorMod(ix, gs) + Math.floorMod(iy + 1, gs) * gs], d = grid[Math.floorMod(ix + 1, gs) + Math.floorMod(iy + 1, gs) * gs];
        return a + (b - a) * fx + (c - a) * fy + (a - b - c + d) * fx * fy;
    }

    private static int tone(int rgb, float k) {
        int r = Math.max(0, Math.min(255, Math.round(((rgb >> 16) & 255) * k))), g = Math.max(0, Math.min(255, Math.round(((rgb >> 8) & 255) * k))), b = Math.max(0, Math.min(255, Math.round((rgb & 255) * k)));
        return r << 16 | g << 8 | b;
    }

    /** One variant of one kind: N x N pixels, ARGB, row 0 at the top. */
    public static int[] paint(int kind, int variant) {
        Random r = new Random(kind * 7907L + variant * 104729L + 3);
        int[] px = new int[N * N];
        final int gs = 16;
        float[] grid = new float[gs * gs], grid2 = new float[gs * gs];
        for (int i = 0; i < grid.length; i++) { grid[i] = r.nextFloat(); grid2[i] = r.nextFloat(); }
        // an edge falloff so that no decal ends in a straight line
        float[] edge = new float[N * N];
        for (int y = 0; y < N; y++) for (int x = 0; x < N; x++) {
            float ex = Math.min(x, N - 1 - x) / 9f, ey = Math.min(y, N - 1 - y) / 9f;
            edge[x + y * N] = Math.max(0, Math.min(1, Math.min(ex, ey)));
        }
        switch (kind) {
            case DIRT_STREAK, RUST -> {
                // what rain carries down a wall from a sill: several runs of different length, dark at the top, thinning down
                int col = kind == RUST ? 0x7A4220 : 0x2A2620;
                int runs = 5 + r.nextInt(5);
                for (int k = 0; k < runs; k++) {
                    float cx = 4 + r.nextFloat() * (N - 8), wdt = 1.5f + r.nextFloat() * 4.5f, len = N * (0.35f + r.nextFloat() * 0.65f), str = 0.3f + r.nextFloat() * 0.45f;
                    for (int y = 0; y < len; y++) {
                        float t = y / len, wob = (vnoise(r, grid, gs, cx * 0.2f, y * 0.12f) - 0.5f) * 3f, w = wdt * (1 - t * 0.6f);
                        for (int x = (int) (cx + wob - w); x <= cx + wob + w; x++) {
                            if (x < 0 || x >= N) continue;
                            float a = str * (1 - t) * (1 - t * 0.3f) * (1 - Math.abs(x - cx - wob) / (w + 0.5f)) * (0.7f + 0.5f * vnoise(r, grid2, gs, x * 0.5f, y * 0.5f));
                            int i = x + y * N;
                            float old = (px[i] >>> 24) / 255f;
                            px[i] = argb(Math.min(0.85f, old + a * (1 - old)), tone(col, 0.8f + 0.4f * vnoise(r, grid2, gs, x * 0.3f, y * 0.1f)));
                        }
                    }
                }
                // the band right under the sill
                for (int y = 0; y < 5; y++) for (int x = 0; x < N; x++) { int i = x + y * N; float a = Math.max((px[i] >>> 24) / 255f, (0.5f - y * 0.1f) * (0.5f + 0.5f * vnoise(r, grid, gs, x * 0.2f, 3))); px[i] = argb(a * Math.min(1, Math.min(x, N - 1 - x) / 6f), col); }
            }
            case MOSS -> {
                // growth that climbs from the foot of a wall: solid at the bottom, breaking into islands higher up
                for (int y = 0; y < N; y++) for (int x = 0; x < N; x++) {
                    float up = (N - 1 - y) / (float) N;                              // 0 at the foot
                    float n = vnoise(r, grid, gs, x * 0.14f, y * 0.14f) * 0.65f + vnoise(r, grid2, gs, x * 0.45f, y * 0.45f) * 0.35f;
                    float reach = 0.25f + 0.55f * vnoise(r, grid2, gs, x * 0.07f, 1.5f);
                    float a = n - up / reach * 0.75f;
                    if (a < 0.18f) continue;
                    int col = n > 0.66f ? 0x4E7A32 : n > 0.5f ? 0x3A6428 : 0x2C4E22;
                    px[x + y * N] = argb(Math.min(0.92f, (a - 0.18f) * 4.5f) * Math.min(1, Math.min(x, N - 1 - x) / 5f), tone(col, 0.85f + r.nextFloat() * 0.3f));
                }
            }
            case CRACK, WALL_CRACK -> {
                // a crack: a line that wanders and forks, dark in the middle, a lighter chipped edge beside it
                int cracks = 1 + r.nextInt(2);
                for (int k = 0; k < cracks; k++) {
                    float x = 8 + r.nextFloat() * (N - 16), y = kind == WALL_CRACK ? 2 : 8 + r.nextFloat() * (N - 16);
                    float a = kind == WALL_CRACK ? 1.5708f + (r.nextFloat() - 0.5f) * 0.8f : r.nextFloat() * 6.2832f;
                    crack(px, r, x, y, a, 26 + r.nextInt(30), 2);
                    if (kind == CRACK) crack(px, r, x, y, a + 3.14159f + (r.nextFloat() - 0.5f) * 0.6f, 14 + r.nextInt(24), 1);
                }
            }
            case PATCH -> {
                // a repair: a rectangle of newer, flatter material with a soft uneven border
                int x0 = 8 + r.nextInt(12), y0 = 8 + r.nextInt(12), x1 = N - 8 - r.nextInt(12), y1 = N - 8 - r.nextInt(12);
                int col = variant == 0 ? 0x4A4A4C : variant == 1 ? 0x8A8880 : 0x3A3836;
                for (int y = 0; y < N; y++) for (int x = 0; x < N; x++) {
                    float d = Math.min(Math.min(x - x0, x1 - x), Math.min(y - y0, y1 - y)) + (vnoise(r, grid, gs, x * 0.3f, y * 0.3f) - 0.5f) * 5f;
                    if (d < 0) continue;
                    px[x + y * N] = argb(Math.min(0.62f, d * 0.2f) * (0.8f + 0.3f * vnoise(r, grid2, gs, x * 0.5f, y * 0.5f)), tone(col, 0.9f + 0.2f * vnoise(r, grid2, gs, x * 0.2f, y * 0.2f)));
                }
            }
            case LEAVES, PAPER -> {
                // litter: a scatter of small shapes, each its own tone, thicker toward one side as if the wind had left it there
                int n = kind == LEAVES ? 7 + r.nextInt(9) : 3 + r.nextInt(4);
                int[] pal = kind == LEAVES ? new int[]{0x5E4A26, 0x6E5626, 0x4E3A1E, 0x74622C, 0x5A361C, 0x3E3420} : new int[]{0xC8C4B4, 0xB8B09A, 0xD4D0C4};
                // the wind leaves them in one corner: a centre off to one side, the scatter falling away from it
                float lcx = 12 + r.nextFloat() * (N - 24), lcy = 12 + r.nextFloat() * (N - 24);
                for (int k = 0; k < n; k++) {
                    float sa0 = r.nextFloat() * 6.2832f, sd = (float) Math.pow(r.nextFloat(), 1.5) * 26f;
                    float cx = kind == LEAVES ? Math.max(5, Math.min(N - 6, lcx + (float) Math.cos(sa0) * sd)) : 6 + r.nextFloat() * (N - 12), cy = kind == LEAVES ? Math.max(5, Math.min(N - 6, lcy + (float) Math.sin(sa0) * sd)) : 6 + r.nextFloat() * (N - 12);
                    float a = r.nextFloat() * 3.14159f, len = kind == LEAVES ? 2f + r.nextFloat() * 2.5f : 5f + r.nextFloat() * 5f, wd = kind == LEAVES ? 1f + r.nextFloat() : len * 0.7f;
                    int col = tone(pal[r.nextInt(pal.length)], 0.8f + r.nextFloat() * 0.35f);
                    float ca = (float) Math.cos(a), sa = (float) Math.sin(a);
                    for (int y = (int) (cy - len - 1); y <= cy + len + 1; y++) for (int x = (int) (cx - len - 1); x <= cx + len + 1; x++) {
                        if (x < 0 || y < 0 || x >= N || y >= N) continue;
                        float u = (x - cx) * ca + (y - cy) * sa, v = -(x - cx) * sa + (y - cy) * ca;
                        boolean in = kind == LEAVES ? (u * u) / (len * len) + (v * v) / (wd * wd) <= 1 : Math.abs(u) <= len && Math.abs(v) <= wd;
                        if (in) px[x + y * N] = argb(0.92f, kind == PAPER && Math.abs(v) < wd - 1 && ((int) (v + 20) % 3 == 0) && Math.abs(u) < len - 1.5f ? tone(col, 0.82f) : col);
                    }
                }
            }
            case OIL, MUD, WATER_STAIN, SCORCH, BLOOD -> {
                // a blot: a lumpy disc, with satellites for anything that was thrown rather than spilt
                int col = kind == OIL ? 0x16161A : kind == MUD ? 0x4A3C2A : kind == WATER_STAIN ? 0x5A4A32 : kind == SCORCH ? 0x0C0B0A : 0x5A0C0A;
                float rad = kind == BLOOD ? 13 + r.nextFloat() * 8 : kind == SCORCH ? 27 : 17 + r.nextFloat() * 9;
                float cx = N / 2f + (r.nextFloat() - 0.5f) * 6, cy = N / 2f + (r.nextFloat() - 0.5f) * 6;
                for (int y = 0; y < N; y++) for (int x = 0; x < N; x++) {
                    float d = (float) Math.hypot(x - cx, y - cy) / rad;
                    float n = vnoise(r, grid, gs, x * 0.16f, y * 0.16f) * 0.7f + vnoise(r, grid2, gs, x * 0.5f, y * 0.5f) * 0.3f;
                    float a;
                    if (kind == SCORCH) a = Math.max(0, 1 - d) * (0.55f + 0.7f * n);
                    else if (kind == WATER_STAIN) { float ring = 1 - Math.abs(d + (n - 0.5f) * 0.5f - 0.82f) * 6f; a = Math.max(Math.max(0, ring) * 0.55f, d + (n - 0.5f) * 0.5f < 0.82f ? 0.16f : 0); }
                    else a = d + (n - 0.5f) * 0.7f < 0.8f ? (kind == OIL ? 0.72f : kind == MUD ? 0.6f : 0.86f) : 0;
                    if (a <= 0) continue;
                    int c = kind == OIL && n > 0.62f ? 0x24283A : kind == BLOOD ? tone(col, 0.75f + 0.5f * n) : tone(col, 0.85f + 0.3f * n);
                    px[x + y * N] = argb(Math.min(0.95f, a) * edge[x + y * N], c);
                }
                if (kind == BLOOD || kind == MUD) for (int k = 0; k < 16 + r.nextInt(16); k++) {
                    float a = r.nextFloat() * 6.2832f, dist = rad * (0.9f + r.nextFloat() * 1.0f), sr = 0.7f + r.nextFloat() * 2f;
                    int sx = Math.round(cx + (float) Math.cos(a) * dist), sy = Math.round(cy + (float) Math.sin(a) * dist);
                    for (int y = (int) (sy - sr); y <= sy + sr; y++) for (int x = (int) (sx - sr); x <= sx + sr; x++) if (x >= 1 && y >= 1 && x < N - 1 && y < N - 1 && Math.hypot(x - sx, y - sy) <= sr) px[x + y * N] = argb(0.85f, tone(col, 0.9f));
                }
            }
            case BULLET -> {
                float cx = N / 2f, cy = N / 2f;
                for (int y = 0; y < N; y++) for (int x = 0; x < N; x++) {
                    float d = (float) Math.hypot(x - cx, y - cy), n = vnoise(r, grid, gs, x * 0.4f, y * 0.4f);
                    if (d < 5) px[x + y * N] = argb(0.95f, 0x0A0A0A);
                    else if (d < 10 + n * 6) px[x + y * N] = argb(0.55f * (1 - (d - 5) / 11f), n > 0.5f ? 0xB0A898 : 0x3A3632);
                }
                for (int k = 0; k < 3 + variant * 2; k++) crack(px, r, cx, cy, r.nextFloat() * 6.2832f, 8 + r.nextInt(12), 0);
            }
            case GRIME -> {
                // the dirt of a floor line: a dark band along the bottom, scuffed, with a few kicks higher up
                for (int y = 0; y < N; y++) for (int x = 0; x < N; x++) {
                    float up = (N - 1 - y) / (float) N;
                    float n = vnoise(r, grid, gs, x * 0.2f, y * 0.25f) * 0.6f + vnoise(r, grid2, gs, x * 0.6f, y * 0.6f) * 0.4f;
                    float a = (0.62f - up * (2.2f + n * 1.6f)) * (0.6f + 0.6f * n);
                    if (a > 0.02f) px[x + y * N] = argb(Math.min(0.7f, a), tone(0x24201C, 0.8f + 0.4f * n));
                }
                for (int k = 0; k < 4; k++) { int sx = 4 + r.nextInt(N - 8), sy = N - 10 - r.nextInt(20), len = 4 + r.nextInt(9); for (int i = 0; i < len; i++) if (sx + i < N) px[sx + i + sy * N] = argb(0.4f, 0x1A1816); }
            }
            default -> { }
        }
        return px;
    }

    private static void crack(int[] px, Random r, float x, float y, float a, int len, int forks) {
        float a0 = a;
        for (int k = 0; k < len; k++) {
            // it wanders, but about a heading: a crack does not curl back on itself
            a += (r.nextFloat() - 0.5f) * 0.55f + (a0 - a) * 0.22f;
            x += (float) Math.cos(a); y += (float) Math.sin(a);
            int ix = Math.round(x), iy = Math.round(y);
            if (ix < 1 || iy < 1 || ix >= N - 1 || iy >= N - 1) return;
            float fade = Math.min(1, (len - k) / 6f);
            px[ix + iy * N] = argb(0.88f * fade, 0x0E0D0C);
            if (k % 3 != 0) { int ox = ix + (Math.abs(Math.cos(a)) > 0.7 ? 0 : 1), oy = iy + (Math.abs(Math.cos(a)) > 0.7 ? 1 : 0); if ((px[ox + oy * N] >>> 24) < 60) px[ox + oy * N] = argb(0.3f * fade, 0xA09888); }
            if (forks > 0 && k > 4 && r.nextInt(11) == 0) crack(px, r, x, y, a + (r.nextBoolean() ? 1 : -1) * (0.5f + r.nextFloat() * 0.6f), (len - k) * 2 / 3, forks - 1);
        }
    }
}
