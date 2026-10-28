package de.hymods.orbisarena.sim;

/** Something flying: arrows, spears, bombs, bolts. */
public final class Projectile {
    final int id;
    final int side;
    final String kind;
    final Body source;
    final Body target;
    final boolean lob;
    double x;
    double y;
    final double startX;
    final double startY;
    double toX;
    double toY;
    final double speed;
    final double damage;
    final double splash;
    final Targets targets;
    boolean alive = true;

    Projectile(int id, int side, String kind, Body source, Body target, boolean lob, double x, double y,
               double speed, double damage, double splash, Targets targets) {
        this.id = id;
        this.side = side;
        this.kind = kind;
        this.source = source;
        this.target = target;
        this.lob = lob;
        this.x = x;
        this.y = y;
        this.startX = x;
        this.startY = y;
        this.toX = target.x;
        this.toY = target.y;
        this.speed = speed;
        this.damage = damage;
        this.splash = splash;
        this.targets = targets;
    }

    public int id() {
        return this.id;
    }

    public int side() {
        return this.side;
    }

    public String kind() {
        return this.kind;
    }

    public Body source() {
        return this.source;
    }

    public Body target() {
        return this.target;
    }

    public boolean lob() {
        return this.lob;
    }

    public double x() {
        return this.x;
    }

    public double y() {
        return this.y;
    }

    public double startX() {
        return this.startX;
    }

    public double startY() {
        return this.startY;
    }

    public double toX() {
        return this.toX;
    }

    public double toY() {
        return this.toY;
    }

    /** 0 at launch, 1 on impact. */
    public double progress() {
        double total = Math.hypot(this.toX - this.startX, this.toY - this.startY);
        if (total < 1e-6) {
            return 1;
        }
        return Math.min(1, Math.hypot(this.x - this.startX, this.y - this.startY) / total);
    }

    public boolean alive() {
        return this.alive;
    }
}
