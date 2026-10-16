package astratto.ai.perception;

import astratto.ai.runtime.NeuralConfig;

/**
 * How sure one observer is that it is looking at one particular someone. It fills while that someone is exposed and
 * drains when they are not, so a glimpse is a glimpse - it does not become knowledge of where the target is. Three
 * levels come off it, each with a lower threshold for going back down than for coming up, so a meter sitting on a
 * boundary does not flicker.
 */
public final class VisionMeter {
    public enum Level { NONE, SUSPICIOUS, PROBABLE, CONFIRMED }

    private double progress;
    private Level level = Level.NONE;
    /** When this meter last had any exposure at all (simulation seconds). */
    public double lastExposed = -1e9;

    public double progress() { return progress; }
    public Level level() { return level; }
    public boolean confirmed() { return level == Level.CONFIRMED; }

    /** exposure01 is how much of the target can be seen and how well (0 = hidden); alertMultiplier > 1 for an observer already on edge. */
    public double tick(double dt, double exposure01, double alertMultiplier, NeuralConfig c) {
        double gain = c.gainPerSecond * Math.max(0, exposure01) * alertMultiplier;
        double decay = exposure01 > 0.01 ? 0 : c.decayPerSecond;
        progress = Math.max(0, Math.min(1, progress + dt * (gain - decay)));
        level = switch (level) {
            case NONE -> progress >= c.lookAt ? Level.SUSPICIOUS : Level.NONE;
            case SUSPICIOUS -> progress >= c.investigate ? Level.PROBABLE : progress < c.lookAtDown ? Level.NONE : Level.SUSPICIOUS;
            case PROBABLE -> progress >= c.confirm - 1e-3 ? Level.CONFIRMED : progress < c.investigateDown ? Level.SUSPICIOUS : Level.PROBABLE;
            case CONFIRMED -> progress < c.confirmDown ? Level.PROBABLE : Level.CONFIRMED;
        };
        return progress;
    }

    /** Certain at once: being shot from in front, a body an arm's length away. */
    public void confirm() { progress = 1; level = Level.CONFIRMED; }
    public void clear() { progress = 0; level = Level.NONE; }
}
