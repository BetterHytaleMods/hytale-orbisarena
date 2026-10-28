package de.hymods.orbisarena.sim;

/**
 * How a unit, tower or building attacks.
 *
 * @param hitSpeed   seconds between two hits
 * @param firstHit   seconds from coming into range to the first hit
 * @param range      reach from the attacker's edge to the target's edge, in tiles
 * @param splash     radius around the point of impact that takes the full damage too, 0 for single target
 * @param projectile kind of projectile (the view picks its look), null for melee
 * @param lob        the projectile flies to where the target stood instead of following it
 */
public record Weapon(double damage, double hitSpeed, double firstHit, double range, Targets targets, double splash,
                     String projectile, double projectileSpeed, boolean lob) {

    public boolean melee() {
        return this.projectile == null || this.projectileSpeed <= 0;
    }
}
