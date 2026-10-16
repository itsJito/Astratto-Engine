package astratto.weather;

import astratto.fx.ParticleSystem;

/**
 * The Astratto weather system (1.0): fog, rain, heavy storm with thunder and lightning, and snow, as one simulated
 * state that everything else reads.
 *
 * Weather here is not a switch. A {@link Kind} is a set of targets - how much rain, how much snow, how thick the air,
 * how strong the wind, how much cloud - and the state moves toward the targets of the kind that has been asked for over
 * {@link #transition} seconds, so rain comes on as a few drops and builds, a storm blows itself out, snow thins before
 * it stops. On top of the targets things build up and wear off at their own pace: the ground gets wet in the rain and
 * dries after it, snow settles and melts, the wind gusts.
 *
 * What it produces:
 * <ul>
 * <li>numbers a renderer can use directly: {@link #rain}, {@link #snow}, {@link #fog}, {@link #overcast},
 *     {@link #wetness}, {@link #snowCover}, {@link #lightning}, the wind ({@link #windX}, {@link #windZ}, {@link #gust});</li>
 * <li>precipitation, as particles in the Astratto particle system, in a volume that follows the camera - each drop and
 *     flake stops on the first roof or road beneath it, so it rains outside a window and not inside the room;</li>
 * <li>lightning: a forked bolt from the cloud base to the ground somewhere round the camera, a flash that lights the
 *     sky and everything under it, and thunder that arrives when sound would (three seconds a kilometre).</li>
 * </ul>
 * The system knows nothing about the game; the world is the particle system's {@link ParticleSystem.Terrain}.
 */
public final class Weather {
    public enum Kind {
        //          rain  snow  fog   wind  cloud
        CLEAR(0.00f, 0.00f, 0.00f, 0.18f, 0.00f),
        FOG(0.00f, 0.00f, 1.00f, 0.06f, 0.55f),
        RAIN(0.55f, 0.00f, 0.30f, 0.38f, 0.80f),
        STORM(1.00f, 0.00f, 0.50f, 1.00f, 1.00f),
        SNOW(0.00f, 0.80f, 0.45f, 0.28f, 0.85f);

        public final float rain, snow, fog, wind, cloud;
        Kind(float rain, float snow, float fog, float wind, float cloud) { this.rain = rain; this.snow = snow; this.fog = fog; this.wind = wind; this.cloud = cloud; }

        public static Kind of(String name) {
            if (name != null) for (Kind k : values()) if (k.name().equalsIgnoreCase(name.trim())) return k;
            return CLEAR;
        }
    }

    /** What the weather tells whoever plays sound. */
    public interface Listener {
        /** Thunder has reached the listener: how far away the strike was (m) and how loud it should be, 0..1. */
        void thunder(float distance, float gain);
        /** A bolt has come down here (the game may set fire to it, or frighten somebody). */
        default void strike(float x, float y, float z) {}
    }

    private Kind kind = Kind.CLEAR;
    /** Seconds a change of weather takes. */
    public float transition = 40f;
    // ---- the state, eased
    public float rain, snow, fog, overcast, wind;
    /** How wet open ground is, 0..1: rises in the rain in about a minute, dries over several. */
    public float wetness;
    /** How much snow lies, 0..1: settles over a few minutes of snowfall, melts slowly when it stops (fast in the rain). */
    public float snowCover;
    /** The lightning's light this instant, 0..1 (several quick pulses a strike). */
    public float lightning;
    /** The wind over the ground, m/s, gusts included; and the gust alone, -1..1, for things that sway. */
    public float windX, windZ, gust;
    /** Seconds the system has run: the phase of everything that sways with it. */
    public float time;
    public Listener listener;
    /** How many drops or flakes a second at full strength, and how far round the camera they are made. */
    public float rainRate = 2600f, snowRate = 900f, radius = 24f;
    public float density = 1f;
    /** Counters (gates). */
    public int strikes, thunders;
    public long drops, flakes;

    private float windAngle = 0.6f, gustPhase, rainAcc, snowAcc, nextStrike = 8f;
    private final java.util.Random rnd = new java.util.Random(23);
    private final float[] pulses = new float[4];
    private int pulseCount;
    private float pulseT;
    private final java.util.ArrayList<float[]> pendingThunder = new java.util.ArrayList<>();   // {seconds left, distance, gain}
    /** The last bolt, for whoever wants to look at it: packed x, y, z triples of its points (main channel first). */
    public final float[] bolt = new float[3 * 200];
    public int boltPoints;
    public float boltAge = 99f;

    public Kind kind() { return kind; }

    /** Asks for a kind of weather; it arrives over {@link #transition} seconds. */
    public void set(Kind k) { if (k != null) kind = k; }

    /** Puts the weather where it is going at once (loading a save, a test, the console's "now"). */
    public void snap() {
        rain = kind.rain; snow = kind.snow; fog = kind.fog; overcast = kind.cloud; wind = kind.wind;
        wetness = kind.rain > 0 ? 1 : 0; snowCover = kind.snow > 0 ? 0.85f : 0;
    }

    private static float toward(float v, float target, float step) { return v < target ? Math.min(target, v + step) : Math.max(target, v - step); }

    public void update(float dt, float camX, float camY, float camZ, ParticleSystem ps, ParticleSystem.Terrain t) {
        if (dt <= 0) return;
        time += dt;
        float step = dt / Math.max(1f, transition);
        rain = toward(rain, kind.rain, step); snow = toward(snow, kind.snow, step); fog = toward(fog, kind.fog, step);
        overcast = toward(overcast, kind.cloud, step); wind = toward(wind, kind.wind, step * 1.5f);
        // what builds up and wears off
        wetness = rain > 0.02f ? Math.min(1, wetness + dt * rain / 45f) : Math.max(0, wetness - dt / 420f);
        if (snow > 0.02f) snowCover = Math.min(1, snowCover + dt * snow / 150f);
        else snowCover = Math.max(0, snowCover - dt * (1 / 600f + rain / 60f));
        // the wind: a direction that wanders, a strength that gusts
        windAngle += dt * 0.02f * (float) Math.sin(time * 0.013f + 1.7f);
        gustPhase += dt * (0.35f + wind * 0.9f);
        gust = (float) (Math.sin(gustPhase) * 0.55 + Math.sin(gustPhase * 2.31 + 1.3) * 0.3 + Math.sin(gustPhase * 5.7 + 0.4) * 0.15);
        float speed = wind * 9.5f * (1 + 0.45f * gust);
        windX = (float) Math.cos(windAngle) * speed; windZ = (float) Math.sin(windAngle) * speed;
        if (ps != null) { ps.windX = windX; ps.windZ = windZ; }

        if (ps != null && t != null) precipitation(dt, camX, camY, camZ, ps, t);
        lightning(dt, camX, camY, camZ, ps, t);
    }

    private void precipitation(float dt, float camX, float camY, float camZ, ParticleSystem ps, ParticleSystem.Terrain t) {
        if (rain > 0.01f) {
            rainAcc += dt * rainRate * rain * density;
            float fall = 17f + 9f * rain;
            int n = (int) rainAcc;
            rainAcc -= n;
            for (int i = 0; i < n; i++) {
                // uniform over the disc; the wind leans the fall, so the column is made a little upwind of where it will land
                float a = rnd.nextFloat() * 6.2832f, rr = (float) Math.sqrt(rnd.nextFloat()) * radius;
                float y = camY + 7f + rnd.nextFloat() * 11f;
                float lead = (y - camY) / fall;
                float x = camX + (float) Math.cos(a) * rr - windX * 0.9f * lead, z = camZ + (float) Math.sin(a) * rr - windZ * 0.9f * lead;
                if (t.top((int) Math.floor(x), (int) Math.floor(z)) >= y - 1) continue;         // it would start inside a building
                ps.raindrop(x, y, z, fall, 0.34f + 0.3f * rain + rnd.nextFloat() * 0.12f, 0.34f + 0.3f * rain);
                drops++;
            }
        } else rainAcc = 0;
        if (snow > 0.01f) {
            snowAcc += dt * snowRate * snow * density;
            int n = (int) snowAcc;
            snowAcc -= n;
            for (int i = 0; i < n; i++) {
                float a = rnd.nextFloat() * 6.2832f, rr = (float) Math.sqrt(rnd.nextFloat()) * radius;
                float y = camY + 2f + rnd.nextFloat() * 12f;
                float x = camX + (float) Math.cos(a) * rr, z = camZ + (float) Math.sin(a) * rr;
                if (t.top((int) Math.floor(x), (int) Math.floor(z)) >= y - 1) continue;
                ps.snowflake(x, y, z, 1.1f + rnd.nextFloat() * 0.9f, 0.012f + rnd.nextFloat() * 0.016f);
                flakes++;
            }
        } else snowAcc = 0;
    }

    private void lightning(float dt, float camX, float camY, float camZ, ParticleSystem ps, ParticleSystem.Terrain t) {
        boltAge += dt;
        // thunder on its way
        for (int i = pendingThunder.size() - 1; i >= 0; i--) {
            float[] th = pendingThunder.get(i);
            th[0] -= dt;
            if (th[0] > 0) continue;
            pendingThunder.remove(i);
            thunders++;
            if (listener != null) listener.thunder(th[1], th[2]);
        }
        // the flash: a strike is two to four pulses a few hundredths of a second apart
        if (pulseCount > 0) {
            pulseT += dt;
            float v = 0;
            for (int i = 0; i < pulseCount; i++) { float d = pulseT - pulses[i]; if (d >= 0) v = Math.max(v, (float) Math.exp(-d * 13f) * (i == 0 ? 1f : 0.7f)); }
            lightning = v;
            if (pulseT > pulses[pulseCount - 1] + 0.6f) { pulseCount = 0; lightning = 0; }
        } else lightning = 0;
        // only a storm strikes; heavy rain on its way to being one rumbles now and then
        float storminess = Math.max(0, Math.min(1, (rain - 0.7f) / 0.3f)) * Math.max(0, Math.min(1, (wind - 0.5f) / 0.4f));
        if (storminess <= 0.05f) { nextStrike = Math.max(nextStrike, 4f); return; }
        nextStrike -= dt * storminess;
        if (nextStrike > 0) return;
        nextStrike = 5f + rnd.nextFloat() * 15f;
        strike(camX, camY, camZ, 70f + rnd.nextFloat() * 520f, rnd.nextFloat() * 6.2832f, ps, t);
    }

    /** A strike at a distance and bearing from the camera (the console and the gates call this directly). */
    public void strike(float camX, float camY, float camZ, float distance, float bearing, ParticleSystem ps, ParticleSystem.Terrain t) {
        strikes++;
        float gx = camX + (float) Math.cos(bearing) * distance, gz = camZ + (float) Math.sin(bearing) * distance;
        float gy = t != null ? t.top((int) Math.floor(gx), (int) Math.floor(gz)) + 1 : camY - 1.6f;
        float topY = gy + 95f + rnd.nextFloat() * 30f;
        // the channel: midpoint displacement between the cloud and the ground, then two or three forks off it
        boltPoints = 0;
        float sx = gx + (rnd.nextFloat() - 0.5f) * 50f, sz = gz + (rnd.nextFloat() - 0.5f) * 50f;
        int main = channel(sx, topY, sz, gx, gy, gz, 108, 3.6f);
        int forks = 2 + rnd.nextInt(3);
        for (int f = 0; f < forks && boltPoints < 180; f++) {
            int from = 8 + rnd.nextInt(Math.max(1, main - 30));
            float fx = bolt[from * 3], fy = bolt[from * 3 + 1], fz = bolt[from * 3 + 2];
            float len = 14f + rnd.nextFloat() * 30f, a = rnd.nextFloat() * 6.2832f;
            channel(fx, fy, fz, fx + (float) Math.cos(a) * len, fy - len * (0.5f + rnd.nextFloat() * 0.6f), fz + (float) Math.sin(a) * len, 16, 1.6f);
        }
        boltAge = 0;
        pulseCount = 2 + rnd.nextInt(3);
        pulseT = 0;
        float at = 0;
        for (int i = 0; i < pulseCount; i++) { pulses[i] = at; at += 0.05f + rnd.nextFloat() * 0.11f; }
        if (ps != null) {
            // the bolt itself: a string of hard white lights that outlasts the first pulse, then a fainter after-image
            float bright = Math.max(0.25f, 1 - distance / 900f), sz2 = 0.35f + distance * 0.004f;
            for (int i = 0; i < boltPoints; i++) {
                boolean trunk = i < main;
                float px = bolt[i * 3], py = bolt[i * 3 + 1], pz = bolt[i * 3 + 2];
                ps.spawn(ParticleSystem.GLOW, 0, px, py, pz, 0, 0, 0, trunk ? 0.16f : 0.1f, sz2 * (trunk ? 1.25f : 0.7f), 0, 26f * bright, 28f * bright, 34f * bright, 1f);
                if (trunk && (i & 3) == 0) ps.spawn(ParticleSystem.GLOW, 0, px, py, pz, 0, 0, 0, 0.34f, sz2 * 4f, 0, 1.6f * bright, 1.9f * bright, 3.0f * bright, 0.4f);
            }
            if (distance < 160f) { ps.sparks(gx, gy + 0.2f, gz, 0, 1, 0, 30, 12f); ps.shockwave(gx, gy, gz, 5f, 0.4f, 0xC8C4BC, 0.4f); }
        }
        float gain = Math.max(0.12f, Math.min(1f, 1.15f - distance / 650f));
        pendingThunder.add(new float[]{distance / 343f, distance, gain});
        if (listener != null) listener.strike(gx, gy, gz);
    }

    /** Lays a jagged line of points from a to b into the bolt buffer; returns how many points the buffer holds afterwards. */
    private int channel(float ax, float ay, float az, float bx, float by, float bz, int segments, float jag) {
        float ox = 0, oz = 0;
        for (int i = 0; i <= segments && boltPoints < bolt.length / 3; i++) {
            float k = i / (float) segments;
            // the wander is largest in the middle and nothing at the ends, so the bolt still arrives where it was sent
            float env = (float) Math.sin(k * Math.PI);
            ox += (rnd.nextFloat() - 0.5f) * jag; oz += (rnd.nextFloat() - 0.5f) * jag;
            ox *= 0.86f; oz *= 0.86f;
            int p = boltPoints++ * 3;
            bolt[p] = ax + (bx - ax) * k + ox * env; bolt[p + 1] = ay + (by - ay) * k; bolt[p + 2] = az + (bz - az) * k + oz * env;
        }
        return boltPoints;
    }

    /** How much nearer the fog bank stands for this weather, 1 (unmoved) down to about 0.45 in thick fog or a blizzard. */
    public float visibility() { return 1 - 0.55f * Math.max(fog, Math.max(snow * 0.8f, rain * 0.45f)); }

    /** How much of the sun gets through the cloud, 0.25..1. */
    public float sunlight() { return 1 - 0.75f * overcast; }
}
