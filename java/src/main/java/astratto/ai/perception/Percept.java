package astratto.ai.perception;

import astratto.ai.api.Vec3;

/**
 * One thing a sense reported this tick. It is all the cognition layer ever receives about the world: where the
 * sense puts the stimulus (exactly, for someone in plain sight; a point and an error radius for anything else), how
 * sure it is, and what kind of thing it was.
 */
public record Percept(Kind kind, long subjectId, Vec3 position, Vec3 velocity, double radius, double confidence, VisionMeter.Level level, String detail, boolean threat, double time) {
    public enum Kind { SIGHT, SOUND, EVIDENCE, HIT, REPORT }

    public static Percept sight(long id, Vec3 pos, Vec3 vel, double radius, double conf, VisionMeter.Level level, double time) { return new Percept(Kind.SIGHT, id, pos, vel, radius, conf, level, "", true, time); }
    public static Percept sound(long source, Vec3 pos, double radius, double conf, String kind, boolean threat, double time) { return new Percept(Kind.SOUND, source, pos, Vec3.ZERO, radius, conf, VisionMeter.Level.NONE, kind, threat, time); }
    public static Percept evidence(long id, Vec3 pos, double radius, double conf, String kind, double time) { return new Percept(Kind.EVIDENCE, id, pos, Vec3.ZERO, radius, conf, VisionMeter.Level.NONE, kind, true, time); }
}
