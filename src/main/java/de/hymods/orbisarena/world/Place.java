package de.hymods.orbisarena.world;

import de.hymods.orbisarena.sim.Arena;

/**
 * Where the arena lies in its world: tile (x, y) of the simulation is the block column (OX + x, OZ + y), units
 * stand on {@link #FLOOR}. Every arena gets a world of its own, so the numbers are the same everywhere.
 */
public final class Place {
    public static final int OX = -9;
    public static final int OZ = -16;
    /** Height units stand at; the ground blocks are one below. */
    public static final int FLOOR = 64;
    /** Height of the towers' walls; guards and avatars stand on top. */
    public static final int TOWER_HEIGHT = 4;
    /** Fliers fly this high above the floor. */
    public static final double AIR = 2.6;

    private Place() {
    }

    public static double x(double tileX) {
        return OX + tileX;
    }

    public static double z(double tileY) {
        return OZ + tileY;
    }

    public static double tileX(double worldX) {
        return worldX - OX;
    }

    public static double tileY(double worldZ) {
        return worldZ - OZ;
    }

    /** Body yaw for a heading of the simulation (0 = towards larger tile y): the game's yaw 0 looks to −z. */
    public static float yaw(double facing) {
        return (float) (facing + Math.PI);
    }

    /** Where a side's avatar stands: on top of its keep, facing the enemy. */
    public static double[] keepTop(int side) {
        int x0 = Arena.sideTileX(side, Arena.KEEP_X0, Arena.KEEP_SIZE);
        int y0 = Arena.sideTileY(side, Arena.KEEP_Y0, Arena.KEEP_SIZE);
        return new double[]{x(x0 + Arena.KEEP_SIZE / 2.0), FLOOR + TOWER_HEIGHT + 1, z(y0 + Arena.KEEP_SIZE / 2.0)};
    }
}
