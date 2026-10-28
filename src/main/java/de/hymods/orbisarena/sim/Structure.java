package de.hymods.orbisarena.sim;

/** What a building or build card put on the field. */
public final class Structure extends Body {
    final StructureDef def;
    final String cardId;
    final int x0;
    final int y0;
    final int w;
    final int h;
    double age;
    double spawnIn;
    Body target;
    double attackTimer;
    double hedgeDamage;

    Structure(int id, int side, StructureDef def, String cardId, int x0, int y0, int w, int h, double hp) {
        super(id, side, x0 + w / 2.0, y0 + h / 2.0, Math.max(hp, 1));
        this.def = def;
        this.cardId = cardId;
        this.x0 = x0;
        this.y0 = y0;
        this.w = w;
        this.h = h;
        this.spawnIn = def.spawn() != null ? def.spawn().every() / 2 : 0;
    }

    public StructureDef def() {
        return this.def;
    }

    public String cardId() {
        return this.cardId;
    }

    public int x0() {
        return this.x0;
    }

    public int y0() {
        return this.y0;
    }

    public int w() {
        return this.w;
    }

    public int h() {
        return this.h;
    }

    public double age() {
        return this.age;
    }

    public Body target() {
        return this.target;
    }

    /** Whether units stand in its way: walls, buildings and kegs. */
    public boolean blocks() {
        return switch (this.def.kind()) {
            case WALL, BUILDING, KEG -> true;
            case HEDGE, BRIDGE -> false;
        };
    }

    /** Whether it can be attacked. */
    public boolean hittable() {
        return this.blocks();
    }

    @Override
    public boolean building() {
        return this.def.kind() == StructureDef.Kind.BUILDING;
    }

    @Override
    public double distanceTo(double px, double py) {
        return rectDistance(px, py, this.x0, this.y0, this.x0 + this.w, this.y0 + this.h);
    }

    boolean covers(double px, double py, double margin) {
        return px >= this.x0 - margin && px <= this.x0 + this.w + margin
                && py >= this.y0 - margin && py <= this.y0 + this.h + margin;
    }
}
