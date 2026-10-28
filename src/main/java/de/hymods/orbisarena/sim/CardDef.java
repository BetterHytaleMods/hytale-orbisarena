package de.hymods.orbisarena.sim;

/**
 * A card in a faction's deck.
 *
 * @param icon name of the image the hand shows (a model portrait of the game, or one of ours)
 */
public record CardDef(String id, String name, int cost, Type type, String icon, String unit, int count, SpellDef spell,
                      String structure) {

    public enum Type {
        TROOP, SPELL, BUILDING, BUILD
    }
}
