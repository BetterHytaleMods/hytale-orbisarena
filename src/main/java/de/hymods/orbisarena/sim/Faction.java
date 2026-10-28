package de.hymods.orbisarena.sim;

import java.util.List;

/**
 * A faction: its deck, its avatar's weapon and ability, and the arena zone it calls home.
 *
 * @param zone   arena look a match uses when it is this faction's home game
 * @param avatar the weapon of the avatar on the keep
 */
public record Faction(String id, String name, String icon, String zone, Weapon avatar, AbilityDef ability,
                      List<CardDef> cards) {

    public CardDef card(String id) {
        for (CardDef card : this.cards) {
            if (card.id().equals(id)) {
                return card;
            }
        }
        return null;
    }
}
