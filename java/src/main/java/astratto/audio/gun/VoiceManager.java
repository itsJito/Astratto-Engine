package astratto.audio.gun;

/**
 * The voices of the gun bus (0.5, brief 17 phases 4 and 7): a fixed pool, a hard budget per kind of layer, and a
 * stealing rule that gives up what is heard least first.
 *
 * Every voice is preallocated; starting one writes fields and nothing else, and mixing allocates nothing. A voice
 * carries the absolute sample it starts on, so it can be created ahead of time and begin in the middle of a block,
 * on the sample. Voices are never cut because another shot fired: each plays out, and the only thing that ends one
 * early is a steal - and a stolen voice is faded over a few milliseconds, not stopped.
 *
 * Budgets, in the order they are defended: transients (the newest attack always wins), bodies, distant layers,
 * reflections, tails, mechanics. When a kind is at its budget the oldest of that kind is released; when the pool
 * itself is full the victim is the oldest voice of the least defended kind that has any.
 */
public final class VoiceManager {
    public static final int TRANSIENT = 0, BODY = 1, DISTANT = 2, REFLECTION = 3, TAIL = 4, MECH = 5, KINDS = 6;
    public static final String[] KIND = {"transient", "body", "distant", "reflection", "tail", "mechanical"};
    /** Hard concurrency budget per kind. */
    public final int[] budget = {24, 28, 14, 36, 28, 12};
    public static final int POOL = 176;
    private static final int FADE = 288;      // about 6.5 ms at 44.1 kHz

    static final class Voice {
        float[] data;
        double pos, rate;
        float gl, gr, lpA, lpS, send, fade = 1, fadeStep;
        long start, born;
        /** Source position at which the voice lets go of its own accord (reflections are a slice, not the whole decay). */
        double until;
        int kind;
        boolean active;
    }

    private final Voice[] pool = new Voice[POOL];
    private final int[] live = new int[KINDS];
    private long serial;
    /** Counters since start: voices started, stolen (faded early), refused; the most ever sounding at once, overall and per kind. */
    public int started, stolen, refused, peakVoices;
    public final int[] peakKind = new int[KINDS];

    public VoiceManager() { for (int i = 0; i < POOL; i++) pool[i] = new Voice(); }

    public int active() { int n = 0; for (int k = 0; k < KINDS; k++) n += live[k]; return n; }
    public int active(int kind) { return live[kind]; }

    private Voice oldest(int kind) {
        Voice v = null;
        for (Voice c : pool) if (c.active && c.kind == kind && c.fadeStep == 0 && (v == null || c.born < v.born)) v = c;
        return v;
    }

    private static void release(Voice v) { if (v != null && v.fadeStep == 0) v.fadeStep = 1f / FADE; }

    /**
     * Starts a voice. gain and pan give the stereo placement; lowpass is a one-pole coefficient (0 = open); rate is
     * source samples per output sample; start is absolute, on the mixer clock. Returns false when it was refused.
     */
    public boolean start(int kind, float[] data, double rate, float gain, float pan, float lowpass, float send, long start) { return start(kind, data, rate, gain, pan, lowpass, send, start, 0); }

    /** As above, with a length in seconds after which the voice fades itself out (0 = play the whole sample). */
    public boolean start(int kind, float[] data, double rate, float gain, float pan, float lowpass, float send, long start, float seconds) {
        if (data == null || data.length < 4 || gain < 1e-4f) return false;
        if (live[kind] >= budget[kind]) { Voice o = oldest(kind); if (o != null) { release(o); stolen++; } }
        Voice v = null;
        for (Voice c : pool) if (!c.active) { v = c; break; }
        if (v == null) {
            // the pool is full: free the oldest voice of the least defended kind, and take the new one only if it matters more
            for (int k = KINDS - 1; k >= 0 && v == null; k--) {
                if (k < kind) break;
                Voice o = oldest(k);
                if (o != null) { release(o); stolen++; }
            }
            refused++;
            return false;
        }
        float p = Math.max(-1, Math.min(1, pan));
        v.data = data; v.pos = 0; v.rate = rate; v.kind = kind; v.start = start; v.born = serial++;
        v.gl = gain * (float) Math.sqrt(0.5 * (1 - p)); v.gr = gain * (float) Math.sqrt(0.5 * (1 + p));
        v.lpA = Math.max(0, Math.min(0.985f, lowpass)); v.lpS = 0; v.send = send; v.fade = 1; v.fadeStep = 0;
        v.until = seconds <= 0 ? Double.MAX_VALUE : seconds * 44100.0 * rate;
        v.active = true;
        live[kind]++;
        started++;
        int n = active();
        if (n > peakVoices) peakVoices = n;
        if (live[kind] > peakKind[kind]) peakKind[kind] = live[kind];
        return true;
    }

    /**
     * Mixes every sounding voice into separate left and right buffers and adds its share to the reverb send.
     * blockStart is the mixer clock at the first frame.
     */
    public void mix(long blockStart, int frames, float[] left, float[] right, float[] send) {
        for (Voice v : pool) {
            if (!v.active) continue;
            int f0 = v.start <= blockStart ? 0 : (int) Math.min(frames, v.start - blockStart);
            float[] d = v.data;
            int last = d.length - 2;
            double pos = v.pos, rate = v.rate;
            float lpA = v.lpA, lpS = v.lpS, gl = v.gl, gr = v.gr, snd = v.send, fade = v.fade, fs = v.fadeStep;
            double until = v.until;
            boolean done = false;
            for (int f = f0; f < frames; f++) {
                int i = (int) pos;
                if (i >= last) { done = true; break; }
                float frac = (float) (pos - i);
                float s = d[i] + (d[i + 1] - d[i]) * frac;
                if (lpA > 0) { lpS += (s - lpS) * (1 - lpA); s = lpS; }
                if (fs == 0 && pos >= until) fs = 1f / (FADE * 8);          // a self-limited voice lets go slowly: about 50 ms
                if (fs > 0) { fade -= fs; if (fade <= 0) { done = true; break; } s *= fade; }
                left[f] += s * gl; right[f] += s * gr;
                send[f] += s * snd * (gl + gr) * 0.7f;
                pos += rate;
            }
            v.pos = pos; v.lpS = lpS; v.fade = fade; v.fadeStep = fs;
            if (done) { v.active = false; v.data = null; live[v.kind]--; }
        }
    }

    /** Releases everything with a fade (a raid ends, the device is lost). */
    public void releaseAll() { for (Voice v : pool) if (v.active) release(v); }
}
