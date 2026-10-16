package astratto.game;

import astratto.bridge.AstrattoNative;
import astratto.bridge.CommandBuffer;
import astratto.bridge.EventSink;
import astratto.bridge.NativeEngine;
import astratto.bridge.Snapshots;
import astratto.bridge.WorldView;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Astratto Engine: runs a game's modules round the engine's tick. A game adds its Java modules ({@link #add}) and
 * loads its C++ ones ({@link #loadNative}); then each {@link #tick}:
 * <ol>
 *   <li>every Java module's {@link GameModule#update}, in the order added, writing into one command batch;</li>
 *   <li>the engine's frame: those commands, then every C++ module's update (inside the engine, on this thread), then
 *       the simulation - physics, AI, audio - and the answers to the questions asked;</li>
 *   <li>the frame's events, to every Java module in the order added (and, inside the engine, to every C++ module);</li>
 *   <li>every Java module's {@link GameModule#afterFrame}.</li>
 * </ol>
 * All of it on the thread that made the game: a tick from any other thread is refused, and the engine never calls Java
 * (its worker threads never leave native code; events are handed over only when the frame has returned).
 */
public final class Game implements GameContext, AutoCloseable {
    private final NativeEngine engine;
    private final boolean ownsEngine;
    private final CommandBuffer commands = new CommandBuffer(1 << 16);
    private final List<GameModule> modules = new ArrayList<>();
    private final List<Long> nativeModules = new ArrayList<>();
    private final Thread owner = Thread.currentThread();
    private long world;
    private WorldView view;
    private double time;
    private long ticks;
    private boolean closed;
    private Consumer<String> logger = s -> System.out.println("[game] " + s);

    /** The events of a frame, to every module in turn. */
    private final EventSink fanout = new Fanout();

    /** A game over an engine it is handed (it does not close it). */
    public Game(NativeEngine engine) { this(engine, false); }

    private Game(NativeEngine engine, boolean owns) {
        if (engine == null || !engine.alive()) throw new IllegalArgumentException("a game needs a running engine");
        this.engine = engine;
        this.ownsEngine = owns;
    }

    /** A game over an engine of its own, made with this configuration; closed with the game. Throws when there is no native core. */
    public static Game create(NativeEngine.Config config) {
        NativeEngine e = NativeEngine.create(config);
        if (e == null) throw new IllegalStateException("the Astratto native core could not be started: " + AstrattoNative.reason());
        return new Game(e, true);
    }

    /** Where the game's log lines go (C++ modules' lines too). */
    public Game logger(Consumer<String> to) { logger = to == null ? s -> { } : to; return this; }

    /** Makes a world the active one (from the next tick): routes, sight, bodies and agents are in it. */
    public Game activate(long worldHandle) {
        thread();
        world = worldHandle;
        view = worldHandle == 0 ? null : engine.view(worldHandle);
        commands.worldActivate(worldHandle);
        return this;
    }

    /** Adds a Java module; its {@link GameModule#init} runs now. */
    public <T extends GameModule> T add(T module) {
        thread();
        modules.add(module);
        module.init(this);
        return module;
    }

    /**
     * Loads a C++ gameplay module from a shared library (see {@code astratto_module.h}), with any configuration text it
     * understands. It runs inside every frame from the next tick. Returns its handle; throws when it cannot be loaded.
     */
    public long loadNative(String libraryPath, String config) {
        thread();
        long h = engine.loadModule(libraryPath, config == null ? "" : config);
        if (h == 0) throw new IllegalStateException("module " + libraryPath + " was not loaded: " + engine.lastError());
        nativeModules.add(h);
        return h;
    }

    /** One tick of dt seconds: modules' updates, the engine's frame, its events, modules' after-frame. False when the engine refused the batch. */
    public boolean tick(float dt) {
        thread();
        if (closed) throw new IllegalStateException("the game has been closed");
        for (GameModule m : modules) m.update(this, dt);
        boolean ok = engine.frame(commands, dt, fanout);
        if (!ok) logger.accept("frame refused: " + engine.lastError());
        time += dt;
        ticks++;
        for (GameModule m : modules) m.afterFrame(this);
        return ok;
    }

    private void thread() {
        if (Thread.currentThread() != owner)
            throw new IllegalStateException("a game is driven from the thread that made it (" + owner.getName() + "), not " + Thread.currentThread().getName());
    }

    // ------------------------------------------------------------------ GameContext
    @Override public NativeEngine engine() { return engine; }
    @Override public CommandBuffer commands() { return commands; }
    @Override public long world() { return world; }
    @Override public WorldView view() { return view; }
    @Override public long reserve(int kind) { return engine.reserve(kind); }
    @Override public Snapshots.Bodies bodies() { return engine.bodies(); }
    @Override public Snapshots.Agents agents() { return engine.agents(); }
    @Override public double time() { return time; }
    @Override public long ticks() { return ticks; }
    @Override public void log(String text) { logger.accept(text); }
    @Override public <T extends GameModule> T module(Class<T> type) {
        for (GameModule m : modules) if (type.isInstance(m)) return type.cast(m);
        return null;
    }

    /** The Java modules, in the order they run. */
    public List<GameModule> modules() { return java.util.Collections.unmodifiableList(modules); }

    /** Shuts the Java modules down (last added first), releases the C++ ones, and closes the engine if the game made it. */
    @Override public void close() {
        if (closed) return;
        thread();
        closed = true;
        for (int i = modules.size() - 1; i >= 0; i--) {
            try { modules.get(i).shutdown(this); } catch (RuntimeException ex) { logger.accept(modules.get(i).name() + " did not shut down cleanly: " + ex); }
        }
        for (int i = nativeModules.size() - 1; i >= 0; i--) engine.release(nativeModules.get(i));
        // a C++ module's last log lines come back with the next frame
        if (engine.alive()) engine.frame(commands, 0, fanout);
        commands.free();
        if (ownsEngine) engine.close();
    }

    /** Every event to every Java module, in the order they were added; a C++ module's log line to the game's log as well. */
    private final class Fanout implements EventSink {
        @Override public void path(int request, int[] cells, int expansions) { for (GameModule m : modules) m.path(request, cells, expansions); }
        @Override public void sight(int request, boolean clear) { for (GameModule m : modules) m.sight(request, clear); }
        @Override public void walk(int request, int value) { for (GameModule m : modules) m.walk(request, value); }
        @Override public void ray(int request, float distance, int x, int y, int z, int nx, int ny, int nz, int block) { for (GameModule m : modules) m.ray(request, distance, x, y, z, nx, ny, nz, block); }
        @Override public void error(int command, int status, long handle) { for (GameModule m : modules) m.error(command, status, handle); }
        @Override public void landed(long body, float speed) { for (GameModule m : modules) m.landed(body, speed); }
        @Override public void settled(long body) { for (GameModule m : modules) m.settled(body); }
        @Override public void gear(long vehicle, int gear) { for (GameModule m : modules) m.gear(vehicle, gear); }
        @Override public void ragdollState(long ragdoll, int state, int previous, int outcome) { for (GameModule m : modules) m.ragdollState(ragdoll, state, previous, outcome); }
        @Override public void vehicleImpact(long vehicle, float dv, float x, float y, float z) { for (GameModule m : modules) m.vehicleImpact(vehicle, dv, x, y, z); }
        @Override public void blockHit(long vehicle, float speed, float x, float y, float z, int bx, int by, int bz) { for (GameModule m : modules) m.blockHit(vehicle, speed, x, y, z, bx, by, bz); }
        @Override public void agentAction(long agent, int action, int previous) { for (GameModule m : modules) m.agentAction(agent, action, previous); }
        @Override public void agentSees(long agent, long other) { for (GameModule m : modules) m.agentSees(agent, other); }
        @Override public void agentLost(long agent, long other) { for (GameModule m : modules) m.agentLost(agent, other); }
        @Override public void agentArrived(long agent, long target) { for (GameModule m : modules) m.agentArrived(agent, target); }
        @Override public void agentStuck(long agent) { for (GameModule m : modules) m.agentStuck(agent); }
        @Override public void agentHeard(long agent, int loudness) { for (GameModule m : modules) m.agentHeard(agent, loudness); }
        @Override public void voiceEnded(long voice) { for (GameModule m : modules) m.voiceEnded(voice); }
        @Override public void moduleLog(long module, String text) { logger.accept(text); for (GameModule m : modules) m.moduleLog(module, text); }
        @Override public void other(int type, java.nio.ByteBuffer record) { for (GameModule m : modules) m.other(type, record.duplicate().order(record.order())); }
    }
}
