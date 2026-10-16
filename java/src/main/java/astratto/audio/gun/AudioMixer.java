package astratto.audio.gun;

import java.util.concurrent.atomic.AtomicLong;

/**
 * The gun bus (0.5, brief 17 phases 4 to 7): takes shot events from the game thread through a lock-free ring,
 * renders their layers, early reflections and the one shared late field, manages its own dynamics and adds the
 * result to the engine's mix. Everything in {@link #mix} runs on the audio thread and does no disk access, takes
 * no lock and allocates nothing: assets were decoded at load, events arrive as references in a preallocated ring,
 * voices come from a fixed pool.
 *
 * Gain staging: layers are summed with headroom (the bus runs 3 dB under full scale), a slow compressor rides the
 * level of sustained fire so twenty rounds are not twenty times one round, a peak limiter catches what the compressor
 * is too slow for, and the last stage is a soft knee that should never be reached. The engine's own output limiter comes after this bus.
 */
public final class AudioMixer {
    public static final int RATE = 44100;
    private static final int RING = 256, MAX_BLOCK = 4096;
    private static final boolean TRACE = System.getenv("ASTRATTO_AUDIO_TRACE") != null;

    // ---- event ring: single producer (game thread), single consumer (audio thread)
    private final ShotEvent[] ring = new ShotEvent[RING];
    private final AtomicLong head = new AtomicLong(), tail = new AtomicLong();
    /** Auxiliary one-shots (handling sounds started by a shot's action are voices; this is for cues the game posts directly). */
    public final VoiceManager voices = new VoiceManager();
    public final SampleLayerEngine layers = new SampleLayerEngine(voices);
    public final LateReverb reverb = new LateReverb(RATE);
    public final AcousticEnvironment environment = new AcousticEnvironment();

    private final float[] left = new float[MAX_BLOCK], right = new float[MAX_BLOCK], send = new float[MAX_BLOCK], wetL = new float[MAX_BLOCK], wetR = new float[MAX_BLOCK];
    /** The mixer clock: samples rendered since start. Read by the game thread to place events. */
    private volatile long clock;
    private float comp = 1, env, lim = 1;
    private static final float CEILING = 0.92f;
    /** Samples on which the peak limiter had to act (diagnostics). */
    public volatile int limited;
    private static final float HEADROOM = 0.7f, THRESHOLD = 0.62f, RATIO = 3.2f;

    // ---- diagnostics (gates and the audio debug line)
    public volatile float peakOut, peakPreClip;
    public volatile int softClipped, hardOver, eventsRendered, eventsDropped, blocks;
    public volatile long maxBlockNanos, totalNanos;
    /** Blocks that took longer than 5 ms to mix (a quarter of the time a block lasts). */
    public volatile int slowBlocks;
    /** When set (tests), every rendered block is appended here: interleaved stereo. */
    public float[] capture;
    public int captured;

    public long clock() { return clock; }

    /** Game thread: hands a shot to the audio thread. Never blocks; a full ring drops the event and counts it. */
    public boolean post(ShotEvent e) {
        long h = head.get();
        if (h - tail.get() >= RING) { eventsDropped++; return false; }
        ring[(int) (h & (RING - 1))] = e;
        head.lazySet(h + 1);
        return true;
    }

    /** Audio thread: renders one block of the gun bus and adds it, scaled by the effects bus level, into the engine's interleaved mix. */
    public void mix(float[] out, int frames, float level) {
        long t0 = System.nanoTime();
        int n = Math.min(frames, MAX_BLOCK);
        long start = clock;
        // drain: every event becomes voices that know their own start sample
        long t = tail.get(), h = head.get();
        while (t < h) {
            int i = (int) (t & (RING - 1));
            ShotEvent e = ring[i];
            ring[i] = null;
            t++;
            if (e != null) { layers.render(e, start); eventsRendered++; }
        }
        tail.lazySet(t);
        java.util.Arrays.fill(left, 0, n, 0f); java.util.Arrays.fill(right, 0, n, 0f); java.util.Arrays.fill(send, 0, n, 0f);
        voices.mix(start, n, left, right, send);
        reverb.target(environment.roomDecay, environment.roomWet, environment.roomDamp, environment.roomPredelay);
        reverb.process(send, wetL, wetR, n);
        float pk = 0, pre = 0;
        int clipped = 0, over = 0;
        for (int f = 0; f < n; f++) {
            float l = (left[f] + wetL[f]) * HEADROOM, r = (right[f] + wetR[f]) * HEADROOM;
            // level riding: fast enough to hold a burst, slow enough to leave every attack alone
            float a = Math.max(Math.abs(l), Math.abs(r));
            env += (a - env) * (a > env ? 0.0035f : 0.00012f);
            float want = env <= THRESHOLD ? 1f : (THRESHOLD + (env - THRESHOLD) / RATIO) / env;
            comp += (want - comp) * (want < comp ? 0.004f : 0.00025f);
            l *= comp; r *= comp;
            // peak limiter: gain drops at once for a peak the compressor let through, and recovers over about 60 ms
            float raw = Math.max(Math.abs(l), Math.abs(r));
            if (raw * lim > CEILING) { lim = CEILING / raw; limited++; } else lim += (1 - lim) * 0.0004f;
            l *= lim; r *= lim;
            float m = Math.max(Math.abs(l), Math.abs(r));
            if (m > pre) pre = m;
            // the last guard: transparent below 0.8, a smooth knee above it, never past full scale
            if (m > 0.8f) { l = knee(l); r = knee(r); clipped++; if (m > 1.5f) over++; }
            float o = Math.max(Math.abs(l), Math.abs(r));
            if (o > pk) pk = o;
            out[f * 2] += l * level; out[f * 2 + 1] += r * level;
            if (capture != null && captured + 2 <= capture.length) { capture[captured++] = l; capture[captured++] = r; }
        }
        clock = start + n;
        peakOut = Math.max(peakOut * 0.95f, pk);
        if (pre > peakPreClip) peakPreClip = pre;
        softClipped += clipped; hardOver += over;
        blocks++;
        long dt = System.nanoTime() - t0;
        totalNanos += dt;
        if (dt > maxBlockNanos) maxBlockNanos = dt;
        if (dt > 5_000_000L) { slowBlocks++; if (TRACE) System.out.println("    slow block " + blocks + ": " + dt / 1000 + " us, " + voices.active() + " voices"); }
    }

    private static float knee(float x) {
        float a = Math.abs(x);
        if (a <= 0.8f) return x;
        float y = 0.8f + 0.2f * (float) Math.tanh((a - 0.8f) / 0.2f);
        return x < 0 ? -y : y;
    }

    public void resetStats() { peakOut = peakPreClip = 0; softClipped = hardOver = limited = eventsRendered = eventsDropped = blocks = 0; maxBlockNanos = totalNanos = 0; slowBlocks = 0; voices.peakVoices = 0; java.util.Arrays.fill(voices.peakKind, 0); voices.started = voices.stolen = voices.refused = 0; }

    /**
     * The shared late field: one feedback delay network for every weapon in the scene. Four delay lines of mutually
     * prime lengths feed each other through a lossless mixing matrix; each line loses level per pass according to
     * the decay time and loses top according to the damping, so the tail darkens as it dies. Shots only add energy
     * to it - which is why a long burst costs one reverb, not twenty tails. Its parameters glide, so a change of
     * room is a change of character over a quarter of a second and never a click.
     */
    public static final class LateReverb {
        private final int rate;
        private final float[][] line = new float[4][];
        private final int[] len = {1447, 1877, 2251, 2789}, at = new int[4];
        private final float[] lp = new float[4], g = new float[4];
        private final float[] pre;
        private int preAt;
        private final float[] ap1 = new float[441], ap2 = new float[149];
        private int a1, a2;
        private float decay = 1.1f, wet = 0.22f, damp = 0.45f, predelay = 0.02f, tDecay = 1.1f, tWet = 0.22f, tDamp = 0.45f, tPre = 0.02f;

        LateReverb(int rate) {
            this.rate = rate;
            for (int i = 0; i < 4; i++) line[i] = new float[len[i]];
            pre = new float[rate / 8];
            gains();
        }

        void target(float decay, float wet, float damp, float predelay) { tDecay = Math.max(0.15f, Math.min(4f, decay)); tWet = Math.max(0, Math.min(0.8f, wet)); tDamp = Math.max(0.05f, Math.min(0.9f, damp)); tPre = Math.max(0.001f, Math.min(0.11f, predelay)); }

        private void gains() { for (int i = 0; i < 4; i++) g[i] = (float) Math.pow(10, -3.0 * len[i] / (decay * rate)); }

        void process(float[] in, float[] outL, float[] outR, int n) {
            // parameters move a little every block: about a quarter of a second to arrive
            float k = Math.min(1f, n / (rate * 0.25f));
            decay += (tDecay - decay) * k; wet += (tWet - wet) * k; damp += (tDamp - damp) * k; predelay += (tPre - predelay) * k;
            gains();
            int pd = Math.max(1, Math.min(pre.length - 1, (int) (predelay * rate)));
            for (int f = 0; f < n; f++) {
                pre[preAt] = in[f];
                int rp = preAt - pd;
                if (rp < 0) rp += pre.length;
                float x = pre[rp];
                preAt++; if (preAt == pre.length) preAt = 0;
                // two all-pass stages smear the attack so a single shot does not come back as four copies of itself
                float d1 = ap1[a1], y1 = d1 - 0.6f * x; ap1[a1] = x + 0.6f * y1; a1++; if (a1 == ap1.length) a1 = 0;
                float d2 = ap2[a2], y2 = d2 - 0.6f * y1; ap2[a2] = y1 + 0.6f * y2; a2++; if (a2 == ap2.length) a2 = 0;
                float s0 = line[0][at[0]], s1 = line[1][at[1]], s2 = line[2][at[2]], s3 = line[3][at[3]];
                float sum = (s0 + s1 + s2 + s3) * 0.5f;
                float f0 = s0 - sum, f1 = s1 - sum, f2 = s2 - sum, f3 = s3 - sum;          // Householder reflection: lossless, fully mixing
                lp[0] += (f0 - lp[0]) * (1 - damp); lp[1] += (f1 - lp[1]) * (1 - damp); lp[2] += (f2 - lp[2]) * (1 - damp); lp[3] += (f3 - lp[3]) * (1 - damp);
                if (Math.abs(lp[0]) < 1e-18f) lp[0] = 0; if (Math.abs(lp[1]) < 1e-18f) lp[1] = 0; if (Math.abs(lp[2]) < 1e-18f) lp[2] = 0; if (Math.abs(lp[3]) < 1e-18f) lp[3] = 0;   // a dying tail reaches zero instead of crawling through subnormals
                if (Math.abs(y2) < 1e-18f) y2 = 0;
                line[0][at[0]] = y2 + lp[0] * g[0]; line[1][at[1]] = y2 + lp[1] * g[1]; line[2][at[2]] = y2 + lp[2] * g[2]; line[3][at[3]] = y2 + lp[3] * g[3];
                for (int i = 0; i < 4; i++) { at[i]++; if (at[i] == len[i]) at[i] = 0; }
                outL[f] = (s0 - s2 + s1 * 0.5f) * wet;
                outR[f] = (s1 - s3 + s0 * 0.5f) * wet;
            }
        }

        public float decay() { return decay; }
        public float wet() { return wet; }
    }
}
