package astratto.ai.api;

/**
 * NeuralAI's only way to move a body. The AI says what it means to do - go there at this gait in this stance, look
 * there, swing at that - and the Sydney side makes the motion, on the host's own character controller and physics.
 * Nothing here names an animation clip. Every request that takes time has a status the AI can read, so a task is
 * held until the body says it finished, could not start, was blocked or was knocked out of it.
 */
public interface SydneyBridge {
    enum Stance { STAND, CROUCH, PRONE }
    enum Locomotion { STOP, WALK, RUN, SPRINT }
    enum ActionStatus { RUNNING, COMPLETED, BLOCKED, FAILED, INTERRUPTED }

    /** destination and facingTarget are world points; a null facingTarget faces the way of travel. */
    record MotionIntent(Vec3 destination, Vec3 facingTarget, Stance stance, Locomotion gait, boolean aimWhileMoving) {}
    record AimIntent(Vec3 worldTarget, double steadiness) {}
    record MeleeIntent(long targetId, String attackType, double reachMeters, double commitmentSeconds) {}
    record ReactionIntent(Vec3 impulse, String reactionType, double severity) {}

    /**
     * What a creature - one of the infected - means to do (0.8). A creature's mind decides this from what it has
     * seen, heard or smelt and nothing else; how its kind of body carries it out (a lunge, a swollen burst, a charge
     * with a chainsaw raised) is the body's own business, as a stride is for a person.
     *   HUNT    go for what it believes is there        SCOUR   it was here a moment ago: look for it
     *   PROWL   something made a noise: go and find out  NOTICE  turn the head to it, then the body
     *   FEED    there is a body within reach             WANDER  nothing to hunt
     */
    enum CreatureMode { HUNT, SCOUR, PROWL, NOTICE, FEED, WANDER }

    /**
     * point is where the mind believes its quarry (or the noise) is. targetId names the quarry only while the mind
     * has it in sight - so that the host can rule on a blow that lands - and is 0 at every other time.
     */
    record CreatureIntent(CreatureMode mode, Vec3 point, long targetId, boolean targetVisible, double beliefAge) {}

    void setMotion(long actorId, MotionIntent intent);
    /** The creature's body takes the intent and moves, attacks or feeds in the way of its kind. */
    void setCreature(long actorId, CreatureIntent intent);
    void setAim(long actorId, AimIntent intent);
    void requestMelee(long actorId, long actionId, MeleeIntent intent);
    void requestReaction(long actorId, long actionId, ReactionIntent intent);
    /** Status of a melee or reaction request by its id; for the current motion intent pass {@link #MOTION}. */
    ActionStatus status(long actorId, long actionId);
    boolean canReachMelee(long attackerId, long targetId, double reachMeters);
    void cancelAction(long actorId, long actionId);

    /** The action id that stands for "the motion intent this actor currently has". */
    long MOTION = 0;

    /** Things the body reports as they happen (section 11): the host's combat rules and the AI's replanning both hang off these. */
    enum Event { CONTACT_WINDOW_OPEN, CONTACT_WINDOW_CLOSED, FOOTING_LOST, DODGE_COMPLETED, MOTION_BLOCKED, ACTION_COMPLETED }

    interface Listener { void onSydneyEvent(long actorId, long actionId, Event event); }
    void addListener(Listener l);
}
