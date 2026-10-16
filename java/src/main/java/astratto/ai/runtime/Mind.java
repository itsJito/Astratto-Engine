package astratto.ai.runtime;

import astratto.ai.api.GameWorld.TacticalPoint;
import astratto.ai.api.SydneyBridge;
import astratto.ai.api.Vec3;
import astratto.ai.cognition.BeliefStore;
import astratto.ai.cognition.TargetBelief;
import astratto.ai.debug.DecisionTrace;
import astratto.ai.decision.AiAction;
import astratto.ai.decision.CogState;
import astratto.ai.group.Role;
import astratto.ai.perception.VisionMeter;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Everything one NPC knows, wants and is in the middle of. There is one of these per agent and nothing in it is
 * the world's truth: meters it has filled by looking, beliefs it has formed, the role it was dealt, the action it
 * has committed to. The host's entity is known only by its id.
 */
public final class Mind {
    public final long id;
    public final long squad;
    public final String faction, archetype;
    public final NeuralConfig.Profile profile;
    /** A stable offset in [0, 1): spreads this agent's ticks across the frame so a squad does not all think at once. */
    public final double phase;

    // ---- senses
    public final Map<Long, VisionMeter> meters = new LinkedHashMap<>();
    public final Set<Long> evidenceSeen = new HashSet<>();
    public long soundCursor;
    /** 0 calm .. 1 on edge. Rises with anything alarming, falls slowly. */
    public double alert;
    public double visionAcc, beliefAcc, decisionAcc;
    /** Something has happened that will not wait for the next decision step (a grenade, a hit, a first sighting, a blocked path). */
    public boolean urgent;
    /** When the target currently in sight was first confirmed: recognition takes a moment (the profile's reaction latency). */
    public double sightedAt = -1e9;

    // ---- knowledge
    public final BeliefStore beliefs;
    /** The belief decisions are made from right now (an immutable snapshot). */
    public TargetBelief belief = TargetBelief.NONE;
    /** When this mind last looked at its believed position and found nobody there. */
    public double lastContradicted = -1e9;

    // ---- state and intent
    public CogState state = CogState.PATROL;
    public double stateSince;
    public Role role = Role.HOLD;
    public double roleSince, roleUntil = -1e9;
    public AiAction action;
    public double actionSince, commitUntil = -1e9, actionScore;
    public final Map<String, Double> cooldownUntil = new LinkedHashMap<>();

    // ---- the fight as this one is living it
    public double suppression, pinnedUntil = -1e9, lastHitTime = -1e9, flankedAt = -1e9;
    public TacticalPoint cover;
    public double coverSince, coverUntil = -1e9;
    public Vec3 destination;
    /** 0.9: a place out of the threat's sight this one has gone to ground in for want of real cover, and until when it stays put there. */
    public Vec3 concealAt;
    public double concealUntil = -1e9;
    /** 0.9: broken and out of the fight: reached somewhere to hide and staying down in it. */
    public boolean cowering;
    public double hidAt = -1e9;
    public long searchRegion = -1;
    public double searchSince, searchStarted = -1e9;
    public int regionsInspected;
    public SearchPhase searchPhase = SearchPhase.REACT;
    public double burstUntil = -1e9, restUntil = -1e9, peekUntil = -1e9, hideUntil = -1e9, reloadUntil = -1e9, lastShot = -1e9, lastSpoke = -1e9;
    public int shotsFired, speculativeShots;
    public long meleeAction;
    /** Set when this mind has just seen its target go down: the host may want to know (bodies are looted, the dead are not hunted). */
    public boolean justKilled;
    /** The last status the body gave for the current motion, and how long it has been reporting itself blocked. */
    public SydneyBridge.ActionStatus motionStatus = SydneyBridge.ActionStatus.COMPLETED;
    public double blockedFor;
    public int replans;
    /** Shoulder to shoulder with a squadmate and nothing to get behind: where this one is stepping aside to, until when, and when it next checks. */
    public Vec3 aside;
    public double asideUntil = -1e9, asideCheck;
    /** 0.8: when the host last held a shot of this one's for a friend in the line of it, and how many times it has stepped clear. */
    public double lineBlockedAt = -1e9;
    public int lineBlocks;
    /** A grenade or other immediate danger this mind knows about: where, and until when it matters. */
    public Vec3 danger;
    public double dangerUntil = -1e9;

    // ---- squadmate orders (the player's element): what the leader last told this one to do
    public String order = "";
    public Vec3 orderPoint;
    public boolean isMate;
    /** 0.8: told to hold fire. It still moves, takes cover and reports - and still defends itself at arm's length or when hit. */
    public boolean holdFire;

    // ---- what kind of body this mind is in (0.8)
    /** A creature: one of the infected. It hunts, feeds and wanders; it does not speak, reason from evidence, take cover or run away. */
    public boolean creature;
    /** No eyes: nothing this mind believes was ever seen. */
    public boolean blind;
    /** When a creature began attending to a noise, and when it reached the place it went to look at (-1e9 = not yet). */
    public double noticeSince = -1e9, arrivedAt = -1e9;

    public final DecisionTrace trace = new DecisionTrace();

    public enum SearchPhase { REACT, APPROACH_LAST_KNOWN, INSPECT, EXPAND, SWEEP, REACQUIRE, DEESCALATE }

    public Mind(long id, long squad, String faction, String archetype, NeuralConfig cfg) {
        this.id = id; this.squad = squad; this.faction = faction; this.archetype = archetype;
        this.profile = cfg.profile(faction);
        this.beliefs = new BeliefStore(cfg);
        this.phase = ((id * 2654435761L) >>> 8 & 1023) / 1024.0;
    }

    public VisionMeter meter(long target) { return meters.computeIfAbsent(target, k -> new VisionMeter()); }
    public boolean onCooldown(String name, double now) { Double t = cooldownUntil.get(name); return t != null && now < t; }
    public void cooldown(String name, double until) { cooldownUntil.put(name, until); }
    public boolean pinned(double now) { return now < pinnedUntil; }

    public void enter(CogState s, double now) {
        if (state == s) return;
        trace.add(now, "state " + state + " -> " + s);
        state = s; stateSince = now;
        // back to routine: whatever the body last said about a route is about a route nobody is walking any more
        if (s == CogState.PATROL) { motionStatus = SydneyBridge.ActionStatus.COMPLETED; blockedFor = 0; }
    }
}
