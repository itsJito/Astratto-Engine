package astratto.ai.cognition;

/**
 * How an agent came by what it believes, weakest first. EVIDENCE: inferred from something left behind. APPROXIMATE:
 * heard, or told. LAST_SEEN: it saw the target there and no longer does. VISIBLE: it is looking at the target now.
 */
public enum ConfidenceClass { NONE, EVIDENCE, APPROXIMATE, LAST_SEEN, VISIBLE }
