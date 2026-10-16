package astratto.ai.decision;

import astratto.ai.runtime.Mind;
import java.util.List;

/**
 * Chooses what a mind does next. Every allowed action is scored and the best one wins - but a mind does not change
 * its mind for a better score alone. What it is doing is kept until it has run its commitment, and after that only
 * gives way to something that beats it by a margin; an action that just ended cannot be picked again until its
 * cooldown is over. Without these three, agents jitter between two near-equal options every tick. An emergency
 * action (a grenade at the feet, a blow coming in) ignores all of it.
 */
public final class Selector {
    public record Pick(AiAction action, double score, boolean changed, String why) {}

    /** The plain choice: the allowed action with the highest utility. */
    public AiAction choose(AiContext c, List<AiAction> actions) {
        AiAction best = null;
        double score = Double.NEGATIVE_INFINITY;
        for (AiAction a : actions) {
            if (!a.allowed(c)) continue;
            double u = a.utility(c);
            if (u > score) { score = u; best = a; }
        }
        return best;
    }

    /** The choice a mind actually makes: with commitment, hysteresis, cooldowns and emergency interrupts. */
    public Pick decide(Mind m, AiContext c, List<AiAction> actions, double hysteresis) {
        AiAction best = null, emergency = null;
        double bs = Double.NEGATIVE_INFINITY, es = Double.NEGATIVE_INFINITY, cur = Double.NEGATIVE_INFINITY;
        boolean curAllowed = false;
        // 0.8: an action that has finished is over. It may be chosen again - and is then begun again - but only like any
        // other: not while it is cooling down. (0.7 let a finished action that still scored highest be "kept" without ever
        // being restarted, so a man with no cover to run to stood in a spent TakeCover for the rest of the fight and never fired.)
        boolean curDone = m.action != null && actions.contains(m.action) && m.action.allowed(c) && m.action.finished(c);
        for (AiAction a : actions) {
            if (!a.allowed(c)) continue;
            if ((a != m.action || curDone) && m.onCooldown(a.name(), c.now())) continue;
            double u = a.utility(c);
            if (a == m.action) { cur = u; curAllowed = true; }
            if (a.emergency() && u > es) { es = u; emergency = a; }
            if (u > bs || u == bs && best != null && a.name().compareTo(best.name()) < 0) { bs = u; best = a; }
        }
        if (emergency != null && emergency != m.action && es > 0) return new Pick(emergency, es, true, "emergency");
        if (m.action != null && curAllowed && !curDone) {
            if (c.now() < m.commitUntil) return new Pick(m.action, cur, false, "committed");
            if (best == m.action || bs < cur + hysteresis + Math.abs(cur) * hysteresis) return new Pick(m.action, cur, false, "kept");
        }
        if (best == null) return new Pick(null, 0, m.action != null, "nothing allowed");
        return new Pick(best, bs, best != m.action || curDone, m.action == null ? "start" : curDone ? "finished" : !curAllowed ? "no longer allowed" : "better");
    }
}
