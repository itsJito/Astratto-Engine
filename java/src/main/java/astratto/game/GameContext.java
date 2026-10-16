package astratto.game;

import astratto.bridge.CommandBuffer;
import astratto.bridge.NativeEngine;
import astratto.bridge.Snapshots;
import astratto.bridge.WorldView;

/**
 * Astratto Engine: what a {@link GameModule} sees of the engine. Everything here is for the thread that drives the
 * {@link Game}; nothing is safe to keep and use from another thread.
 */
public interface GameContext {
    /** The engine itself (for what has no shortcut here: sounds, world edits between frames, more worlds). */
    NativeEngine engine();

    /** This tick's command batch: what is written here goes across with the next frame. */
    CommandBuffer commands();

    /** The active world (0 when there is none), and a read-only view of its cells and light. */
    long world();
    WorldView view();

    /** Fresh handles for things a command will make this tick ({@code AstrattoNative.KIND_*}). */
    long reserve(int kind);

    /** The bodies and agents as the last frame left them. Each call takes a fresh snapshot. */
    Snapshots.Bodies bodies();
    Snapshots.Agents agents();

    /** Seconds simulated since the game began, and ticks run. */
    double time();
    long ticks();

    /** A line for the game's log. */
    void log(String text);

    /** Another module of this game, by its class (null when there is none). */
    <T extends GameModule> T module(Class<T> type);
}
