package astratto.tests;

import astratto.bridge.AstrattoNative;
import astratto.bridge.CommandBuffer;
import astratto.bridge.EventSink;
import astratto.bridge.NativeEngine;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

/** What the engine's tests share: the engine directory, a small walled yard of their own, the example C++ module. */
final class Fixtures {
    private Fixtures() {}

    static final int SX = 64, SY = 24, SZ = 64, FLOOR = 4;
    static final int AIR = 0, STONE = 1, GLASS = 2, LAMP = 3;

    /** The engine's own directory (the one holding native/, java/, shaders/...), found from the working directory. */
    static File engineDir() {
        for (String base : new String[]{".", "..", "../.."}) {
            File d = new File(base).getAbsoluteFile();
            if (new File(d, "native/include/astratto/astratto.h").isFile() && new File(d, "java/src/main/java/astratto").isDirectory()) return d;
        }
        throw new IllegalStateException("the tests run from the engine directory");
    }

    /** An engine for a test: deterministic, no audio device, and - Java fallbacks being off - never null. */
    static NativeEngine engine() {
        NativeEngine.Config c = new NativeEngine.Config();
        c.workerThreads = 2;
        c.deterministic = true;
        NativeEngine e = NativeEngine.create(c);
        if (e == null) throw new AssertionError("no engine: " + AstrattoNative.reason());
        return e;
    }

    static NativeEngine.BlockDef[] blocks() {
        NativeEngine.BlockDef[] d = new NativeEngine.BlockDef[4];
        d[AIR] = new NativeEngine.BlockDef(0);
        d[STONE] = new NativeEngine.BlockDef(AstrattoNative.BLOCK_SOLID | AstrattoNative.BLOCK_OPAQUE);
        d[GLASS] = new NativeEngine.BlockDef(AstrattoNative.BLOCK_SOLID); d[GLASS].material = AstrattoNative.MATERIAL_GLASS;
        d[LAMP] = new NativeEngine.BlockDef(AstrattoNative.BLOCK_SOLID | AstrattoNative.BLOCK_OPAQUE); d[LAMP].emission = 15;
        return d;
    }

    /** A floor, and a wall across the yard at z = 40 with a gap at x 30..33 and a window at x 10..12. Returns the world. */
    static long yard(NativeEngine e) {
        if (!e.blockTable(blocks())) throw new AssertionError(e.lastError());
        long w = e.createWorld(SX, SY, SZ, STONE, null, null, 0);
        if (w == 0) throw new AssertionError(e.lastError());
        CommandBuffer b = new CommandBuffer();
        int raw = AstrattoNative.SET_RAW;
        b.fill(0, 0, 0, SX - 1, FLOOR - 1, SZ - 1, STONE | raw);
        b.fill(0, FLOOR, 40, 29, FLOOR + 5, 40, STONE | raw);
        b.fill(34, FLOOR, 40, SX - 1, FLOOR + 5, 40, STONE | raw);
        b.fill(10, FLOOR + 1, 40, 12, FLOOR + 2, 40, GLASS | raw);
        b.blockSetRaw(50, FLOOR + 5, 50, LAMP);
        b.lightAll();
        if (!e.edit(w, b)) throw new AssertionError(e.lastError());
        b.free();
        return w;
    }

    /** The example gameplay module's library (built beside the native core, or bundled in the jar). */
    static String exampleModule() {
        String name = AstrattoNative.moduleName("example");
        File beside = new File(new File(AstrattoNative.path()).getParentFile(), name);
        if (beside.isFile()) return beside.getAbsolutePath();
        File b = AstrattoNative.extractBundled(name);
        if (b != null) return b.getAbsolutePath();
        File dir = AstrattoNative.engineBuildDir();
        if (dir != null && new File(dir, name).isFile()) return new File(dir, name).getAbsolutePath();
        throw new AssertionError("the example module " + name + " was not built");
    }

    /** Every event of a frame, in order, as "kind:detail" strings (for tests that look at order). */
    static final class Recorder implements EventSink {
        final List<String> log = new ArrayList<>();
        final List<int[]> errors = new ArrayList<>();
        @Override public void path(int request, int[] cells, int expansions) { log.add("path:" + request + ":" + (cells == null ? "none" : cells.length / 3)); }
        @Override public void sight(int request, boolean clear) { log.add("sight:" + request + ":" + clear); }
        @Override public void walk(int request, int value) { log.add("walk:" + request + ":" + value); }
        @Override public void ray(int request, float distance, int x, int y, int z, int nx, int ny, int nz, int block) { log.add("ray:" + request + ":" + y + ":" + block); }
        @Override public void error(int command, int status, long handle) { errors.add(new int[]{command, status}); log.add("error:" + command + ":" + status); }
        @Override public void moduleLog(long module, String text) { log.add("module:" + text); }
        @Override public void agentAction(long agent, int action, int previous) { log.add("action:" + action); }
        @Override public void agentHeard(long agent, int loudness) { log.add("heard"); }
        @Override public void ragdollState(long r, int state, int previous, int outcome) { log.add("ragdoll:" + state); }
        @Override public void voiceEnded(long voice) { log.add("voiceEnded"); }
        long count(String prefix) { return log.stream().filter(s -> s.startsWith(prefix)).count(); }
    }
}
