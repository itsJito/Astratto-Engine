package astratto.util;

/** Deterministic 2D value/fbm noise for terrain and texture variation. */
public final class Noise {
    private Noise() {}

    private static float h(int x, int y, int seed) { return Rng.hash(x, y, 0, seed); }

    private static float smooth(float t) { return t * t * (3 - 2 * t); }

    public static float value(float x, float y, int seed) {
        int ix = (int) Math.floor(x), iy = (int) Math.floor(y);
        float fx = smooth(x - ix), fy = smooth(y - iy);
        float a = h(ix, iy, seed), b = h(ix + 1, iy, seed), c = h(ix, iy + 1, seed), d = h(ix + 1, iy + 1, seed);
        float top = a + (b - a) * fx, bot = c + (d - c) * fx;
        return top + (bot - top) * fy;
    }

    /** Tileable value noise with integer period p. */
    public static float tiled(float x, float y, int p, int seed) {
        int ix = (int) Math.floor(x), iy = (int) Math.floor(y);
        float fx = smooth(x - ix), fy = smooth(y - iy);
        int x0 = Math.floorMod(ix, p), x1 = Math.floorMod(ix + 1, p), y0 = Math.floorMod(iy, p), y1 = Math.floorMod(iy + 1, p);
        float a = h(x0, y0, seed), b = h(x1, y0, seed), c = h(x0, y1, seed), d = h(x1, y1, seed);
        float top = a + (b - a) * fx, bot = c + (d - c) * fx;
        return top + (bot - top) * fy;
    }

    public static float fbm(float x, float y, int oct, int seed) {
        float sum = 0, amp = 0.5f, norm = 0;
        for (int i = 0; i < oct; i++) {
            sum += value(x, y, seed + i * 31) * amp;
            norm += amp;
            x *= 2.03f; y *= 2.03f; amp *= 0.5f;
        }
        return sum / norm;
    }

    public static float fbmTiled(float x, float y, int period, int oct, int seed) {
        float sum = 0, amp = 0.5f, norm = 0;
        int p = period;
        for (int i = 0; i < oct; i++) {
            sum += tiled(x, y, p, seed + i * 31) * amp;
            norm += amp;
            x *= 2; y *= 2; p *= 2; amp *= 0.5f;
        }
        return sum / norm;
    }
}
