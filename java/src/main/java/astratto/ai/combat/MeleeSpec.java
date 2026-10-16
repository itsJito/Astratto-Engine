package astratto.ai.combat;

/**
 * A close attack as the AI understands it: when it may be tried and how long each part of it lasts. The body's
 * motion is Sydney's; whether it hits is the host's. The progression is always
 * APPROACH -> WINDUP -> ACTIVE_WINDOW -> RECOVERY -> RELEASE, and an attack is held for its windup and recovery so
 * that a mind cannot restart it every tick.
 */
public record MeleeSpec(String id, double minRange, double maxRange, double maxFacingAngleDeg, double windup, double activeWindow, double recovery) {
    public enum Phase { APPROACH, WINDUP, ACTIVE_WINDOW, RECOVERY, RELEASE }

    public static final MeleeSpec STRIKE = new MeleeSpec("strike", 0.6, 2.2, 50, 0.36, 0.14, 0.75);
    public static final MeleeSpec SHOVE = new MeleeSpec("shove", 0.0, 1.3, 70, 0.20, 0.12, 0.55);
    public static final MeleeSpec GRAB = new MeleeSpec("grab", 0.4, 2.0, 45, 0.30, 0.14, 1.30);

    public double commitment() { return windup + activeWindow + recovery; }

    /** The attack for a distance and a moment: a shove makes room, a grab holds someone who is off balance, otherwise a blow. */
    public static MeleeSpec pick(double distance, boolean targetVulnerable, boolean grabAllowed) {
        if (targetVulnerable && grabAllowed && distance < GRAB.maxRange()) return GRAB;
        if (distance < SHOVE.maxRange()) return SHOVE;
        return STRIKE;
    }
}
