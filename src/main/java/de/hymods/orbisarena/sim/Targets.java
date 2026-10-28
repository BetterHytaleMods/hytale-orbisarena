package de.hymods.orbisarena.sim;

/** What a weapon may hit. */
public enum Targets {
    /** Ground units and buildings, never fliers. */
    GROUND,
    /** Everything. */
    ALL,
    /** Towers and buildings only; walks past every unit. */
    BUILDINGS;

    public boolean hitsAir() {
        return this == ALL;
    }
}
