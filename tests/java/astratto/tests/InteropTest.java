package astratto.tests;

import static org.junit.jupiter.api.Assertions.*;

import astratto.bridge.AstrattoNative;
import astratto.bridge.CommandBuffer;
import astratto.bridge.NativeEngine;
import astratto.bridge.NativeMesher;
import astratto.bridge.Snapshots;
import astratto.bridge.WorldView;
import astratto.game.Game;
import astratto.game.GameContext;
import astratto.game.GameModule;
import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Interoperability: the Java bindings and the native core agree - on the version, on the size of every structure, on
 * every constant - and the rules of the boundary hold when Java drives it: batches in, events out in order, handles
 * with lifetimes, read-only views, modules in Java and in C++ run in the stated order on the caller's thread.
 */
class InteropTest {
    @Test void theLibraryLoadsAndSpeaksThisInterface() {
        assertTrue(AstrattoNative.load(), AstrattoNative.reason());
        assertEquals(AstrattoNative.API_VERSION, AstrattoNative.apiVersion());
        assertTrue(AstrattoNative.hasRenderer(), "the native core has its renderer");
        // load() refuses a library any of whose structures differs in size from the bindings: here it loaded, so they all agree
        assertEquals(40, AstrattoNative.layout("astratto_frame_io"), "astratto_frame_io as the header lays it out on a 64-bit machine");
        assertEquals(0, AstrattoNative.layout("no_such_structure"));
    }

    /** Every constant of astratto.h that the Java bindings have, with the same value. */
    @Test void everyConstantMatchesTheHeader() throws Exception {
        String h = BoundaryTest.code(Files.readString(new File(Fixtures.engineDir(), "native/include/astratto/astratto.h").toPath(), StandardCharsets.UTF_8), false);
        Map<String, Long> c = new HashMap<>();
        Matcher m = Pattern.compile("\\b(ASTRATTO_[A-Z0-9_]+)\\s*=\\s*([^,}]+)").matcher(h);
        while (m.find()) {
            Long v = eval(m.group(2).trim(), c);
            if (v != null) c.put(m.group(1), v);
        }
        Matcher d = Pattern.compile("#define\\s+(ASTRATTO_[A-Z0-9_]+)\\s+(\\d+)\\s*$", Pattern.MULTILINE).matcher(h);
        while (d.find()) c.put(d.group(1), Long.parseLong(d.group(2)));
        int compared = 0;
        List<String> wrong = new ArrayList<>();
        for (Field f : AstrattoNative.class.getFields()) {
            if (!Modifier.isStatic(f.getModifiers()) || f.getType() != int.class) continue;
            Long want = c.get("ASTRATTO_" + f.getName());
            if (want == null) continue;
            compared++;
            if (f.getInt(null) != want.intValue()) wrong.add(f.getName() + " = " + f.getInt(null) + ", header " + want);
        }
        assertEquals(List.of(), wrong);
        assertTrue(compared > 200, "only " + compared + " constants compared");
    }

    private static Long eval(String e, Map<String, Long> known) {
        e = e.replace("(", "").replace(")", "").replace("uint32_t", "").replace("u", "").trim();
        if (e.contains("<<")) { String[] p = e.split("<<"); Long a = eval(p[0], known), b = eval(p[1], known); return a == null || b == null ? null : a << b; }
        if (e.matches("-?\\d+")) return Long.parseLong(e);
        if (e.matches("0x[0-9a-fA-F]+")) return Long.parseLong(e.substring(2), 16);
        return known.get(e);
    }

    @Test void answersComeBackInTheOrderTheQuestionsWereWritten() {
        try (NativeEngine e = Fixtures.engine()) {
            long w = Fixtures.yard(e);
            CommandBuffer c = new CommandBuffer();
            c.worldActivate(w);
            c.sightRequest(1, AstrattoNative.SIGHT_EYE, 5, 6, 30, 5, 6, 50);           // through the wall: blocked
            c.pathRequest(2, AstrattoNative.PATH_DOORS, 5.5f, 4, 30.5f, 5.5f, 4, 50.5f, 20000);
            c.sightRequest(3, AstrattoNative.SIGHT_EYE, 11, 5.5f, 30, 11, 5.5f, 50);   // through the window: clear
            c.rayQuery(4, 30, 20, 10, 20, 0, -1, 0);                                  // straight down onto the floor
            c.walkQuery(5, AstrattoNative.WALK_STAND, false, 0.3f, 20, 4, 20, 0, 0, 0);
            Fixtures.Recorder r = new Fixtures.Recorder();
            assertTrue(e.frame(c, 1 / 60f, r), e.lastError());
            assertEquals(List.of("sight:1:false", r.log.get(1), "sight:3:true", "ray:4:3:1", "walk:5:1"), r.log);
            assertTrue(r.log.get(1).startsWith("path:2:") && !r.log.get(1).endsWith("none"), "a route round by the gap: " + r.log.get(1));
            c.free();
        }
    }

    @Test void handlesHaveKindsAndLifetimes() {
        try (NativeEngine e = Fixtures.engine()) {
            long w = Fixtures.yard(e);
            long m = e.reserve(AstrattoNative.KIND_MOVER);
            assertEquals(AstrattoNative.KIND_MOVER, AstrattoNative.kindOf(m));
            assertEquals(AstrattoNative.KIND_WORLD, AstrattoNative.kindOf(w));
            CommandBuffer c = new CommandBuffer();
            c.worldActivate(w);
            c.moverCreate(m, 20, 6, 20, 0.3f, 1.8f, 0.6f, 0, 1.2f);
            Fixtures.Recorder r = new Fixtures.Recorder();
            e.frame(c, 1 / 60f, r);
            assertEquals(0, r.errors.size());
            assertTrue(e.release(m));
            assertFalse(e.release(m), "a handle is released once");
            c.moverInput(m, 1, 0, 0, 0, 0, 0);
            e.frame(c, 1 / 60f, r);
            assertEquals(1, r.errors.size(), "a released handle used in a command is an error event, not a crash");
            assertEquals(AstrattoNative.ERR_HANDLE, r.errors.get(0)[1]);
            c.free();
        }
    }

    @Test void theWorldIsTheEnginesAndJavaSeesItReadOnly() {
        try (NativeEngine e = Fixtures.engine()) {
            long w = Fixtures.yard(e);
            WorldView v = e.view(w);
            assertEquals(Fixtures.STONE, v.block(5, 4, 40));
            assertTrue(v.blocks().isReadOnly(), "the view cannot be written");
            assertThrows(java.nio.ReadOnlyBufferException.class, () -> v.blocks().put(0, (byte) 1));
            CommandBuffer edit = new CommandBuffer();
            edit.blockSet(5, 4, 40, Fixtures.AIR);
            assertTrue(e.edit(w, edit));
            assertEquals(Fixtures.AIR, v.block(5, 4, 40), "an edit is seen at once through the same view: there is no copy");
            assertEquals(15, v.sky(20, 4, 20), "the engine lit it");
            assertTrue(e.release(w));
            assertThrows(IllegalStateException.class, () -> v.block(5, 4, 40), "a view of a released world is dead, not dangling");
            edit.free();
        }
    }

    @Test void javaFallbacksOffMeansFactoriesThrowInsteadOfReturningNull() {
        NativeMesher.BlockDef[] blocks = new NativeMesher.BlockDef[300];   // more than 256 kinds: malformed
        assertThrows(IllegalStateException.class, () -> NativeMesher.create(blocks, new NativeMesher.ShapeDef[]{new NativeMesher.ShapeDef()}, 0));
    }

    /** Two Java modules and a C++ one: their order, the events they see, the thread rule, shutdown. */
    @Test void gameplayModulesInJavaAndCppRunInTheStatedOrder() throws Exception {
        List<String> order = new ArrayList<>();
        class Probe implements GameModule {
            final String id;
            long mover;
            int heard, lines;
            Probe(String id) { this.id = id; }
            @Override public void init(GameContext ctx) { order.add(id + ".init"); }
            @Override public void update(GameContext ctx, float dt) { order.add(id + ".update"); }
            @Override public void afterFrame(GameContext ctx) { order.add(id + ".after"); }
            @Override public void shutdown(GameContext ctx) { order.add(id + ".shutdown"); }
            @Override public void moduleLog(long module, String text) { lines++; }
            @Override public void agentHeard(long agent, int loudness) { heard++; }
        }
        NativeEngine.Config cfg = new NativeEngine.Config();
        cfg.deterministic = true;
        Game g = Game.create(cfg).logger(null);
        long w = Fixtures.yard(g.engine());
        g.activate(w);
        Probe a = g.add(new Probe("a")), b = g.add(new Probe("b"));
        // an agent that hears the C++ module's radio
        long policy = g.reserve(AstrattoNative.KIND_POLICY), agent = g.reserve(AstrattoNative.KIND_AGENT), body = g.reserve(AstrattoNative.KIND_MOVER);
        g.commands().policyCreate(policy, List.of(new CommandBuffer.Action(1, AstrattoNative.ACT_IDLE).base(0.5f)), 0.1f, 0.2f);
        g.commands().moverCreate(body, 20.5f, 4, 20.5f, 0.3f, 1.8f, 0.6f, AstrattoNative.MOVER_AGENT, 1.2f);
        g.commands().agentCreate(agent, policy, body, 0, 0, 0, 1.6f, 30, 0f, 40, 1, 0, 1);
        g.loadNative(Fixtures.exampleModule(), "radio 22 5 22 0.5 1");
        order.clear();
        assertTrue(g.tick(1 / 30f));
        assertEquals(List.of("a.update", "b.update", "a.after", "b.after"), order, "updates in the order added, then the frame, then after-frame");
        for (int i = 0; i < 60; i++) g.tick(1 / 30f);
        assertTrue(a.heard > 0 && a.heard == b.heard, "the C++ module's commands ran inside the frames, and both Java modules saw the same events");
        assertTrue(a.lines > 0, "the C++ module's log lines came back as events");
        AtomicReference<Throwable> other = new AtomicReference<>();
        Thread t = new Thread(() -> { try { g.tick(1 / 30f); } catch (Throwable x) { other.set(x); } });
        t.start(); t.join();
        assertInstanceOf(IllegalStateException.class, other.get(), "a game is driven only from the thread that made it");
        order.clear();
        g.close();
        assertEquals(List.of("b.shutdown", "a.shutdown"), order, "shut down last added first");
    }

    @Test void snapshotsAreCopiesTheCallerOwns() {
        try (NativeEngine e = Fixtures.engine()) {
            long w = Fixtures.yard(e);
            long m = e.reserve(AstrattoNative.KIND_MOVER);
            CommandBuffer c = new CommandBuffer();
            c.worldActivate(w);
            c.moverCreate(m, 20, 8, 20, 0.3f, 1.8f, 0.6f, 0, 1.2f);
            e.frame(c, 0, null);
            Snapshots.Bodies before = e.bodies();
            for (int i = 0; i < 60; i++) e.frame(c, 1 / 60f, null);
            Snapshots.Bodies after = e.bodies();
            assertEquals(8f, before.mover(m).y, 1e-3f, "a snapshot taken earlier is unchanged by later frames");
            assertEquals(4f, after.mover(m).y, 0.05f);
            Snapshots.Stats s = e.stats();
            assertTrue(s.simSteps > 0 && s.simTime > 0.9, "the frame's dt ran the simulation: " + s);
            c.free();
        }
    }
}
