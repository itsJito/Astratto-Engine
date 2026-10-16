package astratto.audio.gun;

/**
 * How a shot reaches the listener (0.5, brief 17 phase 6), worked out on the game thread from the world's real
 * geometry at the moment of firing and handed to the audio thread as an immutable snapshot.
 *
 *   direct path        inverse-distance attenuation, air absorption that rolls the top off with distance, loss
 *                      through whatever solid stands between muzzle and ear, and a little less top from behind the gun
 *   early reflections  a bounded fan of rays from the muzzle; each surface found gives one reflected path, delayed
 *                      by (reflected - direct) / 343 seconds, attenuated by path length and the surface's material,
 *                      and placed where the surface is
 *   enclosure          how many of those rays stop close by, and whether there is a roof: outdoors the recorded
 *                      tail is the decay; indoors it is replaced by the room
 *   late field         decay time, level and damping for the one shared reverb, from the space round the listener
 *
 * When there is no geometry to ask (menus, tests without a world) a plain medium room is used and reported as such.
 */
public final class AcousticEnvironment {
    public static final float SPEED_OF_SOUND = 343f;
    public static final int MAX_REFLECTIONS = 6;

    /** What the model needs from the world. */
    public interface Geometry {
        /** Distance along a ray to the first solid, or -1 within max. hit receives {x, y, z, absorption 0..1}. */
        float ray(float ox, float oy, float oz, float dx, float dy, float dz, float max, float[] hit);
        /** 0..1: how open the straight line between two points is (1 = nothing in the way). */
        float openness(float ax, float ay, float az, float bx, float by, float bz);
    }

    /** The snapshot a shot carries. */
    public static final class Acoustics {
        public float distance, directGain = 1, directLowpass, pan, travel;
        /** 0 open sky .. 1 fully enclosed, at the muzzle. */
        public float indoor;
        public float reverbSend = 1;
        public int reflections;
        public final float[] delay = new float[MAX_REFLECTIONS], gain = new float[MAX_REFLECTIONS], rpan = new float[MAX_REFLECTIONS], lowpass = new float[MAX_REFLECTIONS];
        public boolean fallback;
    }

    /** Targets for the shared late reverb, read by the audio thread and smoothed there. */
    public volatile float roomDecay = 1.1f, roomWet = 0.22f, roomDamp = 0.45f, roomPredelay = 0.02f;
    public volatile float listenerIndoor;
    public volatile boolean fallbackRoom = true;

    private static final float[][] FAN = fan();
    private final float[] hit = new float[4];

    /** Fourteen directions: a ring of eight, four raised, straight up and straight down. */
    private static float[][] fan() {
        float[][] d = new float[14][3];
        for (int i = 0; i < 8; i++) { double a = i * Math.PI / 4; d[i][0] = (float) Math.cos(a); d[i][2] = (float) Math.sin(a); }
        for (int i = 0; i < 4; i++) { double a = i * Math.PI / 2 + Math.PI / 4; d[8 + i][0] = (float) (Math.cos(a) * 0.707); d[8 + i][1] = 0.707f; d[8 + i][2] = (float) (Math.sin(a) * 0.707); }
        d[12][1] = 1; d[13][1] = -1;
        return d;
    }

    /** Low-pass coefficient (0 = open, toward 1 = closed) for a cutoff in Hz at the engine rate. */
    public static float coefficient(float hz, int rate) { return hz >= rate * 0.45f ? 0 : (float) Math.exp(-2 * Math.PI * Math.max(40f, hz) / rate); }

    /**
     * Analyses one shot. {@code rays}: how many of the fan to cast (the listener's own weapon gets all of it; far
     * shots fewer). Everything is bounded: at most {@code rays} casts and as many openness checks.
     */
    public Acoustics analyse(Geometry g, float sx, float sy, float sz, float dirX, float dirZ, float lx, float ly, float lz, float lyaw, float ref, int rays, int rate) {
        Acoustics a = new Acoustics();
        float dx = sx - lx, dy = sy - ly, dz = sz - lz, d = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        a.distance = d;
        a.travel = d / SPEED_OF_SOUND;
        float rx = (float) Math.cos(lyaw), rz = (float) -Math.sin(lyaw), flat = (float) Math.sqrt(dx * dx + dz * dz);
        a.pan = flat < 0.5f ? 0 : (dx * rx + dz * rz) / flat * Math.min(1, d / 3f);
        a.directGain = ref / (ref + d);
        // air: the top goes first; by a few hundred metres a shot is a thud
        float cutoff = 18000f / (1 + d / 55f);
        if (g == null) {
            a.fallback = true;
            a.directLowpass = coefficient(cutoff, rate);
            a.indoor = 0.3f;
            return a;
        }
        if (d > 1.5f) {
            float open = g.openness(sx, sy, sz, lx, ly, lz);
            a.directGain *= 0.22f + 0.78f * open;
            cutoff *= 0.15f + 0.85f * open * open;
            // behind the weapon the blast is duller
            float toward = flat < 0.5f ? 1 : -(dx * dirX + dz * dirZ) / flat;
            if (toward < 0) { a.directGain *= 1 + 0.22f * toward; cutoff *= 1 + 0.35f * toward; }
        }
        a.directLowpass = coefficient(cutoff, rate);
        // ---- the fan: reflections and enclosure
        int n = Math.min(FAN.length, Math.max(4, rays));
        float close = 0, total = 0, roof = 0;
        for (int i = 0; i < n; i++) {
            int k = n >= FAN.length ? i : i < n - 2 ? (i * 8 / Math.max(1, n - 2)) % 8 : 12 + (i - (n - 2));
            float[] dir = FAN[k];
            float t = g.ray(sx, sy, sz, dir[0], dir[1], dir[2], 60, hit);
            float w = k == 12 ? 3 : 1;
            total += w;
            if (t < 0) continue;
            if (t < 14) close += w * (1 - t / 28f);
            if (k == 12 && t < 9) roof = 1;
            if (k == 13 || t < 0.7f) continue;                               // the ground under the gun is part of the shot, not an echo of it
            float hx = hit[0], hy = hit[1], hz = hit[2];
            float back = (float) Math.sqrt((hx - lx) * (hx - lx) + (hy - ly) * (hy - ly) + (hz - lz) * (hz - lz));
            float path = t + back, extra = path - d;
            if (extra < 0.4f) continue;                                      // too close to the direct sound to be heard apart from it
            float delay = Math.max(0.002f, Math.min(0.5f, extra / SPEED_OF_SOUND));
            float seen = g.openness(hx - dir[0] * 0.4f, hy - dir[1] * 0.4f, hz - dir[2] * 0.4f, lx, ly, lz);
            if (seen < 0.45f) continue;
            float absorb = hit[3];
            float gain = (1 - absorb) * 0.62f * (ref / (ref + path)) * seen;
            if (gain < 0.004f) continue;
            float pdx = hx - lx, pdz = hz - lz, pl = (float) Math.sqrt(pdx * pdx + pdz * pdz);
            float pan = pl < 0.5f ? 0 : (pdx * rx + pdz * rz) / pl;
            float lp = coefficient(9000f * (1 - absorb * 0.7f) / (1 + path / 70f), rate);
            insert(a, delay, gain, pan, lp);
        }
        a.indoor = Math.max(0, Math.min(1, close / Math.max(1, total) * 1.25f * (0.45f + 0.55f * roof)));
        a.reverbSend = 0.55f + 0.45f * a.indoor;
        return a;
    }

    /** Keeps the strongest reflections. */
    private static void insert(Acoustics a, float delay, float gain, float pan, float lp) {
        int slot = a.reflections;
        if (slot >= MAX_REFLECTIONS) {
            slot = 0;
            for (int i = 1; i < MAX_REFLECTIONS; i++) if (a.gain[i] < a.gain[slot]) slot = i;
            if (a.gain[slot] >= gain) return;
        } else a.reflections++;
        a.delay[slot] = delay; a.gain[slot] = gain; a.rpan[slot] = pan; a.lowpass[slot] = lp;
    }

    /**
     * The space round the listener, for the shared late field. Called a few times a second; the audio thread glides
     * to whatever this sets, so walking through a doorway changes the room without a step in the sound.
     */
    public void listenerRoom(Geometry g, float lx, float ly, float lz) {
        if (g == null) { fallbackRoom = true; roomDecay = 1.1f; roomWet = 0.22f; roomDamp = 0.45f; roomPredelay = 0.02f; listenerIndoor = 0.3f; return; }
        fallbackRoom = false;
        float close = 0, total = 0, roof = 0, sum = 0, absorb = 0;
        int hits = 0;
        for (int k = 0; k < FAN.length; k++) {
            float[] dir = FAN[k];
            float t = g.ray(lx, ly, lz, dir[0], dir[1], dir[2], 60, hit);
            float w = k == 12 ? 3 : 1;
            total += w;
            if (t < 0) continue;
            if (k != 13) { sum += Math.min(t, 40); hits++; absorb += hit[3]; }
            if (t < 14) close += w * (1 - t / 28f);
            if (k == 12 && t < 9) roof = 1;
        }
        float indoor = Math.max(0, Math.min(1, close / Math.max(1, total) * 1.25f * (0.45f + 0.55f * roof)));
        float mean = hits == 0 ? 40 : sum / hits, soft = hits == 0 ? 0.3f : absorb / hits;
        listenerIndoor = indoor;
        // a room rings for a time set by its size and its surfaces; the open air barely rings, a street answers late and thin
        float inDecay = (0.30f + 0.055f * mean) * (1.25f - soft), outDecay = 1.5f + 0.5f * Math.min(1, hits / 10f);
        roomDecay = inDecay * indoor + outDecay * (1 - indoor);
        roomWet = 0.42f * indoor + (0.07f + 0.09f * Math.min(1, hits / 10f)) * (1 - indoor);
        roomDamp = 0.30f + 0.4f * soft + 0.2f * (1 - indoor);
        roomPredelay = Math.min(0.09f, 0.006f + mean / SPEED_OF_SOUND);
    }
}
