package starter;

import astratto.bridge.AstrattoNative;
import astratto.bridge.NativeEngine;
import astratto.bridge.NativeRenderer;
import astratto.bridge.RenderCommands;
import astratto.bridge.Snapshots;
import astratto.math.Mat4;
import java.nio.ByteBuffer;
import org.lwjgl.system.MemoryUtil;

/**
 * What the player sees, drawn by the engine's native renderer: the world (meshed, lit and shaded by the engine from
 * its own copy of the cells), and models for the bodies the engine simulates, placed where the last snapshot says they
 * are. The game describes a frame; every GPU resource and pass is the engine's.
 */
public final class View implements AutoCloseable {
    private final NativeRenderer r;
    private final RenderCommands cmd = new RenderCommands();
    private final Mat4 view = new Mat4(), proj = new Mat4(), model = new Mat4();
    private final int cube, crateCube, carBody;
    private final float[] m16 = new float[16];
    public NativeRenderer.Stats last;
    public int frames;
    private boolean first = true;

    public View(NativeEngine engine, int width, int height) {
        r = NativeRenderer.create(engine, width, height, AstrattoNative.QUALITY_HIGH, 1f);
        if (r == null) throw new IllegalStateException("no native renderer: " + NativeRenderer.createError);
        ByteBuffer atlas = EmptyMap.atlasPixels(), noise = EmptyMap.noisePixels();
        int atlasTex, noiseTex;
        try {
            atlasTex = r.texture(AstrattoNative.TEX_ARRAY, EmptyMap.TEX, EmptyMap.TEX, EmptyMap.LAYERS,
                    AstrattoNative.TEX_NEAREST | AstrattoNative.TEX_MIPMAP | AstrattoNative.TEX_REPEAT | AstrattoNative.texMaxLevel(4), atlas);
            noiseTex = r.texture(AstrattoNative.TEX_2D, 256, 256, 1, AstrattoNative.TEX_MIPMAP | AstrattoNative.TEX_MIP_LINEAR | AstrattoNative.TEX_REPEAT, noise);
        } finally { MemoryUtil.memFree(atlas); MemoryUtil.memFree(noise); }
        if (!r.worldLook(atlasTex, noiseTex, EmptyMap.LAYERS, EmptyMap.renderBlocks(), EmptyMap.renderShapes(), EmptyMap.STONE,
                EmptyMap.palette(), EmptyMap.layerMaterials(), EmptyMap.FLOOR, 0))
            throw new IllegalStateException("world look refused: " + r.lastError());
        cube = r.mesh(box(1, 1, 1, 0.85f, 0.78f, 0.70f, 1), 36);
        crateCube = r.mesh(box(1, 1, 1, 0.55f, 0.40f, 0.24f, 1), 36);
        carBody = r.mesh(box(1, 1, 1, 0.20f, 0.32f, 0.52f, 1), 36);
        if (cube == 0 || crateCube == 0 || carBody == 0) throw new IllegalStateException("meshes refused: " + r.lastError());
    }

    /** One frame: the camera at the player's eyes, the sun, the lamp, and every simulated body. */
    public void draw(long world, PlayerModule p, Snapshots.Bodies bodies, WatcherModule watcher, ToysModule toys, int width, int height, float dt, double time) {
        float aspect = width / (float) Math.max(1, height), fov = (float) Math.toRadians(70);
        float fx = (float) (-Math.sin(p.yaw) * Math.cos(p.pitch)), fy = (float) Math.sin(p.pitch), fz = (float) (-Math.cos(p.yaw) * Math.cos(p.pitch));
        float ex = p.x, ey = p.eyeY(), ez = p.z;
        view.lookAt(ex, ey, ez, ex + fx, ey + fy, ez + fz, 0, 1, 0);
        proj.perspective(fov, aspect, 0.05f, 400f);
        cmd.world(world);
        cmd.camera(view.m, proj.m, proj.m, ex, ey, ez, fx, fy, fz, fov, aspect, 0.05f, 400f, 200f, dt, first);
        first = false;
        float sunA = 0.9f;
        cmd.env(0.35f, sunA, 0.45f, 1f, (float) time, 0, 0, 0, 0.1f, 0,
                0.62f, 0.66f, 0.72f, 1f / 260f, 1e9f, 2e9f, 0, 0, 1, 1, 1, 0.0035f, Float.NaN, 0, 0, 0.05f);
        cmd.pointLight(EmptyMap.LAMP_X, EmptyMap.LAMP_Y - 0.6f, EmptyMap.LAMP_Z, 9, 6f, 5.2f, 3.6f, AstrattoNative.LIGHT_SHADOW);
        // the watcher: a tall box
        Snapshots.Mover w = bodies.mover(watcher.body);
        if (w != null) { model.identity().translate(w.x, w.y + 0.9f, w.z).scale(0.6f, 1.8f, 0.6f); put(cube, 0.7f, 0.2f, 0.2f); }
        cmd.shadowBody(w == null ? 0 : w.x, w == null ? 0 : w.y, w == null ? 0 : w.z, 0.3f, 1.8f);
        // the person: a small block at every particle of the ragdoll
        Snapshots.Ragdoll rd = bodies.ragdoll(toys.person);
        if (rd != null) for (int i = 0; i < AstrattoNative.RD_PARTICLES; i++) {
            float s = i == AstrattoNative.RD_HEAD ? 0.26f : i == AstrattoNative.RD_PELVIS || i == AstrattoNative.RD_NECK ? 0.22f : 0.14f;
            model.identity().translate(rd.p[i * 3], rd.p[i * 3 + 1], rd.p[i * 3 + 2]).scale(s, s, s);
            put(cube, 0.9f, 0.85f, 0.75f);
        }
        // the car: its body box, carried by the engine's matrix
        Snapshots.Vehicle v = bodies.vehicle(toys.car);
        if (v != null) {
            System.arraycopy(v.matrix, 0, model.m, 0, 16);
            model.translate(0, 0.75f, 0).scale(1.8f, 0.9f, 4.4f);
            put(carBody, 1, 1, 1);
        }
        // the crate: its corners
        Snapshots.SoftBody sb = bodies.softBody(toys.crate);
        if (sb != null && sb.p != null) for (int i = 0; i + 2 < sb.p.length; i += 3) {
            model.identity().translate(sb.p[i], sb.p[i + 1], sb.p[i + 2]).scale(0.3f, 0.3f, 0.3f);
            put(crateCube, 1, 1, 1);
        }
        // a crosshair
        cmd.rect(0, width / 2f - 1, height / 2f - 6, 2, 12, 0, 0, 1, 1, 1, 1, 1, 0.8f, 0);
        cmd.rect(0, width / 2f - 6, height / 2f - 1, 12, 2, 0, 0, 1, 1, 1, 1, 1, 0.8f, 0);
        NativeRenderer.Stats s = r.frame(cmd, width, height);
        if (s == null) throw new IllegalStateException("frame refused: " + r.lastError());
        last = s;
        frames++;
    }

    private void put(int mesh, float r, float g, float b) {
        System.arraycopy(model.m, 0, m16, 0, 16);
        cmd.model(mesh, 0, m16, r, g, b, 1, 0, 1, 0.8f, 0);
    }

    /** The window as last drawn (RGBA, bottom row first). */
    public ByteBuffer pixels(int width, int height) {
        ByteBuffer px = MemoryUtil.memAlloc(width * height * 4);
        if (!r.readPixels(0, 0, width, height, px)) { MemoryUtil.memFree(px); throw new IllegalStateException("read back refused: " + r.lastError()); }
        return px;
    }

    /** A unit box centred on the origin, coloured, as triangles of 10-float vertices (position, colour, normal). */
    static float[] box(float sx, float sy, float sz, float r, float g, float b, float a) {
        float[] v = new float[36 * 10];
        float[][] n = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
        int k = 0;
        for (float[] f : n) {
            // two axes across the face
            float[] u = Math.abs(f[1]) > 0.5f ? new float[]{1, 0, 0} : new float[]{0, 1, 0};
            float[] w = {f[1] * u[2] - f[2] * u[1], f[2] * u[0] - f[0] * u[2], f[0] * u[1] - f[1] * u[0]};
            float[][] q = new float[4][3];
            int[][] sgn = {{-1, -1}, {1, -1}, {1, 1}, {-1, 1}};
            for (int c = 0; c < 4; c++) for (int i = 0; i < 3; i++) q[c][i] = (f[i] + u[i] * sgn[c][0] + w[i] * sgn[c][1]) * 0.5f * (i == 0 ? sx : i == 1 ? sy : sz);
            int[] order = {0, 1, 2, 0, 2, 3};
            // wind counter-clockwise seen from outside
            float[] e1 = sub(q[1], q[0]), e2 = sub(q[2], q[0]);
            float dot = (e1[1] * e2[2] - e1[2] * e2[1]) * f[0] + (e1[2] * e2[0] - e1[0] * e2[2]) * f[1] + (e1[0] * e2[1] - e1[1] * e2[0]) * f[2];
            if (dot < 0) order = new int[]{0, 2, 1, 0, 3, 2};
            for (int o : order) {
                float[] p = q[o];
                v[k++] = p[0]; v[k++] = p[1]; v[k++] = p[2]; v[k++] = r; v[k++] = g; v[k++] = b; v[k++] = a; v[k++] = f[0]; v[k++] = f[1]; v[k++] = f[2];
            }
        }
        return v;
    }

    private static float[] sub(float[] a, float[] b) { return new float[]{a[0] - b[0], a[1] - b[1], a[2] - b[2]}; }

    @Override public void close() { r.close(); cmd.free(); }
}
