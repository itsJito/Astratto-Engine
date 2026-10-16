package starter;

import astratto.bridge.AstrattoNative;
import astratto.bridge.CommandBuffer;
import astratto.bridge.Snapshots;
import astratto.game.GameContext;
import astratto.game.GameModule;
import java.util.List;

import static astratto.bridge.AstrattoNative.*;

/**
 * Gameplay in Java, behaviour in the engine: a watcher. What it WANTS is written here as a policy - wander about; go to
 * whatever it hears (the radio the C++ module keeps switching on, the player's steps); walk up to the player whenever
 * it sees them - and the engine does the rest natively: it looks with its own sight lines, scores the actions, routes
 * round the hut and walks its body. This module only reads what it decided (events and a snapshot).
 */
public final class WatcherModule implements GameModule {
    public static final int WANDER = 1, INVESTIGATE = 2, APPROACH = 3;
    public long agent, body, policy;
    private final PlayerModule player;
    public int decisions, sightings, heard, arrivals;
    public int lastAction = -1;
    public float x, z, startX = 28.5f, startZ = 16.5f, travelled;

    public WatcherModule(PlayerModule player) { this.player = player; }

    @Override public void init(GameContext ctx) {
        CommandBuffer c = ctx.commands();
        policy = ctx.reserve(KIND_POLICY);
        body = ctx.reserve(KIND_MOVER);
        agent = ctx.reserve(KIND_AGENT);
        c.policyCreate(policy, List.of(
                new CommandBuffer.Action(WANDER, ACT_WANDER).base(0.3f).range(14).speed(1.6f),
                new CommandBuffer.Action(INVESTIGATE, ACT_INVESTIGATE).base(0.7f).speed(2.4f)
                        .consider(IN_SINCE_HEARD, CURVE_INVERSE, 0, 12, 1),
                new CommandBuffer.Action(APPROACH, ACT_APPROACH).base(1.0f).range(2.5f).speed(3.2f)
                        .consider(IN_TARGET_SEEN, CURVE_STEP, 0, 1, 1)), 0.15f, 0.2f);
        c.moverCreate(body, startX, EmptyMap.FLOOR + 0.5f, startZ, 0.3f, 1.8f, 0.6f, MOVER_AGENT, 1.2f);
        c.agentCreate(agent, policy, body, 0, 0, 0, 1.6f, 40, 0.3f, 40, 1, AGENT_DOORS, 1234);
        c.agentTarget(agent, player.body, 0, 0, 0);
        x = startX; z = startZ;
    }

    @Override public void agentAction(long a, int action, int previous) { if (a == agent) { decisions++; lastAction = action; } }
    @Override public void agentSees(long a, long other) { if (a == agent && other == player.body) sightings++; }
    @Override public void agentHeard(long a, int loudness) { if (a == agent) heard++; }
    @Override public void agentArrived(long a, long target) { if (a == agent) arrivals++; }

    @Override public void afterFrame(GameContext ctx) {
        Snapshots.Mover m = ctx.bodies().mover(body);
        if (m == null) return;
        travelled += (float) Math.hypot(m.x - x, m.z - z);
        x = m.x; z = m.z;
    }

    public String describe() {
        return String.format("watcher: %d decisions (last %s), saw the player %d times, heard %d, arrived %d times, walked %.1f m",
                decisions, lastAction == WANDER ? "wander" : lastAction == INVESTIGATE ? "investigate" : lastAction == APPROACH ? "approach" : "-", sightings, heard, arrivals, travelled);
    }
}
