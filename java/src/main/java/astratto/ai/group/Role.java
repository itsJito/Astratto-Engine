package astratto.ai.group;

/** What the squad has asked one of its members to be for the next few seconds. */
public enum Role {
    HOLD, SUPPRESS, ADVANCE, FLANK_LEFT, FLANK_RIGHT, INVESTIGATE, SEARCH, REPOSITION, RETREAT,
    /** 1.3: while the rest search a building, one holds its way out - crouched at the door, covering it - so what is hiding cannot slip away. */
    WATCH;

    public boolean flank() { return this == FLANK_LEFT || this == FLANK_RIGHT; }
}
