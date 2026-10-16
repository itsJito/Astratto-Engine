package astratto.ai.animal;

/**
 * NeuralAI for animals (1.1). An animal's mind is a NeuralAI mind in the sense that matters: it knows nothing it has
 * not sensed, it decides, and what it decides is an intent for a body it does not move itself. It is a much smaller
 * mind than a person's - no beliefs about who is where behind which wall, no squad, no cover - and every kind of
 * animal has one of its own, because a crow, a dog and a spider do not want the same things:
 *
 *   {@link Crow} ....... sits. It leaves when something comes within three blocks of it, and not before.
 *   {@link Dog} ........ belongs to somebody. It keeps at their heel, warns of what it smells, and goes for whatever
 *                        goes for them. Without an owner it is a stray and keeps its distance.
 *   {@link Bat} ........ hangs under a roof by day; flies its rounds when it is dark or it is disturbed.
 *   {@link Chicken} .... scratches about, and runs flapping from anything that comes at it fast.
 *   {@link Spider} ..... waits. What walks into its ground is rushed; what stays out of it is left alone.
 *   {@link PackDog} .... one of the infected dogs: it has a pack and nothing else. Whatever is not of its pack is
 *                        prey - people first - and it comes in fast, mouth open, and leaps the last of the way.
 *   {@link Brood} ...... the mutated spider: thrust, tackle and spit, from the floor, the wall or the ceiling.
 *
 * The host gives a brain its {@link Senses} and takes an {@link Intent} back. Nothing in this package knows what a
 * voxel, an actor or a sound file is.
 */
public abstract class AnimalBrain {
    /** Something sensed: where it is, how far, how fast it is moving, and what the host calls it (an id of the host's own). */
    public static final class Thing {
        public float x, y, z, dist, speed;
        public long id;
        public boolean valid;
        public Thing set(float x, float y, float z, float dist, float speed, long id) { this.x = x; this.y = y; this.z = z; this.dist = dist; this.speed = speed; this.id = id; valid = true; return this; }
        public Thing none() { valid = false; dist = Float.MAX_VALUE; return this; }
    }

    /** What an animal can know of the world, asked for as it needs it. */
    public interface Senses {
        float time();
        float x(); float y(); float z();
        float homeX(); float homeY(); float homeZ();
        /** A number in 0..1, different every call. */
        float random();
        /** The nearest person (anybody on two legs who is not one of the dead) within a distance. */
        Thing person(float within);
        /** The nearest of the dead within a distance. */
        Thing infected(float within);
        /** The nearest living thing that is not of this animal's own pack or owner: what a predator calls prey. */
        Thing prey(float within);
        /** Whoever this animal belongs to, and whoever is harming them (or them harming) right now. */
        Thing owner();
        Thing ownerThreat();
        /** The middle of this animal's pack, and how many of it are left (itself included). */
        Thing pack();
        int packSize();
        boolean dark();
        boolean roofed();
        /** When this animal was last hurt (host time; very negative if never), and by whom. */
        float hurtAt();
        Thing attacker();
        /** A place to go: somewhere to perch or roost near a point, or a point on open ground. Returns false when there is none. */
        boolean perchNear(float x, float z, float within, float[] out);
        boolean groundNear(float x, float z, float within, float[] out);
        /** A straight line that can be walked. */
        boolean clearTo(float x, float z);
    }

    public enum Move { STAY, WALK, RUN, FLY, PERCH, HANG, LEAP, CLING }

    /** What the body is asked to do this frame. */
    public static final class Intent {
        public Move move = Move.STAY;
        public float x, y, z, speed = 1;
        /** Something to face (NaN: the way it is going). */
        public float faceX = Float.NaN, faceZ = Float.NaN;
        /** A sound to make now ("" = none): the host knows what a "bark" is. */
        public String voice = "";
        /** Who to hurt now (0 = nobody), how, and how hard. */
        public long attack;
        public String attackKind = "";
        public float damage;
        public boolean mouthOpen, sitting, alarmed;
        /** 1.2: how far through the wind-up of a strike or a leap it is (0 = not winding up, 1 = about to let go): what is seen coming. */
        public float windUp;
        public void clear() { move = Move.STAY; speed = 1; faceX = faceZ = Float.NaN; voice = ""; attack = 0; attackKind = ""; damage = 0; mouthOpen = false; sitting = false; alarmed = false; windUp = 0; }
        public Intent go(Move m, float tx, float ty, float tz, float sp) { move = m; x = tx; y = ty; z = tz; speed = sp; return this; }
    }

    /** What the mind is doing, in a word (the inspector shows it; gates read it). */
    public String state = "idle";
    protected float stateSince, nextVoice, nextThink;
    protected final float[] spot = new float[3];

    protected void to(String s, Senses w) { if (!state.equals(s)) { state = s; stateSince = w.time(); } }
    protected float in(Senses w) { return w.time() - stateSince; }
    protected boolean voice(Senses w, Intent out, String v, float every) {
        if (w.time() < nextVoice) return false;
        nextVoice = w.time() + every * (0.7f + 0.6f * w.random());
        out.voice = v;
        return true;
    }

    public abstract void think(Senses w, Intent out, float dt);

    // ================================================================== the crow
    /** Still, until something is within three blocks; then away to another perch, and still again. */
    public static final class Crow extends AnimalBrain {
        /** How near anything may come before it goes (blocks) - the brief's figure - and how far it goes. */
        public static float FLUSH = 3f, FLIGHT = 34f;
        private float tx, ty, tz;
        public int flushes;

        @Override public void think(Senses w, Intent out, float dt) {
            switch (state) {
                case "idle", "perched" -> {
                    to("perched", w);
                    out.move = Move.PERCH;
                    Thing p = w.person(FLUSH), i = w.infected(FLUSH);
                    boolean near = p.valid && p.dist <= FLUSH || i.valid && i.dist <= FLUSH || w.time() - w.hurtAt() < 0.5f;
                    if (near) {
                        // away from whatever it was, to somewhere to sit a good way off - or just up and off, if there is nowhere
                        Thing from = p.valid && (!i.valid || p.dist <= i.dist) ? p : i;
                        float ax = w.x() - (from.valid ? from.x : w.x() - 1), az = w.z() - (from.valid ? from.z : w.z()), al = Math.max(0.01f, (float) Math.sqrt(ax * ax + az * az));
                        float gx = w.x() + ax / al * FLIGHT * (0.6f + 0.4f * w.random()), gz = w.z() + az / al * FLIGHT * (0.6f + 0.4f * w.random());
                        if (w.perchNear(gx, gz, 16, spot)) { tx = spot[0]; ty = spot[1]; tz = spot[2]; }
                        else { tx = gx; ty = w.y() + 9; tz = gz; }
                        flushes++;
                        out.voice = "caw"; out.alarmed = true;
                        nextVoice = w.time() + 1.2f;
                        to("flying", w);
                    } else if (w.random() < dt * 0.05f) voice(w, out, "caw", 6);
                }
                case "flying" -> {
                    out.go(Move.FLY, tx, ty, tz, 1);
                    float dx = tx - w.x(), dy = ty - w.y(), dz = tz - w.z();
                    if (dx * dx + dy * dy + dz * dz < 0.5f || in(w) > 14) to("perched", w);
                    else if (in(w) < 2.5f) voice(w, out, "caw", 1.1f);
                }
                default -> to("perched", w);
            }
        }
    }

    // ================================================================== the dog
    /** Somebody's dog. At heel; a warning for what it smells; teeth for whatever goes for its owner. */
    public static final class Dog extends AnimalBrain {
        public static float HEEL = 2.4f, LEASH = 9f, SMELL = 13f, GUARD = 7f, BITE_REACH = 1.25f, BITE = 16f, BITE_EVERY = 0.9f;
        private float biteAt, sitAfter;
        private long quarry;

        @Override public void think(Senses w, Intent out, float dt) {
            Thing own = w.owner();
            if (!own.valid) { stray(w, out, dt); return; }
            // ---- something is at its owner, or its owner is at something: that comes first
            Thing th = w.ownerThreat();
            Thing dead = w.infected(GUARD);
            if (!th.valid && dead.valid) th = dead;                         // the dead near its owner are a threat whether or not anybody has noticed
            if (!th.valid && w.time() - w.hurtAt() < 4) th = w.attacker();
            if (th.valid && th.dist < 26) {
                to("attack", w);
                quarry = th.id;
                out.mouthOpen = true; out.alarmed = true;
                out.faceX = th.x; out.faceZ = th.z;
                if (th.dist > BITE_REACH) { out.go(Move.RUN, th.x, th.y, th.z, 1); voice(w, out, in(w) < 1 ? "bark" : "growl", 1.3f); }
                else if (w.time() >= biteAt) { biteAt = w.time() + BITE_EVERY; out.attack = th.id; out.attackKind = "bite"; out.damage = BITE; out.voice = "growl"; }
                return;
            }
            quarry = 0;
            // ---- something it does not like the smell of, further off: it says so, and stays where it is
            Thing far = w.infected(SMELL);
            if (far.valid) {
                to("alert", w);
                out.faceX = far.x; out.faceZ = far.z; out.alarmed = true;
                voice(w, out, far.dist < SMELL * 0.6f ? "bark" : "growl", 1.6f);
                if (own.dist > HEEL * 1.6f) out.go(Move.RUN, own.x, own.y, own.z, 0.9f);
                return;
            }
            // ---- at heel
            if (own.dist > LEASH) { to("catch_up", w); out.go(Move.RUN, own.x, own.y, own.z, 1); sitAfter = w.time() + 3; return; }
            if (own.dist > HEEL || own.speed > 0.6f) {
                to("heel", w);
                out.go(own.dist > HEEL * 1.8f ? Move.RUN : Move.WALK, own.x, own.y, own.z, own.dist > HEEL * 1.8f ? 0.8f : 1);
                sitAfter = w.time() + 2.5f + w.random() * 2;
                if (own.dist <= HEEL) out.move = Move.STAY;
                return;
            }
            // its owner has stopped: it sits, looks at them, pants; whines if they have been hurt
            to(w.time() > sitAfter ? "sit" : "wait", w);
            out.sitting = w.time() > sitAfter;
            out.faceX = own.x; out.faceZ = own.z;
            if (w.random() < dt * 0.12f) voice(w, out, "pant", 7);
        }

        private void stray(Senses w, Intent out, float dt) {
            Thing p = w.person(9), i = w.infected(11);
            if (i.valid) { to("flee", w); out.go(Move.RUN, w.x() + (w.x() - i.x) * 3, w.y(), w.z() + (w.z() - i.z) * 3, 1); voice(w, out, "whine", 2.5f); return; }
            if (p.valid && p.dist < 4) { to("wary", w); out.go(Move.WALK, w.x() + (w.x() - p.x), w.y(), w.z() + (w.z() - p.z), 1); out.faceX = p.x; out.faceZ = p.z; voice(w, out, "growl", 3); return; }
            if (w.time() >= nextThink) {
                nextThink = w.time() + 3 + w.random() * 6;
                if (w.groundNear(w.homeX() + (w.random() - 0.5f) * 24, w.homeZ() + (w.random() - 0.5f) * 24, 6, spot)) to("roam", w); else to("idle", w);
            }
            if ("roam".equals(state)) { out.go(Move.WALK, spot[0], spot[1], spot[2], 0.8f); float dx = spot[0] - w.x(), dz = spot[2] - w.z(); if (dx * dx + dz * dz < 1) to("idle", w); }
            else out.sitting = in(w) > 2;
        }
    }

    // ================================================================== the bat
    /** Under a roof by day; on the wing when it is dark or something has disturbed it; never far from where it roosts. */
    public static final class Bat extends AnimalBrain {
        public static float RANGE = 11f, DISTURB = 2.5f;
        private float ang, lap, restAfter;

        @Override public void think(Senses w, Intent out, float dt) {
            Thing p = w.person(DISTURB);
            boolean disturbed = p.valid || w.time() - w.hurtAt() < 1;
            switch (state) {
                case "idle", "roost" -> {
                    to("roost", w);
                    out.go(Move.HANG, w.homeX(), w.homeY(), w.homeZ(), 1);
                    if (disturbed || w.dark() && in(w) > 6 && w.random() < dt * 0.2f) { ang = w.random() * 6.28f; lap = 0; restAfter = (disturbed ? 9 : 18) + w.random() * 14; to("flying", w); out.voice = "squeak"; out.alarmed = disturbed; }
                }
                default -> {
                    // its rounds: an uneven circle about the roost, never level, never the same twice
                    ang += dt * (1.7f + 0.6f * (float) Math.sin(w.time() * 0.7f));
                    lap += dt;
                    float rad = RANGE * (0.35f + 0.3f * (float) Math.sin(w.time() * 0.31f + ang * 0.5f) + 0.3f);
                    out.go(Move.FLY, w.homeX() + (float) Math.cos(ang) * rad, w.homeY() - 1.3f - 0.9f * (float) Math.sin(ang * 2.3f), w.homeZ() + (float) Math.sin(ang) * rad, 1);
                    if (w.random() < dt * 0.35f) voice(w, out, "squeak", 3.5f);
                    if (lap > restAfter && !disturbed) { out.go(Move.FLY, w.homeX(), w.homeY(), w.homeZ(), 1); float dx = w.homeX() - w.x(), dy = w.homeY() - w.y(), dz = w.homeZ() - w.z(); if (dx * dx + dy * dy + dz * dz < 0.4f || lap > restAfter + 8) to("roost", w); }
                }
            }
        }
    }

    // ================================================================== the chicken
    /** Scratching about a yard; off in a flap when anything comes at it. */
    public static final class Chicken extends AnimalBrain {
        public static float SCARE = 3.4f, RANGE = 7f;

        @Override public void think(Senses w, Intent out, float dt) {
            Thing p = w.person(SCARE), i = w.infected(SCARE + 2);
            Thing fright = i.valid ? i : p.valid && (p.speed > 2.2f || p.dist < 1.3f) ? p : null;
            if (w.time() - w.hurtAt() < 2) fright = w.attacker().valid ? w.attacker() : fright;
            if (fright != null && fright.valid) {
                to("flee", w);
                float ax = w.x() - fright.x, az = w.z() - fright.z, al = Math.max(0.01f, (float) Math.sqrt(ax * ax + az * az));
                out.go(Move.RUN, w.x() + ax / al * 6, w.y(), w.z() + az / al * 6, 1);
                out.alarmed = true;
                voice(w, out, in(w) < 0.3f ? "squawk" : "cluck", 0.9f);
                return;
            }
            if ("flee".equals(state) && in(w) < 1.2f) { out.go(Move.RUN, spot[0], spot[1], spot[2], 0.6f); return; }
            if (w.time() >= nextThink) {
                nextThink = w.time() + 1.5f + w.random() * 5;
                if (w.random() < 0.6f && w.groundNear(w.homeX() + (w.random() - 0.5f) * RANGE * 2, w.homeZ() + (w.random() - 0.5f) * RANGE * 2, 3, spot)) to("walk", w); else to("peck", w);
            }
            if ("walk".equals(state)) { out.go(Move.WALK, spot[0], spot[1], spot[2], 1); float dx = spot[0] - w.x(), dz = spot[2] - w.z(); if (dx * dx + dz * dz < 0.3f) to("peck", w); }
            else { to("peck", w); out.sitting = false; if (w.random() < dt * 0.18f) voice(w, out, "cluck", 4); }
        }
    }

    // ================================================================== the spider
    /** It has a patch of ground. What comes onto it is rushed and bitten; what stays off it is watched. */
    public static final class Spider extends AnimalBrain {
        public static float GROUND = 5.5f, GIVE_UP = 13f, REACH = 1.9f, BITE = 13f, BITE_EVERY = 2.5f;
        /** 1.2: a strike is reared up for before it comes, and gathered from after it: it can be seen, stepped back from, and answered. */
        public static float WIND_UP = 0.5f, RECOVER = 0.75f;
        private float biteAt, strikeAt = -1, recoverUntil = -99;
        public int strikes, misses;

        @Override public void think(Senses w, Intent out, float dt) {
            Thing prey = w.prey(GIVE_UP);
            float homeD = (float) Math.sqrt((w.x() - w.homeX()) * (w.x() - w.homeX()) + (w.z() - w.homeZ()) * (w.z() - w.homeZ()));
            boolean provoked = w.time() - w.hurtAt() < 6;
            if (prey.valid && (prey.dist < GROUND || provoked || "rush".equals(state) && homeD < GIVE_UP)) {
                to("rush", w);
                out.alarmed = true;
                out.faceX = prey.x; out.faceZ = prey.z;
                if (strikeAt >= 0) {
                    // reared: it comes down where the prey was when it let go - on it, if it is still in reach
                    out.windUp = Math.max(0.02f, Math.min(1, 1 - (strikeAt - w.time()) / WIND_UP));
                    if (w.time() >= strikeAt) {
                        strikeAt = -1; recoverUntil = w.time() + RECOVER; strikes++;
                        if (prey.dist <= REACH * 1.2f) { out.attack = prey.id; out.attackKind = "bite"; out.damage = BITE; } else { misses++; out.attack = 0; out.attackKind = "miss"; }
                    }
                    return;
                }
                if (w.time() < recoverUntil) { out.go(Move.WALK, w.x() + (w.x() - prey.x), w.y(), w.z() + (w.z() - prey.z), 0.5f); return; }   // it gathers itself, giving a little ground
                if (prey.dist > REACH) { out.go(Move.RUN, prey.x, prey.y, prey.z, 1); if (in(w) < 0.4f) out.voice = "hiss"; }
                else if (w.time() >= biteAt) { biteAt = w.time() + BITE_EVERY; strikeAt = w.time() + WIND_UP; out.windUp = 0.02f; out.voice = "hiss"; }
                return;
            }
            strikeAt = -1;
            if (homeD > 2.2f) { to("return", w); out.go(Move.WALK, w.homeX(), w.homeY(), w.homeZ(), 1); return; }
            to("lurk", w);
            if (prey.valid) { out.faceX = prey.x; out.faceZ = prey.z; if (prey.dist < GROUND * 1.6f) voice(w, out, "hiss", 5); }
        }
    }

    // ================================================================== the infected dog
    /**
     * A dog that has turned. It runs with its pack and for nothing else: people are prey, the other dead are nothing
     * to it, and a dog of another pack is a rival. It comes in faster than the fastest of the dead run, mouth open,
     * and from a few metres off it leaves the ground. What its teeth close on is an arm, as often as not.
     */
    public static final class PackDog extends AnimalBrain {
        public static float SCENT = 34f, LEAP_FROM = 4.2f, LEAP_MIN = 1.6f, REACH = 1.3f, BITE = 21f, BITE_EVERY = 0.85f, LEAP_EVERY = 2.6f, PACK_KEEP = 9f;
        /** 1.2: the moment it sits back on its haunches before it comes - hackles up, ears flat, the snarl - which is when to move. */
        public static float COIL = 0.26f;
        private float biteAt, leapAt, coilUntil = -99;
        private boolean coiled;
        public int leaps;

        @Override public void think(Senses w, Intent out, float dt) {
            out.mouthOpen = true;
            Thing prey = w.prey(SCENT);
            if (prey.valid) {
                to("hunt", w);
                out.alarmed = true;
                out.faceX = prey.x; out.faceZ = prey.z;
                if (in(w) < 0.2f) out.voice = "snarl";
                if (prey.dist <= REACH) { if (w.time() >= biteAt) { biteAt = w.time() + BITE_EVERY; out.attack = prey.id; out.attackKind = "maul"; out.damage = BITE; out.voice = "snarl"; } return; }
                if (coiled) {
                    out.windUp = Math.max(0.02f, Math.min(1, 1 - (coilUntil - w.time()) / COIL));
                    if (w.time() >= coilUntil) { coiled = false; leaps++; out.windUp = 0; out.go(Move.LEAP, prey.x, prey.y, prey.z, 1); }
                    return;
                }
                if (prey.dist < LEAP_FROM && prey.dist > LEAP_MIN && w.time() >= leapAt && Math.abs(prey.y - w.y()) < 1.6f) { leapAt = w.time() + LEAP_EVERY; coiled = true; coilUntil = w.time() + COIL; out.windUp = 0.02f; out.voice = "snarl"; return; }
                out.go(Move.RUN, prey.x, prey.y, prey.z, 1);
                voice(w, out, "snarl", 2.2f);
                return;
            }
            coiled = false;
            // nothing to hunt: it keeps with the pack, and the pack roams
            Thing pk = w.pack();
            if (pk.valid && pk.dist > PACK_KEEP) { to("regroup", w); out.go(Move.RUN, pk.x, pk.y, pk.z, 0.7f); return; }
            if (w.time() >= nextThink) {
                nextThink = w.time() + 2 + w.random() * 5;
                float bx = pk.valid ? pk.x : w.homeX(), bz = pk.valid ? pk.z : w.homeZ();
                if (w.groundNear(bx + (w.random() - 0.5f) * 22, bz + (w.random() - 0.5f) * 22, 6, spot)) to("prowl", w); else to("idle", w);
            }
            if ("prowl".equals(state)) { out.go(Move.WALK, spot[0], spot[1], spot[2], 1); float dx = spot[0] - w.x(), dz = spot[2] - w.z(); if (dx * dx + dz * dz < 1.5f) to("idle", w); if (w.random() < dt * 0.1f) voice(w, out, "growl", 6); }
        }
    }

    // ================================================================== the mutated spider
    /**
     * The infected spider. Three things it does to what it hunts: from a little way off it spits (venom, a pool of
     * it, the Spewer's trick in black); nearer, it throws its whole body at it (the tackle); at arm's length it stabs
     * with its forelegs (the thrust). It goes up walls and across ceilings as readily as along a floor, and likes to
     * be over what it is after when it drops.
     */
    public static final class Brood extends AnimalBrain {
        public static float SCENT = 30f, SPIT_MIN = 5f, SPIT_MAX = 14f, SPIT_EVERY = 8f, TACKLE_FROM = 5f, TACKLE_MIN = 2.2f, TACKLE_EVERY = 6.5f, THRUST_REACH = 2.1f, THRUST = 13f, THRUST_EVERY = 2.3f, TACKLE_DAMAGE = 18f;
        /** 1.2: it was three attacks on three clocks, so they came one on top of another. Whatever it has just done, it does nothing else for this long; and a thrust is reared for like the rest. */
        public static float BETWEEN = 1.7f, THRUST_WIND = 0.42f;
        private float spitAt, tackleAt, thrustAt, windUntil, windFrom, nextAttack;
        private String wound = "";
        public int spits, tackles, thrusts, drops;

        @Override public void think(Senses w, Intent out, float dt) {
            Thing prey = w.prey(SCENT);
            if (w.time() < windUntil) {
                // committed: the wind-up of a spit or a tackle is seen and heard before it comes
                out.alarmed = true; out.mouthOpen = true;
                out.windUp = Math.max(0.02f, Math.min(1, (w.time() - windFrom) / Math.max(0.05f, windUntil - windFrom)));
                if (prey.valid) { out.faceX = prey.x; out.faceZ = prey.z; }
                return;
            }
            if (!wound.isEmpty() && prey.valid) {
                nextAttack = w.time() + BETWEEN;
                if (wound.equals("thrust")) { thrusts++; if (prey.dist <= THRUST_REACH * 1.2f) { out.attack = prey.id; out.attackKind = "thrust"; out.damage = THRUST; } else out.attackKind = "miss"; }
                else if (wound.equals("spit")) { out.attack = prey.id; out.attackKind = "spit"; out.damage = 0; spits++; out.x = prey.x; out.y = prey.y; out.z = prey.z; }
                else { out.go(Move.LEAP, prey.x, prey.y, prey.z, 1); out.attack = prey.id; out.attackKind = "tackle"; out.damage = TACKLE_DAMAGE; tackles++; }
                wound = "";
                return;
            }
            wound = "";
            if (!prey.valid) {
                // nothing to hunt: up out of the way if there is a roof to hang from, otherwise still
                to("lurk", w);
                if (w.roofed()) out.go(Move.CLING, w.x(), w.y(), w.z(), 1);
                return;
            }
            to("hunt", w);
            out.alarmed = true;
            out.faceX = prey.x; out.faceZ = prey.z;
            float d = prey.dist;
            boolean ready = w.time() >= nextAttack;
            if (d <= THRUST_REACH) { if (ready && w.time() >= thrustAt) { thrustAt = w.time() + THRUST_EVERY; windFrom = w.time(); windUntil = w.time() + THRUST_WIND; wound = "thrust"; out.windUp = 0.02f; out.voice = "hiss"; } return; }
            if (ready && d > SPIT_MIN && d < SPIT_MAX && w.time() >= spitAt) { spitAt = w.time() + SPIT_EVERY; windFrom = w.time(); windUntil = w.time() + 0.8f; wound = "spit"; out.voice = "hiss"; out.mouthOpen = true; return; }
            if (ready && d > TACKLE_MIN && d < TACKLE_FROM && w.time() >= tackleAt && Math.abs(prey.y - w.y()) < 5f) { tackleAt = w.time() + TACKLE_EVERY; windFrom = w.time(); windUntil = w.time() + 0.5f; wound = "tackle"; out.voice = "hiss"; return; }
            // closing: along the ceiling when there is one (so as to come down on it), otherwise across the floor
            if (w.roofed() && d > 3.5f) { out.go(Move.CLING, prey.x, prey.y, prey.z, 1); }
            else { if ("ceiling".equals(lastSurface)) drops++; out.go(Move.RUN, prey.x, prey.y, prey.z, 1); }
            lastSurface = out.move == Move.CLING ? "ceiling" : "floor";
        }
        private String lastSurface = "floor";
    }
}
