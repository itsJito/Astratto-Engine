package astratto.bridge;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.lwjgl.system.MemoryUtil;

/**
 * 1.2, Astratto Engine: one native engine, held from Java. The whole conversation with it is here:
 *
 *   {@link #blockTable} and {@link #loadWorld}   what the world is made of, and the world, once
 *   {@link #frame}                               a batch of commands in, a batch of events out, once a tick
 *   {@link #stats}                               a read-only snapshot of its counters
 *
 * The engine owns its memory. Events are read straight out of it while {@link #frame} is still on the stack and are
 * handed to the {@link Events} given - plain values and a fresh array, nothing that outlives the call.
 */
public final class NativeEngine implements AutoCloseable {
    /** What comes back from a frame. Called on the thread that called {@link #frame}, in the order the requests were written. */
    public interface Events {
        /** A route: cells {x, y, z, ...} from the first step, or null when there is none. */
        void path(int request, int[] cells, int expansions);
        void sight(int request, boolean clear);
    }

    /** The counters snapshot. */
    public static final class Stats {
        public int workers, navVersion, sealedDoors, worldX, worldY, worldZ;
        public long frames, commands, blockEdits, pathRequests, pathExpansions, pathsFound, sightRequests;
        public float lastFrameMs, worstFrameMs;
        @Override public String toString() {
            return String.format("%d frames, %d commands, %d block edits, %d routes (%d found, %d cells expanded), %d sight lines, nav v%d, %d sealed doors, last frame %.3f ms (worst %.3f) on %d workers",
                    frames, commands, blockEdits, pathRequests, pathsFound, pathExpansions, sightRequests, navVersion, sealedDoors, lastFrameMs, worstFrameMs, workers);
        }
    }

    private long handle;
    private final ByteBuffer io = MemoryUtil.memCalloc(AstrattoNative.SIZEOF_FRAME).order(ByteOrder.nativeOrder());
    private final ByteBuffer snap = MemoryUtil.memCalloc(AstrattoNative.SIZEOF_SNAPSHOT).order(ByteOrder.nativeOrder());
    private String lastError = "";

    private NativeEngine(long handle) { this.handle = handle; }

    /** A new engine, or null when there is no native library, or it would not make one ({@link AstrattoNative#reason} says which). */
    public static NativeEngine create(int workerThreads) {
        if (!AstrattoNative.load()) return null;
        ByteBuffer cfg = MemoryUtil.memCalloc(AstrattoNative.SIZEOF_CONFIG).order(ByteOrder.nativeOrder());
        try {
            cfg.putInt(0, AstrattoNative.SIZEOF_CONFIG).putInt(4, AstrattoNative.API_VERSION).putInt(8, Math.max(0, workerThreads));
            long h = AstrattoNative.create(cfg);
            return h == 0 ? null : new NativeEngine(h);
        } finally { MemoryUtil.memFree(cfg); }
    }

    public boolean alive() { return handle != 0; }
    public String lastError() { return lastError; }

    private boolean ok(int status) {
        if (status == AstrattoNative.OK) return true;
        lastError = AstrattoNative.status(status) + ": " + AstrattoNative.lastError(handle);
        return false;
    }

    /**
     * What each of up to 256 kinds of block means to the engine: flags ({@code AstrattoNative.BLOCK_*}), the heights at
     * which a shaped block hides a line (sixteen bits, one a sixteenth of its height), its material class and shape.
     */
    public boolean blockTable(int[] flags, int[] hideMask, int[] material, int[] shape) {
        int n = flags.length;
        ByteBuffer defs = MemoryUtil.memCalloc(n * AstrattoNative.SIZEOF_BLOCK_DEF).order(ByteOrder.nativeOrder());
        ByteBuffer table = MemoryUtil.memCalloc(AstrattoNative.SIZEOF_BLOCK_TABLE).order(ByteOrder.nativeOrder());
        try {
            for (int i = 0; i < n; i++) defs.putInt(i * 8, flags[i]).putShort(i * 8 + 4, (short) hideMask[i]).put(i * 8 + 6, (byte) material[i]).put(i * 8 + 7, (byte) shape[i]);
            table.putInt(0, AstrattoNative.SIZEOF_BLOCK_TABLE).putInt(4, n).putLong(8, MemoryUtil.memAddress(defs));
            return ok(AstrattoNative.blockTable(handle, table));
        } finally { MemoryUtil.memFree(defs); MemoryUtil.memFree(table); }
    }

    /** A whole world, copied across once: one byte a cell, index = (x * sizeZ + z) * sizeY + y. */
    public boolean loadWorld(int sizeX, int sizeY, int sizeZ, int belowFloorBlock, byte[] cells) {
        ByteBuffer copy = MemoryUtil.memAlloc(cells.length);
        ByteBuffer desc = MemoryUtil.memCalloc(AstrattoNative.SIZEOF_WORLD_DESC).order(ByteOrder.nativeOrder());
        try {
            copy.put(0, cells);
            desc.putInt(0, AstrattoNative.SIZEOF_WORLD_DESC).putInt(4, sizeX).putInt(8, sizeY).putInt(12, sizeZ).putInt(16, belowFloorBlock)
                    .putLong(24, MemoryUtil.memAddress(copy)).putLong(32, cells.length);
            return ok(AstrattoNative.worldLoad(handle, desc));
        } finally { MemoryUtil.memFree(copy); MemoryUtil.memFree(desc); }
    }

    /**
     * One tick: the batch goes across, the engine runs, and what it answers is given to {@code out} before this
     * returns. The buffer is cleared for the next tick. False when the engine refused the batch ({@link #lastError}).
     */
    public boolean frame(CommandBuffer commands, float dt, Events out) {
        io.putInt(0, AstrattoNative.SIZEOF_FRAME).putFloat(4, dt).putLong(8, commands.isEmpty() ? 0 : commands.address()).putInt(16, commands.bytes()).putLong(24, 0).putInt(32, 0).putInt(36, 0);
        boolean good = ok(AstrattoNative.frame(handle, io));
        commands.clear();
        long events = io.getLong(24);
        int bytes = io.getInt(32);
        if (events != 0 && bytes > 0 && out != null) {
            ByteBuffer e = MemoryUtil.memByteBuffer(events, bytes).order(ByteOrder.nativeOrder());
            int at = 0;
            while (at + 8 <= bytes) {
                int type = e.getInt(at), size = e.getInt(at + 4);
                if (size < 8 || at + size > bytes) break;
                switch (type) {
                    case AstrattoNative.EVT_PATH_RESULT -> {
                        int request = e.getInt(at + 8), status = e.getInt(at + 12), expansions = e.getInt(at + 16), n = e.getInt(at + 20);
                        int[] cells = null;
                        if (status == AstrattoNative.PATH_FOUND && n > 0) { cells = new int[n * 3]; e.asIntBuffer().get((at + 24) / 4, cells); }
                        out.path(request, cells, expansions);
                    }
                    case AstrattoNative.EVT_SIGHT_RESULT -> out.sight(e.getInt(at + 8), e.getInt(at + 12) != 0);
                    default -> { }      // an event from a newer engine: stepped over by its own size
                }
                at += size;
            }
        }
        return good;
    }

    public Stats stats() {
        snap.putInt(0, AstrattoNative.SIZEOF_SNAPSHOT).putInt(4, AstrattoNative.SNAPSHOT_STATS).putLong(8, 0).putLong(16, 0);
        Stats s = new Stats();
        if (!ok(AstrattoNative.snapshot(handle, snap)) || snap.getLong(8) == 0) return s;
        ByteBuffer b = MemoryUtil.memByteBuffer(snap.getLong(8), (int) snap.getLong(16)).order(ByteOrder.nativeOrder());
        s.workers = b.getInt(4); s.frames = b.getLong(8); s.commands = b.getLong(16); s.blockEdits = b.getLong(24); s.pathRequests = b.getLong(32);
        s.pathExpansions = b.getLong(40); s.pathsFound = b.getLong(48); s.sightRequests = b.getLong(56); s.navVersion = b.getInt(64); s.sealedDoors = b.getInt(68);
        s.lastFrameMs = b.getFloat(72); s.worstFrameMs = b.getFloat(76); s.worldX = b.getInt(80); s.worldY = b.getInt(84); s.worldZ = b.getInt(88);
        return s;
    }

    @Override public void close() {
        if (handle != 0) { AstrattoNative.destroy(handle); handle = 0; }
        MemoryUtil.memFree(io); MemoryUtil.memFree(snap);
    }
}
