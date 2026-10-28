package de.hymods.orbisarena.sim;

/** A watchtower or a keep. The keep's weapon is the avatar standing on it. */
public final class Tower extends Body {
    final boolean keep;
    final int lane;
    final int x0;
    final int y0;
    final int size;
    final Weapon weapon;
    Body target;
    double attackTimer;

    Tower(int id, int side, boolean keep, int lane, int x0, int y0, int size, double hp, Weapon weapon) {
        super(id, side, x0 + size / 2.0, y0 + size / 2.0, hp);
        this.keep = keep;
        this.lane = lane;
        this.x0 = x0;
        this.y0 = y0;
        this.size = size;
        this.weapon = weapon;
    }

    public boolean keep() {
        return this.keep;
    }

    /** 0 left, 1 right (seen from side 0); -1 for the keep. */
    public int lane() {
        return this.lane;
    }

    public int x0() {
        return this.x0;
    }

    public int y0() {
        return this.y0;
    }

    public int size() {
        return this.size;
    }

    public Body target() {
        return this.target;
    }

    @Override
    public boolean building() {
        return true;
    }

    @Override
    public double distanceTo(double px, double py) {
        return rectDistance(px, py, this.x0, this.y0, this.x0 + this.size, this.y0 + this.size);
    }
}
