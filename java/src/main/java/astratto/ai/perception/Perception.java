package astratto.ai.perception;

import astratto.ai.api.GameWorld;
import astratto.ai.api.GameWorld.Evidence;
import astratto.ai.api.GameWorld.SoundEvent;
import astratto.ai.api.Vec3;
import astratto.ai.runtime.Mind;
import astratto.ai.runtime.NeuralConfig;
import java.util.List;

/**
 * The senses: sight, hearing and the reading of evidence. This is the one place in NeuralAI that is allowed to ask
 * the world where somebody actually is - and what it hands on is never that. Sight gives an exact position only for
 * someone it has confirmed and can see this instant; hearing gives a bearing and an error; evidence gives an
 * inference. Everything downstream works from those.
 */
public final class Perception {
    private final GameWorld world;
    private final NeuralConfig cfg;
    /** Counters (profiling and gates). */
    public long rays, visionTicks, soundsHeard;

    public Perception(GameWorld world, NeuralConfig cfg) { this.world = world; this.cfg = cfg; }

    private static final double[] UPRIGHT = {0.92, 0.62, 0.22}, CROUCHED = {0.76, 0.5, 0.2};

    /** The half-angle of the field of view at a distance: wide close in (what is beside you is noticed), narrow far out. */
    public double halfAngleDeg(double distance, double arcFarDeg) {
        double far = Math.min(cfg.farHalfAngleDeg, arcFarDeg);
        if (distance <= cfg.nearDistance) return cfg.nearHalfAngleDeg;
        if (distance >= cfg.farDistance) return far;
        double t = (distance - cfg.nearDistance) / (cfg.farDistance - cfg.nearDistance);
        return cfg.nearHalfAngleDeg + (far - cfg.nearHalfAngleDeg) * t;
    }

    /**
     * How exposed a target is to an observer right now, 0 .. 1: is it inside the cone for its distance, how much of
     * the body has a clear line (head, chest, legs), and then everything that makes a visible body harder or easier
     * to notice - stance, movement, light, foliage in the way, distance.
     */
    public double exposure(Mind m, long target, double sightRange, double arcFarDeg, boolean targetLit) {
        Vec3 eye = world.actorPosition(m.id).add(new Vec3(0, world.eyeHeight(m.id), 0));
        Vec3 tp = world.actorPosition(target);
        double d = eye.distanceXZ(tp);
        double range = Math.min(sightRange, world.visibilityRange());
        if (d > range) return 0;
        Vec3 fwd = world.actorForward(m.id), to = tp.sub(eye).flat().normalized();
        if (d > cfg.closeSense) {
            double cos = fwd.flat().normalized().dot(to);
            if (cos < Math.cos(Math.toRadians(halfAngleDeg(d, arcFarDeg)))) return 0;
            // unalerted eyes are on the street, not the rooftops
            double dy = tp.y() + world.bodyHeight(target) * 0.6 - eye.y();
            if (m.alert < 0.5 && Math.abs(dy) > d * 0.75 + 1.5) return 0;
        }
        double h = world.bodyHeight(target), seen = 0;
        // 0.9: a crouching body keeps its head down. Its head sample is the face, not the crown: crouched behind a single block
        // (1.22 m of person, 1 m of block) the crown used to show over the top and count as two fifths of the body in view
        boolean crouched = h < 1.45 && h >= 0.75;
        double[] at = crouched ? CROUCHED : UPRIGHT, weight = {0.4, 0.4, 0.2};
        int foliage = 0;
        for (int i = 0; i < 3; i++) {
            Vec3 p = tp.add(new Vec3(0, h * at[i], 0));
            rays++;
            if (!world.rayClear(eye, p)) continue;
            seen += weight[i];
            if (i == 1) foliage = world.foliageBetween(eye, p);
        }
        if (seen <= 0) return 0;
        Vec3 v = world.actorVelocity(target);
        double speed = Math.sqrt(v.x() * v.x() + v.z() * v.z());
        double stance = h < 0.75 ? cfg.proneExposure : h < 1.45 ? cfg.crouchExposure : 1;
        double motion = speed > 5 ? cfg.sprintExposure : speed > 0.5 ? cfg.movingExposure : cfg.stillExposure;
        double light = targetLit ? 1 : Math.max(Math.max(cfg.darkFloor, world.lightAt(tp.add(new Vec3(0, 1, 0)))), world.torchLight(m.id, target));
        double veg = Math.pow(cfg.foliagePerCell, foliage);
        double dist = d <= cfg.closeSense ? 1.6 : Math.max(0.12, 1 - d / Math.max(1, range));
        return Math.max(0, Math.min(2, seen * stance * motion * light * veg * (0.35 + 0.9 * dist)));
    }

    /**
     * What a listener makes of one sound: nothing (too far, too muffled), or a point with an error radius. The point
     * is pushed off the true source by that error - a gunshot says "over there", not "behind the third window".
     */
    public Percept hear(Mind m, SoundEvent s, double hearing, double jitterA, double jitterB) {
        if (s.sourceId() == m.id || s.radius() <= 0) return null;
        Vec3 me = world.actorPosition(m.id).add(new Vec3(0, world.eyeHeight(m.id), 0));
        double d = me.distance(s.position());
        double reach = s.radius() * hearing * (m.alert > 0.5 ? 1.15 : 1);
        if (d > reach) return null;
        // walls and floors between take the top off it; within arm's length a sound is a sound
        double open = d < 6 ? 1 : (world.rayClear(me, s.position()) ? 1 : 0.55);
        if (Math.abs(me.y() - s.position().y()) > 3.2 && d < 40 && !s.kind().startsWith("shot") && !s.kind().equals("explosion")) open *= 0.6;
        if (d > reach * open) return null;
        soundsHeard++;
        double loud = 1 - d / Math.max(1, reach * open);
        boolean unambiguous = d < cfg.hearUnambiguous || loud > 0.85 && open >= 1;
        double err = unambiguous ? 0.8 : Math.max(1.5, d * cfg.hearErrorPerMetre * (1.25 - loud) / Math.max(0.55, open));
        Vec3 est = s.position().add(new Vec3((jitterA - 0.5) * 2 * err, 0, (jitterB - 0.5) * 2 * err));
        boolean threat = threatSound(s.kind());
        return Percept.sound(s.sourceId(), est, err, Math.min(1, loud) * (threat ? 0.6 : 0.35), s.kind(), threat, s.time());
    }

    /** Sounds that mean someone hostile is about, as against sounds that only mean something moved. */
    public static boolean threatSound(String kind) {
        return kind.startsWith("shot") || kind.equals("explosion") || kind.equals("impact") || kind.equals("bullet_snap") || kind.equals("screech") || kind.equals("roar") || kind.equals("shriek")
                || kind.equals("door_kick") || kind.equals("door_break") || kind.equals("glass_break") || kind.equals("flashbang") || kind.equals("melee");
    }

    /** Evidence in plain view of this observer: close enough to make out and with a clear line to it. */
    public void evidence(Mind m, List<Evidence> near, List<Percept> out) {
        Vec3 eye = world.actorPosition(m.id).add(new Vec3(0, world.eyeHeight(m.id), 0));
        Vec3 fwd = world.actorForward(m.id).flat().normalized();
        for (Evidence e : near) {
            if (m.evidenceSeen.contains(e.id())) continue;
            double d = eye.distanceXZ(e.position());
            if (d > 14) continue;
            if (d > 4 && fwd.dot(e.position().sub(eye).flat().normalized()) < 0.2) continue;
            rays++;
            if (!world.rayClear(eye, e.position().add(new Vec3(0, 0.4, 0)))) continue;
            m.evidenceSeen.add(e.id());
            // a body or a pool of blood says someone was here and roughly which way the harm came from - not where they are now
            Vec3 guess = e.sourceDirection().length() > 0.01 ? e.position().add(e.sourceDirection().normalized().mul(Math.min(18, 6 + d))) : e.position();
            out.add(Percept.evidence(e.id(), guess, e.sourceDirection().length() > 0.01 ? 9 : 5, e.confidence(), e.kind(), world.simulationTimeSeconds()));
        }
    }
}
