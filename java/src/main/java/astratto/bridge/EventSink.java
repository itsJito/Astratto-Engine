package astratto.bridge;

/**
 * Astratto Engine: what comes back from a frame, one method an event kind. Every method has an empty default, so a
 * caller implements only the events it cares about. They are called on the thread that called {@link NativeEngine#frame},
 * before it returns, in the order the engine produced them (answers in the order the questions were written); never
 * from an engine thread. Arrays handed over are fresh copies: they may be kept.
 */
public interface EventSink {
    /** A route: cells {x, y, z, ...} from the first step, or null when there is none. */
    default void path(int request, int[] cells, int expansions) { }
    default void sight(int request, boolean clear) { }
    /** A walkability answer: 1/0 for CLEAR, CLEAR_BODY and STAND; a height or -1 for STEP_Y. */
    default void walk(int request, int value) { }
    /** A ray: distance (-1 when nothing was struck), the cell struck, the face it was struck on, the block. */
    default void ray(int request, float distance, int x, int y, int z, int nx, int ny, int nz, int block) { }
    /** A command that could not be carried out (the frame went on): its type, why, and the handle it was about. */
    default void error(int command, int status, long handle) { }

    /** A mover or a car came down (speed), a ragdoll or soft body came to rest, a car changed gear. */
    default void landed(long body, float speed) { }
    default void settled(long body) { }
    default void gear(long vehicle, int gear) { }
    /** Sydney's state machine moved on ({@code STATE_*}), and how it came out ({@code OUTCOME_*}). */
    default void ragdollState(long ragdoll, int state, int previous, int outcome) { }
    /** A car struck something (dv: the change of speed); blockHit: it went into a block at speed (bx, by, bz). */
    default void vehicleImpact(long vehicle, float dv, float x, float y, float z) { }
    default void blockHit(long vehicle, float speed, float x, float y, float z, int bx, int by, int bz) { }

    /** An agent chose an action (its id; previous: the one before, -1 none). */
    default void agentAction(long agent, int action, int previous) { }
    default void agentSees(long agent, long other) { }
    default void agentLost(long agent, long other) { }
    default void agentArrived(long agent, long target) { }
    default void agentStuck(long agent) { }
    default void agentHeard(long agent, int loudness) { }

    default void voiceEnded(long voice) { }

    /** A line logged by a C++ gameplay module. */
    default void moduleLog(long module, String text) { }

    /** Any event of a kind not listed above (a newer engine's): its type and its whole record, read-only. */
    default void other(int type, java.nio.ByteBuffer record) { }
}
