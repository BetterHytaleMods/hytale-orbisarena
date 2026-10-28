package de.hymods.orbisarena.game;

import com.hypixel.hytale.server.core.universe.world.World;

/** Names of the worlds Orbis Arena makes: one lobby, one world per match. */
public final class Worlds {
    public static final String PREFIX = "orbis_arena_";
    public static final String LOBBY = PREFIX + "lobby";
    public static final String TEMPLATE = "OrbisArena/Arena";

    private Worlds() {
    }

    public static boolean ours(World world) {
        return world != null && world.getName().startsWith(PREFIX);
    }

    public static boolean lobby(World world) {
        return world != null && world.getName().equals(LOBBY);
    }
}
