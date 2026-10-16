package astratto.ai.spatial;

import astratto.ai.api.GameWorld;
import astratto.ai.api.GameWorld.TacticalPoint;
import astratto.ai.api.Vec3;
import astratto.ai.cognition.TargetBelief;
import astratto.ai.group.Role;
import astratto.ai.runtime.NeuralConfig;
import java.util.List;

/**
 * Chooses where to fight from. Every candidate point is scored against where the threat is <i>believed</i> to be -
 * the scorer is never given a target, only a belief - by
 *
 *   coverScore = 3*protection + 2*firingAngle + 1.5*flankAdvantage - travelCost - 2.5*exposure
 *
 * protection: of several rays from the believed threat to the body's knees, chest and head as it would be behind
 * the point, how many the world stops. firingAngle: from the place the body would shoot from, is there a line to the
 * believed position. flankAdvantage: how far round to the side of the squad's line of fire the point is (for those
 * whose job is the flank). travelCost: how far it is. exposure: whether the way there crosses ground the threat can
 * see. Flank destinations are found the same way, from the believed engagement axis.
 */
public final class CoverScorer {
    private final GameWorld world;
    private final NeuralConfig cfg;
    public long rays, evaluated;

    public record Choice(TacticalPoint point, Vec3 position, double score, boolean protectedFrom, boolean canFire) {}

    public CoverScorer(GameWorld world, NeuralConfig cfg) { this.world = world; this.cfg = cfg; }

    /** Share of the body, behind this point, that the believed threat cannot see (0 = wide open, 1 = fully hidden). */
    public double protection(Vec3 at, Vec3 threat, boolean low) {
        Vec3 from = threat.add(new Vec3(0, 1.5, 0));
        double[] hs = low ? new double[]{0.35, 0.75, 1.05} : new double[]{0.4, 1.0, 1.6};
        int blocked = 0;
        for (double h : hs) { rays++; if (!world.rayClear(from, at.add(new Vec3(0, h, 0)))) blocked++; }
        return blocked / (double) hs.length;
    }

    public boolean canFireFrom(Vec3 peek, Vec3 threat) {
        rays++;
        return world.rayClear(peek.add(new Vec3(0, 1.5, 0)), threat.add(new Vec3(0, 1.2, 0)));
    }

    /**
     * The best point for an actor in a role. axisFrom is where the squad is fighting from (the engagement axis runs
     * from there to the believed threat); wantRange is the distance this weapon likes. Returns null when nothing in
     * reach is better than standing still.
     */
    public Choice best(long actor, Vec3 from, TargetBelief belief, Role role, Vec3 axisFrom, double wantRange, List<Vec3> allies) { return best(actor, from, belief, role, axisFrom, wantRange, allies, java.util.Set.of()); }

    /** The best point that is not in skip (points already found to be somebody else's). */
    public Choice best(long actor, Vec3 from, TargetBelief belief, Role role, Vec3 axisFrom, double wantRange, List<Vec3> allies, java.util.Set<Long> skip) {
        if (!belief.known()) return null;
        Vec3 threat = belief.estimatedPosition();
        Vec3 axis = threat.sub(axisFrom).flat().normalized();
        // where the role would ideally be: the search for points is centred there as well as on the actor
        Vec3 ideal = from;
        double toThreat = from.distanceXZ(threat);
        if (role.flank()) {
            double side = role == Role.FLANK_LEFT ? 1 : -1;
            ideal = threat.add(axis.left().mul(side * cfg.flankDistance)).add(axis.mul(-cfg.flankDistance * 0.25));
        } else if (role == Role.ADVANCE) {
            double step = Math.min(9, Math.max(0, toThreat - Math.max(6, wantRange * 0.6)));
            ideal = from.add(threat.sub(from).flat().normalized().mul(step));
        }
        List<TacticalPoint> pts = world.tacticalPointsNear(from, cfg.coverRadius);
        if (ideal.distanceXZ(from) > 5) pts.addAll(world.tacticalPointsNear(ideal, cfg.coverRadius * 0.6));
        Choice best = null;
        java.util.Set<Long> seen = new java.util.HashSet<>();
        for (TacticalPoint p : pts) {
            if (!seen.add(p.id()) || skip.contains(p.id())) continue;
            evaluated++;
            if (world.hazardAt(p.position())) continue;
            // the obstacle has to be between the point and the threat at all
            Vec3 toT = threat.sub(p.position()).flat().normalized();
            double facing = -p.normal().dot(toT);
            if (facing < 0.25) continue;
            double prot = protection(p.position(), threat, p.low()) * (0.6 + 0.4 * p.integrity());
            if (prot < 0.34) continue;
            boolean fire = canFireFrom(p.peekPosition(), threat);
            double d = p.position().distanceXZ(threat), run = from.distanceXZ(p.position());
            // 0.9: a bound forward has to gain ground. Without this an advancing man was handed whichever point scored best -
            // often the one he had just left, since the one he stood at was excluded - and ran to and fro between the two
            if (role == Role.ADVANCE && d > toThreat - 2.0) continue;
            double angle = fire ? 0.6 + 0.4 * facing : role.flank() ? 0.25 : 0;
            double want = role == Role.ADVANCE ? Math.max(6, wantRange * 0.6) : role.flank() ? cfg.flankDistance : wantRange;
            angle -= Math.min(0.6, Math.abs(d - want) * 0.03);
            double flank = 0;
            if (role.flank()) { Vec3 rel = p.position().sub(threat).flat().normalized(); flank = 1 - Math.abs(rel.dot(axis)); double side = axis.left().dot(rel) * (role == Role.FLANK_LEFT ? 1 : -1); if (side < 0) flank -= 0.8; }
            double travel = Math.min(1.5, run / 18.0) * (role == Role.HOLD || role == Role.SUPPRESS ? 1.6 : 1);
            double exposure = 0;
            if (run > 3) { rays++; if (world.rayClear(threat.add(new Vec3(0, 1.5, 0)), from.add(p.position()).mul(0.5).add(new Vec3(0, 1.2, 0)))) exposure = Math.min(1, run / 10.0); }
            // a flank that has to cross the squad's own line of fire is no flank
            if (role.flank() && run > 4) { Vec3 mid = from.add(p.position()).mul(0.5), rel = mid.sub(axisFrom).flat(); double alongAxis = rel.dot(axis), off = Math.abs(axis.left().dot(rel)); if (alongAxis > 2 && alongAxis < toThreat && off < 1.5) exposure += 0.8; }
            double s = cfg.wProtection * prot + cfg.wFiringAngle * angle + cfg.wFlank * flank - cfg.wTravel * travel - cfg.wExposure * exposure;
            for (Vec3 a : allies) { double ad = a.distanceXZ(p.position()); if (ad < 1.6) s -= 3.5; else if (ad < 9) s += 0.15; }
            if (p.canLeanLeft() || p.canLeanRight()) s += 0.2;
            if (best == null || s > best.score() || s == best.score() && p.id() < best.point().id()) best = new Choice(p, p.position(), s, prot >= 0.66, fire);
        }
        return best;
    }

    /** A place to go round to when there is no cover on the flank: a standable point off the side of the believed axis. */
    public Vec3 flankPoint(Vec3 axisFrom, TargetBelief belief, Role role) {
        Vec3 threat = belief.estimatedPosition(), axis = threat.sub(axisFrom).flat().normalized();
        double side = role == Role.FLANK_LEFT ? 1 : -1;
        for (double back : new double[]{0.2, 0.5, 0.0, 0.8}) for (double out : new double[]{1, 0.75, 1.3}) {
            Vec3 p = world.standNear(threat.add(axis.left().mul(side * cfg.flankDistance * out)).add(axis.mul(-cfg.flankDistance * back)));
            if (p != null && Math.abs(p.y() - threat.y()) < 3 && !world.hazardAt(p)) return p;
        }
        return null;
    }
}
