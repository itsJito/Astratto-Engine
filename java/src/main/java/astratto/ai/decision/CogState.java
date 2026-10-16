package astratto.ai.decision;

/**
 * The top level of what a mind is doing. Each state offers its own set of actions to the utility selector.
 * PATROL is the host's time: routine, posts, the leader's standing orders.
 */
public enum CogState { PATROL, SUSPICIOUS, INVESTIGATE, SEARCH, COMBAT, WITHDRAW, INCAPACITATED, DEAD }
