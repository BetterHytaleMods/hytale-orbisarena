package de.hymods.orbisarena.sim;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Plays one side when nobody else joined: defends what crosses the river, saves up and pushes a lane, uses its
 * ability when it pays off. Thinks every half second, with a little hesitation so it does not feel like a machine.
 */
public final class Bot {
    private final Match match;
    private final int side;
    private final Random random;
    private double thinkIn = 2.0;
    private int pushLane;

    public Bot(Match match, int side, long seed) {
        this.match = match;
        this.side = side;
        this.random = new Random(seed);
        this.pushLane = this.random.nextInt(2);
    }

    public int side() {
        return this.side;
    }

    public void tick() {
        if (this.match.over()) {
            return;
        }
        this.thinkIn -= Match.DT;
        if (this.thinkIn > 0) {
            return;
        }
        this.thinkIn = 0.45 + this.random.nextDouble() * 0.5;
        List<Unit> threats = this.threats();
        this.ability(threats);
        if (!threats.isEmpty()) {
            this.defend(threats);
        } else {
            this.attack();
        }
    }

    private Side me() {
        return this.match.side(this.side);
    }

    /** Enemy units on our half or about to reach a tower of ours. */
    private List<Unit> threats() {
        List<Unit> threats = new ArrayList<>();
        for (Unit unit : this.match.units()) {
            if (!unit.alive() || unit.side() == this.side || unit.burrowed()) {
                continue;
            }
            boolean ourHalf = Arena.halfOf(unit.y()) == this.side;
            boolean nearRiver = Math.abs(unit.y() - Arena.LENGTH / 2.0) < 3.5;
            if (ourHalf || nearRiver) {
                threats.add(unit);
            }
        }
        return threats;
    }

    private void defend(List<Unit> threats) {
        double[] center = center(threats);
        double threatHp = 0;
        boolean air = false;
        int small = 0;
        for (Unit unit : threats) {
            threatHp += unit.hp();
            air |= unit.air();
            if (unit.maxHp() < 400) {
                small++;
            }
        }
        Side me = this.me();
        // A spell on a crowd.
        for (int slot = 0; slot < Hand.SIZE; slot++) {
            CardDef card = me.hand().slot(slot);
            if (card.type() == CardDef.Type.SPELL && card.cost() <= me.essence() && card.spell().damage() > 0
                    && (count(threats, center, card.spell().radius()) >= 3 || threatHp > 1500)) {
                if (this.match.play(this.side, slot, center[0], center[1]) == Match.Play.OK) {
                    return;
                }
            }
        }
        // Small threats near the river can wait for the towers.
        if (threatHp < 350 && !this.closeToTower(center)) {
            return;
        }
        int best = -1;
        double bestScore = Double.NEGATIVE_INFINITY;
        for (int slot = 0; slot < Hand.SIZE; slot++) {
            CardDef card = me.hand().slot(slot);
            if (card.cost() > me.essence()) {
                continue;
            }
            double score = this.defenseScore(card, air, small, threatHp);
            if (score > bestScore) {
                bestScore = score;
                best = slot;
            }
        }
        if (best < 0 || bestScore <= 0) {
            return;
        }
        CardDef card = me.hand().slot(best);
        double[] spot = this.defenseSpot(card, center);
        this.playNear(best, card, spot[0], spot[1]);
    }

    private double defenseScore(CardDef card, boolean air, int small, double threatHp) {
        Catalog catalog = this.match.catalog();
        return switch (card.type()) {
            case SPELL -> -1;
            case TROOP -> {
                UnitDef unit = catalog.unit(card.unit());
                if (unit.targetsBuildingsOnly() || unit.burrow() != null) {
                    yield -1;
                }
                if (air && !unit.weapon().targets().hitsAir()) {
                    yield 0.1;
                }
                double dps = unit.weapon().damage() / unit.weapon().hitSpeed() * card.count();
                double score = dps / 40 + unit.hp() * card.count() / 600;
                if (small >= 3 && unit.weapon().splash() > 0) {
                    score += 3;
                }
                if (air && unit.weapon().targets().hitsAir()) {
                    score += 2;
                }
                yield score - card.cost() * 0.3 + this.random.nextDouble();
            }
            case BUILDING -> {
                StructureDef s = catalog.structure(card.structure());
                if (air && (s.weapon() == null || !s.weapon().targets().hitsAir())) {
                    yield 0.1;
                }
                yield 2.5 + (threatHp > 1200 ? 1.5 : 0) + this.random.nextDouble();
            }
            case BUILD -> {
                StructureDef s = catalog.structure(card.structure());
                if (air || s.kind() == StructureDef.Kind.BRIDGE) {
                    yield -1;
                }
                yield 1.5 + (s.kind() == StructureDef.Kind.KEG && small >= 2 ? 2 : 0) + this.random.nextDouble();
            }
        };
    }

    /** Where a defending card goes: troops between the threat and our tower, walls in its way, buildings central. */
    private double[] defenseSpot(CardDef card, double[] threat) {
        Tower tower = this.nearestTower(threat);
        double dx = tower.x() - threat[0];
        double dy = tower.y() - threat[1];
        double d = Math.max(1e-6, Math.hypot(dx, dy));
        return switch (card.type()) {
            case BUILDING -> new double[]{Arena.centerX(), Arena.sideY(this.side, 9.5)};
            case BUILD -> new double[]{threat[0] + dx / d * 2.5, threat[1] + dy / d * 2.5};
            default -> new double[]{threat[0] + dx / d * Math.min(2.5, d), threat[1] + dy / d * Math.min(2.5, d)};
        };
    }

    private void attack() {
        Side me = this.me();
        if (me.essence() < 8.5 + this.random.nextDouble()) {
            return;
        }
        int enemy = 1 - this.side;
        Tower left = this.match.watchtower(enemy, 0);
        Tower right = this.match.watchtower(enemy, 1);
        if (!left.alive() || !right.alive()) {
            this.pushLane = left.alive() ? 1 : 0;
        } else if (this.random.nextDouble() < 0.2) {
            this.pushLane = left.hp() < right.hp() ? 0 : 1;
        }
        double laneX = this.pushLane == 0 ? 3.5 : Arena.WIDTH - 3.5;
        // Prefer a building attacker, then the strongest troop we can pay for.
        int best = -1;
        double bestScore = Double.NEGATIVE_INFINITY;
        for (int slot = 0; slot < Hand.SIZE; slot++) {
            CardDef card = me.hand().slot(slot);
            if (card.cost() > me.essence() || card.type() != CardDef.Type.TROOP) {
                continue;
            }
            UnitDef unit = this.match.catalog().unit(card.unit());
            double score = card.cost() + (unit.targetsBuildingsOnly() ? 3 : 0) + this.random.nextDouble() * 2;
            if (score > bestScore) {
                bestScore = score;
                best = slot;
            }
        }
        if (best >= 0) {
            CardDef card = me.hand().slot(best);
            UnitDef unit = this.match.catalog().unit(card.unit());
            if (unit.burrow() != null) {
                Tower target = this.match.watchtower(enemy, this.pushLane).alive()
                        ? this.match.watchtower(enemy, this.pushLane) : this.match.keep(enemy);
                this.playNear(best, card, target.x(), target.y() - Arena.forward(this.side) * 2.5);
            } else {
                this.playNear(best, card, laneX, Arena.sideY(this.side, 12.5));
            }
            return;
        }
        // Nothing to push with: open a middle bridge or cycle a cheap card.
        for (int slot = 0; slot < Hand.SIZE; slot++) {
            CardDef card = me.hand().slot(slot);
            if (card.cost() <= me.essence() && card.type() == CardDef.Type.BUILD
                    && this.match.catalog().structure(card.structure()).kind() == StructureDef.Kind.BRIDGE) {
                this.playNear(slot, card, Arena.centerX(), (Arena.RIVER_Y0 + Arena.RIVER_Y1) / 2.0);
                return;
            }
        }
    }

    private void ability(List<Unit> threats) {
        if (this.match.abilityCooldown(this.side) > 0) {
            return;
        }
        SpellDef spell = this.me().faction().ability().spell();
        if (spell.haste() > 0) {
            List<Unit> own = new ArrayList<>();
            for (Unit unit : this.match.units()) {
                if (unit.alive() && unit.side() == this.side && Arena.halfOf(unit.y()) != this.side) {
                    own.add(unit);
                }
            }
            if (own.size() >= 2) {
                double[] c = center(own);
                this.match.ability(this.side, c[0], c[1]);
            }
            return;
        }
        if (threats.isEmpty()) {
            return;
        }
        double[] c = center(threats);
        double hp = 0;
        for (Unit unit : threats) {
            hp += unit.hp();
        }
        if (spell.summon() != null) {
            if (hp > 400 && this.closeToTower(c)) {
                double[] spot = this.nearestInZone(c[0], c[1] + Arena.forward(this.side) * -1.5);
                if (spot != null) {
                    this.match.ability(this.side, spot[0], spot[1]);
                }
            }
            return;
        }
        if (count(threats, c, spell.radius()) >= 2 || hp > 1200) {
            this.match.ability(this.side, c[0], c[1]);
        }
    }

    /** Plays the card at the spot, or at the nearest spot nearby where it fits. */
    private void playNear(int slot, CardDef card, double x, double y) {
        for (int r = 0; r <= 4; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dy = -r; dy <= r; dy++) {
                    if (Math.max(Math.abs(dx), Math.abs(dy)) != r) {
                        continue;
                    }
                    double px = Math.floor(x) + dx + 0.5;
                    double py = Math.floor(y) + dy + 0.5;
                    if (this.match.canPlace(this.side, card, px, py)
                            && this.match.play(this.side, slot, px, py) == Match.Play.OK) {
                        return;
                    }
                }
            }
        }
    }

    private double[] nearestInZone(double x, double y) {
        for (int r = 0; r <= 6; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dy = -r; dy <= r; dy++) {
                    int tx = (int) Math.floor(x) + dx;
                    int ty = (int) Math.floor(y) + dy;
                    if (this.match.inZone(this.side, tx, ty) && this.match.grid().free(tx, ty)) {
                        return new double[]{tx + 0.5, ty + 0.5};
                    }
                }
            }
        }
        return null;
    }

    private boolean closeToTower(double[] at) {
        Tower tower = this.nearestTower(at);
        return tower.distanceTo(at[0], at[1]) < 8;
    }

    private Tower nearestTower(double[] at) {
        Tower best = this.match.keep(this.side);
        double bestDist = best.distanceTo(at[0], at[1]);
        for (Tower tower : this.match.towers()) {
            if (tower.alive() && tower.side() == this.side && tower.distanceTo(at[0], at[1]) < bestDist) {
                best = tower;
                bestDist = tower.distanceTo(at[0], at[1]);
            }
        }
        return best;
    }

    private static double[] center(List<Unit> units) {
        double x = 0;
        double y = 0;
        double weight = 0;
        for (Unit unit : units) {
            x += unit.x() * unit.hp();
            y += unit.y() * unit.hp();
            weight += unit.hp();
        }
        return weight > 0 ? new double[]{x / weight, y / weight} : new double[]{Arena.centerX(), Arena.LENGTH / 2.0};
    }

    private static int count(List<Unit> units, double[] at, double radius) {
        int n = 0;
        for (Unit unit : units) {
            if (Math.hypot(unit.x() - at[0], unit.y() - at[1]) <= radius) {
                n++;
            }
        }
        return n;
    }
}
