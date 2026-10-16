package astratto.audio.gun;

import astratto.audio.gun.AcousticEnvironment.Acoustics;
import astratto.audio.gun.AcousticEnvironment.Geometry;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * The game's one door into gun audio (0.5, brief 17): firing code reports a round - who, with what, where, at what
 * rate of fire - and this turns it into a {@link ShotEvent} on the mixer's clock. It owns the profile library, the
 * scheduler and the gun bus, and the small amount of state that keeps consecutive shots from sounding the same.
 *
 * All of this runs on the game thread; the only thing that crosses to the audio thread is the finished event.
 */
public final class GunAudio {
    public final AudioMixer mixer = new AudioMixer();
    public final ShotEventScheduler scheduler = new ShotEventScheduler(AudioMixer.RATE);
    public WeaponAudioProfile.Library library = new WeaponAudioProfile.Library();
    /** Rays cast for the listener's own weapon, for a nearby shooter and for a far one. */
    public int raysLocal = 14, raysNear = 10, raysFar = 6;

    /** Per shooter: the last two takes used, so a take is never repeated back to back (nor A-B-A when there are three). */
    private static final class Bag { int last = -1, prev = -1, lastFar = -1, lastBody = -1; }
    private final Map<Integer, Bag> bags = new HashMap<>();
    private long serial;
    /** Shots posted, and the last event built (diagnostics and tests). */
    public int posted;
    public ShotEvent last;

    public void load(Path assets, Map<String, Object> packWeapons) {
        library = WeaponAudioProfile.load(assets, packWeapons);
        mixer.layers.handling = library.handling;
        calibrate();
        for (String p : library.problems) System.err.println("[Audio] guns: " + p);
        System.out.println("[Audio] guns: " + library.profiles.size() + " weapon profiles, " + library.sets.size() + " recorded sets, " + library.handling.size() + " handling samples, " + (library.bytes >> 20) + " MB decoded");
    }

    /** An open field with ground under the muzzle: the reference the calibration listens in. */
    private static final AcousticEnvironment.Geometry FIELD = new AcousticEnvironment.Geometry() {
        public float ray(float ox, float oy, float oz, float dx, float dy, float dz, float max, float[] hit) { if (dy < -0.9f) { hit[0] = ox; hit[1] = oy - 1.6f; hit[2] = oz; hit[3] = 0.5f; return 1.6f; } return -1; }
        public float openness(float ax, float ay, float az, float bx, float by, float bz) { return 1; }
    };

    /** Loudest 100 ms (RMS) of one of the shooter's own rounds as the bus actually renders it, compressor and limiter included. */
    public static float heard(WeaponAudioProfile.Library lib, String weaponId) {
        // 0.8: a weapon is several takes, and each round is varied a little: it is judged on six rounds, not on one
        float sum = 0;
        int n = 0;
        for (int k = 0; k < HEARD_ROUNDS; k++) { float h = heardOnce(lib, weaponId, k * 5); if (h > 0) { sum += h; n++; } }
        return n == 0 ? 0 : sum / n;
    }

    public static int HEARD_ROUNDS = 6;

    private static float heardOnce(WeaponAudioProfile.Library lib, String weaponId, int serial) {
        GunAudio g = new GunAudio();
        g.serial = serial;
        g.library = lib;
        g.mixer.layers.handling = lib.handling;
        int block = 1024, blocks = 44, win = AudioMixer.RATE / 10;
        float[] scratch = new float[block * 2], all = new float[block * blocks];
        if (g.shot(FIELD, weaponId, 1, 60, 0, 1.6f, 0, 0, -1, false, true, 0, 1.6f, 0, 0) == null) return 0;
        for (int b = 0; b < blocks; b++) {
            java.util.Arrays.fill(scratch, 0f);
            g.mixer.mix(scratch, block, 1f);
            for (int i = 0; i < block; i++) all[b * block + i] = (scratch[i * 2] + scratch[i * 2 + 1]) * 0.5f;
        }
        double acc = 0, best = 0;
        for (int i = 0; i < all.length; i++) {
            acc += all[i] * all[i];
            if (i >= win) acc -= all[i - win] * all[i - win];
            if (acc > best) best = acc;
        }
        return (float) Math.sqrt(best / win);
    }

    /** What each weapon measured after calibration (weapon id -> loudest 100 ms RMS at the shooter), for the log and the gates. */
    public final Map<String, Float> heardLevel = new HashMap<>();

    /**
     * 0.6 loudness calibration. The loader scales every profile toward one level from its raw recordings; this pass
     * closes the loop by listening to the result: each weapon fires one round in an open field through a private
     * copy of the bus, and its gain is trimmed until what comes out of the limiter sits at the class level set in
     * content/weapon_audio.json ("loudness.heardRms" times the class factor). Three passes; a weapon the limiter
     * will not let any higher simply stops rising.
     */
    private void calibrate() {
        float target = library.heardRms;
        if (target <= 0) return;
        for (WeaponAudioProfile p : library.profiles.values()) {
            if (p.set == null) continue;
            float want = target * library.classFactor.getOrDefault(p.loudClass, 1f), got = 0;
            for (int pass = 0; pass < 5; pass++) {
                got = heard(library, p.weaponId);
                if (got < 1e-5f) break;
                float k = (float) Math.pow(want / got, 0.85);
                if (Math.abs(k - 1) < 0.012f) break;
                p.gain = Math.max(p.gain * 0.5f, Math.min(p.gain * 2.2f, p.gain * k));
            }
            heardLevel.put(p.weaponId, heard(library, p.weaponId));
            if (System.getenv("ASTRATTO_GUNDEBUG") != null) System.out.println("[gundebug] " + p.weaponId + " want " + want + " got " + heardLevel.get(p.weaponId) + " gain " + p.gain + " norm " + p.norm + " measured " + p.measured + " punch " + p.punch.size());
        }
    }

    /** True when this weapon is rendered by the layered system (it has a profile and its recordings are loaded). */
    public boolean handles(String weaponId) { return library.has(weaponId); }

    private static int pick(long seed, int n, int avoidA, int avoidB) {
        if (n <= 1) return 0;
        int k = (int) Math.floorMod(seed >>> 17, (long) n);
        for (int i = 0; i < n; i++) {
            int c = (k + i) % n;
            if (c == avoidA) continue;
            if (n > 2 && c == avoidB) continue;
            return c;
        }
        return k;
    }

    /**
     * One round fired. Returns the event posted, or null when the weapon has no usable profile (the caller then
     * keeps whatever it did before). The listener position is the engine's.
     */
    public ShotEvent shot(Geometry g, String weaponId, int shooterId, float rpm, float x, float y, float z, float dirX, float dirZ, boolean suppressed, boolean local,
                          float lx, float ly, float lz, float lyaw) {
        WeaponAudioProfile p = library.profiles.get(weaponId);
        if (p == null || p.set == null || !p.set.usable()) return null;
        float dx = x - lx, dy = y - ly, dz = z - lz, d2 = dx * dx + dy * dy + dz * dz;
        if (d2 > p.maxDistance * p.maxDistance) return null;                       // out of earshot: nothing to render
        Acoustics a = mixer.environment.analyse(g, x, y, z, dirX, dirZ, lx, ly, lz, lyaw, p.refDistance, local ? raysLocal : d2 < 60 * 60 ? raysNear : raysFar, AudioMixer.RATE);
        if (local) { a.pan = 0; a.travel = 0; a.distance = 0; a.directGain = 1; a.directLowpass = 0; }
        long[] at = scheduler.schedule(shooterId, weaponId, rpm, mixer.clock());
        long seed = (++serial) * 0x9E3779B97F4A7C15L ^ ((long) shooterId << 32) ^ weaponId.hashCode();
        float[] v = ShotEvent.vary(seed, p.varRate, p.varDb);
        Bag b = bags.computeIfAbsent(shooterId, k -> new Bag());
        int take = pick(seed, p.set.takes(), b.last, b.prev);
        int far = pick(seed >>> 7, Math.max(1, p.set.distant.size()), b.lastFar, -1);
        // the low band of one take under the high band of another is still this weapon, and doubles the shots it can make
        int body = pick(seed >>> 23, p.set.takes(), b.lastBody, -1);
        b.prev = b.last; b.last = take; b.lastFar = far; b.lastBody = body;
        ShotEvent e = new ShotEvent(weaponId, p, shooterId, (int) at[1], at[0], x, y, z, dirX, dirZ, suppressed, local, seed, take, body, far, v[0], v[1], a);
        if (!mixer.post(e)) return null;
        posted++;
        last = e;
        return e;
    }

    /** The handling sample a reload cue maps to for a weapon family (rifle, pistol, shotgun), or null. */
    public String cue(String family, String cue) {
        Map<String, String> m = library.cues.get(family);
        String s = m == null ? null : m.get(cue);
        return s != null && library.handling.containsKey(s) ? s : null;
    }

    public void forget() { bags.clear(); scheduler.clear(); }
}
