package de.hymods.orbisarena.sim;

/**
 * An effect on an area: spells on cards and the avatars' abilities.
 *
 * @param towerPct    share of the damage that towers take
 * @param delay       seconds until it lands
 * @param slow        speed and attack speed multiplier for enemies hit (0 = none)
 * @param root        seconds enemy ground units cannot move
 * @param haste       speed and attack speed multiplier for own units in the area (0 = none)
 * @param summon      unit id to summon at the spot, null for none
 * @param effect      what the view shows
 */
public record SpellDef(double radius, double damage, double towerPct, double delay, double slow, double slowTime,
                       double root, double haste, double hasteTime, String summon, int summonCount, boolean hitsAir,
                       String effect) {
}
