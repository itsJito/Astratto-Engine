package astratto.events;

/** Typed domain events. Each is an immutable record of a validated fact. */
public final class Events {
    private Events() {}

    public record ShotFired(int shooterId, String faction, float x, float y, float z, float noise, String weaponId, boolean suppressed, float rpm, float dirX, float dirZ) {}
    public record SoundEmitted(int sourceId, float x, float y, float z, float intensity, String category, boolean infectedOnly) {}
    public record ActorInjured(int victimId, int attackerId, String region, float damage, boolean penetrated) {}
    public record ActorKilled(int victimId, int attackerId, String victimFaction, String attackerFaction, float x, float y, float z, boolean witnessed, String archetype) {}
    public record ItemTransferred(long uid, String defId, String from, String to, int qty) {}
    public record ItemConsumed(long uid, String defId, int qty, String reason) {}
    public record FactionStandingChanged(String faction, float delta, float now, String reason) {}
    public record DistrictControlChanged(String district, String faction, float influence, String reason) {}
    public record ExtractionStarted(String exitId, float duration) {}
    public record ExtractionCompleted(String exitId) {}
    public record RaidFailed(String reason) {}
    public record BlockDestroyed(int x, int y, int z, int blockId, int byActor) {}
    public record ContainerSearched(long spotId, String table, float x, float y, float z) {}
    public record DocumentRead(String docId) {}
    public record InfrastructureChanged(String site, String state) {}
    public record ObjectiveProgress(String missionId, String objective) {}

    // ---- 0.5: confirmed combat facts (the hit marker, the killfeed and the squad panel are driven by these and nothing else)
    /** Damage was applied to a living character. */
    public record HitConfirmed(int attackerId, int victimId, String region, float damage, float time) {}
    /** 1.2: an animal was killed (the victim of a {@link HitConfirmed} that is an animal has a negative id). */
    public record AnimalKilled(int killerId, String kind, float time) {}
    /** A human went down instead of dying. Not a kill. */
    public record ActorDowned(int victimId, int attackerId, String victimName, String victimFaction, float time, String kind) {}
    public record ActorRevived(int victimId, int rescuerId, String victimName, float time) {}
    public record ReviveInterrupted(int victimId, int rescuerId, String reason) {}
    /** A character died: exactly one of these per death, whoever or whatever caused it. */
    public record KillConfirmed(int killerId, String killerName, String killerFaction, int victimId, String victimName, String victimFaction, String weaponId, String cause,
                                boolean friendlyFire, boolean headshot, boolean playerSide, float time) {}
}
