package astratto.render;

/**
 * 1.2, Astratto Engine: flat vector drawing for the interface, on top of {@link Batch2D}. The batch draws rectangles
 * and textured quads; a mark, an emblem or a badge made of those is a grid of squares and looks like one. These are
 * the shapes such things are actually made of - triangles, four-sided figures at any angle, ellipses and parts of
 * them, round-ended strokes, runs of line - filled exactly, at whatever size they are asked for.
 *
 * Everything is in the batch's own coordinates (pixels, y down) and one colour (ARGB) a call.
 */
public final class Vector2D {
    private Vector2D() {}

    private static final float[] UV = new float[8];

    private static void emit(Batch2D b, float x0, float y0, float x1, float y1, float x2, float y2, float x3, float y3, int argb) {
        b.quad4(b.whiteTex(), new float[]{x0, y0, x1, y1, x2, y2, x3, y3}, UV, ((argb >> 16) & 255) / 255f, ((argb >> 8) & 255) / 255f, (argb & 255) / 255f, ((argb >>> 24) & 255) / 255f, 0);
    }

    public static void tri(Batch2D b, float x0, float y0, float x1, float y1, float x2, float y2, int argb) { emit(b, x0, y0, x1, y1, x2, y2, x2, y2, argb); }

    /** A four-sided figure, corners in order round it. */
    public static void quad(Batch2D b, float x0, float y0, float x1, float y1, float x2, float y2, float x3, float y3, int argb) { emit(b, x0, y0, x1, y1, x2, y2, x3, y3, argb); }

    /** A convex figure of any number of corners {x, y, ...}, in order round it. */
    public static void poly(Batch2D b, float[] p, int argb) {
        int n = p.length / 2;
        for (int i = 1; i + 1 < n; i += 2) {
            int j = Math.min(n - 1, i + 2);
            emit(b, p[0], p[1], p[i * 2], p[i * 2 + 1], p[(i + 1) * 2], p[(i + 1) * 2 + 1], p[j * 2], p[j * 2 + 1], argb);
        }
    }

    /** The same figure given in the unit square (0..1 across and down) and set in a box. */
    public static void polyIn(Batch2D b, float x, float y, float w, float h, int argb, float... unit) {
        float[] p = new float[unit.length];
        for (int i = 0; i + 1 < unit.length; i += 2) { p[i] = x + unit[i] * w; p[i + 1] = y + unit[i + 1] * h; }
        poly(b, p, argb);
    }

    /** A filled ellipse - or, between two angles (radians, 0 = right, clockwise on screen), a wedge of one. */
    public static void ellipse(Batch2D b, float cx, float cy, float rx, float ry, int argb) { arc(b, cx, cy, rx, ry, 0, (float) (Math.PI * 2), argb); }

    public static void arc(Batch2D b, float cx, float cy, float rx, float ry, float a0, float a1, int argb) {
        int steps = Math.max(6, Math.min(48, (int) (Math.abs(a1 - a0) * Math.max(rx, ry) * 0.35f) + 6));
        if ((steps & 1) == 1) steps++;
        float da = (a1 - a0) / steps;
        for (int i = 0; i < steps; i += 2) {
            double p = a0 + da * i, q = p + da, r = q + da;
            emit(b, cx, cy, cx + (float) Math.cos(p) * rx, cy + (float) Math.sin(p) * ry, cx + (float) Math.cos(q) * rx, cy + (float) Math.sin(q) * ry, cx + (float) Math.cos(r) * rx, cy + (float) Math.sin(r) * ry, argb);
        }
    }

    /** A ring: the band between two ellipses about the same centre, {@code t} thick, between two angles. */
    public static void ring(Batch2D b, float cx, float cy, float rx, float ry, float t, float a0, float a1, int argb) {
        int steps = Math.max(8, Math.min(64, (int) (Math.abs(a1 - a0) * Math.max(rx, ry) * 0.35f) + 8));
        float da = (a1 - a0) / steps;
        for (int i = 0; i < steps; i++) {
            double p = a0 + da * i, q = p + da;
            float c0 = (float) Math.cos(p), s0 = (float) Math.sin(p), c1 = (float) Math.cos(q), s1 = (float) Math.sin(q);
            emit(b, cx + c0 * rx, cy + s0 * ry, cx + c1 * rx, cy + s1 * ry, cx + c1 * (rx - t), cy + s1 * (ry - t), cx + c0 * (rx - t), cy + s0 * (ry - t), argb);
        }
    }

    /** A stroke with round ends from one point to another, {@code t} thick. */
    public static void capsule(Batch2D b, float x0, float y0, float x1, float y1, float t, int argb) {
        b.line(x0, y0, x1, y1, t, argb);
        ellipse(b, x0, y0, t / 2, t / 2, argb);
        ellipse(b, x1, y1, t / 2, t / 2, argb);
    }

    /** A run of line through points {x, y, ...}, {@code t} thick, its corners rounded. */
    public static void path(Batch2D b, float[] p, float t, int argb) {
        for (int i = 0; i + 3 < p.length; i += 2) {
            b.line(p[i], p[i + 1], p[i + 2], p[i + 3], t, argb);
            if (i > 0) ellipse(b, p[i], p[i + 1], t / 2, t / 2, argb);
        }
    }

    /** A star of n points about a centre: outer radius r, inner radius r * inner, first point straight up. */
    public static void star(Batch2D b, float cx, float cy, float r, float inner, int n, int argb) {
        for (int i = 0; i < n; i++) {
            double a = -Math.PI / 2 + i * 2 * Math.PI / n, h = Math.PI / n;
            emit(b, cx, cy, cx + (float) Math.cos(a - h) * r * inner, cy + (float) Math.sin(a - h) * r * inner, cx + (float) Math.cos(a) * r, cy + (float) Math.sin(a) * r, cx + (float) Math.cos(a + h) * r * inner, cy + (float) Math.sin(a + h) * r * inner, argb);
        }
    }
}
