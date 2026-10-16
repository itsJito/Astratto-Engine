package starter;

import astratto.bridge.Snapshots;
import astratto.game.GameContext;
import astratto.game.GameModule;

import static astratto.bridge.AstrattoNative.*;

/**
 * Gameplay in Java, audio in the engine: the lamp in the hut hums (a looping positional voice, muffled by the walls
 * and sent into the room's reverb by the engine's mixer) and the player's steps click as they walk. The sounds are
 * made here, from code, and handed to the engine once; a game would decode its own files the same way.
 */
public final class SoundModule implements GameModule {
    private final PlayerModule player;
    private final boolean renderAudio;
    private long hum, step, humVoice;
    private float stepClock;
    public int steps, voicesEnded;
    public long framesMixed;
    public int peakVoices;
    private final float[] mixScratch = new float[2 * 4096];

    /** renderAudio: there is no audio device, so the mixer is run here, once a tick, for as long as the tick (and its output let go). */
    public SoundModule(PlayerModule player, boolean renderAudio) { this.player = player; this.renderAudio = renderAudio; }

    @Override public void init(GameContext ctx) {
        int rate = 44100;
        float[] h = new float[rate];
        for (int i = 0; i < h.length; i++) { double t = i / (double) rate; h[i] = (float) (0.25 * Math.sin(2 * Math.PI * 100 * t) + 0.12 * Math.sin(2 * Math.PI * 200 * t)); }
        float[] s = new float[rate / 12];
        java.util.Random rnd = new java.util.Random(3);
        for (int i = 0; i < s.length; i++) s[i] = (float) ((rnd.nextFloat() * 2 - 1) * Math.exp(-i / 300.0) * 0.8);
        hum = ctx.engine().createSound(h, 1, rate);
        step = ctx.engine().createSound(s, 1, rate);
        if (hum == 0 || step == 0) throw new IllegalStateException("sounds refused: " + ctx.engine().lastError());
        humVoice = ctx.reserve(KIND_VOICE);
        ctx.commands().voicePlay(humVoice, hum, BUS_AMBIENCE, VOICE_LOOP | VOICE_POSITIONAL | VOICE_OCCLUDE | VOICE_REVERB, 0.8f, 1, 0,
                EmptyMap.LAMP_X, EmptyMap.LAMP_Y - 1, EmptyMap.LAMP_Z, 18, 0.5f);
    }

    @Override public void update(GameContext ctx, float dt) {
        if (player.grounded && player.walked > 0) {
            stepClock += dt;
            if (stepClock > 0.45f && moving()) {
                stepClock = 0;
                long v = ctx.reserve(KIND_VOICE);
                ctx.commands().voicePlay(v, step, BUS_SFX, VOICE_POSITIONAL | VOICE_REVERB, 0.6f, 0.9f + steps % 3 * 0.07f, 0, player.x, player.y + 0.05f, player.z, 20, 0);
                steps++;
            }
        }
        if (renderAudio) {
            int frames = Math.min(4096, Math.max(1, Math.round(dt * 44100)));
            ctx.engine().renderAudio(mixScratch, frames);
        }
    }

    private float lastWalked;
    private boolean moving() { boolean m = player.walked - lastWalked > 0.05f; lastWalked = player.walked; return m; }

    @Override public void voiceEnded(long v) { voicesEnded++; }

    @Override public void afterFrame(GameContext ctx) {
        Snapshots.AudioStats a = ctx.engine().audioStats();
        framesMixed = a.framesMixed;
        peakVoices = Math.max(peakVoices, a.voices);
    }

    public String describe() {
        return String.format("sound: %d steps, %d voices finished, up to %d playing at once, %d frames mixed", steps, voicesEnded, peakVoices, framesMixed);
    }
}
