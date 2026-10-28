package de.hymods.orbisarena.sim;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Every unit, structure and faction, read from {@code orbisarena/factions.json}. */
public final class Catalog {
    public static final String RESOURCE = "/orbisarena/factions.json";
    public static final int DECK_SIZE = 8;

    private final Map<String, UnitDef> units;
    private final Map<String, StructureDef> structures;
    private final Map<String, Faction> factions;

    private Catalog(Map<String, UnitDef> units, Map<String, StructureDef> structures, Map<String, Faction> factions) {
        this.units = units;
        this.structures = structures;
        this.factions = factions;
    }

    public static Catalog load() {
        try (InputStream in = Catalog.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("missing " + RESOURCE);
            }
            return read(new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException("cannot read " + RESOURCE, e);
        }
    }

    public static Catalog read(Reader reader) {
        File file;
        try {
            file = new Gson().fromJson(reader, File.class);
        } catch (JsonParseException e) {
            throw new IllegalStateException("broken " + RESOURCE + ": " + e.getMessage(), e);
        }
        Map<String, UnitDef> units = new LinkedHashMap<>();
        file.units.forEach((id, unit) -> units.put(id, new UnitDef(id, unit.name(), unit.models(), unit.scale() > 0 ? unit.scale() : 1,
                unit.hp(), unit.speed(), unit.radius(), unit.mass() > 0 ? unit.mass() : 1, unit.air(),
                unit.sight() > 0 ? unit.sight() : 5.5, unit.weapon(), unit.heal(), unit.charge(), unit.burrow(),
                unit.role(), unit.attack())));
        Map<String, StructureDef> structures = new LinkedHashMap<>();
        file.structures.forEach((id, s) -> structures.put(id, new StructureDef(id, s.kind(), s.w(), s.h(), s.hp(), s.lifetime(),
                s.weapon(), s.spawn(), s.hedge(), s.keg(), s.style(), s.puppet())));
        Map<String, Faction> factions = new LinkedHashMap<>();
        for (Faction faction : file.factions) {
            factions.put(faction.id(), faction);
        }
        Catalog catalog = new Catalog(units, structures, factions);
        List<String> problems = catalog.problems();
        if (!problems.isEmpty()) {
            throw new IllegalStateException("broken " + RESOURCE + ": " + String.join("; ", problems));
        }
        return catalog;
    }

    public UnitDef unit(String id) {
        UnitDef unit = this.units.get(id);
        if (unit == null) {
            throw new IllegalArgumentException("unknown unit " + id);
        }
        return unit;
    }

    public StructureDef structure(String id) {
        StructureDef structure = this.structures.get(id);
        if (structure == null) {
            throw new IllegalArgumentException("unknown structure " + id);
        }
        return structure;
    }

    public Faction faction(String id) {
        return this.factions.get(id);
    }

    public List<Faction> factions() {
        return List.copyOf(this.factions.values());
    }

    public Iterable<UnitDef> units() {
        return this.units.values();
    }

    public Iterable<StructureDef> structures() {
        return this.structures.values();
    }

    /** Everything that would break a match: missing references, wrong deck sizes, weapons without targets. */
    List<String> problems() {
        List<String> problems = new ArrayList<>();
        for (UnitDef unit : this.units.values()) {
            if (unit.models() == null || unit.models().isEmpty()) {
                problems.add(unit.id() + " has no model");
            }
            if (unit.hp() <= 0 || unit.radius() <= 0) {
                problems.add(unit.id() + " needs hp and radius");
            }
            if (unit.weapon() == null || unit.weapon().targets() == null || unit.weapon().hitSpeed() <= 0) {
                problems.add(unit.id() + " needs a weapon with targets and hitSpeed");
            }
        }
        for (StructureDef s : this.structures.values()) {
            if (s.kind() == null || s.w() <= 0 || s.h() <= 0 || s.lifetime() <= 0) {
                problems.add(s.id() + " needs kind, size and lifetime");
            }
            if (s.spawn() != null && !this.units.containsKey(s.spawn().unit())) {
                problems.add(s.id() + " spawns unknown " + s.spawn().unit());
            }
            if (s.kind() == StructureDef.Kind.HEDGE && s.hedge() == null) {
                problems.add(s.id() + " needs hedge");
            }
            if (s.kind() == StructureDef.Kind.KEG && s.keg() == null) {
                problems.add(s.id() + " needs keg");
            }
            if (s.weapon() != null && (s.weapon().targets() == null || s.weapon().hitSpeed() <= 0)) {
                problems.add(s.id() + " weapon needs targets and hitSpeed");
            }
        }
        for (Faction faction : this.factions.values()) {
            if (faction.cards() == null || faction.cards().size() != DECK_SIZE) {
                problems.add(faction.id() + " needs " + DECK_SIZE + " cards");
                continue;
            }
            if (faction.avatar() == null || faction.avatar().targets() == null) {
                problems.add(faction.id() + " needs an avatar weapon");
            }
            if (faction.ability() == null || faction.ability().spell() == null) {
                problems.add(faction.id() + " needs an ability");
            } else {
                spellProblems(faction.id() + " ability", faction.ability().spell(), problems);
            }
            for (CardDef card : faction.cards()) {
                String where = faction.id() + "/" + card.id();
                if (card.type() == null || card.cost() < 1 || card.cost() > 10) {
                    problems.add(where + " needs a type and a cost of 1-10");
                    continue;
                }
                switch (card.type()) {
                    case TROOP -> {
                        if (!this.units.containsKey(card.unit()) || card.count() < 1) {
                            problems.add(where + " needs a known unit and a count");
                        }
                    }
                    case SPELL -> {
                        if (card.spell() == null) {
                            problems.add(where + " needs a spell");
                        } else {
                            spellProblems(where, card.spell(), problems);
                        }
                    }
                    case BUILDING, BUILD -> {
                        if (!this.structures.containsKey(card.structure())) {
                            problems.add(where + " needs a known structure");
                        }
                    }
                }
            }
        }
        return problems;
    }

    private void spellProblems(String where, SpellDef spell, List<String> problems) {
        if (spell.radius() <= 0) {
            problems.add(where + " needs a radius");
        }
        if (spell.summon() != null && !this.units.containsKey(spell.summon())) {
            problems.add(where + " summons unknown " + spell.summon());
        }
    }

    /** The file as Gson reads it; defaults are filled in when the definitions are made. */
    private static final class File {
        Map<String, RawUnit> units = Map.of();
        Map<String, RawStructure> structures = Map.of();
        List<Faction> factions = List.of();
    }

    private record RawUnit(String name, List<String> models, double scale, double hp, double speed, double radius,
                           double mass, boolean air, double sight, Weapon weapon, UnitDef.Heal heal,
                           UnitDef.Charge charge, UnitDef.Burrow burrow, String role, String attack) {
    }

    private record RawStructure(StructureDef.Kind kind, int w, int h, double hp, double lifetime, Weapon weapon,
                                StructureDef.Spawn spawn, StructureDef.Hedge hedge, StructureDef.Keg keg, String style,
                                String puppet) {
    }
}
