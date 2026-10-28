package de.hymods.orbisarena.sim;

/**
 * The fixed layout of every arena, in tiles (one tile is one block).
 *
 * <p>x runs across the arena (0 = left of side 0), y runs from side 0's back wall (y = 0) to side 1's
 * back wall (y = {@link #LENGTH}). The river covers the rows {@link #RIVER_Y0} and {@link #RIVER_Y0} + 1.
 * Everything of side 1 is side 0's layout turned by 180 degrees around the arena's center.
 */
public final class Arena {
    public static final int WIDTH = 18;
    public static final int LENGTH = 32;
    public static final int RIVER_Y0 = 15;
    public static final int RIVER_Y1 = 17;
    /** Bridge columns of side 0's left lane; the right bridge is the mirror image. */
    public static final int BRIDGE_X0 = 2;
    public static final int BRIDGE_WIDTH = 3;

    public static final int WATCHTOWER_SIZE = 3;
    public static final int KEEP_SIZE = 4;
    /** Lower-left tile of side 0's left watchtower. */
    public static final int WATCHTOWER_X0 = 2;
    public static final int WATCHTOWER_Y0 = 5;
    /** Lower-left tile of side 0's keep. */
    public static final int KEEP_X0 = 7;
    public static final int KEEP_Y0 = 1;

    /** How far a destroyed watchtower opens the enemy half for deploying, in rows past the river. */
    public static final int POCKET_DEPTH = 4;

    private Arena() {
    }

    public static double centerX() {
        return WIDTH / 2.0;
    }

    public static boolean inside(int tx, int ty) {
        return tx >= 0 && tx < WIDTH && ty >= 0 && ty < LENGTH;
    }

    public static boolean inside(double x, double y) {
        return x >= 0 && x < WIDTH && y >= 0 && y < LENGTH;
    }

    public static boolean isRiver(int ty) {
        return ty >= RIVER_Y0 && ty < RIVER_Y1;
    }

    public static boolean isBridge(int tx, int ty) {
        if (!isRiver(ty)) {
            return false;
        }
        return (tx >= BRIDGE_X0 && tx < BRIDGE_X0 + BRIDGE_WIDTH)
                || (tx >= WIDTH - BRIDGE_X0 - BRIDGE_WIDTH && tx < WIDTH - BRIDGE_X0);
    }

    /** The side whose half holds this row, or -1 for the river. */
    public static int halfOf(double y) {
        if (y < RIVER_Y0) {
            return 0;
        }
        if (y >= RIVER_Y1) {
            return 1;
        }
        return -1;
    }

    /** 0 for the left lane (seen from side 0), 1 for the right lane. */
    public static int laneOf(double x) {
        return x < centerX() ? 0 : 1;
    }

    /** Turns side 0's layout into the given side's: the same for side 0, rotated by 180 degrees for side 1. */
    public static double sideX(int side, double x) {
        return side == 0 ? x : WIDTH - x;
    }

    public static double sideY(int side, double y) {
        return side == 0 ? y : LENGTH - y;
    }

    /** Lower-left tile of a footprint of size w × h placed in side 0 coordinates at (x0, y0), for the given side. */
    public static int sideTileX(int side, int x0, int w) {
        return side == 0 ? x0 : WIDTH - x0 - w;
    }

    public static int sideTileY(int side, int y0, int h) {
        return side == 0 ? y0 : LENGTH - y0 - h;
    }

    /** +1 if the side attacks towards larger y, -1 otherwise. */
    public static int forward(int side) {
        return side == 0 ? 1 : -1;
    }
}
