package de.hymods.orbisarena.sim;

/**
 * The numbers of a match that are not on a card.
 *
 * @param regular      seconds of regular time
 * @param doubleFrom   second of regular time from which essence comes twice as fast
 * @param overtime     seconds of sudden death after a tie
 * @param essenceEvery seconds per essence at normal speed
 * @param deployTime   seconds a unit stands still after being played
 */
public record Rules(double regular, double doubleFrom, double overtime, double startEssence, double maxEssence,
                    double essenceEvery, double deployTime, double watchtowerHp, Weapon watchtower, double keepHp,
                    double buildingTowerPct) {

    public static Rules standard() {
        return new Rules(180, 120, 60, 5, 10, 2.8, 1.0, 1800,
                new Weapon(75, 0.8, 0.5, 7.0, Targets.ALL, 0, "arrow", 14, false),
                3000, 1.0);
    }

    public double end() {
        return this.regular + this.overtime;
    }
}
