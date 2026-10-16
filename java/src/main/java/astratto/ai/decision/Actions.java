package astratto.ai.decision;

import astratto.ai.api.SydneyBridge.ActionStatus;
import astratto.ai.api.SydneyBridge.Locomotion;
import astratto.ai.api.SydneyBridge.MeleeIntent;
import astratto.ai.api.SydneyBridge.Stance;
import astratto.ai.api.Vec3;
import astratto.ai.cognition.ConfidenceClass;
import astratto.ai.cognition.TargetBelief;
import astratto.ai.combat.MeleeSpec;
import astratto.ai.group.Role;
import astratto.ai.group.SquadCoordinator;
import astratto.ai.runtime.Mind;
import astratto.ai.runtime.NeuralAi;
import astratto.ai.runtime.NeuralConfig;
import astratto.ai.spatial.CoverScorer;
import astratto.ai.spatial.SearchGrid;
import java.util.List;

/**
 * Everything a human mind can choose to do, by state. Each action is one object shared by every agent; what an
 * agent is in the middle of lives in its {@link Mind}. An action reads the context it is given and the mind's own
 * memory, and acts only through the engine: a motion intent, an aim, a weapon command, a report. None of them can
 * see a target - where an action goes or shoots is where the belief says.
 */
public final class Actions {
    private final NeuralAi ai;
    private final NeuralConfig cfg;

    public final List<AiAction> combat, search, investigate, suspicious, withdraw, matePatrol, patrol;

    public Actions(NeuralAi ai) {
        this.ai = ai;
        this.cfg = ai.cfg;
        AiAction dodge = new Dodge(), conduct = new Conduct();
        combat = List.of(dodge, conduct, new Melee(), new Reload(), new Regroup(), new Reposition(), new Flank(), new Advance(), new Suppress(), new Grenade(), new TakeCover(), new FightFromCover(), new StandAndFight());
        search = List.of(dodge, conduct, new Regroup(), new WatchExit(), new SearchRegions());
        investigate = List.of(dodge, conduct, new Regroup(), new InvestigatePoint());
        suspicious = List.of(dodge, conduct, new LookAt());
        withdraw = List.of(dodge, conduct, new Retreat());
        matePatrol = List.of(dodge, new HoldOrder(), new MoveOrder(), new FollowLeader());
        patrol = List.of(dodge, conduct, new Routine());
    }

    // ------------------------------------------------------------------ shared pieces
    private Vec3 threat(AiContext c) { return c.belief().estimatedPosition(); }
    private boolean atCover(Mind m, AiContext c) { return m.cover != null && c.position().distanceXZ(m.cover.position()) < 1.25; }

    /** 0.9: at a place means on its floor as well as over its spot on the map. */
    private static boolean at(Vec3 pos, Vec3 p, double within) { return pos.distanceXZ(p) < within && Math.abs(pos.y() - p.y()) < 1.8; }

    /** 0.9: gone to ground out of the threat's sight (no cover point was to be had) and still meant to be there. */
    private boolean concealed(Mind m, AiContext c) { return m.concealAt != null && c.now() < m.concealUntil && c.position().distanceXZ(m.concealAt) < 1.8; }

    /** Somewhere to anchor a squadmate's fight: the order point, or the leader. Null for everyone else. */
    private Vec3 anchor(AiContext c) { return !c.mate() ? null : c.orderPoint() != null && !"follow".equals(c.order()) ? c.orderPoint() : c.leader(); }
    private double leash(AiContext c) { return "hold".equals(c.order()) ? cfg.holdLeash : "follow".equals(c.order()) || c.order().isEmpty() ? cfg.followLeash : cfg.moveLeash; }

    /**
     * Fire discipline: bursts with rests between them, the length of both set by role and nerve. Aimed fire tracks the
     * belief while it is a live sighting; speculative fire is thrown at where the target was, wide, to keep it there.
     */
    private void shoot(Mind m, AiContext c, boolean speculative) {
        if (!c.self().canShoot() || c.pinned() && !speculative) return;
        double now = c.now();
        // 0.8, fire discipline: nobody throws rounds at something further off than the weapon in their hands is any use at
        // (the player's own people least of all), and a squadmate told to hold fire shoots only to save itself
        if (c.distanceToBelief() > fireRange(c)) return;
        if (m.holdFire && !(c.sinceHit() < cfg.holdFireHitSeconds || c.belief().visible() && c.distanceToBelief() < cfg.holdFireCloseRange)) return;
        if (now < m.restUntil) return;
        if (now > m.burstUntil) {
            // a new burst: how long it lasts and how long the pause after it
            double len = "auto".equals(c.self().weaponKind()) ? 0.35 + ai.random(m) * 0.55 : 0.12;
            double rest = (c.role() == Role.SUPPRESS ? 0.55 : 0.9) + ai.random(m) * (speculative ? 1.2 : 0.9) + m.suppression * 0.8;
            if (m.burstUntil > 0 && now - m.burstUntil < rest) { m.restUntil = m.burstUntil + rest; if (now < m.restUntil) return; }
            m.burstUntil = now + len;
        }
        Vec3 aim = threat(c).add(new Vec3(0, 1.15, 0));
        if (speculative) { double e = cfg.speculativeError + c.belief().radiusMeters() * 0.5; aim = aim.add(new Vec3((ai.random(m) - 0.5) * 2 * e, (ai.random(m) - 0.3) * 0.8, (ai.random(m) - 0.5) * 2 * e)); m.speculativeShots++; }
        ai.fire(m, aim, speculative, c.belief().targetId());
    }

    /** How far off this agent will open fire with what it is holding (metres). */
    public double fireRange(AiContext c) {
        double r = switch (c.self().weaponKind()) { case "handgun" -> cfg.fireRangeHandgun; case "shotgun" -> cfg.fireRangeShotgun; case "auto" -> cfg.fireRangeAuto; case "scoped" -> cfg.fireRangeScoped; default -> cfg.fireRangeRifle; };
        return c.mate() ? r * cfg.mateFireRange : r;
    }

    /** Seen, and too far off to be worth a round: whoever is in that position closes the distance instead of shooting at it. */
    private boolean outOfRange(AiContext c) { return c.belief().known() && c.distanceToBelief() > fireRange(c) && !"hold".equals(c.order()); }

    private void hold(Mind m, AiContext c, Stance stance) { ai.stop(m, c.belief().known() ? threat(c) : null, stance); }

    // ================================================================== emergencies
    /** Something is about to go off, or burn, where this one stands: get away from it, now, whatever the plan was. */
    private final class Dodge implements AiAction {
        public String name() { return "Dodge"; }
        public boolean emergency() { return true; }
        public boolean allowed(AiContext c) { return c.dangerNear() && c.self().canWalk(); }
        public double utility(AiContext c) { return 10; }
        public void begin(AiContext c) {
            Mind m = ai.mind(c.id());
            Vec3 away = c.position().sub(c.danger()).flat().normalized();
            if (away.length() < 0.1) away = c.self().forward().mul(-1);
            Vec3 best = null;
            for (double turn : new double[]{0, 0.6, -0.6, 1.2, -1.2}) {
                double cs = Math.cos(turn), sn = Math.sin(turn);
                Vec3 dir = new Vec3(away.x() * cs - away.z() * sn, 0, away.x() * sn + away.z() * cs);
                Vec3 p = ai.world.standNear(c.position().add(dir.mul(cfg.grenadeRadius + 2)));
                if (p != null && ai.world.walkable(c.position(), c.position().add(dir.mul(2.5)))) { best = p; break; }
            }
            m.destination = best != null ? best : c.position().add(away.mul(6));
            m.cover = null;
            ai.world.releasePoints(m.id);
            ai.speak(m, "grenade", "");
        }
        public void tick(AiContext c, double dt) { Mind m = ai.mind(c.id()); ai.move(m, m.destination, null, Stance.STAND, Locomotion.SPRINT, false); }
        public boolean finished(AiContext c) { Mind m = ai.mind(c.id()); return !c.dangerNear() || m.destination != null && c.position().distanceXZ(m.destination) < 1; }
        public void cancel(AiContext c) { }
        public double commitment() { return 0.6; }
    }

    /** At arm's length the rifle is a club. The body winds up, the host decides whether it lands. */
    private final class Melee implements AiAction {
        public String name() { return "Melee"; }
        public boolean emergency() { return true; }
        public boolean allowed(AiContext c) {
            // a blow that has been started is finished: the target stepping away does not call it back, it makes it miss
            Mind m = ai.mind(c.id());
            if (m != null && m.meleeAction != 0 && ai.sydney.status(m.id, m.meleeAction) == ActionStatus.RUNNING) return true;
            return c.state() == CogState.COMBAT && c.belief().visible() && c.distanceToBelief() < 2.6 && !c.self().staggered();
        }
        public double utility(AiContext c) { return 6; }
        public void begin(AiContext c) { ai.mind(c.id()).meleeAction = 0; }
        public void tick(AiContext c, double dt) {
            Mind m = ai.mind(c.id());
            ai.aim(m, threat(c).add(new Vec3(0, 1.2, 0)), 1);
            if (m.meleeAction != 0) {
                ActionStatus st = ai.sydney.status(m.id, m.meleeAction);
                if (st == ActionStatus.RUNNING) { ai.stop(m, threat(c), Stance.STAND); return; }
                m.meleeAction = 0;
                m.cooldown("melee_swing", c.now() + 0.35);
            }
            if (m.onCooldown("melee_swing", c.now())) { ai.stop(m, threat(c), Stance.STAND); return; }
            if (!c.belief().visible() || c.distanceToBelief() >= 2.6) return;          // only here to finish a swing; there is not another to start
            double d = c.distanceToBelief();
            MeleeSpec spec = MeleeSpec.pick(d, false, m.profile.aggression() > 0.55);
            if (d > spec.maxRange() || !ai.sydney.canReachMelee(m.id, c.belief().targetId(), spec.maxRange())) { ai.move(m, threat(c), threat(c), Stance.STAND, Locomotion.RUN, true); return; }
            m.meleeAction = ai.nextActionId();
            ai.sydney.requestMelee(m.id, m.meleeAction, new MeleeIntent(c.belief().targetId(), spec.id(), spec.maxRange(), spec.commitment()));
            m.trace.add(c.now(), "melee " + spec.id());
        }
        public boolean finished(AiContext c) { return false; }
        public void cancel(AiContext c) { Mind m = ai.mind(c.id()); if (m.meleeAction != 0) { ai.sydney.cancelAction(m.id, m.meleeAction); m.meleeAction = 0; } }
        public double commitment() { return 0.5; }
    }

    // ================================================================== combat
    /** Out of rounds: get the head down and put more in. */
    private final class Reload implements AiAction {
        public String name() { return "Reload"; }
        public boolean allowed(AiContext c) { return c.state() == CogState.COMBAT && c.self().armed() && c.empty(); }
        public double utility(AiContext c) { return 4.2; }
        public void begin(AiContext c) { Mind m = ai.mind(c.id()); ai.reload(m); ai.speak(m, "reloading", ""); }
        public void tick(AiContext c, double dt) { Mind m = ai.mind(c.id()); if (m.cover != null) ai.move(m, m.cover.position(), threat(c), Stance.CROUCH, Locomotion.RUN, false); else hold(m, c, Stance.CROUCH); }
        public boolean finished(AiContext c) { return !c.empty(); }
        public void cancel(AiContext c) { }
        public double commitment() { return 1.4; }
    }

    /** A squadmate that has been drawn too far from the leader, or from where it was told to stay, comes back. */
    private final class Regroup implements AiAction {
        public String name() { return "Regroup"; }
        public boolean allowed(AiContext c) { Vec3 a = anchor(c); return a != null && c.self().canWalk() && c.position().distanceXZ(a) > leash(c); }
        public double utility(AiContext c) { return 3.6; }
        public void begin(AiContext c) { Mind m = ai.mind(c.id()); m.cover = null; ai.world.releasePoints(m.id); }
        public void tick(AiContext c, double dt) { Mind m = ai.mind(c.id()); ai.move(m, anchor(c), c.belief().known() ? threat(c) : null, Stance.STAND, Locomotion.SPRINT, false); }
        public boolean finished(AiContext c) { Vec3 a = anchor(c); return a == null || c.position().distanceXZ(a) < leash(c) * 0.6; }
        public void cancel(AiContext c) { }
        public double commitment() { return 1.5; }
    }

    /** The cover has stopped being cover: flanked, shot to pieces, pinned too long behind it, or on fire. Find another. */
    private final class Reposition implements AiAction {
        public String name() { return "Reposition"; }
        public boolean allowed(AiContext c) {
            Mind m = ai.mind(c.id());
            if (c.state() != CogState.COMBAT || !c.self().canWalk() || m.cover == null || !atCover(m, c)) return false;
            boolean flanked = c.belief().visible() && c.protection() < 0.34;
            boolean stuck = c.pinned() && c.now() - m.coverSince > cfg.pinnedRelocate;
            return flanked || stuck || ai.world.hazardAt(c.position()) || c.now() > m.coverUntil && c.role() != Role.HOLD && c.role() != Role.SUPPRESS && !c.belief().visible();
        }
        public double utility(AiContext c) { return 3.1; }
        public void begin(AiContext c) {
            Mind m = ai.mind(c.id());
            boolean flanked = c.belief().visible() && c.protection() < 0.34;
            if (flanked) { m.flankedAt = c.now(); ai.speak(m, "flanked", ai.bearing(c.position(), threat(c))); }
            else if (c.pinned()) ai.speak(m, "moving", "");
            long old = m.cover == null ? -1 : m.cover.id();
            m.cover = null;
            ai.world.releasePoints(m.id);
            CoverScorer.Choice ch = ai.pickCover(m, c, c.role(), old);
            if (ch == null) m.cooldown(name(), c.now() + 3);
        }
        public void tick(AiContext c, double dt) {
            Mind m = ai.mind(c.id());
            if (m.cover == null) { hold(m, c, Stance.CROUCH); return; }
            ai.move(m, m.cover.position(), threat(c), Stance.STAND, c.suppression() > 0.4 ? Locomotion.SPRINT : Locomotion.RUN, false);
        }
        public boolean finished(AiContext c) { Mind m = ai.mind(c.id()); return m.cover == null || atCover(m, c) || c.motionStatus() == ActionStatus.BLOCKED && c.blockedFor() > 1.2; }
        public void cancel(AiContext c) { }
        public double commitment() { return 1.8; }
        public double cooldown() { return 2.5; }
    }

    /** Round the side, out of sight, to come in where the cover does not face. */
    private final class Flank implements AiAction {
        public String name() { return "Flank"; }
        public boolean allowed(AiContext c) {
            Mind m = ai.mind(c.id());
            return c.state() == CogState.COMBAT && c.role().flank() && !m.holdFire && c.self().canWalk() && !c.pinned() && c.belief().age(c.now()) < cfg.flankStale && !(m.cover != null && atCover(m, c) && c.now() - m.coverSince < cfg.coverTtl * 2 && m.destination == null);
        }
        public double utility(AiContext c) { return 2.4 + ai.mind(c.id()).profile.aggression() * 0.5 - c.suppression() * 1.2 - (c.hurt() ? 1.5 : 0); }
        public void begin(AiContext c) {
            Mind m = ai.mind(c.id());
            m.cover = null;
            ai.world.releasePoints(m.id);
            CoverScorer.Choice ch = ai.pickCover(m, c, c.role(), -1);
            m.destination = ch != null ? ch.position() : ai.cover.flankPoint(ai.squadCentre(m), c.belief(), c.role());
            if (m.destination == null) { giveUp(m, c, "no way round"); return; }
            ai.speak(m, "flanking", c.role() == Role.FLANK_LEFT ? "left" : "right");
            m.trace.add(c.now(), "flank " + (c.role() == Role.FLANK_LEFT ? "left" : "right") + " via " + (ch != null ? "cover" : "open ground"));
        }
        private void giveUp(Mind m, AiContext c, String why) {
            m.destination = null;
            m.cooldown(name(), c.now() + 12);
            m.trace.add(c.now(), "flank abandoned: " + why);
            SquadCoordinator sq = ai.squad(m);
            if (sq != null) sq.dirty = true;
        }
        public void tick(AiContext c, double dt) {
            Mind m = ai.mind(c.id());
            if (m.destination == null) return;
            if (c.motionStatus() == ActionStatus.BLOCKED && c.blockedFor() > 1.0) {
                // the way is shut: try the open-ground point once, then give the flank up - never push into a wall
                m.replans++;
                Vec3 alt = ai.cover.flankPoint(ai.squadCentre(m), c.belief(), c.role());
                if (alt != null && alt.distanceXZ(m.destination) > 3 && m.replans % 2 == 1) { m.destination = alt; m.cover = null; ai.world.releasePoints(m.id); }
                else { giveUp(m, c, "path blocked"); return; }
            }
            double left = c.position().distanceXZ(m.destination);
            boolean creeping = left < 8 && !c.belief().visible();
            ai.move(m, m.destination, creeping ? threat(c) : null, creeping ? Stance.CROUCH : Stance.STAND, left > 14 ? Locomotion.SPRINT : creeping ? Locomotion.WALK : Locomotion.RUN, false);
            if (c.belief().visible() && c.distanceToBelief() < 12) shoot(m, c, false);     // seen at close range: no point in pretending
        }
        public boolean finished(AiContext c) {
            Mind m = ai.mind(c.id());
            if (m.destination == null) return true;
            if (c.position().distanceXZ(m.destination) < 1.1) { m.destination = null; m.coverSince = c.now(); m.coverUntil = c.now() + cfg.coverTtl * 2; return true; }
            if (c.belief().age(c.now()) >= cfg.flankStale) { giveUp(m, c, "target gone cold"); return true; }
            return false;
        }
        public void cancel(AiContext c) { ai.mind(c.id()).destination = null; }
        public double commitment() { return 2.5; }
    }

    /** Close the distance, one piece of cover at a time, while somebody else keeps the target's head down. */
    private final class Advance implements AiAction {
        public String name() { return "Advance"; }
        public boolean allowed(AiContext c) {
            Mind m = ai.mind(c.id());
            if (c.state() != CogState.COMBAT || c.role() != Role.ADVANCE || m.holdFire || !c.self().canWalk() || c.pinned() || c.hurt()) return false;       // holding fire is holding ground: nobody walks up to what they may not shoot
            if (c.distanceToBelief() < Math.max(6, c.wantRange() * 0.6) + 2) return false;
            if (concealed(m, c) && c.belief().visible() && !c.squadSuppressing()) return false;
            return m.cover == null || !atCover(m, c) || c.now() - m.coverSince > (c.squadSuppressing() || !c.belief().visible() ? 3.0 : 6.0);
        }
        public double utility(AiContext c) { return 1.9 + ai.mind(c.id()).profile.aggression() * 0.7 + (c.squadSuppressing() ? 0.7 : 0) + (c.belief().visible() ? -0.3 : 0.3) - c.suppression() * 1.2 - c.squadCaution() * 0.6; }
        public void begin(AiContext c) {
            Mind m = ai.mind(c.id());
            long old = m.cover == null ? -1 : m.cover.id();
            m.cover = null;
            ai.world.releasePoints(m.id);
            CoverScorer.Choice ch = ai.pickCover(m, c, Role.ADVANCE, old);
            if (ch != null) m.destination = ch.position();
            // 0.9: nothing to get behind further forward, and the target is looking this way with nobody keeping its head down:
            // that is not a moment to walk into the open. Stay where the cover is
            else if (c.belief().visible() && !c.squadSuppressing()) { m.destination = null; m.cooldown(name(), c.now() + 5); return; }
            else {
                // no cover ahead: a short bound toward the believed position, and stop
                Vec3 dir = threat(c).sub(c.position()).flat().normalized();
                m.destination = ai.world.standNear(c.position().add(dir.mul(Math.min(7, c.distanceToBelief() - c.wantRange() * 0.6))));
            }
            if (m.destination == null) { m.cooldown(name(), c.now() + 3); return; }
            if (ai.random(m) < 0.5) ai.speak(m, "moving", "");
        }
        public void tick(AiContext c, double dt) {
            Mind m = ai.mind(c.id());
            if (m.destination == null) return;
            ai.move(m, m.destination, threat(c), Stance.STAND, c.position().distanceXZ(m.destination) > 5 ? Locomotion.SPRINT : Locomotion.RUN, true);
            if (c.belief().visible() && m.profile.aggression() > 0.7) shoot(m, c, false);       // the bold shoot on the run
        }
        public boolean finished(AiContext c) {
            Mind m = ai.mind(c.id());
            if (m.destination == null) return true;
            boolean there = c.position().distanceXZ(m.destination) < 1.1, shut = c.motionStatus() == ActionStatus.BLOCKED && c.blockedFor() > 1.2;
            if (there || shut) { if (shut) { m.cover = null; ai.world.releasePoints(m.id); m.replans++; } m.destination = null; m.coverSince = c.now(); return true; }
            return false;
        }
        public void cancel(AiContext c) { ai.mind(c.id()).destination = null; }
        public double commitment() { return 2.0; }
        public double cooldown() { return 3.0; }
    }

    /** Keep the place the target was last known to be under fire, so the others can move. */
    private final class Suppress implements AiAction {
        public String name() { return "Suppress"; }
        public boolean allowed(AiContext c) {
            Mind m = ai.mind(c.id());
            if (c.state() != CogState.COMBAT || c.role() != Role.SUPPRESS || !c.self().canShoot() || c.empty()) return false;
            if (m.cover != null && !atCover(m, c)) return false;                      // get to the position first
            double d = c.distanceToBelief();
            return d > 6 && d < Math.min(75, fireRange(c)) && !m.holdFire && (c.belief().visible() || c.belief().age(c.now()) < cfg.suppressSeconds && c.belief().kind().ordinal() >= ConfidenceClass.APPROXIMATE.ordinal());
        }
        public double utility(AiContext c) { return 2.5 + (c.inCover() ? 0.4 : 0) - (c.pinned() ? 1.6 : 0); }
        public void begin(AiContext c) { Mind m = ai.mind(c.id()); if (!c.belief().visible()) ai.speak(m, "suppressing", ""); m.peekUntil = c.now() + cfg.peekSeconds * 1.5; m.hideUntil = -1e9; }
        public void tick(AiContext c, double dt) {
            Mind m = ai.mind(c.id());
            boolean out = !c.pinned() && c.now() >= m.hideUntil;
            if (out && c.now() > m.peekUntil) { m.hideUntil = c.now() + cfg.hideSeconds * (0.6 + m.suppression); m.peekUntil = m.hideUntil + cfg.peekSeconds * 1.5; out = false; }
            if (m.cover != null) ai.move(m, out ? m.cover.peekPosition() : m.cover.position(), threat(c), out || !m.cover.low() ? Stance.STAND : Stance.CROUCH, Locomotion.WALK, true);
            else hold(m, c, out ? Stance.STAND : Stance.CROUCH);
            ai.aim(m, threat(c).add(new Vec3(0, 1.15, 0)), c.belief().visible() ? 1 : 0.4);
            if (out) shoot(m, c, !c.belief().visible());
        }
        public boolean finished(AiContext c) { return false; }
        public void cancel(AiContext c) { }
        public double commitment() { return 1.6; }
    }

    /** A target that has gone to ground and stayed there gets a grenade from whoever carries them. */
    private final class Grenade implements AiAction {
        public String name() { return "Grenade"; }
        public boolean allowed(AiContext c) {
            Mind m = ai.mind(c.id());
            SquadCoordinator sq = ai.squad(m);
            if (c.state() != CogState.COMBAT || c.belief().visible() || c.belief().kind() != ConfidenceClass.LAST_SEEN) return false;
            if (c.role() != Role.SUPPRESS && c.role() != Role.ADVANCE && c.role() != Role.HOLD) return false;
            double unseen = c.belief().unseenFor(c.now()), d = c.distanceToBelief();
            if (unseen < 6 || unseen > 16 || c.belief().radiusMeters() > 12 || d < 9 || d > 26) return false;
            return sq != null && c.now() - sq.grenadeAt > 28 && (c.self().radio() || m.profile.aggression() > 0.7) && !c.mate();
        }
        public double utility(AiContext c) { return 2.7; }
        public void begin(AiContext c) {
            Mind m = ai.mind(c.id());
            SquadCoordinator sq = ai.squad(m);
            if (sq != null) sq.grenadeAt = c.now();
            if (ai.world.throwGrenade(m.id, threat(c))) { ai.speak(m, "grenade_out", ""); m.trace.add(c.now(), "grenade at believed position"); }
        }
        public void tick(AiContext c, double dt) { hold(ai.mind(c.id()), c, Stance.CROUCH); }
        public boolean finished(AiContext c) { return c.now() - ai.mind(c.id()).actionSince > 1.2; }
        public void cancel(AiContext c) { }
        public double commitment() { return 1.2; }
        public double cooldown() { return 20; }
    }

    /** Get behind something that stops rounds from where the threat is believed to be. */
    private final class TakeCover implements AiAction {
        public String name() { return "TakeCover"; }
        public boolean allowed(AiContext c) {
            Mind m = ai.mind(c.id());
            if (c.state() != CogState.COMBAT || !c.self().canWalk()) return false;
            if (m.cover == null && concealed(m, c)) return false;                    // already out of sight for want of cover: stay there
            return m.cover == null || !atCover(m, c);
        }
        // 0.9: getting behind something outranks standing in the open to shoot (it was 1.5: a man out of range and out of cover scored closing in above it)
        public double utility(AiContext c) { Mind m = ai.mind(c.id()); return 2.0 + (m.cover == null ? 0.6 : 0.9) + c.suppression() * 1.3 + (c.belief().visible() ? 0.5 : 0) + (c.now() - m.lastHitTime < 2 ? 1.0 : 0); }
        public void begin(AiContext c) {
            Mind m = ai.mind(c.id());
            if (m.cover == null) {
                // the nearest good cover, whatever the role: the man told to advance gets behind something first and bounds from there
                CoverScorer.Choice ch = ai.pickCover(m, c, c.role() == Role.ADVANCE ? Role.HOLD : c.role(), -1);
                if (ch == null) {
                    // 0.9: no cover point in reach. Then out of sight - round a corner, through a doorway - and stay down there
                    m.concealAt = c.belief().known() ? ai.hideSpot(m, c.position(), threat(c), 15) : null;
                    if (m.concealAt != null) { m.destination = m.concealAt; m.concealUntil = c.now() + 30; m.trace.add(c.now(), "no cover: going to ground out of sight"); return; }
                    // nowhere to get behind or out of sight: then at least not in a huddle - a step away from whoever is at the elbow
                    m.destination = ai.spreadSpot(m, c.position()); if (m.destination != null) m.trace.add(c.now(), "no cover: spreading out"); else m.cooldown(name(), c.now() + 2.5); return;
                }
                m.concealAt = null;
            }
            m.trace.add(c.now(), "to cover as " + c.role());
        }
        public void tick(AiContext c, double dt) {
            Mind m = ai.mind(c.id());
            if (m.cover == null) { if (m.destination != null) { boolean hide = m.concealAt != null; ai.move(m, m.destination, hide ? null : threat(c), Stance.STAND, hide ? Locomotion.SPRINT : Locomotion.RUN, !hide); if (c.belief().visible() && !hide) shoot(m, c, false); } return; }
            double run = c.position().distanceXZ(m.cover.position());
            ai.move(m, m.cover.position(), threat(c), Stance.STAND, run > 6 || c.suppression() > 0.5 ? Locomotion.SPRINT : Locomotion.RUN, true);
            if (c.belief().visible() && run < 4) shoot(m, c, false);
        }
        public boolean finished(AiContext c) {
            Mind m = ai.mind(c.id());
            if (m.cover == null) {
                if (m.destination == null) return true;
                boolean there = c.position().distanceXZ(m.destination) < 0.9;
                if (there || c.motionStatus() == ActionStatus.BLOCKED && c.blockedFor() > 1 || c.now() - m.actionSince > (m.concealAt != null ? 7 : 4)) {
                    // out of sight: stay there a while (StandAndFight holds it); cover is looked for again when that runs out
                    if (m.concealAt != null) { if (there) m.concealUntil = c.now() + 6 + ai.random(m) * 5; else m.concealAt = null; }
                    m.destination = null; m.cooldown(name(), c.now() + 2.5); return true;
                }
                return false;
            }
            if (atCover(m, c)) { m.coverSince = c.now(); m.coverUntil = c.now() + cfg.coverTtl * (1 + ai.random(m)); m.hideUntil = c.now() + 0.4; return true; }
            if (c.motionStatus() == ActionStatus.BLOCKED && c.blockedFor() > 1.2) { m.cover = null; ai.world.releasePoints(m.id); m.replans++; m.cooldown(name(), c.now() + 1.5); return true; }
            return false;
        }
        public void cancel(AiContext c) { }
        public double commitment() { return 1.6; }
    }

    /** Behind cover: up or out to shoot, back in to wait, reload and not be shot. */
    private final class FightFromCover implements AiAction {
        public String name() { return "FightFromCover"; }
        public boolean allowed(AiContext c) { Mind m = ai.mind(c.id()); return c.state() == CogState.COMBAT && m.cover != null && atCover(m, c); }
        public double utility(AiContext c) { return 2.0 + (c.belief().visible() ? 0.5 : 0) + (c.role() == Role.HOLD ? 0.3 : 0) - (c.protection() < 0.34 && c.belief().visible() ? 1.2 : 0) - (outOfRange(c) ? 1.6 : 0); }
        public void begin(AiContext c) { }
        public void tick(AiContext c, double dt) {
            Mind m = ai.mind(c.id());
            double now = c.now();
            // the cover can be taken away between two decisions (the squad dealt the roles again): hold where the feet are until the next one
            if (m.cover == null) { hold(m, c, Stance.CROUCH); return; }
            boolean out = !c.pinned() && now >= m.hideUntil && c.self().canShoot() && !c.empty();
            if (out && m.peekUntil < m.hideUntil) m.peekUntil = now + cfg.peekSeconds * (0.7 + ai.random(m) * 0.6);
            if (out && now > m.peekUntil) { m.hideUntil = now + cfg.hideSeconds * (0.7 + ai.random(m) * 0.6 + m.suppression * 1.4); out = false; }
            // a flanker that has arrived and has not been seen waits for the shot instead of advertising itself
            if (c.role().flank() && !c.belief().visible()) out = false;
            ai.move(m, out ? m.cover.peekPosition() : m.cover.position(), threat(c), out || !m.cover.low() ? Stance.STAND : Stance.CROUCH, Locomotion.WALK, true);
            ai.aim(m, threat(c).add(new Vec3(0, 1.15, 0)), c.belief().visible() ? 1 : 0.5);
            if (out && c.belief().visible()) shoot(m, c, false);
        }
        public boolean finished(AiContext c) { return false; }
        public void cancel(AiContext c) { }
        public double commitment() { return 1.2; }
    }

    /** No cover to be had: do the job from where the feet are rather than freeze - hold and shoot, close in, or keep the range. */
    private final class StandAndFight implements AiAction {
        public String name() { return "StandAndFight"; }
        public boolean allowed(AiContext c) { return c.state() == CogState.COMBAT; }
        public double utility(AiContext c) { return (ai.mind(c.id()).cover == null ? 1.0 : 0.3) + (outOfRange(c) ? 1.2 : 0); }
        public void begin(AiContext c) { }
        public void tick(AiContext c, double dt) {
            Mind m = ai.mind(c.id());
            double d = c.distanceToBelief(), want = Math.min(c.wantRange(), fireRange(c) * 0.75);
            boolean vis = c.belief().visible();
            if (!c.self().canWalk()) { hold(m, c, Stance.CROUCH); if (vis) shoot(m, c, false); return; }
            // 0.9: gone to ground out of sight - stay down and wait for the shot, do not walk back out to look for it
            if (concealed(m, c)) { hold(m, c, Stance.CROUCH); ai.aim(m, threat(c).add(new Vec3(0, 1.15, 0)), vis ? 0.8 : 0.4); if (vis) shoot(m, c, false); return; }
            if (m.concealAt != null && c.now() >= m.concealUntil) m.concealAt = null;
            if (m.holdFire || (c.role() == Role.HOLD || c.role() == Role.SUPPRESS) && !outOfRange(c)) {
                hold(m, c, vis ? Stance.STAND : Stance.CROUCH);
            } else if (!vis && c.belief().known()) {
                // nothing to shoot at: close on where they were, carefully - and stop short of it
                if (d > Math.max(5, c.belief().radiusMeters())) ai.move(m, threat(c), threat(c), d < 12 ? Stance.CROUCH : Stance.STAND, d < 12 ? Locomotion.WALK : Locomotion.RUN, true); else hold(m, c, Stance.CROUCH);
            } else if (d > want * 1.3) ai.move(m, threat(c), threat(c), Stance.STAND, Locomotion.RUN, true);
            else if (d < want * 0.45) { Vec3 back = c.position().add(c.position().sub(threat(c)).flat().normalized().mul(3)); ai.move(m, back, threat(c), Stance.STAND, Locomotion.WALK, true); }
            else {
                // sidestep while shooting: a standing target is a dead one. (0.9: only while rounds are actually coming this way -
                // pacing two steps left and two steps right for the whole of a fight read as a man walking in circles. Otherwise
                // it kneels and shoots: smaller, steadier, and still.)
                boolean underFire = c.sinceHit() < 2.5 || c.suppression() > 0.25;
                Vec3 side = threat(c).sub(c.position()).flat().normalized().left().mul(((int) (c.now() * 0.5 + m.phase * 8) & 1) == 0 ? 2.2 : -2.2);
                Vec3 p = c.position().add(side);
                if (underFire && ai.world.walkable(c.position(), p)) ai.move(m, p, threat(c), Stance.STAND, Locomotion.WALK, true); else hold(m, c, d > 12 ? Stance.CROUCH : Stance.STAND);
            }
            ai.aim(m, threat(c).add(new Vec3(0, 1.15, 0)), vis ? 0.8 : 0.4);
            if (vis) shoot(m, c, false);
        }
        public boolean finished(AiContext c) { return false; }
        public void cancel(AiContext c) { }
        public double commitment() { return 0.9; }
    }

    // ================================================================== withdraw
    /** Break contact: back toward home, or just away, turning to cover the move while the threat can still be seen. */
    private final class Retreat implements AiAction {
        public String name() { return "Retreat"; }
        public boolean allowed(AiContext c) { return c.state() == CogState.WITHDRAW; }
        public double utility(AiContext c) { return 1; }
        public void begin(AiContext c) {
            Mind m = ai.mind(c.id());
            m.cover = null;
            ai.world.releasePoints(m.id);
            ai.speak(m, "retreating", "");
            pickRefuge(m, c);
        }
        /**
         * 0.9: where a broken man goes. Home, if home is near enough to matter and not toward the trouble; otherwise the nearest
         * place the threat cannot see; only with neither, simply away. (It used to be a point 24 m off in a straight line, reached,
         * forgotten, and then run from again the moment the threat came back into view - which looked like running in circles.)
         */
        private void pickRefuge(Mind m, AiContext c) {
            Vec3 home = c.self().home();
            Vec3 away = c.belief().known() ? c.position().sub(threat(c)).flat().normalized() : c.self().forward().mul(-1);
            boolean homeward = home != null && (!c.belief().known() || home.sub(c.position()).flat().normalized().dot(away) > -0.2) && home.distanceXZ(c.position()) > 6 && home.distanceXZ(c.position()) < 60;
            Vec3 hide = c.belief().known() ? ai.hideSpot(m, c.position(), threat(c), 18) : null;
            m.destination = hide != null && (!homeward || c.belief().visible()) ? hide : homeward ? home : ai.world.standNear(c.position().add(away.mul(24)));
            if (m.destination == null) m.destination = c.position().add(away.mul(12));
            m.cowering = false; m.hidAt = c.now();
            m.trace.add(c.now(), hide != null && m.destination == hide ? "breaking contact: to a place out of sight" : homeward ? "breaking contact: home" : "breaking contact: away");
        }
        public void tick(AiContext c, double dt) {
            Mind m = ai.mind(c.id());
            if (m.destination == null) { hold(m, c, Stance.CROUCH); return; }
            boolean there = c.position().distanceXZ(m.destination) < 1.6 || c.motionStatus() == ActionStatus.BLOCKED && c.blockedFor() > 1.5;
            if (there && !m.cowering) { m.cowering = true; m.hidAt = c.now(); m.trace.add(c.now(), "hiding"); }
            if (m.cowering) {
                // down, small, watching the way the trouble would come from. Found there - the threat in plain view and close -
                // it is somewhere else to hide, or, with nowhere left and a weapon in the hands, a cornered man shoots
                hold(m, c, Stance.CROUCH);
                boolean found = c.belief().visible() && c.distanceToBelief() < 16 && c.now() - m.hidAt > 1.5;
                if (found && c.self().canWalk()) { Vec3 was = m.destination; pickRefuge(m, c); if (m.destination.distanceXZ(was) < 3) { m.destination = was; m.cowering = true; if (c.self().canShoot()) { ai.aim(m, threat(c).add(new Vec3(0, 1.15, 0)), 0.5); shoot(m, c, false); } } }
                return;
            }
            boolean coverTurn = c.belief().visible() && ((int) ((c.now() - m.actionSince) / 1.6) & 1) == 1 && c.self().canShoot() && m.profile.caution() < 0.75;
            if (coverTurn) { hold(m, c, Stance.STAND); ai.aim(m, threat(c).add(new Vec3(0, 1.15, 0)), 0.5); shoot(m, c, false); }
            else ai.move(m, m.destination, null, Stance.STAND, Locomotion.SPRINT, false);
        }
        // over when it has stayed hidden long enough for the trouble to have gone: nothing seen for a quarter of a minute
        public boolean finished(AiContext c) { Mind m = ai.mind(c.id()); return m.destination == null || m.cowering && (!c.belief().known() || c.sinceSeen() > 15 && c.now() - m.hidAt > 6); }
        public void cancel(AiContext c) { ai.mind(c.id()).cowering = false; }
        public double commitment() { return 3; }
    }

    // ================================================================== search, investigate, suspicion
    /**
     * The hunt for a target that has gone out of sight:
     * REACT (stop, face where they went) -> APPROACH_LAST_KNOWN -> INSPECT (look properly) -> EXPAND (the next most
     * likely place nobody else is covering) -> SWEEP (the trail is cold: widen it) -> DEESCALATE. Seeing the target
     * again at any point is REACQUIRE, and that is the engine's state change, not this action's.
     */
    /**
     * 1.3: the WATCH role: while the others go through the building, this one goes to its way out and holds it - crouched
     * just outside the entrance, facing in, weapon on the door - and says so. Anyone breaking out meets it there.
     */
    private final class WatchExit implements AiAction {
        public String name() { return "WatchExit"; }
        public boolean allowed(AiContext c) {
            if (c.state() != CogState.SEARCH || c.role() != Role.WATCH || !c.belief().known()) return false;
            return ai.world.exitOf(c.belief().estimatedPosition()) != null;
        }
        public double utility(AiContext c) { return 2.2; }
        public void begin(AiContext c) { Mind m = ai.mind(c.id()); m.cover = null; ai.world.releasePoints(m.id); m.trace.add(c.now(), "watch the way out"); }
        public void tick(AiContext c, double dt) {
            Mind m = ai.mind(c.id());
            Vec3 exit = ai.world.exitOf(c.belief().estimatedPosition());
            if (exit == null) return;
            Vec3 inside = c.belief().estimatedPosition();
            if (c.position().distanceXZ(exit) > 1.2) ai.move(m, exit, inside, Stance.STAND, c.position().distanceXZ(exit) > 8 ? Locomotion.RUN : Locomotion.WALK, true);
            else {
                ai.stop(m, inside, Stance.CROUCH);
                ai.aim(m, exit.add(inside.sub(exit).flat().normalized().mul(2)).add(new Vec3(0, 1.2, 0)), 0.8);
                if (!m.onCooldown("watch_said", c.now())) { ai.speak(m, "hold_door", ""); m.cooldown("watch_said", c.now() + 30); watches++; }
            }
        }
        public boolean finished(AiContext c) { return false; }
        public void cancel(AiContext c) { }
        public double commitment() { return 0.4; }
    }
    /** Times somebody took up a watch on a way out (gates). */
    public static int watches;

    private final class SearchRegions implements AiAction {
        public String name() { return "Search"; }
        public boolean allowed(AiContext c) { return c.state() == CogState.SEARCH; }
        public double utility(AiContext c) { return 1; }
        public void begin(AiContext c) {
            Mind m = ai.mind(c.id());
            m.searchPhase = Mind.SearchPhase.REACT;
            m.searchSince = c.now();
            if (m.searchStarted < -1e8 || c.now() - m.searchStarted > cfg.searchPatienceSeconds * 2) { m.searchStarted = c.now(); m.regionsInspected = 0; }
            m.searchRegion = -1;
            m.cover = null;
            ai.world.releasePoints(m.id);
        }
        public void tick(AiContext c, double dt) {
            Mind m = ai.mind(c.id());
            SquadCoordinator sq = ai.squad(m);
            SearchGrid grid = sq == null ? null : sq.search;
            double now = c.now();
            TargetBelief b = c.belief();
            switch (m.searchPhase) {
                case REACT -> {
                    hold(m, c, Stance.CROUCH);
                    if (now - m.searchSince > 0.5 + m.profile.reactionLatency() * 1.5) {
                        // the nearest to where the target was lost goes there; the others go straight to the places round it
                        boolean lead = c.role() != Role.SEARCH || ai.closestInSquad(m, b.estimatedPosition());
                        next(m, lead ? Mind.SearchPhase.APPROACH_LAST_KNOWN : Mind.SearchPhase.EXPAND, now);
                    }
                }
                case APPROACH_LAST_KNOWN -> {
                    Vec3 last = b.lastSeenTime() > -1e8 ? b.lastSeenPosition() : b.estimatedPosition();
                    double d = c.position().distanceXZ(last);
                    // 0.9: they went into a building - said once, and the squad takes it room by room
                    if (grid != null && grid.house != null && grid.rooms > 0 && !grid.houseCalled && grid.house.contains(last)) { grid.houseCalled = true; ai.speak(m, "search_house", grid.house.name()); m.trace.add(now, "search: the building (" + grid.rooms + " rooms)"); }
                    if (at(c.position(), last, 2.4) || c.motionStatus() == ActionStatus.BLOCKED && c.blockedFor() > 1.5 || now - m.searchSince > 16) { next(m, Mind.SearchPhase.INSPECT, now); break; }
                    boolean near = d < 8;
                    ai.move(m, last, last, near ? Stance.CROUCH : Stance.STAND, near ? Locomotion.WALK : Locomotion.RUN, true);
                }
                case INSPECT -> {
                    // look properly: a slow sweep of the weapon round the place before calling it empty
                    double t = now - m.searchSince, sweep = Math.sin(t * 2.4 + m.phase * 6) * 1.3;
                    Vec3 f = c.self().forward().flat().normalized();
                    Vec3 look = c.position().add(new Vec3(f.x() * Math.cos(sweep * dt * 3) - f.z() * Math.sin(sweep * dt * 3), 0, f.x() * Math.sin(sweep * dt * 3) + f.z() * Math.cos(sweep * dt * 3)).mul(6));
                    ai.stop(m, look, Stance.CROUCH);
                    if (grid != null) grid.observe(m.id, c.position().add(new Vec3(0, 1.2, 0)), c.self().forward(), 14, now);
                    if (t > cfg.inspectSeconds) {
                        if (grid != null && m.searchRegion >= 0) grid.markVisibleEmpty(m.id, m.searchRegion, now);
                        // standing where the belief said and nobody is here: the belief is wrong about the place
                        if (b.known() && c.position().distanceXZ(b.estimatedPosition()) < Math.max(3, b.radiusMeters() * 0.5)) { m.beliefs.contradicted(b.targetId(), now); m.lastContradicted = now; }
                        m.regionsInspected++;
                        if (ai.random(m) < 0.3) ai.speak(m, "clear", "");
                        // a house is not left half searched: while it has rooms nobody has looked into, the hunt stays on it
                        boolean houseLeft = grid != null && grid.house != null && grid.roomsCleared < grid.rooms;
                        next(m, m.regionsInspected >= 3 && !houseLeft ? Mind.SearchPhase.SWEEP : Mind.SearchPhase.EXPAND, now);
                    }
                }
                case EXPAND, SWEEP -> {
                    if (grid == null || !grid.built()) { next(m, Mind.SearchPhase.DEESCALATE, now); break; }
                    if (m.searchRegion < 0) {
                        long r = grid.bestRegionFor(m.id, b, now);
                        if (r < 0 || !grid.reserve(r, m.id, now + cfg.reservationTtl)) { if (now - m.searchSince > 3) next(m, Mind.SearchPhase.DEESCALATE, now); else hold(m, c, Stance.CROUCH); break; }
                        m.searchRegion = r;
                        m.searchSince = now;
                        m.trace.add(now, "search region " + r + (m.searchPhase == Mind.SearchPhase.SWEEP ? " (sweep)" : ""));
                    }
                    SearchGrid.Cell cell = grid.cell(m.searchRegion);
                    if (cell == null) { m.searchRegion = -1; break; }
                    double d = c.position().distanceXZ(cell.centre);
                    if (c.motionStatus() == ActionStatus.BLOCKED && c.blockedFor() > 1.5 || now - m.searchSince > cfg.reservationTtl) {
                        // cannot get there: let it go so someone else may, and do not ask for it again for a while
                        grid.releaseActor(m.id); cell.lastInspected = now - 5; m.searchRegion = -1; m.replans++; m.searchSince = now; break;
                    }
                    if (at(c.position(), cell.centre, 2.6)) { m.searchSince = now; m.searchPhase = Mind.SearchPhase.INSPECT; break; }
                    boolean near = d < 7;
                    ai.move(m, cell.centre, cell.centre, near ? Stance.CROUCH : Stance.STAND, near ? Locomotion.WALK : Locomotion.RUN, true);
                    grid.observe(m.id, c.position().add(new Vec3(0, 1.5, 0)), c.self().forward(), 10, now);
                }
                default -> hold(m, c, Stance.STAND);
            }
        }
        private void next(Mind m, Mind.SearchPhase p, double now) { m.searchPhase = p; m.searchSince = now; if (p != Mind.SearchPhase.INSPECT) m.searchRegion = -1; m.trace.add(now, "search " + p); }
        public boolean finished(AiContext c) {
            Mind m = ai.mind(c.id());
            // a building takes longer than a street: patience is stretched by the rooms still to look into
            SquadCoordinator sq = ai.squad(m);
            double extra = sq != null && sq.search != null && sq.search.house != null ? Math.min(60, (sq.search.rooms - sq.search.roomsCleared) * 6.0) : 0;
            return m.searchPhase == Mind.SearchPhase.DEESCALATE || c.now() - m.searchStarted > cfg.searchPatienceSeconds * m.profile.searchPatience() + extra;
        }
        public void cancel(AiContext c) { Mind m = ai.mind(c.id()); SquadCoordinator sq = ai.squad(m); if (sq != null && sq.search != null) sq.search.releaseActor(m.id); m.searchRegion = -1; }
        public double commitment() { return 2; }
    }

    /** Something made a noise, or something is not right over there: go and see - a place and a look, not a chase. */
    private final class InvestigatePoint implements AiAction {
        public String name() { return "Investigate"; }
        public boolean allowed(AiContext c) { return c.state() == CogState.INVESTIGATE && c.belief().known(); }
        public double utility(AiContext c) { return 1; }
        public void begin(AiContext c) { Mind m = ai.mind(c.id()); m.destination = threat(c); m.searchSince = -1; }
        public void tick(AiContext c, double dt) {
            Mind m = ai.mind(c.id());
            if (m.destination == null) m.destination = threat(c);
            double d = c.position().distanceXZ(m.destination) + Math.max(0, Math.abs(c.position().y() - m.destination.y()) - 1.8) * 4, stop = Math.max(2.4, Math.min(8, c.belief().radiusMeters() * 0.5));
            // close enough is close enough only with eyes on the place: a wall between is not having looked
            if (d <= stop && d > 2.4 && !ai.world.rayClear(c.position().add(new Vec3(0, 1.5, 0)), m.destination.add(new Vec3(0, 1.2, 0)))) stop = 2.4;
            if (m.searchSince < 0 && d > stop && !(c.motionStatus() == ActionStatus.BLOCKED && c.blockedFor() > 1.5)) {
                boolean near = d < 10;
                ai.move(m, m.destination, m.destination, near && m.profile.caution() > 0.3 ? Stance.CROUCH : Stance.STAND, c.alert() > cfg.threatSoundAlert && c.self().radio() && !near ? Locomotion.RUN : Locomotion.WALK, true);
                return;
            }
            if (m.searchSince < 0) m.searchSince = c.now();
            double t = c.now() - m.searchSince;
            Vec3 f = new Vec3(Math.sin(t * 1.3 + m.phase * 6), 0, Math.cos(t * 1.3 + m.phase * 6));
            ai.stop(m, c.position().add(f.mul(6)), Stance.STAND);
        }
        public boolean finished(AiContext c) { Mind m = ai.mind(c.id()); return m.searchSince > 0 && c.now() - m.searchSince > 4.5 + m.profile.searchPatience() * 3; }
        public void cancel(AiContext c) { ai.mind(c.id()).destination = null; }
        public double commitment() { return 1.5; }
    }

    /** A shape at the edge of sight, a sound that might be nothing: stop and look at it. */
    private final class LookAt implements AiAction {
        public String name() { return "LookAt"; }
        public boolean allowed(AiContext c) { return c.state() == CogState.SUSPICIOUS; }
        public double utility(AiContext c) { return 1; }
        public void begin(AiContext c) { }
        public void tick(AiContext c, double dt) { Mind m = ai.mind(c.id()); ai.stop(m, c.belief().known() ? threat(c) : null, Stance.STAND); if (c.belief().known()) ai.aim(m, threat(c).add(new Vec3(0, 1.2, 0)), 0.3); }
        public boolean finished(AiContext c) { return c.now() - ai.mind(c.id()).actionSince > 1.4 + ai.mind(c.id()).profile.reactionLatency() * 3; }
        public void cancel(AiContext c) { }
        public double commitment() { return 0.8; }
    }

    // ================================================================== peace (0.8)
    /**
     * The side's own way of dealing with a stranger has this one's hands: a hold-up, a warning, a loner backing off,
     * a body being stripped. The mind chooses it - nothing outside the engine takes a body away from it - and it
     * outranks everything but getting clear of a grenade.
     */
    private final class Conduct implements AiAction {
        public String name() { return "Conduct"; }
        public boolean emergency() { return true; }
        public boolean allowed(AiContext c) { return !c.mate() && ai.world.conductHolds(c.id(), c.state() == CogState.PATROL); }
        public double utility(AiContext c) { return 9; }
        public void begin(AiContext c) { Mind m = ai.mind(c.id()); m.cover = null; ai.world.releasePoints(m.id); }
        public void tick(AiContext c, double dt) { ai.world.conduct(c.id(), dt); }
        public boolean finished(AiContext c) { return false; }
        public void cancel(AiContext c) { }
        public double commitment() { return 0.2; }
    }

    /** Nothing is wrong: the post, the patrol, the bunk, the day's work. */
    private final class Routine implements AiAction {
        public String name() { return "Routine"; }
        public boolean allowed(AiContext c) { return c.state() == CogState.PATROL && !c.mate(); }
        public double utility(AiContext c) { return 1; }
        public void begin(AiContext c) { }
        public void tick(AiContext c, double dt) { ai.world.routine(c.id(), dt); }
        public boolean finished(AiContext c) { return false; }
        public void cancel(AiContext c) { }
        public double commitment() { return 0.2; }
    }

    // ================================================================== the player's squadmates, between fights
    /** Where a follower belongs: behind the leader, in two files, never ahead and never beside. */
    public Vec3 followSpot(AiContext c, int index) {
        Vec3 f = c.leaderForward().flat().normalized();
        if (f.length() < 0.1) f = new Vec3(0, 0, -1);
        double back = cfg.followBehind + (index / 2) * cfg.followSpacing, side = (index % 2 == 0 ? -1 : 1) * (0.9 + (index / 2) * 0.25);
        return c.leader().add(f.mul(-back)).add(f.left().mul(-side));
    }

    /** Follow: behind the leader, at the leader's pace - sprinting when the leader does or when left behind. */
    private final class FollowLeader implements AiAction {
        public String name() { return "Follow"; }
        public boolean allowed(AiContext c) { return c.mate() && c.leader() != null; }
        public double utility(AiContext c) { return "follow".equals(c.order()) || c.order().isEmpty() ? 2 : 0.2; }
        public void begin(AiContext c) { }
        public void tick(AiContext c, double dt) {
            Mind m = ai.mind(c.id());
            Vec3 spot = followSpot(c, ai.mateIndex(m));
            Vec3 s = ai.world.standNear(spot);
            if (s != null && Math.abs(s.y() - c.leader().y()) < 2.5) spot = s; else spot = c.leader().add(c.leaderForward().flat().normalized().mul(-1.4));
            double d = c.position().distanceXZ(spot);
            if (d < 1.1) { ai.stop(m, c.leader().add(c.leaderForward().mul(8)), c.leaderCrouched() ? Stance.CROUCH : Stance.STAND); return; }
            Locomotion gait = d > cfg.sprintToCatchUp || c.leaderSprinting() && d > 2.5 ? Locomotion.SPRINT : d > 3.5 ? Locomotion.RUN : Locomotion.WALK;
            ai.move(m, spot, null, c.leaderCrouched() && d < 5 ? Stance.CROUCH : Stance.STAND, gait, false);
        }
        public boolean finished(AiContext c) { return false; }
        public void cancel(AiContext c) { }
        public double commitment() { return 0.4; }
    }

    private final class HoldOrder implements AiAction {
        public String name() { return "Hold"; }
        public boolean allowed(AiContext c) { return c.mate() && "hold".equals(c.order()) && c.orderPoint() != null; }
        public double utility(AiContext c) { return 3; }
        public void begin(AiContext c) { }
        public void tick(AiContext c, double dt) {
            Mind m = ai.mind(c.id());
            if (c.position().distanceXZ(c.orderPoint()) > 1.4) { ai.move(m, c.orderPoint(), null, Stance.STAND, Locomotion.RUN, false); return; }
            double t = c.now() * 0.3 + m.phase * 6;
            ai.stop(m, c.position().add(new Vec3(Math.sin(t), 0, Math.cos(t)).mul(8)), Stance.CROUCH);
        }
        public boolean finished(AiContext c) { return false; }
        public void cancel(AiContext c) { }
    }

    private final class MoveOrder implements AiAction {
        public String name() { return "MoveTo"; }
        public boolean allowed(AiContext c) { return c.mate() && ("move".equals(c.order()) || "sweep".equals(c.order())) && c.orderPoint() != null; }
        public double utility(AiContext c) { return 3; }
        public void begin(AiContext c) { }
        public void tick(AiContext c, double dt) {
            Mind m = ai.mind(c.id());
            boolean sweep = "sweep".equals(c.order());
            double d = c.position().distanceXZ(c.orderPoint());
            if (d < 1.8) { ai.orderDone(m, sweep ? "clear" : "in_position"); ai.stop(m, null, Stance.CROUCH); return; }
            ai.move(m, c.orderPoint(), null, sweep && d < 10 ? Stance.CROUCH : Stance.STAND, sweep ? Locomotion.WALK : d > 12 ? Locomotion.SPRINT : Locomotion.RUN, sweep);
        }
        public boolean finished(AiContext c) { return false; }
        public void cancel(AiContext c) { }
    }
}
