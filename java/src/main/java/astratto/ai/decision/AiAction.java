package astratto.ai.decision;

/**
 * One thing a mind can commit to doing. The selector asks every action its state offers whether it is allowed and
 * what it is worth, and starts the best; from then on the action is ticked every frame and issues intents - to the
 * body through the Sydney bridge, to the weapon through the host - until it finishes or is cancelled.
 */
public interface AiAction {
    String name();
    boolean allowed(AiContext context);
    double utility(AiContext context);
    void begin(AiContext context);
    void tick(AiContext context, double dt);
    boolean finished(AiContext context);
    void cancel(AiContext context);

    /** How long, once started, this action is kept before a better-scoring one may replace it. */
    default double commitment() { return 1.1; }
    /** How long before the same action may be started again after it ends (0 = at once). */
    default double cooldown() { return 0; }
    /** Emergency actions take the body at once, whatever is committed. */
    default boolean emergency() { return false; }
}
