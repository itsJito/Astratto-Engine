package astratto.voxel;

import astratto.render.Tex;

/**
 * CPU ray-caster that renders a {@link VoxModel} into a small ARGB image with an orthographic
 * camera, face shading and a 1-pixel dark outline - producing pixel-art icons and the spinning
 * inspect view straight from the same voxel data used in the world.
 */
public final class VoxRaster {
    private VoxRaster() {}

    /**
     * @param yaw   rotation about the vertical axis (radians)
     * @param pitch camera elevation (radians, positive looks down)
     * @param roll  in-plane rotation (radians) - used to lay long items along a tall footprint
     * @param fill  0..1 fraction of the image the model's bounding sphere should cover
     */
    public static int[] render(VoxModel m, int w, int h, float yaw, float pitch, float roll, float fill, boolean outline) {
        int[] out = new int[w * h];
        float[] depth = new float[w * h];
        java.util.Arrays.fill(depth, Float.MAX_VALUE);
        // fit to the voxels that are actually set, not to the grid they were authored in: a rifle drawn in a
        // 60x16 grid but only 48x9 long would otherwise float small inside its inventory footprint
        int[] bb = bounds(m);
        float cx = (bb[0] + bb[3]) / 2f, cy = (bb[1] + bb[4]) / 2f, cz = (bb[2] + bb[5]) / 2f;
        // camera basis
        float cp = (float) Math.cos(pitch), sp = (float) Math.sin(pitch), cyw = (float) Math.cos(yaw), syw = (float) Math.sin(yaw);
        float fx = -syw * cp, fy = -sp, fz = -cyw * cp;          // forward
        float rx = cyw, ry = 0, rz = -syw;                        // right
        float ux = ry * fz - rz * fy, uy = rz * fx - rx * fz, uz = rx * fy - ry * fx; // up = right x forward
        float ul = (float) Math.sqrt(ux * ux + uy * uy + uz * uz);
        ux /= ul; uy /= ul; uz /= ul;   // up = right x forward: model +y is up on screen
        // fit: project bounding box extents onto right/up
        float ext = 0;
        for (int k = 0; k < 8; k++) {
            float px = ((k & 1) == 0 ? bb[0] : bb[3]) - cx, py = ((k & 2) == 0 ? bb[1] : bb[4]) - cy, pz = ((k & 4) == 0 ? bb[2] : bb[5]) - cz;
            float a = px * rx + py * ry + pz * rz, b = px * ux + py * uy + pz * uz;
            float cr = (float) Math.cos(roll), sr = (float) Math.sin(roll);
            float a2 = a * cr - b * sr, b2 = a * sr + b * cr;
            ext = Math.max(ext, Math.max(Math.abs(a2) / (w / 2f), Math.abs(b2) / (h / 2f)));
        }
        float scale = ext / Math.max(0.05f, fill); // model units per pixel
        float dist = m.sx + m.sy + m.sz;
        float cr = (float) Math.cos(-roll), sr = (float) Math.sin(-roll);
        float[] L = norm(0.45f, 0.8f, 0.35f);
        for (int py = 0; py < h; py++) for (int px = 0; px < w; px++) {
            float a0 = (px + 0.5f - w / 2f) * scale, b0 = -(py + 0.5f - h / 2f) * scale;
            float a = a0 * cr - b0 * sr, b = a0 * sr + b0 * cr;
            float ox = cx + rx * a + ux * b - fx * dist, oy = cy + ry * a + uy * b - fy * dist, oz = cz + rz * a + uz * b - fz * dist;
            int hit = cast(m, ox, oy, oz, fx, fy, fz, dist * 2.2f, depth, py * w + px);
            if (hit == 0) continue;
            out[py * w + px] = hit;
        }
        // face shading is encoded in the cast; add outline for the pixel-art read
        if (outline) {
            int[] o2 = out.clone();
            for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
                if (out[y * w + x] != 0) continue;
                boolean edge = (x > 0 && out[y * w + x - 1] != 0) || (x < w - 1 && out[y * w + x + 1] != 0) || (y > 0 && out[(y - 1) * w + x] != 0) || (y < h - 1 && out[(y + 1) * w + x] != 0);
                if (edge) o2[y * w + x] = 0xE0100E0C;
            }
            out = o2;
        }
        return out;
    }

    /** Tight bounds of the set voxels: {x0, y0, z0, x1, y1, z1} with exclusive upper corners (the whole grid when empty). */
    public static int[] bounds(VoxModel m) {
        int x0 = m.sx, y0 = m.sy, z0 = m.sz, x1 = 0, y1 = 0, z1 = 0;
        for (int x = 0; x < m.sx; x++) for (int y = 0; y < m.sy; y++) for (int z = 0; z < m.sz; z++) {
            if (m.get(x, y, z) == 0) continue;
            if (x < x0) x0 = x; if (y < y0) y0 = y; if (z < z0) z0 = z;
            if (x >= x1) x1 = x + 1; if (y >= y1) y1 = y + 1; if (z >= z1) z1 = z + 1;
        }
        if (x1 <= x0) return new int[]{0, 0, 0, m.sx, m.sy, m.sz};
        return new int[]{x0, y0, z0, x1, y1, z1};
    }

    private static float[] norm(float x, float y, float z) { float l = (float) Math.sqrt(x * x + y * y + z * z); return new float[]{x / l, y / l, z / l}; }

    /** Amanatides-Woo DDA through the model grid. Returns shaded colour or 0. */
    private static int cast(VoxModel m, float ox, float oy, float oz, float dx, float dy, float dz, float maxT, float[] depth, int di) {
        // clip ray to the model AABB
        float t0 = 0, t1 = maxT;
        float[] o = {ox, oy, oz}, d = {dx, dy, dz}, hi = {m.sx, m.sy, m.sz};
        for (int a = 0; a < 3; a++) {
            if (Math.abs(d[a]) < 1e-7f) { if (o[a] < 0 || o[a] > hi[a]) return 0; continue; }
            float ta = (0 - o[a]) / d[a], tb = (hi[a] - o[a]) / d[a];
            if (ta > tb) { float t = ta; ta = tb; tb = t; }
            t0 = Math.max(t0, ta); t1 = Math.min(t1, tb);
            if (t0 > t1) return 0;
        }
        float t = t0 + 1e-4f;
        int x = (int) Math.floor(ox + dx * t), y = (int) Math.floor(oy + dy * t), z = (int) Math.floor(oz + dz * t);
        int stx = dx > 0 ? 1 : -1, sty = dy > 0 ? 1 : -1, stz = dz > 0 ? 1 : -1;
        float tdx = Math.abs(1 / (dx == 0 ? 1e-7f : dx)), tdy = Math.abs(1 / (dy == 0 ? 1e-7f : dy)), tdz = Math.abs(1 / (dz == 0 ? 1e-7f : dz));
        float px = ox + dx * t, py = oy + dy * t, pz = oz + dz * t;
        float tmx = (dx > 0 ? (x + 1 - px) : (px - x)) * tdx + t, tmy = (dy > 0 ? (y + 1 - py) : (py - y)) * tdy + t, tmz = (dz > 0 ? (z + 1 - pz) : (pz - z)) * tdz + t;
        int face = -1;
        // entry face from the AABB clip
        {
            float ex = Math.abs(px - Math.round(px)), ey = Math.abs(py - Math.round(py)), ez = Math.abs(pz - Math.round(pz));
            face = ex < ey && ex < ez ? 0 : (ey < ez ? 1 : 2);
        }
        for (int i = 0; i < 256; i++) {
            if (!m.in(x, y, z)) { if (t > t1 + 1) return 0; }
            else {
                int c = m.get(x, y, z);
                if (c != 0) {
                    float shade = face == 1 ? (sty < 0 ? 1.0f : 0.5f) : face == 0 ? 0.78f : 0.64f;
                    // subtle top-left key light variation per face direction
                    if (face == 0 && stx < 0) shade = 0.86f;
                    if (face == 2 && stz < 0) shade = 0.72f;
                    depth[di] = t;
                    int a = (c >>> 24) & 255;
                    return (a << 24) | (Tex.shade(c, shade) & 0xFFFFFF);
                }
            }
            if (tmx < tmy && tmx < tmz) { x += stx; t = tmx; tmx += tdx; face = 0; }
            else if (tmy < tmz) { y += sty; t = tmy; tmy += tdy; face = 1; }
            else { z += stz; t = tmz; tmz += tdz; face = 2; }
            if (t > t1 + 0.5f) return 0;
        }
        return 0;
    }
}
