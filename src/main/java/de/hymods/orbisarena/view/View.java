package de.hymods.orbisarena.view;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.AnimationSlot;
import com.hypixel.hytale.server.core.universe.world.ParticleUtil;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import de.hymods.orbisarena.sim.Arena;
import de.hymods.orbisarena.sim.Body;
import de.hymods.orbisarena.sim.Match;
import de.hymods.orbisarena.sim.MatchEvent;
import de.hymods.orbisarena.sim.Projectile;
import de.hymods.orbisarena.sim.SpellDef;
import de.hymods.orbisarena.sim.Structure;
import de.hymods.orbisarena.sim.Tower;
import de.hymods.orbisarena.sim.Unit;
import de.hymods.orbisarena.sim.UnitDef;
import de.hymods.orbisarena.world.ArenaBuilder;
import de.hymods.orbisarena.world.Place;
import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Shows a match in its world: a puppet per unit, guards with the towers' health on the watchtowers, flying
 * projectiles, blocks for what cards build, particles for hits and spells. Reads the match after every tick.
 * On the arena world's thread.
 */
public final class View {
    /** Who stands guard on a faction's watchtowers, and on its keep when the bot plays it: role and model. */
    private static final Map<String, String[]> GUARDS = Map.of(
            "kweebec", new String[]{"Kweebec_Razorleaf", "Kweebec_Sapling_Razorleaf"},
            "trork", new String[]{"Trork_Hunter", "Trork_Hunter"},
            "undead", new String[]{"Skeleton_Archer", "Skeleton_Archer"},
            "goblin", new String[]{"Goblin_Lobber", "Goblin_Lobber"});
    private static final Map<String, String[]> CHIEFS = Map.of(
            "kweebec", new String[]{"Kweebec_Elder", "Kweebec_Sapling_Treesinger"},
            "trork", new String[]{"Trork_Chieftain", "Trork_Chieftain"},
            "undead", new String[]{"Skeleton_Archmage", "Skeleton_Archmage"},
            "goblin", new String[]{"Goblin_Scrapper", "Goblin_Scrapper"});
    private static final double SHOT_HEIGHT = 1.2;

    private final Match match;
    private final Store<EntityStore> store;
    private final Puppets puppets;
    private final ArenaBuilder builder;
    private final Map<Integer, Ref<EntityStore>> units = new HashMap<>();
    private final Map<Integer, Double> shownHp = new HashMap<>();
    private final Map<Integer, Shot> shots = new HashMap<>();
    private final Map<Integer, Ref<EntityStore>> structurePuppets = new HashMap<>();
    private final Map<Integer, Ref<EntityStore>> guards = new HashMap<>();
    private final Map<Integer, Integer> shownTowerHp = new HashMap<>();
    private final List<Fall> dying = new ArrayList<>();
    private final List<Shot> flights = new ArrayList<>();
    private double now;
    private int ticks;

    private record Fall(Ref<EntityStore> ref, double at) {
    }

    /** Something flying from one point to another along an arc. */
    private static final class Shot {
        final Ref<EntityStore> ref;
        final double fromX;
        final double fromY;
        final double fromZ;
        final double toY;
        final double arc;
        final double start;
        final double duration;
        final double toX;
        final double toZ;

        Shot(Ref<EntityStore> ref, double fromX, double fromY, double fromZ, double toX, double toY, double toZ,
             double arc, double start, double duration) {
            this.ref = ref;
            this.fromX = fromX;
            this.fromY = fromY;
            this.fromZ = fromZ;
            this.toX = toX;
            this.toY = toY;
            this.toZ = toZ;
            this.arc = arc;
            this.start = start;
            this.duration = duration;
        }
    }

    public View(World world, Match match, ArenaBuilder builder) {
        this.match = match;
        this.store = world.getEntityStore().getStore();
        this.puppets = new Puppets(this.store);
        this.builder = builder;
    }

    /** Guards on the watchtowers; a chief on a keep nobody stands on (the bot's). */
    public void start(boolean[] avatars) {
        for (Tower tower : this.match.towers()) {
            String faction = this.match.side(tower.side()).faction().id();
            String[] who;
            if (!tower.keep()) {
                who = GUARDS.getOrDefault(faction, new String[]{null, "Skeleton_Archer"});
            } else if (!avatars[tower.side()]) {
                who = CHIEFS.getOrDefault(faction, new String[]{null, "Trork_Chieftain"});
            } else {
                continue;
            }
            Ref<EntityStore> guard = this.puppets.spawn(who[0], who[1], 1.0, Place.x(tower.x()),
                    Place.FLOOR + Place.TOWER_HEIGHT + 1, Place.z(tower.y()), Place.yaw(tower.side() == 0 ? 0 : Math.PI),
                    tower.side());
            if (guard != null) {
                this.guards.put(tower.id(), guard);
                this.puppets.nameplate(guard, Integer.toString((int) Math.ceil(tower.hp())));
            }
        }
    }

    public void apply(List<MatchEvent> events) {
        for (MatchEvent event : events) {
            switch (event) {
                case MatchEvent.UnitSpawned e -> {
                    if (!e.unit().burrowed()) {
                        this.show(e.unit());
                        this.particle("Block_Land_Hard_Dust", e.unit().x(), 0.1, e.unit().y());
                    }
                }
                case MatchEvent.UnitEmerged e -> {
                    this.show(e.unit());
                    this.particle("Block_Break_Dust", e.unit().x(), 0.2, e.unit().y());
                }
                case MatchEvent.UnitDied e -> {
                    Ref<EntityStore> ref = this.units.remove(e.unit().id());
                    this.shownHp.remove(e.unit().id());
                    if (ref != null) {
                        this.puppets.health(ref, 0);
                        this.puppets.animate(ref, AnimationSlot.Status, null, "Death");
                        this.dying.add(new Fall(ref, this.now + 1.1));
                    }
                }
                case MatchEvent.UnitAttacked e -> {
                    Ref<EntityStore> ref = this.units.get(e.unit().id());
                    if (ref != null) {
                        this.puppets.attack(ref, e.unit().def().attack());
                    }
                    if (e.unit().def().weapon().melee()) {
                        this.particle(e.charged() ? "Impact_Critical" : "Impact_Sword_Basic", e.target().x(), 1.0, e.target().y());
                    }
                }
                case MatchEvent.Healed e -> this.particle("Totem_Heal_AoE", e.healer().x(), 0.1, e.healer().y());
                case MatchEvent.ProjectileFired e -> this.fired(e.projectile());
                case MatchEvent.ProjectileLanded e -> this.landed(e.projectile());
                case MatchEvent.SpellCast e -> this.cast(e.side(), e.spell(), e.x(), e.y(), e.delay());
                case MatchEvent.SpellLanded e -> this.spell(e.spell(), e.x(), e.y());
                case MatchEvent.StructureBuilt e -> this.built(e.structure());
                case MatchEvent.StructureGone e -> this.gone(e.structure(), e.destroyed());
                case MatchEvent.KegExploded e -> this.particle("Explosion_Barrel", e.keg().x(), 0.5, e.keg().y());
                case MatchEvent.TowerHit e -> this.towerHp(e.tower());
                case MatchEvent.TowerDestroyed e -> {
                    this.builder.rubble(e.tower());
                    this.puppets.remove(this.guards.remove(e.tower().id()));
                    this.particle("Explosion_Big", e.tower().x(), 1.5, e.tower().y());
                }
                default -> {
                }
            }
        }
    }

    private void show(Unit unit) {
        UnitDef def = unit.def();
        Ref<EntityStore> ref = this.puppets.spawn(def.role(), unit.model(), def.scale(), Place.x(unit.x()),
                height(unit), Place.z(unit.y()), Place.yaw(unit.facing()), unit.side());
        if (ref != null) {
            this.units.put(unit.id(), ref);
            this.shownHp.put(unit.id(), unit.hp());
        }
    }

    private static double height(Unit unit) {
        return Place.FLOOR + (unit.air() ? Place.AIR : 0);
    }

    private static String shotModel(String kind) {
        return switch (kind == null ? "" : kind) {
            case "arrow", "bolt", "feather" -> "Arrow_Crude";
            case "spear" -> "Spear_Crude";
            case "leaf" -> "Spear_Leaf";
            case "seed" -> "Bomb_Popberry";
            case "bomb" -> "Bomb_Fire_Goblin";
            case "orb", "hex", "wisp" -> "Skeleton_Mage_Corruption_Orb";
            default -> null;
        };
    }

    private double shotHeight(Body body) {
        if (body instanceof Tower) {
            return Place.FLOOR + Place.TOWER_HEIGHT + 1.6;
        }
        if (body instanceof Structure) {
            return Place.FLOOR + 1.6;
        }
        return Place.FLOOR + SHOT_HEIGHT + (body.air() ? Place.AIR : 0);
    }

    private void fired(Projectile p) {
        if (p.source() instanceof Tower tower) {
            Ref<EntityStore> guard = this.guards.get(tower.id());
            if (guard != null) {
                String faction = this.match.side(tower.side()).faction().id();
                this.puppets.attack(guard, switch (faction) {
                    case "kweebec" -> "Kweebec_Spear/Stab";
                    case "trork" -> "Trork_Axe/ThrowDouble";
                    case "undead" -> "Skeleton_Bow/Shoot";
                    default -> "Goblin_Bomb/Throw";
                });
            }
        }
        String model = shotModel(p.kind());
        double fromY = this.shotHeight(p.source());
        double toY = this.shotHeight(p.target());
        if (model == null) {
            this.particle("Fire_ChargeOrange", p.x(), fromY - Place.FLOOR, p.y());
            return;
        }
        Ref<EntityStore> ref = this.puppets.spawn(null, model, 1.0, Place.x(p.x()), fromY, Place.z(p.y()),
                heading(p.toX() - p.x(), p.toY() - p.y()), -1);
        if (ref != null) {
            double distance = Math.hypot(p.toX() - p.x(), p.toY() - p.y());
            double arc = p.lob() ? 1.0 + distance * 0.35 : 0.15 * distance;
            this.shots.put(p.id(), new Shot(ref, Place.x(p.x()), fromY, Place.z(p.y()), 0, toY, 0, arc, 0, 0));
        }
    }

    private void landed(Projectile p) {
        Shot shot = this.shots.remove(p.id());
        if (shot != null) {
            this.puppets.remove(shot.ref);
        }
        String particle = switch (p.kind() == null ? "" : p.kind()) {
            case "bomb" -> "Explosion_Small";
            case "orb", "hex", "wisp" -> "VoidImpact";
            case "flame" -> "Impact_Fire";
            case "seed" -> "Impact_Poison";
            default -> null;
        };
        if (particle != null) {
            this.particle(particle, p.x(), 0.6, p.y());
        }
    }

    /** A spell on its way: something flies from the caster's keep over to the spot if it takes a while. */
    private void cast(int side, SpellDef spell, double x, double y, double delay) {
        if (delay <= 0) {
            return;
        }
        Tower keep = this.match.keep(side);
        double fromY = Place.FLOOR + Place.TOWER_HEIGHT + 2;
        Ref<EntityStore> ref = this.puppets.spawn(null, "Bomb_Large_Fire_Goblin", 1.0, Place.x(keep.x()), fromY,
                Place.z(keep.y()), heading(x - keep.x(), y - keep.y()), -1);
        if (ref != null) {
            double distance = Math.hypot(x - keep.x(), y - keep.y());
            this.flights.add(new Shot(ref, Place.x(keep.x()), fromY, Place.z(keep.y()), Place.x(x), Place.FLOOR + 0.3,
                    Place.z(y), 2 + distance * 0.3, this.now, delay));
        }
    }

    private void spell(SpellDef spell, double x, double y) {
        String particle = switch (spell.effect() == null ? "" : spell.effect()) {
            case "roots" -> "Totem_Slow_AoE";
            case "war_cry" -> "Buff_Enrage";
            case "raise_dead" -> "Dungeon_Goblin_Void_Dissolve";
            case "bomb" -> "Explosion_Medium";
            case "chill" -> "Ability_Ice_Explosion";
            default -> "Impact_Explosion";
        };
        this.particle(particle, x, 0.2, y);
    }

    private void built(Structure s) {
        this.builder.build(s);
        this.particle("Block_Break_Dust", s.x(), 0.3, s.y());
        if (s.def().puppet() != null) {
            Ref<EntityStore> ref = this.puppets.spawn(null, s.def().puppet(), 1.0, Place.x(s.x()), Place.FLOOR + 1,
                    Place.z(s.y()), Place.yaw(s.side() == 0 ? 0 : Math.PI), s.side());
            if (ref != null) {
                this.structurePuppets.put(s.id(), ref);
            }
        }
    }

    private void gone(Structure s, boolean destroyed) {
        this.builder.unbuild(s);
        this.puppets.remove(this.structurePuppets.remove(s.id()));
        this.particle(destroyed ? "Explosion_Small" : "Block_Break_Dust", s.x(), 0.4, s.y());
    }

    private void towerHp(Tower tower) {
        int hp = (int) Math.ceil(tower.hp());
        Integer was = this.shownTowerHp.put(tower.id(), hp);
        Ref<EntityStore> guard = this.guards.get(tower.id());
        if (guard != null && (was == null || was != hp)) {
            this.puppets.nameplate(guard, Integer.toString(hp));
            this.puppets.health(guard, tower.hp() / tower.maxHp());
        }
    }

    /** Every tick: puppets to their units, shots along their arcs, the dead away. */
    public void sync() {
        this.now = this.match.time();
        this.ticks++;
        for (Unit unit : this.match.units()) {
            if (unit.burrowed()) {
                if (this.ticks % 5 == unit.id() % 5) {
                    this.particle("Block_Hit_Dirt", unit.x(), 0.05, unit.y());
                }
                continue;
            }
            Ref<EntityStore> ref = this.units.get(unit.id());
            if (ref == null) {
                continue;
            }
            boolean fast = unit.def().speed() >= 1.4 || unit.charging() || unit.hasted(this.now);
            this.puppets.move(ref, Place.x(unit.x()), height(unit), Place.z(unit.y()), Place.yaw(unit.facing()),
                    unit.moving(), fast, unit.air());
            Double shown = this.shownHp.get(unit.id());
            if (shown == null || Math.abs(shown - unit.hp()) >= 1) {
                this.shownHp.put(unit.id(), unit.hp());
                this.puppets.health(ref, unit.hp() / unit.maxHp());
            }
        }
        for (Projectile p : this.match.projectiles()) {
            Shot shot = this.shots.get(p.id());
            if (shot == null) {
                continue;
            }
            double t = p.progress();
            double toY = this.shotHeight(p.target());
            double y = shot.fromY + (toY - shot.fromY) * t + shot.arc * 4 * t * (1 - t);
            this.puppets.move(shot.ref, Place.x(p.x()), y, Place.z(p.y()),
                    heading(p.toX() - p.x(), p.toY() - p.y()), false, false, true);
        }
        for (Iterator<Shot> it = this.flights.iterator(); it.hasNext(); ) {
            Shot flight = it.next();
            double t = Math.min(1, (this.now - flight.start) / flight.duration);
            double x = flight.fromX + (flight.toX - flight.fromX) * t;
            double z = flight.fromZ + (flight.toZ - flight.fromZ) * t;
            double y = flight.fromY + (flight.toY - flight.fromY) * t + flight.arc * 4 * t * (1 - t);
            this.puppets.move(flight.ref, x, y, z, 0f, false, false, true);
            if (t >= 1) {
                this.puppets.remove(flight.ref);
                it.remove();
            }
        }
        for (Iterator<Fall> it = this.dying.iterator(); it.hasNext(); ) {
            Fall fall = it.next();
            if (this.now >= fall.at) {
                this.puppets.remove(fall.ref);
                it.remove();
            }
        }
    }

    /** How many puppets stand for units, shots, buildings and guards. */
    public String census() {
        return "%d units, %d shots, %d flights, %d buildings, %d guards".formatted(this.units.size(), this.shots.size(),
                this.flights.size(), this.structurePuppets.size(), this.guards.size());
    }

    /** Takes every puppet away. */
    public void clear() {
        this.units.values().forEach(this.puppets::remove);
        this.shots.values().forEach(shot -> this.puppets.remove(shot.ref));
        this.flights.forEach(shot -> this.puppets.remove(shot.ref));
        this.structurePuppets.values().forEach(this.puppets::remove);
        this.guards.values().forEach(this.puppets::remove);
        this.dying.forEach(fall -> this.puppets.remove(fall.ref));
        this.units.clear();
        this.shots.clear();
        this.flights.clear();
        this.structurePuppets.clear();
        this.guards.clear();
        this.dying.clear();
    }

    /** The game's yaw for a direction in tiles. */
    private static float heading(double dx, double dy) {
        return Place.yaw(Math.atan2(dx, dy));
    }

    private void particle(String id, double tileX, double up, double tileY) {
        if (!Arena.inside(tileX, tileY) && !(tileX > -3 && tileX < Arena.WIDTH + 3)) {
            return;
        }
        ParticleUtil.spawnParticleEffect(id, new Vector3d(Place.x(tileX), Place.FLOOR + up, Place.z(tileY)), this.store);
    }
}
