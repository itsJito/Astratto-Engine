package astratto.audio.gun;

import java.util.HashMap;
import java.util.Map;

/**
 * Places shots on the mixer's sample clock at the weapon's exact rate of fire (0.5, brief 17 phase 4).
 *
 * The game decides that a round is fired on a frame boundary, so the moments it reports are a frame apart at best
 * and jitter by a frame at worst - at 750 rounds a minute that is a fifth of the gap between shots, and it is
 * audible as an uneven burst. The scheduler does not use those moments as timestamps. For a weapon that is firing
 * continuously, each shot is placed exactly one interval (60 / RPM seconds, in samples) after the previous one; the
 * report from the game only says "another round", and whether the burst is still the same burst. A first shot, or
 * one after a pause, is placed a small fixed latency ahead of now. There is no thread, no sleep and no timer here:
 * the result is a sample position, and the audio thread starts the voices on that sample.
 */
public final class ShotEventScheduler {
    private static final class Lane { long last = Long.MIN_VALUE / 2; int index; float rpm; String weapon = ""; }

    private final Map<Integer, Lane> lanes = new HashMap<>();
    private final int rate;
    /** Lead given to a first shot so it never lands in a block already mixed (about two blocks). */
    public int latency = 1536;
    /** Shots placed on the grid of their burst, and shots that started a new one (diagnostics). */
    public int continued, started;

    public ShotEventScheduler(int rate) { this.rate = rate; }

    public int interval(float rpm) { return Math.max(1, Math.round(60f / Math.max(1f, rpm) * rate)); }

    /**
     * The sample position for the next shot of a shooter's weapon. {@code now} is the mixer clock as the game
     * thread sees it. Returns {position, shotIndexWithinBurst}.
     */
    public long[] schedule(int shooterId, String weaponId, float rpm, long now) {
        Lane l = lanes.computeIfAbsent(shooterId, k -> new Lane());
        int step = interval(rpm);
        long earliest = now + 64;
        long at;
        // the same weapon, reported no later than a little over one interval after its last round: the burst goes on
        boolean sustained = l.weapon.equals(weaponId) && Math.abs(l.rpm - rpm) < 0.5f && now <= l.last + step + step * 3L / 5;
        if (sustained) { at = Math.max(earliest, l.last + step); l.index++; continued++; }
        else { at = now + latency; l.index = 0; started++; }
        l.last = at; l.rpm = rpm; l.weapon = weaponId;
        return new long[]{at, l.index};
    }

    public void forget(int shooterId) { lanes.remove(shooterId); }
    public void clear() { lanes.clear(); continued = started = 0; }
}
