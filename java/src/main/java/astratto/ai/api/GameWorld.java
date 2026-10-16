package astratto.ai.api;

import java.util.List;

/**
 * Everything NeuralAI is allowed to ask the game. This interface is the seam: cognition and decision code never see
 * the host's entities, only what comes through here - and the methods that could reveal a hidden target's position
 * ({@link #actorPosition}, {@link #actorForward}, {@link #actorVelocity}) are for the perception layer alone. A
 * decision is made from beliefs; see cognition.TargetBelief and the invariant test in tools.NeuralTests.
 */
public interface GameWorld {
    /** 0.9: one room of a building as a search sees it: somewhere to stand in it, which storey, and whether it is only a way through (a hall, a stair). */
    record Room(long id, Vec3 stand, int floor, boolean passage) {}
    /** 0.9: a building with rooms - what "he went into that house" refers to. */
    record Structure(long id, String name, double x0, double z0, double x1, double z1, java.util.List<Room> rooms) {
        public boolean contains(Vec3 p) { return p.x() >= x0 && p.x() <= x1 + 1 && p.z() >= z0 && p.z() <= z1 + 1; }
    }
    /** The building a point is in or right up against (within margin metres of its walls), or null in the open. */
    default Structure structureAt(Vec3 p, double margin) { return null; }
    /** 1.3: the way out of the building a point is in - where to stand to hold it, just outside its entrance - or null in the open. */
    default Vec3 exitOf(Vec3 p) { return null; }

    double simulationTimeSeconds();

    // ---- actors. Positions of anyone but the asker are sensor-only.
    Vec3 actorPosition(long id);
    Vec3 actorForward(long id);
    Vec3 actorVelocity(long id);
    boolean isAlive(long id);
    /** Eye height above the feet right now (standing, crouched, prone), and the body's full height. */
    double eyeHeight(long id);
    double bodyHeight(long id);
    /** Would the observer treat the other as an enemy if it knew it was there. */
    boolean hostile(long observer, long other);

    // ---- physics
    /** Nothing that blocks sight lies between the two points (walls, closed doors; glass does not; foliage is counted separately). */
    boolean rayClear(Vec3 from, Vec3 to);
    /** Cells of vegetation the line passes through: each one hides a little more of what is behind it. */
    int foliageBetween(Vec3 from, Vec3 to);
    /** How lit a point is, 0 (dark) .. 1 (daylight or a lamp). */
    double lightAt(Vec3 p);
    /** How far anything can be seen at all right now (fog, night, rain). */
    double visibilityRange();

    // ---- sensors' candidate sets (never the answer, only who to test)
    List<Long> potentialTargetsNear(Vec3 center, double radius);
    /**
     * 1.2: how far this observer can make this particular target out, at the most, and how far it will open fire on it
     * (the engine takes the lesser of this and the observer's own sight). The host's rules, not the engine's: it has none.
     */
    default double sightLimit(long observer, long target) { return Double.MAX_VALUE; }
    default double engageLimit(long shooter, long target) { return Double.MAX_VALUE; }
    List<SoundEvent> soundEventsSince(long sequence);
    long soundSequence();
    List<Evidence> evidenceNear(Vec3 center, double radius);

    // ---- navigation and positions
    NavPath path(Vec3 from, Vec3 to);
    /** A straight walk between two nearby points is possible (no wall, drop or closed door in it). */
    boolean walkable(Vec3 from, Vec3 to);
    List<TacticalPoint> tacticalPointsNear(Vec3 origin, double radius);
    /** A standable point at or beside the given one, or null. */
    Vec3 standNear(Vec3 p);
    /** Reserve / release a tactical point for an actor until a time: nobody shares a narrow position. */
    boolean reservePoint(long pointId, long actor, double until);
    void releasePoints(long actor);
    /** Something that makes a place unusable right now: fire, acid, a grenade about to go off. */
    boolean hazardAt(Vec3 p);

    // ---- the only way a shot is ever fired
    void issueWeaponCommand(long actor, WeaponCommand command);

    // ---- what an actor knows about itself (never about a target)
    SelfState self(long id);
    /** Is the target lit up so that it shows in the dark (a torch, a muzzle flash). Sensor-only. */
    boolean lit(long id);
    /** How much light the observer's own torch throws on a target, 0 .. 1 (0 when it carries none or the target is out of its reach). Sensor-only. */
    double torchLight(long observer, long target);
    /**
     * Says a line for a dialogue event (contact, lost, flanking, casualty, reloading, moving, clear ...). Returns
     * true only if it was actually spoken aloud - the squad's verbal reports ride on this.
     */
    boolean speak(long actor, String event, String detail);
    /** Lobs a grenade at a point (the host owns throwables, fuses and blast). Returns false if the actor has none or may not. */
    boolean throwGrenade(long actor, Vec3 at);

    /**
     * Which way an actor's body is turned - as against where it is looking, which is {@link #actorForward}. For the
     * player in third person the two differ: the eyes follow the camera, the body follows the feet. A squad forms up
     * behind the body.
     */
    Vec3 bodyForward(long id);

    // ---- the host's own rules of conduct and daily life, carried out when the mind chooses them (0.8)
    /**
     * The actor's side has a claim on its hands right now that is not a fight: a hold-up in progress, a warning being
     * given, a loner backing away from trouble, a body being stripped (the last only when atPeace).
     */
    boolean conductHolds(long id, boolean atPeace);
    /** One frame of that conduct. */
    void conduct(long id, double dt);
    /** One frame of the actor's ordinary day: its post, its patrol, its bunk, the containers it works through. */
    void routine(long id, double dt);
    /** A creature's own surroundings: is there a body within reach that it could feed on. */
    boolean mealNear(long id);

    /**
     * What a body is to its mind, beyond {@link SelfState}: whether there is a mind in it at all in the human sense
     * (a creature has none: it does not speak, read evidence, take cover or run), and whether it has eyes.
     */
    record Nature(boolean creature, boolean blind) {
        public static final Nature PERSON = new Nature(false, false);
    }
    Nature nature(long id);

    /**
     * An actor's own condition: where it stands, what it carries and what it can still do.
     * weaponKind is one of auto, shotgun, scoped, rifle, handgun, none.
     */
    record SelfState(Vec3 position, Vec3 forward, double health, double ammo, String weaponKind, boolean canWalk, boolean canShoot, boolean armed, boolean radio,
                     double sightRange, double arcFarDeg, double hearing, double morale, double breakAt, boolean staggered, boolean grounded, Vec3 home, double leash, double walkSpeed, double runSpeed) {}

    record NavPath(List<Vec3> corners, double length, boolean reachable) {}

    /**
     * A place to fight from. position is where the body stands; peekPosition is where it has to be to bring a weapon
     * to bear (over a low obstacle it is the same place, standing; round a high one it is a step to the side);
     * normal points from the obstacle toward the body.
     */
    record TacticalPoint(long id, Vec3 position, Vec3 peekPosition, Vec3 normal, boolean low, boolean canLeanLeft, boolean canLeanRight, double integrity) {}

    /** Something that made a noise. sequence is monotonic: each listener keeps its own cursor and hears each event once. */
    record SoundEvent(long sequence, Vec3 position, double radius, double intensity, String kind, long sourceId, double time) {}

    /** Something left behind that says "someone was here": a fallen ally, blood, a forced door, a round in the wall. */
    record Evidence(long id, Vec3 position, Vec3 sourceDirection, double confidence, String kind, double time) {}

    /** aim is a point in the world the muzzle is laid on - a believed position, never an entity. */
    record WeaponCommand(Vec3 aim, boolean trigger, boolean reload, boolean speculative, long intendedTarget) {}
}
