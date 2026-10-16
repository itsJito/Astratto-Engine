package astratto.ai.runtime;

import astratto.ai.api.GameWorld;
import astratto.ai.api.GameWorld.SelfState;
import astratto.ai.api.GameWorld.SoundEvent;
import astratto.ai.api.GameWorld.WeaponCommand;
import astratto.ai.api.SydneyBridge;
import astratto.ai.api.SydneyBridge.AimIntent;
import astratto.ai.api.SydneyBridge.Locomotion;
import astratto.ai.api.SydneyBridge.MotionIntent;
import astratto.ai.api.SydneyBridge.Stance;
import astratto.ai.api.Vec3;
import astratto.ai.cognition.ConfidenceClass;
import astratto.ai.cognition.TargetBelief;
import astratto.ai.decision.Actions;
import astratto.ai.decision.AiAction;
import astratto.ai.decision.AiContext;
import astratto.ai.decision.CogState;
import astratto.ai.decision.CreatureActions;
import astratto.ai.decision.Selector;
import astratto.ai.group.ReportBus;
import astratto.ai.group.Role;
import astratto.ai.group.SquadCoordinator;
import astratto.ai.perception.Percept;
import astratto.ai.perception.Perception;
import astratto.ai.perception.VisionMeter;
import astratto.ai.spatial.CoverScorer;
import astratto.ai.spatial.SearchGrid;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The NeuralAI engine: what every NPC - a hostile, a neutral, one of the player's own, or one of the infected - knows,
 * decides and attempts (0.8: there is no other AI in the game). It owns none of the world. The host owns entities, physics, weapons, damage, sound and navigation, and is
 * reached only through {@link GameWorld}; the body is moved only through {@link SydneyBridge}.
 *
 * Each agent is run through the same pipeline, on fixed steps that do not depend on the frame rate:
 *
 *   senses (15 Hz)  ->  beliefs (10 Hz)  ->  reports delivered  ->  squad roles (2 Hz)  ->  decisions (5 Hz)
 *
 * and its current action is ticked every frame, which is where motion intents and weapon commands are issued. An
 * emergency - a grenade landing, a blow coming in, a path that turns out to be shut - asks for a decision at once
 * rather than waiting for the next step.
 *
 * The invariant the whole engine is built round: every action an NPC takes can be explained by what it has
 * observed, what it still believes, what it has been told, the role it was dealt, or what its body has just
 * reported. Nothing in the decision or cognition packages can ask where a target is.
 */
public final class NeuralAi implements SydneyBridge.Listener {
    public final GameWorld world;
    public final SydneyBridge sydney;
    public final NeuralConfig cfg;
    public final Perception perception;
    public final CoverScorer cover;
    public final ReportBus bus = new ReportBus();
    private final Actions actions;
    private final CreatureActions creatures;
    private final Selector selector = new Selector();
    /** Ordered by id: iteration order is part of the determinism the replay gate checks. */
    private final Map<Long, Mind> minds = new TreeMap<>();
    private final Map<Long, SquadCoordinator> squads = new TreeMap<>();
    private final FixedStepper groupStep, searchStep;
    private long seed, actionIds = 1;
    /** The player's element: who they follow, in what order they fall in. */
    public long leaderId = -1;
    /** Difficulty, as the only thing it is allowed to be: how long recognition takes (1 = as tuned; lower is quicker). It buys no knowledge. */
    public double reactionScale = 1;
    private final List<Long> mateOrder = new ArrayList<>();
    /** Counters for the gates and the overlay. */
    public long decisions, actionChanges, interrupts, weaponCommands, speculativeCommands, motionIntents;

    public NeuralAi(GameWorld world, SydneyBridge sydney, NeuralConfig cfg, long seed) {
        this.world = world; this.sydney = sydney; this.cfg = cfg; this.seed = seed;
        this.perception = new Perception(world, cfg);
        this.cover = new CoverScorer(world, cfg);
        this.actions = new Actions(this);
        this.creatures = new CreatureActions(this);
        this.groupStep = new FixedStepper(cfg.rolesHz);
        this.searchStep = new FixedStepper(cfg.searchHz);
        sydney.addListener(this);
    }

    // ------------------------------------------------------------------ agents and squads
    public Mind enroll(long id, long squadId, String faction, String archetype, boolean mate) {
        Mind m = minds.get(id);
        if (m != null) return m;
        m = new Mind(id, squadId, faction, archetype, cfg);
        m.isMate = mate;
        GameWorld.Nature nature = world.nature(id);
        m.creature = nature.creature();
        m.blind = nature.blind();
        // a mind that starts now has still heard the last second: a shot does not go unheard because the listener was new
        m.soundCursor = world.soundSequence();
        double now = world.simulationTimeSeconds();
        for (SoundEvent s : world.soundEventsSince(0)) if (now - s.time() < 1.0) { m.soundCursor = s.sequence() - 1; break; }
        minds.put(id, m);
        if (squadId != 0) squads.computeIfAbsent(squadId, k -> new SquadCoordinator(squadId, faction, cfg)).members.add(m);
        if (mate) { mateOrder.add(id); m.order = "follow"; }
        return m;
    }

    public Mind mind(long id) { return minds.get(id); }
    public boolean knows(long id) { return minds.containsKey(id); }
    public List<Mind> minds() { return new ArrayList<>(minds.values()); }
    public SquadCoordinator squad(Mind m) { return m.squad == 0 ? null : squads.get(m.squad); }
    public SquadCoordinator squad(long id) { return squads.get(id); }
    public List<SquadCoordinator> squads() { return new ArrayList<>(squads.values()); }
    public long nextActionId() { return ++actionIds; }
    public int mateIndex(Mind m) { int i = 0; for (long id : mateOrder) { if (id == m.id) return i; Mind o = minds.get(id); if (o != null && ("follow".equals(o.order) || o.order.isEmpty())) i++; } return 0; }

    /** An agent has died, turned, or left the simulation: everything it held is given back. */
    public void remove(long id, boolean died) {
        Mind m = minds.remove(id);
        if (m == null) return;
        mateOrder.remove(id);
        world.releasePoints(id);
        SquadCoordinator sq = squad(m);
        if (sq != null) {
            sq.members.remove(m);
            if (sq.search != null) sq.search.releaseActor(id);
            if (died) { sq.casualties++; sq.lastCasualty = world.simulationTimeSeconds(); sq.dirty = true; }
            if (sq.members.isEmpty()) squads.remove(sq.id);
        }
        if (m.action != null && m.meleeAction != 0) sydney.cancelAction(id, m.meleeAction);
    }

    /** Deterministic noise in [0, 1) for one mind: the same encounter from the same seed rolls the same dice. */
    public double random(Mind m) {
        seed = seed * 6364136223846793005L + 1442695040888963407L + m.id;
        return ((seed >>> 11) & 0xFFFFFFFFFFFFFL) / (double) (1L << 52);
    }

    // ------------------------------------------------------------------ the frame
    /** Once a frame, before the agents: messages whose time has come, roles that have run out, searches that have aged. */
    public void frame(double dt) {
        double now = world.simulationTimeSeconds();
        bus.deliverDue(now, sq -> { List<Long> l = new ArrayList<>(); SquadCoordinator s = squads.get(sq); if (s != null) for (Mind m : s.members) l.add(m.id); return l; }, this::reaches, this::deliver);
        groupStep.update(dt, () -> { for (SquadCoordinator sq : squads.values()) if (sq.due(now) && fighting(sq)) sq.assign(world::self, now); });
        searchStep.update(dt, () -> { for (SquadCoordinator sq : squads.values()) if (sq.search != null && sq.search.built() && searching(sq)) sq.search.refresh(now); });
    }

    private boolean fighting(SquadCoordinator sq) { for (Mind m : sq.members) if (m.state == CogState.COMBAT || m.state == CogState.SEARCH) return true; return false; }
    private boolean searching(SquadCoordinator sq) { for (Mind m : sq.members) if (m.state == CogState.SEARCH) return true; return false; }

    /**
     * The senses and the beliefs of one agent, for one frame. Always run - a mind goes on seeing and hearing whatever
     * its body is busy with. Returns false when the agent is not in a state to perceive (down, or not enrolled).
     */
    public boolean sense(long id, double dt) {
        Mind m = minds.get(id);
        if (m == null) return false;
        double now = world.simulationTimeSeconds();
        SelfState self = world.self(id);
        if (self.grounded()) { if (m.state != CogState.INCAPACITATED) { cancelAction(m, now); m.enter(CogState.INCAPACITATED, now); } return false; }
        if (m.state == CogState.INCAPACITATED) m.enter(m.belief.known() ? CogState.SEARCH : CogState.PATROL, now);
        m.alert = Math.max(0, m.alert - dt * 0.03);
        m.suppression = Math.max(0, m.suppression - dt * 0.28);
        hear(m, self, now);
        m.visionAcc += dt;
        if (m.visionAcc >= 1.0 / cfg.visionHz) { double step = Math.min(m.visionAcc, 0.25); m.visionAcc = 0; see(m, self, step, now); }
        m.beliefAcc += dt;
        if (m.beliefAcc >= 1.0 / cfg.beliefHz) {
            double step = Math.min(m.beliefAcc, 0.3);
            m.beliefAcc = 0;
            m.beliefs.age(now, step, t -> { VisionMeter vm = m.meters.get(t); return vm != null && vm.confirmed() && now - vm.lastExposed < 0.35; });
            settle(m, self, now);
            m.belief = m.beliefs.primary(now);
        }
        if (m.danger != null && now > m.dangerUntil) m.danger = null;
        return true;
    }

    /**
     * Beliefs that the world has overtaken. Someone who is no longer an enemy is no longer hunted. A target that has
     * died is only known to be dead by a mind that watched it happen or is standing over the body - anyone else goes
     * on looking for it.
     */
    private void settle(Mind m, SelfState self, double now) {
        for (TargetBelief b : m.beliefs.all()) {
            if (b.targetId() <= 0) continue;
            if (world.isAlive(b.targetId())) { if (!world.hostile(m.id, b.targetId())) { m.beliefs.forget(b.targetId()); m.meters.remove(b.targetId()); } continue; }
            boolean knows = b.visible() || b.unseenFor(now) < 1.5 || self.position().distanceXZ(world.actorPosition(b.targetId())) < 6;
            if (!knows) continue;
            m.beliefs.forget(b.targetId());
            m.meters.remove(b.targetId());
            m.trace.add(now, "target down");
            if (m.state == CogState.COMBAT) { speak(m, "target_down", ""); m.justKilled = true; }
        }
    }

    /**
     * The decision and the action of one agent, for one frame. Returns false only when there is no mind to act (not
     * enrolled, or incapacitated). 0.8: a mind at peace chooses too - its day, its post, its side's conduct - so there
     * is no state in which something other than this engine runs a body.
     */
    public boolean act(long id, double dt) {
        Mind m = minds.get(id);
        if (m == null || m.state == CogState.INCAPACITATED) return false;
        double now = world.simulationTimeSeconds();
        SelfState self = world.self(id);
        // the body's word on the current motion
        SydneyBridge.ActionStatus st = sydney.status(id, SydneyBridge.MOTION);
        m.blockedFor = st == SydneyBridge.ActionStatus.BLOCKED ? m.blockedFor + dt : 0;
        m.motionStatus = st;
        m.decisionAcc += dt;
        boolean urgent = m.urgent;
        if (m.decisionAcc >= 1.0 / cfg.decisionHz || urgent || m.action == null && m.state != CogState.PATROL) {
            m.decisionAcc = 0;
            m.urgent = false;
            if (urgent) interrupts++;
            decide(m, self, now);
        }
        if (m.action != null) m.action.tick(context(m, self, now), dt);
        // nobody fights from another man's elbow: in the open, with someone that close, a step aside comes first
        // 0.8: and nobody stands behind a friend with a weapon up. A shot the host held because one of our own was in the line
        // of it is answered with a step to the side, to where the line is clear - in cover or out of it
        String doing = m.action == null ? "" : m.action.name();
        boolean mayStep = doing.equals("StandAndFight") || doing.equals("Suppress") || doing.equals("FightFromCover") || doing.isEmpty();
        if (mayStep && m.state == CogState.COMBAT && !m.creature && m.meleeAction == 0 && self.canWalk() && now - m.lineBlockedAt < 0.5 && now >= m.asideUntil && m.belief.known()) {
            Vec3 dir = m.belief.estimatedPosition().sub(self.position()).flat().normalized();
            double side = (m.lineBlocks & 1) == 0 ? 1 : -1;
            for (int k = 0; k < 2 && dir.length() > 0.1; k++, side = -side) {
                Vec3 p = world.standNear(self.position().add(dir.left().mul(side * 2.6)));
                if (p == null || p.distanceXZ(self.position()) < 1.2 || !world.walkable(self.position(), p)) continue;
                m.aside = p; m.asideUntil = now + 1.5; m.lineBlocks++; clearings++;
                m.trace.add(now, "friend in the line of fire: stepping clear");
                break;
            }
        }
        boolean clearing = now < m.asideUntil && m.aside != null && now - m.lineBlockedAt < 2.5;
        // 0.9: stepping aside is for someone who is standing and fighting. A man on his way to cover, round a flank or out of the
        // fight is left to get there - this used to pull him off his run every second and send him back the way he came
        if (!mayStep) { m.asideUntil = Math.min(m.asideUntil, now); clearing = false; }
        if (mayStep && m.state == CogState.COMBAT && !m.creature && (m.cover == null || clearing) && m.meleeAction == 0 && self.canWalk()) {
            if (!clearing && now >= m.asideCheck && now >= m.asideUntil) {
                m.asideCheck = now + 0.8 + m.phase * 0.4;
                m.aside = spreadSpot(m, self.position(), 1.5);
                if (m.aside != null) { m.asideUntil = now + 1.6; spreads++; m.trace.add(now, "stepping aside"); }
            }
            if (now < m.asideUntil && m.aside != null) { if (self.position().distanceXZ(m.aside) < 0.7) m.asideUntil = now; else move(m, m.aside, m.belief.known() ? m.belief.estimatedPosition() : null, Stance.STAND, Locomotion.RUN, true); }
        }
        return true;
    }

    public long spreads, clearings;

    /** The host held a shot of this agent's because one of its own side was in the line of it. */
    public void onFireHeld(long id) { Mind m = minds.get(id); if (m != null) m.lineBlockedAt = world.simulationTimeSeconds(); }

    /**
     * 0.9: can this agent make that body out right now - inside its field of view for the distance, with a line to it, in
     * enough light? The host asks this before it lets a character react to somebody (a sentry's warning, a greeting); until
     * now those asked only whether a straight line between two heads was free of walls, in any direction.
     */
    public boolean perceives(long observer, long target) {
        Mind m = minds.get(observer);
        if (m == null || m.blind || !world.isAlive(target)) return false;
        SelfState self = world.self(observer);
        return perception.exposure(m, target, Math.min(self.sightRange(), world.sightLimit(observer, target)), self.arcFarDeg(), world.lit(target)) > 0.05;
    }

    /** Senses, then decision and action: the whole of one agent's frame. */
    public boolean update(long id, double dt) { return sense(id, dt) && act(id, dt); }

    // ------------------------------------------------------------------ senses
    private void hear(Mind m, SelfState self, double now) {
        List<SoundEvent> ev = world.soundEventsSince(m.soundCursor);
        if (ev.isEmpty()) return;
        for (SoundEvent s : ev) {
            m.soundCursor = Math.max(m.soundCursor, s.sequence());
            if (s.sourceId() == m.id) continue;
            // an ally's voice, footsteps or reload is not a disturbance: what they have to say arrives as a report
            boolean ally = s.sourceId() > 0 && world.isAlive(s.sourceId()) && !world.hostile(m.id, s.sourceId());
            boolean threat = Perception.threatSound(s.kind());
            if (ally && !threat) continue;
            if (ally && m.blind) continue;                                    // the eyeless live among the noise of their own kind and have stopped hearing it
            if (ally && (s.kind().startsWith("shot") || s.kind().equals("impact"))) { m.alert = Math.min(1, m.alert + 0.25); continue; }   // friends shooting: something is up, but it is not them we hunt
            Percept p = perception.hear(m, s, self.hearing(), random(m), random(m));
            if (p == null) continue;
            // one of the pack calling: a place to go to, about nobody in particular (a belief about a friend would be dropped as soon as it was formed)
            if (ally && m.creature) p = new Percept(Percept.Kind.SOUND, -s.sourceId() - 2_000_000L, p.position(), Vec3.ZERO, p.radius(), p.confidence(), VisionMeter.Level.NONE, p.detail(), true, p.time());
            m.alert = Math.min(1, m.alert + (threat ? 0.5 : 0.18) * Math.max(0.3, p.confidence() * 1.6));
            if (m.beliefs.ingest(p, now)) { m.trace.add(now, "heard " + s.kind() + " ~" + Math.round(p.radius()) + "m"); if (m.creature && m.state == CogState.PATROL) m.urgent = true; }
        }
    }

    private void see(Mind m, SelfState self, double dt, double now) {
        if (m.blind) return;                                                  // no eyes: nothing is ever seen, close or far
        perception.visionTicks++;
        Vec3 pos = self.position();
        double mult = (m.state == CogState.COMBAT ? cfg.combatMultiplier : m.alert > 0.5 ? cfg.alertMultiplier : 1) * (m.creature ? cfg.creatureGain : 1);
        for (long o : world.potentialTargetsNear(pos, self.sightRange() * 1.15)) {
            if (o == m.id || !world.isAlive(o) || !world.hostile(m.id, o)) continue;
            VisionMeter vm = m.meter(o);
            double ex = perception.exposure(m, o, Math.min(self.sightRange(), world.sightLimit(m.id, o)), self.arcFarDeg(), world.lit(o));
            Vec3 tp = world.actorPosition(o);
            double d = pos.distanceXZ(tp);
            VisionMeter.Level before = vm.level();
            vm.tick(dt, ex, mult * (d < 8 ? 2.4 : d < 20 ? 1.25 : 0.75) / reactionScale, cfg);
            if (ex <= 0.01) continue;
            vm.lastExposed = now;
            VisionMeter.Level lv = vm.level();
            if (lv == VisionMeter.Level.CONFIRMED) {
                m.beliefs.ingest(Percept.sight(o, tp, world.actorVelocity(o), cfg.visibleRadius, 1, lv, now), now);
                if (before != VisionMeter.Level.CONFIRMED) { m.trace.add(now, "confirmed target at " + Math.round(d) + "m"); m.alert = 1; m.urgent = true; m.sightedAt = now; }
            } else if (lv.ordinal() >= VisionMeter.Level.SUSPICIOUS.ordinal()) {
                // not resolved: the eye gives a rough place, not the place
                double err = lv == VisionMeter.Level.PROBABLE ? 2.5 : 5;
                Vec3 rough = tp.add(new Vec3((random(m) - 0.5) * 2 * err, 0, (random(m) - 0.5) * 2 * err));
                m.beliefs.ingest(Percept.sight(o, rough, Vec3.ZERO, err + 1, vm.progress(), lv, now), now);
                m.alert = Math.min(1, m.alert + dt * 0.5);
                if (before == VisionMeter.Level.NONE) m.trace.add(now, "noticed something at " + Math.round(d) + "m");
            }
        }
        m.meters.entrySet().removeIf(e -> e.getValue().progress() <= 0 && now - e.getValue().lastExposed > 12);
        if (m.creature) return;                                               // a creature reads nothing into a body or a pool of blood: it eats one and walks through the other
        List<Percept> ev = new ArrayList<>();
        // a body means something to those whose side it was on; blood means something to anyone
        List<GameWorld.Evidence> near = new ArrayList<>();
        for (GameWorld.Evidence e : world.evidenceNear(pos, 16)) if (!e.kind().startsWith("body:") || e.kind().equals("body:" + m.faction)) near.add(e);
        perception.evidence(m, near, ev);
        // 1.1, blood in a fight. Blood used to be a lead whenever it was seen - and a firefight covers the ground in it, so
        // people in the middle of one kept breaking off to go and look at the stain their own rounds had just made. Blood
        // is now read in its context:
        //   in contact (somebody hostile in sight, or seen, heard or shot at within the last quarter of a minute, or
        //   rounds landing round this one) it is what a fight looks like: it is noted - never looked at again - and that
        //   is all;
        //   out of contact, with somebody it has reason to suspect known to be near where the blood is, the blood is put
        //   down to them: what it believes about that person is sharpened toward the place, and it says so;
        //   otherwise it is what it always was - a lead about nobody in particular, to be gone and looked at.
        TargetBelief fight = contact(m, now);
        for (Percept p : ev) {
            boolean blood = !p.detail().startsWith("body");
            if (blood && fight != null) { bloodIgnored++; m.trace.add(now, "blood - in contact, ignored"); continue; }
            if (blood) {
                TargetBelief suspect = suspectNear(m, p.position(), now);
                if (suspect != null) {
                    bloodBlamed++;
                    m.alert = Math.min(1, m.alert + 0.35);
                    // the blood says the one it already suspects came this way: the guess moves toward it, a little surer
                    Vec3 toward = suspect.estimatedPosition().add(p.position().sub(suspect.estimatedPosition()).mul(0.5));
                    m.beliefs.ingest(new Percept(Percept.Kind.EVIDENCE, suspect.targetId(), toward, Vec3.ZERO, Math.max(4, p.radius()), Math.min(0.5, p.confidence() + 0.1), VisionMeter.Level.NONE, "blood", true, now), now);
                    m.trace.add(now, "blood - put down to " + suspect.targetId());
                    speak(m, "blood_blame", "");
                    continue;
                }
            }
            m.alert = Math.min(1, m.alert + 0.6);
            // a body or blood is about nobody in particular: it is filed under its own id until someone is seen
            m.beliefs.ingest(new Percept(Percept.Kind.EVIDENCE, -p.subjectId() - 1000, p.position(), Vec3.ZERO, p.radius(), p.confidence(), VisionMeter.Level.NONE, p.detail(), true, now), now);
            m.trace.add(now, "found " + p.detail());
            speak(m, p.detail().startsWith("body") ? "body_found" : "blood_found", "");
        }
    }

    /** 1.1: pools of blood seen and passed over because their finder was in a fight, and pools put down to somebody already suspected (gates, the overlay). */
    public int bloodIgnored, bloodBlamed;
    /** How recent a sight, sound or shot has to be for a mind to count as in contact (seconds), and how near blood a suspect has to be believed to be to be blamed for it (metres). */
    public static double CONTACT_SECONDS = 16, BLAME_RADIUS = 28;

    /** The belief that puts this mind in contact right now - a real person it can see, or has had word of within {@link #CONTACT_SECONDS} - or null. Being shot at counts by itself. */
    private TargetBelief contact(Mind m, double now) {
        TargetBelief best = null;
        for (TargetBelief b : m.beliefs.all()) {
            if (b.targetId() <= 0 || !b.known() || b.kind() == ConfidenceClass.EVIDENCE) continue;
            if (b.visible() || b.age(now) < CONTACT_SECONDS) { if (best == null || b.age(now) < best.age(now)) best = b; }
        }
        if (best == null && (m.suppression > 0.25 || now - m.lastHitTime < CONTACT_SECONDS || now - m.lastShot < CONTACT_SECONDS * 0.5)) best = m.belief.known() ? m.belief : TargetBelief.NONE;
        return best;
    }

    /** Somebody real this mind already has an idea about, believed to be within {@link #BLAME_RADIUS} of a place: the one blood found there is put down to. */
    private TargetBelief suspectNear(Mind m, Vec3 at, double now) {
        TargetBelief best = null;
        double bd = BLAME_RADIUS;
        for (TargetBelief b : m.beliefs.all()) {
            if (b.targetId() <= 0 || !b.known() || b.age(now) > 120) continue;
            double d = b.estimatedPosition().distanceXZ(at);
            if (d < bd) { bd = d; best = b; }
        }
        return best;
    }

    // ------------------------------------------------------------------ host events (things that happen to an agent)
    /** Rounds are landing close: nerves, and - when nothing is known yet - a rough idea of where they are coming from. */
    public void onNearMiss(long id, Vec3 impact, Vec3 fromDirection, long shooterId) {
        Mind m = minds.get(id);
        if (m == null || m.creature) return;
        double now = world.simulationTimeSeconds();
        m.suppression = Math.min(1, m.suppression + 0.34);
        m.alert = 1;
        if (m.suppression > 0.65) m.pinnedUntil = Math.max(m.pinnedUntil, now + 1.2 + m.suppression * 1.4 * (1.2 - m.profile.aggression()));
        if (m.state != CogState.COMBAT && fromDirection != null && fromDirection.length() > 0.1) {
            Vec3 me = world.actorPosition(id);
            double reach = 18 + random(m) * 8;
            Vec3 guess = me.add(fromDirection.flat().normalized().mul(reach)).add(new Vec3((random(m) - 0.5) * 8, 0, (random(m) - 0.5) * 8));
            m.beliefs.ingest(new Percept(Percept.Kind.HIT, shooterId, guess, Vec3.ZERO, 9, 0.45, VisionMeter.Level.NONE, "incoming", true, now), now);
            m.urgent = true;
        }
    }

    /** Hit. From in front and in sight, the shooter is known; otherwise only the direction the round came from. */
    public void onHit(long id, long attackerId, Vec3 fromDirection, boolean sawShooter) {
        Mind m = minds.get(id);
        if (m == null) return;
        double now = world.simulationTimeSeconds();
        m.alert = 1; m.lastHitTime = now; m.urgent = true;
        m.suppression = Math.min(1, m.suppression + 0.25);
        if (sawShooter) { m.meter(attackerId).confirm(); m.meter(attackerId).lastExposed = now; m.beliefs.ingest(Percept.sight(attackerId, world.actorPosition(attackerId), world.actorVelocity(attackerId), cfg.visibleRadius, 1, VisionMeter.Level.CONFIRMED, now), now); }
        else if (fromDirection != null) {
            Vec3 me = world.actorPosition(id);
            double reach = 14 + random(m) * 10, err = 7;
            m.beliefs.ingest(new Percept(Percept.Kind.HIT, attackerId, me.add(fromDirection.flat().normalized().mul(reach)).add(new Vec3((random(m) - 0.5) * err, 0, (random(m) - 0.5) * err)), Vec3.ZERO, err + 3, 0.5, VisionMeter.Level.NONE, "hit", true, now), now);
            m.trace.add(now, "hit from an unseen shooter");
        }
        SquadCoordinator sq = squad(m);
        if (sq != null) sq.dirty = true;
    }

    /** Something that is about to hurt whoever stays near it. Only those who could see or hear it land know. */
    public void onDanger(Vec3 at, double until, double radius) {
        for (Mind m : minds.values()) {
            if (m.creature) continue;                                         // the dead do not know what a grenade is
            Vec3 p = world.actorPosition(m.id);
            if (p.distance(at) > radius + 6) continue;
            if (!world.rayClear(p.add(new Vec3(0, 1.4, 0)), at.add(new Vec3(0, 0.3, 0))) && p.distance(at) > 4) continue;
            m.danger = at; m.dangerUntil = until; m.urgent = true;
        }
    }

    /** An ally went down where this one could see or hear it. */
    public void onAllyDown(long witness, long ally, Vec3 where, Vec3 fromDirection) {
        Mind m = minds.get(witness);
        if (m == null || m.creature) return;
        double now = world.simulationTimeSeconds();
        m.alert = 1; m.urgent = true;
        SquadCoordinator sq = squad(m);
        if (sq != null) sq.dirty = true;
        if (!m.belief.known() && fromDirection != null && fromDirection.length() > 0.1)
            m.beliefs.ingest(new Percept(Percept.Kind.EVIDENCE, -ally - 1000, where.add(fromDirection.flat().normalized().mul(14)), Vec3.ZERO, 10, 0.45, VisionMeter.Level.NONE, "ally_down", true, now), now);
        speak(m, "ally_down", "");
    }

    /** The leader's standing order for one squadmate (follow, hold, move, sweep). */
    public void order(long id, String order, Vec3 point) {
        Mind m = minds.get(id);
        if (m == null) return;
        m.order = order == null ? "follow" : order;
        m.orderPoint = point;
        m.urgent = true;
        m.trace.add(world.simulationTimeSeconds(), "order " + m.order);
    }

    /** 0.8: the leader's word on shooting. A squadmate holding fire keeps its place in the fight and its eyes open, and shoots only to save itself. */
    public void holdFire(long id, boolean hold) {
        Mind m = minds.get(id);
        if (m == null || m.holdFire == hold) return;
        m.holdFire = hold;
        m.urgent = true;
        m.trace.add(world.simulationTimeSeconds(), hold ? "order: hold fire" : "order: fire at will");
    }

    /** Something the body can smell on someone (0.8, the infected and bile): as good as having them in sight, for as long as it lasts. */
    public void onScent(long id, long targetId) {
        Mind m = minds.get(id);
        if (m == null || m.blind || !world.isAlive(targetId)) return;
        double now = world.simulationTimeSeconds();
        m.alert = 1;
        VisionMeter vm = m.meter(targetId);
        if (!vm.confirmed()) m.urgent = true;
        vm.confirm(); vm.lastExposed = now;
        m.beliefs.ingest(Percept.sight(targetId, world.actorPosition(targetId), world.actorVelocity(targetId), cfg.visibleRadius, 1, VisionMeter.Level.CONFIRMED, now), now);
    }

    /** Someone this agent is looking straight at has just become its business (pointed out, or a warning ignored): no second look is needed. */
    public void onSighted(long id, long targetId) {
        Mind m = minds.get(id);
        if (m == null || m.blind || !world.isAlive(targetId)) return;
        double now = world.simulationTimeSeconds();
        m.alert = 1; m.urgent = true; m.sightedAt = now - 10;
        m.meter(targetId).confirm(); m.meter(targetId).lastExposed = now;
        m.beliefs.ingest(Percept.sight(targetId, world.actorPosition(targetId), world.actorVelocity(targetId), cfg.visibleRadius, 1, VisionMeter.Level.CONFIRMED, now), now);
    }

    /** Sent somewhere by its own side (a patrol called to a noise, reinforcements): a place to go and look at, on someone else's word. */
    public void onDispatch(long id, Vec3 where) {
        Mind m = minds.get(id);
        if (m == null) return;
        double now = world.simulationTimeSeconds();
        m.alert = Math.max(m.alert, 0.8); m.urgent = true;
        m.beliefs.ingest(new Percept(Percept.Kind.EVIDENCE, -id - 3_000_000L, where, Vec3.ZERO, 6, 0.5, VisionMeter.Level.NONE, "dispatch", true, now), now);
        m.trace.add(now, "dispatched");
    }

    /** Asks a mind to decide again now rather than at its next step (the host has changed something it should weigh at once). */
    public void nudge(long id) { Mind m = minds.get(id); if (m != null) m.urgent = true; }

    public void orderDone(Mind m, String event) {
        if (!"hold".equals(m.order)) { m.order = "hold"; m.orderPoint = world.actorPosition(m.id); speak(m, event, ""); }
    }

    @Override public void onSydneyEvent(long actorId, long actionId, SydneyBridge.Event event) {
        Mind m = minds.get(actorId);
        if (m == null) return;
        double now = world.simulationTimeSeconds();
        switch (event) {
            case MOTION_BLOCKED -> { m.urgent = true; m.trace.add(now, "body: motion blocked"); }
            case FOOTING_LOST -> { cancelAction(m, now); m.trace.add(now, "body: footing lost"); }
            case ACTION_COMPLETED, DODGE_COMPLETED -> m.urgent = true;
            default -> { }
        }
    }

    // ------------------------------------------------------------------ communication
    private boolean reaches(ReportBus.Report r, long listener) {
        if (!world.isAlive(listener)) return false;
        Vec3 at = world.actorPosition(listener);
        switch (r.channel()) {
            case RADIO -> { SquadCoordinator sq = squads.get(r.squad()); return sq != null && sq.radioEnabled && world.self(listener).radio() && at.distance(r.sentFrom()) <= r.range(); }
            case VISUAL_SIGNAL -> { return at.distance(r.sentFrom()) <= r.range() && world.rayClear(at.add(new Vec3(0, 1.5, 0)), r.sentFrom().add(new Vec3(0, 1.5, 0))); }
            default -> { return at.distance(r.sentFrom()) <= r.range(); }
        }
    }

    private void deliver(ReportBus.Report r, long listener) {
        Mind m = minds.get(listener);
        if (m == null) return;
        double now = world.simulationTimeSeconds();
        // what arrives is what the sender believed when they sent it, scaled by how good the channel is
        double rel = r.channel() == ReportBus.Channel.RADIO ? 0.85 : r.channel() == ReportBus.Channel.SHOUT ? 0.7 : 0.6;
        Percept p = new Percept(Percept.Kind.REPORT, r.targetId(), r.position(), r.velocity(), r.radius() + (r.channel() == ReportBus.Channel.SHOUT ? 2.5 : 1.5), r.confidence() * rel, VisionMeter.Level.NONE, r.kind(), true, r.sentAt());
        if (m.beliefs.ingest(p, now)) { m.alert = Math.max(m.alert, 0.8); m.urgent = true; m.trace.add(now, "told (" + r.channel() + "): " + r.kind()); }
    }

    /**
     * Tells the squad what this mind believes. With a radio it goes on the net; without one it is shouted, and only
     * goes anywhere if the shout was actually made.
     */
    public void report(Mind m, String kind, boolean spoken) {
        SquadCoordinator sq = squad(m);
        if (sq == null || !m.belief.known() || m.belief.targetId() < 0) return;
        double now = world.simulationTimeSeconds();
        if (m.onCooldown("report", now)) return;
        TargetBelief b = m.belief;
        Vec3 from = world.actorPosition(m.id);
        boolean radio = world.self(m.id).radio() && sq.radioEnabled;
        if (!radio && !spoken) return;
        m.cooldown("report", now + 1.6);
        sq.note(b, m.id, now);
        Vec3 vel = b.direction().mul(b.visible() ? cfg.assumedTargetSpeed : 0);
        if (radio) bus.send(m.id, sq.id, ReportBus.Channel.RADIO, b.targetId(), b.estimatedPosition(), vel, b.radiusMeters(), b.confidence01(), kind, from, now, cfg.radioDelay, cfg.radioRange);
        else bus.send(m.id, sq.id, ReportBus.Channel.SHOUT, b.targetId(), b.estimatedPosition(), vel, b.radiusMeters(), b.confidence01(), kind, from, now, cfg.shoutDelay, cfg.shoutRange);
    }

    /** A line of dialogue for an event, on a per-speaker and per-squad cooldown. Returns whether it was actually said. */
    public boolean speak(Mind m, String event, String detail) {
        if (m.creature) return false;
        double now = world.simulationTimeSeconds();
        if (now - m.lastSpoke < 2.5) return false;
        SquadCoordinator sq = squad(m);
        if (sq != null && now - sq.lastSpeech < 1.1) return false;
        if (!world.speak(m.id, event, detail)) return false;
        m.lastSpoke = now;
        if (sq != null) sq.lastSpeech = now;
        return true;
    }

    private static final String[] COMPASS = {"north", "north-east", "east", "south-east", "south", "south-west", "west", "north-west"};
    public String bearing(Vec3 from, Vec3 to) {
        double a = Math.atan2(to.x() - from.x(), -(to.z() - from.z()));
        return COMPASS[(int) Math.floor((a + Math.PI * 2 + Math.PI / 8) / (Math.PI / 4)) % 8];
    }

    // ------------------------------------------------------------------ deciding
    private AiContext context(Mind m, SelfState self, double now) {
        SquadCoordinator sq = squad(m);
        int size = 1, fit = 1;
        boolean suppressing = false, advancing = false;
        if (sq != null) {
            size = sq.members.size(); fit = 0;
            for (Mind o : sq.members) {
                if (o.state != CogState.INCAPACITATED && o.state != CogState.DEAD) fit++;
                if (o == m) continue;
                if (o.action != null && o.action.name().equals("Suppress") && now - o.lastShot < 1.5) suppressing = true;
                if (o.action != null && (o.action.name().equals("Advance") || o.action.name().equals("Flank"))) advancing = true;
            }
        }
        TargetBelief b = m.belief;
        double prot = 1;
        boolean inCover = m.cover != null && self.position().distanceXZ(m.cover.position()) < 1.25;
        if (b.known() && (inCover || b.visible())) prot = cover.protection(self.position(), b.estimatedPosition(), m.cover != null && m.cover.low());
        boolean dangerNear = m.danger != null && now < m.dangerUntil && self.position().distance(m.danger) < cfg.grenadeRadius;
        Vec3 leader = null, lf = Vec3.ZERO;
        boolean lSprint = false, lCrouch = false;
        if (m.isMate && leaderId >= 0 && world.isAlive(leaderId)) {
            // a squadmate knows where its leader is: they are on the same net and moving together
            // 0.8: the way the leader's body is turned, not the way the leader is looking (in third person those differ, and the
            // squad used to swing round behind the camera instead of forming up behind the character)
            leader = world.actorPosition(leaderId); lf = world.bodyForward(leaderId);
            Vec3 lv = world.actorVelocity(leaderId);
            lSprint = Math.sqrt(lv.x() * lv.x() + lv.z() * lv.z()) > 5.2;
            lCrouch = world.bodyHeight(leaderId) < 1.45;
        }
        double want = "shotgun".equals(self.weaponKind()) ? 8 : "scoped".equals(self.weaponKind()) ? 34 : m.profile.preferredRange();
        return new AiContext(m.id, now, m.state, m.role, b, self, m.alert, m.suppression, m.pinned(now), inCover, prot, m.motionStatus, m.blockedFor, m.danger, dangerNear,
                size, fit, sq == null ? 0 : sq.caution(now), suppressing, advancing, m.isMate, m.order, m.orderPoint, leader, lf, lSprint, lCrouch, want,
                b.known() ? b.unseenFor(now) : 1e9, now - m.lastHitTime);
    }

    /** Where the mind's state should be, from what it believes. The transitions are the only place states change. */
    private void transition(Mind m, SelfState self, double now) {
        if (m.creature) { transitionCreature(m, self, now); return; }
        TargetBelief b = m.belief;
        SquadCoordinator sq = squad(m);
        boolean target = b.known() && b.targetId() >= 0;
        boolean contact = target && (b.visible() || b.kind() == ConfidenceClass.LAST_SEEN && b.unseenFor(now) < cfg.lostAfter * (0.7 + m.profile.searchPatience() * 0.3));
        // a fresh, confident report of a target is contact too - that is what a squad is for
        boolean told = target && b.kind() == ConfidenceClass.APPROXIMATE && b.source().startsWith("report") && b.confidence01() > 0.45 && b.age(now) < 6;
        boolean broken = self.health() < 0.28 || self.morale() < self.breakAt() || !self.armed();
        boolean mateHold = m.isMate;                     // the leader's people do not run: they fall back on the leader
        switch (m.state) {
            case WITHDRAW -> {
                // 0.9: a man who broke does not come out of hiding to hunt what broke him. Whole again, he looks; still broken, he goes
                // back to his day and lets go of what he believed (seeing it again is a fresh fright, and somewhere else to hide)
                if (!broken && now - m.stateSince > 6 && !m.cowering || m.action != null && now - m.actionSince > 2 && m.action.finished(context(m, self, now)) && !(b.visible())) {
                    boolean hunt = target && b.age(now) < 20 && !broken;
                    if (!hunt && target) { m.beliefs.forget(b.targetId()); m.meters.remove(b.targetId()); }
                    m.cowering = false;
                    m.enter(hunt ? CogState.SEARCH : CogState.PATROL, now);
                }
                return;
            }
            case COMBAT -> {
                if (broken && !mateHold && (b.visible() || now - m.lastHitTime < 4)) { m.enter(CogState.WITHDRAW, now); break; }
                if (!contact && !told) {
                    // contact lost: the hunt starts where the squad last had eyes
                    m.enter(target ? CogState.SEARCH : CogState.PATROL, now);
                    if (target && sq != null) { sq.escapes++; sq.dirty = true; if (sq.search == null || sq.search.targetId != b.targetId() || now - sq.search.builtAt > 30) { sq.search = new SearchGrid(world, cfg, b.targetId()); sq.search.seed(b, now); } }
                    if (target) { if (speak(m, "lost", bearing(self.position(), b.estimatedPosition()))) report(m, "lost", true); else report(m, "lost", false); }
                }
            }
            default -> {
                if (contact || told) {
                    // the moment of recognition takes a moment: reaction latency, shorter for the already alarmed
                    if (b.visible() && now - m.sightedAt < m.profile.reactionLatency() * reactionScale * (m.alert > 0.8 && m.state != CogState.PATROL ? 0.4 : 1)) return;
                    if (broken && !mateHold && self.morale() < self.breakAt()) { m.enter(CogState.WITHDRAW, now); return; }
                    boolean first = m.state != CogState.SEARCH;
                    m.enter(CogState.COMBAT, now);
                    if (sq != null) { sq.dirty = true; sq.lastContact = now; }
                    if (b.visible()) { boolean said = speak(m, first ? "contact" : "reacquired", bearing(self.position(), b.estimatedPosition())); report(m, "contact", said); }
                    return;
                }
                if (m.state == CogState.SEARCH) {
                    if (!target || m.action != null && m.action.finished(context(m, self, now))) {
                        m.enter(CogState.PATROL, now);
                        m.alert = Math.max(m.alert, 0.7);
                        if (target) m.beliefs.forget(b.targetId());
                        speak(m, "search_over", "");
                    }
                    return;
                }
                if (b.known()) {
                    // something heard, glimpsed, found or passed on - not a target yet
                    boolean serious = m.alert >= cfg.threatSoundAlert || b.kind() == ConfidenceClass.EVIDENCE || b.source().equals("hit") || b.source().startsWith("report");
                    if (m.state == CogState.PATROL) m.enter(serious || b.confidence01() > 0.4 ? CogState.INVESTIGATE : CogState.SUSPICIOUS, now);
                    else if (m.state == CogState.SUSPICIOUS && m.action != null && m.action.finished(context(m, self, now))) {
                        // looked, and saw nothing. A sound that means somebody (glass going, a door kicked, a shot) is gone and looked at however
                        // faint it was; anything else that was faint is let go of - and let go of properly, or the same half-heard noise
                        // would turn the head again every few seconds for as long as it was remembered
                        boolean worth = serious || b.confidence01() > 0.2 || b.source().startsWith("sound:") && Perception.threatSound(b.source().substring(6));
                        m.enter(worth ? CogState.INVESTIGATE : CogState.PATROL, now);
                        if (!worth) m.beliefs.forget(b.targetId());
                    }
                    else if (m.state == CogState.INVESTIGATE && m.action != null && m.action.finished(context(m, self, now))) {
                        // went, looked, nobody: a threat sound still means somebody is about, anything less is forgotten
                        boolean hunt = target && (b.source().equals("hit") || m.alert >= cfg.threatSoundAlert && b.confidence01() > 0.15);
                        if (hunt && sq != null && sq.search == null) { sq.search = new SearchGrid(world, cfg, b.targetId()); sq.search.seed(b, now); }
                        m.enter(hunt ? CogState.SEARCH : CogState.PATROL, now);
                        if (!hunt) m.beliefs.forget(b.targetId());
                    }
                } else if (m.state != CogState.PATROL) m.enter(CogState.PATROL, now);
            }
        }
    }

    /**
     * A creature's states (0.8). It has the same six a person has and uses four ways through them: something in sight is
     * hunted at once and for as long as it stays in sight or was a moment ago; something lost is looked for briefly where
     * it was; a noise is noticed and then gone to, or gone to at once if it was loud or one of the pack calling - or if
     * the creature has no eyes, when a noise is all there ever is; and with nothing at all it is at peace, which for the
     * dead means feeding and drifting. It never withdraws.
     */
    private void transitionCreature(Mind m, SelfState self, double now) {
        TargetBelief b = m.belief;
        boolean contact = b.known() && b.targetId() > 0 && (b.visible() || b.kind() == ConfidenceClass.LAST_SEEN && b.unseenFor(now) < cfg.creatureLostAfter);
        if (m.state == CogState.COMBAT) {
            if (!contact) m.enter(b.known() ? CogState.SEARCH : CogState.PATROL, now);
            return;
        }
        if (contact) { m.enter(CogState.COMBAT, now); return; }
        if (m.state == CogState.SEARCH) {
            if (!b.known() || m.action != null && m.action.finished(context(m, self, now))) { m.enter(CogState.PATROL, now); if (b.known()) m.beliefs.forget(b.targetId()); }
            return;
        }
        if (!b.known()) { if (m.state != CogState.PATROL) m.enter(CogState.PATROL, now); return; }
        boolean urgent = m.blind || b.confidence01() > cfg.creatureUrgentConfidence || b.source().equals("hit") || b.kind().ordinal() >= ConfidenceClass.LAST_SEEN.ordinal()
                || b.source().startsWith("sound:") && Perception.threatSound(b.source().substring(6));
        switch (m.state) {
            case PATROL -> m.enter(urgent ? CogState.INVESTIGATE : CogState.SUSPICIOUS, now);
            case SUSPICIOUS -> { if (urgent || m.action != null && m.action.finished(context(m, self, now))) m.enter(CogState.INVESTIGATE, now); }
            case INVESTIGATE -> { if (m.action != null && m.action.finished(context(m, self, now))) { m.enter(CogState.PATROL, now); m.beliefs.forget(b.targetId()); } }
            default -> m.enter(CogState.PATROL, now);
        }
    }

    private void decide(Mind m, SelfState self, double now) {
        decisions++;
        m.belief = m.beliefs.primary(now);
        transition(m, self, now);
        AiContext c = context(m, self, now);
        List<AiAction> set = m.creature ? creatures.of(m.state) : switch (m.state) {
            case COMBAT -> actions.combat;
            case SEARCH -> actions.search;
            case INVESTIGATE -> actions.investigate;
            case SUSPICIOUS -> actions.suspicious;
            case WITHDRAW -> actions.withdraw;
            case PATROL -> m.isMate ? actions.matePatrol : actions.patrol;
            default -> List.of();
        };
        if (set.isEmpty()) { cancelAction(m, now); return; }
        Selector.Pick pick = selector.decide(m, c, set, cfg.hysteresis);
        if (pick.action() == null) { cancelAction(m, now); return; }
        if (pick.changed()) {
            if (m.action != null) { m.action.cancel(c); if (m.action.cooldown() > 0) m.cooldown(m.action.name(), now + m.action.cooldown()); }
            m.action = pick.action();
            m.actionSince = now;
            m.actionScore = pick.score();
            m.commitUntil = now + Math.max(pick.action().commitment(), cfg.commitSeconds * 0.5);
            m.blockedFor = 0;
            actionChanges++;
            m.trace.add(now, m.state + "/" + m.role + ": " + pick.action().name() + String.format(" (%.2f, %s)", pick.score(), pick.why()));
            pick.action().begin(c);
        } else m.actionScore = pick.score();
    }

    private void cancelAction(Mind m, double now) {
        if (m.action == null) return;
        m.action.cancel(context(m, world.self(m.id), now));
        m.action = null;
    }

    // ------------------------------------------------------------------ what actions may ask for
    public void move(Mind m, Vec3 destination, Vec3 facing, Stance stance, Locomotion gait, boolean aimWhileMoving) {
        if (destination == null) return;
        motionIntents++;
        sydney.setMotion(m.id, new MotionIntent(destination, facing, stance, gait, aimWhileMoving));
    }

    /** How far off an agent will open fire with what it is holding right now (metres; overlay and gates). */
    public double fireRange(long id) { Mind m = minds.get(id); return m == null ? 0 : actions.fireRange(context(m, world.self(id), world.simulationTimeSeconds())); }

    /** A creature's intent, to its body. */
    public void creature(Mind m, SydneyBridge.CreatureIntent intent) { motionIntents++; sydney.setCreature(m.id, intent); }

    public void stop(Mind m, Vec3 facing, Stance stance) { motionIntents++; sydney.setMotion(m.id, new MotionIntent(world.actorPosition(m.id), facing, stance, Locomotion.STOP, facing != null)); }
    public void aim(Mind m, Vec3 target, double steadiness) { sydney.setAim(m.id, new AimIntent(target, steadiness)); }

    /** 1.2: shots a mind wanted and the host's engagement rule would not have (gates). */
    public int engagementsRefused;

    public void fire(Mind m, Vec3 aim, boolean speculative, long intended) {
        // 1.2: the host may say that this target is too far off to be fired on at all, seen or not
        if (intended != 0) {
            double lim = world.engageLimit(m.id, intended);
            if (lim < Double.MAX_VALUE && world.actorPosition(m.id).distanceXZ(world.actorPosition(intended)) > lim) { engagementsRefused++; return; }
        }
        weaponCommands++;
        if (speculative) speculativeCommands++;
        m.lastShot = world.simulationTimeSeconds();
        m.shotsFired++;
        world.issueWeaponCommand(m.id, new WeaponCommand(aim, true, false, speculative, intended));
    }

    public void reload(Mind m) { world.issueWeaponCommand(m.id, new WeaponCommand(Vec3.ZERO, false, true, false, 0)); }

    public Vec3 squadCentre(Mind m) {
        SquadCoordinator sq = squad(m);
        if (sq == null || sq.members.isEmpty()) return world.actorPosition(m.id);
        Vec3 c = Vec3.ZERO;
        for (Mind o : sq.members) c = c.add(world.actorPosition(o.id));
        return c.mul(1.0 / sq.members.size());
    }

    public boolean closestInSquad(Mind m, Vec3 p) {
        SquadCoordinator sq = squad(m);
        if (sq == null) return true;
        double mine = world.actorPosition(m.id).distanceXZ(p);
        for (Mind o : sq.members) if (o != m && o.state != CogState.INCAPACITATED && world.actorPosition(o.id).distanceXZ(p) < mine - 0.01) return false;
        return true;
    }

    /** Chooses and reserves a position for a role against what this mind believes. exclude is a point not to choose again (-1 = none). */
    /**
     * Somewhere to stand that is not on top of a squadmate: for a member with no cover to go to who has ended up
     * shoulder to shoulder with another. Null when there is room where it is.
     */
    public Vec3 spreadSpot(Mind m, Vec3 at) { return spreadSpot(m, at, 2.6); }

    public Vec3 spreadSpot(Mind m, Vec3 at, double within) {
        SquadCoordinator sq = squad(m);
        if (sq == null) return null;
        Vec3 near = null;
        double nd = within;
        for (Mind o : sq.members) { if (o == m) continue; Vec3 p = world.actorPosition(o.id); double d = p.distanceXZ(at); if (d < nd) { nd = d; near = p; } }
        if (near == null) return null;
        Vec3 away = at.sub(near).flat();
        if (away.length() < 0.05) away = new Vec3(Math.cos(m.phase * 6.283), 0, Math.sin(m.phase * 6.283));
        for (double out : new double[]{4.0, 5.5, 3.0}) { Vec3 p = world.standNear(at.add(away.normalized().mul(out))); if (p != null && p.distanceXZ(near) > 2.6) return p; }
        return null;
    }

    /**
     * 0.9: somewhere out of sight of where the threat is believed to be, when there is no cover point to be had: the nearest
     * standable place the threat cannot see - round a corner, through a door, behind a parked car. Nearer is better, a place
     * that does not mean running at the threat is better, and a place a squadmate is already in is not taken. Null when
     * everything in reach is in the open.
     */
    public Vec3 hideSpot(Mind m, Vec3 from, Vec3 threat, double reach) {
        Vec3 eye = threat.add(new Vec3(0, 1.5, 0));
        Vec3 away = from.sub(threat).flat().normalized();
        if (away.length() < 0.1) away = new Vec3(Math.cos(m.phase * 6.283), 0, Math.sin(m.phase * 6.283));
        SquadCoordinator sq = squad(m);
        Vec3 best = null;
        double bs = -1e9;
        for (double ring : new double[]{4, 7, 11, reach}) for (int k = 0; k < 12; k++) {
            double ang = k * Math.PI / 6 + m.phase;
            Vec3 dir = new Vec3(Math.cos(ang), 0, Math.sin(ang));
            Vec3 p = world.standNear(from.add(dir.mul(ring)));
            if (p == null || Math.abs(p.y() - from.y()) > 3.2 || world.hazardAt(p) || p.distanceXZ(threat) < 5) continue;
            cover.rays += 2;
            // hidden means the whole of a crouching body: the knees and the head
            if (world.rayClear(eye, p.add(new Vec3(0, 0.4, 0))) || world.rayClear(eye, p.add(new Vec3(0, 1.0, 0)))) continue;
            boolean crowded = false;
            if (sq != null) for (Mind o : sq.members) if (o != m && world.actorPosition(o.id).distanceXZ(p) < 1.6) { crowded = true; break; }
            if (crowded) continue;
            double s = -p.distanceXZ(from) * 0.5 + dir.dot(away) * 2.5 + (world.walkable(from, p) ? 1.5 : 0);
            if (s > bs) { bs = s; best = p; }
        }
        return best;
    }

    public CoverScorer.Choice pickCover(Mind m, AiContext c, Role role, long exclude) {
        if (!c.belief().known()) return null;
        SquadCoordinator sq = squad(m);
        List<Vec3> allies = new ArrayList<>();
        if (sq != null) for (Mind o : sq.members) if (o != m) allies.add(o.cover != null ? o.cover.position() : world.actorPosition(o.id));
        Vec3 from = c.position();
        // the best place may already be somebody's: then the next best, and the next - a man without the spot he wanted
        // takes another, he does not stand beside the one who has it
        java.util.Set<Long> taken = new java.util.HashSet<>();
        if (exclude >= 0) taken.add(exclude);
        CoverScorer.Choice ch = null;
        for (int attempt = 0; attempt < 5; attempt++) {
            ch = cover.best(m.id, from, c.belief(), role, squadCentre(m), c.wantRange(), allies, taken);
            if (ch == null) return null;
            // a squadmate's cover has to be within reach of the leader or the place it was told to hold
            boolean far = false;
            if (c.mate()) { Vec3 a = c.orderPoint() != null && !"follow".equals(c.order()) ? c.orderPoint() : c.leader(); double leash = "hold".equals(c.order()) ? cfg.holdLeash : cfg.followLeash; far = a != null && ch.position().distanceXZ(a) > leash; }
            if (!far && world.reservePoint(ch.point().id(), m.id, c.now() + cfg.coverTtl * 2)) break;
            taken.add(ch.point().id());
            ch = null;
        }
        if (ch == null) return null;
        m.cover = ch.point();
        m.coverSince = c.now();
        m.coverUntil = c.now() + cfg.coverTtl * (1 + random(m));
        m.trace.add(c.now(), String.format("cover %.1f for %s", ch.score(), role));
        return ch;
    }

    // ------------------------------------------------------------------ overlay
    /** One line for the debug overlay: what this mind is, believes and is doing. Never the truth about its target. */
    public String describe(long id) {
        Mind m = minds.get(id);
        if (m == null) return "";
        double now = world.simulationTimeSeconds();
        TargetBelief b = m.belief;
        double best = 0;
        for (VisionMeter vm : m.meters.values()) best = Math.max(best, vm.progress());
        return m.state + "/" + m.role + " " + (m.action == null ? "-" : m.action.name()) + String.format(" u%.1f", m.actionScore) + String.format(" see %.0f%%", best * 100)
                + (b.known() ? " " + b.kind() + String.format(" r%.0fm %.0fs", b.radiusMeters(), b.age(now)) + " [" + b.source() + "]" : " no belief") + (m.pinned(now) ? " PINNED" : "") + " body:" + m.motionStatus;
    }
}
