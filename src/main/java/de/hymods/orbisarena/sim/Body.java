package de.hymods.orbisarena.sim;

/** Anything on the field that has health: units, towers, structures. */
public abstract sealed class Body permits Unit, Tower, Structure {
    private final int id;
    private final int side;
    double x;
    double y;
    double hp;
    final double maxHp;
    boolean alive = true;

    Body(int id, int side, double x, double y, double hp) {
        this.id = id;
        this.side = side;
        this.x = x;
        this.y = y;
        this.hp = hp;
        this.maxHp = hp;
    }

    public int id() {
        return this.id;
    }

    public int side() {
        return this.side;
    }

    public double x() {
        return this.x;
    }

    public double y() {
        return this.y;
    }

    public double hp() {
        return this.hp;
    }

    public double maxHp() {
        return this.maxHp;
    }

    public boolean alive() {
        return this.alive;
    }

    public boolean air() {
        return false;
    }

    /** Towers and buildings: what building attackers go for. */
    public abstract boolean building();

    /** Distance from a point to this body's edge, 0 inside. */
    public abstract double distanceTo(double px, double py);

    /** Distance between the edges of this body and a circle. */
    double gap(double px, double py, double radius) {
        return Math.max(0, this.distanceTo(px, py) - radius);
    }

    static double rectDistance(double px, double py, double x0, double y0, double x1, double y1) {
        double dx = Math.max(Math.max(x0 - px, 0), px - x1);
        double dy = Math.max(Math.max(y0 - py, 0), py - y1);
        return Math.hypot(dx, dy);
    }
}
