package astratto.audio.gun;

import astratto.util.Json;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Everything the audio system knows about one weapon (0.5, brief 17 phase 4): which recorded set it fires, how the
 * set's layers are balanced, how far its per-shot variation may go, and what its action does after each round.
 * Profiles come from {@code content/weapon_audio.json}; recorded sets from {@code assets/audio/weapons} (built by
 * {@code gradlew prepareAudio}). Both are loaded and decoded once, before the first shot - nothing here is touched
 * by the audio thread except to read sample arrays.
 */
public final class WeaponAudioProfile {
    /** One buffer of mono samples and the rate it was stored at (bodies and distant layers are stored band-limited at a low rate). */
    public static final class Sample {
        public final float[] data; public final int rate; public final String name;
        Sample(float[] d, int r, String n) { data = d; rate = r; name = n; }
        public float seconds() { return data.length / (float) rate; }
    }

    /** The recorded ingredients of one firearm: takes of each layer, index-aligned (transient k, body k and tail k are one shot). */
    public static final class SampleSet {
        public final String id;
        public final List<Sample> transient_ = new ArrayList<>(), body = new ArrayList<>(), tail = new ArrayList<>(), distant = new ArrayList<>();
        SampleSet(String id) { this.id = id; }
        public int takes() { return Math.min(transient_.size(), Math.min(body.size(), tail.size())); }
        public boolean usable() { return takes() > 0; }
    }

    /** A mechanical event after the shot: which handling sample, how long after, how loud, at what rate. */
    public record Mech(String sample, float at, float gain, float rate) {}

    public String weaponId = "", setId = "", analogue = "", match = "", action = "", loudClass = "";
    /** The gain the loader applied to bring this weapon to its class level, and the level it measured before doing so. */
    public float norm = 1, measured;

    /**
     * 0.6 loudness. The recordings come from different microphones, distances and firearms, and 0.5 balanced them by
     * hand: the loudest 100 ms of the quietest weapon measured 11.6 dB under the loudest, and shotguns sat level with
     * handguns. Each profile is now measured as the mixer will sum it (transient, body and tail at the profile's own
     * gains) and scaled to one target, then offset by its class - so a shotgun is louder than a pistol because the
     * table says so, not because of who recorded it. Targets and class offsets are in content/weapon_audio.json.
     */
    static float loudness(WeaponAudioProfile p) {
        if (p.set == null || !p.set.usable()) return 0;
        double sum = 0;
        int n = p.set.takes(), rate = 44100, len = rate * 6 / 10, win = rate / 10;
        float[] mix = new float[len];
        for (int k = 0; k < n; k++) {
            java.util.Arrays.fill(mix, 0f);
            // the three layers start together and are summed in time, each resampled from the rate it is stored at
            float under = p.punch.isEmpty() ? 1 : p.punchUnder;
            add(mix, rate, p.set.transient_.get(k), p.transientGain * under);
            add(mix, rate, p.set.body.get(k), p.bodyGain * under);
            add(mix, rate, p.set.tail.get(k), p.tailGain * under);
            if (!p.punch.isEmpty()) add(mix, rate, p.punch.get(k % p.punch.size()), p.punchGain);
            double acc = 0, best = 0;
            for (int i = 0; i < len; i++) {
                acc += mix[i] * mix[i];
                if (i >= win) acc -= mix[i - win] * mix[i - win];
                if (acc > best) best = acc;
            }
            sum += Math.sqrt(best / win);
        }
        return (float) (sum / n);
    }

    /** Adds a layer into a mix at the output rate; the profile gain of the layer is applied as it goes in. */
    private static void add(float[] mix, int rate, Sample s, float gain) {
        double step = s.rate / (double) rate;
        for (int i = 0; i < mix.length; i++) {
            double pos = i * step;
            int a = (int) pos;
            if (a + 1 >= s.data.length) break;
            float f = (float) (pos - a);
            mix[i] += (s.data[a] * (1 - f) + s.data[a + 1] * f) * gain;
        }
    }

    public float gain = 1, transientGain = 1, bodyGain = 1, tailGain = 0.85f, distantGain = 1, rate = 1, reverbSend = 1, varRate = 0.015f, varDb = 1f, refDistance = 9, maxDistance = 420;
    public final List<Mech> mech = new ArrayList<>();
    public SampleSet set;
    /**
     * 0.8, "punchier, real gun sounds": close recordings of this firearm as it is heard at the shooter - the whole
     * report in one take, crack and thump together - from the sound pack (content/soundpack.json, "weapons"). When a
     * weapon has one it leads: it starts on the shot's sample at full level, and the layered set (which carries the
     * distance, the reflections and the open-air tail) plays under it at {@link #punchUnder}. A separate recording of
     * the same weapon suppressed replaces the filtered rendering when there is one.
     */
    public final List<Sample> punch = new ArrayList<>(), punchSuppressed = new ArrayList<>();
    public float punchGain = 1f, punchUnder = 0.45f;
    /** How long a close recording is allowed to run, the time constant it is let go with after its first instant, and how loud a suppressed one is against an unsuppressed one. */
    public static float PUNCH_SECONDS = 1.1f, PUNCH_DECAY = 0.16f, PUNCH_SUPPRESSED = 0.2f, PUNCH_RMS = 0.3f;

    // ---- suppressed rendering (shared: the library holds no suppressed recordings)
    public static float supTransient = 0.22f, supBody = 0.38f, supTail = 0.12f, supLowpassHz = 2600, supRate = 0.94f, supReverb = 0.25f, supMech = 2.2f;

    /** The registry: profiles by weapon id, recorded sets by id, handling samples by name. */
    public static final class Library {
        public final Map<String, WeaponAudioProfile> profiles = new HashMap<>();
        public final Map<String, SampleSet> sets = new HashMap<>();
        public final Map<String, Sample> handling = new HashMap<>();
        public final Map<String, Map<String, String>> cues = new HashMap<>();
        public WeaponAudioProfile fallback;
        /** 0.6: level every weapon is calibrated to at the shooter (loudest 100 ms RMS out of the bus), and the factor per class. */
        public float heardRms;
        public final Map<String, Float> classFactor = new HashMap<>();
        public final List<String> problems = new ArrayList<>();
        public int bytes;

        public WeaponAudioProfile of(String weaponId) { WeaponAudioProfile p = profiles.get(weaponId); return p != null && p.set != null ? p : fallback; }
        /** True when the weapon has its own profile and its recorded set loaded (not the fallback). */
        public boolean has(String weaponId) { WeaponAudioProfile p = profiles.get(weaponId); return p != null && p.set != null && p.set.usable(); }
    }

    private static float f(Map<String, Object> m, Map<String, Object> def, String k, float d) { return (float) Json.num(m, k, Json.num(def, k, d)); }

    /** The classpath resource (JSON) the game's weapon sound profiles are read from; set by the game before the first {@link #load}. */
    public static volatile String resource;

    /** Loads profiles and every recorded ingredient they refer to. Missing assets are reported, never silently replaced. */
    @SuppressWarnings("unchecked")
    /** packWeapons: the close recordings a game supplies per weapon ({"weapon": {"shot": [stems], "suppressed": [stems]}}), looked up in assets/pack. */
    public static Library load(Path assets, Map<String, Object> packWeapons) {
        if (packWeapons == null) packWeapons = new java.util.LinkedHashMap<>();
        Library lib = new Library();
        Map<String, Object> root;
        if (resource == null) { lib.problems.add("no weapon sound profiles named (WeaponAudioProfile.resource)"); return lib; }
        try (InputStream in = WeaponAudioProfile.class.getResourceAsStream(resource)) {
            if (in == null) { lib.problems.add(resource + " missing"); return lib; }
            root = Json.parseObject(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (Exception e) { lib.problems.add("weapon_audio.json: " + e); return lib; }
        Map<String, Object> def = Json.obj(root, "defaults"), sup = Json.obj(root, "suppressed"), actions = Json.obj(root, "actions");
        supTransient = (float) Json.num(sup, "transient", supTransient); supBody = (float) Json.num(sup, "body", supBody); supTail = (float) Json.num(sup, "tail", supTail);
        supLowpassHz = (float) Json.num(sup, "lowpassHz", supLowpassHz); supRate = (float) Json.num(sup, "rate", supRate); supReverb = (float) Json.num(sup, "reverbSend", supReverb); supMech = (float) Json.num(sup, "mechBoost", supMech);
        Map<String, Object> var = Json.obj(def, "variation");
        Map<String, Object> loudDef = Json.obj(root, "loudness");
        lib.heardRms = (float) Json.num(loudDef, "heardRms", 0);
        for (Map.Entry<String, Object> ce : Json.obj(loudDef, "class").entrySet()) if (ce.getValue() instanceof Number nv) lib.classFactor.put(ce.getKey(), nv.floatValue());
        // handling samples first: actions and reload cues refer to them by name
        Path hdir = assets.resolve("handling");
        if (Files.isDirectory(hdir)) try (var st = Files.list(hdir)) {
            for (Path p : (Iterable<Path>) st.sorted()::iterator) {
                String n = p.getFileName().toString();
                if (!n.toLowerCase().endsWith(".wav")) continue;
                Sample s = wav(p, n.substring(0, n.length() - 4));
                if (s != null) { lib.handling.put(s.name, s); lib.bytes += s.data.length * 4; }
            }
        } catch (Exception e) { lib.problems.add("handling: " + e); }
        for (Map.Entry<String, Object> e : Json.obj(root, "handling").entrySet()) {
            if (!(e.getValue() instanceof Map)) continue;
            Map<String, String> m = new HashMap<>();
            for (Map.Entry<String, Object> c : ((Map<String, Object>) e.getValue()).entrySet()) m.put(c.getKey(), String.valueOf(c.getValue()));
            lib.cues.put(e.getKey(), m);
        }
                Map<String, Path> packFiles = astratto.audio.SampleOps.index(assets.resolve("pack"));
        Map<String, Object> punchDef = Json.obj(root, "punch");
        Map<String, Object> weapons = Json.obj(root, "weapons");
        Map<String, Object> all = new java.util.LinkedHashMap<>(weapons);
        all.put("__fallback", Json.obj(root, "fallback"));
        for (Map.Entry<String, Object> e : all.entrySet()) {
            if (!(e.getValue() instanceof Map)) continue;
            Map<String, Object> w = (Map<String, Object>) e.getValue();
            WeaponAudioProfile p = new WeaponAudioProfile();
            p.weaponId = e.getKey();
            p.setId = Json.str(w, "set", ""); p.analogue = Json.str(w, "analogue", ""); p.match = Json.str(w, "match", ""); p.action = Json.str(w, "action", "");
            p.gain = f(w, def, "gain", 1); p.transientGain = f(w, def, "transient", 1); p.bodyGain = f(w, def, "body", 1); p.tailGain = f(w, def, "tail", 0.85f) * (float) Math.pow(10, Json.num(def, "tailMakeupDb", 0) / 20.0); p.distantGain = f(w, def, "distant", 1);
            p.rate = f(w, def, "rate", 1); p.reverbSend = f(w, def, "reverbSend", 1); p.refDistance = f(w, def, "refDistance", 9); p.maxDistance = f(w, def, "maxDistance", 420);
            Map<String, Object> wv = Json.obj(w, "variation");
            p.varRate = (float) Json.num(wv, "rate", Json.num(var, "rate", 0.015)); p.varDb = (float) Json.num(wv, "db", Json.num(var, "db", 1.0));
            for (Object o : Json.list(actions, p.action)) {
                Map<String, Object> mm = (Map<String, Object>) o;
                String sn = Json.str(mm, "sample", "");
                if (!lib.handling.containsKey(sn)) { lib.problems.add(p.weaponId + ": action sample '" + sn + "' not found"); continue; }
                p.mech.add(new Mech(sn, (float) Json.num(mm, "at", 0.04), (float) Json.num(mm, "gain", 0.1), (float) Json.num(mm, "rate", 1)));
            }
            p.set = lib.sets.computeIfAbsent(p.setId, id -> loadSet(assets.resolve("weapons").resolve(id), id, lib));
            if (p.set == null || !p.set.usable()) { lib.problems.add(p.weaponId + ": recorded set '" + p.setId + "' missing or empty (run gradlew prepareAudio)"); p.set = null; }
            p.loudClass = Json.str(w, "class", "");
            p.punchGain = (float) Json.num(w, "punchGain", Json.num(punchDef, "gain", 1.0)); p.punchUnder = (float) Json.num(w, "punchUnder", Json.num(punchDef, "under", 0.45));
            Map<String, Object> pw = Json.obj(packWeapons, p.weaponId);
            for (int which = 0; which < 2; which++) for (Object f : Json.list(pw, which == 0 ? "shot" : "suppressed")) {
                String stem = String.valueOf(f).toLowerCase();
                Path file = packFiles.get(stem);
                if (file == null) { lib.problems.add(p.weaponId + ": close recording '" + stem + "' is not in the sound pack"); continue; }
                try {
                    float[] x = astratto.audio.Codecs.decode(file).mono();
                    astratto.audio.SampleOps.trim(x);
                    astratto.audio.SampleOps.level(x, 0.9f);
                    // the close recording is the report, not the afternoon: its first tenth of a second is kept whole, what
                    // follows is let go quickly (the layered set carries the decay and the reflections), and it is over in
                    // a little more than a second - so automatic fire is a row of cracks, not a wall
                    int hold = 44100 / 12, end = Math.min(x.length, (int) (44100 * PUNCH_SECONDS));
                    x = java.util.Arrays.copyOf(x, end);
                    for (int i = hold; i < end; i++) x[i] *= (float) Math.exp(-(i - hold) / (44100.0 * PUNCH_DECAY));
                    astratto.audio.SampleOps.edges(x);
                    // takes of one weapon came from different files (a stereo one and a mono one, often): they are set to the
                    // same loudness - the loudest tenth of a second of each - so the weapon does not change level between rounds
                    double acc = 0, best = 0;
                    int win = 4410;
                    for (int i = 0; i < x.length; i++) { acc += x[i] * x[i]; if (i >= win) acc -= x[i - win] * x[i - win]; if (acc > best) best = acc; }
                    float rms = (float) Math.sqrt(best / win), kk = rms > 1e-5f ? PUNCH_RMS / rms : 1;
                    for (int i = 0; i < x.length; i++) x[i] *= kk * (which == 1 ? PUNCH_SUPPRESSED : 1);
                    float[] guarded = java.util.Arrays.copyOf(x, x.length + 2);
                    (which == 0 ? p.punch : p.punchSuppressed).add(new Sample(guarded, 44100, stem));
                    lib.bytes += guarded.length * 4;
                } catch (astratto.audio.Codecs.Failure ex) { lib.problems.add(p.weaponId + ": " + stem + " " + ex.getMessage()); }
            }
            if (p.set != null) {
                Map<String, Object> loud = Json.obj(root, "loudness");
                float target = (float) Json.num(loud, "targetRms", 0), cls = (float) Json.num(Json.obj(loud, "class"), p.loudClass, 1.0);
                p.measured = loudness(p);
                if (target > 0 && p.measured > 1e-5f) {
                    p.norm = Math.max((float) Json.num(loud, "minNorm", 0.4), Math.min((float) Json.num(loud, "maxNorm", 5), target * cls / (p.gain * p.measured)));
                    p.gain *= p.norm;
                }
            }
            if (p.weaponId.equals("__fallback")) lib.fallback = p.set == null ? null : p; else lib.profiles.put(p.weaponId, p);
        }
        return lib;
    }

    private static SampleSet loadSet(Path dir, String id, Library lib) {
        if (!Files.isDirectory(dir)) return null;
        SampleSet s = new SampleSet(id);
        for (int k = 1; k <= 12; k++) {
            Sample tr = wav(dir.resolve(id + "_tr" + k + ".wav"), id + "_tr" + k), bd = wav(dir.resolve(id + "_bd" + k + ".wav"), id + "_bd" + k), tl = wav(dir.resolve(id + "_tl" + k + ".wav"), id + "_tl" + k);
            if (tr == null || bd == null || tl == null) break;
            s.transient_.add(tr); s.body.add(bd); s.tail.add(tl);
            lib.bytes += (tr.data.length + bd.data.length + tl.data.length) * 4;
        }
        for (int k = 1; k <= 12; k++) { Sample ds = wav(dir.resolve(id + "_ds" + k + ".wav"), id + "_ds" + k); if (ds == null) break; s.distant.add(ds); lib.bytes += ds.data.length * 4; }
        return s;
    }

    /** 16-bit mono PCM WAV as written by the pipeline. */
    static Sample wav(Path p, String name) {
        try {
            if (!Files.exists(p)) return null;
            byte[] d = Files.readAllBytes(p);
            ByteBuffer b = ByteBuffer.wrap(d).order(ByteOrder.LITTLE_ENDIAN);
            int pos = 12, rate = 44100, ch = 1;
            while (pos + 8 <= d.length) {
                String id = new String(d, pos, 4, StandardCharsets.US_ASCII);
                int size = b.getInt(pos + 4), body = pos + 8;
                if (id.equals("fmt ")) { ch = b.getShort(body + 2); rate = b.getInt(body + 4); }
                else if (id.equals("data")) {
                    int n = Math.min(size, d.length - body) / (2 * ch);
                    float[] x = new float[n + 2];                       // two zero samples of guard for interpolation
                    for (int i = 0; i < n; i++) { float s = 0; for (int c = 0; c < ch; c++) s += b.getShort(body + (i * ch + c) * 2) / 32768f; x[i] = s / ch; }
                    return new Sample(x, rate, name);
                }
                pos = body + size + (size & 1);
            }
        } catch (Exception ignored) { }
        return null;
    }
}
