package astratto.game;

import astratto.bridge.EventSink;

/**
 * Astratto Engine: a piece of game logic written in Java. A game is any number of these (and of C++ modules, see
 * {@code native/include/astratto/astratto_module.h}), run by a {@link Game}. A Java module and a C++ module see the
 * engine the same way: they write commands, read snapshots and the world view, and are shown each frame's events.
 *
 * <p>Lifecycle, all on the thread that drives the {@link Game} - never on an engine thread:
 * <ol>
 *   <li>{@link #init} once, when the module is added (the engine and its world exist by then);</li>
 *   <li>{@link #update} once a tick, before the engine's frame: commands written to {@link GameContext#commands()} go
 *       across with that frame, after the commands of the modules added before this one;</li>
 *   <li>the events of the frame, by kind, through the {@link EventSink} methods this interface inherits (all default
 *       to nothing: override the ones the module cares about);</li>
 *   <li>{@link #shutdown} once, when the game closes (modules are shut down in the reverse order they were added).</li>
 * </ol>
 *
 * <p>Inside a frame, C++ modules run as well (after the commands of all Java modules, before the simulation); their
 * commands and the Java modules' are applied in that order, and every module sees the same events.
 */
public interface GameModule extends EventSink {
    /** A name for logs. */
    default String name() { return getClass().getSimpleName(); }

    /** Once, when the module joins the game. */
    default void init(GameContext ctx) { }

    /** Once a tick, before the engine's frame: dt is the time the frame will simulate. */
    default void update(GameContext ctx, float dt) { }

    /** Once a tick, after the frame's events have been delivered (snapshots taken now show the frame's outcome). */
    default void afterFrame(GameContext ctx) { }

    /** Once, when the game closes. */
    default void shutdown(GameContext ctx) { }
}
