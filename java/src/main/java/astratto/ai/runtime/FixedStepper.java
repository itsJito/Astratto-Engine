package astratto.ai.runtime;

/**
 * Turns frames of any length into ticks of one length. Cognition runs on these: a cooldown, a detection rate or a
 * commitment never depends on the frame rate. A long frame is capped (a quarter of a second) and at most five ticks
 * are run to catch up, so a hitch slows thinking down for a moment instead of unloading a second of it at once.
 */
public final class FixedStepper {
    private final double interval;
    private double accumulator;
    public long ticks;

    public FixedStepper(double ticksPerSecond) { interval = 1.0 / ticksPerSecond; }

    public double interval() { return interval; }

    public void update(double frameSeconds, Runnable tick) {
        accumulator += Math.min(frameSeconds, 0.25);
        int steps = 0;
        while (accumulator >= interval && steps++ < 5) {
            tick.run();
            ticks++;
            accumulator -= interval;
        }
        if (accumulator > interval * 5) accumulator = interval;      // never owe more than a tick after a hitch
    }
}
