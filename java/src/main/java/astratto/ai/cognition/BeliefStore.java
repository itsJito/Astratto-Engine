package astratto.ai.cognition;

import astratto.ai.api.Vec3;
import astratto.ai.perception.Percept;
import astratto.ai.perception.VisionMeter;
import astratto.ai.runtime.NeuralConfig;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One mind's beliefs about who is out there. Each agent has its own store; what a squad knows together is a
 * separate one (group.SquadCoordinator) that members only learn from by being told.
 *
 * A sighting sets a tight, confident estimate. When sight is lost the last seen place is kept and the error round
 * it grows with time - radius(t) = initial + assumedTargetSpeed * elapsed - because an unseen target may have gone
 * anywhere it can walk to. A sound or a report can move the estimate, but only if it is fresher or more reliable
 * than what is already held: a stale radio message never overrides what the eyes have. Old beliefs fade and go.
 */
public final class BeliefStore {
    private final Map<Long, TargetBelief> beliefs = new LinkedHashMap<>();
    private final NeuralConfig cfg;
    /** Counters (gates): beliefs created from each kind of percept, and stale ones dropped. */
    public int fromSight, fromSound, fromEvidence, fromReport, rejectedStale, forgotten;

    public BeliefStore(NeuralConfig cfg) { this.cfg = cfg; }

    public TargetBelief of(long target) { return beliefs.getOrDefault(target, TargetBelief.NONE); }
    public List<TargetBelief> all() { return new ArrayList<>(beliefs.values()); }
    public boolean isEmpty() { return beliefs.isEmpty(); }
    public void forget(long target) { beliefs.remove(target); }
    public void clear() { beliefs.clear(); }

    /** The belief that matters most right now: what is in sight beats what was seen beats what was heard; then the surest; then the freshest. */
    public TargetBelief primary(double now) {
        TargetBelief best = TargetBelief.NONE;
        double bs = -1;
        for (TargetBelief b : beliefs.values()) {
            double s = b.kind().ordinal() * 10 + b.confidence01() * 4 - Math.min(8, b.age(now) * 0.05);
            if (s > bs) { bs = s; best = b; }
        }
        return best;
    }

    /** Takes in one percept. Returns true if it changed what is believed. */
    public boolean ingest(Percept p, double now) {
        if (p == null) return false;
        long key = p.subjectId();
        TargetBelief old = of(key);
        switch (p.kind()) {
            case SIGHT -> {
                if (p.level() == VisionMeter.Level.CONFIRMED) {
                    Vec3 dir = p.velocity().length() > 0.3 ? p.velocity().flat().normalized() : old.direction();
                    beliefs.put(key, new TargetBelief(key, ConfidenceClass.VISIBLE, p.position(), dir, cfg.visibleRadius, 1, now, now, p.position(), "sight", 1, now + cfg.forgetAfter));
                    fromSight++;
                    return true;
                }
                // a shape that has not resolved yet: a place to look at, nothing to act on as a position
                if (old.kind().ordinal() >= ConfidenceClass.LAST_SEEN.ordinal() && old.unseenFor(now) < 2) return false;
                if (p.level().ordinal() < VisionMeter.Level.SUSPICIOUS.ordinal()) return false;
                double conf = p.level() == VisionMeter.Level.PROBABLE ? 0.5 : 0.25;
                if (old.known() && old.confidence01() > conf && old.age(now) < 4) return false;
                beliefs.put(key, new TargetBelief(key, ConfidenceClass.APPROXIMATE, p.position(), old.direction(), Math.max(p.radius(), 3), conf, now, old.lastSeenTime(), old.lastSeenPosition(), "glimpse", 0.6, now + cfg.forgetAfter));
                fromSight++;
                return true;
            }
            case SOUND, HIT -> {
                if (old.visible()) return false;                                        // looking straight at them: a noise adds nothing
                if (old.kind() == ConfidenceClass.LAST_SEEN && old.unseenFor(now) < 1.5) return false;
                double conf = Math.max(0.15, Math.min(0.75, p.confidence()));
                // a vaguer idea does not replace a better one unless the better one has gone stale
                if (old.known() && old.confidence01() > conf * 1.4 && old.age(now) < 6 && p.radius() > old.radiusMeters()) { rejectedStale++; return false; }
                Vec3 dir = old.known() ? p.position().sub(old.estimatedPosition()).flat().normalized() : Vec3.ZERO;
                beliefs.put(key, new TargetBelief(key, ConfidenceClass.APPROXIMATE, p.position(), dir, Math.max(1, p.radius()), conf, now, old.lastSeenTime(), old.lastSeenPosition(), p.kind() == Percept.Kind.HIT ? "hit" : "sound:" + p.detail(), 0.6, now + cfg.forgetAfter));
                fromSound++;
                return true;
            }
            case EVIDENCE -> {
                if (old.kind().ordinal() > ConfidenceClass.EVIDENCE.ordinal() && old.age(now) < 20) return false;
                beliefs.put(key, new TargetBelief(key, ConfidenceClass.EVIDENCE, p.position(), Vec3.ZERO, Math.max(4, p.radius()), Math.min(0.5, p.confidence()), now, old.lastSeenTime(), old.lastSeenPosition(), "evidence:" + p.detail(), 0.4, now + cfg.forgetAfter));
                fromEvidence++;
                return true;
            }
            case REPORT -> {
                // told by someone: only as good as the teller and how old the telling is, and never better than own eyes
                if (old.visible() || old.kind() == ConfidenceClass.LAST_SEEN && old.unseenFor(now) < 2.5) { rejectedStale++; return false; }
                if (now - p.time() > cfg.reportExpiry) { rejectedStale++; return false; }
                if (old.known() && old.lastEvidenceTime() >= p.time() && old.confidence01() >= p.confidence()) { rejectedStale++; return false; }
                beliefs.put(key, new TargetBelief(key, ConfidenceClass.APPROXIMATE, p.position(), p.velocity().length() > 0.3 ? p.velocity().flat().normalized() : old.direction(), Math.max(2, p.radius()), Math.min(0.7, p.confidence()), p.time(), old.lastSeenTime(), old.lastSeenPosition(), "report:" + p.detail(), p.confidence(), now + cfg.reportExpiry));
                fromReport++;
                return true;
            }
        }
        return false;
    }

    /**
     * Time passes. A target that was in sight and is not any more becomes LAST_SEEN; every unseen belief's radius
     * grows at the speed a person might have moved, and its confidence leaks away; beliefs past their expiry go.
     */
    public void age(double now, double dt, java.util.function.LongPredicate stillVisible) {
        java.util.Iterator<Map.Entry<Long, TargetBelief>> it = beliefs.entrySet().iterator();
        List<TargetBelief> changed = new ArrayList<>();
        while (it.hasNext()) {
            TargetBelief b = it.next().getValue();
            if (b.visible()) {
                if (stillVisible.test(b.targetId())) continue;
                changed.add(new TargetBelief(b.targetId(), ConfidenceClass.LAST_SEEN, b.estimatedPosition(), b.direction(), 1.0, 0.9, b.lastEvidenceTime(), b.lastSeenTime(), b.lastSeenPosition(), "lost", b.reliability(), b.expiry()));
                continue;
            }
            // a belief goes when its time is up, not when it has grown faint: a faint idea of where a noise came from still has to
            // last the walk over to it (the mind that held it lets go of it when it has looked)
            if (now > b.expiry()) { it.remove(); forgotten++; continue; }
            double r = Math.min(cfg.maxUncertainty, b.radiusMeters() + cfg.assumedTargetSpeed * dt);
            double c = Math.max(0.05, b.confidence01() - dt * (b.kind() == ConfidenceClass.LAST_SEEN ? 0.012 : 0.02));
            changed.add(b.aged(r, c, b.kind()));
        }
        for (TargetBelief b : changed) beliefs.put(b.targetId(), b);
    }

    /** Looked at where the belief said and nobody was there: the belief is wrong about the place, so it widens and weakens. */
    public void contradicted(long target, double now) {
        TargetBelief b = of(target);
        if (!b.known() || b.visible()) return;
        beliefs.put(target, b.aged(Math.min(cfg.maxUncertainty, b.radiusMeters() * 1.5 + 4), b.confidence01() * 0.6, ConfidenceClass.APPROXIMATE));
    }
}
