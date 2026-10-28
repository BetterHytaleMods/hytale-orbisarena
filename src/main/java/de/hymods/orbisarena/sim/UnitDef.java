package de.hymods.orbisarena.sim;

import java.util.List;

/**
 * A kind of unit.
 *
 * @param models one model per unit of a troop, repeated if the troop is larger
 * @param speed  tiles per second
 * @param radius collision radius in tiles
 * @param mass   how hard it pushes others; tanks are heavy
 * @param sight  how far it notices enemies, in tiles
 */
public record UnitDef(String id, String name, List<String> models, double scale, double hp, double speed, double radius,
                      double mass, boolean air, double sight, Weapon weapon, Heal heal, Charge charge, Burrow burrow) {

    public String model(int index) {
        return this.models.get(index % this.models.size());
    }

    public boolean targetsBuildingsOnly() {
        return this.weapon != null && this.weapon.targets() == Targets.BUILDINGS;
    }

    /** Heals allies around it (and itself) every {@code every} seconds. */
    public record Heal(double radius, double amount, double every) {
    }

    /** After walking {@code after} tiles without attacking it runs faster and its next hit is stronger. */
    public record Charge(double after, double damageMul, double speedMul) {
    }

    /** Tunnels from its own keep to wherever it is played; cannot be hit while underground. */
    public record Burrow(double speed) {
    }
}
