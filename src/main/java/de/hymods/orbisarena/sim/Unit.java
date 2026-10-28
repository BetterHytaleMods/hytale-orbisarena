package de.hymods.orbisarena.sim;

import java.util.List;

/** A unit on the field. */
public final class Unit extends Body {
    final UnitDef def;
    final int modelIndex;
    final String cardId;
    double radius;
    double deployLeft;
    boolean burrowed;
    double destX;
    double destY;

    Body target;
    boolean locked;
    boolean inRange;
    double retargetIn;
    double attackTimer;
    double facing;
    boolean moving;

    List<int[]> path;
    int pathIndex;
    Body pathFor;
    double repathIn;

    double slowUntil;
    double slowMul = 1;
    double rootUntil;
    double hasteUntil;
    double hasteMul = 1;
    double walked;
    boolean charging;
    double healIn;

    Unit(int id, int side, UnitDef def, int modelIndex, String cardId, double x, double y, double deploy) {
        super(id, side, x, y, def.hp());
        this.def = def;
        this.modelIndex = modelIndex;
        this.cardId = cardId;
        this.radius = def.radius();
        this.deployLeft = deploy;
        this.facing = side == 0 ? 0 : Math.PI;
        this.healIn = def.heal() != null ? def.heal().every() : 0;
    }

    public UnitDef def() {
        return this.def;
    }

    public String model() {
        return this.def.model(this.modelIndex);
    }

    /** The card that brought it, or null for spawned and summoned units. */
    public String cardId() {
        return this.cardId;
    }

    public double radius() {
        return this.radius;
    }

    public boolean deploying() {
        return this.deployLeft > 0;
    }

    public boolean burrowed() {
        return this.burrowed;
    }

    /** Heading in radians: 0 looks towards larger y, π/2 towards larger x. */
    public double facing() {
        return this.facing;
    }

    public boolean moving() {
        return this.moving;
    }

    public boolean charging() {
        return this.charging;
    }

    public boolean slowed(double now) {
        return now < this.slowUntil || now < this.rootUntil;
    }

    public boolean hasted(double now) {
        return now < this.hasteUntil;
    }

    public Body target() {
        return this.target;
    }

    @Override
    public boolean air() {
        return this.def.air();
    }

    @Override
    public boolean building() {
        return false;
    }

    @Override
    public double distanceTo(double px, double py) {
        return Math.max(0, Math.hypot(px - this.x, py - this.y) - this.radius);
    }
}
