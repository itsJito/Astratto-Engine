package astratto.props;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL15.*;
import static org.lwjgl.opengl.GL20.*;
import static org.lwjgl.opengl.GL30.*;
import static org.lwjgl.opengl.GL31.*;
import static org.lwjgl.opengl.GL33.*;

import astratto.math.Mat4;
import astratto.render.Shader;
import astratto.voxel.VoxModel;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.lwjgl.system.MemoryUtil;

/**
 * The Astratto prop system (1.0): static voxel models stood in a world by the thousand - furniture, fittings, machinery,
 * ornament - each drawn at a sixteenth of a block, lit, shadowed and fogged with the world.
 *
 * A world of blocks can only be furnished with as many kinds of thing as it has kinds of block. A prop is not a block:
 * it is a model with a place and a quarter-turn, so a building can have a hundred kinds of object in it and no two
 * rooms need be furnished from the same handful. Models are registered once ({@link #model}); a model's mesh is its
 * outside faces only, with faces of one colour merged into the largest rectangles that cover them, so a wardrobe is a
 * few dozen quads however many voxels it is made of. Voxels may glow (a lampshade, a screen): their colour's alpha is
 * read as emission.
 *
 * Instances live in 16 m patches. What is drawn is whatever stands in the patches round the camera: one instanced draw
 * per model, into the G-buffer and - for whatever is within shadow distance - the first shadow cascades. Each instance
 * carries the baked light of the cell it stands in, asked of the {@link LightSource} whenever its patch is uploaded.
 *
 * 2.0: given the engine's renderer ({@link #initNative}), the models and instance lists go to it ({@code LAYER_PROP})
 * and each frame is asked for with {@link #frame}.
 */
public final class Props {
    public interface LightSource {
        /** Baked light of a cell: sky light in the high four bits, block light in the low four. */
        int light(int x, int y, int z);
    }

    private static final int PATCH = 16, VF = 10, INST = 8;

    private static final class Model { String name; float[] mesh; int vao, vbo, ibo, verts, cap, drawn, nat; float height; boolean shadow = true; }
    /** One prop: where it stands, how it is turned, which model it is - and whether it is still there. */
    public static final class Instance { public int model, x, y, z, rot, id; public float ox, oy, oz, scale = 1; public boolean alive = true; }

    private final List<Model> models = new ArrayList<>();
    private final Map<String, Integer> byName = new HashMap<>();
    private final List<Instance> all = new ArrayList<>();
    private final Map<Long, List<Instance>> patches = new HashMap<>();
    private final Map<Long, Instance> byCell = new HashMap<>();
    public LightSource light;
    public float drawDistance = 72f, shadowDistance = 40f;
    public boolean enabled = true;
    private Shader gbuf, shadow;
    private boolean glReady, dirty = true;
    private astratto.bridge.NativeRenderer nat;
    private int lastCX = Integer.MIN_VALUE, lastCZ, sinceRefresh;
    private FloatBuffer scratch;
    public int instances, drawCalls, triangles;

    private static long pkey(int cx, int cz) { return ((long) cx << 32) | (cz & 0xFFFFFFFFL); }
    private static long ckey(int x, int y, int z) { return ((long) x << 40) | ((long) (y & 0xFFFFF) << 20) | (z & 0xFFFFF); }

    public int modelCount() { return models.size(); }
    public int count() { return all.size(); }
    public int id(String name) { Integer i = byName.get(name); return i == null ? -1 : i; }
    public String name(int model) { return model < 0 || model >= models.size() ? "" : models.get(model).name; }

    /** Registers a model: voxelSize metres to the voxel, its floor's middle at the origin. Returns its id (the same one if the name is known). */
    public int model(String name, VoxModel m, float voxelSize) {
        Integer known = byName.get(name);
        if (known != null) return known;
        Model md = new Model();
        md.name = name;
        md.mesh = mesh(m, voxelSize);
        md.verts = md.mesh.length / VF;
        md.height = m.sy * voxelSize;
        models.add(md);
        byName.put(name, models.size() - 1);
        if (nat != null) uploadNative(md);
        else if (glReady) upload(md);
        return models.size() - 1;
    }

    /** Forgets every instance (a new world); the models stay. */
    public void clear() { all.clear(); patches.clear(); byCell.clear(); dirty = true; }

    /** Stands a prop in cell (x, y, z), turned by quarter turns, nudged by (ox, oy, oz) metres from the middle of the cell's floor. */
    public Instance add(int model, int x, int y, int z, int rot, float ox, float oy, float oz) {
        Instance in = new Instance();
        in.model = model; in.x = x; in.y = y; in.z = z; in.rot = rot & 3; in.ox = ox; in.oy = oy; in.oz = oz; in.id = all.size();
        all.add(in);
        patches.computeIfAbsent(pkey(x >> 4, z >> 4), k -> new ArrayList<>()).add(in);
        byCell.putIfAbsent(ckey(x, y, z), in);
        dirty = true;
        return in;
    }

    /** Ties another cell to a prop that stands in more than one (a piano, a bed): removing any of its cells removes it. */
    public void claim(Instance in, int x, int y, int z) { byCell.put(ckey(x, y, z), in); }

    /** The prop whose cell this is, or null. */
    public Instance at(int x, int y, int z) { Instance in = byCell.get(ckey(x, y, z)); return in != null && in.alive ? in : null; }

    /** Takes a prop away (it was broken): returns it, or null if there was none there. */
    public Instance remove(int x, int y, int z) {
        Instance in = byCell.get(ckey(x, y, z));
        if (in == null || !in.alive) return null;
        in.alive = false;
        dirty = true;
        return in;
    }

    /** 1.2: the lists are built again next frame (an instance was changed in place: carried by a tool). */
    public void touch() { dirty = true; }

    /** 1.2: moves a prop to another cell: it is drawn from there, found there, and no longer found where it was. */
    public void move(Instance in, int x, int y, int z) {
        List<Instance> was = patches.get(pkey(in.x >> 4, in.z >> 4));
        if (was != null) was.remove(in);
        byCell.values().removeIf(v -> v == in);
        in.x = x; in.y = y; in.z = z;
        patches.computeIfAbsent(pkey(x >> 4, z >> 4), k -> new ArrayList<>()).add(in);
        byCell.put(ckey(x, y, z), in);
        dirty = true;
    }

    /** Puts back everything that was taken away (the world is restored between operations). */
    public void restore() { for (Instance in : all) in.alive = true; dirty = true; }

    public List<Instance> all() { return all; }

    // ------------------------------------------------------------------ meshing
    private static final int[][] AXES = {{0, 1, 2}, {1, 2, 0}, {2, 0, 1}};

    /** Outside faces, greedily merged by colour, as triangles: position, normal, colour (rgb), emission. The floor's middle is the origin. */
    static float[] mesh(VoxModel m, float v) {
        float[] out = new float[8192];
        int o = 0;
        int[] dims = {m.sx, m.sy, m.sz};
        float cx = m.sx / 2f, cz = m.sz / 2f;
        int[] p = new int[3];
        for (int axis = 0; axis < 3; axis++) {
            int u = AXES[axis][1], w = AXES[axis][2];
            int du = dims[u], dw = dims[w];
            int[] mask = new int[du * dw];
            for (int sign = -1; sign <= 1; sign += 2) for (int slice = 0; slice < dims[axis]; slice++) {
                boolean any = false;
                for (int a = 0; a < du; a++) for (int b = 0; b < dw; b++) {
                    p[axis] = slice; p[u] = a; p[w] = b;
                    int c = m.get(p[0], p[1], p[2]);
                    int key = 0;
                    if (c != 0) {
                        p[axis] = slice + sign;
                        if (m.get(p[0], p[1], p[2]) == 0) { key = c; any = true; }
                    }
                    mask[a + b * du] = key;
                }
                if (!any) continue;
                for (int b = 0; b < dw; b++) for (int a = 0; a < du; ) {
                    int c = mask[a + b * du];
                    if (c == 0) { a++; continue; }
                    int wdt = 1;
                    while (a + wdt < du && mask[a + wdt + b * du] == c) wdt++;
                    int hgt = 1;
                    grow:
                    while (b + hgt < dw) { for (int k = 0; k < wdt; k++) if (mask[a + k + (b + hgt) * du] != c) break grow; hgt++; }
                    for (int hh = 0; hh < hgt; hh++) for (int k = 0; k < wdt; k++) mask[a + k + (b + hh) * du] = 0;
                    // the quad's four corners
                    float[][] q = new float[4][3];
                    int[][] corner = {{0, 0}, {wdt, 0}, {wdt, hgt}, {0, hgt}};
                    for (int i = 0; i < 4; i++) { q[i][axis] = slice + (sign > 0 ? 1 : 0); q[i][u] = a + corner[i][0]; q[i][w] = b + corner[i][1]; }
                    float nx = axis == 0 ? sign : 0, ny = axis == 1 ? sign : 0, nz = axis == 2 ? sign : 0;
                    // shading baked per face: tops a little lighter, undersides darker, so a model reads even in flat light
                    float fs = axis == 1 ? (sign > 0 ? 1.05f : 0.72f) : axis == 0 ? 0.94f : 0.88f;
                    float cr = ((c >> 16) & 255) / 255f * fs, cg = ((c >> 8) & 255) / 255f * fs, cb = (c & 255) / 255f * fs;
                    // alpha below 255 marks a glowing voxel: 254 = fully emissive ... (models are painted with 0xFF for ordinary ones)
                    int al = (c >>> 24) & 255;
                    float em = al == 255 || al == 0 ? 0 : al / 254f;
                    int[] order = sign > 0 ? new int[]{0, 1, 2, 0, 2, 3} : new int[]{0, 2, 1, 0, 3, 2};
                    if (o + 6 * VF > out.length) out = java.util.Arrays.copyOf(out, out.length * 2);
                    for (int k : order) {
                        out[o++] = (q[k][0] - cx) * v; out[o++] = q[k][1] * v; out[o++] = (q[k][2] - cz) * v;
                        out[o++] = nx; out[o++] = ny; out[o++] = nz;
                        out[o++] = cr; out[o++] = cg; out[o++] = cb; out[o++] = em;
                    }
                    a += wdt;
                }
            }
        }
        return java.util.Arrays.copyOf(out, o);
    }

    // ------------------------------------------------------------------ 2.0: the engine's renderer
    /** Hands every model to the engine's renderer; from now on it draws the props (see {@link #frame}). */
    public void initNative(astratto.bridge.NativeRenderer r) {
        nat = r;
        if (scratch == null) scratch = MemoryUtil.memAllocFloat(INST * 2048);
        for (Model md : models) uploadNative(md);
        dirty = true;
    }

    private void uploadNative(Model md) {
        if (md.nat != 0 || md.mesh == null) return;
        md.nat = nat.layerMesh(astratto.bridge.AstrattoNative.LAYER_PROP, md.mesh, md.verts);
    }

    public boolean isNative() { return nat != null; }

    /** This frame's props, for the engine's renderer. */
    public void frame(astratto.bridge.RenderCommands c) { if (nat != null && enabled && instances > 0) c.props(drawDistance, shadowDistance); }

    // ------------------------------------------------------------------ GL
    public void init() {
        gbuf = new Shader(VS, FS_GBUF);
        shadow = new Shader(VS, FS_SHADOW);
        scratch = MemoryUtil.memAllocFloat(INST * 2048);
        glReady = true;
        for (Model md : models) upload(md);
        dirty = true;
    }

    private void upload(Model md) {
        md.vao = glGenVertexArrays(); md.vbo = glGenBuffers(); md.ibo = glGenBuffers();
        glBindVertexArray(md.vao);
        glBindBuffer(GL_ARRAY_BUFFER, md.vbo);
        glBufferData(GL_ARRAY_BUFFER, md.mesh.length == 0 ? new float[VF] : md.mesh, GL_STATIC_DRAW);
        glVertexAttribPointer(0, 3, GL_FLOAT, false, VF * 4, 0); glEnableVertexAttribArray(0);
        glVertexAttribPointer(1, 3, GL_FLOAT, false, VF * 4, 12); glEnableVertexAttribArray(1);
        glVertexAttribPointer(2, 4, GL_FLOAT, false, VF * 4, 24); glEnableVertexAttribArray(2);
        glBindBuffer(GL_ARRAY_BUFFER, md.ibo);
        md.cap = 64;
        glBufferData(GL_ARRAY_BUFFER, (long) md.cap * INST * 4, GL_DYNAMIC_DRAW);
        glVertexAttribPointer(3, 4, GL_FLOAT, false, INST * 4, 0); glEnableVertexAttribArray(3); glVertexAttribDivisor(3, 1);
        glVertexAttribPointer(4, 4, GL_FLOAT, false, INST * 4, 16); glEnableVertexAttribArray(4); glVertexAttribDivisor(4, 1);
        glBindVertexArray(0);
        glBindBuffer(GL_ARRAY_BUFFER, 0);
    }

    /** Rebuilds the instance lists when the camera has moved to another patch, something was added or removed, or the light may have changed. */
    public void update(float camX, float camZ) {
        if (!(glReady || nat != null) || !enabled) return;
        int ccx = (int) Math.floor(camX / PATCH), ccz = (int) Math.floor(camZ / PATCH);
        if (ccx != lastCX || ccz != lastCZ) { lastCX = ccx; lastCZ = ccz; dirty = true; }
        if (++sinceRefresh > 240) dirty = true;          // lamps come on and go off: the baked light is asked for again now and then
        if (!dirty) return;
        dirty = false; sinceRefresh = 0;
        int R = (int) Math.ceil(drawDistance / PATCH);
        List<List<Instance>> near = new ArrayList<>();
        for (int dx = -R; dx <= R; dx++) for (int dz = -R; dz <= R; dz++) { List<Instance> l = patches.get(pkey(ccx + dx, ccz + dz)); if (l != null) near.add(l); }
        instances = 0; triangles = 0;
        for (int mi = 0; mi < models.size(); mi++) {
            Model md = models.get(mi);
            scratch.clear();
            int n = 0;
            for (List<Instance> l : near) for (Instance in : l) {
                if (in.model != mi || !in.alive) continue;
                if (scratch.remaining() < INST) { FloatBuffer nb = MemoryUtil.memAllocFloat(scratch.capacity() * 2); scratch.flip(); nb.put(scratch); MemoryUtil.memFree(scratch); scratch = nb; }
                int lt = light == null ? 0xF0 : light.light(in.x, in.y, in.z);
                // a prop in a cell that is itself dark (a solid stand-in block) is lit from the brightest cell beside it
                if (light != null && lt == 0) for (int[] d : new int[][]{{1, 0, 0}, {-1, 0, 0}, {0, 0, 1}, {0, 0, -1}, {0, 1, 0}}) { int l2 = light.light(in.x + d[0], in.y + d[1], in.z + d[2]); if (((l2 >> 4) & 15) > ((lt >> 4) & 15)) lt = (lt & 15) | (l2 & 0xF0); if ((l2 & 15) > (lt & 15)) lt = (lt & 0xF0) | (l2 & 15); }
                scratch.put(in.x + 0.5f + in.ox).put(in.y + in.oy).put(in.z + 0.5f + in.oz).put(in.scale);
                scratch.put(in.rot * -1.5707964f).put(((lt >> 4) & 15) / 15f).put((lt & 15) / 15f).put(0);
                n++;
            }
            md.drawn = n;
            instances += n; triangles += n * md.verts / 3;
            if (nat != null) { scratch.flip(); nat.instances(md.nat, scratch, n, md.shadow ? astratto.bridge.AstrattoNative.INSTANCES_SHADOW : 0); continue; }
            if (n == 0 || md.vao == 0) continue;
            scratch.flip();
            glBindBuffer(GL_ARRAY_BUFFER, md.ibo);
            if (n > md.cap) { md.cap = Math.max(n, md.cap * 2); glBufferData(GL_ARRAY_BUFFER, (long) md.cap * INST * 4, GL_DYNAMIC_DRAW); }
            glBufferSubData(GL_ARRAY_BUFFER, 0, scratch);
        }
        glBindBuffer(GL_ARRAY_BUFFER, 0);
    }

    public void drawGBuffer(Mat4 pv, float camX, float camY, float camZ) { draw(gbuf, pv, camX, camY, camZ, drawDistance); }
    public void drawShadow(Mat4 lightPV, float camX, float camY, float camZ) { draw(shadow, lightPV, camX, camY, camZ, shadowDistance); }

    private void draw(Shader s, Mat4 pv, float camX, float camY, float camZ, float dist) {
        if (!glReady || !enabled || instances == 0) return;
        s.use();
        s.mat4("uPV", pv);
        s.f3("uCam", camX, camY, camZ);
        s.f1("uDist", dist);
        glEnable(GL_CULL_FACE);
        drawCalls = 0;
        for (Model md : models) {
            if (md.drawn == 0 || md.verts == 0) continue;
            glBindVertexArray(md.vao);
            glDrawArraysInstanced(GL_TRIANGLES, 0, md.verts, md.drawn);
            drawCalls++;
        }
        glBindVertexArray(0);
    }

    private static final String VS = astratto.render.Shaders.source("props.vert");

    private static final String FS_GBUF = astratto.render.Shaders.source("props_gbuffer.frag");

    private static final String FS_SHADOW = astratto.render.Shaders.source("props_shadow.frag");
}
