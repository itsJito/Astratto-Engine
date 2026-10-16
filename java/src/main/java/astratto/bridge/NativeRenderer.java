package astratto.bridge;

import astratto.render.Shaders;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

/**
 * Astratto Engine: the native renderer, held from Java. Every GPU resource and every pass is the engine's (see
 * {@code astratto.h}, "the renderer"); Java hands over the shader sources once, makes textures and model meshes, says
 * what the world looks like, and then describes each frame in {@link RenderCommands}.
 *
 * It draws into the OpenGL 4.5 context current on the calling thread (made by {@code astratto.platform.Window}), and is
 * given GLFW's own loader - a C function - to find the GL with. Every call is made on that thread.
 */
public final class NativeRenderer implements AutoCloseable {
    /** What a frame did. */
    public static final class Stats {
        public int columnsMeshed, columnsDrawn, quadsDrawn, modelsDrawn, lights, probesUpdated, probesUnsettled, columnsPending;
        public float cpuMs, settled;
        @Override public String toString() {
            return String.format("%d columns drawn (%d meshed, %d waiting), %d world quads, %d models, %d lights, probes +%d (%d unsettled, %.0f%% settled near), %.2f ms",
                    columnsDrawn, columnsMeshed, columnsPending, quadsDrawn, modelsDrawn, lights, probesUpdated, probesUnsettled, settled * 100, cpuMs);
        }
    }

    private long handle;
    private final NativeEngine engine;
    private final ByteBuffer io = MemoryUtil.memCalloc(AstrattoNative.SIZEOF_RENDER_IO).order(ByteOrder.nativeOrder());
    private String lastError = "";
    private boolean freed;

    private NativeRenderer(long handle, NativeEngine engine) { this.handle = handle; this.engine = engine; }

    /** Why the last {@link #create} failed. */
    public static String createError = "";

    /**
     * A renderer for an engine, drawing into the GL context current on this thread; null when there is none, the context
     * is not OpenGL 4.5, or a shader does not compile ({@link #createError} says which).
     */
    public static NativeRenderer create(NativeEngine engine, int width, int height, int quality, float renderScale) {
        if (engine == null || !AstrattoNative.hasRenderer()) { createError = engine == null ? "no engine" : "this native core has no renderer"; return astratto.Astratto.noNative("the renderer", createError); }
        long loader = org.lwjgl.glfw.GLFW.Functions.GetProcAddress;
        if (loader == 0) { createError = "GLFW has not been initialised"; return astratto.Astratto.noNative("the renderer", createError); }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            // the engine's shader files, by name, with their includes resolved
            for (String name : Shaders.PROGRAMS) {
                String src;
                try { src = Shaders.source(name); } catch (IllegalStateException missing) { continue; }
                ByteBuffer n = MemoryUtil.memUTF8(name), s = MemoryUtil.memUTF8(src);
                try { AstrattoNative.rendererShader(engine.address(), MemoryUtil.memAddress(n), MemoryUtil.memAddress(s)); }
                finally { MemoryUtil.memFree(n); MemoryUtil.memFree(s); }
            }
            ByteBuffer d = stack.calloc(AstrattoNative.SIZEOF_RENDERER_DESC).order(ByteOrder.nativeOrder());
            d.putInt(0, AstrattoNative.SIZEOF_RENDERER_DESC).putInt(4, width).putInt(8, height).putInt(12, quality).putLong(16, loader).putFloat(24, renderScale);
            java.nio.LongBuffer out = stack.callocLong(1);
            int st = AstrattoNative.rendererCreate(engine.address(), d, MemoryUtil.memAddress(out));
            if (st != AstrattoNative.OK || out.get(0) == 0) { createError = AstrattoNative.status(st) + ": " + engine.lastErrorNow(); return astratto.Astratto.noNative("the renderer", createError); }
            createError = "";
            return new NativeRenderer(out.get(0), engine);
        }
    }

    public String lastError() { return lastError; }

    private boolean ok(int status) {
        if (status == AstrattoNative.OK) return true;
        lastError = AstrattoNative.status(status) + ": " + AstrattoNative.rendererError(handle);
        return false;
    }

    private void live() { if (handle == 0) throw new IllegalStateException("the renderer has been closed"); }

    /**
     * A texture from RGBA8 pixels (rows from the top; an array texture's layers one after another): kind
     * {@code TEX_2D} or {@code TEX_ARRAY}, flags {@code TEX_*}. The pixels are copied. Returns its id, or 0.
     */
    public int texture(int kind, int width, int height, int layers, int flags, ByteBuffer pixels) {
        live();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            ByteBuffer d = stack.calloc(AstrattoNative.SIZEOF_TEXTURE_DESC).order(ByteOrder.nativeOrder());
            d.putInt(0, AstrattoNative.SIZEOF_TEXTURE_DESC).putInt(4, kind).putInt(8, width).putInt(12, height).putInt(16, layers).putInt(20, flags)
                    .putLong(24, pixels == null ? 0 : MemoryUtil.memAddress(pixels)).putLong(32, pixels == null ? 0 : pixels.remaining());
            java.nio.IntBuffer out = stack.callocInt(1);
            return ok(AstrattoNative.renderTexture(handle, d, MemoryUtil.memAddress(out))) ? out.get(0) : 0;
        }
    }

    /** A model mesh: count vertices of 10 floats (position, colour with alpha, normal), three a triangle. Copied. Returns its id, or 0. */
    public int mesh(float[] vertices, int count) {
        live();
        java.nio.FloatBuffer v = MemoryUtil.memAllocFloat(Math.max(10, count * 10));
        try (MemoryStack stack = MemoryStack.stackPush()) {
            v.put(0, vertices, 0, count * 10);
            java.nio.IntBuffer out = stack.callocInt(1);
            return ok(AstrattoNative.renderMesh(handle, MemoryUtil.memAddress(v), count, MemoryUtil.memAddress(out))) ? out.get(0) : 0;
        } finally { MemoryUtil.memFree(v); }
    }

    /**
     * 2.1: a layer mesh ({@code LAYER_FOLIAGE}, {@code LAYER_PROP}, {@code LAYER_DECAL}): count vertices of 10 floats
     * (see {@code astratto.h}, "the renderer's layers"). Copied. Returns its id, or 0. Released as {@code RES_MESH}.
     */
    public int layerMesh(int kind, float[] vertices, int count) {
        live();
        java.nio.FloatBuffer v = MemoryUtil.memAllocFloat(Math.max(10, count * 10));
        try (MemoryStack stack = MemoryStack.stackPush()) {
            if (count > 0) v.put(0, vertices, 0, count * 10);
            java.nio.IntBuffer out = stack.callocInt(1);
            return ok(AstrattoNative.renderLayerMesh(handle, kind, MemoryUtil.memAddress(v), count, MemoryUtil.memAddress(out))) ? out.get(0) : 0;
        } finally { MemoryUtil.memFree(v); }
    }

    /** 2.1: the instances a FOLIAGE (12 floats each) or PROP (8 floats each) mesh is drawn with until set again, from a direct buffer holding count of them from its position (copied, not consumed); flags {@code INSTANCES_*}. */
    public boolean instances(int mesh, java.nio.FloatBuffer data, int count, int flags) {
        live();
        return ok(AstrattoNative.renderInstances(handle, mesh, count == 0 ? 0 : MemoryUtil.memAddress(data), count, flags));
    }

    /** Releases a texture or a mesh ({@code RES_*}); a mesh still named by a frame in flight goes a little later. */
    public boolean release(int kind, int id) { live(); return ok(AstrattoNative.renderRelease(handle, kind, id)); }

    /**
     * What the world looks like: the block texture array and a noise texture (ids from {@link #texture}), how many layers
     * the array has, the render table (as for the mesher), per block id its albedo and emitted light for the probes
     * (256 x 8 floats: r g b, unused, emitted r g b, unused), per layer its roughness, metalness and emission (layers x 4
     * bytes, or null), the street level, and the mesh flags ({@code MESH_*}).
     */
    public boolean worldLook(int atlas, int noise, int layers, NativeMesher.BlockDef[] blocks, NativeMesher.ShapeDef[] shapes, int belowFloorBlock,
                             float[] palette, byte[] layerMaterials, float groundY, int meshFlags) {
        live();
        if (palette.length < 256 * 8) throw new IllegalArgumentException("a palette is 256 x 8 floats");
        java.nio.FloatBuffer pal = MemoryUtil.memAllocFloat(256 * 8);
        ByteBuffer lm = layerMaterials == null ? null : MemoryUtil.memAlloc(layerMaterials.length);
        try {
            pal.put(0, palette, 0, 256 * 8);
            if (lm != null) lm.put(0, layerMaterials);
            long st = NativeMesher.withTable(blocks, shapes, belowFloorBlock, table -> {
                try (MemoryStack stack = MemoryStack.stackPush()) {
                    ByteBuffer look = stack.calloc(AstrattoNative.SIZEOF_WORLD_LOOK).order(ByteOrder.nativeOrder());
                    look.putInt(0, AstrattoNative.SIZEOF_WORLD_LOOK).putInt(4, atlas).putInt(8, noise).putInt(12, layers).putLong(16, MemoryUtil.memAddress(table))
                            .putLong(24, MemoryUtil.memAddress(pal)).putLong(32, lm == null ? 0 : MemoryUtil.memAddress(lm)).putFloat(40, groundY).putInt(44, meshFlags);
                    return AstrattoNative.renderWorldLook(handle, look);
                }
            });
            return ok((int) st);
        } finally {
            MemoryUtil.memFree(pal);
            if (lm != null) MemoryUtil.memFree(lm);
        }
    }

    /** Draws one frame from its description into the current context's window of the given size. The commands are reset. */
    public Stats frame(RenderCommands commands, int width, int height) {
        live();
        io.clear();
        io.putInt(0, AstrattoNative.SIZEOF_RENDER_IO).putInt(4, width).putInt(8, height).putInt(12, 0)
                .putLong(16, commands == null || commands.bytes() == 0 ? 0 : commands.address()).putInt(24, commands == null ? 0 : commands.bytes()).putInt(28, 0);
        for (int o = 32; o < AstrattoNative.SIZEOF_RENDER_IO; o += 4) io.putInt(o, 0);
        boolean good = ok(AstrattoNative.renderFrame(handle, engine.address(), io));
        if (commands != null) commands.reset();
        Stats s = new Stats();
        s.columnsMeshed = io.getInt(32); s.columnsDrawn = io.getInt(36); s.quadsDrawn = io.getInt(40); s.modelsDrawn = io.getInt(44);
        s.lights = io.getInt(48); s.probesUpdated = io.getInt(52); s.probesUnsettled = io.getInt(56); s.columnsPending = io.getInt(60);
        s.cpuMs = io.getFloat(64); s.settled = io.getFloat(68);
        return good ? s : null;
    }

    /** The window as last drawn: width x height x 4 bytes RGBA, bottom row first. */
    public boolean readPixels(int x, int y, int width, int height, ByteBuffer out) {
        live();
        if (out.remaining() < width * height * 4) throw new IllegalArgumentException("not room for the pixels");
        return ok(AstrattoNative.renderReadPixels(handle, x, y, width, height, MemoryUtil.memAddress(out)));
    }

    @Override public void close() {
        if (handle != 0) { AstrattoNative.rendererDestroy(handle); handle = 0; }
        if (!freed) { freed = true; MemoryUtil.memFree(io); }
    }
}
