package astratto.voxel;

import astratto.render.Tex;
import astratto.util.Rng;

/**
 * Small dense voxel model (items, character parts, weapons). Colours are ARGB; 0 = empty.
 * The same model feeds the pixel-art icon rasteriser, the spinning inspect view and the in-world
 * GL mesher, so UI icons and the 3D world always agree.
 */
public final class VoxModel {
    public final int sx, sy, sz;
    public final int[] col;

    public VoxModel(int sx, int sy, int sz) { this.sx = sx; this.sy = sy; this.sz = sz; col = new int[sx * sy * sz]; }

    public boolean in(int x, int y, int z) { return x >= 0 && y >= 0 && z >= 0 && x < sx && y < sy && z < sz; }
    public int get(int x, int y, int z) { return in(x, y, z) ? col[(x * sy + y) * sz + z] : 0; }
    public void set(int x, int y, int z, int c) { if (in(x, y, z)) col[(x * sy + y) * sz + z] = c; }

    /** Inclusive box. */
    public VoxModel box(int x0, int y0, int z0, int x1, int y1, int z1, int c) {
        for (int x = Math.min(x0, x1); x <= Math.max(x0, x1); x++) for (int y = Math.min(y0, y1); y <= Math.max(y0, y1); y++)
            for (int z = Math.min(z0, z1); z <= Math.max(z0, z1); z++) set(x, y, z, c);
        return this;
    }

    /** Cylinder along the X axis centred on (cy, cz). */
    public VoxModel cylX(int x0, int x1, float cy, float cz, float r, int c) {
        for (int x = x0; x <= x1; x++) for (int y = 0; y < sy; y++) for (int z = 0; z < sz; z++) {
            float dy = y + 0.5f - cy, dz = z + 0.5f - cz;
            if (dy * dy + dz * dz <= r * r) set(x, y, z, c);
        }
        return this;
    }

    /** Cylinder along the Y axis centred on (cx, cz). */
    public VoxModel cylY(int y0, int y1, float cx, float cz, float r, int c) {
        for (int y = y0; y <= y1; y++) for (int x = 0; x < sx; x++) for (int z = 0; z < sz; z++) {
            float dx = x + 0.5f - cx, dz = z + 0.5f - cz;
            if (dx * dx + dz * dz <= r * r) set(x, y, z, c);
        }
        return this;
    }

    public VoxModel sphere(float cx, float cy, float cz, float r, int c) {
        for (int x = 0; x < sx; x++) for (int y = 0; y < sy; y++) for (int z = 0; z < sz; z++) {
            float dx = x + 0.5f - cx, dy = y + 0.5f - cy, dz = z + 0.5f - cz;
            if (dx * dx + dy * dy + dz * dz <= r * r) set(x, y, z, c);
        }
        return this;
    }

    /** Recolours voxels whose colour equals 'from'. */
    public VoxModel replace(int from, int to) { for (int i = 0; i < col.length; i++) if (col[i] == from) col[i] = to; return this; }

    /** Per-voxel tone jitter: the "HD pixeled on volume" material read. */
    public VoxModel jitter(float amount, int seed) {
        for (int x = 0; x < sx; x++) for (int y = 0; y < sy; y++) for (int z = 0; z < sz; z++) {
            int c = get(x, y, z);
            if (c == 0) continue;
            float h = Rng.hash(x, y, z, seed);
            set(x, y, z, Tex.shade(c, 1 - amount + h * amount * 2));
        }
        return this;
    }

    /** Rust / grime spots on exposed voxels proportional to wear (0..1). */
    public VoxModel weather(float wear, int seed) {
        if (wear <= 0.02f) return this;
        int rust = Tex.rgb(122, 64, 30), grime = Tex.rgb(40, 36, 30);
        for (int x = 0; x < sx; x++) for (int y = 0; y < sy; y++) for (int z = 0; z < sz; z++) {
            int c = get(x, y, z);
            if (c == 0) continue;
            float h = Rng.hash(x * 3, y * 5, z * 7, seed + 99);
            if (h < wear * 0.35f) set(x, y, z, Tex.mix(c, rust, 0.55f + h));
            else if (h < wear * 0.6f) set(x, y, z, Tex.mix(c, grime, 0.4f));
        }
        return this;
    }

    /** Copies another model into this one at an offset (non-empty voxels only). */
    public VoxModel paste(VoxModel o, int ox, int oy, int oz) {
        for (int x = 0; x < o.sx; x++) for (int y = 0; y < o.sy; y++) for (int z = 0; z < o.sz; z++) {
            int c = o.get(x, y, z);
            if (c != 0) set(x + ox, y + oy, z + oz, c);
        }
        return this;
    }

    public int count() { int n = 0; for (int c : col) if (c != 0) n++; return n; }
}
