package astratto.ai.spatial;

import astratto.ai.cognition.TargetBelief;

/** Where to look next, and who is already going there. One planner serves one squad's hunt for one target. */
public interface SearchPlanner {
    /** A destination held for one actor until a time, so two searchers are not sent to the same place. */
    record SearchReservation(long regionId, long actorId, double expiresAt) {}

    /** The best region for this actor to go and look at, or -1 when there is nothing worth looking at. */
    long bestRegionFor(long actorId, TargetBelief belief, double now);
    boolean reserve(long regionId, long actorId, double until);
    void releaseActor(long actorId);
    /** The actor has looked into the region and the target was not in it. */
    void markVisibleEmpty(long actorId, long regionId, double now);
}
