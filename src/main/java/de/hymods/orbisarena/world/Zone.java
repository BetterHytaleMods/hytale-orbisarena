package de.hymods.orbisarena.world;

import java.util.List;

/**
 * The look of an arena, after the zones of Orbis: what the ground, the paths, the towers and the scenery around
 * are made of. Every match picks one.
 */
public enum Zone {
    EMERALD_GROVE("emerald_grove", "Emerald Grove", "Soil_Grass", "Soil_Grass_Full", "Soil_Pathway", "Soil_Dirt",
            "Rock_Stone_Brick", "Rock_Stone_Brick_Mossy", "Wood_Oak_Trunk", "Plant_Leaves_Oak",
            List.of("Plant_Grass_Lush_Tall", "Plant_Grass_Lush", "Plant_Bush")),
    HOWLING_SANDS("howling_sands", "Howling Sands", "Soil_Sand", "Soil_Gravel_Sand", "Rock_Sandstone", "Rock_Sandstone",
            "Rock_Sandstone_Brick", "Rock_Sandstone_Red_Brick", "Wood_Palo_Trunk", "Plant_Leaves_Palm_Arid",
            List.of("Plant_Cactus_1", "Plant_Bush_Arid", "Plant_Grass_Dry_Tall")),
    BOREA("borea", "Borea", "Soil_Snow", "Rock_Ice_Permafrost", "Soil_Gravel", "Rock_Stone",
            "Rock_Stone_Brick", "Rock_Calcite_Brick", "Wood_Fir_Trunk", "Plant_Leaves_Fir_Snow",
            List.of("Plant_Grass_Snowy_Tall", "Plant_Grass_Snowy", "Plant_Bush_Dead")),
    DEVASTATED_LANDS("devastated_lands", "Devastated Lands", "Soil_Ash", "Rock_Volcanic", "Rock_Basalt", "Rock_Basalt",
            "Rock_Basalt_Brick", "Rock_Volcanic_Brick", "Wood_Dry_Trunk", "Plant_Leaves_Dry",
            List.of("Plant_Bush_Dead_Twisted", "Plant_Bush_Dead", "Rock_Volcanic_Cracked_Lava"));

    public final String id;
    public final String title;
    public final String ground;
    public final String ground2;
    public final String path;
    public final String bank;
    public final String wall;
    public final String trim;
    public final String trunk;
    public final String leaves;
    public final List<String> scenery;

    Zone(String id, String title, String ground, String ground2, String path, String bank, String wall, String trim,
         String trunk, String leaves, List<String> scenery) {
        this.id = id;
        this.title = title;
        this.ground = ground;
        this.ground2 = ground2;
        this.path = path;
        this.bank = bank;
        this.wall = wall;
        this.trim = trim;
        this.trunk = trunk;
        this.leaves = leaves;
        this.scenery = scenery;
    }

    public static Zone byId(String id) {
        for (Zone zone : values()) {
            if (zone.id.equals(id)) {
                return zone;
            }
        }
        return null;
    }
}
