package de.hymods.orbisarena.sim;

/**
 * Something a card builds out of blocks.
 *
 * @param w        footprint across the arena (x), in tiles
 * @param h        footprint along the arena (y), in tiles
 * @param lifetime seconds until it crumbles; buildings and walls lose health evenly over it
 * @param style    which blocks the view uses
 * @param puppet   model standing on top of it, null for none
 */
public record StructureDef(String id, Kind kind, int w, int h, double hp, double lifetime, Weapon weapon, Spawn spawn,
                           Hedge hedge, Keg keg, String style, String puppet) {

    public enum Kind {
        /** Blocks the way; enemies walk around or break through. */
        WALL,
        /** Walkable; slows and hurts enemies inside. */
        HEDGE,
        /** Makes river tiles walkable for everyone. */
        BRIDGE,
        /** One small powder keg per tile that blows up when an enemy comes close or it is hit hard enough. */
        KEG,
        /** A building: blocks the way, draws building attackers, may shoot or spawn units. */
        BUILDING
    }

    /** Sends {@code count} units every {@code every} seconds, and {@code onDeath} more when destroyed. */
    public record Spawn(String unit, double every, int count, int onDeath) {
    }

    /** Multiplies enemy speed by {@code slow} and deals {@code dps} to them while they are inside. */
    public record Hedge(double slow, double dps) {
    }

    public record Keg(double trigger, double damage, double radius) {
    }
}
