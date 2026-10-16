package astratto.bridge;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Astratto Engine: one of the engine's worlds, seen from Java - its own memory, read-only. There is no Java copy of the
 * world: the buffers here ARE the engine's cells and light, and they change as the world is edited (by edits and
 * frames, between the caller's reads - never during one). A view is valid until the world is released or the engine
 * destroyed; {@link NativeEngine#release} of the world marks it dead, and reads after that throw instead of touching
 * freed memory.
 *
 * Layout: column by column, index = (x * sizeZ + z) * sizeY + y. Light: sky light in the high four bits, block light in
 * the low four.
 */
public final class WorldView {
    public final long handle;
    public final int sizeX, sizeY, sizeZ;
    private final ByteBuffer blocks, light;
    private final NativeEngine engine;
    private final long blocksAddress, lightAddress;

    WorldView(NativeEngine engine, long handle, int sx, int sy, int sz, long blocksAddress, long lightAddress) {
        this.engine = engine;
        this.handle = handle;
        this.sizeX = sx; this.sizeY = sy; this.sizeZ = sz;
        this.blocksAddress = blocksAddress;
        this.lightAddress = lightAddress;
        long cells = (long) sx * sy * sz;
        if (cells > Integer.MAX_VALUE) throw new IllegalStateException("a world of more than 2^31 cells cannot be viewed from Java");
        this.blocks = org.lwjgl.system.MemoryUtil.memByteBuffer(blocksAddress, (int) cells).asReadOnlyBuffer().order(ByteOrder.nativeOrder());
        this.light = lightAddress == 0 ? null : org.lwjgl.system.MemoryUtil.memByteBuffer(lightAddress, (int) cells).asReadOnlyBuffer().order(ByteOrder.nativeOrder());
    }

    private void live() { if (!engine.holds(handle)) throw new IllegalStateException("the world this view was of has been released"); }

    public static int index(int x, int y, int z, int sizeY, int sizeZ) { return (x * sizeZ + z) * sizeY + y; }
    public boolean inBounds(int x, int y, int z) { return x >= 0 && y >= 0 && z >= 0 && x < sizeX && y < sizeY && z < sizeZ; }

    /** The block id of a cell (0 outside the world). */
    public int block(int x, int y, int z) {
        live();
        return inBounds(x, y, z) ? blocks.get((x * sizeZ + z) * sizeY + y) & 0xFF : 0;
    }
    /** The light byte of a cell (sky light << 4 | block light). */
    public int light(int x, int y, int z) {
        live();
        return light != null && inBounds(x, y, z) ? light.get((x * sizeZ + z) * sizeY + y) & 0xFF : 0;
    }
    public int sky(int x, int y, int z) { return light(x, y, z) >> 4; }
    public int blockLight(int x, int y, int z) { return light(x, y, z) & 15; }

    /** The cells themselves, read-only (no copy; check the world is still held before reading through it later). */
    public ByteBuffer blocks() { live(); return blocks.duplicate().order(ByteOrder.nativeOrder()); }
    public ByteBuffer light() { live(); return light == null ? null : light.duplicate().order(ByteOrder.nativeOrder()); }
    /** The address of the cells, for passing to native code (not to be written through). */
    public long blocksAddress() { live(); return blocksAddress; }
    public long lightAddress() { live(); return lightAddress; }
}
