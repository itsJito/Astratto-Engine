package astratto.audio;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.SourceDataLine;

/**
 * UltraEngine (0.8): the audio engine of the Astratto Engine. Until 0.8 this class was called AudioEngine; the name
 * changed, and with it the engine learned to read every format it is handed - WAV in all its PCM and float forms,
 * Ogg Vorbis and MP3 ({@link Codecs}) - to play long beds without holding the whole game up while they decode
 * ({@link #loopStream}), and to choose between the takes of a sound by itself ({@link #take}).
 *
 * It is a software mixer on a javax.sound.sampled SourceDataLine (iteration P0.3).
 *
 * Voices are routed to buses - Effects, Music, Voice, Ambience and Interface - each with its own
 * level and mute under one master level and mute. Bus levels are read while mixing, so moving a
 * slider changes sounds that are already playing. Positional one-shots get inverse-distance
 * attenuation, constant-power pan from the listener's facing, and a low-pass that grows with
 * distance so far gunfire sounds far rather than merely quiet.
 */
public final class UltraEngine {
    public static final int RATE = 44100;
    public static final int SFX = 0, MUSIC = 1, VOICE = 2, AMBIENCE = 3, UI = 4, BUSES = 5;
    public static final String[] BUS_NAMES = {"Effects", "Music", "Voice", "Ambience", "Interface"};
    private static final int FRAMES = 1024;
    private SourceDataLine line;
    private Thread thread;
    private volatile boolean running;
    public final Map<String, float[]> samples = new HashMap<>();
    private final List<Voice> voices = new ArrayList<>();
    private final Object lock = new Object();
    public volatile float master = 0.8f;
    public volatile boolean muted;
    /** Level per bus (0..1) and per-bus mute. */
    public final float[] bus = {1f, 0.55f, 1f, 0.7f, 0.8f};
    public final boolean[] busMuted = new boolean[BUSES];
    public float lx, ly, lz, lyaw; // listener
    public boolean available;
    /** Directional caption hook (accessibility): name, bearing text, distance. */
    public CaptionSink captions;
    /** Mixed output peak of the last block per bus (meters / tests). */
    public final float[] peak = new float[BUSES];
    /** Voices started per bus since start (tests / debug). */
    public final int[] started = new int[BUSES];

    /** The layered gun bus (0.5): shots are events on its sample clock, not one-shot samples. */
    public final astratto.audio.gun.GunAudio guns = new astratto.audio.gun.GunAudio();
    /** Recorded footstep variations per surface, and the last one played of each (never the same step twice running). */
    private final Map<String, Integer> stepCount = new HashMap<>(), stepLast = new HashMap<>();
    private final java.util.Random stepRng = new java.util.Random(7);

    public interface CaptionSink { void caption(String sound, float x, float y, float z, float loudness); }

    public static final class Voice {
        float[] data;
        double pos;
        float pitch = 1, gl = 1, gr = 1, gain = 1, pan, lp, lpState;
        int bus = SFX;
        boolean loop, stopped;
        float fade = 1, fadeTarget = 1, fadeSpeed;
        public void stop() { stopped = true; }
        public void setGain(float g) { gain = g; recalc(); }
        public void setPan(float p) { pan = p; recalc(); }
        public void setPitch(float p) { pitch = Math.max(0.05f, Math.min(4f, p)); }
        public void setLowpass(float k) { lp = Math.max(0, Math.min(0.95f, k)); }
        void recalc() { float p = Math.max(-1, Math.min(1, pan)); gl = gain * (float) Math.sqrt(0.5 * (1 - p)); gr = gain * (float) Math.sqrt(0.5 * (1 + p)); }
        public void fadeTo(float target, float seconds) { fadeTarget = target; fadeSpeed = seconds <= 0 ? 1 : 1f / (seconds * RATE); }
        public boolean done() { return stopped; }
        public float gain() { return gain; }
        public float pan() { return pan; }
        public float lowpass() { return lp; }
        public int bus() { return bus; }
    }

    /** What Windows holds for this process in its Volume Mixer, as found (and repaired) at start-up. */
    public WinAudioSession.State session = WinAudioSession.UNAVAILABLE;
    /** One line for the title screen and the log: device, and anything that was wrong with it. */
    public String status = "Audio not started";
    /** True when the mixer found itself silenced by the system and put that right; the menus say so once. */
    public boolean repaired;
    private long framesWritten;
    private int faults;

    public static final String NAME = "UltraEngine";
    /** When set, every block handed to the device is also written here: 16-bit little-endian stereo at {@link #RATE}. */
    public volatile java.io.OutputStream tap;
    /** How many takes each sound has (name, name_2, name_3 ...), and the one last played of each. */
    private final Map<String, Integer> takes = new HashMap<>(), lastTake = new HashMap<>();
    private final java.util.Random takeRng = new java.util.Random(11);
    /** Long beds that are decoded when first wanted, off the game's thread: name -> file. */
    public final Map<String, java.nio.file.Path> streams = new HashMap<>();
    private final java.util.LinkedHashMap<String, float[]> streamCache = new java.util.LinkedHashMap<>(4, 0.75f, true);
    /** How many decoded beds are kept (each is minutes of sound). */
    public static int STREAM_CACHE = 2;
    /** How much of a long bed is decoded and looped (the recordings supplied run a quarter of an hour each; a different stretch is taken each time). */
    public static float STREAM_SECONDS = 240f;
    /** Names that are recordings rather than synthesis (everything loaded from a file). */
    public final java.util.Set<String> recorded = new java.util.HashSet<>();

    /** Sounds that are still synthesis after the bank is built (0.9: there should be none). */
    public final java.util.TreeSet<String> synthesised = new java.util.TreeSet<>();

    /** 2.0: the bank is the game's (it puts its sounds in {@link #samples}); this is called once it is complete. */
    public void uploadBank() { }

    /** Works out how many takes every sound has. Called after the bank changes. */
    public void countTakes() {
        takes.clear();
        for (String name : samples.keySet()) {
            int n = 1;
            while (samples.containsKey(name + "_" + (n + 1))) n++;
            if (n > 1) takes.put(name, n);
        }
    }

    public int takes(String name) { return takes.getOrDefault(name, 1); }

    /**
     * 0.8: the take to play for a sound that has several. Asked for by its plain name, a sound with takes gives one of
     * them at random and never the one it gave last - so thirty bullet strikes on a wall are thirty different strikes
     * without anything that plays a sound having to know how many recordings there are.
     */
    public String take(String name) {
        Integer n = takes.get(name);
        if (n == null) return name;
        int last = lastTake.getOrDefault(name, -1), k = takeRng.nextInt(n);
        if (k == last) k = (k + 1 + takeRng.nextInt(n - 1)) % n;
        lastTake.put(name, k);
        return k == 0 ? name : name + "_" + (k + 1);
    }

    /**
     * A long bed, looped, on a bus. If it is already decoded it starts at once; if not, the voice is returned silent
     * and the file is decoded on a thread of its own - the bed fades in when it is ready, and the game never waits.
     * A bed starts somewhere in the middle, so the same thirty seconds do not open every operation.
     */
    public Voice loopStream(String name, float gain, int busId) {
        java.nio.file.Path file = streams.get(name);
        if (file == null) return loop(name, gain, busId);
        Voice v = new Voice();
        v.data = SILENCE; v.loop = true; v.gain = gain; v.bus = busId; v.fade = 0;
        v.recalc();
        started[busId]++;
        float[] have;
        synchronized (streamCache) { have = streamCache.get(name); }
        if (have != null) { begin(v, have, name.startsWith("music_")); }
        else {
            Thread t = new Thread(() -> {
                try {
                    // (1.1: a piece of music is played from its first bar; only a bed of ambience is picked up somewhere in the middle)
                    float[] raw = Codecs.excerpt(file, STREAM_SECONDS, name.startsWith("music_") ? 0f : takeRng.nextFloat()).mono();
                    SampleOps.level(raw, 0.42f);
                    float[] d = SampleOps.seam(raw);
                    synchronized (streamCache) {
                        streamCache.put(name, d);
                        while (streamCache.size() > STREAM_CACHE) { String eldest = streamCache.keySet().iterator().next(); streamCache.remove(eldest); }
                    }
                    if (!v.stopped && v.fadeTarget > 0) begin(v, d, name.startsWith("music_"));
                } catch (Throwable e) {
                    System.err.println("[" + NAME + "] bed " + name + ": " + e.getMessage());
                    v.stopped = true;
                }
            }, "ultra-stream-" + name);
            t.setDaemon(true);
            t.setPriority(Thread.MIN_PRIORITY + 1);
            t.start();
        }
        synchronized (lock) { voices.add(v); }
        return v;
    }

    private static final float[] SILENCE = new float[2048];

    private void begin(Voice v, float[] d, boolean fromStart) {
        synchronized (lock) { v.pos = fromStart ? 0 : takeRng.nextInt(Math.max(1, d.length / 2)); v.data = d; v.fade = 0; v.fadeTo(1, fromStart ? 1.5f : 3f); }
    }

    /** True when a bed of this name can be asked for with {@link #loopStream}. */
    public boolean hasStream(String name) { return streams.containsKey(name); }
    /** True once a bed has been decoded and is held (tests). */
    public boolean streamReady(String name) { synchronized (streamCache) { return streamCache.containsKey(name); } }

    /** A surface has a recorded set of footsteps: step_<surface>_1 .. step_<surface>_<count>. */
    public void stepSet(String surface, int count) { if (count > 0) stepCount.put(surface, count); else stepCount.remove(surface); }

    /** The sample to play for a step on a surface: one of its recorded variations, never the one just played. */
    public String step(String surface) {
        Integer c = stepCount.get(surface);
        if (c == null) return "step_" + surface;
        int last = stepLast.getOrDefault(surface, 0), k = 1 + stepRng.nextInt(c);
        if (k == last && c > 1) k = k % c + 1;
        stepLast.put(surface, k);
        return "step_" + surface + "_" + k;
    }

    public int stepVariations(String surface) { return stepCount.getOrDefault(surface, 0); }

    public void start() {
        try {
            AudioFormat fmt = new AudioFormat(RATE, 16, 2, true, false);
            line = AudioSystem.getSourceDataLine(fmt);
            line.open(fmt, FRAMES * 4 * 4);
            line.start();
            available = true;
        } catch (Exception e) {
            System.err.println("[" + NAME + "] unavailable: " + e.getMessage());
            status = "No audio output device (" + e.getMessage() + ")";
            available = false;
            return;
        }
        // The line plays into this process's Windows mixer session. If that session sits at zero the game is mute
        // however healthy the mixer is, so look - and lift it - before the first sound is due.
        session = WinAudioSession.query(true);
        repaired = session.available() && session.note().startsWith("restored");
        String dev = "default output";
        try { dev = AudioSystem.getMixer(null).getMixerInfo().getName(); } catch (Exception ignored) {}
        status = "Audio: " + dev + (session.available() ? "  -  Windows mixer " + Math.round(session.volume() * 100) + "%" + (session.muted() ? " (muted)" : "") : "")
                + (repaired ? "  -  was silenced in the Windows Volume Mixer, " + session.note() : session.silenced() ? "  -  SILENCED in the Windows Volume Mixer (" + session.note() + ")" : "");
        System.out.println("[" + NAME + "] " + status);
        running = true;
        thread = new Thread(this::loop, "audio-mixer");
        thread.setDaemon(true);
        thread.setPriority(Thread.MAX_PRIORITY - 1);
        thread.start();
    }

    public void stop() { running = false; if (line != null) { line.stop(); line.close(); } }

    /** Effective level of a bus right now (master, mutes included). */
    public float level(int b) { return muted || busMuted[b] ? 0 : master * bus[b] * (b == SFX || b == VOICE || b == AMBIENCE ? duck : 1); }

    /** World sound (effects, voices, ambience) is scaled by this: the death sequence pulls the world away under the music. */
    private volatile float duck = 1, duckTarget = 1, duckRate;

    public void duckTo(float level, float seconds) { duckTarget = level; duckRate = Math.abs(level - duck) / Math.max(0.05f, seconds); }

    private void loop() {
        float[] mix = new float[FRAMES * 2];
        byte[] out = new byte[FRAMES * 4];
        while (running) {
            try {
                mixBlock(mix);
                for (int i = 0; i < FRAMES * 2; i++) {
                    float m = mix[i];
                    if (m != m) m = 0;                          // a NaN from one voice must never blank the whole mix
                    float s = (float) Math.tanh(m * 1.1);       // soft limiter
                    int v = (int) (s * 32000);
                    out[i * 2] = (byte) v;
                    out[i * 2 + 1] = (byte) (v >> 8);
                }
                line.write(out, 0, out.length);
                // whoever is recording gets exactly what the device got (Director Mode's clips)
                java.io.OutputStream t = tap;
                if (t != null) try { t.write(out, 0, out.length); } catch (java.io.IOException e) { tap = null; }
                framesWritten += FRAMES;
            } catch (Throwable t) {
                // the mixer thread never dies quietly: a bad voice is dropped and the stream carries on
                if (faults++ < 5) System.err.println("[" + NAME + "] mixer fault (" + t + "), voices reset");
                synchronized (lock) { voices.clear(); }
                if (!running) return;
            }
        }
    }

    /** Mixes one block of all voices into an interleaved stereo buffer (also used headless by the quality gate). */
    public void mixBlock(float[] mix) {
        java.util.Arrays.fill(mix, 0f);
        int frames = mix.length / 2;
        java.util.Arrays.fill(peak, 0);
        if (duck != duckTarget) { float step = duckRate * frames / RATE; duck = duck < duckTarget ? Math.min(duckTarget, duck + step) : Math.max(duckTarget, duck - step); }
        synchronized (lock) {
            for (int vi = voices.size() - 1; vi >= 0; vi--) {
                Voice v = voices.get(vi);
                if (v.stopped) { voices.remove(vi); continue; }
                float lvl = level(v.bus);
                float[] d = v.data;
                float keep = v.lp;
                for (int f = 0; f < frames; f++) {
                    int i0 = (int) v.pos;
                    if (i0 >= d.length - 1) {
                        if (v.loop) { v.pos -= d.length - 1; i0 = (int) v.pos; }
                        else { v.stopped = true; break; }
                    }
                    float frac = (float) (v.pos - i0);
                    float s = d[i0] + (d[i0 + 1] - d[i0]) * frac;
                    if (keep > 0) { v.lpState += (s - v.lpState) * (1 - keep); s = v.lpState; }
                    if (v.fade != v.fadeTarget) {
                        v.fade = v.fade < v.fadeTarget ? Math.min(v.fadeTarget, v.fade + v.fadeSpeed) : Math.max(v.fadeTarget, v.fade - v.fadeSpeed);
                        if (v.fade <= 0 && v.fadeTarget <= 0) { v.stopped = true; break; }
                    }
                    s *= v.fade * lvl;
                    float l = s * v.gl, r = s * v.gr;
                    mix[f * 2] += l;
                    mix[f * 2 + 1] += r;
                    float a = Math.max(Math.abs(l), Math.abs(r));
                    if (a > peak[v.bus]) peak[v.bus] = a;
                    v.pos += v.pitch;
                }
            }
        }
        // the gun bus mixes itself (own voices, reflections, reverb, dynamics) and joins the effects bus here
        guns.mixer.mix(mix, frames, level(SFX));
    }

    /**
     * 0.7: one of the takes of a sound. A sound with more than one recording has them as name, name_2, name_3 ... and
     * whoever plays it asks for one by a number of their own (a random draw, an actor's id): the same groan is not
     * heard twice running.
     */
    public String variant(String name, int pick) {
        int n = 1;
        while (samples.containsKey(name + "_" + (n + 1))) n++;
        int k = Math.floorMod(pick, n);
        return k == 0 ? name : name + "_" + (k + 1);
    }

    public Voice play(String name, float gain, float pan, float pitch, int busId) {
        float[] d = samples.get(take(name));
        if (d == null) return new Voice();
        Voice v = new Voice();
        v.data = d; v.pitch = pitch; v.gain = gain; v.pan = pan; v.bus = busId;
        v.recalc();
        started[busId]++;
        synchronized (lock) { if (voices.size() < 96) voices.add(v); }
        return v;
    }

    public Voice play(String name, float gain, float pan, float pitch) { return play(name, gain, pan, pitch, SFX); }
    public Voice play(String name, float gain) { return play(name, gain, 0, 1, SFX); }

    /**
     * 1.0: what stands between a sound and the listener, asked of whoever knows the world: how much of a sound made at
     * (x, y, z) reaches the ear, 0..1. Null = everything does (menus, tools). Every positional sound goes through it:
     * what it lets through is quieter and duller, and what it lets almost nothing of through is not played at all.
     */
    public interface Hearing { float through(float x, float y, float z); }
    public volatile Hearing hearing;
    /** Positional sounds asked for and not played because nothing of them would have been heard (gates, tuning). */
    public int unheard;

    /** Positional one-shot: inverse-distance attenuation with range, pan from listener yaw, distance muffling. */
    public Voice playAt(String name, float x, float y, float z, float gain, float range, float pitch, int busId) {
        float dx = x - lx, dy = y - ly, dz = z - lz;
        float d = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (d > range) return new Voice();
        float att = (float) Math.pow(1 - d / range, 1.6) * Math.min(1f, 6f / Math.max(1f, d * 0.4f + 1));
        att = Math.max(att, (1 - d / range) * 0.25f);
        Hearing h = hearing;
        float hear = h == null ? 1f : h.through(x, y, z);
        att *= hear;
        if (gain * att < 0.012f) { unheard++; return new Voice(); }
        float pan = pan(x, z);
        if (captions != null && gain * att > 0.08f) captions.caption(name, x, y, z, gain * att);
        Voice v = play(name, gain * att, pan * Math.min(1, d / 3f), pitch, busId);
        v.lp = Math.max(Math.max(0, Math.min(0.93f, (d - 12) / Math.max(40f, range * 0.5f))), Math.min(0.9f, (1 - hear) * 0.95f));
        return v;
    }

    public Voice playAt(String name, float x, float y, float z, float gain, float range, float pitch) { return playAt(name, x, y, z, gain, range, pitch, SFX); }

    public float pan(float x, float z) {
        float dx = x - lx, dz = z - lz;
        // listener right vector for yaw (yaw 0 faces -z): right = (cos, 0, -sin)
        float rx = (float) Math.cos(lyaw), rz = (float) -Math.sin(lyaw);
        float l = (float) Math.sqrt(dx * dx + dz * dz);
        if (l < 1e-3f) return 0;
        return (dx * rx + dz * rz) / l;
    }

    /** Looping bed on a bus, faded in. */
    public Voice loop(String name, float gain, int busId) {
        // 0.9: music, sirens and the like are long recordings held as streams - asked for by the same name as ever
        if (!samples.containsKey(name) && streams.containsKey(name)) return loopStream(name, gain, busId);
        float[] d = samples.get(take(name));
        if (d == null) return new Voice();
        Voice v = new Voice();
        v.data = d; v.loop = true; v.gain = gain; v.bus = busId; v.fade = 0;
        v.recalc();
        v.fadeTo(1, 2f);
        started[busId]++;
        synchronized (lock) { voices.add(v); }
        return v;
    }

    /**
     * Moves a looping voice in the world (engines, fires): the same attenuation, pan and distance muffling a
     * positional one-shot gets, re-evaluated as the source and the listener move. Call once a frame.
     */
    public void place(Voice v, float x, float y, float z, float gain, float range) {
        if (v == null || v.data == null) return;
        float dx = x - lx, dy = y - ly, dz = z - lz;
        float d = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        float att = d >= range ? 0 : Math.max((float) Math.pow(1 - d / range, 1.6) * Math.min(1f, 6f / Math.max(1f, d * 0.4f + 1)), (1 - d / range) * 0.25f);
        Hearing h = hearing;
        float hear = h == null || att <= 0 ? 1f : h.through(x, y, z);
        att *= hear;
        v.lp = Math.max(v.lp * 0.9f, Math.min(0.9f, (1 - hear) * 0.95f));
        v.gain = gain * att;
        v.pan = pan(x, z) * Math.min(1, d / 3f);
        v.recalc();
        v.lp = Math.max(0, Math.min(0.93f, (d - 12) / Math.max(40f, range * 0.5f)));
    }

    /** Frames handed to the device since start (diagnostics: zero means the stream never ran). */
    public long framesWritten() { return framesWritten; }

    public Voice loop(String name, float gain, boolean ambient) { return loop(name, gain, ambient ? AMBIENCE : SFX); }

    public int activeVoices() { synchronized (lock) { return voices.size(); } }
    public void stopAll() { synchronized (lock) { for (Voice v : voices) v.stopped = true; } }
}
