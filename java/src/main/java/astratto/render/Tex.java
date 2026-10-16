package astratto.render;

import astratto.util.Noise;
import astratto.util.Rng;

/**
 * A square ARGB canvas with pixel-art painting primitives used to synthesise the
 * high-resolution voxel face textures (material reads, wear, grime, highlights).
 */
public final class Tex {
    public final int n;
    public final int[] px;
    public final Rng rng;
    public final int seed;

    public Tex(int n, int seed) {
        this.n = n; this.px = new int[n * n]; this.seed = seed; this.rng = new Rng(seed);
    }

    // ------------------------------------------------------------ colour helpers
    public static int rgb(int r, int g, int b) { return 0xFF000000 | (c(r) << 16) | (c(g) << 8) | c(b); }
    public static int argb(int a, int r, int g, int b) { return (c(a) << 24) | (c(r) << 16) | (c(g) << 8) | c(b); }
    private static int c(int v) { return v < 0 ? 0 : (v > 255 ? 255 : v); }
    public static int hex(int h) { return 0xFF000000 | h; }
    public static int r(int c) { return (c >> 16) & 255; }
    public static int g(int c) { return (c >> 8) & 255; }
    public static int b(int c) { return c & 255; }
    public static int a(int c) { return (c >>> 24) & 255; }
    public static int shade(int col, float f) { return (col & 0xFF000000) | (c((int) (r(col) * f)) << 16) | (c((int) (g(col) * f)) << 8) | c((int) (b(col) * f)); }
    public static int add(int col, int d) { return (col & 0xFF000000) | (c(r(col) + d) << 16) | (c(g(col) + d) << 8) | c(b(col) + d); }
    public static int mix(int a, int b, float t) {
        return argb((int) (a(a) + (a(b) - a(a)) * t), (int) (r(a) + (r(b) - r(a)) * t), (int) (g(a) + (g(b) - g(a)) * t), (int) (b(a) + (b(b) - b(a)) * t));
    }

    public int get(int x, int y) { return px[Math.floorMod(y, n) * n + Math.floorMod(x, n)]; }
    public void set(int x, int y, int col) { px[Math.floorMod(y, n) * n + Math.floorMod(x, n)] = col; }
    public void blend(int x, int y, int col, float t) { set(x, y, mix(get(x, y), col, t)); }
    public void mul(int x, int y, float f) { set(x, y, shade(get(x, y), f)); }

    // ------------------------------------------------------------ fills
    public Tex fill(int col) { java.util.Arrays.fill(px, col); return this; }

    /** Multiplicative tileable value-noise shading: amp=0.15 means +-15%. */
    public Tex noise(float scale, float amp, int s) {
        int period = Math.max(1, (int) (n / scale));
        for (int y = 0; y < n; y++) for (int x = 0; x < n; x++) {
            float v = Noise.fbmTiled(x / scale, y / scale, period, 3, seed + s) - 0.5f;
            mul(x, y, 1 + v * 2 * amp);
        }
        return this;
    }

    /** Per-pixel dither grain. */
    public Tex grain(float amp) {
        for (int i = 0; i < px.length; i++) {
            if (a(px[i]) == 0) continue;
            px[i] = shade(px[i], 1 + (rng.nextFloat() - 0.5f) * 2 * amp);
        }
        return this;
    }

    public Tex speckle(int col, float density) {
        for (int i = 0; i < px.length; i++) if (rng.nextFloat() < density) px[i] = col;
        return this;
    }

    public Tex speckleShade(float density, float f) {
        for (int i = 0; i < px.length; i++) if (rng.nextFloat() < density) px[i] = shade(px[i], f);
        return this;
    }

    public Tex rect(int x0, int y0, int w, int h, int col) {
        for (int y = y0; y < y0 + h; y++) for (int x = x0; x < x0 + w; x++) set(x, y, col);
        return this;
    }

    public Tex rectShade(int x0, int y0, int w, int h, float f) {
        for (int y = y0; y < y0 + h; y++) for (int x = x0; x < x0 + w; x++) mul(x, y, f);
        return this;
    }

    public Tex outline(int x0, int y0, int w, int h, int col) {
        for (int x = x0; x < x0 + w; x++) { set(x, y0, col); set(x, y0 + h - 1, col); }
        for (int y = y0; y < y0 + h; y++) { set(x0, y, col); set(x0 + w - 1, y, col); }
        return this;
    }

    /** Raised panel: light top/left edge, dark bottom/right edge. */
    public Tex bevel(int x0, int y0, int w, int h, float hi, float lo) {
        for (int x = x0; x < x0 + w; x++) { mul(x, y0, hi); mul(x, y0 + h - 1, lo); }
        for (int y = y0 + 1; y < y0 + h - 1; y++) { mul(x0, y, hi); mul(x0 + w - 1, y, lo); }
        return this;
    }

    public Tex line(int x0, int y0, int x1, int y1, int col) {
        int dx = Math.abs(x1 - x0), dy = -Math.abs(y1 - y0), sx = x0 < x1 ? 1 : -1, sy = y0 < y1 ? 1 : -1, err = dx + dy;
        while (true) {
            set(x0, y0, col);
            if (x0 == x1 && y0 == y1) break;
            int e2 = 2 * err;
            if (e2 >= dy) { err += dy; x0 += sx; }
            if (e2 <= dx) { err += dx; y0 += sy; }
        }
        return this;
    }

    public Tex circle(int cx, int cy, float rad, int col) {
        for (int y = (int) (cy - rad - 1); y <= cy + rad + 1; y++)
            for (int x = (int) (cx - rad - 1); x <= cx + rad + 1; x++)
                if ((x - cx) * (x - cx) + (y - cy) * (y - cy) <= rad * rad) set(x, y, col);
        return this;
    }

    public Tex disc(float cx, float cy, float rad, int col, float soft) {
        for (int y = 0; y < n; y++) for (int x = 0; x < n; x++) {
            float d = (float) Math.hypot(x + 0.5f - cx, y + 0.5f - cy);
            if (d < rad) {
                float t = soft <= 0 ? 1 : Math.min(1, (rad - d) / soft);
                blend(x, y, col, t * a(col) / 255f);
            }
        }
        return this;
    }

    /** Wandering crack line from a random point. */
    public Tex crack(int col, int len) {
        int x = rng.nextInt(n), y = rng.nextInt(n);
        int dx = rng.nextInt(3) - 1, dy = rng.chance(0.5) ? 1 : -1;
        for (int i = 0; i < len; i++) {
            set(x, y, col);
            if (rng.nextFloat() < 0.4f) dx = rng.nextInt(3) - 1;
            if (rng.nextFloat() < 0.3f) dy = rng.nextInt(3) - 1;
            if (dx == 0 && dy == 0) dy = 1;
            x += dx; y += dy;
            if (rng.nextFloat() < 0.08f) {
                int bx = x, by = y;
                for (int k = 0; k < 4; k++) { bx += rng.nextInt(3) - 1; by += 1; set(bx, by, col); }
            }
        }
        return this;
    }

    /** Darkened soft blotches (grime / water stains). */
    public Tex stains(int count, float strength, float maxR) {
        for (int i = 0; i < count; i++) {
            float cx = rng.nextFloat() * n, cy = rng.nextFloat() * n, rr = 2 + rng.nextFloat() * maxR;
            for (int y = (int) (cy - rr); y <= cy + rr; y++) for (int x = (int) (cx - rr); x <= cx + rr; x++) {
                float d = (float) Math.hypot(x - cx, y - cy) / rr;
                if (d < 1) mul(x, y, 1 - strength * (1 - d) * (0.7f + 0.3f * rng.nextFloat()));
            }
        }
        return this;
    }

    /** Vertical drip streaks from the top (rain grime on walls). */
    public Tex streaks(int count, float strength) {
        for (int i = 0; i < count; i++) {
            int x = rng.nextInt(n), len = n / 3 + rng.nextInt(n / 2);
            int y0 = rng.nextInt(n / 3);
            for (int y = y0; y < y0 + len; y++) mul(x, y, 1 - strength * (1 - (y - y0) / (float) len));
        }
        return this;
    }

    public Tex vgradient(float top, float bottom) {
        for (int y = 0; y < n; y++) { float f = top + (bottom - top) * y / (n - 1f); for (int x = 0; x < n; x++) mul(x, y, f); }
        return this;
    }

    // ------------------------------------------------------------ patterns
    /** Running-bond bricks with per-brick tint, mortar, chipped edges. */
    public Tex bricks(int base, int mortar, int bw, int bh, float tintVar) {
        fill(mortar).noise(4, 0.1f, 7);
        int rows = n / bh;
        for (int row = 0; row < rows; row++) {
            int off = (row % 2) * (bw / 2);
            for (int bx = -bw; bx < n + bw; bx += bw) {
                float t = 1 + (rng.nextFloat() - 0.5f) * 2 * tintVar;
                int col = shade(base, t);
                if (rng.nextFloat() < 0.12f) col = mix(col, rgb(r(base) - 30, g(base) - 20, b(base) - 10), 0.5f);
                int x0 = bx + off + 1, y0 = row * bh + 1;
                for (int y = y0; y < y0 + bh - 1; y++) for (int x = x0; x < x0 + bw - 1; x++) {
                    float sh = 1;
                    if (y == y0) sh = 1.12f; else if (y == y0 + bh - 2) sh = 0.85f;
                    if (x == x0 + bw - 2) sh *= 0.9f;
                    set(x, y, shade(col, sh * (1 + (rng.nextFloat() - 0.5f) * 0.12f)));
                }
                if (rng.nextFloat() < 0.25f) set(x0 + rng.nextInt(bw - 1), y0 + rng.nextInt(bh - 1), mortar);
            }
        }
        return this;
    }

    /** Horizontal wooden planks with grain and nails. */
    public Tex planks(int base, int count, boolean nails) {
        int ph = n / count;
        for (int p = 0; p < count; p++) {
            int col = shade(base, 0.88f + rng.nextFloat() * 0.24f);
            int seam = rng.nextInt(n);
            for (int y = p * ph; y < (p + 1) * ph; y++) for (int x = 0; x < n; x++) {
                float grain = (float) Math.sin((x * 0.35f + Noise.tiled(x / 6f, y / 2f, n / 6, seed + p) * 6) + p) * 0.06f;
                float sh = 1 + grain;
                if (y == p * ph) sh *= 1.15f;
                if (y == (p + 1) * ph - 1) sh *= 0.6f;
                if (x == seam) sh *= 0.65f;
                set(x, y, shade(col, sh));
            }
            if (rng.nextFloat() < 0.5f) circle(rng.nextInt(n), p * ph + ph / 2, 1, shade(col, 0.6f));
            if (nails) { set(seam + 2, p * ph + 2, rgb(70, 70, 75)); set(seam - 2, p * ph + ph - 3, rgb(70, 70, 75)); }
        }
        return grain(0.05f);
    }

    /** Square tile grid with grout lines. */
    public Tex tiles(int c1, int c2, int grout, int size) {
        for (int y = 0; y < n; y++) for (int x = 0; x < n; x++) {
            int tx = x / size, ty = y / size;
            int col = ((tx + ty) % 2 == 0) ? c1 : c2;
            if (x % size == 0 || y % size == 0) col = grout;
            else if (x % size == 1 || y % size == 1) col = shade(col, 1.08f);
            set(x, y, col);
        }
        return grain(0.04f);
    }

    public Tex hstripes(int c1, int c2, int w) {
        for (int y = 0; y < n; y++) for (int x = 0; x < n; x++) set(x, y, ((y / w) % 2 == 0) ? c1 : c2);
        return this;
    }

    public Tex vstripes(int c1, int c2, int w) {
        for (int y = 0; y < n; y++) for (int x = 0; x < n; x++) set(x, y, ((x / w) % 2 == 0) ? c1 : c2);
        return this;
    }

    public Tex diagStripes(int c1, int c2, int w) {
        for (int y = 0; y < n; y++) for (int x = 0; x < n; x++) set(x, y, (((x + y) / w) % 2 == 0) ? c1 : c2);
        return this;
    }

    /** Vertical corrugation ridges. */
    public Tex corrugate(int base, int period) {
        for (int y = 0; y < n; y++) for (int x = 0; x < n; x++) {
            float s = (float) Math.sin(x * Math.PI * 2 / period);
            set(x, y, shade(base, 0.82f + 0.25f * (s * 0.5f + 0.5f)));
        }
        return this;
    }

    public Tex rivets(int spacing, int col) {
        for (int y = spacing / 2; y < n; y += spacing) for (int x = spacing / 2; x < n; x += spacing) {
            set(x, y, col); set(x + 1, y + 1, shade(col, 0.5f)); set(x, y - 1, add(col, 40));
        }
        return this;
    }

    public Tex rust(float density, int s) {
        for (int y = 0; y < n; y++) for (int x = 0; x < n; x++) {
            float v = Noise.fbmTiled(x / 5f, y / 5f, Math.max(1, n / 5), 3, seed + s);
            if (v > 1 - density) {
                float t = Math.min(1, (v - (1 - density)) * 4);
                blend(x, y, rgb(140 + rng.nextInt(30), 70 + rng.nextInt(20), 30), t * 0.85f);
            }
        }
        return this;
    }

    /** Edge darkening to read block borders (ambient grime in corners). */
    public Tex edgeDarken(float f, int w) {
        for (int y = 0; y < n; y++) for (int x = 0; x < n; x++) {
            int d = Math.min(Math.min(x, y), Math.min(n - 1 - x, n - 1 - y));
            if (d < w) mul(x, y, f + (1 - f) * d / (float) w);
        }
        return this;
    }

    /** Cutout: make pixels matching predicate transparent. */
    public Tex clear() { java.util.Arrays.fill(px, 0); return this; }

    public Tex alpha(int a) {
        for (int i = 0; i < px.length; i++) px[i] = (a << 24) | (px[i] & 0xFFFFFF);
        return this;
    }

    public Tex copy() {
        Tex t = new Tex(n, seed + 1);
        System.arraycopy(px, 0, t.px, 0, px.length);
        return t;
    }

    /** Blood splatter. */
    public Tex blood(int count) {
        for (int i = 0; i < count; i++) {
            float cx = rng.nextFloat() * n, cy = rng.nextFloat() * n, rr = 1.5f + rng.nextFloat() * 4;
            int col = rgb(100 + rng.nextInt(40), 10, 12);
            for (int k = 0; k < 30; k++) {
                float a = rng.nextFloat() * 6.283f, d = rng.nextFloat() * rr;
                set((int) (cx + Math.cos(a) * d), (int) (cy + Math.sin(a) * d), shade(col, 0.8f + rng.nextFloat() * 0.3f));
            }
        }
        return this;
    }
}
