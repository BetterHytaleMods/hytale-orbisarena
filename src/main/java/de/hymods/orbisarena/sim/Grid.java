package de.hymods.orbisarena.sim;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.PriorityQueue;
import java.util.function.BiPredicate;

/** What stands on each tile, and paths across them for ground units. */
public final class Grid {
    private static final int W = Arena.WIDTH;
    private static final int L = Arena.LENGTH;
    /** Extra cost of walking through an enemy wall or building, i.e. of breaking it. */
    static final double BREAK_COST = 10;
    private static final int MAX_EXPANSIONS = 4000;

    private final Tower[] towers = new Tower[W * L];
    private final Structure[] blocks = new Structure[W * L];
    private final int[] bridges = new int[W * L];

    private static int index(int tx, int ty) {
        return ty * W + tx;
    }

    void put(Tower tower) {
        for (int tx = tower.x0; tx < tower.x0 + tower.size; tx++) {
            for (int ty = tower.y0; ty < tower.y0 + tower.size; ty++) {
                this.towers[index(tx, ty)] = tower;
            }
        }
    }

    void remove(Tower tower) {
        for (int tx = tower.x0; tx < tower.x0 + tower.size; tx++) {
            for (int ty = tower.y0; ty < tower.y0 + tower.size; ty++) {
                if (this.towers[index(tx, ty)] == tower) {
                    this.towers[index(tx, ty)] = null;
                }
            }
        }
    }

    void put(Structure s) {
        for (int tx = s.x0; tx < s.x0 + s.w; tx++) {
            for (int ty = s.y0; ty < s.y0 + s.h; ty++) {
                if (s.def.kind() == StructureDef.Kind.BRIDGE) {
                    this.bridges[index(tx, ty)]++;
                } else if (s.blocks()) {
                    this.blocks[index(tx, ty)] = s;
                }
            }
        }
    }

    void remove(Structure s) {
        for (int tx = s.x0; tx < s.x0 + s.w; tx++) {
            for (int ty = s.y0; ty < s.y0 + s.h; ty++) {
                if (s.def.kind() == StructureDef.Kind.BRIDGE) {
                    this.bridges[index(tx, ty)]--;
                } else if (this.blocks[index(tx, ty)] == s) {
                    this.blocks[index(tx, ty)] = null;
                }
            }
        }
    }

    public Tower tower(int tx, int ty) {
        return Arena.inside(tx, ty) ? this.towers[index(tx, ty)] : null;
    }

    public Structure block(int tx, int ty) {
        return Arena.inside(tx, ty) ? this.blocks[index(tx, ty)] : null;
    }

    /** River without a bridge. */
    public boolean water(int tx, int ty) {
        return Arena.isRiver(ty) && !Arena.isBridge(tx, ty) && this.bridges[index(tx, ty)] == 0;
    }

    public boolean builtBridge(int tx, int ty) {
        return Arena.inside(tx, ty) && this.bridges[index(tx, ty)] > 0;
    }

    /** Nothing can stand here: outside, water, a tower, a wall or a building. */
    public boolean solid(int tx, int ty) {
        return !Arena.inside(tx, ty) || water(tx, ty) || this.towers[index(tx, ty)] != null
                || this.blocks[index(tx, ty)] != null;
    }

    /** Free ground for placing something new. */
    public boolean free(int tx, int ty) {
        return !solid(tx, ty);
    }

    /** Cost of entering a tile for a ground unit of this side; infinite where it cannot go. */
    double cost(int side, int tx, int ty) {
        if (!Arena.inside(tx, ty) || water(tx, ty) || this.towers[index(tx, ty)] != null) {
            return Double.POSITIVE_INFINITY;
        }
        Structure block = this.blocks[index(tx, ty)];
        if (block != null) {
            return block.side() == side ? Double.POSITIVE_INFINITY : 1 + BREAK_COST;
        }
        return 1;
    }

    boolean passable(int side, int tx, int ty) {
        return this.cost(side, tx, ty) == 1;
    }

    /** Whether a ground unit of this side can walk straight from one point to another without breaking anything. */
    boolean clearLine(int side, double x0, double y0, double x1, double y1) {
        double dist = Math.hypot(x1 - x0, y1 - y0);
        int steps = Math.max(1, (int) Math.ceil(dist / 0.25));
        for (int i = 1; i <= steps; i++) {
            double t = (double) i / steps;
            int tx = (int) Math.floor(x0 + (x1 - x0) * t);
            int ty = (int) Math.floor(y0 + (y1 - y0) * t);
            if (!this.passable(side, tx, ty)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Cheapest tile path for a ground unit from a point to any tile the goal accepts, or null. The first entry
     * is the start tile.
     */
    List<int[]> path(int side, double fromX, double fromY, BiPredicate<Integer, Integer> goal) {
        int sx = clamp((int) Math.floor(fromX), W);
        int sy = clamp((int) Math.floor(fromY), L);
        double[] best = new double[W * L];
        int[] came = new int[W * L];
        java.util.Arrays.fill(best, Double.POSITIVE_INFINITY);
        java.util.Arrays.fill(came, -1);
        PriorityQueue<double[]> open = new PriorityQueue<>((a, b) -> Double.compare(a[0], b[0]));
        int start = index(sx, sy);
        best[start] = 0;
        open.add(new double[]{0, start});
        int expansions = 0;
        while (!open.isEmpty() && expansions++ < MAX_EXPANSIONS) {
            double[] head = open.poll();
            int at = (int) head[1];
            double g = best[at];
            if (head[0] > g + 1e-9) {
                continue;
            }
            int ax = at % W;
            int ay = at / W;
            if (goal.test(ax, ay)) {
                List<int[]> path = new ArrayList<>();
                for (int i = at; i != -1; i = came[i]) {
                    path.add(new int[]{i % W, i / W});
                }
                Collections.reverse(path);
                return path;
            }
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    if (dx == 0 && dy == 0) {
                        continue;
                    }
                    int nx = ax + dx;
                    int ny = ay + dy;
                    double step = this.cost(side, nx, ny);
                    if (step == Double.POSITIVE_INFINITY) {
                        continue;
                    }
                    if (dx != 0 && dy != 0) {
                        // No cutting corners past something solid.
                        if (this.cost(side, ax + dx, ay) == Double.POSITIVE_INFINITY
                                || this.cost(side, ax, ay + dy) == Double.POSITIVE_INFINITY) {
                            continue;
                        }
                        step *= Math.sqrt(2);
                    }
                    int next = index(nx, ny);
                    double ng = g + step;
                    if (ng < best[next]) {
                        best[next] = ng;
                        came[next] = at;
                        open.add(new double[]{ng, next});
                    }
                }
            }
        }
        return null;
    }

    private static int clamp(int v, int size) {
        return Math.max(0, Math.min(size - 1, v));
    }
}
