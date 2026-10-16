package astratto.render;

import astratto.math.Mat4;

/**
 * A frame's worth of recorded model draws: mesh, matrix, tint, emission and surface response, in flat arrays that
 * are reused from frame to frame (no per-draw allocation). The deferred renderer replays a list several times -
 * shadow cascades, G-buffer, forward pass - picking entries by their flags.
 */
public final class DrawList {
    /** The entry only casts a shadow (the first-person body's hidden head and arms still shade the ground). */
    public static final int SHADOW_ONLY = 1;
    /** The entry never casts a shadow (decals, beams, markers). */
    public static final int NO_SHADOW = 2;
    /** Drawn after lighting, blended: see-through or self-lit things. Set automatically from tint alpha and emission. */
    public static final int FORWARD = 4;
    /** Additive blending in the forward pass (tracers, flashes, beacons). */
    public static final int ADDITIVE = 8;

    public int count;
    public ModelRenderer.Mesh[] mesh = new ModelRenderer.Mesh[1024];
    public float[] mat = new float[1024 * 16];
    /** Per entry: r, g, b, a, emissive, light, roughness, metallic. */
    public float[] par = new float[1024 * 8];
    public int[] flag = new int[1024];

    public void clear() { count = 0; }

    void add(ModelRenderer.Mesh m, Mat4 model, int argb, float emissive, float light, float rough, float metal, int flags) {
        if (m == null || m.count == 0) return;
        if (count == mesh.length) {
            int n = count * 2;
            mesh = java.util.Arrays.copyOf(mesh, n);
            mat = java.util.Arrays.copyOf(mat, n * 16);
            par = java.util.Arrays.copyOf(par, n * 8);
            flag = java.util.Arrays.copyOf(flag, n);
        }
        mesh[count] = m;
        System.arraycopy(model.m, 0, mat, count * 16, 16);
        int p = count * 8;
        float a = ((argb >>> 24) & 255) / 255f;
        par[p] = ((argb >> 16) & 255) / 255f; par[p + 1] = ((argb >> 8) & 255) / 255f; par[p + 2] = (argb & 255) / 255f; par[p + 3] = a;
        par[p + 4] = emissive; par[p + 5] = light; par[p + 6] = rough; par[p + 7] = metal;
        int f = flags;
        // anything see-through cannot live in a G-buffer; anything purely self-lit and see-through adds its light
        if (a < 0.98f || m.translucent) { f |= FORWARD | NO_SHADOW; if (emissive >= 0.99f && a < 0.98f) f |= ADDITIVE; }
        if (emissive > 0.5f) f |= NO_SHADOW;
        flag[count++] = f;
    }

    /** World-space origin of an entry (its matrix translation): where its baked light is sampled. */
    public float x(int i) { return mat[i * 16 + 12]; }
    public float y(int i) { return mat[i * 16 + 13]; }
    public float z(int i) { return mat[i * 16 + 14]; }
}
