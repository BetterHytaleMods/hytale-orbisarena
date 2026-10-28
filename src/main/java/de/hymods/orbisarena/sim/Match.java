package de.hymods.orbisarena.sim;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Random;

/**
 * One match between two sides, ticked 20 times a second. Knows nothing about Hytale: the view reads positions
 * and drains {@link #drainEvents() events} after every tick.
 */
public final class Match {
    public static final double DT = 0.05;
    private static final double RETARGET = 0.25;
    private static final double REPATH = 0.6;
    private static final double TERRAIN_RADIUS = 0.42;

    public enum Phase {
        REGULAR, OVERTIME, ENDED
    }

    public enum Play {
        OK, NO_ESSENCE, BAD_PLACE, COOLDOWN, OVER
    }

    private final Catalog catalog;
    private final Rules rules;
    private final Random random;
    private final Side[] sides = new Side[2];
    private final Grid grid = new Grid();
    private final List<Unit> units = new ArrayList<>();
    private final List<Tower> towers = new ArrayList<>();
    private final List<Structure> structures = new ArrayList<>();
    private final List<Projectile> projectiles = new ArrayList<>();
    private final List<Pending> pending = new ArrayList<>();
    private final List<MatchEvent> events = new ArrayList<>();
    private int nextId = 1;
    private double time;
    private Phase phase = Phase.REGULAR;
    private boolean doubleEssence;
    private int winner = -2;

    private record Pending(int side, SpellDef spell, double x, double y, double at) {
    }

    public Match(Catalog catalog, Rules rules, Faction side0, Faction side1, long seed) {
        this.catalog = catalog;
        this.rules = rules;
        this.random = new Random(seed);
        Faction[] factions = {side0, side1};
        for (int s = 0; s < 2; s++) {
            Faction faction = factions[s];
            this.sides[s] = new Side(s, faction, this.random, rules.startEssence(), faction.ability().initial());
            // Lanes count from side 0's left for both sides, so a lane names the same bridge for everyone.
            for (int lane = 0; lane < 2; lane++) {
                int x0 = lane == 0 ? Arena.WATCHTOWER_X0 : Arena.WIDTH - Arena.WATCHTOWER_X0 - Arena.WATCHTOWER_SIZE;
                Tower tower = new Tower(this.nextId++, s, false, lane, x0,
                        Arena.sideTileY(s, Arena.WATCHTOWER_Y0, Arena.WATCHTOWER_SIZE), Arena.WATCHTOWER_SIZE,
                        rules.watchtowerHp(), rules.watchtower());
                this.towers.add(tower);
                this.grid.put(tower);
            }
            Tower keep = new Tower(this.nextId++, s, true, -1,
                    Arena.sideTileX(s, Arena.KEEP_X0, Arena.KEEP_SIZE), Arena.sideTileY(s, Arena.KEEP_Y0, Arena.KEEP_SIZE),
                    Arena.KEEP_SIZE, rules.keepHp(), faction.avatar());
            this.towers.add(keep);
            this.grid.put(keep);
        }
    }

    // ---- reading ----

    public Catalog catalog() {
        return this.catalog;
    }

    public Rules rules() {
        return this.rules;
    }

    public Side side(int s) {
        return this.sides[s];
    }

    public Grid grid() {
        return this.grid;
    }

    public List<Unit> units() {
        return this.units;
    }

    public List<Tower> towers() {
        return this.towers;
    }

    public List<Structure> structures() {
        return this.structures;
    }

    public List<Projectile> projectiles() {
        return this.projectiles;
    }

    public double time() {
        return this.time;
    }

    public Phase phase() {
        return this.phase;
    }

    public boolean doubleEssence() {
        return this.doubleEssence;
    }

    /** Seconds left in the current phase. */
    public double timeLeft() {
        double end = this.phase == Phase.REGULAR ? this.rules.regular() : this.rules.end();
        return Math.max(0, end - this.time);
    }

    public boolean over() {
        return this.phase == Phase.ENDED;
    }

    /** 0 or 1, -1 for a draw; only meaningful once the match is over. */
    public int winner() {
        return this.winner;
    }

    public Tower keep(int side) {
        for (Tower tower : this.towers) {
            if (tower.side() == side && tower.keep) {
                return tower;
            }
        }
        throw new IllegalStateException();
    }

    public Tower watchtower(int side, int lane) {
        for (Tower tower : this.towers) {
            if (tower.side() == side && tower.lane == lane) {
                return tower;
            }
        }
        throw new IllegalStateException();
    }

    public List<MatchEvent> drainEvents() {
        List<MatchEvent> drained = List.copyOf(this.events);
        this.events.clear();
        return drained;
    }

    public double abilityCooldown(int side) {
        return Math.max(0, this.sides[side].abilityReadyAt - this.time);
    }

    // ---- playing ----

    /** Plays the card in a hand slot at a spot. */
    public Play play(int s, int slot, double x, double y) {
        if (this.over()) {
            return Play.OVER;
        }
        Side side = this.sides[s];
        CardDef card = side.hand.slot(slot);
        if (card == null) {
            return Play.BAD_PLACE;
        }
        if (side.essence < card.cost()) {
            return Play.NO_ESSENCE;
        }
        if (!this.canPlace(s, card, x, y)) {
            return Play.BAD_PLACE;
        }
        side.essence -= card.cost();
        side.hand.take(slot);
        return this.deploy(s, card, x, y);
    }

    /** Puts a card on the field without asking the hand or the essence. */
    Play deploy(int s, CardDef card, double x, double y) {
        if (!this.canPlace(s, card, x, y)) {
            return Play.BAD_PLACE;
        }
        this.events.add(new MatchEvent.CardPlayed(s, card, x, y));
        switch (card.type()) {
            case TROOP -> this.spawnTroop(s, card, x, y);
            case SPELL -> this.cast(s, card.spell(), x, y);
            case BUILDING, BUILD -> this.build(s, card, x, y);
        }
        return Play.OK;
    }

    /** Fires the avatar's ability at a spot. */
    public Play ability(int s, double x, double y) {
        if (this.over()) {
            return Play.OVER;
        }
        Side side = this.sides[s];
        AbilityDef ability = side.faction.ability();
        if (this.time < side.abilityReadyAt) {
            return Play.COOLDOWN;
        }
        if (!Arena.inside(x, y) || (ability.ownHalf() && !this.inZone(s, (int) Math.floor(x), (int) Math.floor(y)))) {
            return Play.BAD_PLACE;
        }
        side.abilityReadyAt = this.time + ability.cooldown();
        this.events.add(new MatchEvent.AbilityUsed(s, ability, x, y));
        this.cast(s, ability.spell(), x, y);
        return Play.OK;
    }

    /** Whether a card may be played there (essence aside). */
    public boolean canPlace(int s, CardDef card, double x, double y) {
        if (!Arena.inside(x, y)) {
            return false;
        }
        int tx = (int) Math.floor(x);
        int ty = (int) Math.floor(y);
        return switch (card.type()) {
            case SPELL -> true;
            case TROOP -> {
                UnitDef unit = this.catalog.unit(card.unit());
                if (unit.burrow() != null) {
                    yield this.grid.free(tx, ty);
                }
                yield this.grid.free(tx, ty) && this.inZone(s, tx, ty);
            }
            case BUILDING, BUILD -> this.footprint(s, this.catalog.structure(card.structure()), x, y) != null;
        };
    }

    /** Own half, plus the pocket behind a destroyed enemy watchtower. */
    public boolean inZone(int s, int tx, int ty) {
        if (!Arena.inside(tx, ty)) {
            return false;
        }
        if (Arena.halfOf(ty + 0.5) == s) {
            return true;
        }
        int enemy = 1 - s;
        int lane = Arena.laneOf(tx + 0.5);
        if (this.watchtower(enemy, lane).alive) {
            return false;
        }
        return s == 0
                ? ty >= Arena.RIVER_Y1 && ty < Arena.RIVER_Y1 + Arena.POCKET_DEPTH
                : ty < Arena.RIVER_Y0 && ty >= Arena.RIVER_Y0 - Arena.POCKET_DEPTH;
    }

    /** The lower-left tile and size of a structure placed at a spot, or null if it does not fit there. */
    public int[] footprint(int s, StructureDef def, double x, double y) {
        if (def.kind() == StructureDef.Kind.BRIDGE) {
            int x0 = (int) Math.round(x - def.w() / 2.0);
            if (Math.abs(y - (Arena.RIVER_Y0 + Arena.RIVER_Y1) / 2.0) > 2.0) {
                return null;
            }
            for (int tx = x0; tx < x0 + def.w(); tx++) {
                for (int ty = Arena.RIVER_Y0; ty < Arena.RIVER_Y1; ty++) {
                    if (!Arena.inside(tx, ty) || !this.grid.water(tx, ty)) {
                        return null;
                    }
                }
            }
            return new int[]{x0, Arena.RIVER_Y0, def.w(), Arena.RIVER_Y1 - Arena.RIVER_Y0};
        }
        int x0 = (int) Math.round(x - def.w() / 2.0);
        int y0 = (int) Math.round(y - def.h() / 2.0);
        for (int tx = x0; tx < x0 + def.w(); tx++) {
            for (int ty = y0; ty < y0 + def.h(); ty++) {
                if (!this.grid.free(tx, ty) || !this.inZone(s, tx, ty)) {
                    return null;
                }
                if (def.kind() == StructureDef.Kind.HEDGE && this.hedgeAt(tx + 0.5, ty + 0.5) != null) {
                    return null;
                }
            }
        }
        return new int[]{x0, y0, def.w(), def.h()};
    }

    private Structure hedgeAt(double x, double y) {
        for (Structure s : this.structures) {
            if (s.alive && s.def.kind() == StructureDef.Kind.HEDGE && s.covers(x, y, 0)) {
                return s;
            }
        }
        return null;
    }

    private void spawnTroop(int s, CardDef card, double x, double y) {
        UnitDef def = this.catalog.unit(card.unit());
        for (int i = 0; i < card.count(); i++) {
            double[] at = this.formation(x, y, i, card.count());
            if (def.burrow() != null) {
                Tower keep = this.keep(s);
                Unit unit = new Unit(this.nextId++, s, def, i, card.id(), keep.x, keep.y, 0);
                unit.burrowed = true;
                unit.destX = at[0];
                unit.destY = at[1];
                this.add(unit);
            } else {
                this.add(new Unit(this.nextId++, s, def, i, card.id(), at[0], at[1], this.rules.deployTime()));
            }
        }
    }

    /** Spot of unit i of n around a point, on walkable ground where possible. */
    private double[] formation(double x, double y, int i, int n) {
        if (n == 1) {
            return new double[]{x, y};
        }
        double ring = n <= 3 ? 0.55 : 0.9;
        double angle = 2 * Math.PI * i / n + 0.3;
        double fx = x + Math.cos(angle) * ring;
        double fy = y + Math.sin(angle) * ring;
        if (this.grid.solid((int) Math.floor(fx), (int) Math.floor(fy))) {
            return new double[]{x, y};
        }
        return new double[]{fx, fy};
    }

    private void add(Unit unit) {
        this.units.add(unit);
        this.events.add(new MatchEvent.UnitSpawned(unit));
    }

    private void cast(int s, SpellDef spell, double x, double y) {
        this.events.add(new MatchEvent.SpellCast(s, spell, x, y, spell.delay()));
        if (spell.delay() <= 0) {
            this.land(s, spell, x, y);
        } else {
            this.pending.add(new Pending(s, spell, x, y, this.time + spell.delay()));
        }
    }

    private void land(int s, SpellDef spell, double x, double y) {
        this.events.add(new MatchEvent.SpellLanded(s, spell, x, y));
        int enemy = 1 - s;
        for (Unit unit : List.copyOf(this.units)) {
            if (!unit.alive || unit.burrowed || unit.distanceTo(x, y) > spell.radius()) {
                continue;
            }
            if (unit.side() == enemy) {
                if (unit.air() && !spell.hitsAir()) {
                    continue;
                }
                if (spell.slow() > 0) {
                    unit.slowMul = Math.min(unit.slowUntil > this.time ? unit.slowMul : 1, spell.slow());
                    unit.slowUntil = this.time + spell.slowTime();
                }
                if (spell.root() > 0 && !unit.air()) {
                    unit.rootUntil = this.time + spell.root();
                }
                if (spell.damage() > 0) {
                    this.hurt(unit, spell.damage());
                }
            } else if (spell.haste() > 0) {
                unit.hasteMul = spell.haste();
                unit.hasteUntil = this.time + spell.hasteTime();
            }
        }
        if (spell.damage() > 0) {
            for (Tower tower : this.towers) {
                if (tower.alive && tower.side() == enemy && tower.distanceTo(x, y) <= spell.radius()) {
                    this.hurt(tower, spell.damage() * spell.towerPct());
                }
            }
            for (Structure structure : List.copyOf(this.structures)) {
                if (structure.alive && structure.side() == enemy && structure.hittable()
                        && structure.distanceTo(x, y) <= spell.radius()) {
                    this.hurt(structure, spell.damage());
                }
            }
        }
        if (spell.summon() != null) {
            UnitDef def = this.catalog.unit(spell.summon());
            for (int i = 0; i < spell.summonCount(); i++) {
                double[] at = this.formation(x, y, i, spell.summonCount());
                this.add(new Unit(this.nextId++, s, def, i, null, at[0], at[1], this.rules.deployTime() / 2));
            }
        }
    }

    private void build(int s, CardDef card, double x, double y) {
        StructureDef def = this.catalog.structure(card.structure());
        int[] at = this.footprint(s, def, x, y);
        if (def.kind() == StructureDef.Kind.KEG) {
            for (int tx = at[0]; tx < at[0] + at[2]; tx++) {
                for (int ty = at[1]; ty < at[1] + at[3]; ty++) {
                    this.place(new Structure(this.nextId++, s, def, card.id(), tx, ty, 1, 1, def.hp()));
                }
            }
        } else {
            this.place(new Structure(this.nextId++, s, def, card.id(), at[0], at[1], at[2], at[3], def.hp()));
        }
    }

    private void place(Structure structure) {
        this.structures.add(structure);
        this.grid.put(structure);
        this.events.add(new MatchEvent.StructureBuilt(structure));
        // Whoever stood there is pushed out by the terrain step.
    }

    // ---- ticking ----

    public void tick() {
        if (this.over()) {
            return;
        }
        this.time += DT;
        this.essence();
        this.spells();
        for (Structure structure : List.copyOf(this.structures)) {
            if (structure.alive) {
                this.updateStructure(structure);
            }
        }
        for (Tower tower : this.towers) {
            if (tower.alive) {
                this.shoot(tower, tower.weapon, tower.x, tower.y, true);
            }
        }
        for (Unit unit : List.copyOf(this.units)) {
            if (unit.alive) {
                this.updateUnit(unit);
            }
        }
        this.separate();
        this.updateProjectiles();
        this.units.removeIf(unit -> !unit.alive);
        this.structures.removeIf(structure -> !structure.alive);
        this.projectiles.removeIf(projectile -> !projectile.alive);
        this.clock();
    }

    private void essence() {
        if (!this.doubleEssence && this.time >= this.rules.doubleFrom()) {
            this.doubleEssence = true;
            this.events.add(new MatchEvent.DoubleEssence());
        }
        double rate = DT / this.rules.essenceEvery() * (this.doubleEssence ? 2 : 1);
        for (Side side : this.sides) {
            side.essence = Math.min(this.rules.maxEssence(), side.essence + rate);
        }
    }

    private void spells() {
        for (Iterator<Pending> it = this.pending.iterator(); it.hasNext(); ) {
            Pending p = it.next();
            if (this.time >= p.at) {
                it.remove();
                this.land(p.side, p.spell, p.x, p.y);
            }
        }
    }

    private void clock() {
        if (this.over()) {
            return;
        }
        int c0 = this.sides[0].crowns;
        int c1 = this.sides[1].crowns;
        if (this.phase == Phase.REGULAR && this.time >= this.rules.regular()) {
            if (c0 != c1) {
                this.end(c0 > c1 ? 0 : 1);
            } else {
                this.phase = Phase.OVERTIME;
                this.events.add(new MatchEvent.PhaseChanged(Phase.OVERTIME));
            }
        } else if (this.phase == Phase.OVERTIME) {
            if (c0 != c1) {
                this.end(c0 > c1 ? 0 : 1);
            } else if (this.time >= this.rules.end()) {
                double w0 = this.weakest(0);
                double w1 = this.weakest(1);
                this.end(Math.abs(w0 - w1) < 1e-6 ? -1 : (w0 > w1 ? 0 : 1));
            }
        }
    }

    /** Health share of a side's weakest standing tower. */
    public double weakest(int side) {
        double weakest = 1;
        for (Tower tower : this.towers) {
            if (tower.side() == side && tower.alive) {
                weakest = Math.min(weakest, tower.hp / tower.maxHp);
            }
        }
        return weakest;
    }

    private void end(int winner) {
        this.winner = winner;
        this.phase = Phase.ENDED;
        this.events.add(new MatchEvent.PhaseChanged(Phase.ENDED));
        this.events.add(new MatchEvent.MatchEnded(winner));
    }

    // ---- structures and towers ----

    private void updateStructure(Structure s) {
        StructureDef def = s.def;
        s.age += DT;
        switch (def.kind()) {
            case HEDGE, BRIDGE -> {
                if (s.age >= def.lifetime()) {
                    this.remove(s, false);
                    return;
                }
            }
            case WALL, BUILDING, KEG -> {
                s.hp -= s.maxHp / def.lifetime() * DT;
                if (s.hp <= 0) {
                    this.remove(s, false);
                    return;
                }
            }
        }
        if (def.kind() == StructureDef.Kind.HEDGE) {
            for (Unit unit : this.units) {
                if (unit.alive && unit.side() != s.side() && !unit.air() && !unit.burrowed && s.covers(unit.x, unit.y, 0.15)) {
                    unit.slowMul = Math.min(unit.slowUntil > this.time ? unit.slowMul : 1, def.hedge().slow());
                    unit.slowUntil = this.time + 0.3;
                    this.hurt(unit, def.hedge().dps() * DT);
                }
            }
        }
        if (def.kind() == StructureDef.Kind.KEG) {
            for (Unit unit : this.units) {
                if (unit.alive && unit.side() != s.side() && !unit.air() && !unit.burrowed && !unit.deploying()
                        && s.distanceTo(unit.x, unit.y) <= def.keg().trigger()) {
                    this.explode(s);
                    return;
                }
            }
        }
        if (def.weapon() != null) {
            this.shoot(s, def.weapon(), s.x, s.y, false);
        }
        if (def.spawn() != null) {
            s.spawnIn -= DT;
            if (s.spawnIn <= 0) {
                s.spawnIn += def.spawn().every();
                this.spawnFrom(s, def.spawn().count());
            }
        }
    }

    private void spawnFrom(Structure s, int count) {
        UnitDef def = this.catalog.unit(s.def.spawn().unit());
        double front = Arena.forward(s.side()) > 0 ? s.y0 + s.h + 0.5 : s.y0 - 0.5;
        for (int i = 0; i < count; i++) {
            double x = s.x0 + s.w / 2.0 + (i - (count - 1) / 2.0) * 0.8;
            double y = front;
            if (this.grid.solid((int) Math.floor(x), (int) Math.floor(y))) {
                y = s.y;
                x = s.x0 - 0.5;
            }
            this.add(new Unit(this.nextId++, s.side(), def, i, null, x, y, 0.3));
        }
    }

    private void explode(Structure keg) {
        if (!keg.alive) {
            return;
        }
        this.events.add(new MatchEvent.KegExploded(keg));
        this.remove(keg, true);
        StructureDef.Keg k = keg.def.keg();
        int enemy = 1 - keg.side();
        for (Unit unit : List.copyOf(this.units)) {
            if (unit.alive && unit.side() == enemy && !unit.air() && !unit.burrowed && unit.distanceTo(keg.x, keg.y) <= k.radius()) {
                this.hurt(unit, k.damage());
            }
        }
        for (Tower tower : this.towers) {
            if (tower.alive && tower.side() == enemy && tower.distanceTo(keg.x, keg.y) <= k.radius()) {
                this.hurt(tower, k.damage() * this.rules.buildingTowerPct());
            }
        }
    }

    private void remove(Structure s, boolean destroyed) {
        if (!s.alive) {
            return;
        }
        s.alive = false;
        this.grid.remove(s);
        this.events.add(new MatchEvent.StructureGone(s, destroyed));
        if (destroyed && s.def.spawn() != null && s.def.spawn().onDeath() > 0) {
            this.spawnFrom(s, s.def.spawn().onDeath());
        }
        if (destroyed && s.def.kind() == StructureDef.Kind.KEG) {
            this.explode(s);
        }
    }

    /** Towers and shooting buildings: keep the target while it is in range, else take the nearest. */
    private void shoot(Body shooter, Weapon weapon, double fromX, double fromY, boolean tower) {
        Body target = tower ? ((Tower) shooter).target : ((Structure) shooter).target;
        if (target == null || !target.alive || !this.hittable(weapon, target)
                || shooter.distanceTo(target.x, target.y) - radius(target) > weapon.range()) {
            target = null;
            double best = Double.MAX_VALUE;
            for (Unit unit : this.units) {
                if (unit.alive && unit.side() != shooter.side() && this.hittable(weapon, unit)) {
                    double gap = shooter.distanceTo(unit.x, unit.y) - unit.radius;
                    if (gap <= weapon.range() && gap < best) {
                        best = gap;
                        target = unit;
                    }
                }
            }
        }
        double timer = tower ? ((Tower) shooter).attackTimer : ((Structure) shooter).attackTimer;
        if (target == null) {
            timer = Math.max(timer, weapon.firstHit());
        } else {
            timer -= DT;
            if (timer <= 0) {
                timer += weapon.hitSpeed();
                this.fire(shooter, weapon, target, fromX, fromY, 1);
            }
        }
        if (tower) {
            ((Tower) shooter).target = target;
            ((Tower) shooter).attackTimer = timer;
        } else {
            ((Structure) shooter).target = target;
            ((Structure) shooter).attackTimer = timer;
        }
    }

    private boolean hittable(Weapon weapon, Body body) {
        if (body instanceof Unit unit) {
            return !unit.burrowed && weapon.targets() != Targets.BUILDINGS && (!unit.air() || weapon.targets().hitsAir());
        }
        if (body instanceof Structure structure) {
            return structure.hittable();
        }
        return true;
    }

    private static double radius(Body body) {
        return body instanceof Unit unit ? unit.radius : 0;
    }

    private void fire(Body source, Weapon weapon, Body target, double fromX, double fromY, double damageMul) {
        double damage = weapon.damage() * damageMul;
        if (weapon.melee()) {
            this.impact(source.side(), weapon, target, target.x, target.y, damage);
            return;
        }
        Projectile projectile = new Projectile(this.nextId++, source.side(), weapon.projectile(), source, target,
                weapon.lob(), fromX, fromY, weapon.projectileSpeed(), damage, weapon.splash(), weapon.targets());
        this.projectiles.add(projectile);
        this.events.add(new MatchEvent.ProjectileFired(projectile));
    }

    private void updateProjectiles() {
        for (Projectile p : List.copyOf(this.projectiles)) {
            if (!p.alive) {
                continue;
            }
            if (!p.lob && p.target.alive) {
                p.toX = p.target.x;
                p.toY = p.target.y;
            }
            double dx = p.toX - p.x;
            double dy = p.toY - p.y;
            double dist = Math.hypot(dx, dy);
            double step = p.speed * DT;
            if (dist <= step + 0.05) {
                p.x = p.toX;
                p.y = p.toY;
                p.alive = false;
                this.events.add(new MatchEvent.ProjectileLanded(p));
                Weapon weapon = new Weapon(p.damage, 1, 0, 0, p.targets, p.splash, null, 0, p.lob);
                if (p.lob || p.target.alive) {
                    this.impact(p.side, weapon, p.lob ? null : p.target, p.x, p.y, p.damage);
                }
            } else {
                p.x += dx / dist * step;
                p.y += dy / dist * step;
            }
        }
    }

    /** Damage at a point: the target itself (if any) and everything hittable in the splash. */
    private void impact(int side, Weapon weapon, Body target, double x, double y, double damage) {
        if (target != null && target.alive) {
            this.hurt(target, damage);
        }
        if (weapon.splash() <= 0) {
            return;
        }
        for (Unit unit : List.copyOf(this.units)) {
            if (unit != target && unit.alive && unit.side() != side && this.hittable(weapon, unit)
                    && unit.distanceTo(x, y) <= weapon.splash()) {
                this.hurt(unit, damage);
            }
        }
        if (weapon.targets() == Targets.BUILDINGS || target == null || target.building() || target instanceof Unit) {
            for (Tower tower : this.towers) {
                if (tower != target && tower.alive && tower.side() != side && tower.distanceTo(x, y) <= weapon.splash() * 0.5) {
                    this.hurt(tower, damage);
                }
            }
            for (Structure s : List.copyOf(this.structures)) {
                if (s != target && s.alive && s.side() != side && s.hittable() && s.distanceTo(x, y) <= weapon.splash()) {
                    this.hurt(s, damage);
                }
            }
        }
    }

    private void hurt(Body body, double damage) {
        if (!body.alive || damage <= 0) {
            return;
        }
        if (body instanceof Unit unit && unit.burrowed) {
            return;
        }
        body.hp -= damage;
        if (body instanceof Tower tower) {
            this.events.add(new MatchEvent.TowerHit(tower, damage));
        }
        if (body.hp <= 0) {
            body.hp = 0;
            this.kill(body);
        }
    }

    private void kill(Body body) {
        switch (body) {
            case Unit unit -> {
                unit.alive = false;
                this.events.add(new MatchEvent.UnitDied(unit));
            }
            case Tower tower -> {
                tower.alive = false;
                this.grid.remove(tower);
                this.events.add(new MatchEvent.TowerDestroyed(tower));
                Side other = this.sides[1 - tower.side()];
                if (tower.keep) {
                    other.crowns = 3;
                    for (Tower t : this.towers) {
                        if (t.side() == tower.side() && t.alive) {
                            t.alive = false;
                            this.grid.remove(t);
                            this.events.add(new MatchEvent.TowerDestroyed(t));
                        }
                    }
                    this.end(other.index);
                } else {
                    other.crowns++;
                }
            }
            case Structure structure -> this.remove(structure, true);
        }
    }

    // ---- units ----

    private void updateUnit(Unit u) {
        u.moving = false;
        if (u.burrowed) {
            this.tunnel(u);
            return;
        }
        if (u.deployLeft > 0) {
            u.deployLeft -= DT;
            return;
        }
        double slow = this.time < u.slowUntil ? u.slowMul : 1;
        double haste = this.time < u.hasteUntil ? u.hasteMul : 1;
        UnitDef.Heal heal = u.def.heal();
        if (heal != null) {
            u.healIn -= DT;
            if (u.healIn <= 0) {
                u.healIn += heal.every();
                this.heal(u, heal);
            }
        }
        Weapon weapon = u.def.weapon();
        if (u.target != null && (!u.target.alive || !this.hittable(weapon, u.target) && !this.blockingWall(u, u.target))) {
            u.target = null;
            u.locked = false;
        }
        if (!u.locked) {
            u.retargetIn -= DT;
            if (u.target == null || u.retargetIn <= 0) {
                u.target = this.chooseTarget(u);
                u.retargetIn = RETARGET;
            }
        }
        if (u.target == null) {
            return;
        }
        double gap = u.target.gap(u.x, u.y, u.radius);
        if (gap <= weapon.range()) {
            u.locked = true;
            this.face(u, u.target.x, u.target.y);
            if (!u.inRange) {
                u.inRange = true;
                u.attackTimer = weapon.firstHit();
            }
            u.attackTimer -= DT * slow * haste;
            if (u.attackTimer <= 0) {
                u.attackTimer += weapon.hitSpeed();
                boolean charged = u.charging;
                double mul = charged ? u.def.charge().damageMul() : 1;
                u.charging = false;
                u.walked = 0;
                this.events.add(new MatchEvent.UnitAttacked(u, u.target, charged));
                this.fire(u, weapon, u.target, u.x, u.y, mul);
            }
            return;
        }
        if (u.locked && gap > weapon.range() + 0.8) {
            u.locked = false;
            u.retargetIn = 0;
        }
        u.inRange = false;
        u.attackTimer = weapon.firstHit();
        if (this.time < u.rootUntil) {
            return;
        }
        double speed = u.def.speed() * slow * haste;
        UnitDef.Charge charge = u.def.charge();
        if (charge != null && u.walked >= charge.after()) {
            u.charging = true;
            speed *= charge.speedMul();
        }
        double before = u.x;
        double beforeY = u.y;
        if (u.air()) {
            this.step(u, u.target.x, u.target.y, speed * DT);
        } else {
            this.walk(u, speed * DT);
        }
        double moved = Math.hypot(u.x - before, u.y - beforeY);
        u.walked += moved;
        u.moving = moved > 1e-4;
    }

    private void tunnel(Unit u) {
        double step = u.def.burrow().speed() * DT;
        double dx = u.destX - u.x;
        double dy = u.destY - u.y;
        double dist = Math.hypot(dx, dy);
        if (dist <= step) {
            u.x = u.destX;
            u.y = u.destY;
            u.burrowed = false;
            u.deployLeft = this.rules.deployTime() * 0.6;
            this.events.add(new MatchEvent.UnitEmerged(u));
        } else {
            u.x += dx / dist * step;
            u.y += dy / dist * step;
            u.facing = Math.atan2(dx, dy);
        }
    }

    private void heal(Unit healer, UnitDef.Heal heal) {
        boolean any = false;
        for (Unit unit : this.units) {
            if (unit.alive && unit.side() == healer.side() && !unit.burrowed && unit.hp < unit.maxHp
                    && Math.hypot(unit.x - healer.x, unit.y - healer.y) <= heal.radius()) {
                unit.hp = Math.min(unit.maxHp, unit.hp + heal.amount());
                any = true;
            }
        }
        if (any) {
            this.events.add(new MatchEvent.Healed(healer));
        }
    }

    private Body chooseTarget(Unit u) {
        Weapon weapon = u.def.weapon();
        Body best = null;
        double bestGap = Double.MAX_VALUE;
        if (weapon.targets() != Targets.BUILDINGS) {
            for (Unit other : this.units) {
                if (other.alive && other.side() != u.side() && this.hittable(weapon, other)) {
                    double gap = other.gap(u.x, u.y, u.radius);
                    if (gap <= u.def.sight() && gap < bestGap) {
                        bestGap = gap;
                        best = other;
                    }
                }
            }
            for (Structure s : this.structures) {
                if (s.alive && s.side() != u.side() && (s.building() || s.def.kind() == StructureDef.Kind.KEG)) {
                    double gap = s.gap(u.x, u.y, u.radius);
                    if (gap <= u.def.sight() && gap < bestGap) {
                        bestGap = gap;
                        best = s;
                    }
                }
            }
            for (Tower tower : this.towers) {
                if (tower.alive && tower.side() != u.side()) {
                    double gap = tower.gap(u.x, u.y, u.radius);
                    if (gap <= u.def.sight() && gap < bestGap) {
                        bestGap = gap;
                        best = tower;
                    }
                }
            }
            if (best != null) {
                return best;
            }
        }
        // Nothing in sight (or only buildings wanted): the nearest enemy building.
        for (Tower tower : this.towers) {
            if (tower.alive && tower.side() != u.side()) {
                double gap = tower.gap(u.x, u.y, u.radius);
                if (gap < bestGap) {
                    bestGap = gap;
                    best = tower;
                }
            }
        }
        for (Structure s : this.structures) {
            if (s.alive && s.side() != u.side() && s.building()) {
                double gap = s.gap(u.x, u.y, u.radius);
                if (gap < bestGap) {
                    bestGap = gap;
                    best = s;
                }
            }
        }
        return best;
    }

    /** An enemy wall the unit decided to break through counts as a target even if it would not pick it. */
    private boolean blockingWall(Unit u, Body body) {
        return body instanceof Structure s && s.blocks() && s.side() != u.side();
    }

    /** Ground movement along a tile path towards the target; breaks through enemy walls in the way. */
    private void walk(Unit u, double step) {
        Body target = u.target;
        u.repathIn -= DT;
        if (u.path == null || u.pathFor != target || u.repathIn <= 0 || u.pathIndex >= u.path.size()) {
            double reach = u.def.weapon().range() + u.radius + 0.3;
            u.path = this.grid.path(u.side(), u.x, u.y,
                    (tx, ty) -> target.distanceTo(tx + 0.5, ty + 0.5) <= reach);
            u.pathIndex = 1;
            u.pathFor = target;
            u.repathIn = REPATH;
        }
        if (u.path == null || u.path.size() < 2) {
            this.step(u, target.x, target.y, step);
            return;
        }
        // The next tile is an enemy wall or building: break it.
        int[] next = u.path.get(Math.min(u.pathIndex, u.path.size() - 1));
        Structure block = this.grid.block(next[0], next[1]);
        if (block != null && block.side() != u.side() && block != target) {
            u.target = block;
            u.locked = true;
            u.path = null;
            return;
        }
        // Head for the furthest tile of the path that can be reached in a straight line.
        int furthest = u.pathIndex;
        for (int i = u.pathIndex; i < Math.min(u.path.size(), u.pathIndex + 6); i++) {
            int[] tile = u.path.get(i);
            if (this.grid.block(tile[0], tile[1]) != null) {
                break;
            }
            if (this.grid.clearLine(u.side(), u.x, u.y, tile[0] + 0.5, tile[1] + 0.5)) {
                furthest = i;
            }
        }
        int[] aim = u.path.get(furthest);
        double ax = aim[0] + 0.5;
        double ay = aim[1] + 0.5;
        if (furthest == u.path.size() - 1) {
            // Last tile: walk straight at the target instead of the tile's center.
            if (this.grid.clearLine(u.side(), u.x, u.y, target.x, target.y)) {
                ax = target.x;
                ay = target.y;
            }
        }
        this.step(u, ax, ay, step);
        int tx = (int) Math.floor(u.x);
        int ty = (int) Math.floor(u.y);
        for (int i = u.pathIndex; i < u.path.size(); i++) {
            int[] tile = u.path.get(i);
            if (tile[0] == tx && tile[1] == ty) {
                u.pathIndex = i + 1;
            }
        }
    }

    private void step(Unit u, double toX, double toY, double step) {
        double dx = toX - u.x;
        double dy = toY - u.y;
        double dist = Math.hypot(dx, dy);
        if (dist < 1e-6) {
            return;
        }
        double move = Math.min(step, dist);
        u.x += dx / dist * move;
        u.y += dy / dist * move;
        u.facing = Math.atan2(dx, dy);
    }

    private void face(Unit u, double x, double y) {
        if (Math.hypot(x - u.x, y - u.y) > 1e-6) {
            u.facing = Math.atan2(x - u.x, y - u.y);
        }
    }

    /** Pushes overlapping units apart (heavy ones push light ones) and ground units out of solid tiles. */
    private void separate() {
        int n = this.units.size();
        for (int i = 0; i < n; i++) {
            Unit a = this.units.get(i);
            if (!a.alive || a.burrowed) {
                continue;
            }
            for (int j = i + 1; j < n; j++) {
                Unit b = this.units.get(j);
                if (!b.alive || b.burrowed || a.air() != b.air()) {
                    continue;
                }
                double dx = b.x - a.x;
                double dy = b.y - a.y;
                double dist = Math.hypot(dx, dy);
                double overlap = a.radius + b.radius - dist;
                if (overlap <= 0) {
                    continue;
                }
                if (dist < 1e-6) {
                    dx = (a.id() % 2 == 0 ? 1 : -1) * 0.01;
                    dy = 0.01;
                    dist = Math.hypot(dx, dy);
                }
                double total = a.def.mass() + b.def.mass();
                double pushA = overlap * b.def.mass() / total * 0.5;
                double pushB = overlap * a.def.mass() / total * 0.5;
                a.x -= dx / dist * pushA;
                a.y -= dy / dist * pushA;
                b.x += dx / dist * pushB;
                b.y += dy / dist * pushB;
            }
        }
        for (Unit unit : this.units) {
            if (unit.alive && !unit.air() && !unit.burrowed) {
                this.keepOutOfSolid(unit);
            }
            unit.x = Math.max(0.2, Math.min(Arena.WIDTH - 0.2, unit.x));
            unit.y = Math.max(0.2, Math.min(Arena.LENGTH - 0.2, unit.y));
        }
    }

    private void keepOutOfSolid(Unit u) {
        int tx = (int) Math.floor(u.x);
        int ty = (int) Math.floor(u.y);
        if (this.grid.solid(tx, ty)) {
            // Inside something (a structure was built on it, a bridge vanished): to the nearest free tile.
            double best = Double.MAX_VALUE;
            double bx = u.x;
            double by = u.y;
            for (int r = 1; r <= 4 && best == Double.MAX_VALUE; r++) {
                for (int dx = -r; dx <= r; dx++) {
                    for (int dy = -r; dy <= r; dy++) {
                        int nx = tx + dx;
                        int ny = ty + dy;
                        if (this.grid.solid(nx, ny)) {
                            continue;
                        }
                        double cx = Math.max(nx + 0.05, Math.min(nx + 0.95, u.x));
                        double cy = Math.max(ny + 0.05, Math.min(ny + 0.95, u.y));
                        double d = Math.hypot(cx - u.x, cy - u.y);
                        if (d < best) {
                            best = d;
                            bx = cx;
                            by = cy;
                        }
                    }
                }
            }
            u.x = bx;
            u.y = by;
            return;
        }
        double r = Math.min(u.radius, TERRAIN_RADIUS);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                int nx = tx + dx;
                int ny = ty + dy;
                if ((dx == 0 && dy == 0) || !this.grid.solid(nx, ny)) {
                    continue;
                }
                double cx = Math.max(nx, Math.min(nx + 1, u.x));
                double cy = Math.max(ny, Math.min(ny + 1, u.y));
                double ex = u.x - cx;
                double ey = u.y - cy;
                double d = Math.hypot(ex, ey);
                if (d < r && d > 1e-9) {
                    u.x += ex / d * (r - d);
                    u.y += ey / d * (r - d);
                }
            }
        }
    }
}
