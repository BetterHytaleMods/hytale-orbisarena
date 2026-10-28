package de.hymods.orbisarena.sim;

/** What happened in a tick, for the view: sounds, animations, particles, blocks. */
public sealed interface MatchEvent {
    record CardPlayed(int side, CardDef card, double x, double y) implements MatchEvent {
    }

    record AbilityUsed(int side, AbilityDef ability, double x, double y) implements MatchEvent {
    }

    record UnitSpawned(Unit unit) implements MatchEvent {
    }

    /** A burrowing unit comes up. */
    record UnitEmerged(Unit unit) implements MatchEvent {
    }

    record UnitDied(Unit unit) implements MatchEvent {
    }

    /** A melee hit, or a ranged unit letting go of its projectile. */
    record UnitAttacked(Unit unit, Body target, boolean charged) implements MatchEvent {
    }

    record Healed(Unit healer) implements MatchEvent {
    }

    record ProjectileFired(Projectile projectile) implements MatchEvent {
    }

    record ProjectileLanded(Projectile projectile) implements MatchEvent {
    }

    /** A spell or ability on its way; it lands after {@code delay} seconds. */
    record SpellCast(int side, SpellDef spell, double x, double y, double delay) implements MatchEvent {
    }

    record SpellLanded(int side, SpellDef spell, double x, double y) implements MatchEvent {
    }

    record StructureBuilt(Structure structure) implements MatchEvent {
    }

    /** It crumbled ({@code destroyed} false) or was destroyed. */
    record StructureGone(Structure structure, boolean destroyed) implements MatchEvent {
    }

    record KegExploded(Structure keg) implements MatchEvent {
    }

    record TowerHit(Tower tower, double damage) implements MatchEvent {
    }

    record TowerDestroyed(Tower tower) implements MatchEvent {
    }

    record PhaseChanged(Match.Phase phase) implements MatchEvent {
    }

    /** Essence started coming twice as fast. */
    record DoubleEssence() implements MatchEvent {
    }

    /** {@code winner} is 0 or 1, or -1 for a draw. */
    record MatchEnded(int winner) implements MatchEvent {
    }
}
