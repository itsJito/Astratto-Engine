package astratto.ai.group;

import astratto.ai.api.GameWorld;
import astratto.ai.api.Vec3;
import astratto.ai.cognition.ConfidenceClass;
import astratto.ai.cognition.TargetBelief;
import astratto.ai.decision.CogState;
import astratto.ai.runtime.Mind;
import astratto.ai.runtime.NeuralConfig;
import astratto.ai.spatial.SearchGrid;
import java.util.ArrayList;
import java.util.List;

/**
 * One squad's shared arrangements. Every member reacts to what is in front of it by itself; what the coordinator
 * adds is the things only a group can decide: who does which job, and who goes where so that two people are never
 * sent to the same place.
 *
 * Roles are dealt by utility - utility(a, r) = suitability + positioning + equipment + knowledge - risk -
 * switchingCost - under quotas (one flanker a side, one or two suppressors, so that a squad never all does the
 * same thing), highest scores first. A role lasts a few seconds; an actor going down, a first sighting or an expiry
 * deals them again. Survival, a dodge or a collision always outrank a role: the coordinator proposes, the member's
 * own decision layer disposes.
 *
 * The squad also has a picture of the fight that is its own store - the best estimate anyone has reported - kept
 * apart from what each member believes. Members learn from it only through messages (see {@link ReportBus}).
 */
public final class SquadCoordinator {
    public final long id;
    public final String faction;
    public final List<Mind> members = new ArrayList<>();
    private final NeuralConfig cfg;
    /** The squad's own picture: the last estimate reported to it, by whom and when. */
    public TargetBelief shared = TargetBelief.NONE;
    public double sharedAt = -1e9;
    public long sharedBy;
    /** Where the squad is looking, when it has lost what it was fighting. */
    public SearchGrid search;
    public boolean radioEnabled = true;
    public double rolesAt = -1e9;
    public boolean dirty = true;
    /** Group memory: who has gone down, how often contact has been lost. */
    public int casualties, escapes;
    public double lastCasualty = -1e9, lastContact = -1e9, lastSpeech = -1e9, grenadeAt = -1e9;
    /** Counters (gates). */
    public int assignments, roleChanges;

    public SquadCoordinator(long id, String faction, NeuralConfig cfg) { this.id = id; this.faction = faction; this.cfg = cfg; }

    public double caution(double now) { return Math.min(1, casualties * 0.3 + (now - lastCasualty < 20 ? 0.3 : 0)); }

    /** A report reached the squad's net: it becomes the shared picture if it is better than what is there. */
    public void note(TargetBelief b, long by, double now) {
        if (!b.known()) return;
        boolean better = !shared.known() || b.kind().ordinal() > shared.kind().ordinal() || b.lastEvidenceTime() > shared.lastEvidenceTime() + 0.5 || now - sharedAt > 6;
        if (!better) return;
        shared = b; sharedAt = now; sharedBy = by;
        if (b.kind().ordinal() >= ConfidenceClass.LAST_SEEN.ordinal()) lastContact = now;
    }

    /** What a role needs to know about one member that is not in its mind: where it is and what shape it is in. */
    public interface Facts { GameWorld.SelfState of(long id); }

    private double utility(Mind m, GameWorld.SelfState s, Role r, Vec3 axisFrom, Vec3 threat, double now) {
        NeuralConfig.Profile p = m.profile;
        boolean contact = threat != null;
        double hurt = 1 - s.health(), sup = m.suppression;
        // ---- suitability: what this kind of soldier and this temperament are for
        double suit = cfg.bias(m.archetype, r.name());
        switch (r) {
            case ADVANCE -> suit += p.aggression() * 0.9 - p.caution() * 0.3;
            case FLANK_LEFT, FLANK_RIGHT -> suit += p.aggression() * 0.7 + p.teamPreference() * 0.2 - p.caution() * 0.2;
            case HOLD -> suit += p.caution() * 0.6;
            case SUPPRESS -> suit += p.teamPreference() * 0.5;
            case RETREAT -> suit += p.caution() * 0.4 - p.aggression() * 0.4 - 0.9;
            case SEARCH, INVESTIGATE -> suit += contact ? -1.5 : 0.9;
            case WATCH -> suit += contact ? -2 : 0.5 + p.caution() * 0.6 - p.aggression() * 0.2;
            case REPOSITION -> suit += -0.4;
        }
        // ---- positioning: where it already is, relative to the line the squad is fighting along
        double pos = 0;
        if (contact && axisFrom != null) {
            Vec3 axis = threat.sub(axisFrom).flat().normalized(), rel = s.position().sub(axisFrom).flat();
            double side = axis.left().dot(rel);                 // > 0: already out to the left of the line
            double d = s.position().distanceXZ(threat);
            switch (r) {
                case FLANK_LEFT -> pos += Math.max(-0.6, Math.min(0.8, side * 0.12));
                case FLANK_RIGHT -> pos += Math.max(-0.6, Math.min(0.8, -side * 0.12));
                case ADVANCE -> pos += Math.max(-0.5, 0.6 - d * 0.02);
                case SUPPRESS -> pos += 0.5 - Math.abs(d - 24) * 0.02;
                case HOLD -> pos += (m.cover != null ? 0.5 : 0) + Math.min(0.4, d * 0.01);
                default -> { }
            }
        }
        // ---- equipment
        double eq = 0;
        switch (r) {
            case SUPPRESS -> eq += ("auto".equals(s.weaponKind()) ? 0.8 : "shotgun".equals(s.weaponKind()) ? -0.6 : 0) + (s.ammo() < 0.3 ? -0.7 : 0);
            case ADVANCE, FLANK_LEFT, FLANK_RIGHT -> eq += "shotgun".equals(s.weaponKind()) ? 0.6 : "scoped".equals(s.weaponKind()) ? -0.8 : 0;
            case HOLD -> eq += "scoped".equals(s.weaponKind()) ? 0.7 : s.ammo() < 0.3 ? 0.4 : 0;
            case WATCH -> eq += "shotgun".equals(s.weaponKind()) || "auto".equals(s.weaponKind()) ? 0.4 : 0;
            default -> { }
        }
        if (!s.canShoot() && (r == Role.SUPPRESS || r == Role.ADVANCE || r.flank())) eq -= 3;
        // ---- knowledge: someone who can see the target should be shooting at it; someone who cannot can move
        double know = 0;
        TargetBelief b = m.belief;
        if (b.visible()) know += r == Role.SUPPRESS || r == Role.HOLD ? 0.5 : r.flank() ? -0.4 : 0;
        else if (b.known()) know += r.flank() ? 0.3 : r == Role.SUPPRESS ? 0.2 : 0;
        else know += r == Role.SEARCH || r == Role.INVESTIGATE ? 0.4 : -0.3;
        // ---- risk: the hurt and the pinned do not go forward
        double risk = 0;
        if (r == Role.ADVANCE || r.flank()) risk += hurt * 1.6 + sup * 0.9 + caution(now) * 0.6 + (s.canWalk() ? 0 : 5);
        if (r == Role.RETREAT) risk -= hurt * 2.4 + (hurt > 0.7 ? 1.2 : 0);
        if (r == Role.HOLD) risk -= hurt * 0.4;
        double sw = r != m.role && now < m.roleUntil ? cfg.switchingCost : 0;
        return suit + pos + eq + know - risk - sw;
    }

    /** How many of a role the squad wants right now. */
    private int quota(Role r, int fit, boolean contact, boolean fresh) {
        if (!contact) return r == Role.SEARCH ? fit : r == Role.INVESTIGATE ? 1 : r == Role.HOLD ? fit : r == Role.WATCH ? (fit >= 3 ? 1 : 0) : 0;
        return switch (r) {
            case FLANK_LEFT, FLANK_RIGHT -> fit >= 3 && fresh ? cfg.maxFlankPerSide : 0;
            case SUPPRESS -> fit >= 2 ? Math.min(cfg.maxSuppress, Math.max(1, fit / 3)) : 0;
            case ADVANCE -> Math.max(1, (int) Math.ceil(fit / 3.0));
            // holding is not where everyone ends up by default: with three or more fit, at most half of them hold and the rest are given something to do
            case HOLD -> fit >= 3 ? Math.max(1, fit / 2) : fit;
            case RETREAT, REPOSITION -> fit;
            default -> 0;
        };
    }

    /**
     * Deals the roles: every (member, role) pair is scored, and the best pair still available is taken until everyone
     * has a job or the quotas are full; whoever is left holds. Greedy, and for squads of this size indistinguishable
     * from the optimal matching.
     */
    public void assign(Facts facts, double now) {
        rolesAt = now; dirty = false; assignments++;
        List<Mind> team = new ArrayList<>();
        for (Mind m : members) if (m.state != CogState.DEAD && m.state != CogState.INCAPACITATED) team.add(m);
        if (team.isEmpty()) return;
        team.sort(java.util.Comparator.comparingLong(m -> m.id));
        // the threat as the squad understands it: the shared picture, else the best any member holds
        TargetBelief ref = shared;
        for (Mind m : team) if (m.belief.kind().ordinal() > ref.kind().ordinal()) ref = m.belief;
        boolean contact = ref.known() && ref.kind().ordinal() >= ConfidenceClass.APPROXIMATE.ordinal() && ref.confidence01() > 0.2;
        boolean fresh = contact && ref.age(now) < cfg.flankStale;
        Vec3 threat = contact ? ref.estimatedPosition() : null, centre = Vec3.ZERO;
        GameWorld.SelfState[] st = new GameWorld.SelfState[team.size()];
        int fit = 0;
        for (int i = 0; i < team.size(); i++) { st[i] = facts.of(team.get(i).id); centre = centre.add(st[i].position()); if (st[i].canWalk() && st[i].canShoot()) fit++; }
        centre = centre.mul(1.0 / team.size());
        Role[] roles = contact ? new Role[]{Role.RETREAT, Role.SUPPRESS, Role.FLANK_LEFT, Role.FLANK_RIGHT, Role.ADVANCE, Role.HOLD} : new Role[]{Role.WATCH, Role.SEARCH, Role.INVESTIGATE, Role.HOLD};
        int[] left = new int[roles.length];
        for (int k = 0; k < roles.length; k++) left[k] = quota(roles[k], fit, contact, fresh);
        double[][] u = new double[team.size()][roles.length];
        for (int i = 0; i < team.size(); i++) for (int k = 0; k < roles.length; k++) {
            u[i][k] = utility(team.get(i), st[i], roles[k], centre, threat, now);
            // nobody is told to run who is not hurt, and nobody is told to search who is in a fight
            if (roles[k] == Role.RETREAT && st[i].health() > 0.35 && team.get(i).suppression < 0.95) u[i][k] = -99;
        }
        boolean[] done = new boolean[team.size()];
        for (int n = 0; n < team.size(); n++) {
            int bi = -1, bk = -1;
            double best = -1e9;
            for (int i = 0; i < team.size(); i++) {
                if (done[i]) continue;
                for (int k = 0; k < roles.length; k++) if (left[k] > 0 && u[i][k] > best) { best = u[i][k]; bi = i; bk = k; }
            }
            if (bi < 0) break;
            done[bi] = true;
            // a job nobody is fit for is not handed out to fill a quota: someone that unsuited to going forward stays where they are
            if (best < MIN_FIT && contact && roles[bk] != Role.HOLD && roles[bk] != Role.RETREAT) { give(team.get(bi), Role.HOLD, now); continue; }
            left[bk]--;
            give(team.get(bi), roles[bk], now);
        }
        for (int i = 0; i < team.size(); i++) if (!done[i]) give(team.get(i), contact ? Role.HOLD : Role.SEARCH, now);
    }

    /** Below this score a member is not sent to do a moving job, whatever the quotas say. */
    public static double MIN_FIT = -0.9;

    private void give(Mind m, Role r, double now) {
        if (m.role != r) { roleChanges++; m.trace.add(now, "role " + m.role + " -> " + r); m.roleSince = now; m.cover = null; m.urgent = true; }
        m.role = r;
        m.roleUntil = now + cfg.roleSeconds * (0.85 + m.phase * 0.3);
    }

    /** Roles are dealt again when asked for, when they have run their time, or on the squad's own clock. */
    public boolean due(double now) {
        if (dirty) return true;
        if (now - rolesAt >= 1.0 / cfg.rolesHz) for (Mind m : members) if (now > m.roleUntil) return true;
        return false;
    }
}
