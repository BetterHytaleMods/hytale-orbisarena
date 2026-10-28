package de.hymods.orbisarena.sim;

import java.util.Random;

/** One player's state in a match. */
public final class Side {
    final int index;
    final Faction faction;
    final Hand hand;
    double essence;
    int crowns;
    double abilityReadyAt;

    Side(int index, Faction faction, Random random, double essence, double abilityReadyAt) {
        this.index = index;
        this.faction = faction;
        this.hand = new Hand(faction.cards(), random);
        this.essence = essence;
        this.abilityReadyAt = abilityReadyAt;
    }

    public int index() {
        return this.index;
    }

    public Faction faction() {
        return this.faction;
    }

    public Hand hand() {
        return this.hand;
    }

    public double essence() {
        return this.essence;
    }

    /** Enemy towers destroyed. */
    public int crowns() {
        return this.crowns;
    }

    public double abilityReadyAt() {
        return this.abilityReadyAt;
    }
}
