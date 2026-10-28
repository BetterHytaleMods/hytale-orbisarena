package de.hymods.orbisarena.sim;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MatchTest {
    private static final Catalog CATALOG = Catalog.load();

    private static Match match(String f0, String f1) {
        return new Match(CATALOG, Rules.standard(), CATALOG.faction(f0), CATALOG.faction(f1), 7);
    }

    private static CardDef card(String faction, String id) {
        CardDef card = CATALOG.faction(faction).card(id);
        assertNotNull(card, id);
        return card;
    }

    private static void run(Match match, double seconds) {
        for (int i = 0; i < Math.round(seconds / Match.DT); i++) {
            match.tick();
        }
    }

    @Test
    void catalogHasFourFullFactions() {
        assertEquals(List.of("kweebec", "trork", "undead", "goblin"),
                CATALOG.factions().stream().map(Faction::id).toList());
        for (Faction faction : CATALOG.factions()) {
            assertEquals(Catalog.DECK_SIZE, faction.cards().size(), faction.id());
            long builds = faction.cards().stream()
                    .filter(c -> c.type() == CardDef.Type.BUILD || c.type() == CardDef.Type.BUILDING).count();
            assertTrue(builds >= 1, faction.id() + " has a build card");
        }
    }

    @Test
    void towersStandMirrored() {
        Match match = match("trork", "kweebec");
        Tower left0 = match.watchtower(0, 0);
        Tower left1 = match.watchtower(1, 0);
        assertEquals(left0.x(), left1.x(), 1e-9);
        assertEquals(Arena.LENGTH - left0.y(), left1.y(), 1e-9);
        assertEquals(Arena.centerX(), match.keep(0).x(), 1e-9);
        assertEquals(Arena.LENGTH - match.keep(0).y(), match.keep(1).y(), 1e-9);
    }

    @Test
    void essenceFillsUpAndDoubles() {
        Match match = match("trork", "kweebec");
        run(match, 2.8 * 2);
        assertEquals(7, match.side(0).essence(), 0.05);
        run(match, 30);
        assertEquals(10, match.side(0).essence(), 1e-9);
        assertFalse(match.doubleEssence());
        run(match, 120 - match.time() + 0.1);
        assertTrue(match.doubleEssence());
    }

    @Test
    void playingTakesEssenceAndCyclesTheHand() {
        Match match = match("trork", "kweebec");
        Hand hand = match.side(0).hand();
        CardDef first = hand.slot(0);
        CardDef next = hand.next();
        run(match, 20);
        double x = 9;
        double y = first.type() == CardDef.Type.BUILD
                && CATALOG.structure(first.structure()).kind() == StructureDef.Kind.BRIDGE ? 16 : 10;
        assertEquals(Match.Play.OK, match.play(0, 0, x, y));
        assertEquals(10 - first.cost(), match.side(0).essence(), 0.1);
        assertEquals(next, hand.slot(0));
    }

    @Test
    void cannotPlayTroopsOnTheEnemyHalf() {
        Match match = match("trork", "kweebec");
        CardDef warband = card("trork", "trork_warband");
        assertFalse(match.canPlace(0, warband, 4, 20));
        assertFalse(match.canPlace(0, warband, 4, 16));
        assertTrue(match.canPlace(0, warband, 4, 12));
        assertTrue(match.canPlace(1, warband, 4, 20));
        // Not inside a tower.
        assertFalse(match.canPlace(0, warband, 3.5, 6.5));
    }

    @Test
    void warbandMarchesOverTheBridgeAndHitsTheTower() {
        Match match = match("trork", "kweebec");
        assertEquals(Match.Play.OK, match.deploy(0, card("trork", "trork_warband"), 3.5, 12));
        Tower target = match.watchtower(1, 0);
        double before = target.hp();
        run(match, 30);
        assertTrue(target.hp() < before, "the enemy watchtower took damage");
    }

    @Test
    void buildingAttackerWalksPastUnits() {
        Match match = match("trork", "goblin");
        match.deploy(0, card("trork", "boar_charger"), 14.5, 13);
        match.deploy(1, card("goblin", "scrapper_gang"), 14.5, 20);
        run(match, 6);
        Unit boar = match.units().stream().filter(u -> u.def().id().equals("boar_charger")).findFirst().orElseThrow();
        assertNotNull(boar.target());
        assertTrue(boar.target().building(), "the boar only wants buildings");
    }

    @Test
    void minerTunnelsToTheEnemyTower() {
        Match match = match("goblin", "trork");
        Tower target = match.watchtower(1, 1);
        assertTrue(match.canPlace(0, card("goblin", "miner"), target.x(), target.y() - 2.5));
        assertEquals(Match.Play.OK, match.deploy(0, card("goblin", "miner"), target.x(), target.y() - 2.5));
        Unit miner = match.units().getFirst();
        assertTrue(miner.burrowed());
        run(match, 20);
        assertFalse(miner.burrowed());
        assertTrue(target.hp() < target.maxHp(), "the miner came up and hit the tower");
    }

    @Test
    void rootBridgeOpensTheMiddle() {
        Match match = match("kweebec", "trork");
        CardDef bridge = card("kweebec", "root_bridge");
        assertFalse(match.grid().free(9, 15));
        assertFalse(match.canPlace(0, bridge, 3.5, 16), "no bridge on a bridge");
        assertFalse(match.canPlace(0, bridge, 9, 8), "only on the river");
        assertEquals(Match.Play.OK, match.deploy(0, bridge, 9, 16));
        assertTrue(match.grid().free(9, 15));
        assertTrue(match.grid().free(9, 16));
        run(match, 41);
        assertFalse(match.grid().free(9, 15), "the bridge withered");
    }

    @Test
    void wallsBlockAndGetBroken() {
        Match match = match("trork", "undead");
        // A bone wall right behind side 1's left bridge.
        assertEquals(Match.Play.OK, match.deploy(1, card("undead", "bone_wall"), 3.5, 17.5));
        Structure wall = match.structures().getFirst();
        assertEquals(StructureDef.Kind.WALL, wall.def().kind());
        match.deploy(0, card("trork", "mauler"), 3.5, 13);
        double decayOnly = wall.maxHp() * 12 / wall.def().lifetime();
        run(match, 12);
        boolean broken = !wall.alive() || wall.maxHp() - wall.hp() > decayOnly + 1;
        boolean around = match.units().stream().anyMatch(u -> u.side() == 0 && u.y() > 18.5);
        assertTrue(broken || around, "the mauler broke the wall or walked around it");
    }

    @Test
    void kegsBlowUpUnderAttackers() {
        Match match = match("goblin", "trork");
        assertEquals(Match.Play.OK, match.deploy(0, card("goblin", "powder_kegs"), 3.5, 12.5));
        assertEquals(3, match.structures().size());
        match.deploy(1, card("trork", "trork_warband"), 3.5, 19);
        run(match, 15);
        assertTrue(match.structures().size() < 3, "a keg went off");
    }

    @Test
    void spellHurtsTowersLess() {
        Match match = match("undead", "trork");
        Tower tower = match.watchtower(1, 0);
        CardDef chill = card("undead", "grave_chill");
        match.deploy(0, chill, tower.x(), tower.y());
        run(match, 0.1);
        assertEquals(tower.maxHp() - chill.spell().damage() * chill.spell().towerPct(), tower.hp(), 1e-6);
    }

    @Test
    void abilityHasACooldown() {
        Match match = match("goblin", "trork");
        assertEquals(Match.Play.COOLDOWN, match.ability(0, 9, 20));
        run(match, 20.1);
        assertEquals(Match.Play.OK, match.ability(0, 9, 20));
        assertEquals(Match.Play.COOLDOWN, match.ability(0, 9, 20));
    }

    @Test
    void raiseDeadOnlyOnTheOwnHalf() {
        Match match = match("undead", "trork");
        run(match, 20.1);
        assertEquals(Match.Play.BAD_PLACE, match.ability(0, 9, 25));
        assertEquals(Match.Play.OK, match.ability(0, 9, 10));
        run(match, 0.1);
        assertEquals(3, match.units().size());
    }

    @Test
    void destroyingAWatchtowerOpensAPocket() {
        Match match = match("trork", "kweebec");
        CardDef warband = card("trork", "trork_warband");
        assertFalse(match.inZone(0, 3, 18));
        Tower tower = match.watchtower(1, 0);
        tower.hp = 1;
        match.deploy(0, card("trork", "spear_hunters"), 3.5, 13);
        run(match, 15);
        assertFalse(tower.alive());
        assertEquals(1, match.side(0).crowns());
        assertTrue(match.inZone(0, 3, 18));
        assertTrue(match.canPlace(0, warband, 3.5, 18.5));
        assertFalse(match.inZone(0, 14, 18), "only on that lane");
    }

    @Test
    void theKeepEndsTheMatch() {
        Match match = match("trork", "kweebec");
        match.keep(1).hp = 1;
        match.watchtower(1, 0).hp = 1;
        match.deploy(0, card("trork", "spear_hunters"), 3.5, 13);
        run(match, 60);
        assertTrue(match.over());
        assertEquals(0, match.winner());
        assertEquals(3, match.side(0).crowns());
    }

    @Test
    void aTieGoesToOvertimeThenToTheWeakestTower() {
        Match match = match("trork", "kweebec");
        run(match, 180.1);
        assertEquals(Match.Phase.OVERTIME, match.phase());
        match.watchtower(1, 1).hp -= 100;
        run(match, 60);
        assertTrue(match.over());
        assertEquals(0, match.winner());
    }

    @Test
    void botsFinishEveryPairing() {
        for (Faction a : CATALOG.factions()) {
            for (Faction b : CATALOG.factions()) {
                Match match = new Match(CATALOG, Rules.standard(), a, b, a.id().hashCode() * 31L + b.id().hashCode());
                Bot bot0 = new Bot(match, 0, 1);
                Bot bot1 = new Bot(match, 1, 2);
                int plays = 0;
                while (!match.over() && match.time() < Rules.standard().end() + 1) {
                    bot0.tick();
                    bot1.tick();
                    match.tick();
                    for (MatchEvent event : match.drainEvents()) {
                        if (event instanceof MatchEvent.CardPlayed) {
                            plays++;
                        }
                    }
                }
                assertTrue(match.over(), a.id() + " vs " + b.id() + " ended");
                assertTrue(plays > 20, a.id() + " vs " + b.id() + " played cards: " + plays);
                System.out.printf("%-8s vs %-8s: winner %2d, crowns %d:%d, %.0f s, %d cards%n", a.id(), b.id(),
                        match.winner(), match.side(0).crowns(), match.side(1).crowns(), match.time(), plays);
            }
        }
    }

    @Test
    void unitsNeverStandInWaterOrTowers() {
        Match match = new Match(CATALOG, Rules.standard(), CATALOG.faction("undead"), CATALOG.faction("goblin"), 3);
        Bot bot0 = new Bot(match, 0, 5);
        Bot bot1 = new Bot(match, 1, 6);
        while (!match.over()) {
            bot0.tick();
            bot1.tick();
            match.tick();
            for (Unit unit : match.units()) {
                if (unit.air() || unit.burrowed()) {
                    continue;
                }
                int tx = (int) Math.floor(unit.x());
                int ty = (int) Math.floor(unit.y());
                assertFalse(match.grid().water(tx, ty) && unit.def().radius() < 0.6,
                        unit.def().id() + " in water at " + unit.x() + "," + unit.y() + " t=" + match.time());
                assertNotEquals(true, match.grid().tower(tx, ty) != null,
                        unit.def().id() + " inside a tower at " + unit.x() + "," + unit.y());
            }
        }
    }
}
