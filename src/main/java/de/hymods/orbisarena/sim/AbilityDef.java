package de.hymods.orbisarena.sim;

/**
 * What the avatar on the keep does on a right click.
 *
 * @param initial  cooldown at the start of a match
 * @param ownHalf  only on the own half (and opened pockets), like a card
 */
public record AbilityDef(String id, String name, String icon, double cooldown, double initial, boolean ownHalf,
                         SpellDef spell) {
}
