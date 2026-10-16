package astratto.audio.gun;

import astratto.audio.gun.AcousticEnvironment.Acoustics;
import astratto.audio.gun.WeaponAudioProfile.Sample;
import astratto.audio.gun.WeaponAudioProfile.SampleSet;

/**
 * Renders one shot as independent components (0.5, brief 17 phase 5). For every {@link ShotEvent}:
 *
 *   1. the transient of the chosen take starts on the event's sample - nothing is faded in front of it;
 *   2. a body starts on the same sample. Bodies and transients were split from their recordings with a
 *      complementary crossover - they occupy different bands, so any body of this weapon sits under any of its
 *      transients without cancelling, and the pairing changes from shot to shot;
 *   3. the action's mechanical events are placed after the shot at the weapon's own timings;
 *   4. the recorded tail carries the open-air decay, scaled down by how enclosed the muzzle is;
 *   5. each early reflection found by the acoustic model is a delayed, filtered, placed copy of the take's
 *      band-limited layer, and the dry layers send energy to the shared late reverb;
 *   6. nothing already sounding is touched - earlier bodies, tails and reflections decay on their own.
 *
 * Distance changes the balance rather than only the level: the crack thins out, the band-limited distant layer
 * takes over, and the whole shot arrives late by the time sound takes to travel.
 */
public final class SampleLayerEngine {
    private final VoiceManager voices;
    public java.util.Map<String, Sample> handling = java.util.Map.of();
    /** Layers started per kind since start (diagnostics). */
    public final int[] startedKind = new int[VoiceManager.KINDS];
    /** How far the layered set was pulled under the close recording for the shot being rendered (1 = not at all). */
    private float punchUnder = 1;
    /** Close recordings started since start (diagnostics). */
    public int punches;

    public SampleLayerEngine(VoiceManager voices) { this.voices = voices; }

    private static float smooth(float a, float b, float x) { float t = Math.max(0, Math.min(1, (x - a) / (b - a))); return t * t * (3 - 2 * t); }

    private void go(int kind, Sample s, float rate, float gain, float pan, float lp, float send, long at) { go(kind, s, rate, gain, pan, lp, send, at, 0); }

    private void go(int kind, Sample s, float rate, float gain, float pan, float lp, float send, long at, float seconds) {
        if (s == null) return;
        if (voices.start(kind, s.data, rate * s.rate / (double) AudioMixer.RATE, gain, pan, lp, send, at, seconds)) startedKind[kind]++;
    }

    /** Audio thread: turns an event into voices. {@code now} is the mixer clock at the start of the block being mixed. */
    public void render(ShotEvent e, long now) {
        WeaponAudioProfile p = e.profile();
        if (p == null || p.set == null) return;
        SampleSet set = p.set;
        Acoustics a = e.acoustics();
        int take = Math.floorMod(e.take(), set.takes());
        boolean sup = e.suppressed();
        float rate = p.rate * e.rateVar() * (sup ? WeaponAudioProfile.supRate : 1);
        float d = a.distance;
        float g = p.gain * e.gainVar() * a.directGain;
        // an event that arrives late (the game hitched) still starts cleanly: on the first sample of this block
        long at = Math.max(now, e.sample() + (long) (a.travel * AudioMixer.RATE));
        float lp = a.directLowpass;
        if (sup) lp = Math.max(lp, AcousticEnvironment.coefficient(WeaponAudioProfile.supLowpassHz, AudioMixer.RATE));
        float near = 1 - smooth(60, 260, d), far = smooth(35, 150, d);
        float send = p.reverbSend * a.reverbSend * (sup ? WeaponAudioProfile.supReverb : 1) * (0.35f + 0.65f * near);
        float tr = g * p.transientGain * (0.25f + 0.75f * near) * (sup ? WeaponAudioProfile.supTransient : 1);
        float bd = g * p.bodyGain * (sup ? WeaponAudioProfile.supBody : 1) * (1 - 0.45f * far);
        // 0.8: a weapon with a close recording of its own leads with it. Near the muzzle that recording is the shot;
        // the layered set stays under it and takes over with distance, where a close recording would be a lie.
        boolean supRec = sup && !p.punchSuppressed.isEmpty();
        java.util.List<Sample> lead = supRec ? p.punchSuppressed : p.punch;
        if (!lead.isEmpty()) {
            float close = 1 - smooth(25, 140, d);
            float pg = g * p.punchGain * close * (sup && !supRec ? WeaponAudioProfile.supTransient * 1.6f : 1);
            if (pg > 0.002f) punches++;
            if (pg > 0.002f) go(VoiceManager.TRANSIENT, lead.get(Math.floorMod(e.take(), lead.size())), e.rateVar() * (sup && !supRec ? WeaponAudioProfile.supRate : 1), pg, a.pan * 0.8f, lp, send * 0.7f, at);
            float under = 1 - (1 - p.punchUnder) * close;
            tr *= under; bd *= under;
            punchUnder = under;
        } else punchUnder = 1;
        go(VoiceManager.TRANSIENT, set.transient_.get(take), rate, tr, a.pan, lp, send * 0.8f, at);
        go(VoiceManager.BODY, set.body.get(Math.floorMod(e.bodyTake(), set.takes())), rate, bd, a.pan * 0.6f, lp, send, at);
        // the recorded tail is an outdoor decay: under a roof the room supplies the decay instead
        float open = 1 - a.indoor;
        float tl = g * p.tailGain * open * (sup ? WeaponAudioProfile.supTail : 1) * (0.5f + 0.5f * near) * punchUnder;
        if (tl > 0.003f) go(VoiceManager.TAIL, set.tail.get(take), rate, tl, a.pan * 0.4f, lp, send * 0.3f, at);
        if (far > 0.02f && !set.distant.isEmpty()) go(VoiceManager.DISTANT, set.distant.get(Math.floorMod(e.distantTake(), set.distant.size())), rate, g * p.distantGain * far * 1.6f * (sup ? WeaponAudioProfile.supBody : 1), a.pan, lp, send * 0.5f, at);
        // the action: heard at the weapon, lost within a few tens of metres
        float mechNear = 1 - smooth(4, 38, d);
        if (mechNear > 0.02f) for (int i = 0; i < p.mech.size(); i++) {
            WeaponAudioProfile.Mech m = p.mech.get(i);
            Sample s = handling.get(m.sample());
            if (s == null) continue;
            go(VoiceManager.MECH, s, m.rate() * e.rateVar(), m.gain() * p.refDistance / (p.refDistance + d * 3) * mechNear * (sup ? WeaponAudioProfile.supMech : 1), a.pan, lp, 0.1f, at + (long) (m.at() * AudioMixer.RATE));
        }
        // early reflections: each surface answers once, later and duller than the shot
        if (!set.distant.isEmpty() || !set.body.isEmpty()) {
            Sample src = !set.distant.isEmpty() ? set.distant.get(Math.floorMod(e.distantTake(), set.distant.size())) : set.body.get(take);
            for (int i = 0; i < a.reflections; i++) {
                float rg = p.gain * e.gainVar() * a.gain[i] * (sup ? WeaponAudioProfile.supBody : 1) * 1.5f;
                go(VoiceManager.REFLECTION, src, rate, rg, a.rpan[i], Math.max(lp, a.lowpass[i]), send * 0.6f, at + (long) (a.delay[i] * AudioMixer.RATE), 0.32f);
            }
        }
    }
}
