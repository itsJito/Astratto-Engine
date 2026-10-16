package astratto.ai.decision;

import astratto.ai.api.SydneyBridge.ActionStatus;
import astratto.ai.api.SydneyBridge.CreatureIntent;
import astratto.ai.api.SydneyBridge.CreatureMode;
import astratto.ai.api.Vec3;
import astratto.ai.cognition.TargetBelief;
import astratto.ai.runtime.Mind;
import astratto.ai.runtime.NeuralAi;
import astratto.ai.runtime.NeuralConfig;
import java.util.List;

/**
 * Everything a creature's mind can choose to do (0.8). The infected think with the same engine as people - the same
 * senses, the same beliefs with the same growing error - and differ only in what they do about what they believe:
 * there is no cover, no role, no report and no retreat in here. Each action states an intent and the creature's body
 * carries it out in the way of its kind (see SydneyBridge.CreatureIntent).
 *
 * As with people, none of these can see a target. A hunt goes to where the belief says; the quarry is named to the
 * body only while it is in sight.
 */
public final class CreatureActions {
    private final NeuralAi ai;
    private final NeuralConfig cfg;

    public final List<AiAction> hunt, scour, prowl, notice, idle;

    public CreatureActions(NeuralAi ai) {
        this.ai = ai;
        this.cfg = ai.cfg;
        hunt = List.of(new Hunt());
        scour = List.of(new Scour());
        prowl = List.of(new Prowl());
        notice = List.of(new Notice());
        idle = List.of(new Feed(), new Wander());
    }

    public List<AiAction> of(CogState s) {
        return switch (s) { case COMBAT -> hunt; case SEARCH -> scour; case INVESTIGATE -> prowl; case SUSPICIOUS -> notice; case PATROL -> idle; default -> List.of(); };
    }

    private void issue(AiContext c, CreatureMode mode) {
        Mind m = ai.mind(c.id());
        TargetBelief b = c.belief();
        boolean vis = b.visible() && b.targetId() > 0;
        ai.creature(m, new CreatureIntent(mode, b.known() ? b.estimatedPosition() : c.position(), vis ? b.targetId() : 0, vis, b.known() ? b.age(c.now()) : 1e9));
    }

    private abstract static class Base implements AiAction {
        public void begin(AiContext c) { }
        public void cancel(AiContext c) { }
        public boolean finished(AiContext c) { return false; }
        public double utility(AiContext c) { return 1; }
        public double commitment() { return 0.3; }
    }

    /** It has it in sight, or had a moment ago: straight at it. */
    private final class Hunt extends Base {
        public String name() { return "Hunt"; }
        public boolean allowed(AiContext c) { return c.state() == CogState.COMBAT && c.belief().known(); }
        public void tick(AiContext c, double dt) { issue(c, CreatureMode.HUNT); }
    }

    /** It was here. It is not now. It goes to where it last had it and turns about. */
    private final class Scour extends Base {
        public String name() { return "Scour"; }
        public boolean allowed(AiContext c) { return c.state() == CogState.SEARCH; }
        public void tick(AiContext c, double dt) { issue(c, c.belief().known() ? CreatureMode.SCOUR : CreatureMode.WANDER); }
        public boolean finished(AiContext c) { return c.now() - ai.mind(c.id()).stateSince > cfg.creatureSearchSeconds; }
    }

    /**
     * A noise, a smell of something, a blow from nowhere: go to the place. Arriving and finding nothing, it looks
     * about for a while and loses interest - sooner if it has no eyes to look with. A second noise while it is there
     * moves the place, and the looking starts again.
     */
    private final class Prowl extends Base {
        public String name() { return "Prowl"; }
        public boolean allowed(AiContext c) { return c.state() == CogState.INVESTIGATE && c.belief().known(); }
        public void begin(AiContext c) { Mind m = ai.mind(c.id()); m.arrivedAt = -1e9; m.destination = c.belief().estimatedPosition(); }
        public void tick(AiContext c, double dt) {
            Mind m = ai.mind(c.id());
            Vec3 p = c.belief().estimatedPosition();
            if (m.destination == null || m.destination.distanceXZ(p) > 3) { m.destination = p; m.arrivedAt = -1e9; }
            if (m.arrivedAt < -1e8 && c.position().distanceXZ(p) < 2.4) m.arrivedAt = c.now();
            issue(c, CreatureMode.PROWL);
        }
        public boolean finished(AiContext c) {
            Mind m = ai.mind(c.id());
            double look = m.blind ? cfg.creatureLookSeconds * 0.65 : cfg.creatureLookSeconds + m.phase * 3;
            if (m.arrivedAt > -1e8 && c.now() - m.arrivedAt > look) return true;
            if (c.motionStatus() == ActionStatus.BLOCKED && c.blockedFor() > 6) return true;
            return c.belief().age(c.now()) > (m.blind ? 12 : 30);
        }
        public void cancel(AiContext c) { ai.mind(c.id()).destination = null; }
    }

    /** Something, faintly. The head comes round to it; if nothing more comes of it, the body follows to see. */
    private final class Notice extends Base {
        public String name() { return "Notice"; }
        public boolean allowed(AiContext c) { return c.state() == CogState.SUSPICIOUS; }
        public void begin(AiContext c) { ai.mind(c.id()).noticeSince = c.now(); }
        public void tick(AiContext c, double dt) { issue(c, c.belief().known() ? CreatureMode.NOTICE : CreatureMode.WANDER); }
        public boolean finished(AiContext c) {
            Mind m = ai.mind(c.id());
            return c.now() - m.noticeSince > cfg.creatureNoticeSeconds + (1 - c.belief().confidence01()) * cfg.creatureNoticeSpread + m.phase * 0.5;
        }
    }

    /** A body within reach of its nose: it goes to it and eats. */
    private final class Feed extends Base {
        public String name() { return "Feed"; }
        public boolean allowed(AiContext c) { return c.state() == CogState.PATROL && ai.world.mealNear(c.id()); }
        public double utility(AiContext c) { return 2; }
        public void tick(AiContext c, double dt) { issue(c, CreatureMode.FEED); }
        public double commitment() { return 1.5; }
    }

    /** Nothing to hunt and nothing to eat: it drifts. */
    private final class Wander extends Base {
        public String name() { return "Wander"; }
        public boolean allowed(AiContext c) { return c.state() == CogState.PATROL; }
        public void tick(AiContext c, double dt) { issue(c, CreatureMode.WANDER); }
    }
}
