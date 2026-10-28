package de.hymods.orbisarena.sim;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Random;

/** Four cards in hand, the rest waiting in line; a played card goes to the back of the line. */
public final class Hand {
    public static final int SIZE = 4;

    private final CardDef[] slots = new CardDef[SIZE];
    private final Deque<CardDef> line = new ArrayDeque<>();

    Hand(List<CardDef> deck, Random random) {
        List<CardDef> shuffled = new ArrayList<>(deck);
        Collections.shuffle(shuffled, random);
        for (int i = 0; i < shuffled.size(); i++) {
            if (i < SIZE) {
                this.slots[i] = shuffled.get(i);
            } else {
                this.line.addLast(shuffled.get(i));
            }
        }
    }

    public CardDef slot(int i) {
        return this.slots[i];
    }

    public CardDef next() {
        return this.line.peekFirst();
    }

    CardDef take(int i) {
        CardDef card = this.slots[i];
        CardDef next = this.line.pollFirst();
        this.slots[i] = next != null ? next : card;
        if (next != null) {
            this.line.addLast(card);
        }
        return card;
    }
}
