package astratto.bridge;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.lwjgl.system.MemoryUtil;

/**
 * Astratto Engine: one frame's description for the native renderer - a camera, an environment, lights, model instances,
 * particles, 2D quads - written here and handed over whole in {@link NativeRenderer#frame}. Each record is the C structure
 * of the same name ({@code astratto_rcmd_*}). Nothing is drawn when a method here is called: the renderer reads the batch
 * during the frame call and keeps none of it.
 */
public final class RenderCommands {
    private ByteBuffer buf;
    private int records;

    public RenderCommands(int capacity) { buf = MemoryUtil.memAlloc(Math.max(1024, capacity)).order(ByteOrder.nativeOrder()); }
    public RenderCommands() { this(1 << 16); }

    private void room(int bytes) {
        if (buf.remaining() >= bytes) return;
        buf = MemoryUtil.memRealloc(buf, Math.max(buf.capacity() * 2, buf.position() + bytes)).order(ByteOrder.nativeOrder());
    }
    private RenderCommands head(int type, int bytes) { room(bytes); buf.putInt(type).putInt(bytes); records++; return this; }
    private void put16(float[] m) { for (int i = 0; i < 16; i++) buf.putFloat(m[i]); }

    /**
     * The camera: view, projection and the first-person viewmodel's projection (column-major), where it is and the way it
     * looks, its vertical field of view and aspect, near and far planes, how far the world is drawn, the frame's dt, and
     * whether the view jumped (no history to blend with).
     */
    public void camera(float[] view, float[] proj, float[] viewmodelProj, float x, float y, float z, float fx, float fy, float fz,
                       float fovY, float aspect, float near, float far, float viewDistance, float dt, boolean reset) {
        camera(view, proj, viewmodelProj, x, y, z, fx, fy, fz, fovY, aspect, near, far, viewDistance, dt, reset, false);
    }

    /** As above; prepare: a loading screen - the world is meshed and its light settled as fast as it can be. */
    public void camera(float[] view, float[] proj, float[] viewmodelProj, float x, float y, float z, float fx, float fy, float fz,
                       float fovY, float aspect, float near, float far, float viewDistance, float dt, boolean reset, boolean prepare) {
        head(AstrattoNative.RCMD_CAMERA, 256);
        put16(view); put16(proj); put16(viewmodelProj);
        buf.putFloat(x).putFloat(y).putFloat(z).putFloat(fx).putFloat(fy).putFloat(fz);
        buf.putFloat(fovY).putFloat(aspect).putFloat(near).putFloat(far).putFloat(viewDistance).putFloat(dt);
        buf.putInt(reset ? 1 : 0).putInt(prepare ? 1 : 0);
    }

    /** A quality preset ({@code QUALITY_*}), the passes that are on ({@code PASS_*}) and the render scale; kept until changed. */
    public void quality(int preset, int passes, float renderScale) { head(AstrattoNative.RCMD_QUALITY, 24).buf.putInt(preset).putInt(passes).putFloat(renderScale).putInt(0); }

    /** What the frame is lit and fogged by (see {@code astratto_rcmd_env}); fogOriginX NaN measures fog from the camera. */
    public void env(float sunX, float sunY, float sunZ, float daylight, float time, float rain, float wet, float snow, float overcast, float lightning,
                    float fogR, float fogG, float fogB, float fogDensity, float wallStart, float wallEnd, float skyHorizon, float skyZenith,
                    float tintR, float tintG, float tintB, float ambientFloor, float fogOriginX, float fogOriginY, float fogOriginZ, float bloom) {
        head(AstrattoNative.RCMD_ENV, 112);
        buf.putFloat(sunX).putFloat(sunY).putFloat(sunZ).putFloat(daylight).putFloat(time).putFloat(rain).putFloat(wet).putFloat(snow).putFloat(overcast).putFloat(lightning);
        buf.putFloat(fogR).putFloat(fogG).putFloat(fogB).putFloat(fogDensity).putFloat(wallStart).putFloat(wallEnd).putFloat(skyHorizon).putFloat(skyZenith);
        buf.putFloat(tintR).putFloat(tintG).putFloat(tintB).putFloat(ambientFloor).putFloat(fogOriginX).putFloat(fogOriginY).putFloat(fogOriginZ).putFloat(bloom);
    }

    /** A light: where, how far it reaches, its colour (radiance per unit albedo), flags ({@code LIGHT_*}) and for a spot its direction and cone. */
    public void light(float x, float y, float z, float radius, float r, float g, float b, int flags, float dx, float dy, float dz, float cosOuter) {
        head(AstrattoNative.RCMD_LIGHT, 56).buf.putFloat(x).putFloat(y).putFloat(z).putFloat(radius).putFloat(r).putFloat(g).putFloat(b).putInt(flags)
                .putFloat(dx).putFloat(dy).putFloat(dz).putFloat(cosOuter);
    }
    public void pointLight(float x, float y, float z, float radius, float r, float g, float b, int flags) { light(x, y, z, radius, r, g, b, flags, 0, -1, 0, -1); }

    /** A model instance: a mesh made with {@link NativeRenderer#mesh}, flags ({@code MODEL_*}), its matrix, tint, emission, light, roughness, metalness. */
    public void model(int mesh, int flags, float[] matrix, float r, float g, float b, float a, float emissive, float light, float roughness, float metallic) {
        head(AstrattoNative.RCMD_MODEL, 112).buf.putInt(mesh).putInt(flags);
        put16(matrix);
        buf.putFloat(r).putFloat(g).putFloat(b).putFloat(a).putFloat(emissive).putFloat(light).putFloat(roughness).putFloat(metallic);
    }

    /** Particles: count x 12 floats (x y z size, r g b a, rotation kind age seed) from data. */
    public void particles(float[] data, int count) {
        head(AstrattoNative.RCMD_PARTICLES, 16 + count * 48).buf.putInt(count).putInt(0);
        for (int i = 0; i < count * 12; i++) buf.putFloat(data[i]);
    }

    /** 2D quads in pixels from the top-left: count x 4 vertices of x y u v r g b a mode (mode 1: alpha from the texture's red). Texture 0 is white. */
    public void quads(int texture, float[] vertices, int count) {
        head(AstrattoNative.RCMD_QUADS, 16 + count * 144).buf.putInt(texture).putInt(count);
        for (int i = 0; i < count * 36; i++) buf.putFloat(vertices[i]);
    }

    /** One axis-aligned 2D rectangle, coloured (and textured when texture is not 0). */
    public void rect(int texture, float x, float y, float w, float h, float u0, float v0, float u1, float v1, float r, float g, float b, float a, int mode) {
        head(AstrattoNative.RCMD_QUADS, 16 + 144).buf.putInt(texture).putInt(1);
        float[][] c = {{x, y, u0, v0}, {x + w, y, u1, v0}, {x + w, y + h, u1, v1}, {x, y + h, u0, v1}};
        for (float[] v : c) buf.putFloat(v[0]).putFloat(v[1]).putFloat(v[2]).putFloat(v[3]).putFloat(r).putFloat(g).putFloat(b).putFloat(a).putFloat(mode);
    }

    /** Screen grading 0..1: death drains colour, hurt reddens the edges, infection greens them, a flash whitens, bile blurs. */
    public void grading(float death, float hurt, float infection, float flash, float bile) {
        head(AstrattoNative.RCMD_GRADING, 32).buf.putFloat(death).putFloat(hurt).putFloat(infection).putFloat(flash).putFloat(bile).putFloat(0);
    }

    /** An upright capsule that shadows local lights (a person in a car's headlights). */
    public void shadowBody(float x, float y, float z, float radius, float height) { head(AstrattoNative.RCMD_SHADOW_BODY, 32).buf.putFloat(x).putFloat(y).putFloat(z).putFloat(radius).putFloat(height).putFloat(0); }

    /**
     * 2.1: draw the FOLIAGE layers this frame: the wind (m/s), its clock and gusts, the distances over which plants sink
     * away, snow, and up to 8 pushers (x, z, radius, y each, in push[0 .. pushCount * 4)).
     */
    public void foliage(float windX, float windZ, float time, float gust, float fadeStart, float fadeEnd, float snow, float[] push, int pushCount) {
        head(AstrattoNative.RCMD_FOLIAGE, 168).buf.putFloat(windX).putFloat(windZ).putFloat(time).putFloat(gust).putFloat(fadeStart).putFloat(fadeEnd).putFloat(snow);
        int n = Math.max(0, Math.min(8, pushCount));
        buf.putInt(n);
        for (int i = 0; i < 32; i++) buf.putFloat(push != null && i < n * 4 && i < push.length ? push[i] : 0);
    }

    /** 2.1: draw the PROP layers this frame, out to these distances (and into the shadows out to the second). */
    public void props(float drawDistance, float shadowDistance) { head(AstrattoNative.RCMD_PROPS, 16).buf.putFloat(drawDistance).putFloat(shadowDistance); }

    /**
     * 2.1: draw the DECAL layers this frame, with the decal texture array (and the array linings come from, 0 = the
     * world's atlas), fading out between the two distances, under snow; plus this frame's marks: count vertices of 10
     * floats from verts (six a quad).
     */
    public void decals(int texture, int atlas, float fadeStart, float fadeEnd, float snow, float[] verts, int count) {
        head(AstrattoNative.RCMD_DECALS, 32 + count * 40).buf.putInt(texture).putInt(atlas).putFloat(fadeStart).putFloat(fadeEnd).putFloat(snow).putInt(count);
        for (int i = 0; i < count * 10; i++) buf.putFloat(verts[i]);
    }

    /** The engine world to draw (0: none this frame). */
    public void world(long world) { head(AstrattoNative.RCMD_WORLD, 16).buf.putLong(world); }

    /** No world: the window cleared to a colour (menus); quads are drawn over it. */
    public void clear(float r, float g, float b, float a) { head(AstrattoNative.RCMD_CLEAR, 24).buf.putFloat(r).putFloat(g).putFloat(b).putFloat(a); }

    public int bytes() { return buf.position(); }
    public int records() { return records; }
    long address() { return MemoryUtil.memAddress0(buf); }
    public void reset() { buf.clear(); records = 0; }
    public void free() { if (buf != null) { MemoryUtil.memFree(buf); buf = null; } }
}
