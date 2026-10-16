package astratto.bridge;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashSet;
import java.util.Set;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

/**
 * Astratto Engine: one native engine, held from Java. The whole conversation with it is here:
 *
 * <pre>
 *   {@link #blockTable}                    what the 256 block ids mean to it, once
 *   {@link #createWorld}, {@link #edit}    a world it owns, built and changed by batched edits; {@link #view} reads it
 *   {@link #reserve}, {@link #release}     handles for what later commands make (bodies, agents, voices, policies)
 *   {@link #frame}                         a batch of commands in, dt simulated, a batch of events out, once a tick
 *   {@link #stats}, {@link #bodies}, ...   read-only snapshots of its state
 *   {@link #createSound}, {@link #loadModule}   things made from data handed over at once
 * </pre>
 *
 * The engine owns its memory and the authoritative state of what it simulates. Events are read straight out of its
 * memory while {@link #frame} is still on the stack and handed to the {@link EventSink} as plain values and fresh
 * arrays; snapshots are copied out likewise. Nothing the engine owns outlives the call that showed it, except a
 * {@link WorldView}, which is valid until its world is released. The engine never calls into Java.
 *
 * One thread drives an engine: every method here is called on the thread that created it.
 */
public final class NativeEngine implements AutoCloseable {
    /** 1.2's name for the events a game received (routes and sight lines); every other event has a default too. */
    public interface Events extends EventSink { }

    /** How an engine is made. */
    public static final class Config {
        /** 0 = one per hardware thread, less one (the caller's thread works too). */
        public int workerThreads;
        /** Open the system's audio output (otherwise audio is mixed on request, {@link #renderAudio}). */
        public boolean audioDevice;
        /** One worker: results independent of thread timing (tests, replays). */
        public boolean deterministic;
        public int audioRate = 44100, audioBlock = 1024;
    }

    /** One kind of block as the engine needs it (see {@code astratto_block_def}). */
    public static final class BlockDef {
        public int flags, hideMask, material, shape, emission, surface, absorb;
        public BlockDef() { }
        public BlockDef(int flags) { this.flags = flags; }
    }

    private long handle;
    private final ByteBuffer io = MemoryUtil.memCalloc(AstrattoNative.SIZEOF_FRAME).order(ByteOrder.nativeOrder());
    private final ByteBuffer snap = MemoryUtil.memCalloc(AstrattoNative.SIZEOF_SNAPSHOT).order(ByteOrder.nativeOrder());
    private final Set<Long> worlds = new HashSet<>();
    private String lastError = "";
    private int lastStatus;
    private final Thread owner = Thread.currentThread();
    private boolean freed;
    /** With -Dastratto.checkThreads=true every call checks it is made on the thread that made the engine (the tests run so). */
    private static final boolean CHECK_THREADS = Boolean.getBoolean("astratto.checkThreads");

    private NativeEngine(long handle) { this.handle = handle; }

    /** A new engine with default settings and the given number of workers, or null ({@link AstrattoNative#reason} says why). */
    public static NativeEngine create(int workerThreads) {
        Config c = new Config();
        c.workerThreads = workerThreads;
        return create(c);
    }

    /** A new engine, or null when there is no native library, or it would not make one ({@link AstrattoNative#reason}, {@link #createFailure}). */
    public static NativeEngine create(Config config) {
        if (!AstrattoNative.load()) return astratto.Astratto.noNative("the engine", AstrattoNative.reason());
        try (MemoryStack stack = MemoryStack.stackPush()) {
            ByteBuffer cfg = stack.calloc(AstrattoNative.SIZEOF_CONFIG).order(ByteOrder.nativeOrder());
            int flags = (config.audioDevice ? AstrattoNative.CONFIG_AUDIO_DEVICE : 0) | (config.deterministic ? AstrattoNative.CONFIG_DETERMINISTIC : 0);
            cfg.putInt(0, AstrattoNative.SIZEOF_CONFIG).putInt(4, AstrattoNative.API_VERSION).putInt(8, Math.max(0, config.workerThreads))
                    .putInt(12, flags).putInt(16, Math.max(0, config.audioRate)).putInt(20, Math.max(0, config.audioBlock));
            long h = AstrattoNative.create(cfg);
            return h == 0 ? astratto.Astratto.noNative("the engine", "astratto_create: " + AstrattoNative.status(AstrattoNative.createStatus())) : new NativeEngine(h);
        }
    }

    /** Why the last {@link #create} returned null, as a status ({@code ERR_*}). */
    public static int createFailure() { return AstrattoNative.loaded() ? AstrattoNative.createStatus() : AstrattoNative.ERR_UNSUPPORTED; }

    public boolean alive() { return handle != 0; }
    /** The engine's address, for other parts of the bridge (the renderer) that take it. */
    long address() { live(); return handle; }
    public String lastError() { return lastError; }
    /** The engine's own text for its last failure, asked now (for calls that report it there, such as making a renderer). */
    public String lastErrorNow() { return handle == 0 ? "" : AstrattoNative.lastError(handle); }
    public int lastStatus() { return lastStatus; }

    private void live() {
        if (handle == 0) throw new IllegalStateException("the engine has been closed");
        if (CHECK_THREADS && Thread.currentThread() != owner) throw new IllegalStateException("an engine is driven from the thread that made it (" + owner.getName() + ")");
    }

    private boolean ok(int status) {
        lastStatus = status;
        if (status == AstrattoNative.OK) return true;
        lastError = AstrattoNative.status(status) + ": " + AstrattoNative.lastError(handle);
        return false;
    }

    // ------------------------------------------------------------------ handles
    /** count fresh handles of a kind ({@code KIND_*}) for things a later command will make; null when refused. */
    public long[] reserve(int kind, int count) {
        live();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            java.nio.LongBuffer out = stack.callocLong(Math.max(1, count));
            if (!ok(AstrattoNative.reserve(handle, kind, count, MemoryUtil.memAddress(out)))) return null;
            long[] r = new long[count];
            out.get(0, r);
            return r;
        }
    }
    public long reserve(int kind) { long[] r = reserve(kind, 1); return r == null ? 0 : r[0]; }

    /** Releases anything by its handle, now: a world, a body, a sound, a module... False when the handle was stale. */
    public boolean release(long h) {
        live();
        boolean done = ok(AstrattoNative.release(handle, h));
        if (done) worlds.remove(h);
        return done;
    }

    /** Whether this engine still holds a world by that handle (as far as this binding has seen). */
    boolean holds(long world) { return handle != 0 && worlds.contains(world); }

    // ------------------------------------------------------------------ worlds
    /** What each of up to 256 kinds of block means to the engine; ids not given are air. */
    public boolean blockTable(BlockDef[] defs) {
        live();
        int n = Math.min(256, defs.length);
        ByteBuffer d = MemoryUtil.memCalloc(Math.max(1, n) * AstrattoNative.SIZEOF_BLOCK_DEF).order(ByteOrder.nativeOrder());
        try (MemoryStack stack = MemoryStack.stackPush()) {
            for (int i = 0; i < n; i++) {
                BlockDef b = defs[i];
                if (b == null) continue;
                int o = i * AstrattoNative.SIZEOF_BLOCK_DEF;
                d.putInt(o, b.flags).putShort(o + 4, (short) b.hideMask).put(o + 6, (byte) b.material).put(o + 7, (byte) b.shape)
                        .put(o + 8, (byte) b.emission).put(o + 9, (byte) b.surface).put(o + 10, (byte) b.absorb);
            }
            ByteBuffer table = stack.calloc(AstrattoNative.SIZEOF_BLOCK_TABLE).order(ByteOrder.nativeOrder());
            table.putInt(0, AstrattoNative.SIZEOF_BLOCK_TABLE).putInt(4, n).putLong(8, MemoryUtil.memAddress(d));
            return ok(AstrattoNative.blockTable(handle, table));
        } finally { MemoryUtil.memFree(d); }
    }

    /** 1.2's form: flags, the heights at which a shaped block hides a line, material class and shape, per id. */
    public boolean blockTable(int[] flags, int[] hideMask, int[] material, int[] shape) {
        return blockTable(flags, hideMask, material, shape, null);
    }
    /** As above, with the light each block gives off (0..15). */
    public boolean blockTable(int[] flags, int[] hideMask, int[] material, int[] shape, int[] emission) {
        BlockDef[] d = new BlockDef[flags.length];
        for (int i = 0; i < d.length; i++) {
            d[i] = new BlockDef(flags[i]);
            d[i].hideMask = hideMask[i]; d[i].material = material[i]; d[i].shape = shape[i];
            if (emission != null) d[i].emission = emission[i];
        }
        return blockTable(d);
    }

    /**
     * A new world the engine owns. cells and light (either may be null: all air, no light) are copied before this returns;
     * one byte a cell, index = (x * sizeZ + z) * sizeY + y. flags: {@code WORLD_LIGHT} computes all light at once.
     * Returns the world's handle, or 0 ({@link #lastError}).
     */
    public long createWorld(int sizeX, int sizeY, int sizeZ, int belowFloorBlock, byte[] cells, byte[] light, int flags) {
        live();
        long cells64 = (long) sizeX * sizeY * sizeZ;
        if ((cells != null && cells.length != cells64) || (light != null && light.length != cells64)) { lastError = "cells or light not the world's size"; return 0; }
        ByteBuffer c = cells == null ? null : MemoryUtil.memAlloc(cells.length);
        ByteBuffer l = light == null ? null : MemoryUtil.memAlloc(light.length);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            if (c != null) c.put(0, cells);
            if (l != null) l.put(0, light);
            ByteBuffer desc = stack.calloc(AstrattoNative.SIZEOF_WORLD_DESC).order(ByteOrder.nativeOrder());
            desc.putInt(0, AstrattoNative.SIZEOF_WORLD_DESC).putInt(4, sizeX).putInt(8, sizeY).putInt(12, sizeZ).putInt(16, belowFloorBlock).putInt(20, flags)
                    .putLong(24, c == null ? 0 : MemoryUtil.memAddress(c)).putLong(32, c == null ? 0 : cells.length)
                    .putLong(40, l == null ? 0 : MemoryUtil.memAddress(l)).putLong(48, l == null ? 0 : light.length);
            java.nio.LongBuffer out = stack.callocLong(1);
            if (!ok(AstrattoNative.worldCreate(handle, desc, MemoryUtil.memAddress(out)))) return 0;
            long w = out.get(0);
            worlds.add(w);
            return w;
        } finally {
            if (c != null) MemoryUtil.memFree(c);
            if (l != null) MemoryUtil.memFree(l);
        }
    }

    /** The world's own memory, read-only, until it is released (null for a handle that is not a live world). */
    public WorldView view(long world) {
        live();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            ByteBuffer v = stack.calloc(AstrattoNative.SIZEOF_WORLD_VIEW).order(ByteOrder.nativeOrder());
            v.putInt(0, AstrattoNative.SIZEOF_WORLD_VIEW);
            if (!ok(AstrattoNative.worldView(handle, world, v))) return null;
            worlds.add(world);
            return new WorldView(this, world, v.getInt(4), v.getInt(8), v.getInt(12), v.getLong(16), v.getLong(24));
        }
    }

    /** The edit counters of a world: {edits, nav version, light version}, or null. */
    public long[] versions(long world) {
        live();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            ByteBuffer v = stack.calloc(AstrattoNative.SIZEOF_WORLD_VIEW).order(ByteOrder.nativeOrder());
            v.putInt(0, AstrattoNative.SIZEOF_WORLD_VIEW);
            if (!ok(AstrattoNative.worldView(handle, world, v))) return null;
            return new long[]{v.getLong(48), v.getInt(56) & 0xFFFFFFFFL, v.getInt(60) & 0xFFFFFFFFL};
        }
    }

    /** Applies a buffer of world edits ({@code EDIT_*} records, block sets) to a world now, in order; the buffer is cleared. */
    public boolean edit(long world, CommandBuffer edits) {
        live();
        if (edits.isEmpty()) return true;
        boolean good = ok(AstrattoNative.worldEdit(handle, world, edits.address(), edits.bytes()));
        edits.clear();
        return good;
    }

    // ------------------------------------------------------------------ the tick
    /**
     * One tick: the batch goes across, the engine runs dt seconds of simulation, and what it answers and what happened is
     * given to {@code out} before this returns. The buffer is cleared for the next tick. False when the engine refused
     * the batch ({@link #lastError}); events up to the refusal are still delivered.
     */
    public boolean frame(CommandBuffer commands, float dt, EventSink out) {
        live();
        io.putInt(0, AstrattoNative.SIZEOF_FRAME).putFloat(4, dt).putLong(8, commands == null || commands.isEmpty() ? 0 : commands.address())
                .putInt(16, commands == null ? 0 : commands.bytes()).putInt(20, 0).putLong(24, 0).putInt(32, 0).putInt(36, 0);
        boolean good = ok(AstrattoNative.frame(handle, io));
        if (commands != null) commands.clear();
        long events = io.getLong(24);
        int bytes = io.getInt(32);
        if (events != 0 && bytes > 0 && out != null) dispatch(MemoryUtil.memByteBuffer(events, bytes).order(ByteOrder.nativeOrder()), bytes, out);
        return good;
    }

    /** Hands every record of an event stream to a sink, by kind. The stream is read in place, by copying fields out. */
    public static void dispatch(ByteBuffer e, int bytes, EventSink out) {
        int at = 0;
        while (at + 8 <= bytes) {
            int type = e.getInt(at), size = e.getInt(at + 4);
            if (size < 8 || at + size > bytes) break;
            switch (type) {
                case AstrattoNative.EVT_ERROR -> out.error(e.getInt(at + 8), e.getInt(at + 12), e.getLong(at + 16));
                case AstrattoNative.EVT_PATH_RESULT -> {
                    int request = e.getInt(at + 8), status = e.getInt(at + 12), expansions = e.getInt(at + 16), n = e.getInt(at + 20);
                    int[] cells = null;
                    if (status == AstrattoNative.PATH_FOUND && n > 0) { cells = new int[n * 3]; for (int i = 0; i < cells.length; i++) cells[i] = e.getInt(at + 24 + i * 4); }
                    out.path(request, cells, expansions);
                }
                case AstrattoNative.EVT_SIGHT_RESULT -> out.sight(e.getInt(at + 8), e.getInt(at + 12) != 0);
                case AstrattoNative.EVT_WALK_RESULT -> out.walk(e.getInt(at + 8), e.getInt(at + 12));
                case AstrattoNative.EVT_RAY_RESULT -> out.ray(e.getInt(at + 8), e.getFloat(at + 12), e.getInt(at + 16), e.getInt(at + 20), e.getInt(at + 24),
                        e.getInt(at + 28), e.getInt(at + 32), e.getInt(at + 36), e.getInt(at + 40));
                case AstrattoNative.EVT_LANDED -> out.landed(e.getLong(at + 8), e.getFloat(at + 16));
                case AstrattoNative.EVT_SETTLED -> out.settled(e.getLong(at + 8));
                case AstrattoNative.EVT_GEAR -> out.gear(e.getLong(at + 8), Math.round(e.getFloat(at + 16)));
                case AstrattoNative.EVT_RAGDOLL_STATE -> out.ragdollState(e.getLong(at + 8), e.getInt(at + 16), e.getInt(at + 20), e.getInt(at + 24));
                case AstrattoNative.EVT_VEHICLE_IMPACT -> out.vehicleImpact(e.getLong(at + 8), e.getFloat(at + 16), e.getFloat(at + 20), e.getFloat(at + 24), e.getFloat(at + 28));
                case AstrattoNative.EVT_BLOCK_HIT -> out.blockHit(e.getLong(at + 8), e.getFloat(at + 16), e.getFloat(at + 20), e.getFloat(at + 24), e.getFloat(at + 28),
                        e.getInt(at + 32), e.getInt(at + 36), e.getInt(at + 40));
                case AstrattoNative.EVT_AGENT_ACTION -> out.agentAction(e.getLong(at + 8), e.getInt(at + 24), e.getInt(at + 28));
                case AstrattoNative.EVT_AGENT_SEES -> out.agentSees(e.getLong(at + 8), e.getLong(at + 16));
                case AstrattoNative.EVT_AGENT_LOST -> out.agentLost(e.getLong(at + 8), e.getLong(at + 16));
                case AstrattoNative.EVT_AGENT_ARRIVED -> out.agentArrived(e.getLong(at + 8), e.getLong(at + 16));
                case AstrattoNative.EVT_AGENT_STUCK -> out.agentStuck(e.getLong(at + 8));
                case AstrattoNative.EVT_AGENT_HEARD -> out.agentHeard(e.getLong(at + 8), e.getInt(at + 24));
                case AstrattoNative.EVT_VOICE_ENDED -> out.voiceEnded(e.getLong(at + 8));
                case AstrattoNative.EVT_MODULE_LOG -> {
                    int len = Math.max(0, Math.min(e.getInt(at + 16), size - 24));
                    byte[] text = new byte[len];
                    for (int i = 0; i < len; i++) text[i] = e.get(at + 24 + i);
                    out.moduleLog(e.getLong(at + 8), new String(text, java.nio.charset.StandardCharsets.UTF_8));
                }
                default -> out.other(type, e.slice(at, size).asReadOnlyBuffer().order(ByteOrder.nativeOrder()));
            }
            at += size;
        }
    }

    // ------------------------------------------------------------------ snapshots
    private ByteBuffer snapshot(int kind) {
        live();
        snap.putInt(0, AstrattoNative.SIZEOF_SNAPSHOT).putInt(4, kind).putLong(8, 0).putLong(16, 0);
        if (!ok(AstrattoNative.snapshot(handle, snap)) || snap.getLong(8) == 0) return null;
        return MemoryUtil.memByteBuffer(snap.getLong(8), (int) snap.getLong(16)).order(ByteOrder.nativeOrder());
    }

    public Snapshots.Stats stats() { ByteBuffer b = snapshot(AstrattoNative.SNAPSHOT_STATS); return b == null ? new Snapshots.Stats() : Snapshots.Stats.read(b); }
    public Snapshots.AudioStats audioStats() { ByteBuffer b = snapshot(AstrattoNative.SNAPSHOT_AUDIO); return b == null ? new Snapshots.AudioStats() : Snapshots.AudioStats.read(b); }
    public Snapshots.Bodies bodies() { ByteBuffer b = snapshot(AstrattoNative.SNAPSHOT_BODIES); return b == null ? new Snapshots.Bodies() : Snapshots.Bodies.read(b, b.capacity()); }
    public Snapshots.Agents agents() { ByteBuffer b = snapshot(AstrattoNative.SNAPSHOT_AGENTS); return b == null ? new Snapshots.Agents() : Snapshots.Agents.read(b, b.capacity()); }

    // ------------------------------------------------------------------ audio
    /** A decoded sound handed over once (copied): frames x channels interleaved samples in -1..1, at a rate. 0 when refused. */
    public long createSound(float[] samples, int channels, int rate) {
        live();
        int frames = samples.length / Math.max(1, channels);
        java.nio.FloatBuffer data = MemoryUtil.memAllocFloat(Math.max(1, samples.length));
        try (MemoryStack stack = MemoryStack.stackPush()) {
            data.put(0, samples);
            ByteBuffer d = stack.calloc(AstrattoNative.SIZEOF_SOUND_DESC).order(ByteOrder.nativeOrder());
            d.putInt(0, AstrattoNative.SIZEOF_SOUND_DESC).putInt(4, channels).putInt(8, rate).putInt(12, frames).putLong(16, MemoryUtil.memAddress(data));
            java.nio.LongBuffer out = stack.callocLong(1);
            return ok(AstrattoNative.soundCreate(handle, d, MemoryUtil.memAddress(out))) ? out.get(0) : 0;
        } finally { MemoryUtil.memFree(data); }
    }

    /** Mixes frames of stereo into out (interleaved, at least 2 * frames) when the engine has no audio device. */
    public boolean renderAudio(float[] out, int frames) {
        live();
        java.nio.FloatBuffer b = MemoryUtil.memAllocFloat(Math.max(2, frames * 2));
        try {
            if (!ok(AstrattoNative.audioRender(handle, MemoryUtil.memAddress(b), frames))) return false;
            b.get(0, out, 0, frames * 2);
            return true;
        } finally { MemoryUtil.memFree(b); }
    }

    // ------------------------------------------------------------------ gameplay modules
    /**
     * Loads a gameplay module written in C++ (a shared library exporting {@code astratto_module_entry}). The engine runs
     * it inside every frame from now on, on this thread. config is text the module understands. 0 when refused.
     */
    public long loadModule(String path, String config) {
        live();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            java.nio.LongBuffer out = stack.callocLong(1);
            ByteBuffer p = stack.UTF8(path);
            ByteBuffer c = config == null ? null : stack.UTF8(config);
            return ok(AstrattoNative.moduleLoad(handle, MemoryUtil.memAddress(p), c == null ? 0 : MemoryUtil.memAddress(c), MemoryUtil.memAddress(out))) ? out.get(0) : 0;
        }
    }

    @Override public void close() {
        if (handle != 0) { AstrattoNative.destroy(handle); handle = 0; worlds.clear(); }
        if (!freed) { freed = true; MemoryUtil.memFree(io); MemoryUtil.memFree(snap); }
    }
}
