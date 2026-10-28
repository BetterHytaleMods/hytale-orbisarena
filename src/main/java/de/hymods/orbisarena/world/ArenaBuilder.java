package de.hymods.orbisarena.world;

import de.hymods.orbisarena.sim.Arena;
import de.hymods.orbisarena.sim.Structure;
import de.hymods.orbisarena.sim.Tower;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Builds an arena out of blocks: the field in the zone's ground with paths along the lanes, the river with its
 * two bridges, towers in the zone's stone with the team colour on their battlements, a low wall and scenery
 * around; and later what cards build, rubble where a tower fell. On the arena world's thread.
 */
public final class ArenaBuilder {
    /** How far the ground reaches past the field on each side. */
    public static final int APRON = 7;
    private static final String[] TEAM = {"Cloth_Block_Wool_Blue", "Cloth_Block_Wool_Red"};

    private final Blocks blocks;
    private final Zone zone;

    public ArenaBuilder(Blocks blocks, Zone zone) {
        this.blocks = blocks;
        this.zone = zone;
    }

    public static int minX() {
        return Place.OX - APRON;
    }

    public static int maxX() {
        return Place.OX + Arena.WIDTH + APRON;
    }

    public static int minZ() {
        return Place.OZ - APRON;
    }

    public static int maxZ() {
        return Place.OZ + Arena.LENGTH + APRON;
    }

    public static String team(int side) {
        return TEAM[side];
    }

    public void field(List<Tower> towers) {
        int floor = Place.FLOOR - 1;
        Random random = new Random(this.zone.ordinal() * 31L + 7);
        for (int x = minX(); x <= maxX(); x++) {
            for (int z = minZ(); z <= maxZ(); z++) {
                int tx = x - Place.OX;
                int ty = z - Place.OZ;
                boolean field = Arena.inside(tx, ty);
                this.blocks.set(x, floor - 2, z, this.zone.bank);
                this.blocks.set(x, floor - 1, z, this.zone.bank);
                String top;
                if (field && Arena.isRiver(ty)) {
                    top = null;
                } else if (field && lane(tx)) {
                    top = this.zone.path;
                } else {
                    top = (tx + ty) % 2 == 0 || random.nextInt(5) == 0 ? this.zone.ground2 : this.zone.ground;
                    if (!field && random.nextInt(3) == 0) {
                        top = this.zone.ground;
                    }
                }
                if (top != null) {
                    this.blocks.set(x, floor, z, top);
                }
                // Air above, in case the world held something.
                for (int y = Place.FLOOR; y < Place.FLOOR + 8; y++) {
                    this.blocks.clear(x, y, z);
                }
            }
        }
        this.river();
        this.border(random);
        for (Tower tower : towers) {
            this.tower(tower);
        }
    }

    /** The two paths from the towers over the bridges. */
    private static boolean lane(int tx) {
        return (tx >= Arena.BRIDGE_X0 && tx < Arena.BRIDGE_X0 + Arena.BRIDGE_WIDTH)
                || (tx >= Arena.WIDTH - Arena.BRIDGE_X0 - Arena.BRIDGE_WIDTH && tx < Arena.WIDTH - Arena.BRIDGE_X0);
    }

    private void river() {
        int floor = Place.FLOOR - 1;
        for (int x = minX(); x <= maxX(); x++) {
            for (int ty = Arena.RIVER_Y0; ty < Arena.RIVER_Y1; ty++) {
                int z = Place.OZ + ty;
                int tx = x - Place.OX;
                this.blocks.set(x, floor - 2, z, this.zone.bank);
                if (Arena.inside(tx, ty) && Arena.isBridge(tx, ty)) {
                    this.blocks.set(x, floor - 1, z, this.zone.bank);
                    this.blocks.set(x, floor, z, this.zone.trunk);
                } else {
                    this.blocks.clear(x, floor - 1, z);
                    this.blocks.clear(x, floor, z);
                    this.blocks.water(x, floor - 1, z, true);
                    this.blocks.water(x, floor, z, true);
                }
            }
        }
    }

    /** A low wall round the field, gaps where the river runs out, scenery on the apron. */
    private void border(Random random) {
        for (int x = Place.OX - 1; x <= Place.OX + Arena.WIDTH; x++) {
            for (int z = Place.OZ - 1; z <= Place.OZ + Arena.LENGTH; z++) {
                int tx = x - Place.OX;
                int ty = z - Place.OZ;
                boolean edge = tx == -1 || tx == Arena.WIDTH || ty == -1 || ty == Arena.LENGTH;
                if (!edge || Arena.isRiver(ty)) {
                    continue;
                }
                this.blocks.set(x, Place.FLOOR, z, this.zone.wall);
                if ((tx + ty) % 4 == 0) {
                    this.blocks.set(x, Place.FLOOR + 1, z, this.zone.trim);
                }
            }
        }
        for (int i = 0; i < 70; i++) {
            int x = minX() + random.nextInt(maxX() - minX() + 1);
            int z = minZ() + random.nextInt(maxZ() - minZ() + 1);
            int tx = x - Place.OX;
            int ty = z - Place.OZ;
            if (tx >= -2 && tx <= Arena.WIDTH + 1 && ty >= -2 && ty <= Arena.LENGTH + 1 || Arena.isRiver(ty)) {
                continue;
            }
            if (random.nextInt(6) == 0) {
                this.tree(x, z, random);
            } else {
                this.blocks.set(x, Place.FLOOR, z, this.zone.scenery.get(random.nextInt(this.zone.scenery.size())));
            }
        }
    }

    private void tree(int x, int z, Random random) {
        int height = 3 + random.nextInt(2);
        for (int y = 0; y < height; y++) {
            this.blocks.set(x, Place.FLOOR + y, z, this.zone.trunk);
        }
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                for (int dy = 0; dy <= 1; dy++) {
                    if (dx == 0 && dz == 0 && dy == 0) {
                        continue;
                    }
                    this.blocks.set(x + dx, Place.FLOOR + height - 1 + dy, z + dz, this.zone.leaves);
                }
            }
        }
        this.blocks.set(x, Place.FLOOR + height + 1, z, this.zone.leaves);
    }

    /** A tower: stone walls, a trimmed top, the team colour on its corners. */
    public void tower(Tower tower) {
        int x0 = Place.OX + tower.x0();
        int z0 = Place.OZ + tower.y0();
        int size = tower.size();
        for (int dx = 0; dx < size; dx++) {
            for (int dz = 0; dz < size; dz++) {
                for (int dy = 0; dy < Place.TOWER_HEIGHT; dy++) {
                    boolean top = dy == Place.TOWER_HEIGHT - 1;
                    this.blocks.set(x0 + dx, Place.FLOOR + dy, z0 + dz, top ? this.zone.trim : this.zone.wall);
                }
                boolean corner = (dx == 0 || dx == size - 1) && (dz == 0 || dz == size - 1);
                if (corner) {
                    this.blocks.set(x0 + dx, Place.FLOOR + Place.TOWER_HEIGHT, z0 + dz, team(tower.side()));
                }
            }
        }
    }

    /** What is left of a tower: a ring of rubble at ground level, walkable. */
    public void rubble(Tower tower) {
        int x0 = Place.OX + tower.x0();
        int z0 = Place.OZ + tower.y0();
        for (int dx = 0; dx < tower.size(); dx++) {
            for (int dz = 0; dz < tower.size(); dz++) {
                for (int dy = 0; dy <= Place.TOWER_HEIGHT; dy++) {
                    this.blocks.clear(x0 + dx, Place.FLOOR + dy, z0 + dz);
                }
                this.blocks.set(x0 + dx, Place.FLOOR - 1, z0 + dz, (dx + dz) % 2 == 0 ? this.zone.trim : this.zone.path);
            }
        }
    }

    /** One block of what a card built, relative to the footprint's lower corner. */
    public record Piece(int dx, int dy, int dz, String block) {
    }

    /** The blocks of a structure, by its style. */
    public static List<Piece> pieces(Structure s) {
        List<Piece> pieces = new ArrayList<>();
        String team = team(s.side());
        int w = s.w();
        int h = s.h();
        switch (s.def().style()) {
            case "bramble" -> each(w, h, (dx, dz) -> pieces.add(new Piece(dx, 0, dz, "Plant_Bush_Bramble")));
            case "roots" -> each(w, h, (dx, dz) -> {
                pieces.add(new Piece(dx, -1, dz, dx == 0 || dx == w - 1 ? "Wood_Oak_Trunk" : "Wood_Hardwood_Planks"));
            });
            case "palisade" -> each(w, h, (dx, dz) -> {
                pieces.add(new Piece(dx, 0, dz, "Wood_Oak_Trunk"));
                pieces.add(new Piece(dx, 1, dz, dx % 2 == 0 ? "Wood_Oak_Trunk" : "Wood_Softwood_Fence"));
            });
            case "bone" -> each(w, h, (dx, dz) -> {
                pieces.add(new Piece(dx, 0, dz, "Deco_Bone_Full"));
                pieces.add(new Piece(dx, 1, dz, dx % 2 == 0 ? "Deco_Bone_Full" : "Deco_Bone_Skulls"));
            });
            case "scrap_wall" -> each(w, h, (dx, dz) -> {
                pieces.add(new Piece(dx, 0, dz, "Metal_Goblin_Iron_Fence"));
                pieces.add(new Piece(dx, 1, dz, "Metal_Goblin_Iron_Fence"));
            });
            case "keg" -> pieces.add(new Piece(0, 0, 0, "Furniture_Tavern_Barrel"));
            case "war_camp" -> each(w, h, (dx, dz) -> {
                boolean corner = (dx == 0 || dx == w - 1) && (dz == 0 || dz == h - 1);
                if (corner) {
                    pieces.add(new Piece(dx, 0, dz, "Wood_Oak_Trunk"));
                    pieces.add(new Piece(dx, 1, dz, "Wood_Oak_Trunk"));
                }
                pieces.add(new Piece(dx, 2, dz, team));
                if (dx == w / 2 && dz == h / 2) {
                    pieces.add(new Piece(dx, 3, dz, team));
                }
            });
            case "grave" -> each(w, h, (dx, dz) -> {
                pieces.add(new Piece(dx, 0, dz, "Rock_Stone_Cobble"));
                if (dx == 0) {
                    pieces.add(new Piece(dx, 1, dz, dz == 0 ? "Furniture_Village_Tombstone" : "Deco_Bone_Skulls"));
                } else if (dz == 0) {
                    pieces.add(new Piece(dx, 1, dz, team));
                }
            });
            case "scrap" -> each(w, h, (dx, dz) -> {
                pieces.add(new Piece(dx, 0, dz, (dx + dz) % 2 == 0 ? "Rock_Stone_Cobble" : team));
            });
            default -> each(w, h, (dx, dz) -> pieces.add(new Piece(dx, 0, dz, "Rock_Stone_Cobble")));
        }
        return pieces;
    }

    private interface Cell {
        void at(int dx, int dz);
    }

    private static void each(int w, int h, Cell cell) {
        for (int dx = 0; dx < w; dx++) {
            for (int dz = 0; dz < h; dz++) {
                cell.at(dx, dz);
            }
        }
    }

    /** Puts a structure's blocks; a bridge replaces water. */
    public void build(Structure s) {
        int x0 = Place.OX + s.x0();
        int z0 = Place.OZ + s.y0();
        for (Piece piece : pieces(s)) {
            int x = x0 + piece.dx();
            int y = Place.FLOOR + piece.dy();
            int z = z0 + piece.dz();
            if (piece.dy() < 0) {
                this.blocks.water(x, y, z, false);
            }
            this.blocks.set(x, y, z, piece.block());
        }
    }

    /** Takes a structure's blocks away again; a bridge gives the water back. */
    public void unbuild(Structure s) {
        int x0 = Place.OX + s.x0();
        int z0 = Place.OZ + s.y0();
        for (Piece piece : pieces(s)) {
            int x = x0 + piece.dx();
            int y = Place.FLOOR + piece.dy();
            int z = z0 + piece.dz();
            this.blocks.clear(x, y, z);
            if (piece.dy() < 0) {
                this.blocks.water(x, y, z, true);
            }
        }
    }

    /** The lobby: a round stone plaza with a banner in each corner. */
    public void lobby() {
        int floor = Place.FLOOR - 1;
        int r = 9;
        for (int x = -r - 1; x <= r + 1; x++) {
            for (int z = -r - 1; z <= r + 1; z++) {
                double d = Math.hypot(x, z);
                if (d > r + 0.5) {
                    continue;
                }
                this.blocks.set(x, floor - 1, z, this.zone.bank);
                this.blocks.set(x, floor, z, d > r - 1.5 ? this.zone.trim : ((x + z) % 2 == 0 ? this.zone.wall : this.zone.path));
                if (d > r - 0.5) {
                    this.blocks.set(x, Place.FLOOR, z, this.zone.wall);
                }
            }
        }
        int[][] posts = {{-6, -6}, {6, -6}, {-6, 6}, {6, 6}};
        for (int[] post : posts) {
            for (int y = 0; y < 3; y++) {
                this.blocks.set(post[0], Place.FLOOR + y, post[1], this.zone.trunk);
            }
            this.blocks.set(post[0], Place.FLOOR + 3, post[1], post[0] < 0 ? TEAM[0] : TEAM[1]);
        }
    }
}
