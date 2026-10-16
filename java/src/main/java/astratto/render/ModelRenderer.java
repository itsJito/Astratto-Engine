package astratto.render;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL15.*;
import static org.lwjgl.opengl.GL20.*;
import static org.lwjgl.opengl.GL30.*;

import astratto.math.Mat4;
import astratto.voxel.VoxModel;
import java.nio.FloatBuffer;
import java.util.HashMap;
import java.util.Map;
import org.lwjgl.system.MemoryUtil;

/**
 * Small voxel models (characters, items, viewmodel, debris, tracers) as face-culled meshes with per-face normals.
 *
 * Two ways of drawing share one API. Outside a raid (menus, previews, the legacy renderer) a draw call renders
 * immediately with baked face shading. Inside the deferred renderer the same call is <em>recorded</em> into a
 * {@link DrawList}; the list is then replayed into the shadow cascades, the G-buffer and the forward pass, so a
 * character is described once and lit, shadowed and reflected like the world around it.
 */
public final class ModelRenderer {
    public static final class Mesh {
        int vao, vbo, count;
        /** Some voxel is see-through: the mesh is drawn in the forward pass. */
        public boolean translucent;
        /** When this mesh was last asked for (milliseconds): what the cache evicts by. */
        long used;
        /** 2.0: the vertex data, kept while {@link #keepVertices} is on (the native renderer is given each mesh once, by this). */
        public float[] vertices;
        /** 2.0: the native renderer's id for this mesh (0 until it has been given it). */
        public int nativeId;
        public int count() { return count; }
    }

    /** 2.0: keep each mesh's vertices on the CPU as well - set while the native renderer draws (it is handed them once). */
    public static volatile boolean keepVertices;
    /** 2.0: told of every mesh whose GL buffers are deleted (the native renderer lets its copy go too). */
    public static volatile java.util.function.Consumer<Mesh> onDelete;

    /**
     * 0.7: how many meshes the cache holds before it starts letting go of the ones nobody has drawn lately, and how
     * long a mesh has to go undrawn before it may be let go (milliseconds).
     *
     * Until 0.7 a full cache was emptied in one go - every mesh deleted, including the ones already recorded into
     * the frame being built. With a district full of people (twelve meshes each, and a new set whenever a look
     * changed) that happened mid-frame: everyone drawn so far pointed at deleted buffers and the whole crowd was
     * gone for a frame, then rebuilt at once on the next. Now nothing drawn recently is ever evicted, and nothing
     * is deleted while a frame that recorded it may still be replayed.
     */
    public static int CACHE_SOFT_LIMIT = 6000;
    public static long EVICT_AFTER_MS = 4000, GRAVE_MS = 400;
    private final java.util.ArrayDeque<Mesh> graveyard = new java.util.ArrayDeque<>();
    private final java.util.ArrayDeque<Long> graveTime = new java.util.ArrayDeque<>();
    private long now = System.nanoTime() / 1_000_000L, lastSweep;
    /** Meshes evicted, whole-cache clears (always zero since 0.7: kept as a gate), and the size the cache last swept at. */
    public int evicted, fullClears, sweptAt;

    public int cacheSize() { return cache.size(); }

    /** Start of a frame: the clock the cache ages by moves on, and meshes retired a moment ago are finally deleted. */
    public void frameStart() {
        now = System.nanoTime() / 1_000_000L;
        while (!graveyard.isEmpty() && now - graveTime.peekFirst() > GRAVE_MS) {
            graveTime.pollFirst();
            Mesh m = graveyard.pollFirst();
            glDeleteBuffers(m.vbo); glDeleteVertexArrays(m.vao);
            java.util.function.Consumer<Mesh> d = onDelete;
            if (d != null && m.nativeId != 0) d.accept(m);
        }
    }

    /** Lets go of meshes nothing has asked for in a while. Never touches one used recently, so never one in the frame being built. */
    private void sweep() {
        if (cache.size() <= CACHE_SOFT_LIMIT || now - lastSweep < 500) return;
        lastSweep = now;
        sweptAt = cache.size();
        java.util.Iterator<Map.Entry<String, Mesh>> it = cache.entrySet().iterator();
        while (it.hasNext()) {
            Mesh m = it.next().getValue();
            if (now - m.used > EVICT_AFTER_MS) { it.remove(); free(m); evicted++; }
        }
    }

    private Shader shader;
    private final Map<String, Mesh> cache = new HashMap<>();
    private Mesh cube;
    private final Mat4 tmp = new Mat4(), pre = new Mat4();
    /** Recording target (null = immediate mode) and an optional matrix applied in front of every model matrix. */
    private DrawList target;
    private boolean premultiply;
    /** Surface response of what is drawn next (deferred renderer only). Reset to the defaults by {@link #material()}. */
    public float rough = 0.82f, metal = 0f;
    /** Bits OR-ed into the flags of what is drawn next: see {@link DrawList}. */
    public int flags;

    public void init() {
        shader = new Shader(VS, FS);
        VoxModel c = new VoxModel(1, 1, 1);
        c.set(0, 0, 0, 0xFFFFFFFF);
        cube = build(c, 1f, true);
    }

    // ------------------------------------------------------------------ recording
    /** Draw calls go to this list until {@link #stop()}; begin/end become no-ops. */
    public void record(DrawList list) { frameStart(); target = list; premultiply = false; material(); flags = 0; }

    /** Records with a matrix in front (the viewmodel is authored in eye space and placed in the world by the inverse view). */
    public void record(DrawList list, Mat4 front) { target = list; pre.set(front); premultiply = true; material(); flags = 0; }

    public void stop() { target = null; premultiply = false; }
    public boolean recording() { return target != null; }

    public ModelRenderer material() { rough = 0.82f; metal = 0f; return this; }
    public ModelRenderer material(float roughness, float metallic) { rough = roughness; metal = metallic; return this; }

    public Mesh mesh(String key, VoxModel m, float voxelSize) {
        Mesh me = cache.get(key);
        if (me == null) {
            sweep();
            me = build(m, voxelSize, true);
            cache.put(key, me);
        }
        me.used = now;
        return me;
    }

    /** Mesh whose origin is a pivot inside the model grid (skinned character parts: one mesh per SKIN group). */
    public Mesh meshAt(String key, VoxModel m, float ox, float oy, float oz) {
        Mesh me = cache.get(key);
        if (me == null) {
            sweep();
            me = build(m, 1f, -ox, -oy, -oz);
            cache.put(key, me);
        }
        me.used = now;
        return me;
    }

    /** Replaces (or creates) a cached mesh: damaged body chunks are rebuilt in place under their own key. */
    public Mesh replace(String key, VoxModel m, float ox, float oy, float oz) {
        Mesh old = cache.remove(key);
        if (old != null) free(old);
        Mesh me = build(m, 1f, -ox, -oy, -oz);
        me.used = now;
        cache.put(key, me);
        return me;
    }

    public Mesh cached(String key) { Mesh me = cache.get(key); if (me != null) me.used = now; return me; }
    public void drop(String key) { Mesh old = cache.remove(key); if (old != null) free(old); }

    /** A mesh leaves the cache: its buffers are deleted a moment later, once no recorded frame can still be drawing it. */
    private void free(Mesh m) { graveyard.addLast(m); graveTime.addLast(now); }

    /** Builds a mesh centred on the model's bottom-centre (x, z centre; y = 0). */
    public static Mesh build(VoxModel m, float s, boolean centre) {
        return build(m, s, centre ? -m.sx / 2f : 0, 0, centre ? -m.sz / 2f : 0);
    }

    /** Bytes per vertex: position (3 floats), colour (4 floats), normal (3 floats). */
    static final int STRIDE = 40;

    /**
     * Builds the mesh of a voxel model with greedy quads: on every slice of every face direction, neighbouring
     * exposed faces of one colour are merged into the largest rectangles that cover them. A character part that
     * would be several hundred little squares becomes a few dozen quads, which is what lets a street full of
     * people be drawn again into each shadow cascade.
     */
    public static Mesh build(VoxModel m, float s, float ox, float oy, float oz) {
        int[] count = new int[1];
        boolean[] see = new boolean[1];
        float[] v = vertices(m, s, ox, oy, oz, count, see);
        return upload(v, count[0], see[0]);
    }

    /**
     * The vertex data of a voxel model (position, colour, normal per vertex; six vertices a quad). Pure: no GL, no
     * shared state, so it may run on any thread. count receives the number of vertices, seeThrough whether any
     * voxel is translucent.
     */
    public static float[] vertices(VoxModel m, float s, float ox, float oy, float oz, int[] count, boolean[] seeThrough) {
        int[] dim = {m.sx, m.sy, m.sz};
        float[] out = new float[4096];
        int n = 0;
        boolean see = false;
        int[] p = new int[3], q = new int[3];
        float[] c0 = new float[3], du = new float[3], dv = new float[3];
        final float[][] corner = {{0, 0}, {1, 0}, {1, 1}, {1, 1}, {0, 1}, {0, 0}};
        for (int f = 0; f < 6; f++) {
            int a = f / 2, u = (a + 1) % 3, v = (a + 2) % 3, sign = (f & 1) == 0 ? 1 : -1;
            int[] mask = new int[dim[u] * dim[v]];
            for (int slice = 0; slice < dim[a]; slice++) {
                boolean any = false;
                for (int j = 0; j < dim[v]; j++) for (int i = 0; i < dim[u]; i++) {
                    p[a] = slice; p[u] = i; p[v] = j;
                    int c = m.get(p[0], p[1], p[2]);
                    int k = 0;
                    if (c != 0) {
                        q[0] = p[0]; q[1] = p[1]; q[2] = p[2];
                        q[a] += sign;
                        if (m.get(q[0], q[1], q[2]) == 0) { k = c; any = true; if ((c >>> 24) < 250) see = true; }
                    }
                    mask[i + j * dim[u]] = k;
                }
                if (!any) continue;
                for (int j = 0; j < dim[v]; j++) for (int i = 0; i < dim[u]; ) {
                    int c = mask[i + j * dim[u]];
                    if (c == 0) { i++; continue; }
                    int w = 1;
                    while (i + w < dim[u] && mask[i + w + j * dim[u]] == c) w++;
                    int h = 1;
                    grow:
                    while (j + h < dim[v]) {
                        for (int k = 0; k < w; k++) if (mask[i + k + (j + h) * dim[u]] != c) break grow;
                        h++;
                    }
                    for (int jj = 0; jj < h; jj++) for (int k = 0; k < w; k++) mask[i + k + (j + jj) * dim[u]] = 0;
                    // corner of the rectangle on the face plane, and its two edge vectors
                    c0[a] = slice + (sign > 0 ? 1 : 0); c0[u] = i; c0[v] = j;
                    du[0] = du[1] = du[2] = 0; dv[0] = dv[1] = dv[2] = 0;
                    du[u] = w; dv[v] = h;
                    float r = ((c >> 16) & 255) / 255f, g = ((c >> 8) & 255) / 255f, bl = (c & 255) / 255f, al = ((c >>> 24) & 255) / 255f;
                    float nx = a == 0 ? sign : 0, ny = a == 1 ? sign : 0, nz = a == 2 ? sign : 0;
                    if (n + 60 > out.length) out = java.util.Arrays.copyOf(out, out.length * 2);
                    for (float[] cr : corner) {
                        out[n++] = (c0[0] + du[0] * cr[0] + dv[0] * cr[1] + ox) * s;
                        out[n++] = (c0[1] + du[1] * cr[0] + dv[1] * cr[1] + oy) * s;
                        out[n++] = (c0[2] + du[2] * cr[0] + dv[2] * cr[1] + oz) * s;
                        out[n++] = r; out[n++] = g; out[n++] = bl; out[n++] = al;
                        out[n++] = nx; out[n++] = ny; out[n++] = nz;
                    }
                    i += w;
                }
            }
        }
        count[0] = n / 10;
        seeThrough[0] = see;
        return out;
    }

    /** Creates the GL objects for vertex data produced by {@link #vertices} (render thread). */
    public static Mesh upload(float[] verts, int vertexCount, boolean translucent) {
        FloatBuffer fb = MemoryUtil.memAllocFloat(Math.max(10, vertexCount * 10));
        fb.put(verts, 0, vertexCount * 10);
        fb.flip();
        Mesh me = new Mesh();
        me.translucent = translucent;
        me.vao = glGenVertexArrays();
        me.vbo = glGenBuffers();
        glBindVertexArray(me.vao);
        glBindBuffer(GL_ARRAY_BUFFER, me.vbo);
        glBufferData(GL_ARRAY_BUFFER, fb, GL_STATIC_DRAW);
        glVertexAttribPointer(0, 3, GL_FLOAT, false, STRIDE, 0);
        glVertexAttribPointer(1, 4, GL_FLOAT, false, STRIDE, 12);
        glVertexAttribPointer(2, 3, GL_FLOAT, false, STRIDE, 28);
        glEnableVertexAttribArray(0);
        glEnableVertexAttribArray(1);
        glEnableVertexAttribArray(2);
        glBindVertexArray(0);
        me.count = vertexCount;
        if (keepVertices) me.vertices = java.util.Arrays.copyOf(verts, vertexCount * 10);
        MemoryUtil.memFree(fb);
        return me;
    }

    /** Replaces (or creates) a cached mesh from ready-made vertex data. */
    public Mesh replace(String key, float[] verts, int vertexCount, boolean translucent) {
        Mesh old = cache.remove(key);
        if (old != null) free(old);
        Mesh me = upload(verts, vertexCount, translucent);
        me.used = now;
        cache.put(key, me);
        return me;
    }

    // ------------------------------------------------------------------ drawing
    public void begin(Mat4 pv, astratto.render.Env e) {
        if (target != null) return;
        frameStart();
        shader.use();
        shader.mat4("uPV", pv);
        shader.f3("uFog", e.fogR, e.fogG, e.fogB);
        shader.f1("uFogDensity", e.fogDensity);
        shader.f3("uCam", e.camX, e.camY, e.camZ);
        glEnable(GL_DEPTH_TEST);
        glDisable(GL_CULL_FACE);
        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
    }

    public void draw(Mesh m, Mat4 model, float light, int tintArgb, float emissive) {
        if (target != null) {
            if (premultiply) { tmp2.set(pre).mul(model); target.add(m, tmp2, tintArgb, emissive, light, rough, metal, flags); }
            else target.add(m, model, tintArgb, emissive, light, rough, metal, flags);
            return;
        }
        shader.mat4("uModel", model);
        shader.f1("uLight", light);
        shader.f1("uEmissive", emissive);
        shader.f4("uTint", ((tintArgb >> 16) & 255) / 255f, ((tintArgb >> 8) & 255) / 255f, (tintArgb & 255) / 255f, ((tintArgb >>> 24) & 255) / 255f);
        glBindVertexArray(m.vao);
        glDrawArrays(GL_TRIANGLES, 0, m.count);
    }

    private final Mat4 tmp2 = new Mat4();

    /** Axis-aligned or transformed solid cube: model maps the unit cube. */
    public void cube(Mat4 model, int argb, float light, float emissive) { draw(cube, model, light, argb, emissive); }

    /** Thin box from a to b (tracers, beams). */
    public void beam(float ax, float ay, float az, float bx, float by, float bz, float thick, int argb, float emissive) {
        float dx = bx - ax, dy = by - ay, dz = bz - az, len = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (len < 1e-4f) return;
        float yaw = (float) Math.atan2(dx, dz), pitch = (float) Math.asin(dy / len);
        tmp.identity().translate(ax, ay, az).rotateY(yaw).rotateX(-pitch).scale(thick, thick, len).translate(0, -0.5f, 0.5f);
        cube(tmp, argb, 1, emissive);
    }

    public void end() {
        if (target != null) return;
        glBindVertexArray(0); glDisable(GL_BLEND); glEnable(GL_CULL_FACE);
    }

    /** Raw draw of a recorded entry's geometry (the deferred renderer sets its own program and uniforms). */
    public static void drawGeometry(Mesh m) {
        glBindVertexArray(m.vao);
        glDrawArrays(GL_TRIANGLES, 0, m.count);
    }

    private static final String VS = astratto.render.Shaders.source("model_simple.vert");
    private static final String FS = astratto.render.Shaders.source("model_simple.frag");
}
