package astratto.bridge;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.lwjgl.system.MemoryUtil;

/**
 * Astratto Engine (rendering): the native chunk mesher, held from Java. One per meshing thread: it is made
 * from a render table (what each kind of block looks like to the mesher, the shapes and their boxes) and then turns
 * columns of cells into vertex streams, with no engine and no world of its own. See {@code astratto.h}, "rendering".
 *
 * A column is handed over as two arrays of 18 x height x 18 bytes (the column and a border of one cell): the block ids
 * and the light. The vertex streams come back copied into the arrays given (they are the mesher's memory only until
 * its next column).
 */
public final class NativeMesher implements AutoCloseable {
    /** One kind of block as the mesher needs it. */
    public static final class BlockDef {
        public int layerTop, layerSide, layerBottom, flags, varTop = 1, varSide = 1, varBottom = 1, shape;
    }
    /** One shape: how it reads its facing (ORIENT_*), whether a fence joins onto it, whether it is drawn, and its boxes ({x0, y0, z0, x1, y1, z1, tex} each, or null). */
    public static final class ShapeDef {
        public int orient;
        public boolean joins, invisible;
        public int[][] boxes;
    }
    /** What a column came out as. The arrays are reused from call to call; only the first {@code *Bytes} of each are the column's. */
    public static final class Out {
        public byte[] opaque = new byte[1 << 16], translucent = new byte[1 << 14];
        public int opaqueBytes, translucentBytes;
    }

    private long handle;
    private final ByteBuffer io = MemoryUtil.memCalloc(AstrattoNative.SIZEOF_MESH_IO).order(ByteOrder.nativeOrder());
    private ByteBuffer cells, light;
    private int height;

    private NativeMesher(long handle) { this.handle = handle; }

    /** A mesher, or null when there is no native core (or it would not take the table). */
    public static NativeMesher create(BlockDef[] blocks, ShapeDef[] shapes, int belowFloorBlock) {
        if (!AstrattoNative.load()) return astratto.Astratto.noNative("the chunk mesher", AstrattoNative.reason());
        long h = withTable(blocks, shapes, belowFloorBlock, AstrattoNative::mesherCreate);
        return h == 0 ? astratto.Astratto.noNative("the chunk mesher", "a malformed render table") : new NativeMesher(h);
    }

    /**
     * Lays a render table out in native memory ({@code astratto_render_table} and the arrays it points at), hands it to
     * {@code use}, and frees it again: whatever takes a table (a mesher, the renderer's world look) copies it.
     */
    static long withTable(BlockDef[] blocks, ShapeDef[] shapes, int belowFloorBlock, java.util.function.ToLongFunction<ByteBuffer> use) {
        int boxCount = 0;
        for (ShapeDef s : shapes) if (s != null && s.boxes != null) boxCount += s.boxes.length;
        ByteBuffer rb = MemoryUtil.memCalloc(Math.max(1, blocks.length) * AstrattoNative.SIZEOF_RENDER_BLOCK).order(ByteOrder.nativeOrder());
        ByteBuffer sd = MemoryUtil.memCalloc(Math.max(1, shapes.length) * AstrattoNative.SIZEOF_SHAPE_DEF).order(ByteOrder.nativeOrder());
        ByteBuffer bx = MemoryUtil.memCalloc(Math.max(1, boxCount) * 8).order(ByteOrder.nativeOrder());
        ByteBuffer table = MemoryUtil.memCalloc(AstrattoNative.SIZEOF_RENDER_TABLE).order(ByteOrder.nativeOrder());
        try {
            for (int i = 0; i < blocks.length; i++) {
                BlockDef b = blocks[i];
                if (b == null) continue;
                int o = i * AstrattoNative.SIZEOF_RENDER_BLOCK;
                rb.putShort(o, (short) b.layerTop).putShort(o + 2, (short) b.layerSide).putShort(o + 4, (short) b.layerBottom).putShort(o + 6, (short) b.flags);
                rb.put(o + 8, (byte) b.varTop).put(o + 9, (byte) b.varSide).put(o + 10, (byte) b.varBottom).put(o + 11, (byte) b.shape);
            }
            int box = 0;
            for (int i = 0; i < shapes.length; i++) {
                ShapeDef s = shapes[i];
                if (s == null) continue;
                int o = i * AstrattoNative.SIZEOF_SHAPE_DEF;
                sd.put(o, (byte) s.orient).put(o + 1, (byte) (s.joins ? 1 : 0)).put(o + 2, (byte) (s.invisible ? 1 : 0)).put(o + 3, (byte) (s.boxes != null ? 1 : 0));
                sd.putInt(o + 4, box).putInt(o + 8, s.boxes == null ? 0 : s.boxes.length);
                if (s.boxes != null) for (int[] b : s.boxes) { for (int k = 0; k < 7; k++) bx.put(box * 8 + k, (byte) b[k]); box++; }
            }
            table.putInt(0, AstrattoNative.SIZEOF_RENDER_TABLE).putInt(4, blocks.length).putLong(8, MemoryUtil.memAddress(rb));
            table.putInt(16, shapes.length).putInt(20, boxCount).putLong(24, MemoryUtil.memAddress(sd)).putLong(32, MemoryUtil.memAddress(bx));
            table.putInt(40, belowFloorBlock);
            return use.applyAsLong(table);
        } finally {
            MemoryUtil.memFree(rb); MemoryUtil.memFree(sd); MemoryUtil.memFree(bx); MemoryUtil.memFree(table);
        }
    }

    /** The column's cells, to be filled before {@link #mesh}: 18 * height * 18 bytes, index ((x + 1) * 18 + (z + 1)) * height + y. */
    public ByteBuffer cells(int height) { ensure(height); return cells; }
    /** The column's light, the same layout. */
    public ByteBuffer light(int height) { ensure(height); return light; }

    private void ensure(int h) {
        if (cells != null && height == h) return;
        if (cells != null) { MemoryUtil.memFree(cells); MemoryUtil.memFree(light); }
        height = h;
        cells = MemoryUtil.memCalloc(18 * 18 * h);
        light = MemoryUtil.memCalloc(18 * 18 * h);
    }

    /** Meshes the column whose cells and light were filled in; the streams are copied into out. False on a refusal. */
    public boolean mesh(int originX, int originZ, int worldX, int worldZ, int flags, Out out) {
        if (handle == 0 || cells == null) return false;
        io.clear();
        io.putInt(0, AstrattoNative.SIZEOF_MESH_IO).putInt(4, flags).putInt(8, height).putInt(12, 0);
        io.putInt(16, originX).putInt(20, originZ).putInt(24, worldX).putInt(28, worldZ);
        io.putLong(32, MemoryUtil.memAddress(cells)).putLong(40, MemoryUtil.memAddress(light));
        if (AstrattoNative.meshColumn(handle, io) != AstrattoNative.OK) return false;
        long op = io.getLong(48), tp = io.getLong(56);
        int ob = io.getInt(64), tb = io.getInt(68);
        if (out.opaque.length < ob) out.opaque = new byte[Math.max(ob, out.opaque.length * 2)];
        if (out.translucent.length < tb) out.translucent = new byte[Math.max(tb, out.translucent.length * 2)];
        if (ob > 0) MemoryUtil.memByteBuffer(op, ob).get(out.opaque, 0, ob);
        if (tb > 0) MemoryUtil.memByteBuffer(tp, tb).get(out.translucent, 0, tb);
        out.opaqueBytes = ob; out.translucentBytes = tb;
        return true;
    }

    @Override public void close() {
        if (handle != 0) { AstrattoNative.mesherDestroy(handle); handle = 0; }
        if (cells != null) { MemoryUtil.memFree(cells); MemoryUtil.memFree(light); cells = light = null; }
        MemoryUtil.memFree(io);
    }
}
