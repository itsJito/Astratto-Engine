package astratto.ai.cognition;

import astratto.ai.api.Vec3;

/**
 * What an agent holds to be true about one target: a place, an error radius round it, how it came by the idea and
 * when. Immutable - a planner is handed a belief and can keep it for as long as it likes. This, and never the
 * target's transform, is what every decision is made from.
 */
public record TargetBelief(long targetId, ConfidenceClass kind, Vec3 estimatedPosition, Vec3 direction, double radiusMeters, double confidence01, double lastEvidenceTime,
                           double lastSeenTime, Vec3 lastSeenPosition, String source, double reliability, double expiry) {

    public static final TargetBelief NONE = new TargetBelief(0, ConfidenceClass.NONE, Vec3.ZERO, Vec3.ZERO, 0, 0, -1e9, -1e9, Vec3.ZERO, "", 0, -1e9);

    public boolean known() { return kind != ConfidenceClass.NONE; }
    public boolean visible() { return kind == ConfidenceClass.VISIBLE; }
    public double age(double now) { return now - lastEvidenceTime; }
    public double unseenFor(double now) { return now - lastSeenTime; }

    /** The same belief with its uncertainty grown to a new radius and its class lowered if it was a live sighting. */
    public TargetBelief aged(double radius, double confidence, ConfidenceClass k) {
        return new TargetBelief(targetId, k, estimatedPosition, direction, radius, confidence, lastEvidenceTime, lastSeenTime, lastSeenPosition, source, reliability, expiry);
    }

    public TargetBelief at(Vec3 p) {
        return new TargetBelief(targetId, kind, p, direction, radiusMeters, confidence01, lastEvidenceTime, lastSeenTime, lastSeenPosition, source, reliability, expiry);
    }
}
