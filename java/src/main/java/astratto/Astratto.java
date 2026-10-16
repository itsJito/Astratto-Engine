package astratto;

import astratto.render.Batch2D;
import astratto.render.Vector2D;

/**
 * The Astratto Engine: a voxel game engine with a native core (C++17, {@code native/}) that owns the world, rendering,
 * physics (Sydney's ragdolls, characters and cars), AI (NeuralAI's perception, routes and decisions) and audio
 * (UltraEngine's mixer), and a Java library ({@code astratto.*}) that binds it ({@code astratto.bridge}), runs a game's
 * modules round its tick ({@code astratto.game}) and carries the reusable Java systems (window and input, 2D, models,
 * events). Nothing in it names a game. How it is divided, and why, is in {@code docs/ARCHITECTURE.md}.
 *
 * Copyright Astratto North of LudusValley Recreations, under VEIN Enterprises.
 */
public final class Astratto {
    private Astratto() {}

    public static final String NAME = "Astratto Engine";
    public static final String SHORT = "ASTRATTO";
    /** The engine's own version: it moves when the engine does, not when the game ships a build. */
    public static final String VERSION = "2.0";

    /**
     * Whether a caller may fall back to a Java implementation when a native service is not there. Set
     * {@code ASTRATTO_JAVA_FALLBACK=0} (or {@code -Dastratto.javaFallback=false}) and the engine's factories
     * ({@code NativeEngine.create}, {@code NativeRenderer.create}, {@code NativeMesher.create}) throw instead of returning
     * null: a run that passes is then known to have used the native core for everything.
     */
    public static boolean javaFallbackAllowed() {
        String p = System.getProperty("astratto.javaFallback");
        if (p != null) return !p.equalsIgnoreCase("false") && !p.equals("0");
        return !"0".equals(System.getenv("ASTRATTO_JAVA_FALLBACK"));
    }

    /** Returns null, or throws when Java fallbacks are disabled ({@link #javaFallbackAllowed}): what was not native, and why. */
    public static <T> T noNative(String what, String why) {
        if (javaFallbackAllowed()) return null;
        throw new IllegalStateException("Astratto: " + what + " is not available natively (" + why + ") and Java fallbacks are disabled (ASTRATTO_JAVA_FALLBACK=0)");
    }
    public static final String COPYRIGHT = "Astratto North of LudusValley Recreations, under VEIN Enterprises";
    public static final String NOTICE = "Copyright (c) " + COPYRIGHT;
    /** The systems that make it up, as they are credited. */
    public static final String[] SYSTEMS = {"Hybrid GI renderer", "NeuralAI", "Sydney Engine", "UltraEngine audio", "Native core (C++)"};

    /**
     * The mark (1.2): a capital A, cut. Two heavy strokes that meet in a flat-topped apex and stand on two feet; the
     * right-hand stroke is parted by a slanting cut a little above the middle, and the bar is not level - it rises from
     * the left foot's inside to meet the lower half of the right stroke, parallel to the cut. It is four four-sided
     * figures and nothing else: it is drawn from them at any size ({@link #drawLogo}), and the grid below - for a window
     * icon, or anything that wants cells - is those same figures, sampled.
     *
     * Each figure is {x, y, ...} round its corners, in a square 0..1 across and down.
     */
    public static final float[][] MARK = {
            {0.41f, 0.00f, 0.56f, 0.00f, 0.20f, 1.00f, 0.00f, 1.00f},              // the left stroke
            {0.44f, 0.00f, 0.59f, 0.00f, 0.7786f, 0.46f, 0.62f, 0.50f},            // the right stroke, above the cut
            {0.6416f, 0.56f, 0.8032f, 0.52f, 1.00f, 1.00f, 0.80f, 1.00f},          // and below it
            {0.2936f, 0.74f, 0.6668f, 0.63f, 0.6956f, 0.71f, 0.2612f, 0.83f}};     // the bar

    private static final int COLS = 28, ROWS = 30;

    /** The mark as a grid: '#' is the mark; '.' is clear. Sampled from {@link #MARK} once. */
    public static final String[] LOGO = raster();

    private static boolean inside(float[] q, float px, float py) {
        boolean pos = false, neg = false;
        for (int i = 0; i < 4; i++) {
            float ax = q[i * 2], ay = q[i * 2 + 1], bx = q[((i + 1) & 3) * 2], by = q[((i + 1) & 3) * 2 + 1];
            float c = (bx - ax) * (py - ay) - (by - ay) * (px - ax);
            if (c > 0) pos = true; else if (c < 0) neg = true;
        }
        return !(pos && neg);
    }

    private static String[] raster() {
        String[] rows = new String[ROWS];
        for (int r = 0; r < ROWS; r++) {
            StringBuilder sb = new StringBuilder(COLS);
            for (int c = 0; c < COLS; c++) {
                // a cell is the mark's when most of it is: four samples, three of them inside
                int in = 0;
                for (int s = 0; s < 4; s++) {
                    float px = (c + 0.25f + 0.5f * (s & 1)) / COLS, py = (r + 0.25f + 0.5f * (s >> 1)) / ROWS;
                    for (float[] q : MARK) if (inside(q, px, py)) { in++; break; }
                }
                sb.append(in >= 2 ? '#' : '.');
            }
            rows[r] = sb.toString();
        }
        return rows;
    }

    public static int logoWidth() { return COLS; }
    public static int logoHeight() { return ROWS; }

    /**
     * Draws the mark with its top-left corner at (x, y), in the box a grid of cells of the given size would fill -
     * drawn as the four figures it is, edge to edge, not as cells.
     */
    public static void drawLogo(Batch2D b, float x, float y, float cell, int argb) {
        float w = COLS * cell, h = ROWS * cell;
        for (float[] q : MARK) Vector2D.quad(b, x + q[0] * w, y + q[1] * h, x + q[2] * w, y + q[3] * h, x + q[4] * w, y + q[5] * h, x + q[6] * w, y + q[7] * h, argb);
    }

    /** The mark as pixels (ARGB, row-major, clear where it is not): for a window icon or anything else that wants an image. */
    public static int[] logoPixels(int scale, int argb) {
        int w = COLS * scale, h = ROWS * scale;
        int[] px = new int[w * h];
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
            float fx = (x + 0.5f) / w, fy = (y + 0.5f) / h;
            for (float[] q : MARK) if (inside(q, fx, fy)) { px[y * w + x] = argb; break; }
        }
        return px;
    }
}
