package starter;

import astratto.bridge.Snapshots;
import astratto.game.GameContext;
import astratto.game.GameModule;

import static astratto.bridge.AstrattoNative.*;

/**
 * Gameplay in Java, physics in the engine: things to knock about. A person (Sydney's fifteen-particle ragdoll, held
 * upright by its controllers) who is shoved every few seconds and catches themself or falls and gets up; a car that
 * drives round in circles; a crate (a soft body) dropped from a height. The game decides when and how hard; the engine
 * simulates at its own fixed rates and says what happened.
 */
public final class ToysModule implements GameModule {
    public long person, car, crate;
    public int ragdollStates, falls, getUps, gearChanges, crateLandings;
    public float carStartZ, carStartX, carTravel;
    private float nextShove = 3;

    @Override public void init(GameContext ctx) {
        person = ctx.reserve(KIND_RAGDOLL);
        car = ctx.reserve(KIND_VEHICLE);
        crate = ctx.reserve(KIND_SOFTBODY);
        ctx.commands().ragdollCreate(person, 1, SYDNEY_HUMAN, RAGDOLL_ACTIVE | RAGDOLL_STAND, 36.5f, EmptyMap.FLOOR, 30.5f, 0, 0, 0, 0);
        float[] desc = {1400, 1.8f, 4.4f, 1.45f, 0, 0.5f, 0, 0,     // mass, size, centre of mass, turn mass
                4, 33, 8, 1.5f, 1.2f,                                   // engine
                1, 0.82f, 0.5f,                                         // traction
                34, 9, 0.62f,                                           // steering lock (degrees), brakes
                1.4f, 0.28f, 0.28f, -0.16f, 0.5f, 0.3f,                 // suspension
                0.34f, 0.55f};                                          // wheel radius, handbrake grip
        float[] wheels = {-0.8f, 0.34f, 1.35f, 0.8f, 0.34f, 1.35f, -0.8f, 0.34f, -1.35f, 0.8f, 0.34f, -1.35f};
        carStartX = 70.5f; carStartZ = 60.5f;
        ctx.commands().vehicleCreate(car, desc, wheels, 4, DRIVE_REAR, 0, null, carStartX, EmptyMap.FLOOR + 0.8f, carStartZ, 0);
        // a crate: eight corners, every pair joined
        float s = 0.8f, cx = 30, cy = EmptyMap.FLOOR + 6, cz = 25;
        float[][] p = new float[8][];
        for (int i = 0; i < 8; i++) p[i] = new float[]{cx + ((i & 1) == 0 ? 0 : s), cy + ((i & 2) == 0 ? 0 : s), cz + ((i & 4) == 0 ? 0 : s), 0.08f, 4};
        int[][] sticks = new int[28][];
        for (int a = 0, k = 0; a < 8; a++) for (int b = a + 1; b < 8; b++) sticks[k++] = new int[]{a, b};
        ctx.commands().softbodyCreate(crate, p, sticks, null, 0, 0, 0, 0.4f, 0, 0.2f);
    }

    @Override public void update(GameContext ctx, float dt) {
        double t = ctx.time();
        if (t >= nextShove) {
            nextShove += 6;
            ctx.commands().ragdollHit(person, RD_NECK, 1, 0, 0.3f, 260, 45);
        }
        ctx.commands().vehicleInput(car, 0.55f, 0, 0.45f, false, false, true);
    }

    @Override public void ragdollState(long r, int state, int previous, int outcome) {
        if (r != person) return;
        ragdollStates++;
        if (state == STATE_FALL) falls++;
        if (state == STATE_GETUP) getUps++;
    }
    @Override public void gear(long v, int g) { if (v == car) gearChanges++; }
    @Override public void settled(long b) { if (b == crate) crateLandings++; }

    @Override public void afterFrame(GameContext ctx) {
        Snapshots.Vehicle v = ctx.bodies().vehicle(car);
        if (v != null) carTravel = Math.max(carTravel, (float) Math.hypot(v.matrix[12] - carStartX, v.matrix[14] - carStartZ));
    }

    public String describe() {
        return String.format("toys: person %d state changes (%d falls, %d get-ups), car %d gear changes and %.1f m from where it started, crate came to rest %d times",
                ragdollStates, falls, getUps, gearChanges, carTravel, crateLandings);
    }

}
